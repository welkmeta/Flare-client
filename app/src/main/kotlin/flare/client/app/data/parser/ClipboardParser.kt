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
            val name = extractNameFromJson(json)
            val configJson = V2RayConfigConverter.convertIfNeeded(text)
            ParseResult.SingleProfile(ProfileEntity(name = name, uri = "internal://json", configJson = configJson, subscriptionId = null))
        } catch (e: Exception) {
            ParseResult.Error("Ошибка JSON: ${e.message}")
        }
    }

    private fun parseSubscriptionUrl(url: String): ParseResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Happ/3.15.2")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val profileTitle = response.header("profile-title")
            val contentDisposition = response.header("content-disposition")
            val name = extractSubscriptionName(url, profileTitle, contentDisposition)
            val userInfo = response.header("subscription-userinfo")
            val descParts = mutableListOf<String>()

            val announce = response.header("announce")
            if (announce != null) descParts.add(decodeIfNeeded(announce))

            val profileDesc = response.header("profile-description") ?: response.header("profile-message") ?: response.header("description")
            if (profileDesc != null) descParts.add(decodeIfNeeded(profileDesc))

            val supportUrl = response.header("support-url") ?: ""
            val webPageUrl = response.header("profile-web-page-url") ?: ""

            val description = descParts.joinToString("\n")
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L
            if (userInfo != null) {
                val parts = userInfo.split(";")
                for (part in parts) {
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        val k = kv[0].trim()
                        val v = kv[1].trim().toLongOrNull() ?: 0L
                        when (k) {
                            "upload" -> upload = v
                            "download" -> download = v
                            "total" -> total = v
                            "expire" -> expire = v
                        }
                    }
                }
            }
            val proxyLines = decodeSubscriptionBody(body)
            val profiles = proxyLines.mapIndexedNotNull { _, line -> try { buildProfileFromUri(line.trim(), 0L) } catch (_: Exception) { null } }
            response.close()
            if (profiles.isEmpty()) return ParseResult.Error("Подписка пуста")
            ParseResult.Subscription(
                SubscriptionEntity(
                    name = name,
                    url = url,
                    upload = upload,
                    download = download,
                    total = total,
                    expire = expire,
                    description = description,
                    supportUrl = supportUrl,
                    webPageUrl = webPageUrl
                ),
                profiles
            )
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
        return try {
            val trimmed = text.trim()
            val decodedBase64 = if (trimmed.startsWith("base64:")) {
                try {
                    val b64 = trimmed.substringAfter("base64:")
                    String(Base64.decode(b64.trim(), Base64.DEFAULT)).trim()
                } catch (_: Exception) { trimmed }
            } else {
                trimmed
            }
            java.net.URLDecoder.decode(decodedBase64, "UTF-8")
        } catch (_: Exception) {
            text.trim()
        }
    }

    private fun extractNameFromJson(json: JSONObject): String {
        return json.optString("remarks").takeIf { it.isNotBlank() }
            ?: json.optString("tag").takeIf { it.isNotBlank() }
            ?: json.optJSONArray("outbounds")?.optJSONObject(0)?.let {
                it.optString("tag").takeIf { tag -> tag.isNotBlank() && tag != "proxy" }
                    ?: it.optString("server").takeIf { srv -> srv.isNotBlank() }
            }
            ?: "Imported Profile"
    }

    private fun decodeSubscriptionBody(body: String): List<String> {
        val trimmed = body.trim()

        if (trimmed.startsWith("[")) {
            return try {
                val arr = org.json.JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    .ifEmpty {
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toString() }
                    }
            } catch (_: Exception) {
                body.lines().filter { it.isNotBlank() }
            }
        }

        val lines = trimmed.lines().filter { it.isNotBlank() }
        val looksLikePlainList = lines.all { line ->
            val l = line.trim()
            l.contains("://") || l.startsWith("{") || l.startsWith("[")
        }
        if (looksLikePlainList && lines.size > 1) {
            return splitJsonAware(trimmed)
        }

        val flat = trimmed.replace("\r", "").replace("\n", "")
        return try {
            val clean = flat.replace("-", "+").replace("_", "/")
            val padded = when (clean.length % 4) { 2 -> "$clean=="; 3 -> "$clean="; else -> clean }
            val decoded = String(Base64.decode(padded, Base64.DEFAULT)).trim()
            splitJsonAware(decoded)
        } catch (_: Exception) {
            splitJsonAware(trimmed)
        }
    }

    private fun splitJsonAware(text: String): List<String> {
        val results = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        var inJson = false

        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) {
                if (inJson && depth == 0 && current.isNotEmpty()) {
                    results.add(current.toString().trim())
                    current.clear()
                    inJson = false
                }
                continue
            }

            if (!inJson && (l.startsWith("{") || l.startsWith("["))) {
                inJson = true
            }

            if (inJson) {
                current.append(line).append('\n')
                depth += l.count { it == '{' || it == '[' } - l.count { it == '}' || it == ']' }
                if (depth <= 0) {
                    results.add(current.toString().trim())
                    current.clear()
                    inJson = false
                    depth = 0
                }
            } else {
                if (l.isNotEmpty()) results.add(l)
            }
        }
        if (current.isNotEmpty()) results.add(current.toString().trim())
        return results.filter { it.isNotBlank() }
    }

    fun buildProfileFromUri(uri: String, subscriptionId: Long?): ProfileEntity {
        val trimmed = uri.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = JSONObject(trimmed)
            val name = extractNameFromJson(json)
            return ProfileEntity(name = name, uri = "internal://json", configJson = V2RayConfigConverter.convertIfNeeded(trimmed), subscriptionId = subscriptionId)
        }

        val parsed = URI(uri)
        val scheme = parsed.scheme
        val displayName = extractDisplayName(uri)
        val params = parseQuery(parsed.rawQuery)

        val proxyServer = when (scheme) {
            "vmess" -> {
                val b64 = uri.removePrefix("vmess://").trim()
                try { JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))).optString("add", "") } catch (_: Exception) { "" }
            }
            else -> parsed.host ?: ""
        }

        val xrayOutbound = when (scheme) {
            "vless" -> buildVlessOutbound(parsed, params)
            "vmess" -> buildVmessOutbound(uri)
            "ss", "shadowsocks" -> buildShadowsocksOutbound(parsed)
            "trojan" -> buildTrojanOutbound(parsed, params)
            else -> throw IllegalArgumentException("Protocol $scheme not supported")
        }
        val sbOutbounds = V2RayConfigConverter.convertOutboundsPublic(JSONArray().put(xrayOutbound))
        val proxyOutbound = sbOutbounds.optJSONObject(0)
            ?: throw IllegalArgumentException("Failed to convert outbound for $scheme")

        val configJson = buildMinimalSingBoxConfig(proxyOutbound, proxyServer)
        return ProfileEntity(name = displayName, uri = uri, configJson = configJson, subscriptionId = subscriptionId)
    }

    private fun buildMinimalSingBoxConfig(proxyOutbound: JSONObject, proxyServer: String): String {
        val sb = JSONObject()

        sb.put("log", JSONObject().apply {
            put("level", "info")
            put("timestamp", true)
        })

        val proxyDomains = JSONArray().apply {
            if (proxyServer.isNotEmpty() && !proxyServer[0].isDigit()) put(proxyServer)
        }
        sb.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", "https://1.1.1.1/dns-query")
                    put("address_resolver", "dns-direct")
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "dns-direct")
                    put("address", "8.8.8.8")
                    put("detour", "direct")
                })
                put(JSONObject().apply {
                    put("tag", "dns-block")
                    put("address", "rcode://success")
                })
            })
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("outbound", JSONArray().put("any"))
                    put("server", "dns-direct")
                })
                if (proxyDomains.length() > 0) {
                    put(JSONObject().apply {
                        put("domain", proxyDomains)
                        put("server", "dns-direct")
                    })
                }
            })
            put("final", "dns-remote")
            put("strategy", "prefer_ipv4")
            put("independent_cache", true)
        })

        sb.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().apply {
                    put("172.19.0.1/30")
                    put("fdfe:dcba:9876::1/126")
                })
                put("mtu", 1500)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "mixed")
            })
        })

        sb.put("outbounds", JSONArray().apply {
            put(proxyOutbound)
            put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
            put(JSONObject().apply { put("type", "block"); put("tag", "block") })
        })

        sb.put("route", JSONObject().apply {
            put("auto_detect_interface", false)
            put("final", "proxy")
            put("rules", JSONArray().apply {
                put(JSONObject().apply { put("protocol", "dns"); put("action", "hijack-dns") })
                put(JSONObject().apply { put("port", 53); put("action", "hijack-dns") })
                put(JSONObject().apply { put("action", "sniff") })
                put(JSONObject().apply { put("protocol", JSONArray().put("bittorrent")); put("outbound", "direct") })
                put(JSONObject().apply { put("ip_is_private", true); put("outbound", "direct") })
                if (proxyDomains.length() > 0) {
                    put(JSONObject().apply { put("domain", proxyDomains); put("outbound", "direct") })
                }
                put(JSONObject().apply { put("rule_set", "geosite-ru"); put("outbound", "direct") })
                put(JSONObject().apply { put("rule_set", "geoip-ru"); put("outbound", "direct") })
            })
            put("rule_set", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "geosite-ru")
                    put("type", "local")
                    put("format", "binary")
                    put("path", "geosite-ru.srs")
                })
                put(JSONObject().apply {
                    put("tag", "geoip-ru")
                    put("type", "local")
                    put("format", "binary")
                    put("path", "geoip-ru.srs")
                })
            })
        })

        return sb.toString(2).replace("\\/", "/")
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
