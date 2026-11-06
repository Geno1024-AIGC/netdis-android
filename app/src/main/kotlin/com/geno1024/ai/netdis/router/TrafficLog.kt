package com.geno1024.ai.netdis.router

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** One app↔router HTTP exchange. Bodies and headers are already sanitised. */
data class TrafficEntry(
    val at: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val status: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val durationMs: Long,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && status in 200..399
}

/**
 * Masks credentials before anything reaches the on-device log or an export.
 * Router pages are inconsistent: some use form fields, some put a session in a
 * query parameter, and some echo cookies in the body.
 */
object TrafficSanitizer {

    private val secretHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-csrf-token",
        "x-xsrf-token",
        "x-auth-token",
    )

    private val secretField = Regex(
        "(?i)((?:password|passwd|pwd|token|secret|session(?:id|token)?|auth(?:orization)?)" +
            "[\"']?\\s*[=:]\\s*[\"']?)([^&\\s,;\"'}]+)",
    )

    private val secretQuery = Regex(
        "(?i)([?&](?:password|passwd|pwd|token|secret|session(?:id|token)?|auth)=)([^&\\s#]+)",
    )

    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (key, value) ->
        if (key.lowercase(Locale.US) in secretHeaders) masked(value) else value
    }

    fun body(body: String?): String? {
        if (body == null) return null
        val masked = secretField.replace(body) { match -> match.groupValues[1] + "***" }
        return masked.take(MAX_BODY)
    }

    fun url(url: String): String = secretQuery.replace(url) { match -> match.groupValues[1] + "***" }

    fun masked(value: String): String = if (value.isBlank()) value else "***(${value.length} chars)"

    const val MAX_BODY = 16 * 1024
}

/**
 * Bounded, in-memory capture of the app's own router requests. It exists so a
 * new firmware can be adapted from a real login exchange without root or a
 * system-wide packet capture. Nothing is written to disk unless the user
 * explicitly exports it, and credentials/cookies are masked on the way in.
 */
object TrafficLog {

    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<TrafficEntry>()

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var version: Int = 0
        private set

    fun record(entry: TrafficEntry) {
        if (!enabled) return
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            version++
        }
    }

    fun snapshot(): List<TrafficEntry> = synchronized(entries) { entries.toList() }

    fun count(): Int = synchronized(entries) { entries.size }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            version++
        }
    }

    fun exportText(items: List<TrafficEntry> = snapshot()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("# NetDis router HTTP capture")
            appendLine("# exported $stamp, ${items.size} entries")
            appendLine("# credentials, cookies and session tokens are masked")
            for (entry in items) {
                appendLine()
                appendLine("=== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(entry.at))} ===")
                appendLine("${entry.method} ${entry.url}")
                if (entry.requestHeaders.isNotEmpty()) {
                    appendLine("--- request headers ---")
                    for ((key, value) in entry.requestHeaders) appendLine("$key: $value")
                }
                entry.requestBody?.takeIf { it.isNotEmpty() }?.let {
                    appendLine("--- request body ---")
                    appendLine(it)
                }
                appendLine("--- response ---")
                appendLine(if (entry.error != null) "ERROR: ${entry.error}" else "HTTP ${entry.status} (${entry.durationMs}ms)")
                if (entry.responseHeaders.isNotEmpty()) {
                    for ((key, value) in entry.responseHeaders) appendLine("$key: $value")
                }
                entry.responseBody?.takeIf { it.isNotEmpty() }?.let {
                    appendLine("--- response body ---")
                    appendLine(it)
                }
            }
        }
    }
}
