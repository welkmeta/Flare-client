package flare.client.app.util

import android.content.Context
import android.util.Log
import flare.client.app.data.model.ProfileEntity
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

object PingHelper {
    private const val TAG = "PingHelper"

    private val directSemaphore = Semaphore(10)
    private val proxyMutex = Mutex() // Proxy batch sequential processing

    @Volatile private var libboxSetupDone = false
    private val setupLock = Any()
    private val portCounter = AtomicInteger(20000)

    private fun ensureLibboxSetup(context: Context) {
        if (libboxSetupDone) return
        synchronized(setupLock) {
            if (libboxSetupDone) return
            try {
                val opts = SetupOptions().apply {
                    basePath    = context.filesDir.absolutePath
                    workingPath = context.filesDir.absolutePath
                    tempPath    = context.cacheDir.absolutePath
                    fixAndroidStack = true
                    logMaxLines = 100
                }
                Libbox.setup(opts)
                Log.i(TAG, "Libbox.setup() success")
            } catch (e: Exception) {
                Log.w(TAG, "Libbox.setup() failed: ${e.message}")
            } finally {
                libboxSetupDone = true
            }
        }
    }


    suspend fun pingDirect(profile: ProfileEntity, method: String): Long = withContext(Dispatchers.IO) {
        val hostPort = extractHostPort(profile) ?: return@withContext -1L
        directSemaphore.withPermit {
            val t = System.currentTimeMillis()
            try {
                if (method == "ICMP") {
                    val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "2", hostPort.first))
                    if (p.waitFor() == 0) System.currentTimeMillis() - t else -1L
                } else {
                    Socket().use { it.connect(InetSocketAddress(hostPort.first, hostPort.second), 3000) }
                    System.currentTimeMillis() - t
                }
            } catch (e: Exception) {
                -1L
            }
        }
    }


    suspend fun pingProxyBatch(
        context: Context,
        profiles: List<ProfileEntity>,
        testUrl: String,
        httpMethod: String,
        onResult: suspend (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        ensureLibboxSetup(context)
        
        proxyMutex.withLock {
            var boxService: CommandServer? = null
            try {
                val port = portCounter.getAndIncrement().also { if (it > 30000) portCounter.set(20000) }
                
                val handler = object : CommandServerHandler {
                    override fun serviceStop() {}
                    override fun serviceReload() {}
                    override fun getSystemProxyStatus() = SystemProxyStatus()
                    override fun setSystemProxyEnabled(enabled: Boolean) {}
                    override fun writeDebugMessage(message: String?) {
                    }
                }

                val platform = object : PlatformInterface {
                    override fun autoDetectInterfaceControl(fd: Int) {}
                    override fun clearDNSCache() {}
                    override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener?) {}
                    override fun findConnectionOwner(p0: Int, p1: String?, p2: Int, p3: String?, p4: Int): ConnectionOwner? = null
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

                boxService = Libbox.newCommandServer(handler, platform)

                for (profile in profiles) {
                    try {
                        val minConfig = buildMinimalConfig(profile, port)
                        if (minConfig == null) {
                            onResult(profile.id, -1L)
                            continue
                        }

                        boxService.startOrReloadService(minConfig.toString().replace("\\/", "/"), OverrideOptions())
                        
                        // Delay for service initialization
                        kotlinx.coroutines.delay(if (boxService == null) 800 else 300)

                        val lat = measureLatency(testUrl, port, httpMethod)
                        onResult(profile.id, lat)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to ping ${profile.name}: ${e.message}")
                        onResult(profile.id, -1L)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in proxy batch: ${e.message}")
            } finally {
                try {
                    boxService?.closeService()
                    boxService?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildMinimalConfig(profile: ProfileEntity, port: Int): JSONObject? {
        return try {
            val profileJson = JSONObject(profile.configJson)
            val profileOutbounds = profileJson.optJSONArray("outbounds") ?: JSONArray()
            
            val proxyOutbound = (0 until profileOutbounds.length())
                .mapNotNull { profileOutbounds.optJSONObject(it) }
                .firstOrNull { 
                    val t = it.optString("type")
                    t != "direct" && t != "block" && t != "dns" && t.isNotBlank() 
                } ?: return null
            
            val proxyTag = proxyOutbound.optString("tag", "proxy")

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
                            put("tag", "dns-remote")
                            put("address", "1.1.1.1")
                            put("detour", proxyTag)
                        })
                    })
                    put("final", "dns-direct")
                })
                put("inbounds", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "http")
                        put("tag", "http-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", port)
                    })
                })
                put("outbounds", JSONArray().apply {
                    put(proxyOutbound)
                    put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
                })
                put("route", JSONObject().apply {
                    put("auto_detect_interface", false)
                    put("final", proxyTag)
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
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
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
            val json = JSONObject(profile.configJson)
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
