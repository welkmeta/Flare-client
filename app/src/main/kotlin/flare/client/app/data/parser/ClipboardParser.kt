package flare.client.app.data.parser

import android.util.Base64
import flare.client.app.data.model.ProfileEntity
import flare.client.app.data.model.SubscriptionEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Parses clipboard text into either a single proxy profile,
 * a subscription reference, or returns an error.
 */
object ClipboardParser {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val singleSchemes = setOf("vless", "vmess", "ss", "trojan", "shadowsocks")

    sealed class ParseResult {
        data class SingleProfile(val profile: ProfileEntity) : ParseResult()
        data class Subscription(val subscription: SubscriptionEntity, val profiles: List<ProfileEntity>) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    fun parse(text: String): ParseResult {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> parseFullJson(trimmed)
            singleSchemes.any { trimmed.startsWith("$it://") } -> parseSingleProxy(trimmed)
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> parseSubscriptionUrl(trimmed)
            else -> ParseResult.Error("Неверный формат. Поддерживаются: vless://, vmess://, ss://, trojan://, http(s):// и JSON")
        }
    }

    private fun parseSingleProxy(uri: String): ParseResult {
        return try {
            val profile = buildProfileFromUri(uri, subscriptionId = null)
            ParseResult.SingleProfile(profile)
        } catch (e: Exception) {
            ParseResult.Error("Ошибка парсинга: ${e.message}")
        }
    }

    private fun parseFullJson(text: String): ParseResult {
        return try {
            val json = JSONObject(text)
            val name = json.optString("remarks", json.optString("tag", "Imported Profile"))
            val configJson = V2RayConfigConverter.convertIfNeeded(text)
            ParseResult.SingleProfile(ProfileEntity(name = name, uri = "internal://json", configJson = configJson, subscriptionId = null))
        } catch (e: Exception) {
            ParseResult.Error("Ошибка JSON: ${e.message}")
        }
    }

