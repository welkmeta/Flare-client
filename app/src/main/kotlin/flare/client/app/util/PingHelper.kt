package flare.client.app.util

import android.content.Context
import android.util.Log
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.parser.V2RayConfigConverter
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

object PingHelper {
    private const val TAG = "PingHelper"

    // Semaphore limiting concurrent direct pings
    private val directSemaphore = Semaphore(10)

    // Mutex to ensure only one libbox instance for batch testing
    private val batchMutex = Mutex()

    @Volatile private var libboxSetupDone = false
    private val setupLock = Any()
    private val portCounter = AtomicInteger(20000)

    private fun ensureLibboxSetup(context: Context) {
        if (libboxSetupDone) return
        synchronized(setupLock) {
            if (libboxSetupDone) return
            try {
                val opts = SetupOptions().apply {
                    basePath        = context.filesDir.absolutePath
                    workingPath     = context.filesDir.absolutePath
                    tempPath        = context.cacheDir.absolutePath
                    fixAndroidStack = true
                    logMaxLines     = 100
                }
                Libbox.setup(opts)
                if (flare.client.app.BuildConfig.DEBUG) Log.i(TAG, "Libbox.setup() success")
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "Libbox.setup() failed: ${e.message}")
            } finally {
                libboxSetupDone = true
            }
        }
    }


    // ── Direct ping (ICMP / TCP) ────────────────────────────────────────────

    suspend fun pingDirect(profile: ProfileEntity, method: String): Long =
        withContext(Dispatchers.IO) {
            val hostPort = extractHostPort(profile) ?: return@withContext -1L
            val host = hostPort.first
            val port = hostPort.second

            // Pre-resolve DNS outside the timed block
            val ipAddress = try {
                InetAddress.getByName(host).hostAddress
            } catch (e: Exception) {
                return@withContext -1L
            }

            directSemaphore.withPermit {
                try {
                    if (method == "ICMP") {
                        val startTime = System.nanoTime()
                        val process = Runtime.getRuntime()
                            .exec(arrayOf("ping", "-c", "1", "-W", "2", ipAddress))
                        
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        val exitCode = process.waitFor()
                        
                        if (exitCode == 0) {
                            val rtt = parseIcmpRtt(output)
                            if (rtt != -1L) rtt else (System.nanoTime() - startTime) / 1_000_000
                        } else {
                            -1L
                        }
                    } else {
                        val startTime = System.nanoTime()
                        Socket().use {
                            it.connect(InetSocketAddress(ipAddress, port), 3000)
                        }
                        (System.nanoTime() - startTime) / 1_000_000
                    }
                } catch (e: Exception) {
                    -1L
                }
            }
        }

    private fun parseIcmpRtt(output: String): Long {
        return try {
            val pattern = Pattern.compile("time=([\\d.]+)")
            val matcher = pattern.matcher(output)
            if (matcher.find()) {
                val timeStr = matcher.group(1) ?: return -1L
                timeStr.toDouble().toLong()
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }


    // ── Proxy ping batch (parallel, up to 4 at once) ───────────────────────

    suspend fun pingProxyBatch(
        context: Context,
        profiles: List<ProfileEntity>,
        testUrl: String,
        httpMethod: String,
        onResult: suspend (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        ensureLibboxSetup(context)

        // Only one batch test can run at a time to avoid Libbox registration panics
        batchMutex.withLock {
            val startPort = portCounter.getAndAdd(profiles.size)
                .also { if (it > 29000) portCounter.set(20000) }

            val handler = object : CommandServerHandler {
                override fun serviceStop() {}
                override fun serviceReload() {}
                override fun getSystemProxyStatus() = SystemProxyStatus()
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun writeDebugMessage(message: String?) {}
            }

            val platform = object : PlatformInterface {
                override fun autoDetectInterfaceControl(fd: Int) {}
                override fun clearDNSCache() {}
                override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener?) {}
                override fun findConnectionOwner(
                    p0: Int, p1: String?, p2: Int, p3: String?, p4: Int
                ): ConnectionOwner? = null
                override fun getInterfaces(): NetworkInterfaceIterator? = null
                override fun includeAllNetworks(): Boolean = false
                override fun localDNSTransport(): LocalDNSTransport? = null
                override fun openTun(o: TunOptions?): Int = -1
                override fun readWIFIState(): WIFIState? = null
                override fun sendNotification(n: Notification?) {}
                override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener?) {}
                override fun systemCertificates(): StringIterator? = null
                override fun underNetworkExtension(): Boolean = false
                override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
                override fun useProcFS(): Boolean = true
            }

            val boxService = Libbox.newCommandServer(handler, platform)
            try {
                val batchConfig = buildBatchConfig(profiles, startPort)
                if (batchConfig == null) {
                    profiles.forEach { onResult(it.id, -1L) }
                    return@withLock
                }

                boxService.startOrReloadService(
                    batchConfig.toString().replace("\\/", "/"),
                    OverrideOptions()
                )

                // Brief pause for the proxy listener to bind all ports and initialize outbounds
                delay(1000)

                coroutineScope {
                    profiles.forEachIndexed { index, profile ->
                        launch {
                            val port = startPort + index
                            val lat = measureLatency(testUrl, port, httpMethod)
                            onResult(profile.id, lat)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch ping failed (core start error): ${e.message}", e)
                profiles.forEach { onResult(it.id, -1L) }
            } finally {
                try {
                    boxService.closeService()
                    boxService.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildBatchConfig(profiles: List<ProfileEntity>, startPort: Int): JSONObject? {
        return try {
            val inbounds = JSONArray()
            val outbounds = JSONArray()
            val rules = JSONArray()

            profiles.forEachIndexed { index, profile ->
                val converted = V2RayConfigConverter.convertIfNeeded(profile.configJson)
                val profileJson = JSONObject(converted)
                val profileOutbounds = profileJson.optJSONArray("outbounds") ?: JSONArray()
                
                // Find main proxy tag
                var mainProxyTag = ""
                for (i in 0 until profileOutbounds.length()) {
                    val ob = profileOutbounds.optJSONObject(i) ?: continue
                    val t = ob.optString("type")
                    if (t != "direct" && t != "block" && t != "dns" && t.isNotBlank()) {
                        mainProxyTag = ob.optString("tag")
                        break
                    }
                }
                if (mainProxyTag.isBlank()) return@forEachIndexed

                val newMainTag = "$mainProxyTag-$index"

                for (i in 0 until profileOutbounds.length()) {
                    val ob = profileOutbounds.optJSONObject(i) ?: continue
                    val t = ob.optString("type")
                    if (t == "direct" || t == "block" || t == "dns") continue
                    
                    val oldTag = ob.optString("tag")
                    if (oldTag.isNotBlank()) {
                        ob.put("tag", "$oldTag-$index")
                    }
                    
                    if (ob.has("outbounds")) {
                        val obList = ob.optJSONArray("outbounds")
                        if (obList != null) {
                            val newList = JSONArray()
                            for (j in 0 until obList.length()) {
                                newList.put("${obList.optString(j)}-$index")
                            }
                            ob.put("outbounds", newList)
                        }
                    }
                    
                    if (ob.has("detour")) {
                        val detourName = ob.optString("detour")
                        if (detourName.isNotBlank() && detourName != "direct" && detourName != "block") {
                            ob.put("detour", "$detourName-$index")
                        }
                    }
                    
                    outbounds.put(ob)
                }

                val inTag = "http-in-$index"

                inbounds.put(JSONObject().apply {
                    put("type", "mixed")
                    put("tag", inTag)
                    put("listen", "127.0.0.1")
                    put("listen_port", startPort + index)
                })

                rules.put(JSONObject().apply {
                    put("inbound", JSONArray().apply { put(inTag) })
                    put("outbound", newMainTag)
                })
            }

            if (outbounds.length() == 0) return null

            JSONObject().apply {
                put("log", JSONObject().apply { put("level", "error") })
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tag", "dns-direct")
                            put("address", "8.8.8.8")
                            put("detour", "direct")
                        })
                        put(JSONObject().apply {
                            put("tag", "dns-local")
                            put("address", "local")
                            put("detour", "direct")
                        })
                    })
                    put("rules", JSONArray().apply {
                        put(JSONObject().apply {
                            put("outbound", JSONArray().put("any"))
                            put("server", "dns-direct")
                        })
                    })
                    put("final", "dns-direct")
                })
                put("inbounds", inbounds)
                put("outbounds", outbounds.apply {
                    put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
                    put(JSONObject().apply { put("type", "block"); put("tag", "block") })
                })
                put("route", JSONObject().apply {
                    put("auto_detect_interface", false)
                    put("rules", rules)
                    put("final", "direct")
                })
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun measureLatency(targetUrl: String, port: Int, httpMethod: String): Long {
        val startTime = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            conn = URL(targetUrl).openConnection(proxy) as HttpURLConnection
            conn.requestMethod = httpMethod
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            if (code in 200..399) System.currentTimeMillis() - startTime else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractHostPort(profile: ProfileEntity): Pair<String, Int>? {
        return try {
            val converted = V2RayConfigConverter.convertIfNeeded(profile.configJson)
            val json = JSONObject(converted)
            val outbounds = json.optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type")
                if (type != "direct" && type != "block" && type.isNotBlank()) {
                    val host = ob.optString("server")
                    val port = ob.optInt("server_port", 443)
                    if (host.isNotEmpty()) return host to port
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T> Semaphore.withPermit(block: suspend () -> T): T {
        acquire()
        return try { block() } finally { release() }
    }
}
