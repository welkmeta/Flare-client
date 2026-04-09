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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.net.ServerSocket

object PingHelper {
    private const val TAG = "PingHelper"

    private val directSemaphore = Semaphore(10)

    private val batchMutex = Mutex()

    @Volatile private var libboxSetupDone = false
    private val setupLock = Any()

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

    suspend fun pingDirect(profile: ProfileEntity, method: String): Pair<Long, String?> =
        withContext(Dispatchers.IO) {
            val hostPort = extractHostPort(profile) ?: return@withContext (-1L to "Config Err")
            val host = hostPort.first
            val port = hostPort.second

            val ipAddress = try {
                InetAddress.getByName(host).hostAddress
            } catch (e: Exception) {
                return@withContext (-1L to "DNS Fail")
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
                            val finalRtt = if (rtt != -1L) rtt else (System.nanoTime() - startTime) / 1_000_000
                            finalRtt to null
                        } else {
                            -1L to "Unreachable"
                        }
                    } else {
                        val startTime = System.nanoTime()
                        Socket().use {
                            it.connect(InetSocketAddress(ipAddress, port), 3000)
                        }
                        ((System.nanoTime() - startTime) / 1_000_000) to null
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    -1L to "Timeout"
                } catch (e: java.net.ConnectException) {
                    -1L to "Refused"
                } catch (e: java.net.NoRouteToHostException) {
                    -1L to "Unreachable"
                } catch (e: Exception) {
                    -1L to (e.message ?: "Error")
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

    suspend fun pingProxyBatch(
        context: Context,
        profiles: List<ProfileEntity>,
        testUrl: String,
        httpMethod: String,
        onResult: suspend (Long, Long, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        ensureLibboxSetup(context)

        batchMutex.withLock {
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
            val clashPort = findAvailablePort()
            try {
                val batchConfig = buildBatchConfig(profiles, testUrl, clashPort)
                if (batchConfig == null) {
                    profiles.forEach { onResult(it.id, -1L, "Config Err") }
                    return@withLock
                }

                boxService.startOrReloadService(
                    batchConfig.toString().replace("\\/", "/"),
                    OverrideOptions()
                )
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()

                var ready = false
                val healthStart = System.currentTimeMillis()
                while (!ready && System.currentTimeMillis() - healthStart < 5000) {
                    try {
                        val checkReq = Request.Builder().url("http://127.0.0.1:$clashPort/").get().build()
                        okHttpClient.newCall(checkReq).execute().use {
                            if (it.code != 0) ready = true
                        }
                    } catch (e: Exception) {
                        delay(50)
                    }
                }
                if (!ready) {
                    Log.w(TAG, "Clash API failed to start on port $clashPort in time")
                }

                val semaphore = Semaphore(25)
                coroutineScope {
                    profiles.forEach { profile ->
                        launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                var rtt = -1L
                                var errMsg: String? = null
                                try {
                                    val index = profiles.indexOf(profile)
                                    val tag = "proxy-$index"
                                    val url = "http://127.0.0.1:$clashPort/proxies/${java.net.URLEncoder.encode(tag, "UTF-8")}/delay?url=${java.net.URLEncoder.encode(testUrl, "UTF-8")}&timeout=10000"
                                    val request = Request.Builder()
                                        .url(url)
                                        .get()
                                        .build()
                                    okHttpClient.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            val body = response.body?.string()
                                            if (body != null) {
                                                val json = JSONObject(body)
                                                rtt = json.optLong("delay", -1L)
                                                if (rtt == -1L) errMsg = "Timeout"
                                            }
                                        } else {
                                            val body = response.body?.string() ?: ""
                                            errMsg = try {
                                                val msg = JSONObject(body).optString("message", "")
                                                when {
                                                    msg.contains("timeout", ignoreCase = true) -> "Timeout"
                                                    msg.contains("TLS", ignoreCase = true) -> "TLS Failed"
                                                    msg.contains("unreachable", ignoreCase = true) -> "Unreachable"
                                                    msg.contains("connection refused", ignoreCase = true) -> "Refused"
                                                    msg.contains("error occurred", ignoreCase = true) -> "Failed"
                                                    msg.length > 20 -> msg.substring(0, 17) + ".."
                                                    msg.isNotBlank() -> msg
                                                    else -> "${response.code}"
                                                }
                                            } catch (_: Exception) {
                                                "${response.code}"
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ping failed for profile ${profile.id}: ${e.message}")
                                    errMsg = when {
                                        e is java.net.SocketTimeoutException -> "Timeout"
                                        e.message?.contains("timeout", ignoreCase = true) == true -> "Timeout"
                                        else -> "Error"
                                    }
                                }
                                onResult(profile.id, rtt, errMsg)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch ping failed (core start error): ${e.message}", e)
                profiles.forEach { onResult(it.id, -1L, "Core err") }
            } finally {
                try {
                    boxService.closeService()
                    boxService.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildBatchConfig(profiles: List<ProfileEntity>, testUrl: String, clashPort: Int): JSONObject? {
        return try {
            val outbounds = JSONArray()
            val proxyTags = ArrayList<String>()

            profiles.forEachIndexed { index, profile ->
                val converted = V2RayConfigConverter.convertIfNeeded(profile.configJson)
                val profileJson = JSONObject(converted)
                val profileOutbounds = profileJson.optJSONArray("outbounds") ?: JSONArray()
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

                val mainTagMapped = "proxy-$index"
                proxyTags.add(mainTagMapped)

                for (i in 0 until profileOutbounds.length()) {
                    val ob = profileOutbounds.optJSONObject(i) ?: continue
                    val t = ob.optString("type")
                    if (t == "direct" || t == "block" || t == "dns") continue
                    val oldTag = ob.optString("tag")
                    if (oldTag.isNotBlank()) {
                        ob.put("tag", if (oldTag == mainProxyTag) mainTagMapped else "$oldTag-$index")
                    }
                    if (ob.has("outbounds")) {
                        val obList = ob.optJSONArray("outbounds")
                        if (obList != null) {
                            val newList = JSONArray()
                            for (j in 0 until obList.length()) {
                                val entry = obList.optString(j)
                                newList.put(if (entry == mainProxyTag) mainTagMapped else "$entry-$index")
                            }
                            ob.put("outbounds", newList)
                        }
                    }
                    if (ob.has("detour")) {
                        val detourName = ob.optString("detour")
                        if (detourName.isNotBlank() && detourName != "direct" && detourName != "block") {
                            ob.put("detour", if (detourName == mainProxyTag) mainTagMapped else "$detourName-$index")
                        }
                    }

                    ob.optJSONObject("tls")?.let { tls ->
                        tls.put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", "chrome")
                        })
                    }
                    outbounds.put(ob)
                }
            }

            if (outbounds.length() == 0) return null

            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "urltest-ping")
                put("outbounds", JSONArray(proxyTags))
                put("url", testUrl)
                put("interval", "10m")
            })

            JSONObject().apply {
                put("experimental", JSONObject().apply {
                    put("clash_api", JSONObject().apply {
                        put("external_controller", "127.0.0.1:$clashPort")
                    })
                })
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
                put("inbounds", JSONArray())
                put("outbounds", outbounds.apply {
                    put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
                    put(JSONObject().apply { put("type", "block"); put("tag", "block") })
                })
                put("route", JSONObject().apply {
                    put("auto_detect_interface", false)
                    put("final", "direct")
                })
            }
        } catch (e: Exception) {
            null
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

    private fun findAvailablePort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            9094
        }
    }
}