    private fun parseSubscriptionUrl(url: String): ParseResult {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            val profileTitle = response.header("profile-title")
            val contentDisposition = response.header("content-disposition")
            val name = extractSubscriptionName(url, profileTitle, contentDisposition)
            
            val proxyLines = decodeSubscriptionBody(body)
            val profiles = proxyLines.mapIndexedNotNull { _, line -> try { buildProfileFromUri(line.trim(), 0L) } catch (_: Exception) { null } }
            
            response.close()
            
            if (profiles.isEmpty()) return ParseResult.Error("Подписка пуста")
            ParseResult.Subscription(SubscriptionEntity(name = name, url = url), profiles)
        } catch (e: Exception) {
            ParseResult.Error("Ошибка подписки: ${e.message}")
        }
    }

    private fun extractSubscriptionName(url: String, profileTitle: String?, contentDisposition: String?): String {
        if (!profileTitle.isNullOrBlank()) {
            return decodeIfNeeded(profileTitle)
        }
        
        if (!contentDisposition.isNullOrBlank()) {
            val filename = contentDisposition.split(";")
                .map { it.trim() }
                .find { it.startsWith("filename=") }
                ?.substringAfter("filename=")
                ?.removeSurrounding("\"")
            if (!filename.isNullOrBlank()) return filename
        }
        
        return try { URI(url).host ?: url } catch (_: Exception) { url }
    }

    private fun decodeIfNeeded(text: String): String {
        return if (text.startsWith("base64:")) {
            try {
                val b64 = text.substringAfter("base64:")
                String(Base64.decode(b64.trim(), Base64.DEFAULT)).trim()
            } catch (_: Exception) { text }
        } else {
            text.trim()
        }
    }


    private fun decodeSubscriptionBody(body: String): List<String> {
        val input = body.trim().replace("\r", "").replace("\n", "")
        return try {
            val clean = input.replace("-", "+").replace("_", "/")
            val padded = when (clean.length % 4) { 2 -> "$clean=="; 3 -> "$clean="; else -> clean }
            String(Base64.decode(padded, Base64.DEFAULT)).lines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            body.lines().filter { it.isNotBlank() }
        }
    }

    fun buildProfileFromUri(uri: String, subscriptionId: Long?): ProfileEntity {
        val trimmed = uri.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = JSONObject(trimmed)
            val name = json.optString("remarks", "Imported Profile")
            return ProfileEntity(name = name, uri = "internal://json", configJson = V2RayConfigConverter.convertIfNeeded(trimmed), subscriptionId = subscriptionId)
        }

        val parsed = URI(uri)
        val scheme = parsed.scheme
        val displayName = extractDisplayName(uri)
        val params = parseQuery(parsed.rawQuery)
        
        val outbound = when (scheme) {
            "vless" -> buildVlessOutbound(parsed, params)
            "vmess" -> buildVmessOutbound(uri)
            "ss", "shadowsocks" -> buildShadowsocksOutbound(parsed)
            "trojan" -> buildTrojanOutbound(parsed, params)
            else -> throw IllegalArgumentException("Protocol $scheme not supported")
        }

        val xray = JSONObject().apply {
            put("outbounds", JSONArray().put(outbound))
            
            put("dns", JSONObject().apply {
                put("queryStrategy", "UseIPv4")
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", "https://dns.comss.one/dns-query")
                        put("skipFallback", true)
                        put("queryStrategy", "UseIPv4")
                    })
                    put(JSONObject().apply { put("address", "1.1.1.1"); put("skipFallback", false) })
                    put(JSONObject().apply { put("address", "77.88.8.8"); put("skipFallback", false) })
                })
            })
            
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("outboundTag", "proxy")
                        put("domain", JSONArray(listOf(
                            "habr.com", "4pda.to", "4pda.ru", "kemono.su", "jut.su",
                            "kara.su", "theins.ru", "tvrain.ru", "echo.msk.ru",
                            "the-village.ru", "snob.ru", "novayagazeta.ru", "moscowtimes.ru"
                        )))
                    })
                    put(JSONObject().apply {
                        put("outboundTag", "direct")
                        put("domain", JSONArray(listOf(
                           "geosite:category-ru"
                        )))
                    })
                    put(JSONObject().apply {
                        put("outboundTag", "direct")
                        put("ip", JSONArray(listOf("geoip:ru", "geoip:private")))
                    })
                })
            })
        }
        val configJson = V2RayConfigConverter.convertV2RayToSingBox(xray)

        return ProfileEntity(name = displayName, uri = uri, configJson = configJson, subscriptionId = subscriptionId)
    }

    private fun buildVlessOutbound(parsed: URI, params: Map<String, String>): JSONObject = JSONObject().apply {
        put("protocol", "vless")
        put("tag", "proxy")
        put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject().apply {
                put("address", parsed.host)
                put("port", if (parsed.port > 0) parsed.port else 443)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", parsed.userInfo)
                    put("flow", params["flow"] ?: "")
                    put("encryption", "none")
                }))
            }))
        })
        put("streamSettings", buildStreamSettings(parsed.host, params))
    }

    private fun buildVmessOutbound(uri: String): JSONObject {
        val b64 = uri.removePrefix("vmess://").trim()
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        return JSONObject().apply {
            put("protocol", "vmess")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", json.optString("add"))
                    put("port", json.optInt("port", 443))
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", json.optString("id"))
                        put("alterId", json.optInt("aid", 0))
                        put("security", "auto")
                    }))
                }))
            })
            val stream = JSONObject().apply {
                put("network", json.optString("net", "tcp"))
                put("security", json.optString("tls"))
                if (json.optString("tls") == "tls") {
                    put("tlsSettings", JSONObject().apply { put("serverName", json.optString("sni", json.optString("add"))) })
                }
            }
            put("streamSettings", stream)
        }
    }

    private fun buildShadowsocksOutbound(parsed: URI): JSONObject = JSONObject().apply {
        put("protocol", "shadowsocks")
        put("tag", "proxy")
        val userInfo = try { String(Base64.decode(parsed.userInfo ?: "", Base64.DEFAULT)) } catch (_: Exception) { parsed.userInfo ?: ":" }
        put("settings", JSONObject().apply {
            put("servers", JSONArray().put(JSONObject().apply {
                put("address", parsed.host)
                put("port", if (parsed.port > 0) parsed.port else 8388)
                put("method", userInfo.substringBefore(":"))
                put("password", userInfo.substringAfter(":"))
            }))
        })
    }

    private fun buildTrojanOutbound(parsed: URI, params: Map<String, String>): JSONObject = JSONObject().apply {
        put("protocol", "trojan")
        put("tag", "proxy")
        put("settings", JSONObject().apply {
            put("servers", JSONArray().put(JSONObject().apply {
                put("address", parsed.host)
                put("port", if (parsed.port > 0) parsed.port else 443)
                put("password", parsed.userInfo)
            }))
        })
        put("streamSettings", buildStreamSettings(parsed.host, params))
    }

    private fun buildStreamSettings(host: String, params: Map<String, String>): JSONObject = JSONObject().apply {
        val security = params["security"] ?: "none"
        put("security", security)
        put("network", params["type"] ?: "tcp")
        
        if (security == "tls") {
            put("tlsSettings", JSONObject().apply { put("serverName", params["sni"] ?: host) })
        } else if (security == "reality") {
            put("realitySettings", JSONObject().apply {
                put("serverName", params["sni"] ?: host)
                put("publicKey", params["pbk"] ?: "")
                put("shortId", params["sid"] ?: "")
                put("fingerprint", params["fp"] ?: "chrome")
            })
        }
        
        when (params["type"]) {
            "ws" -> put("wsSettings", JSONObject().apply { put("path", params["path"] ?: "/"); put("headers", JSONObject().apply { put("Host", params["host"] ?: "") }) })
            "grpc" -> put("grpcSettings", JSONObject().apply { put("serviceName", params["serviceName"] ?: "") })
        }
    }

    private fun extractDisplayName(uri: String): String = try {
        val fragment = URI(uri).fragment
        if (!fragment.isNullOrBlank()) URLDecoder.decode(fragment, "UTF-8") else uri.substringBefore("?").take(40)
    } catch (_: Exception) { uri.substringBefore("?").take(40) }

    private fun parseQuery(query: String?): Map<String, String> = query?.split("&")?.associate {
        val parts = it.split("=", limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    } ?: emptyMap()
}
