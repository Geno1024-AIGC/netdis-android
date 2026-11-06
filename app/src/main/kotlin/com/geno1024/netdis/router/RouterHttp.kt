package com.geno1024.netdis.router

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
 * The HTTP client used only for LAN routers. Home routers commonly ship with
 * self-signed certificates, so HTTPS verification is relaxed here and nowhere
 * else in the app; the updater keeps the platform's normal trust chain.
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
    ): HttpResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NetDis/0.1 Android")
                setRequestProperty("Accept", "application/json, text/html, */*")
                if (username != null && password != null) {
                    val token = Base64.getEncoder()
                        .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                    setRequestProperty("Authorization", "Basic $token")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                }
                if (this is HttpsURLConnection) {
                    sslSocketFactory = insecureFactory
                    hostnameVerifier = acceptAllHosts
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(4096)
                while (builder.length < 512 * 1024) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }.orEmpty()
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") }
            HttpResult(code, text, headers)
        } catch (e: Exception) {
            HttpResult(-1, "", emptyMap(), e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
