package flare.client.app.singbox

import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import flare.client.app.data.SettingsManager
import io.nekohasekai.libbox.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the sing-box VPN core using the libbox AAR library.
 *
 * Architecture (matches SFA / hiddify-next pattern):
 * 1. We start sing-box with a config that includes a TUN inbound (auto_route: true).
 * 2. sing-box calls openTun(TunOptions) when it wants to open the TUN device.
 * 3. We build VpnService.Builder from the TunOptions sing-box provides and return the fd.
 * 4. sing-box owns the TUN lifecycle; we only provide the fd.
 */
object SingBoxManager {

    private const val TAG = "SingBoxManager"
    private var boxService: CommandServer? = null
    private var tunPfd: ParcelFileDescriptor? = null

    var isRunning: Boolean = false
        private set

    var startTime: Long = 0L
        private set

    private var setupDone = false
    private var logFile: File? = null
    private var logTailThread: Thread? = null

    private fun ensureSetup(context: Context) {
        if (setupDone) return
        try {
            val version =
                    try {
                        Libbox.version()
                    } catch (e: Exception) {
                        "unknown: ${e.message}"
                    }
            Log.i(TAG, "sing-box libbox version: $version")

            val options =
                    SetupOptions().apply {
                        basePath = context.filesDir.absolutePath
                        workingPath = context.filesDir.absolutePath
                        tempPath = context.cacheDir.absolutePath
                        fixAndroidStack = true
                        logMaxLines = 500
                    }
            Libbox.setup(options)

            val lf = File(context.filesDir, "sing-box.log")
            logFile = lf
            try {
                lf.delete()
            } catch (_: Exception) {}
            Log.i(TAG, "sing-box log file: ${lf.absolutePath}")

            // Catch Go runtime panics
            try {
                Libbox.redirectStderr(lf.absolutePath)
                Log.i(TAG, "sing-box stderr redirected")
            } catch (e: Exception) {
                Log.w(TAG, "redirectStderr failed (non-fatal): ${e.message}")
            }

            setupDone = true
            if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "Libbox.setup() done")
        } catch (e: Exception) {
            Log.e(TAG, "Libbox.setup() failed: ${e.message}", e)
        }
    }

    fun start(configContent: String, context: Context): Boolean {
        if (isRunning) {
            Log.w(TAG, "sing-box is already running")
            return true
        }

        ensureSetup(context)
        LocalResolver.init(context)

        val vpnService: VpnService? = context as? VpnService

        return try {
            val handler =
                    object : CommandServerHandler {
                        override fun serviceStop() {}
                        override fun serviceReload() {}
                        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
                        override fun setSystemProxyEnabled(enabled: Boolean) {}
                        override fun writeDebugMessage(message: String?) {
                            if (!message.isNullOrBlank()) Log.i(TAG, "[sb] $message")
                        }
                    }

            val platform =
                    object : PlatformInterface {
                        /**
                         * Called by sing-box for every outbound socket fd it creates. We protect it
                         * so traffic bypasses the VPN tunnel (no routing loop).
                         */
                        override fun autoDetectInterfaceControl(fd: Int) {
                            vpnService?.protect(fd)
                        }

                        override fun clearDNSCache() {}
                        override fun closeDefaultInterfaceMonitor(
                                listener: InterfaceUpdateListener?
                        ) {}
                        override fun findConnectionOwner(
                                ipProtocol: Int,
                                sourceAddress: String?,
                                sourcePort: Int,
                                destinationAddress: String?,
                                destinationPort: Int
                        ): ConnectionOwner? = null

                        override fun getInterfaces(): NetworkInterfaceIterator? = null
                        override fun includeAllNetworks(): Boolean = false
                        override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

                        /**
                         * sing-box calls this when it wants to open the TUN interface. We build
                         * VpnService.Builder from the TunOptions sing-box provides. This is the
                         * correct SFA/hiddify pattern — NOT pre-building the TUN.
                         */
                        override fun openTun(options: TunOptions?): Int {
                            Log.i(
                                    TAG,
                                    "openTun called, mtu=${options?.mtu}, autoRoute=${options?.autoRoute}"
                            )

                            if (vpnService == null) {
                                Log.e(TAG, "openTun: context is not a VpnService")
                                return -1
                            }

                            try {
                                val builder = vpnService.Builder().setSession("Flare Client")

                                try {
                                    val settings = SettingsManager(vpnService)
                                    if (settings.isSplitTunnelingEnabled &&
                                                    settings.splitTunnelingApps.isNotEmpty()
                                    ) {
                                        // Prevent conflict with disallowed apps
                                        for (pkg in settings.splitTunnelingApps) {
                                            try {
                                                builder.addAllowedApplication(pkg)
                                            } catch (e: Exception) {
                                                Log.e(
                                                        TAG,
                                                        "Failed to add allowed application: $pkg",
                                                        e
                                                )
                                            }
                                        }
                                    } else {
                                        builder.addDisallowedApplication(vpnService.packageName)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to configure VPN apps", e)
                                }

                                options?.let { opts ->
                                    builder.setMtu(opts.mtu)

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        builder.setMetered(false)
                                    }

                                    val inet4 = opts.inet4Address
                                    while (inet4.hasNext()) {
                                        val addr = inet4.next()
                                        builder.addAddress(addr.address(), addr.prefix())
                                    }

                                    val inet6 = opts.inet6Address
                                    while (inet6.hasNext()) {
                                        val addr = inet6.next()
                                        builder.addAddress(addr.address(), addr.prefix())
                                    }

                                    if (opts.autoRoute) {
                                        val dnsAddr =
                                                try {
                                                    opts.dnsServerAddress?.value
                                                } catch (e: Exception) {
                                                    null
                                                }
                                        Log.i(TAG, "openTun: dnsServerAddress=$dnsAddr")
                                        if (!dnsAddr.isNullOrBlank()) {
                                            builder.addDnsServer(dnsAddr)
                                        } else {
                                            Log.w(
                                                    TAG,
                                                    "openTun: dnsServerAddress is null/empty, using 1.1.1.1 fallback"
                                            )
                                            builder.addDnsServer("1.1.1.1")
                                        }

                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            // Android 13+ route configuration
                                            val v4routes = opts.inet4RouteAddress
                                            if (v4routes.hasNext()) {
                                                while (v4routes.hasNext()) {
                                                    val r = v4routes.next()
                                                    builder.addRoute(r.address(), r.prefix())
                                                }
                                            } else {
                                                builder.addRoute("0.0.0.0", 0)
                                            }

                                            val v6routes = opts.inet6RouteAddress
                                            if (v6routes.hasNext()) {
                                                while (v6routes.hasNext()) {
                                                    val r = v6routes.next()
                                                    builder.addRoute(r.address(), r.prefix())
                                                }
                                            } else {
                                                builder.addRoute("::", 0)
                                            }
                                        } else {
                                            // Pre-Android 13 route configuration
                                            val v4range = opts.inet4RouteRange
                                            if (v4range.hasNext()) {
                                                while (v4range.hasNext()) {
                                                    val r = v4range.next()
                                                    builder.addRoute(r.address(), r.prefix())
                                                }
                                            } else {
                                                builder.addRoute("0.0.0.0", 0)
                                            }

                                            val v6range = opts.inet6RouteRange
                                            if (v6range.hasNext()) {
                                                while (v6range.hasNext()) {
                                                    val r = v6range.next()
                                                    builder.addRoute(r.address(), r.prefix())
                                                }
                                            } else {
                                                builder.addRoute("::", 0)
                                            }
                                        }
                                    } else {
                                        builder.addRoute("0.0.0.0", 0)
                                        builder.addRoute("::", 0)
                                        builder.addDnsServer("1.1.1.1")
                                        builder.addDnsServer("8.8.8.8")
                                    }
                                }
                                        ?: run {
                                            builder.addAddress("172.19.0.1", 30)
                                            builder.addAddress("fdfe:dcba:9876::1", 126)
                                            builder.addRoute("0.0.0.0", 0)
                                            builder.addRoute("::", 0)
                                            builder.addDnsServer("1.1.1.1")
                                            builder.addDnsServer("8.8.8.8")
                                        }

                                val pfd = builder.establish()
                                if (pfd == null) {
                                    Log.e(
                                            TAG,
                                            "openTun: establish() returned null — permission missing?"
                                    )
                                    return -1
                                }

                                // Close old pfd if any (shouldn't happen normally)
                                tunPfd?.close()
                                tunPfd = pfd

                                Log.i(TAG, "openTun: established fd=${pfd.fd}")
                                return pfd.fd
                            } catch (e: Exception) {
                                Log.e(TAG, "openTun failed: ${e.message}", e)
                                return -1
                            }
                        }

                        override fun readWIFIState(): WIFIState? = null
                        override fun sendNotification(notification: Notification?) {}
                        override fun startDefaultInterfaceMonitor(
                                listener: InterfaceUpdateListener?
                        ) {}
                        override fun systemCertificates(): StringIterator? = null
                        override fun underNetworkExtension(): Boolean = false
                        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
                        override fun useProcFS(): Boolean = true
                    }

            boxService = Libbox.newCommandServer(handler, platform)

            val logFilePath = logFile?.absolutePath
            var patchedConfig =
                    if (logFilePath != null) {
                        injectLogOutput(configContent, logFilePath)
                    } else configContent

            patchedConfig = injectAdvancedSettings(patchedConfig, context)

            Log.i(TAG, "Calling startOrReloadService…")
            boxService?.startOrReloadService(patchedConfig, OverrideOptions())
            if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "startOrReloadService completed")

            startLogTail()

            isRunning = true
            startTime = System.currentTimeMillis()
            if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "sing-box started via AAR")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sing-box: ${e.message}", e)
            boxService = null
            isRunning = false
            false
        }
    }

    fun stop() {
        stopLogTail()
        try {
            boxService?.closeService()
            boxService?.close()
            if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "sing-box stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sing-box: ${e.message}", e)
        } finally {
            boxService = null
            isRunning = false
            startTime = 0L
            try {
                tunPfd?.close()
            } catch (_: Exception) {}
            tunPfd = null
        }
    }

    fun getTraffic(callback: (Long, Long) -> Unit) {
        callback(0L, 0L)
    }


    private fun startLogTail() {
        val file = logFile ?: return
        stopLogTail()
        val t =
                Thread(
                        {
                            try {
                                // Wait for sing-box to start writing logs
                                Thread.sleep(800)
                                if (!file.exists()) {
                                    Log.w(
                                            TAG,
                                            "[sb-core] Log file not created: ${file.absolutePath}"
                                    )
                                    return@Thread
                                }
                                val reader = BufferedReader(FileReader(file))
                                Log.i(
                                        TAG,
                                        "[sb-core] === Log tail started, size=${file.length()} ==="
                                )
                                // Read from beginning — file is cleared before each session
                                while (!Thread.currentThread().isInterrupted) {
                                    val line = reader.readLine()
                                    if (line != null) {
                                        // Removed Log.i("SB-Core", line) to reduce noise
                                    } else {
                                        Thread.sleep(150)
                                    }
                                }
                                reader.close()
                            } catch (_: InterruptedException) {
                                // normal stop
                            } catch (e: Exception) {
                                Log.w(TAG, "Log tail error: ${e.message}")
                            }
                            Log.i(TAG, "[sb-core] === Log tail stopped ===")
                        },
                        "sb-log-tail"
                )
        t.isDaemon = true
        t.start()
        logTailThread = t
    }

    private fun stopLogTail() {
        logTailThread?.interrupt()
        logTailThread = null
    }

    /**
     * Copies the sing-box internal log to Downloads directory for easy access without root. Call
     * this after stopping the VPN to retrieve logs.
     */
    fun copySingBoxLog(): String? {
        val src = logFile ?: return null
        if (!src.exists()) return null
        return try {
            val dst =
                    File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                            ),
                            "singbox_${System.currentTimeMillis()}.log"
                    )
            src.copyTo(dst, overwrite = true)
            Log.i(TAG, "sing-box log copied to: ${dst.absolutePath}")
            dst.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy sing-box log: ${e.message}")
            null
        }
    }

    /**
     * Patches the sing-box JSON config to add `log.output` path.
     *
     * In sing-box 1.11+, internal logs go through the internal log system (not stderr). Setting
     * `log.output` in JSON config makes the core write logs to that file. Without this,
     * sing-box.log is always empty even with redirectStderr().
     */
    private fun injectLogOutput(configJson: String, logFilePath: String): String {
        return try {
            val obj = JSONObject(configJson)
            val log = obj.optJSONObject("log") ?: JSONObject()
            log.put("output", logFilePath)
            log.put("level", "debug")
            obj.put("log", log)
            obj.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            Log.w(TAG, "injectLogOutput failed (non-fatal): ${e.message}")
            configJson
        }
    }

    private fun injectAdvancedSettings(configJson: String, context: Context): String {
        return try {
            val settings = SettingsManager(context)
            val obj = JSONObject(configJson)

            val remoteDnsUrl = settings.remoteDnsUrl
            if (remoteDnsUrl.isNotBlank()) {
                val dns = obj.optJSONObject("dns")
                if (dns != null) {
                    val servers = dns.optJSONArray("servers")
                    if (servers != null) {
                        for (i in 0 until servers.length()) {
                            val server = servers.optJSONObject(i)
                            if (server != null && server.optString("tag") == "dns-remote") {
                                server.put("address", remoteDnsUrl)
                                Log.i(
                                        TAG,
                                        "injectAdvancedSettings: overridden dns-remote address to $remoteDnsUrl"
                                )
                                break
                            }
                        }
                    }
                }
            }

            val mtuValue = settings.mtu.toIntOrNull() ?: 1500
            val stackValue = settings.tunStack
            val fakeIpEnabled = settings.isFakeIpEnabled
            val inbounds = obj.optJSONArray("inbounds")
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inb = inbounds.optJSONObject(i) ?: continue
                    if (inb.optString("type") == "tun") {
                        inb.put("mtu", mtuValue)
                        inb.put("stack", stackValue)
                        Log.i(
                                TAG,
                                "injectAdvancedSettings: set TUN mtu=$mtuValue, stack=$stackValue"
                        )
                        break
                    }
                }
            }

            if (fakeIpEnabled) {
                val dns = obj.optJSONObject("dns")
                if (dns != null) {
                    val servers =
                            dns.optJSONArray("servers")
                                    ?: JSONArray().also { dns.put("servers", it) }

                    var hasFakeIp = false
                    for (i in 0 until servers.length()) {
                        if (servers.optJSONObject(i)?.optString("tag") == "dns-fakeip") {
                            hasFakeIp = true
                            break
                        }
                    }
                    if (!hasFakeIp) {
                        servers.put(
                                JSONObject().apply {
                                    put("type", "fakeip")
                                    put("tag", "dns-fakeip")
                                    put("inet4_range", "198.18.0.0/15")
                                    put("inet6_range", "fc00::/18")
                                }
                        )
                        Log.i(TAG, "injectAdvancedSettings: added dns-fakeip server")
                    }

                    // Append query_type rule (after existing rules, so per-domain overrides win)
                    val dnsRules =
                            dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
                    var hasQueryTypeRule = false
                    for (i in 0 until dnsRules.length()) {
                        val r = dnsRules.optJSONObject(i) ?: continue
                        val qt = r.optJSONArray("query_type")
                        if (qt != null && r.optString("server") == "dns-fakeip") {
                            hasQueryTypeRule = true
                            break
                        }
                    }
                    if (!hasQueryTypeRule) {
                        dnsRules.put(
                                JSONObject().apply {
                                    put("query_type", JSONArray().put("A").put("AAAA"))
                                    put("server", "dns-fakeip")
                                }
                        )
                        Log.i(TAG, "injectAdvancedSettings: added fakeip query_type DNS rule")
                    }
                } else {
                    Log.w(
                            TAG,
                            "injectAdvancedSettings: no dns section found, skipping fakeip injection"
                    )
                }
            }

            val outbounds =
                    obj.optJSONArray("outbounds") ?: return obj.toString(2).replace("\\/", "/")

            // Find proxy outbound index
            var proxyIndex = -1
            for (i in 0 until outbounds.length()) {
                if (outbounds.optJSONObject(i)?.optString("tag") == "proxy") {
                    proxyIndex = i
                    break
                }
            }

            if (proxyIndex == -1) {
                Log.w(
                        TAG,
                        "injectAdvancedSettings: 'proxy' outbound not found, skipping frag/noise"
                )
                return obj.toString(2).replace("\\/", "/")
            }

            val proxyOutbound = outbounds.getJSONObject(proxyIndex)

            if (settings.isFragmentationEnabled) {
                val tls = proxyOutbound.optJSONObject("tls")
                if (tls != null) {
                    // Map packet type → which fragment mode to enable
                    val packetType = settings.packetType
                    when (packetType) {
                        "1-3" -> {
                            // TCP-level record fragmentation only
                            tls.put("record_fragment", true)
                        }
                        else -> {
                            // "tlshello" or anything else → TLS handshake fragmentation (default)
                            tls.put("fragment", true)
                            tls.put("record_fragment", true) // also enable record for max compat
                        }
                    }

                    // Use fragmentInterval directly as ms delay (single number, e.g. "10")
                    val intervalMs = settings.fragmentInterval.trim().toIntOrNull() ?: 10
                    tls.put("fragment_fallback_delay", "${intervalMs}ms")

                    Log.i(
                            TAG,
                            "injectAdvancedSettings: fragment injected " +
                                    "(packetType=$packetType, delay=${intervalMs}ms)"
                    )
                } else {
                    Log.w(
                            TAG,
                            "injectAdvancedSettings: proxy has no TLS block, skipping fragmentation"
                    )
                }
            }

            if (settings.isMuxEnabled) {
                val maxStreams = settings.muxMaxStreams.toIntOrNull()?.coerceIn(1, 128) ?: 8
                val protocol = settings.muxProtocol.ifBlank { "smux" }
                val padding = settings.muxPadding

                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val type = ob.optString("type")
                    if (type == "direct" || type == "block" || type == "dns") continue

                    // Vision (xtls-rprx-vision) is incompatible with mux — remove flow field
                    if (ob.has("flow")) {
                        ob.put("flow", "")
                        Log.i(
                                TAG,
                                "injectAdvancedSettings: cleared 'flow' on outbound '${ob.optString("tag")}' (mux requires no flow)"
                        )
                    }

                    ob.put(
                            "multiplex",
                            JSONObject().apply {
                                put("enabled", true)
                                put("protocol", protocol)
                                put("max_streams", maxStreams)
                                put("padding", padding)
                            }
                    )
                    Log.i(
                            TAG,
                            "injectAdvancedSettings: mux injected on '${ob.optString("tag")}' " +
                                    "(protocol=$protocol, max_streams=$maxStreams, padding=$padding)"
                    )
                }
            }

            obj.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            Log.e(TAG, "injectAdvancedSettings failed: ${e.message}", e)
            configJson
        }
    }
}
