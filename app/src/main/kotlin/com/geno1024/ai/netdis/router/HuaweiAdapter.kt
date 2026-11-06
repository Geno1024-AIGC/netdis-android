package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.net.ArpTable
import com.geno1024.ai.netdis.net.Subnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Huawei / China Telecom home gateways (HS8145C and the LuCI-derived family).
 *
 * The login is a two-step form:
 *
 * 1. `GET /` returns an auto-submit form with a hidden `token`.
 * 2. `POST /cgi-bin/luci` with that token establishes the login form.
 * 3. `POST /cgi-bin/luci` with `username=useradmin&psd=<password>`.
 *
 * After login every page is JSON, keyed by generated object names rather than
 * arrays: `devInfo?type=0` (wired) and `?type=1` (Wi-Fi) are the client tables,
 * `allInfo` carries friendly names, and `gwinfo`/`gwstatus` describe the box.
 */
object HuaweiAdapter : RouterAdapter {

    override val type: RouterType = RouterType.HUAWEI

    private const val FORM = "application/x-www-form-urlencoded"
    private const val JSON = "application/json"

    override suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome =
        withContext(Dispatchers.IO) {
            val base = config.baseUrl
            val session = RouterSession()

            var landing = RouterHttp.request(
                url = base + "/cgi-bin/luci/",
                session = session,
                timeoutMs = timeoutMs,
                headers = mapOf("Accept" to "text/html, application/xhtml+xml, */*"),
            )
            if (landing.error != null) {
                landing = RouterHttp.request(
                    url = base + "/",
                    session = session,
                    timeoutMs = timeoutMs,
                    headers = mapOf("Accept" to "text/html, application/xhtml+xml, */*"),
                )
            }
            if (landing.error != null) {
                return@withContext failure("无法连接华为路由器：${landing.error}")
            }

            var loggedIn = HuaweiParsing.looksLoggedIn(landing.body)
            var loginError: String? = null

            if (!loggedIn) {
                // The landing page may already contain the token (some firmware
                // puts the auto-submit form directly on `/cgi-bin/luci/`).
                var token = HuaweiParsing.extractToken(landing.body)
                val home = RouterHttp.request(
                    url = base + "/",
                    session = session,
                    timeoutMs = timeoutMs,
                )
                if (home.error != null) {
                    return@withContext failure("无法连接华为路由器：${home.error}")
                }
                token = HuaweiParsing.extractToken(home.body) ?: token
                if (token != null) {
                    val staged = RouterHttp.request(
                        url = base + "/cgi-bin/luci",
                        method = "POST",
                        body = "token=" + encode(token),
                        contentType = FORM,
                        session = session,
                        timeoutMs = timeoutMs,
                        headers = formHeaders(base),
                    )
                    if (staged.error != null) {
                        return@withContext failure("无法连接华为路由器：${staged.error}")
                    }
                    // The credential form may carry a fresh token.
                    token = HuaweiParsing.extractToken(staged.body) ?: token
                }

                val usernames = listOf(config.username.trim(), "useradmin", "telecomadmin")
                    .filter { it.isNotEmpty() }
                    .distinct()
                for (username in usernames) {
                    val body = "username=" + encode(username) + "&psd=" + encode(config.password)
                    val result = RouterHttp.request(
                        url = base + "/cgi-bin/luci",
                        method = "POST",
                        body = body,
                        contentType = FORM,
                        session = session,
                        timeoutMs = timeoutMs,
                        headers = formHeaders(base),
                    )
                    if (result.error != null) {
                        loginError = result.error
                        break
                    }
                    if (HuaweiParsing.looksLoggedIn(result.body)) {
                        loggedIn = true
                        break
                    }
                    if (!HuaweiParsing.looksLikeLoginPage(result.body) && result.ok) {
                        // A 200 page without the login form is the main frame.
                        loggedIn = true
                        break
                    }
                }
            }

            if (!loggedIn) {
                val message = when {
                    loginError != null -> "无法连接华为路由器：$loginError"
                    else -> "登录失败（华为用户名或密码错误，或页面仍要求登录）"
                }
                return@withContext failure(message)
            }

            val gwInfo = get(base, "/cgi-bin/luci/admin/settings/gwinfo?get=all", session, timeoutMs)
            val gwStatus = get(base, "/cgi-bin/luci/admin/settings/gwstatus", session, timeoutMs)
            val allInfo = get(base, "/cgi-bin/luci/admin/allInfo", session, timeoutMs)
            val wired = get(base, "/cgi-bin/luci/admin/device/devInfo?type=0", session, timeoutMs)
            val wireless = get(base, "/cgi-bin/luci/admin/device/devInfo?type=1", session, timeoutMs)

            val pages = listOf(gwInfo, gwStatus, allInfo, wired, wireless)
            val usable = pages.filter { it.error == null && !HuaweiParsing.looksLikeLoginPage(it.body) }
            if (usable.isEmpty()) {
                val reason = pages.firstNotNullOfOrNull { it.error }
                return@withContext failure(
                    if (reason != null) "无法连接华为路由器：$reason" else "华为路由器登录状态已失效",
                )
            }

            val names = HuaweiParsing.parseNames(allInfo.body)
            val devices = HuaweiParsing.parseDevices(wired.body, config.id, wifi = false) +
                HuaweiParsing.parseDevices(wireless.body, config.id, wifi = true)
            val clients = RouterParsing.mergeHosts(
                HuaweiParsing.applyNames(devices, names).filterNot { it.ip == config.host },
            )
            val info = HuaweiParsing.parseInfo(gwInfo.body, gwStatus.body)
            // Never fall back to a client MAC: the only ROUTER-kind client is a
            // downstream router, and attaching its MAC to this gateway would
            // make the two configurations collide on the next scan.
            val mac = info.mac ?: ArpTable.normalizeMac(config.mac)

            RouterScanOutcome(
                success = true,
                type = type,
                message = if (clients.isEmpty()) {
                    "已登录华为路由器（未解析到客户端列表）"
                } else {
                    "已登录华为路由器"
                },
                model = info.model,
                firmware = info.firmware,
                uptime = info.uptime,
                wanIp = info.wanIp,
                lanCidrs = info.lanCidrs,
                clients = clients,
                wirelessCount = clients.count { it.link == LinkKind.WIFI },
                fingerprint = listOfNotNull(info.model, info.firmware)
                    .joinToString(" · ")
                    .ifBlank { "华为 Huawei" },
                mac = mac,
            )
        }

