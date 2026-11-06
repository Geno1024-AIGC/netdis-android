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
import java.util.Base64

/**
 * ZTE home gateways expose a form-login web UI, not a documented API. Their
 * pages vary by model and firmware, so this adapter logs in with the common
 * `/asp/GetRandCount.asp` + base64 `/login.gch` flow, then reads whatever
 * HTML tables contain IPv4/MAC pairs. That makes it useful across the ZXHN /
 * F-series family even when a page name differs, and the traffic capture in
 * settings shows exactly which request a newer firmware rejects.
 *
 * Adding another brand follows the same shape: implement [RouterAdapter] and
 * register it in [RouterAdapters.all]. The scanner and editor pick it up.
 */
object ZteAdapter : RouterAdapter {

    override val type: RouterType = RouterType.ZTE

    private val clientPaths = listOf(
        "/getpage.gch?pid=1002&nextpage=IPv4IPv6_landevice_t.gch",
        "/getpage.gch?pid=1002&nextpage=net_clients_t.gch",
        "/getpage.gch?pid=1002&nextpage=dhcp_clients_t.gch",
        "/getpage.gch?pid=1002&nextpage=wlan_client_t.gch",
        "/cgi-bin/luci/admin/status/clients",
    )

    private val statusPaths = listOf(
        "/getpage.gch?pid=1002&nextpage=status_t.gch",
        "/getpage.gch?pid=1002&nextpage=net_status_t.gch",
        "/getpage.gch?pid=1002&nextpage=IPv4IPv6_status_t.gch",
    )

    override suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome =
        withContext(Dispatchers.IO) {
            val base = config.baseUrl
            val session = RouterSession()

            // The landing page normally sets the first session cookie.
            RouterHttp.request(base + "/", session = session, timeoutMs = timeoutMs)
            val token = fetchToken(base, session, timeoutMs)
            val attempts = login(config, base, session, token, timeoutMs)
            val connectivityError = attempts.firstOrNull { it.error != null }?.error

            val pages = ArrayList<String>()
            var sawLoginPage = false
            for (path in clientPaths) {
                val result = RouterHttp.request(base + path, session = session, timeoutMs = timeoutMs)
                if (result.error != null) {
                    if (pages.isEmpty()) {
                        return@withContext failure(
                            message = "无法连接中兴路由器：${result.error}",
                            fingerprint = "中兴 ZTE",
                        )
                    }
                    break
                }
                if (!result.ok) continue
                pages += result.body
                if (ZteParsing.looksLikeLoginPage(result.body)) {
                    sawLoginPage = true
                    break
                }
                if (ZteParsing.parseClients(result.body, config.id).isNotEmpty()) break
            }
            for (path in statusPaths) {
                if (pages.any { ZteParsing.looksLoggedIn(it) }) break
                val result = RouterHttp.request(base + path, session = session, timeoutMs = timeoutMs)
                if (result.error != null || !result.ok) continue
                pages += result.body
                if (ZteParsing.looksLikeLoginPage(result.body)) {
                    sawLoginPage = true
                    break
                }
            }

            val html = pages.joinToString("\n")
            val clients = RouterParsing.mergeHosts(
                pages.flatMap { ZteParsing.parseClients(it, config.id) }
                    .filterNot { it.ip == config.host },
            )
            val loggedIn = clients.isNotEmpty() ||
                pages.any { ZteParsing.looksLoggedIn(it) } ||
                attempts.any {
                    it.ok && !ZteParsing.looksLikeLoginPage(it.body) &&
                        !ZteParsing.loginFailed(it.body)
                }

            if (!loggedIn) {
                val rejected = attempts.any { ZteParsing.loginFailed(it.body) }
                val message = when {
                    attempts.all { it.error != null } ->
                        "无法连接中兴路由器：${connectivityError ?: "网络不可达"}"

                    rejected || sawLoginPage ->
                        "登录失败（中兴用户名或密码错误，或页面仍要求登录）"

                    else -> "已连接中兴路由器，但未识别登录响应"
                }
                return@withContext failure(message = message, fingerprint = "中兴 ZTE")
            }

            val pairs = ZteParsing.tablePairs(html)
            val model = ZteParsing.extractModel(pairs, html)
            val firmware = ZteParsing.extractFirmware(pairs, html)
            val uptime = ZteParsing.extractUptime(pairs)
            val wanIp = ZteParsing.extractWanIp(pairs)
            val wanGateway = ZteParsing.extractWanGateway(pairs)
            val lanCidrs = ZteParsing.extractLanCidrs(pairs)

            RouterScanOutcome(
                success = true,
                type = type,
                message = if (clients.isEmpty()) {
                    "已登录中兴路由器（未解析到客户端表格，可开启抓包）"
                } else {
                    "已登录中兴路由器"
                },
                model = model,
                firmware = firmware,
                uptime = uptime,
                wanIp = wanIp,
                wanGateway = wanGateway,
                lanCidrs = lanCidrs,
                clients = clients,
                wirelessCount = clients.count { it.link == LinkKind.WIFI },
                fingerprint = listOfNotNull(model, firmware).joinToString(" · ")
                    .ifBlank { "中兴 ZTE" },
            )
        }

    private suspend fun fetchToken(base: String, session: RouterSession, timeoutMs: Int): String {
        val paths = listOf(
            "/asp/GetRandCount.asp",
            "/getpage.gch?pid=1002&nextpage=login.gch",
        )
        for (path in paths) {
            val result = RouterHttp.request(base + path, session = session, timeoutMs = timeoutMs)
            if (result.error != null) continue
            ZteParsing.extractRand(result.body)?.let { return it }
        }
        return ""
    }

    private suspend fun login(
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
            if (ZteParsing.loginFailed(result.body)) continue
            if (!ZteParsing.looksLikeLoginPage(result.body)) break
        }

        // Newer ZTE firmware sometimes exposes the LuCI-style form instead.
        if (results.none { it.ok && !ZteParsing.looksLikeLoginPage(it.body) }) {
            results += RouterHttp.request(
                url = base + "/cgi-bin/luci",
                method = "POST",
                body = "username=${encode(username)}&password=${encode(config.password)}",
                contentType = "application/x-www-form-urlencoded",
                session = session,
                timeoutMs = timeoutMs,
            )
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

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * Pure HTML helpers, kept separate so firmware-specific column layouts can be
 * tested without a router. The parser deliberately looks for an IPv4 cell and
 * a MAC cell anywhere in a table row instead of assuming fixed column indexes.
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
    private val PASSWORD_INPUT = Regex(
        "type\\s*=\\s*[\"']?password|name\\s*=\\s*[\"']?password",
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

    fun loginFailed(html: String): Boolean = FAILURE_WORDS.containsMatchIn(html)

    fun looksLoggedIn(html: String): Boolean = LOGOUT_WORDS.containsMatchIn(html)

    fun looksLikeLoginPage(html: String): Boolean =
        !looksLoggedIn(html) && PASSWORD_INPUT.containsMatchIn(html) && LOGIN_WORDS.containsMatchIn(html)

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

    private fun isHostAddress(ip: String): Boolean {
        val first = ip.substringBefore('.').toIntOrNull() ?: return false
        val last = ip.substringAfterLast('.').toIntOrNull() ?: return false
        return first in 1..223 && last !in setOf(0, 255)
    }
}
