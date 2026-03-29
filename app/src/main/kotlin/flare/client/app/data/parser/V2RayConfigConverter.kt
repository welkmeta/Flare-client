package flare.client.app.data.parser

import android.util.Log
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Completely rewrites Xray/V2Ray configuration to Sing-box 1.13 format. */
object V2RayConfigConverter {

    fun convertIfNeeded(json: String): String {
        val trimmed = json.trim()
        return try {
            val obj = JSONObject(trimmed)
            when {
                isSingBoxFormat(obj) -> fixSingBox(obj)
                isV2RayFormat(obj) -> convertV2RayToSingBox(obj)
                else -> trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun isSingBoxFormat(obj: JSONObject): Boolean {
        val outbounds = obj.optJSONArray("outbounds")
        if (outbounds != null && outbounds.length() > 0) {
            val first = outbounds.optJSONObject(0)
            if (first?.has("type") == true) return true
        }
        return obj.has("route") && !obj.has("routing")
    }

    private fun isV2RayFormat(obj: JSONObject): Boolean {
        val outbounds = obj.optJSONArray("outbounds")
        if (outbounds != null && outbounds.length() > 0) {
            val first = outbounds.optJSONObject(0)
            if (first?.has("protocol") == true) return true
        }
        return obj.has("routing") || obj.has("outbounds")
    }

    fun convertV2RayToSingBox(xray: JSONObject): String {
        val sb = JSONObject()

        sb.put(
                "log",
                JSONObject().apply {
                    put("level", "info")
                    put("timestamp", true)
                }
        )

        val xrayOutbounds = xray.optJSONArray("outbounds") ?: JSONArray()
        val sbOutbounds = convertOutbounds(xrayOutbounds)

        ensureOutbound(sbOutbounds, "direct")
        ensureOutbound(sbOutbounds, "block")

        val proxyDomains = JSONArray()
        proxyDomains.put("raw.githubusercontent.com")
        for (i in 0 until sbOutbounds.length()) {
            val ob = sbOutbounds.optJSONObject(i) ?: continue
            val type = ob.optString("type")
            if (type == "direct" || type == "block") continue
            val server = ob.optString("server", "")
            if (server.isNotEmpty() && !server[0].isDigit()) {
                var found = false
                for (j in 0 until proxyDomains.length()) {
                    if (proxyDomains.optString(j) == server) found = true
                }
                if (!found) proxyDomains.put(server)
            }
        }

        // 2b. Extract routing rules for DNS and Route
        val xrayRouting = xray.optJSONObject("routing")
        val xrayRules = xrayRouting?.optJSONArray("rules")
        
        val routingRulesObjects = mutableListOf<JSONObject>()
        val requiredRuleSets = mutableSetOf<String>()
        val directRuleSets = mutableSetOf<String>()
        val directDomains = JSONArray()

        if (xrayRules != null) {
            for (i in 0 until xrayRules.length()) {
                val xRule = xrayRules.optJSONObject(i) ?: continue
                val outTag = xRule.optString("outboundTag", xRule.optString("outbound", "")) // xray uses outboundTag
                if (outTag.isEmpty()) continue
                
                val domains = xRule.optJSONArray("domain")
                if (domains != null && domains.length() > 0) {
                    val rawDomains = JSONArray()
                    for (j in 0 until domains.length()) {
                        val d = domains.optString(j, "")
                        if (d.startsWith("geosite:")) {
                            val gs = d.removePrefix("geosite:")
                            
                            // Prevent tunnel crash by ensuring only bundled .srs files are added
                            if (gs == "category-ru" || gs == "ru") {
                                requiredRuleSets.add("geosite-ru")
                                routingRulesObjects.add(JSONObject().apply { put("rule_set", "geosite-ru"); put("outbound", outTag) })
                                if (outTag == "direct" || outTag == "block") directRuleSets.add("geosite-ru")
                            }
                        } else if (d.isNotEmpty()) {
                            rawDomains.put(d)
                            if (outTag == "direct" || outTag == "block") directDomains.put(d)
                        }
                    }
                    if (rawDomains.length() > 0) {
                        routingRulesObjects.add(JSONObject().apply { put("domain", rawDomains); put("outbound", outTag) })
                    }
                }
                
                val ips = xRule.optJSONArray("ip")
                if (ips != null && ips.length() > 0) {
                    val rawIps = JSONArray()
                    var hasPrivate = false
                    for (j in 0 until ips.length()) {
                        val ip = ips.optString(j, "")
                        if (ip == "geoip:private") {
                            hasPrivate = true
                        } else if (ip.startsWith("geoip:")) {
                            val gi = ip.removePrefix("geoip:")
                            if (gi == "ru") {
                                requiredRuleSets.add("geoip-ru")
                                routingRulesObjects.add(JSONObject().apply { put("rule_set", "geoip-ru"); put("outbound", outTag) })
                            }
                        } else if (ip.isNotEmpty()) {
                            rawIps.put(ip)
                        }
                    }
                    if (hasPrivate) {
                        routingRulesObjects.add(JSONObject().apply { put("ip_is_private", true); put("outbound", outTag) })
                    }
                    if (rawIps.length() > 0) {
                        routingRulesObjects.add(JSONObject().apply { put("ip_cidr", rawIps); put("outbound", outTag) })
                    }
                }
            }
        }

        var primaryDns = "https://1.1.1.1/dns-query"
        var strategy = "prefer_ipv4"
        val xrayDns = xray.optJSONObject("dns")
        if (xrayDns != null) {
            if (xrayDns.optString("queryStrategy", "") == "UseIPv4") {
                strategy = "ipv4_only"
            }
            val servers = xrayDns.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val s = servers.opt(0)
                if (s is JSONObject) {
                    val addr = s.optString("address", "")
                    if (addr.isNotEmpty()) primaryDns = addr
                } else if (s is String && s.isNotEmpty()) {
                    primaryDns = s
                }
            }
        }

        val sbDns =
                JSONObject().apply {
                    put(
                            "servers",
                            JSONArray().apply {
                                put(
                                        JSONObject().apply {
                                            put("tag", "dns-remote")
                                            put("address", primaryDns)
                                            put("address_resolver", "dns-direct")
                                            put("detour", "proxy")
                                        }
                                )
                                put(
                                        JSONObject().apply {
                                            put("tag", "dns-direct")
                                            put("address", "8.8.8.8")
                                            put("detour", "direct")
                                        }
                                )
                                put(
                                        JSONObject().apply {
                                            put("tag", "dns-block")
                                            put("address", "rcode://success")
                                        }
                                )
                            }
                    )
                    put(
                            "rules",
                            JSONArray().apply {
                                put(
                                        JSONObject().apply {
                                            put("outbound", JSONArray().put("any"))
                                            put("server", "dns-direct")
                                        }
                                )
                                val dnsDirectDomains = JSONArray()
                                for (i in 0 until proxyDomains.length()) {
                                    dnsDirectDomains.put(proxyDomains.getString(i))
                                }
                                for (i in 0 until directDomains.length()) {
                                    dnsDirectDomains.put(directDomains.getString(i))
                                }
                                if (dnsDirectDomains.length() > 0) {
                                    put(
                                            JSONObject().apply {
                                                put("domain", dnsDirectDomains)
                                                put("server", "dns-direct")
                                            }
                                    )
                                }
                                for (rs in directRuleSets) {
                                    put(
                                            JSONObject().apply {
                                                put("rule_set", rs)
                                                put("server", "dns-direct")
                                            }
                                    )
                                }
                            }
                    )
                    put("final", "dns-remote")
                    put("strategy", strategy)
                    put("independent_cache", true)
                }
        sb.put("dns", sbDns)

        val sbInbounds = JSONArray()
        sbInbounds.put(createTunInbound(xray))
        sb.put("inbounds", sbInbounds)

        sb.put("outbounds", sbOutbounds)

        val sbRoute =
                JSONObject().apply {
                    put("auto_detect_interface", false)
                    put("final", "proxy")
                    
                    val sbRules = JSONArray().apply {
                        put(JSONObject().apply { put("protocol", "dns"); put("action", "hijack-dns") })
                        put(JSONObject().apply { put("port", 53); put("action", "hijack-dns") })
                    }
                    
                    for (rule in routingRulesObjects) {
                        sbRules.put(rule)
                    }

                    // Loop prevention rule
                    if (proxyDomains.length() > 0) {
                        sbRules.put(JSONObject().apply { put("domain", proxyDomains); put("outbound", "direct") })
                    }
                    
                    val sbRuleSets = JSONArray()
                    for (rs in requiredRuleSets) {
                        sbRuleSets.put(
                                JSONObject().apply {
                                    put("tag", rs)
                                    put("type", "local")
                                    put("format", "binary")
                                    put("path", "$rs.srs")
                                }
                        )
                    }

                    put("rules", sbRules)
                    if (sbRuleSets.length() > 0) {
                        put("rule_set", sbRuleSets)
                    }
                }
        sb.put("route", sbRoute)

        return sb.toString(2).replace("\\/", "/")
    }

    private fun convertOutbounds(xrayOutbounds: JSONArray): JSONArray {
        val sbOutbounds = JSONArray()
        for (i in 0 until xrayOutbounds.length()) {
            val xrayOb = xrayOutbounds.optJSONObject(i) ?: continue
            val protocol = xrayOb.optString("protocol", "").lowercase(Locale.ROOT)
            val tag = xrayOb.optString("tag", "outbound-$i")
            val sbOb = JSONObject().apply { put("tag", tag) }

            when (protocol) {
                "vless" -> convertVless(xrayOb, sbOb)
                "vmess" -> convertVmess(xrayOb, sbOb)
                "trojan" -> convertTrojan(xrayOb, sbOb)
                "shadowsocks" -> convertShadowsocks(xrayOb, sbOb)
                "freedom" -> sbOb.put("type", "direct")
                "blackhole" -> sbOb.put("type", "block")
                else -> continue
            }

            xrayOb.optJSONObject("mux")?.let { mux ->
                val flow = sbOb.optString("flow", "")
                val hasReality = sbOb.optJSONObject("tls")?.has("reality") ?: false

                // Vision and Reality are fundamentally incompatible with multiplexing in sing-box
                if (mux.optBoolean("enabled", false) && !flow.contains("vision") && !hasReality) {
                    sbOb.put(
                            "multiplex",
                            JSONObject().apply {
                                put("enabled", true)
                                put("protocol", "smux")
                                val conc = mux.optInt("concurrency", 8)
                                put("max_connections", if (conc <= 0) 8 else conc)
                                put("min_streams", 4)
                                put("max_streams", 64)
                            }
                    )
                }
            }
            sbOutbounds.put(sbOb)
        }
        return sbOutbounds
    }

    private fun convertVless(xrayOb: JSONObject, sbOb: JSONObject) {
        sbOb.put("type", "vless")
        val vnext =
                xrayOb.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: return
        val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return
        sbOb.put("server", vnext.optString("address"))
        sbOb.put("server_port", vnext.optInt("port"))
        sbOb.put("uuid", user.optString("id"))
        sbOb.put("flow", user.optString("flow", ""))
        sbOb.put("packet_encoding", "xudp")
        xrayOb.optJSONObject("streamSettings")?.let { convertStreamSettings(it, sbOb) }
    }

    private fun convertVmess(xrayOb: JSONObject, sbOb: JSONObject) {
        sbOb.put("type", "vmess")
        val vnext =
                xrayOb.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: return
        val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return
        sbOb.put("server", vnext.optString("address"))
        sbOb.put("server_port", vnext.optInt("port"))
        sbOb.put("uuid", user.optString("id"))
        sbOb.put("security", user.optString("security", "auto"))
        sbOb.put("packet_encoding", "xudp")
        xrayOb.optJSONObject("streamSettings")?.let { convertStreamSettings(it, sbOb) }
    }

    private fun convertTrojan(xrayOb: JSONObject, sbOb: JSONObject) {
        sbOb.put("type", "trojan")
        val server =
                xrayOb.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0)
                        ?: return
        sbOb.put("server", server.optString("address"))
        sbOb.put("server_port", server.optInt("port"))
        sbOb.put("password", server.optString("password"))
        xrayOb.optJSONObject("streamSettings")?.let { convertStreamSettings(it, sbOb) }
    }

    private fun convertShadowsocks(xrayOb: JSONObject, sbOb: JSONObject) {
        sbOb.put("type", "shadowsocks")
        val server =
                xrayOb.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0)
                        ?: return
        sbOb.put("server", server.optString("address"))
        sbOb.put("server_port", server.optInt("port"))
        sbOb.put("method", server.optString("method"))
        sbOb.put("password", server.optString("password"))
    }