    private suspend fun get(
        base: String,
        path: String,
        session: RouterSession,
        timeoutMs: Int,
    ): HttpResult = RouterHttp.request(
        url = base + path + (if ('?' in path) "&" else "?") + "_=" + System.currentTimeMillis(),
        session = session,
        timeoutMs = timeoutMs,
        headers = mapOf(
            "Accept" to JSON,
            "X-Requested-With" to "XMLHttpRequest",
        ),
    )

    private fun formHeaders(base: String) = mapOf(
        "Referer" to base + "/",
        "Origin" to base,
    )

    private fun failure(message: String) = RouterScanOutcome(
        success = false,
        type = type,
        message = message,
        fingerprint = "华为 Huawei",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * Pure Huawei helpers. Kept separate so captured JSON can be replayed in unit
 * tests without a gateway.
 */
object HuaweiParsing {

    data class NameInfo(
        val brand: String? = null,
        val model: String? = null,
        val type: String? = null,
        val onlineSeconds: Long? = null,
    )

    data class Info(
        val model: String? = null,
        val firmware: String? = null,
        val mac: String? = null,
        val lanCidrs: List<String> = emptyList(),
        val wanIp: String? = null,
        val uptime: String? = null,
    )

    private val TOKEN_NAME_FIRST = Regex(
        "name\\s*=\\s*[\"']?token[\"']?[^>]*?value\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN_VALUE_FIRST = Regex(
        "value\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?name\\s*=\\s*[\"']?token[\"']?",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_FORM = Regex(
        "login_password|login_form|name\\s*=\\s*[\"']?psd",
        RegexOption.IGNORE_CASE,
    )
    private val LOGGED_IN = Regex(
        "/cgi-bin/luci/admin/|/admin/logout|main_style|退出登录|注销",
        RegexOption.IGNORE_CASE,
    )
    private val FAILURE_WORDS = Regex(
        "用户名或密码错误|密码错误|登录失败|Login failed|Invalid (?:user|password)|incorrect password",
        RegexOption.IGNORE_CASE,
    )

    fun extractToken(html: String): String? =
        TOKEN_NAME_FIRST.find(html)?.groupValues?.getOrNull(1)
            ?: TOKEN_VALUE_FIRST.find(html)?.groupValues?.getOrNull(1)

    fun looksLikeLoginPage(html: String): Boolean = LOGIN_FORM.containsMatchIn(html)

    fun looksLoggedIn(html: String): Boolean =
        !looksLikeLoginPage(html) && LOGGED_IN.containsMatchIn(html)

    fun loginFailed(html: String): Boolean = FAILURE_WORDS.containsMatchIn(html)

    /** `devInfo` rows. `type` is the device kind; the endpoint itself is the link. */
    fun parseDevices(json: String, routerId: String?, wifi: Boolean): List<HostRecord> {
        val root = jsonObject(json) ?: return emptyList()
        val result = ArrayList<HostRecord>()
        for (key in root.keys()) {
            if (!key.startsWith("dev", ignoreCase = true)) continue
            val entry = root.optJSONObject(key) ?: continue
            val ip = entry.optString("ip").trim()
                .takeIf { Subnet.parseIp(it) != null && isHostAddress(it) }
                ?: continue
            val mac = ArpTable.normalizeMac(entry.optString("mac"))
            val deviceType = entry.optString("type").trim()
            val system = entry.optString("system").trim()
            result += HostRecord(
                ip = ip,
                mac = mac,
                kind = kindOf(deviceType, system),
                link = if (wifi) LinkKind.WIFI else LinkKind.ETHERNET,
                online = true,
                sources = setOf(HostSource.DHCP, HostSource.ROUTER_ARP),
                routerId = routerId,
                extra = buildMap {
                    put("lease", "DHCP")
                    if (deviceType.isNotBlank()) put("deviceType", deviceType)
                    if (system.isNotBlank()) put("system", system)
                },
            )
        }
        return result
    }

    /** `allInfo` maps `pc1`, `pc2`, … to a friendly brand/model per IP. */
    fun parseNames(json: String): Map<String, NameInfo> {
        val root = jsonObject(json) ?: return emptyMap()
        val result = LinkedHashMap<String, NameInfo>()
        for (key in root.keys()) {
            if (!key.startsWith("pc", ignoreCase = true)) continue
            val entry = root.optJSONObject(key) ?: continue
            val ip = entry.optString("ip").trim()
                .takeIf { Subnet.parseIp(it) != null && isHostAddress(it) }
                ?: continue
            result[ip] = NameInfo(
                brand = text(entry, "brand"),
                model = text(entry, "model"),
                type = text(entry, "type"),
                onlineSeconds = entry.optLong("onlineTime").takeIf { it > 0 },
            )
        }
        return result
    }

    fun applyNames(
        devices: List<HostRecord>,
        names: Map<String, NameInfo>,
    ): List<HostRecord> = devices.map { device ->
        val info = names[device.ip] ?: return@map device
        val name = info.model ?: info.brand
        val kind = if (device.kind != DeviceKind.UNKNOWN) {
            device.kind
        } else {
            kindOf(info.type.orEmpty(), info.model.orEmpty())
        }
        device.copy(
            hostname = device.hostname ?: name,
            vendor = device.vendor ?: info.brand,
            kind = kind,
            extra = device.extra + buildMap {
                info.brand?.let { put("brand", it) }
                info.model?.let { put("model", it) }
                info.type?.let { put("type", it) }
                info.onlineSeconds?.let { put("onlineSeconds", it.toString()) }
            },
        )
    }

    fun parseInfo(gwInfo: String, gwStatus: String): Info {
        val gw = jsonObject(gwInfo)
        val status = jsonObject(gwStatus)
        val lanIp = gw?.optString("LANIP")?.trim()
            ?.takeIf { Subnet.parseIp(it) != null && isHostAddress(it) }
        val lanCidrs = lanIp?.let { RouterParsing.cidrFrom(it, "24") }?.let { listOf(it) }
            .orEmpty()
        return Info(
            model = text(gw, "ProductCls") ?: text(gw, "DevType"),
            firmware = text(gw, "SWVer"),
            mac = ArpTable.normalizeMac(gw?.optString("MAC")),
            lanCidrs = lanCidrs,
            wanIp = gw?.optString("WANIP")?.trim()
                ?.takeIf { Subnet.parseIp(it) != null && it != "0.0.0.0" },
            uptime = status?.optLong("upTime")?.takeIf { it > 0 }
                ?.let(RouterParsing::formatUptime)
                ?.takeIf { it.isNotBlank() },
        )
    }

    fun kindOf(type: String, extra: String = ""): DeviceKind {
        val text = "$type $extra".lowercase()
        return when {
            "router" in text || "gateway" in text || "路由" in text -> DeviceKind.ROUTER
            "camera" in text || "ipc" in text || "摄像" in text || "doorbell" in text -> DeviceKind.CAMERA
            "printer" in text || "打印" in text -> DeviceKind.PRINTER
            "phone" in text || "mobile" in text || "手机" in text ||
                "android" in text || "ios" in text -> DeviceKind.PHONE
            "pc" in text || "computer" in text || "desktop" in text ||
                "laptop" in text || "windows" in text || "macos" in text -> DeviceKind.COMPUTER
            "tv" in text || "itv" in text || "stb" in text || "电视" in text -> DeviceKind.TV
            "speaker" in text || "audio" in text || "音响" in text -> DeviceKind.SPEAKER
            "nas" in text || "storage" in text || "存储" in text -> DeviceKind.NAS
            "game" in text || "console" in text || "xbox" in text || "playstation" in text ->
                DeviceKind.CONSOLE
            "iot" in text || "smart" in text || "智能" in text -> DeviceKind.IOT
            else -> DeviceKind.UNKNOWN
        }
    }

    private fun jsonObject(text: String): JSONObject? =
        runCatching { JSONObject(text.trim()) }.getOrNull()

    private fun text(entry: JSONObject?, key: String): String? =
        entry?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun isHostAddress(ip: String): Boolean {
        val first = ip.substringBefore('.').toIntOrNull() ?: return false
        val last = ip.substringAfterLast('.').toIntOrNull() ?: return false
        return first in 1..223 && last !in setOf(0, 255)
    }
}
