package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.net.ArpTable
import com.geno1024.ai.netdis.net.Subnet
import com.geno1024.ai.netdis.topo.TopologyBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Shared parsing for web-managed routers. Home gateways have no standard API:
 * some render HTML tables, some return JSON, and many embed JSON in a script
 * tag. Every web adapter feeds its pages through this object, which accepts all
 * three shapes and only keeps rows that contain both an address and identity
 * evidence.
 *
 * Keeping this vendor-neutral is what makes adding a brand small: an adapter
 * normally only contributes login requests and a list of page paths.
 */
object WebUiParsing {

    data class StatusFields(
        val model: String? = null,
        val firmware: String? = null,
        val uptime: String? = null,
        val wanIp: String? = null,
        val wanGateway: String? = null,
        val externalIp: String? = null,
        val lanCidrs: List<String> = emptyList(),
        val dnsServers: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = model == null && firmware == null && uptime == null &&
                wanIp == null && wanGateway == null && externalIp == null &&
                lanCidrs.isEmpty() && dnsServers.isEmpty()

        /** Fills gaps from [other] without overwriting values parsed from tables. */
        fun merge(other: StatusFields): StatusFields = StatusFields(
            model = model ?: other.model,
            firmware = firmware ?: other.firmware,
            uptime = uptime ?: other.uptime,
            wanIp = wanIp ?: other.wanIp,
            wanGateway = wanGateway ?: other.wanGateway,
            externalIp = externalIp ?: other.externalIp,
            lanCidrs = (lanCidrs + other.lanCidrs).distinct(),
            dnsServers = (dnsServers + other.dnsServers).distinct(),
        )
    }

    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")
    private val IP = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
    private val JSON_HINT = Regex(
        "(?i)(?:devicelist|device_list|clientlist|client_list|clients|hostlist|host_list|" +
            "hosts|dhcplist|dhcp_list|dhcpclients|onlinelist|online_list|maclist|mac_list|" +
            "data|list)\\s*[:=]\\s*",
    )
    private val TOKEN = Regex(
        "(?i)[\"']?(?:token|stok|sid|sessionid|session_id|sysauth|asus_token)[\"']?\\s*[:=]\\s*" +
            "[\"']?([A-Za-z0-9._~+=:-]{4,200})",
    )
    private val PASSWORD_INPUT = Regex(
        "type\\s*=\\s*[\"']?password|name\\s*=\\s*[\"']?password|id\\s*=\\s*[\"']?password",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_WORDS = Regex(
        "login|log in|sign in|登录|登陆|用户名|username|password|密码",
        RegexOption.IGNORE_CASE,
    )
    private val LOGOUT_WORDS = Regex(
        "logout|log off|logoff|注销|退出登录|退出|重启|reboot",
        RegexOption.IGNORE_CASE,
    )
    private val FAILURE_WORDS = Regex(
        "用户名或密码错误|密码错误|登录失败|登陆失败|Login failed|Invalid (?:user|password)|" +
            "Authentication failed|incorrect password|wrong password",
        RegexOption.IGNORE_CASE,
    )
    private val OFFLINE_WORDS = Regex(
        "离线|未连接|不在线|offline|inactive|disconnected|not connected",
        RegexOption.IGNORE_CASE,
    )
    private val WIRELESS_WORDS = Regex(
        "无线|wlan|wifi|wi-fi|5g|2\\.4g|5ghz|2\\.4ghz|ssid|wireless",
        RegexOption.IGNORE_CASE,
    )
    private val IGNORED_CELLS = setOf(
        "主机名", "hostname", "name", "设备名", "device", "设备", "ip", "ip地址", "ip address",
        "mac", "mac地址", "mac address", "接口", "interface", "类型", "type", "状态", "status",
        "连接方式", "模式", "mode", "ssid", "信号", "signal", "备注", "note", "序号", "no.",
        "在线", "离线", "online", "offline", "无线", "有线", "wifi", "ethernet", "lan", "wan",
        "dhcp", "static", "dynamic", "动态", "静态", "已连接", "未连接", "连接", "未知", "unknown",
    )
    private val IP_KEYS = listOf(
        "ip", "ipaddr", "ip_addr", "ipaddress", "ip_address", "address", "lanip", "lan_ip",
        "ipv4", "ipv4addr", "ipv4_addr", "client_ip", "host_ip", "device_ip",
    )
    private val MAC_KEYS = listOf(
        "mac", "macaddr", "mac_addr", "macaddress", "mac_address", "hwaddr", "hw_addr",
        "bssid", "client_mac", "device_mac",
    )
    private val HOSTNAME_KEYS = listOf(
        "hostname", "host_name", "name", "nickname", "device_name", "devname", "dev_name",
        "comment", "remark", "alias", "client_name", "host", "display_name",
    )
    private val ONLINE_KEYS = listOf(
        "online", "is_online", "isonline", "active", "is_active", "isactive", "connected",
        "is_connected", "connect", "state", "status",
    )
    private val WIRELESS_KEYS = listOf(
        "iswl", "is_wl", "iswireless", "is_wireless", "wireless", "wifi", "is_wifi",
        "connection_type", "conn_type", "type", "interface", "iface", "ssid", "band",
    )

    fun parseStatus(pages: List<String>): StatusFields =
        pages.fold(StatusFields()) { acc, page -> acc.merge(statusOf(page)) }

    fun parseClients(text: String, routerId: String? = null): List<HostRecord> =
        RouterParsing.mergeHosts(
            (htmlClients(text, routerId) + jsonClients(text, routerId))
                .filter { TopologyBuilder.isPrivate(it.ip) },
        )

    fun looksLikeLoginPage(text: String): Boolean =
        !looksLoggedIn(text) && PASSWORD_INPUT.containsMatchIn(text) && LOGIN_WORDS.containsMatchIn(text)

    fun looksLoggedIn(text: String): Boolean = LOGOUT_WORDS.containsMatchIn(text)

    fun loginFailed(text: String): Boolean = FAILURE_WORDS.containsMatchIn(text)

    fun extractToken(body: String): String? =
        TOKEN.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.length in 4..200 }

