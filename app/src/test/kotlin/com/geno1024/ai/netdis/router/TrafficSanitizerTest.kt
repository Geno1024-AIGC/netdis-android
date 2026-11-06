package com.geno1024.ai.netdis.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSanitizerTest {

    @Test
    fun `masks credential and session headers`() {
        val masked = TrafficSanitizer.headers(
            mapOf(
                "Cookie" to "SESSION=deadbeef",
                "Authorization" to "Basic YWRtaW46aHVudGVyMg==",
                "Accept" to "text/html",
            ),
        )
        assertTrue(masked.getValue("Cookie").startsWith("***("))
        assertTrue(masked.getValue("Authorization").startsWith("***("))
        assertEquals("text/html", masked.getValue("Accept"))
    }

    @Test
    fun `masks form fields that carry passwords and tokens`() {
        val body = TrafficSanitizer.body(
            "username=admin&Password=abc123&_sessionTOKEN=zzz&remember=1",
        )
        assertEquals("username=admin&Password=***&_sessionTOKEN=***&remember=1", body)
    }

    @Test
    fun `masks query parameters in a captured URL`() {
        val url = TrafficSanitizer.url(
            "http://192.168.1.1/login.gch?password=x&user=a&token=yyy",
        )
        assertEquals("http://192.168.1.1/login.gch?password=***&user=a&token=***", url)
    }

    @Test
    fun `truncates oversized bodies`() {
        val body = "a".repeat(TrafficSanitizer.MAX_BODY + 100)
        assertEquals(TrafficSanitizer.MAX_BODY, TrafficSanitizer.body(body)?.length)
    }

    @Test
    fun `exports a readable capture with secrets already gone`() {
        val entry = TrafficEntry(
            at = 0,
            method = "POST",
            url = "http://192.168.1.1/login.gch?token=***",
            requestHeaders = mapOf("Cookie" to "***(7 chars)", "Accept" to "*/*"),
            requestBody = "Username=admin&Password=***",
            status = 200,
            responseHeaders = mapOf("Set-Cookie" to "***(20 chars)"),
            responseBody = "<html>ok</html>",
            durationMs = 12,
        )
        val text = TrafficLog.exportText(listOf(entry))
        assertTrue(text.contains("POST http://192.168.1.1/login.gch"))
        assertTrue(text.contains("Cookie: ***(7 chars)"))
        assertTrue(text.contains("Password=***"))
        assertTrue(text.contains("HTTP 200 (12ms)"))
        assertTrue(text.contains("<html>ok</html>"))
        assertFalse(text.contains("abc123"))
    }

    @Test
    fun `keeps only the newest entries in the ring buffer`() {
        TrafficLog.clear()
        val previous = TrafficLog.enabled
        try {
            TrafficLog.enabled = false
            TrafficLog.record(entry(0))
            assertEquals(0, TrafficLog.count())

            TrafficLog.enabled = true
            for (index in 0 until 205) TrafficLog.record(entry(index))
            val snapshot = TrafficLog.snapshot()
            assertEquals(200, snapshot.size)
            assertEquals("http://router/login?i=5", snapshot.first().url)
            assertEquals("http://router/login?i=204", snapshot.last().url)
        } finally {
            TrafficLog.enabled = previous
            TrafficLog.clear()
        }
    }

    private fun entry(index: Int) = TrafficEntry(
        at = index.toLong(),
        method = "GET",
        url = "http://router/login?i=$index",
        requestHeaders = emptyMap(),
        requestBody = null,
        status = 200,
        responseHeaders = emptyMap(),
        responseBody = null,
        durationMs = 1,
    )
}
