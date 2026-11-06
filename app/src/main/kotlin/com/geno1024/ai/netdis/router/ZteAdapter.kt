package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.net.ArpTable
import com.geno1024.ai.netdis.net.Subnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * ZTE ZXHN home gateways.
 *
 * The E1600 (and the closely related F-series) log in with a token that is
 * embedded in the landing page, then a one-time salt:
 *
 * 1. `GET /` sets a session cookie and contains
 *    `addParameter("_sessionTOKEN", "…")`.
 * 2. `GET /function_module/login_module/login_page/logintoken_lua.lua` returns
 *    the salt as the text of an XML response.
 * 3. `POST /` with `Username`, `Password = sha256(password + salt)`,
 *    `action=login` and `_sessionTOKEN`.
 *
 * After login the client list is XML rather than HTML:
 * `/common_page/Query_AccessDevice_lua.lua?Interface=2.4G|5G|LAN`. Status pages
 * use the same `ParaName`/`ParaValue` instance shape.
 *
 * Older firmware without the token endpoint still works through the legacy
 * `/asp/GetRandCount.asp` + base64 `/login.gch` flow.
 */
object ZteAdapter : RouterAdapter {

    override val type: RouterType = RouterType.ZTE

    private val xmlHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/xml, text/xml, */*",
    )

    private val clientInterfaces = listOf("2.4G", "5G", "LAN")

    private val statusPaths = listOf(
        "/common_page/ManagReg_lua.lua",
        "/getpage.lua?pid=1005&nextpage=Internet_WANInfo_lua.lua",
        "/common_page/Localnet_LanMgrIpv4_DHCPBasicCfg_lua.lua",
        "/common_page/home_a_wlan_state.lua",
    )

    private val legacyClientPaths = listOf(
        "/getpage.gch?pid=1002&nextpage=IPv4IPv6_landevice_t.gch",
        "/getpage.gch?pid=1002&nextpage=net_clients_t.gch",
        "/getpage.gch?pid=1002&nextpage=dhcp_clients_t.gch",
        "/getpage.gch?pid=1002&nextpage=wlan_client_t.gch",
    )

    override suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome =
        withContext(Dispatchers.IO) {
            val base = config.baseUrl
            val session = RouterSession()

            // The landing page normally sets the first session cookie.
            val landing = RouterHttp.request(
                url = base + "/",
                session = session,
                timeoutMs = timeoutMs,
                headers = mapOf("Accept" to "text/html, application/xhtml+xml, */*"),
            )
            if (landing.error != null) {
                return@withContext failure("无法连接中兴路由器：${landing.error}", "中兴 ZTE")
            }

            val token = ZteParsing.extractLoginToken(landing.body)
            var loginOk = !ZteParsing.looksLikeLoginPage(landing.body)
            var loginMessage: String? = null

            if (!loginOk) {
                if (token != null) {
                    val salt = fetchSalt(base, session, timeoutMs)
                    val result = modernLogin(config, base, session, token, salt, timeoutMs)
                    if (result.error == null && result.ok && !ZteParsing.looksLikeLoginPage(result.body)) {
                        loginOk = true
                    } else {
                        loginMessage = result.error
                    }
                }
                if (!loginOk) {
                    // Keep the old firmware path working; it costs two requests.
                    loginOk = legacyLogin(config, base, session, token.orEmpty(), timeoutMs)
                            .any { it.error == null && it.ok && !ZteParsing.looksLikeLoginPage(it.body) }
                }
            }

            if (!loginOk) {
                val message = when {
                    loginMessage != null -> "无法连接中兴路由器：$loginMessage"
                    else -> "登录失败（中兴用户名或密码错误，或页面仍要求登录）"
                }
                return@withContext failure(message, "中兴 ZTE")
            }

            val xmlPages = ArrayList<String>()
            for (path in statusPaths) {
                val result = RouterHttp.request(
                    url = base + path + "?_=${System.currentTimeMillis()}",
                    session = session,
                    timeoutMs = timeoutMs,
                    headers = xmlHeaders,
                )
                if (result.error != null) break
                if (result.ok) xmlPages += result.body
            }

            val clients = ArrayList<HostRecord>()
            for (iface in clientInterfaces) {
                val result = RouterHttp.request(
                    url = base + "/common_page/Query_AccessDevice_lua.lua?Interface=" +
                        encode(iface) + "&_=${System.currentTimeMillis()}",
                    session = session,
                    timeoutMs = timeoutMs,
                    headers = xmlHeaders,
                )
                if (result.error != null) break
                if (!result.ok) continue
                clients += ZteParsing.parseAccessDevices(result.body, config.id)
            }

            var legacyHtml = ""
            if (clients.isEmpty()) {
                val html = ArrayList<String>()
                for (path in legacyClientPaths) {
                    val result = RouterHttp.request(base + path, session = session, timeoutMs = timeoutMs)
                    if (result.error != null) break
                    if (!result.ok) continue
                    html += result.body
                    if (ZteParsing.parseClients(result.body, config.id).isNotEmpty()) break
                }
                legacyHtml = html.joinToString("\n")
                clients += html.flatMap { ZteParsing.parseClients(it, config.id) }
            }

            val merged = RouterParsing.mergeHosts(
                clients.filterNot { it.ip == config.host },
            )
            val instances = xmlPages.flatMap(ZteParsing::parseInstances)
            val model = value(instances, "ModelName") ?: ZteParsing.extractModel(
                ZteParsing.tablePairs(legacyHtml),
                legacyHtml,
            )
            val firmware = value(instances, "SoftwareVer") ?: ZteParsing.extractFirmware(
                ZteParsing.tablePairs(legacyHtml),
                legacyHtml,
            )
            val uptime = value(instances, "UpTime")?.toLongOrNull()
                ?.let(RouterParsing::formatUptime)
                ?.takeIf { it.isNotBlank() }
            val wan = instances.firstOrNull {
                it["IPAddress"] != null && (it["GateWay"] != null || it["SubNetMask"] != null)
            }
            val wanIp = wan?.get("IPAddress")
            val wanGateway = wan?.get("GateWay")
            val dnsServers = listOfNotNull(wan?.get("DNS1"), wan?.get("DNS2"))
                .map { it.trim() }
                .filter { Subnet.parseIp(it) != null && it != "0.0.0.0" }
                .distinct()
            val lanIp = value(instances, "BrIPAddr") ?: value(instances, "IPAddr")
            val lanMask = value(instances, "SubMask") ?: value(instances, "SubnetMask")
            val lanCidrs = listOfNotNull(RouterParsing.cidrFrom(lanIp ?: "", lanMask))
            val mac = ArpTable.normalizeMac(value(instances, "WorkIFMac"))
                ?: ArpTable.normalizeMac(value(instances, "MACAddress"))
                ?: ArpTable.normalizeMac(config.mac)

            RouterScanOutcome(
                success = true,
                type = type,
                message = if (merged.isEmpty()) {
                    "已登录中兴路由器（未解析到客户端列表）"
                } else {
                    "已登录中兴路由器"
                },
                model = model,
                firmware = firmware,
                uptime = uptime,
                wanIp = wanIp,
                wanGateway = wanGateway,
                lanCidrs = lanCidrs,
                dnsServers = dnsServers,
                clients = merged,
                wirelessCount = merged.count { it.link == LinkKind.WIFI },
                fingerprint = listOfNotNull(model, firmware).joinToString(" · ")
                    .ifBlank { "中兴 ZTE" },
                mac = mac,
            )
        }

    private fun value(instances: List<Map<String, String>>, key: String): String? =
        instances.firstNotNullOfOrNull { it[key]?.trim()?.takeIf(String::isNotBlank) }

    private suspend fun fetchSalt(base: String, session: RouterSession, timeoutMs: Int): String {
        val result = RouterHttp.request(
            url = base + "/function_module/login_module/login_page/logintoken_lua.lua" +
                "?_=${System.currentTimeMillis()}",
            session = session,
            timeoutMs = timeoutMs,
            headers = xmlHeaders + ("Referer" to base + "/"),
        )
        if (result.error != null || !result.ok) return ""
        return ZteParsing.extractSalt(result.body)
    }

    private suspend fun modernLogin(
        config: RouterConfig,
        base: String,
        session: RouterSession,
        token: String,
        salt: String,
        timeoutMs: Int,
    ): HttpResult {
        val username = config.username.ifBlank { "admin" }
        val passwordHash = sha256(config.password + salt)
        val form = buildString {
            append("Username=").append(encode(username))
            append("&Password=").append(encode(passwordHash))
            append("&action=login")
            append("&_sessionTOKEN=").append(encode(token))
        }
        return RouterHttp.request(
            url = base + "/",
            method = "POST",
            body = form,
            contentType = "application/x-www-form-urlencoded",
            session = session,
            timeoutMs = timeoutMs,
            headers = xmlHeaders + mapOf(
                "Referer" to base + "/",
                "Origin" to base,
            ),
        )
    }

    private suspend fun legacyLogin(
        config: RouterConfig,
        base: String,
        session: RouterSession,
        token: String,
        timeoutMs: Int,
    ): List<HttpResult> {
        val username = config.username.ifBlank { "admin" }
        val encodedUser = base64(username)
        val encodedPassword = base64(config.password)
        val tokenSuffix = if (token.isBlank()) "" else "&_sessionTOKEN=${encode(token)}"
        val forms = listOf(
            "action=login&Username=$encodedUser&Password=$encodedPassword&Frm_LoginType=0$tokenSuffix",
            "action=login&Username=$encodedUser&Password=$encodedPassword$tokenSuffix",
            "Username=$encodedUser&Password=$encodedPassword&action=login$tokenSuffix",
        )
        val results = ArrayList<HttpResult>()
        for (form in forms) {
            val result = RouterHttp.request(
                url = base + "/login.gch",
                method = "POST",
                body = form,
                contentType = "application/x-www-form-urlencoded",
                session = session,
                timeoutMs = timeoutMs,
            )
            results += result
            if (result.error != null) break
            if (result.code !in 200..399) continue
            if (ZteParsing.looksLikeLoginPage(result.body)) continue
            break
        }
        return results
    }

    private fun failure(message: String, fingerprint: String?): RouterScanOutcome =
        RouterScanOutcome(
            success = false,
            type = type,
            message = message,
            fingerprint = fingerprint,
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * Pure ZTE helpers, kept separate so firmware-specific parsing can be tested
 * without a router. HTML tables are still accepted for older firmware; the
 * E1600 family returns XML with `ParaName`/`ParaValue` pairs.
 */
object ZteParsing {

    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")
    private val IP = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
    private val RAND_VALUE = Regex("[A-Za-z0-9+/=_-]{4,64}")
    private val RAND_FIELD = Regex(
        "(?:GetRandCount|_sessionTOKEN|rand|token)\\s*[=:]\\s*[\"']?([A-Za-z0-9+/=_-]{4,64})",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_TOKEN = Regex(
        "addParameter\\(\\s*\"_sessionTOKEN\"\\s*,\\s*\"([^\"]+)\"\\s*\\)",
    )
    private val INSTANCE = Regex(
        "<Instance[^>]*>(.*?)</Instance>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PARA = Regex(
        "<ParaName[^>]*>(.*?)</ParaName>\\s*<ParaValue[^>]*>(.*?)</ParaValue>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PASSWORD_INPUT = Regex(
        "type\\s*=\\s*[\"']?password|name\\s*=\\s*[\"']?password|Frm_Password",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_WORDS = Regex("login|登录|用户名|Username", RegexOption.IGNORE_CASE)
    private val LOGOUT_WORDS = Regex("logout|logoff|注销|退出登录", RegexOption.IGNORE_CASE)
    private val FAILURE_WORDS = Regex(
        "用户名或密码错误|密码错误|登录失败|Login failed|Invalid (?:user|password)|Authentication failed",
        RegexOption.IGNORE_CASE,
    )
    private val MODEL = Regex("(?:ZXHN|ZTE)[\\s_-]?[A-Z0-9][A-Z0-9_-]{2,}", RegexOption.IGNORE_CASE)
    private val FIRMWARE = Regex(
        "(?:软件版本|固件版本|Firmware(?: Version)?)[^A-Za-z0-9]{0,24}([Vv]?[0-9][A-Za-z0-9._-]{2,})",
        RegexOption.IGNORE_CASE,
    )
    private val OFFLINE_WORDS = Regex("离线|未连接|offline|inactive", RegexOption.IGNORE_CASE)
    private val WIRELESS_WORDS = Regex("无线|wlan|wifi|wi-fi|5g|2\\.4g|ssid", RegexOption.IGNORE_CASE)
    private val HEADER_CELLS = setOf(
        "主机名", "hostname", "name", "设备名", "device", "ip", "ip地址", "ip address",
        "mac", "mac地址", "mac address", "接口", "interface", "类型", "type", "状态", "status",
        "连接方式", "模式", "mode", "ssid", "信号", "signal", "备注", "note", "序号", "no.",
    )

    fun extractRand(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.length in 4..64 && trimmed.matches(RAND_VALUE)) return trimmed
        return RAND_FIELD.find(body)?.groupValues?.getOrNull(1)
    }

    /** `addParameter("_sessionTOKEN", "397667032103403366315799")` in the login page. */
    fun extractLoginToken(html: String): String? =
        LOGIN_TOKEN.find(html)?.groupValues?.getOrNull(1)
            ?: RAND_FIELD.find(html)?.groupValues?.getOrNull(1)

    /** The token endpoint answers `<ajax_response_xml_root>63178618</ajax_response_xml_root>`. */
    fun extractSalt(body: String): String {
        val text = decodeEntities(body.replace(TAG, "")).trim()
        return text.takeIf { it.length in 1..64 && !it.contains('<') }.orEmpty()
    }

    fun loginFailed(html: String): Boolean = FAILURE_WORDS.containsMatchIn(html)

    fun looksLoggedIn(html: String): Boolean = LOGOUT_WORDS.containsMatchIn(html)

    fun looksLikeLoginPage(html: String): Boolean =
        !looksLoggedIn(html) && PASSWORD_INPUT.containsMatchIn(html) && LOGIN_WORDS.containsMatchIn(html)

    /** Flattens one XML response into `ParaName` → `ParaValue` maps, one per instance. */
    fun parseInstances(xml: String): List<Map<String, String>> =
        INSTANCE.findAll(xml).map { match ->
            PARA.findAll(match.groupValues[1]).associate { pair ->
                clean(pair.groupValues[1]) to decodeEntities(pair.groupValues[2]).trim()
            }
        }.filter { it.isNotEmpty() }.toList()

    /**
     * Client rows from `Query_AccessDevice_lua.lua`. `Active` is the online flag;
     * `Band`/`Interface` distinguishes Wi-Fi from the wired LAN.
     */
    fun parseAccessDevices(xml: String, routerId: String? = null): List<HostRecord> =
        parseInstances(xml).mapNotNull { instance ->
            val ip = instance["IPAddress"]?.trim()
                ?.takeIf { Subnet.parseIp(it) != null && isHostAddress(it) }
                ?: return@mapNotNull null
            val mac = instance["MACAddress"]?.let(ArpTable::normalizeMac)
            val hostname = instance["HostName"]?.takeIf { it.isNotBlank() }
                ?: instance["DevName"]?.takeIf { it.isNotBlank() }
            val band = instance["Band"]?.takeIf { it.isNotBlank() }
                ?: instance["ConnectType"]?.takeIf { it.isNotBlank() }
            val iface = instance["Interface"].orEmpty()
            val wireless = WIRELESS_WORDS.containsMatchIn(band.orEmpty() + " " + iface)
            val active = instance["Active"]?.trim() != "0"
            HostRecord(
                ip = ip,
                mac = mac,
                hostname = hostname,
                online = active,
                link = if (wireless) LinkKind.WIFI else LinkKind.ETHERNET,
                sources = setOf(HostSource.DHCP, HostSource.ROUTER_ARP),
                routerId = routerId,
                extra = buildMap {
                    put("lease", "DHCP")
                    band?.let { put("band", it) }
                    if (iface.isNotBlank()) put("interface", iface)
                    instance["AliasName"]?.takeIf { it.isNotBlank() }?.let { put("ssid", it) }
                },
            )
        }.distinctBy { it.ip }

    fun parseClients(html: String, routerId: String? = null): List<HostRecord> {
        val result = ArrayList<HostRecord>()
        for (row in tableRows(html)) {
            if (row.size < 2) continue
            val joined = row.joinToString(" ")
            val upper = joined.uppercase()
            if (upper.contains("MAC") && (upper.contains("IP") || upper.contains("地址"))) continue
            if (joined.contains("子网掩码") || joined.contains("Subnet Mask", true)) continue
            if (joined.contains("默认网关") || joined.contains("Default Gateway", true)) continue

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
                        cell.lowercase() !in HEADER_CELLS &&
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

    /** Flattens two-cell label/value rows, which is where status pages keep WAN details. */
    fun tablePairs(html: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        for (row in tableRows(html)) {
            var index = 0
            while (index + 1 < row.size) {
                val label = clean(row[index])
                val value = clean(row[index + 1])
                if (label.isNotEmpty() && value.isNotEmpty()) result += label to value
                index += 2
            }
        }
        return result
    }

    fun extractModel(pairs: List<Pair<String, String>>, html: String): String? {
        for ((label, value) in pairs) {
            val key = label.lowercase()
            if (key.contains("型号") || key.contains("model") || key.contains("设备类型")) {
                value.takeIf { it.length in 2..64 }?.let { return it }
            }
        }
        return MODEL.find(html)?.value?.trim()?.take(64)
    }

    fun extractFirmware(pairs: List<Pair<String, String>>, html: String): String? {
        for ((label, value) in pairs) {
            val key = label.lowercase()
            if (key.contains("软件版本") || key.contains("固件") || key.contains("firmware") ||
                key.contains("version")
            ) {
                value.takeIf { it.length in 2..80 }?.let { return it }
            }
        }
        return FIRMWARE.find(html)?.groupValues?.getOrNull(1)?.take(80)
    }

    fun extractUptime(pairs: List<Pair<String, String>>): String? {
        for ((label, value) in pairs) {
            val key = label.lowercase()
            if (key.contains("运行时间") || key.contains("uptime") || key.contains("up time")) {
                value.takeIf { it.length in 2..80 }?.let { return it }
            }
        }
        return null
    }

    fun extractWanIp(pairs: List<Pair<String, String>>): String? {
        for ((label, value) in pairs) {
            val key = label.lowercase()
            val isWan = key.contains("wan") || key.contains("广域网") ||
                key.contains("外网") || key.contains("internet")
            if (!isWan || key.contains("mac") || key.contains("状态")) continue
            IP.find(value)?.value?.takeIf { Subnet.parseIp(it) != null }?.let { return it }
        }
        return null
    }

    fun extractWanGateway(pairs: List<Pair<String, String>>): String? {
        for ((label, value) in pairs) {
            val key = label.lowercase()
            if (!key.contains("网关") && !key.contains("gateway")) continue
            IP.find(value)?.value?.takeIf { Subnet.parseIp(it) != null }?.let { return it }
        }
        return null
    }

    fun extractLanCidrs(pairs: List<Pair<String, String>>): List<String> {
        val result = ArrayList<String>()
        for ((label, value) in pairs) {
            val key = label.lowercase()
            val isLan = key.contains("lan") || key.contains("局域网")
            if (!isLan) continue
            val ips = IP.findAll(value).map { it.value }.toList()
            if (ips.isEmpty()) continue
            val cidr = RouterParsing.cidrFrom(ips[0], ips.getOrNull(1))
                ?: RouterParsing.cidrFrom(ips[0], "24")
            cidr?.let { if (it !in result) result += it }
        }
        return result
    }

    fun tableRows(html: String): List<List<String>> =
        ROW.findAll(html).map { row ->
            CELL.findAll(row.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
        }.filter { it.isNotEmpty() }.toList()

    fun clean(fragment: String): String = decodeEntities(fragment.replace(TAG, " "))
        .replace(Regex("\\s+"), " ")
        .trim()

    fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(Regex("&#32;")) { " " }
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.let { code ->
                runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
            } ?: match.value
        }

    private fun isHostAddress(ip: String): Boolean {
        val first = ip.substringBefore('.').toIntOrNull() ?: return false
        val last = ip.substringAfterLast('.').toIntOrNull() ?: return false
        return first in 1..223 && last !in setOf(0, 255)
    }
}
