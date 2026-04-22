package flare.client.app.singbox

import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import flare.client.app.data.SettingsManager
import io.nekohasekai.libbox.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SingBoxManager {

    private const val TAG = "SingBoxManager"
    private var boxService: CommandServer? = null
    private val mutex = Mutex()
    private var tunPfd: ParcelFileDescriptor? = null

    var isRunning: Boolean = false
        private set

    var startTime: Long = 0L
        private set

    private var setupDone = false
    private var logFile: File? = null

    private fun ensureSetup(context: Context) {
        if (setupDone) return
        try {
            val settings = SettingsManager(context)
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
                if (settings.isCoreLogEnabled) {
                    lf.delete()
                }
            } catch (_: Exception) {}
            
            if (settings.isCoreLogEnabled) {
                Log.i(TAG, "sing-box log file: ${lf.absolutePath}")
                try {
                    Libbox.redirectStderr(lf.absolutePath)
                    Log.i(TAG, "sing-box stderr redirected")
                } catch (e: Exception) {
                    Log.w(TAG, "redirectStderr failed (non-fatal): ${e.message}")
                }
            }

            setupDone = true
            if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "Libbox.setup() done")
        } catch (e: Exception) {
            Log.e(TAG, "Libbox.setup() failed: ${e.message}", e)
        }
    }

    suspend fun start(configContent: String, context: Context): Boolean {
        return mutex.withLock {
            if (isRunning) {
                Log.w(TAG, "sing-box is already running")
                return@withLock true
            }

        ensureSetup(context)
        LocalResolver.init(context)

        val vpnService: VpnService? = context as? VpnService

        try {
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
                                val builder = vpnService.Builder().setSession("Flare")

                                try {
                                    val settings = SettingsManager(vpnService)
                                    val modeApps = settings.splitTunnelingModeApps
                                    if (settings.isSplitTunnelingEnabled && settings.splitTunnelingApps.isNotEmpty()) {
                                        for (pkg in settings.splitTunnelingApps) {
                                            try {
                                                if (modeApps == "whitelist") {
                                                    builder.addAllowedApplication(pkg)
                                                } else {
                                                    builder.addDisallowedApplication(pkg)
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to add application: $pkg", e)
                                            }
                                        }
                                        if (modeApps == "blacklist") {
                                            builder.addDisallowedApplication(vpnService.packageName)
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
                                        Log.d(TAG, "openTun: added address ${addr.address()}/${addr.prefix()}")
                                    }

                                    val inet6 = opts.inet6Address
                                    while (inet6.hasNext()) {
                                        val addr = inet6.next()
                                        builder.addAddress(addr.address(), addr.prefix())
                                        Log.d(TAG, "openTun: added IPv6 address ${addr.address()}/${addr.prefix()}")
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

            try {
                boxService?.start()
                if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "CommandServer started")
            } catch (e: Exception) {
                Log.e(TAG, "CommandServer.start() failed: ${e.message}", e)
            }

            val settings = SettingsManager(context)
            val logFilePath = logFile?.absolutePath
            var patchedConfig =
                    if (logFilePath != null) {
                        injectLogOutput(configContent, logFilePath, settings)
                    } else configContent

            patchedConfig = injectAdvancedSettings(patchedConfig, context)

            Log.i(TAG, "Calling startOrReloadService…")
            try {
                boxService?.startOrReloadService(patchedConfig, OverrideOptions())
                if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "startOrReloadService completed")
            } catch (e: Exception) {
                Log.e(TAG, "startOrReloadService failed: ${e.message}", e)
                throw e
            }

            isRunning = true
            startTime = SystemClock.elapsedRealtime()
            if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "sing-box started via AAR")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sing-box: ${e.message}", e)
            boxService = null
            isRunning = false
            false
        }
    }
    }

    suspend fun stop() {
        mutex.withLock {
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
            return@withLock
        }
    }

    fun getTraffic(callback: (Long, Long) -> Unit) {
        callback(0L, 0L)
    }

    

    

    private fun injectLogOutput(configJson: String, logFilePath: String, settings: SettingsManager): String {
        return try {
            val obj = JSONObject(configJson)
            val log = obj.optJSONObject("log") ?: JSONObject()
            
            val level = settings.coreLogLevel
            if (settings.isCoreLogEnabled && level != "none") {
                log.put("disabled", false)
                log.put("level", level)
                log.put("output", logFilePath)
            } else {
                log.put("disabled", true)
                log.remove("level")
                log.remove("output")
            }
            
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

            
            
            
            run {
                val dns = obj.optJSONObject("dns")
                val servers = dns?.optJSONArray("servers")
                val outbounds = obj.optJSONArray("outbounds")
                if (dns != null && servers != null && outbounds != null) {
                    val hasProxyOutbound = (0 until outbounds.length()).any {
                        outbounds.optJSONObject(it)?.optString("tag") == "proxy"
                    }
                    if (!hasProxyOutbound) {
                        val realProxyTag = findPrimaryProxyTag(outbounds)
                        if (realProxyTag != "proxy") {
                            Log.i(TAG, "injectAdvancedSettings: fixing dns-remote detour 'proxy' → '$realProxyTag'")
                            for (i in 0 until servers.length()) {
                                val server = servers.optJSONObject(i) ?: continue
                                if (server.optString("detour") == "proxy") {
                                    server.put("detour", realProxyTag)
                                }
                            }
                        }
                    }
                }
            }

            if (settings.isSplitTunnelingEnabled && settings.splitTunnelingSites.isNotEmpty()) {
                val modeSites = settings.splitTunnelingModeSites
                val sites = settings.splitTunnelingSites.toList()
                val domainsToAdd = sites.toHashSet()
                val route = obj.optJSONObject("route")
                if (route != null) {
                    val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }

                    
                    val proxyTag = findPrimaryProxyTag(obj.optJSONArray("outbounds") ?: JSONArray())

                    
                    val targetOutbound = if (modeSites == "whitelist") proxyTag else "direct"

                    
                    val actionRules = JSONArray()
                    val routingRules = mutableListOf<JSONObject>()
                    for (i in 0 until rules.length()) {
                        val rule = rules.optJSONObject(i) ?: continue
                        if (rule.has("action")) actionRules.put(rule)
                        else routingRules.add(rule)
                    }

                    
                    
                    var merged = false
                    for (rule in routingRules) {
                        val ruleOutbound = rule.optString("outbound", "")
                        val hasDomainField = rule.has("domain_suffix") || rule.has("domain")
                        
                        val isPureDomainRule = hasDomainField &&
                            !rule.has("rule_set") && !rule.has("ip_is_private") &&
                            !rule.has("port") && !rule.has("network") && !rule.has("process_name")
                        if (ruleOutbound == targetOutbound && isPureDomainRule) {
                            
                            val existing = rule.optJSONArray("domain_suffix") ?: JSONArray()
                            val existingSet = (0 until existing.length()).map { existing.optString(it) }.toSet()
                            domainsToAdd.forEach { if (it !in existingSet) existing.put(it) }
                            rule.put("domain_suffix", existing)
                            
                            if (rule.has("domain") && !rule.has("domain_suffix")) {
                                rule.put("domain_suffix", existing)
                            }
                            merged = true
                            Log.i(TAG, "injectAdvancedSettings: merged sites into existing '$targetOutbound' rule")
                            break
                        }
                    }

                    if (!merged) {
                        
                        val siteRule = JSONObject().apply {
                            put("domain_suffix", JSONArray().also { sites.forEach(it::put) })
                            put("outbound", targetOutbound)
                        }
                        routingRules.add(0, siteRule)
                        Log.i(TAG, "injectAdvancedSettings: created new '$targetOutbound' domain rule")
                    }

                    
                    
                    
                    
                    route.put("final", if (modeSites == "whitelist") "direct" else proxyTag)

                    
                    val newRules = JSONArray()
                    for (i in 0 until actionRules.length()) newRules.put(actionRules.opt(i))
                    routingRules.forEach { newRules.put(it) }
                    route.put("rules", newRules)

                    
                    val dns = obj.optJSONObject("dns")
                    if (dns != null) {
                        val dnsRules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
                        val domainsArray = JSONArray().also { sites.forEach(it::put) }
                        val dnsRule = JSONObject().apply {
                            put("domain_suffix", domainsArray)
                            
                            put("server", if (modeSites == "whitelist") "dns-remote" else "dns-direct")
                        }
                        
                        val newDnsRules = JSONArray()
                        var dnsInserted = false
                        for (i in 0 until dnsRules.length()) {
                            val dr = dnsRules.optJSONObject(i) ?: continue
                            newDnsRules.put(dr)
                            if (!dnsInserted) {
                                newDnsRules.put(dnsRule)
                                dnsInserted = true
                            }
                        }
                        if (!dnsInserted) newDnsRules.put(dnsRule)
                        dns.put("rules", newDnsRules)
                    }

                    Log.i(TAG, "injectAdvancedSettings: sites split tunneling done, mode=$modeSites, proxyTag=$proxyTag, merged=$merged, sites=$sites")
                }
            }

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

            
            val proxyTag = findPrimaryProxyTag(outbounds)
            var proxyIndex = -1
            for (i in 0 until outbounds.length()) {
                if (outbounds.optJSONObject(i)?.optString("tag") == proxyTag) {
                    proxyIndex = i
                    break
                }
            }

            if (proxyIndex == -1) {
                Log.w(TAG, "injectAdvancedSettings: no suitable outbound found for frag/mux, skipping")
                return obj.toString(2).replace("\\/", "/")
            }

            val proxyOutbound = outbounds.getJSONObject(proxyIndex)

            if (settings.isTlsSpoofEnabled || settings.isFragmentationEnabled) {
                val tls = proxyOutbound.optJSONObject("tls")
                if (tls != null) {
                    if (settings.isTlsSpoofEnabled) {
                        tls.put("spoof", true)
                        Log.i(TAG, "injectAdvancedSettings: TLS spoof enabled for '${proxyOutbound.optString("tag")}'")
                    }

                    if (settings.isFragmentationEnabled) {
                        tls.put("fragment", true)
                        tls.put("record_fragment", true)

                        if (settings.packetType != "disabled") {
                            val intervalMs = settings.fragmentInterval.trim().toIntOrNull() ?: 10
                            tls.put("fragment_fallback_delay", "${intervalMs}ms")

                            Log.i(
                                TAG,
                                "injectAdvancedSettings: fragment fallback added (${intervalMs}ms)"
                            )
                        } else {
                            Log.i(TAG, "injectAdvancedSettings: fragment fallback disabled")
                        }
                    }
                } else {
                    Log.w(
                        TAG,
                        "injectAdvancedSettings: proxy has no TLS block, skipping TLS features (spoof/frag)"
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

                    val flow = ob.optString("flow", "")
                    val hasReality = ob.optJSONObject("tls")?.has("reality") ?: false

                    if (flow.contains("vision") || hasReality) {
                        continue
                    }

                    ob.put(
                            "multiplex",
                            JSONObject().apply {
                                put("enabled", true)
                                put("protocol", protocol)
                                put("max_connections", 4)
                                put("min_streams", 4)
                                put("max_streams", maxStreams)
                                if (protocol == "smux") {
                                    put("padding", padding)
                                }
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

    private fun findPrimaryProxyTag(outbounds: JSONArray): String {
        
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val type = ob.optString("type", "")
            if (type == "urltest" || type == "selector") {
                val tag = ob.optString("tag", "")
                if (tag.isNotEmpty()) return tag
            }
        }
        
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val type = ob.optString("type", "")
            val tag = ob.optString("tag", "")
            if (tag.isNotEmpty() && type != "direct" && type != "block" && type != "dns" && type != "dns-out") {
                return tag
            }
        }
        return "proxy"
    }
}
