package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** One form or JSON request a web UI needs. Placeholders are expanded per router. */
data class WebUiRequest(
    val path: String,
    val method: String = "GET",
    val body: String = "",
    val contentType: String = "application/x-www-form-urlencoded",
)

/**
 * Describes one vendor's web management UI. The engine is shared, so a new
 * brand usually only needs the landing page, login requests, and the pages that
 * contain the client table or JSON.
 */
data class WebUiProfile(
    val type: RouterType,
    val brand: String,
    val landingPaths: List<String> = listOf("/"),
    val tokenPaths: List<String> = emptyList(),
    val loginSteps: List<WebUiRequest> = emptyList(),
    val clientRequests: List<WebUiRequest> = emptyList(),
    val statusRequests: List<WebUiRequest> = emptyList(),
    val basicAuth: Boolean = false,
)

/**
 * The common login-then-read engine behind every web-managed brand. It keeps a
 * cookie jar, follows tokens found in URLs or JSON, and parses both HTML tables
 * and embedded JSON through [WebUiParsing].
 *
 * AUTO probing is safe: an adapter only reports success after a login response
 * leaves the login page or a client/status page proves the session works.
 */
object WebUiScanner {

    suspend fun scan(profile: WebUiProfile, config: RouterConfig, timeoutMs: Int): RouterScanOutcome =
        withContext(Dispatchers.IO) {
            val session = RouterSession()
            var token = ""
            var networkError: String? = null

            var reachable = false
            for (path in profile.landingPaths) {
                val result = request(profile, config, token, WebUiRequest(path), session, timeoutMs)
                if (result.error != null) {
                    networkError = result.error
                    continue
                }
                reachable = true
                networkError = null
                WebUiParsing.extractToken(result.body)?.let { token = it }
            }
            if (!reachable && profile.landingPaths.isNotEmpty()) {
                return@withContext failure(
                    profile,
                    "无法连接${profile.brand}路由器：${networkError ?: "网络不可达"}",
                )
            }

            for (path in profile.tokenPaths) {
                val result = request(profile, config, token, WebUiRequest(path), session, timeoutMs)
                if (result.error != null) continue
                WebUiParsing.extractToken(result.body)?.let { token = it }
            }

            val attempts = ArrayList<HttpResult>()
            var sawLoginPage = false
            for (step in profile.loginSteps) {
                val result = request(profile, config, token, step, session, timeoutMs)
                attempts += result
                if (result.error != null) {
                    networkError = result.error
                    break
                }
                WebUiParsing.extractToken(result.body)?.let { token = it }
                if (WebUiParsing.looksLikeLoginPage(result.body)) {
                    sawLoginPage = true
                    continue
                }
                if (WebUiParsing.loginFailed(result.body)) continue
                if (result.code in 200..399) break
            }

            val clientPages = ArrayList<HttpResult>()
            for (step in profile.clientRequests) {
                val result = request(profile, config, token, step, session, timeoutMs)
                if (result.error != null) {
                    if (clientPages.isEmpty()) networkError = result.error
                    break
                }
                if (result.code !in 200..299) continue
                WebUiParsing.extractToken(result.body)?.let { token = it }
                if (WebUiParsing.looksLikeLoginPage(result.body)) {
                    sawLoginPage = true
                    break
                }
                clientPages += result
            }

            val statusPages = ArrayList<HttpResult>()
            for (step in profile.statusRequests) {
                val result = request(profile, config, token, step, session, timeoutMs)
                if (result.error != null || result.code !in 200..299) continue
                WebUiParsing.extractToken(result.body)?.let { token = it }
                if (WebUiParsing.looksLikeLoginPage(result.body)) {
                    sawLoginPage = true
                    break
                }
                statusPages += result
            }

            val allPages = clientPages + statusPages
            val clients = RouterParsing.mergeHosts(
                allPages.flatMap { WebUiParsing.parseClients(it.body, config.id) }
                    .filterNot { it.ip == config.host },
            )
            val loggedIn = clients.isNotEmpty() ||
                allPages.any { WebUiParsing.looksLoggedIn(it.body) } ||
                attempts.any {
                    it.ok && !WebUiParsing.looksLikeLoginPage(it.body) &&
                        !WebUiParsing.loginFailed(it.body)
                }

            if (!loggedIn) {
                val rejected = attempts.any {
                    WebUiParsing.loginFailed(it.body) || it.code == 401 || it.code == 403
                }
                val message = when {
                    networkError != null ->
                        "无法连接${profile.brand}路由器：$networkError"

                    rejected || sawLoginPage ->
                        "登录失败（${profile.brand} 用户名或密码错误，或页面仍要求登录）"

                    else -> "已连接${profile.brand}路由器，但未识别登录响应"
                }
                return@withContext failure(profile, message)
            }

            val status = WebUiParsing.parseStatus(allPages.map { it.body })
            val fingerprint = listOfNotNull(status.model, status.firmware)
                .joinToString(" · ")
                .ifBlank { profile.brand }

            RouterScanOutcome(
                success = true,
                type = profile.type,
                message = if (clients.isEmpty()) {
                    "已登录${profile.brand}路由器（未解析到客户端列表，可开启抓包）"
                } else {
                    "已登录${profile.brand}路由器"
                },
                model = status.model,
                firmware = status.firmware,
                uptime = status.uptime,
                wanIp = status.wanIp,
                wanGateway = status.wanGateway,
                externalIp = status.externalIp,
                lanCidrs = status.lanCidrs,
                dnsServers = status.dnsServers,
                clients = clients,
                wirelessCount = clients.count { it.link == LinkKind.WIFI },
                fingerprint = fingerprint,
            )
        }

    private suspend fun request(
        profile: WebUiProfile,
        config: RouterConfig,
        token: String,
        request: WebUiRequest,
        session: RouterSession,
        timeoutMs: Int,
    ): HttpResult {
        val body = expandBody(request.body, config, token, request.contentType)
        return RouterHttp.request(
            url = config.baseUrl + expandPath(request.path, config, token),
            method = request.method,
            body = body.takeIf { request.method != "GET" && it.isNotEmpty() },
            contentType = request.contentType,
            username = config.username.takeIf { profile.basicAuth },
            password = config.password.takeIf { profile.basicAuth },
            timeoutMs = timeoutMs,
            session = session,
        )
    }

    private fun failure(profile: WebUiProfile, message: String): RouterScanOutcome =
        RouterScanOutcome(
            success = false,
            type = profile.type,
            message = message,
            fingerprint = profile.brand,
        )

    private fun expandPath(template: String, config: RouterConfig, token: String): String =
        template.replace("{token}", encode(token))
            .replace("{user}", encode(config.username))
            .replace("{pass}", encode(config.password))

    private fun expandBody(
        template: String,
        config: RouterConfig,
        token: String,
        contentType: String,
    ): String {
        if (template.isEmpty()) return template
        val json = contentType.contains("json", ignoreCase = true)
        val user = config.username
        val pass = config.password
        val values = mapOf(
            "user" to user,
            "pass" to pass,
            "userB64" to base64(user),
            "passB64" to base64(pass),
            "basic" to base64("$user:$pass"),
            "passMd5" to md5(pass),
            "token" to token,
        )
        return Regex("\\{(userB64|passB64|passMd5|basic|user|pass|token)\\}").replace(template) { match ->
            val value = values[match.groupValues[1]].orEmpty()
            if (json) jsonEscape(value) else encode(value)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun jsonEscape(value: String): String = buildString {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}
