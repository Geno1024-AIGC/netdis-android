package com.geno1024.ai.netdis.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class HttpResult(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && code in 200..299
}

/**
 * Cookie jar for one router conversation. ZTE and other form-login firmware
 * authenticate with a session cookie instead of Basic auth, so the client has
 * to carry `Set-Cookie` from the login response into every later page.
 */
class RouterSession {

    private val cookies = LinkedHashMap<String, String>()

    @Synchronized
    fun cookieHeader(): String? {
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    @Synchronized
    fun capture(headers: Map<String, String>) {
        val raw = headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .joinToString(", ") { it.value }
        if (raw.isBlank()) return
        // Set-Cookie attributes such as Expires contain commas, so parse every
        // `name=value` pair and ignore the known attribute names.
        for (match in COOKIE_PAIR.findAll(raw)) {
            val name = match.groupValues[1]
            val value = match.groupValues[2]
            if (name.lowercase() in COOKIE_ATTRIBUTES) continue
            if (name.isBlank()) continue
            cookies[name] = value
        }
    }

    private companion object {
        val COOKIE_PAIR = Regex("(?:^|,\\s*)([^=;,\\s]+)=([^;,]*)")
        val COOKIE_ATTRIBUTES = setOf(
            "expires", "path", "domain", "max-age", "secure", "httponly", "samesite", "version",
        )
    }
}

/**
 * The HTTP client used only for LAN routers. Home routers commonly ship with
 * self-signed certificates, so HTTPS verification is relaxed here and nowhere
 * else in the app; the updater keeps the platform's normal trust chain.
 *
 * When traffic capture is enabled, every exchange is copied into the bounded
 * [TrafficLog] with credentials masked. That log is how a firmware variant can
 * be diagnosed without root or a packet capture app.
 */
object RouterHttp {

    private val insecureFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }.socketFactory
    }

    private val acceptAllHosts = HostnameVerifier { _, _ -> true }

    suspend fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/json",
        username: String? = null,
        password: String? = null,
        timeoutMs: Int = 4000,
        session: RouterSession? = null,
    ): HttpResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        var status = -1
        var responseBody: String? = null
        var responseHeaders: Map<String, String> = emptyMap()
        var error: String? = null
        val requestHeaders = LinkedHashMap<String, String>()
        requestHeaders["User-Agent"] = USER_AGENT
        requestHeaders["Accept"] = "application/json, text/html, */*"
        if (username != null && password != null) {
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
            requestHeaders["Authorization"] = "Basic $token"
        }
        if (body != null) requestHeaders["Content-Type"] = contentType
        session?.cookieHeader()?.let { requestHeaders["Cookie"] = it }

        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                for ((key, value) in requestHeaders) setRequestProperty(key, value)
                if (body != null) {
                    doOutput = true
                }
                if (this is HttpsURLConnection) {
                    sslSocketFactory = insecureFactory
                    hostnameVerifier = acceptAllHosts
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(4096)
                while (builder.length < MAX_RESPONSE) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }.orEmpty()
            responseHeaders = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") }
            session?.capture(responseHeaders)
            HttpResult(status, responseBody.orEmpty(), responseHeaders)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            HttpResult(-1, "", responseHeaders, error)
        } finally {
            if (TrafficLog.enabled) {
                TrafficLog.record(
                    TrafficEntry(
                        at = started,
                        method = method,
                        url = TrafficSanitizer.url(url),
                        requestHeaders = TrafficSanitizer.headers(requestHeaders),
                        requestBody = TrafficSanitizer.body(body),
                        status = status,
                        responseHeaders = TrafficSanitizer.headers(responseHeaders),
                        responseBody = TrafficSanitizer.body(responseBody),
                        durationMs = System.currentTimeMillis() - started,
                        error = error,
                    ),
                )
            }
            connection?.disconnect()
        }
    }

    private const val USER_AGENT = "NetDis/0.1 Android"
    private const val MAX_RESPONSE = 512 * 1024
}