    private fun convertStreamSettings(stream: JSONObject, sbOb: JSONObject) {
        val security = stream.optString("security", "none")
        val network = stream.optString("network", "tcp")

        if (security == "tls" || security == "reality") {
            val tls = JSONObject().apply { put("enabled", true) }
            val settings =
                    if (security == "tls") stream.optJSONObject("tlsSettings")
                    else stream.optJSONObject("realitySettings")

            settings?.let { s ->
                val sni = s.optString("serverName", "")
                if (sni.isNotEmpty()) tls.put("server_name", sni)
                val fp = s.optString("fingerprint", "chrome")

                // Add utls block (mandatory for reality and recommended for tls)
                val utlsObj =
                        JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", if (fp == "random") "chrome" else fp)
                        }
                tls.put("utls", utlsObj)

                if (security == "reality") {
                    val realityObj =
                            JSONObject().apply {
                                put("enabled", true)
                                put("public_key", s.optString("publicKey"))
                                val shortId = s.optString("shortId", "")
                                put("short_id", shortId)
                            }
                    tls.put("reality", realityObj)
                }
            }
            sbOb.put("tls", tls)
        }

        when (network) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings")
                sbOb.put(
                        "transport",
                        JSONObject().apply {
                            put("type", "ws")
                            put("path", ws?.optString("path", "/"))
                            ws?.optJSONObject("headers")?.let { put("headers", it) }
                        }
                )
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings")
                sbOb.put(
                        "transport",
                        JSONObject().apply {
                            put("type", "grpc")
                            put("service_name", grpc?.optString("serviceName", ""))
                        }
                )
            }
            "httpUpgrade", "h2" -> {
                val settings =
                        if (network == "httpUpgrade") stream.optJSONObject("httpUpgradeSettings")
                        else stream.optJSONObject("httpSettings")
                sbOb.put(
                        "transport",
                        JSONObject().apply {
                            put("type", "http")
                            put("path", settings?.optString("path", "/"))
                            val host = settings?.optString("host", "")
                            if (!host.isNullOrEmpty()) put("host", JSONArray().put(host))
                        }
                )
            }
        }
    }

    private fun createTunInbound(xray: JSONObject): JSONObject =
            JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put(
                        "address",
                        JSONArray().apply {
                            put("172.19.0.1/30")
                            put("fdfe:dcba:9876::1/126")
                        }
                )
                put("mtu", 1500)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "mixed")
                // Removed dns_hijack as it's not supported in this version's TunInboundOptions

                var sniffingEnabled = true
                xray.optJSONArray("inbounds")?.let { inbounds ->
                    for (i in 0 until inbounds.length()) {
                        if (inbounds.optJSONObject(i)
                                        ?.optJSONObject("sniffing")
                                        ?.optBoolean("enabled", false) == true
                        ) {
                            sniffingEnabled = true
                            break
                        }
                    }
                }
                put("sniff", sniffingEnabled)
                put("sniff_override_destination", sniffingEnabled)
            }

    private fun hasOutbound(obs: JSONArray, type: String): Boolean {
        for (i in 0 until obs.length()) if (obs.optJSONObject(i)?.optString("type") == type)
                return true
        return false
    }

    private fun ensureOutbound(obs: JSONArray, tag: String) {
        for (i in 0 until obs.length()) if (obs.optJSONObject(i)?.optString("tag") == tag) return
        val type = if (tag == "block") "block" else "direct"
        obs.put(
                JSONObject().apply {
                    put("type", type)
                    put("tag", tag)
                }
        )
    }

    private fun fixSingBox(obj: JSONObject): String {
        // 1. Handle rule_set placement (Move from route to top level)
        val route = obj.optJSONObject("route")
        if (route != null && route.has("rule_set")) {
            val ruleSets = route.optJSONArray("rule_set")
            // Ensure it's placed at the root, but only if not already there with same content
            if (!obj.has("rule_set")) {
                obj.put("rule_set", ruleSets)
                Log.d("V2RayConfigConverter", "Moved rule_set from route to root")
            }
            route.remove("rule_set")
        }

        // 1b. Catch "rule-set" (dash) as well for older/alternate builds
        if (route != null && route.has("rule-set")) {
            val ruleSets = route.optJSONArray("rule-set")
            if (!obj.has("rule-set") && !obj.has("rule_set")) {
                obj.put("rule_set", ruleSets)
                Log.d(
                        "V2RayConfigConverter",
                        "Moved rule-set (dash) from route to root as rule_set"
                )
            }
            route.remove("rule-set")
        }

        obj.optJSONArray("inbounds")?.let { inbs ->
            for (i in 0 until inbs.length()) {
                inbs.optJSONObject(i)?.takeIf { it.optString("type") == "tun" }?.apply {
                    put("sniff", true)
                    put("sniff_override_destination", true)
                    put("auto_route", true)
                    put("strict_route", true)
                    remove("dns_hijack")
                }
            }
        }

        val dns = obj.optJSONObject("dns") ?: JSONObject().also { obj.put("dns", it) }
        if (!dns.has("strategy")) {
            dns.put("strategy", "prefer_ipv4")
        }

        val dnsRules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
        val dnsRulesStr = dnsRules.toString()

        // Fix DNS Deadlocks (Resolve proxy server domains directly)
        val outbounds = obj.optJSONArray("outbounds")
        if (outbounds != null) {
            val proxyDomains = JSONArray()
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val server = ob.optString("server", "")
                if (server.isNotEmpty() && !server[0].isDigit() && !dnsRulesStr.contains(server)) {
                    proxyDomains.put(server)
                }
            }
            if (proxyDomains.length() > 0) {
                val newDnsRules = JSONArray()
                newDnsRules.put(
                        JSONObject().apply {
                            put("domain", proxyDomains)
                            put("server", "dns-direct")
                        }
                )
                for (i in 0 until dnsRules.length()) newDnsRules.put(dnsRules.get(i))
                dns.put("rules", newDnsRules)
            }
        }

        if (route != null) {
            route.put("auto_detect_interface", true)
            val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }

            // Add hijack-dns if not present at the start

            // Modern sing-box (1.8+) handles DNS hijack automatically for TUN inbounds.
            // Remove any deprecated hijack-dns actions from rules to prevent crashes.
            val newRules = JSONArray()
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                if (rule.optString("action") == "hijack-dns") continue
                if (rule.optString("protocol") == "dns" && rule.optString("action") == "hijack-dns")
                        continue
                if (rule.optInt("port") == 53 && rule.optString("action") == "hijack-dns") continue
                newRules.put(rule)
            }
            route.put("rules", newRules)

            // Clean up deprecated geosite/geoip and migrate to rule_set
            for (i in 0 until newRules.length()) {
                val rule = newRules.optJSONObject(i) ?: continue
                if (rule.has("geosite")) {
                    val gs = rule.optJSONArray("geosite")
                    if (gs != null) {
                        for (j in 0 until gs.length()) {
                            if (gs.optString(j) == "category-ru" || gs.optString(j) == "ru") {
                                rule.remove("geosite")
                                rule.put("rule_set", JSONArray().put("geosite-ru"))
                            }
                        }
                    }
                }
                if (rule.has("geoip")) {
                    val gi = rule.optJSONArray("geoip")
                    if (gi != null) {
                        for (j in 0 until gi.length()) {
                            if (gi.optString(j) == "ru") {
                                rule.remove("geoip")
                                rule.put("rule_set", JSONArray().put("geoip-ru"))
                            }
                        }
                    }
                }
            }

            // 5. Ensure rule_set definitions exist at top level if referred to in any rules
            val routeStr = route.toString()
            if (routeStr.contains("geosite-ru") || routeStr.contains("geoip-ru")) {
                val ruleSets =
                        obj.optJSONArray("rule_set") ?: JSONArray().also { obj.put("rule_set", it) }
                val tags = mutableSetOf<String>()
                for (i in 0 until ruleSets.length()) {
                    ruleSets.optJSONObject(i)?.optString("tag")?.let { tags.add(it) }
                }

                if (!tags.contains("geosite-ru")) {
                    Log.d("V2RayConfigConverter", "Adding missing geosite-ru rule_set definition")
                    ruleSets.put(
                            JSONObject().apply {
                                put("tag", "geosite-ru")
                                put("type", "local")
                                put("format", "binary")
                                put("path", "geosite-ru.srs")
                            }
                    )
                }
                if (!tags.contains("geoip-ru")) {
                    Log.d("V2RayConfigConverter", "Adding missing geoip-ru rule_set definition")
                    ruleSets.put(
                            JSONObject().apply {
                                put("tag", "geoip-ru")
                                put("type", "local")
                                put("format", "binary")
                                put("path", "geoip-ru.srs")
                            }
                    )
                }
            }
        }
        return obj.toString(2).replace("\\/", "/")
    }
}