    fun tableRows(html: String): List<List<String>> =
        ROW.findAll(html).map { row ->
            CELL.findAll(row.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
        }.filter { it.isNotEmpty() }.toList()

    /** Flattens two-cell label/value rows, which is where status pages keep WAN details. */
    fun tablePairs(html: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        for (row in tableRows(html)) {
            var index = 0
            while (index + 1 < row.size) {
                val label = row[index]
                val value = row[index + 1]
                if (label.isNotEmpty() && value.isNotEmpty()) result += label to value
                index += 2
            }
        }
        return result
    }

    fun clean(fragment: String): String = fragment
        .replace(TAG, " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun statusOf(text: String): StatusFields {
        val pairs = tablePairs(text)
        val fields = jsonFields(text)

        fun pair(vararg keys: String): String? = pairs.firstOrNull { (label, _) ->
            val lower = label.lowercase()
            keys.any { lower.contains(it) }
        }?.second

        fun field(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { fields[it] }

        val model = pair("型号", "model", "设备类型", "product")
            ?: field(
                "model", "productname", "product_name", "productid", "device_name",
                "device_type", "board_name", "boardname", "hwtype", "hardware",
            )
        val firmware = pair("软件版本", "固件", "firmware", "version")
            ?: field(
                "firmware", "firmver", "fwversion", "fw_version", "firmware_version",
                "software_version", "version", "release",
            )
        val uptime = pair("运行时间", "uptime", "已运行", "开机时间")
            ?: field("uptime", "up_time", "runtime", "sysuptime", "system_uptime")

        val wanIp = pairs.firstOrNull { (label, value) ->
            val lower = label.lowercase()
            val isWan = lower.contains("wan") || lower.contains("广域网") ||
                lower.contains("外网") || lower.contains("internet")
            isWan && !lower.contains("mac") && !lower.contains("状态") &&
                !lower.contains("gateway") && !lower.contains("网关") &&
                IP.find(value)?.value?.let { Subnet.parseIp(it) != null } == true
        }?.let { IP.find(it.second)?.value }
            ?: field(
                "wan_ip", "wanip", "wan_ipaddr", "wan0_ipaddr", "wan1_ipaddr",
                "internet_ip", "public_ip",
            )?.let { IP.find(it)?.value }

        val wanGateway = pairs.firstOrNull { (label, value) ->
            val lower = label.lowercase()
            (lower.contains("网关") || lower.contains("gateway")) &&
                IP.find(value)?.value?.let { Subnet.parseIp(it) != null } == true
        }?.let { IP.find(it.second)?.value }
            ?: field(
                "wan_gateway", "gateway", "wan0_gateway", "wan1_gateway", "default_gateway",
            )?.let { IP.find(it)?.value }

        val externalIp = field("external_ip", "public_ip", "internet_ip", "wan_public_ip")
            ?.let { IP.find(it)?.value }

        val lanCidrs = ArrayList<String>()
        for ((label, value) in pairs) {
            val lower = label.lowercase()
            if (!lower.contains("lan") && !lower.contains("局域网") && !lower.contains("local")) continue
            val ips = IP.findAll(value).map { it.value }.toList()
            if (ips.isEmpty()) continue
            val cidr = RouterParsing.cidrFrom(ips[0], ips.getOrNull(1))
                ?: RouterParsing.cidrFrom(ips[0], "24")
            cidr?.let { if (it !in lanCidrs) lanCidrs += it }
        }
        val lanIp = field("lan_ip", "lan_ipaddr", "lan0_ipaddr", "lan_address")
            ?.let { IP.find(it)?.value }
        val lanMask = field("lan_netmask", "netmask", "lan_mask", "subnet_mask")
        if (lanIp != null) {
            RouterParsing.cidrFrom(lanIp, lanMask ?: "24")?.let {
                if (it !in lanCidrs) lanCidrs += it
            }
        }

        val dnsServers = ArrayList<String>()
        for ((label, value) in pairs) {
            val lower = label.lowercase()
            if (!lower.contains("dns") && !lower.contains("域名服务器") &&
                !lower.contains("domain name server")
            ) {
                continue
            }
            for (ip in IP.findAll(value).map { it.value }) {
                if (ip !in dnsServers) dnsServers += ip
            }
        }
        for (key in listOf(
            "dns", "dns_server", "dns_servers", "dns1", "dns2",
            "wan0_dns", "wan1_dns", "primary_dns", "secondary_dns",
        )) {
            val value = fields[key] ?: continue
            for (ip in IP.findAll(value).map { it.value }) {
                if (ip !in dnsServers) dnsServers += ip
            }
        }

        return StatusFields(
            model = model?.takeIf { it.length in 2..80 },
            firmware = firmware?.takeIf { it.length in 2..80 },
            uptime = uptime?.takeIf { it.length in 2..80 },
            wanIp = wanIp,
            wanGateway = wanGateway,
            externalIp = externalIp,
            lanCidrs = lanCidrs,
            dnsServers = dnsServers,
        )
    }

    private fun htmlClients(html: String, routerId: String?): List<HostRecord> {
        val result = ArrayList<HostRecord>()
        for (row in tableRows(html)) {
            if (row.size < 2) continue
            val joined = row.joinToString(" ")
            val upper = joined.uppercase()
            if (upper.contains("MAC") && (upper.contains("IP") || upper.contains("地址"))) continue
            val lower = joined.lowercase()
            if (lower.contains("子网掩码") || lower.contains("netmask") ||
                lower.contains("默认网关") || lower.contains("default gateway") ||
                lower.contains("域名服务器") || lower.contains("domain name server")
            ) {
                continue
            }

            val ip = row.asSequence()
                .mapNotNull { IP.find(it)?.value }
                .firstOrNull { Subnet.parseIp(it) != null && isHostAddress(it) }
                ?: continue
            val mac = row.asSequence()
                .mapNotNull { MAC.find(it)?.value }
                .firstOrNull()
                ?.let(ArpTable::normalizeMac)
            val hostname = row.asSequence()
                .map { clean(it) }
                .firstOrNull { cell ->
                    cell.length in 1..64 &&
                        !IP.containsMatchIn(cell) &&
                        !MAC.containsMatchIn(cell) &&
                        cell.lowercase() !in IGNORED_CELLS &&
                        cell.toIntOrNull() == null &&
                        cell !in setOf("-", "--", "—", "...", "…")
                }
            if (mac == null && hostname == null) continue

            val wireless = WIRELESS_WORDS.containsMatchIn(joined)
            val offline = OFFLINE_WORDS.containsMatchIn(joined)
            result += HostRecord(
                ip = ip,
                mac = mac,
                hostname = hostname,
                online = !offline,
                link = if (wireless) LinkKind.WIFI else LinkKind.ETHERNET,
                sources = setOf(HostSource.DHCP, HostSource.ROUTER_ARP),
                routerId = routerId,
                extra = buildMap {
                    put("lease", "DHCP")
                    if (wireless) put("ssid", "无线")
                },
            )
        }
        return result
    }

    private fun jsonClients(text: String, routerId: String?): List<HostRecord> {
        val result = ArrayList<HostRecord>()
        for (root in jsonRoots(text)) {
            walkJson(root) { obj, parentMac ->
                val ip = extractIp(obj) ?: return@walkJson
                val mac = (MAC_KEYS.firstNotNullOfOrNull { extractMac(opt(obj, it)) }
                    ?: parentMac?.let(ArpTable::normalizeMac))
                val hostname = HOSTNAME_KEYS.firstNotNullOfOrNull { key ->
                    jsonText(opt(obj, key))?.let(::clean)?.takeIf {
                        it.length in 1..64 && it != "*" && it != "-" &&
                            !IP.containsMatchIn(it) && !MAC.containsMatchIn(it)
                    }
                }
                if (mac == null && hostname == null) return@walkJson

                val wireless = WIRELESS_KEYS.any { key -> isWireless(key, opt(obj, key)) }
                val online = ONLINE_KEYS.firstNotNullOfOrNull { key ->
                    opt(obj, key)?.let(::truthy)
                } ?: true
                val extra = LinkedHashMap<String, String>()
                for (key in listOf("ssid", "signal", "signal_strength", "band", "vendor", "manufacturer")) {
                    jsonText(opt(obj, key))?.takeIf { it.isNotBlank() }?.let { extra[key] = it }
                }
                result += HostRecord(
                    ip = ip,
                    mac = mac,
                    hostname = hostname,
                    vendor = jsonText(opt(obj, "vendor")) ?: jsonText(opt(obj, "manufacturer")),
                    online = online,
                    link = if (wireless) LinkKind.WIFI else LinkKind.ETHERNET,
                    sources = setOf(HostSource.DHCP, HostSource.ROUTER_ARP),
                    routerId = routerId,
                    extra = buildMap {
                        put("lease", "DHCP")
                        if (wireless) put("ssid", extra["ssid"] ?: "无线")
                    } + extra,
                )
            }
        }
        return result
    }

    private fun jsonRoots(text: String): List<Any> {
        val roots = ArrayList<Any>()
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()?.let { roots += it }
        }
        var count = 0
        for (match in JSON_HINT.findAll(text)) {
            if (count >= 16) break
            val start = findJsonStart(text, match.range.last + 1) ?: continue
            val end = findJsonEnd(text, start) ?: continue
            if (end - start > 512 * 1024) continue
            runCatching { JSONTokener(text.substring(start, end)).nextValue() }.getOrNull()?.let {
                roots += it
                count++
            }
        }
        return roots
    }

    private fun findJsonStart(text: String, from: Int): Int? {
        var index = from
        while (index < text.length) {
            when (text[index]) {
                '[' , '{' -> return index
                '\n', '\r', ';', '<' -> return null
                else -> if (!text[index].isWhitespace()) return null
            }
            index++
        }
        return null
    }

    private fun findJsonEnd(text: String, start: Int): Int? {
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
    }

    private fun walkJson(value: Any?, parentMac: String? = null, visit: (JSONObject, String?) -> Unit) {
        when (value) {
            is JSONObject -> {
                visit(value, parentMac)
                for (key in value.keys()) {
                    val child = value.opt(key)
                    val macKey = if (MAC.matches(key)) key else null
                    walkJson(child, macKey, visit)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) walkJson(value.opt(index), null, visit)
            }
        }
    }

    private fun jsonFields(text: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for (root in jsonRoots(text)) {
            walkJson(root) { obj, _ ->
                for (key in obj.keys()) {
                    val value = obj.opt(key)
                    if (value is String || value is Number) {
                        val lower = key.lowercase()
                        fields.putIfAbsent(lower, value.toString())
                        // ASUS appGet returns {"nvram_get(productid)":"RT-AC68U"}.
                        if (lower.startsWith("nvram_get(") && lower.endsWith(")")) {
                            fields.putIfAbsent(
                                lower.removePrefix("nvram_get(").removeSuffix(")"),
                                value.toString(),
                            )
                        }
                    }
                }
            }
        }
        return fields
    }

    private fun extractIp(value: Any?): String? = when (value) {
        is String -> IP.find(value)?.value?.takeIf {
            Subnet.parseIp(it) != null && isHostAddress(it)
        }

        is JSONObject -> IP_KEYS.asSequence().mapNotNull { extractIp(opt(value, it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { extractIp(value.opt(it)) }
            .firstOrNull()

        else -> null
    }

    private fun extractMac(value: Any?): String? = when (value) {
        is String -> MAC.find(value)?.value?.let(ArpTable::normalizeMac)
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { extractMac(value.opt(it)) }
            .firstOrNull()

        else -> null
    }

    private fun isWireless(key: String, value: Any?): Boolean = when {
        value == null -> false
        key.equals("ssid", ignoreCase = true) -> jsonText(value)?.isNotBlank() == true
        value is Boolean -> value
        value is Number -> value.toInt() != 0
        value is String -> {
            val lower = value.lowercase()
            lower == "1" || lower == "true" || lower == "yes" ||
                "wifi" in lower || "wireless" in lower || "wlan" in lower ||
                "5g" in lower || "2.4g" in lower || "5ghz" in lower
        }

        else -> false
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> {
            val lower = value.lowercase()
            lower !in setOf(
                "0", "false", "no", "off", "offline", "inactive", "disconnected",
                "未连接", "离线", "不在线",
            )
        }

        else -> true
    }

    /** Firmware JSON is not consistent about key case (`isWL`, `IP`, `Mac`). */
    private fun opt(obj: JSONObject, key: String): Any? {
        if (obj.has(key)) return obj.opt(key)
        for (candidate in obj.keys()) {
            if (candidate.equals(key, ignoreCase = true)) return obj.opt(candidate)
        }
        return null
    }

    private fun jsonText(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun isHostAddress(ip: String): Boolean {
        val first = ip.substringBefore('.').toIntOrNull() ?: return false
        val last = ip.substringAfterLast('.').toIntOrNull() ?: return false
        return first in 1..223 && last !in setOf(0, 255)
    }
}
