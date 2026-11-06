package com.geno1024.ai.netdis.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets

data class SsdpDevice(
    val usn: String,
    val st: String,
    val location: String? = null,
    val server: String? = null,
    val address: String? = null,
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val deviceType: String? = null,
) {
    val displayName: String
        get() = friendlyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(manufacturer, modelName).joinToString(" ").takeIf { it.isNotBlank() }
            ?: friendlySt

    val friendlySt: String
        get() = when {
            st.contains("InternetGatewayDevice", ignoreCase = true) -> "路由器 (UPnP)"
            st.contains("WANDevice", ignoreCase = true) -> "路由器 (UPnP)"
            st.contains("MediaRenderer", ignoreCase = true) -> "媒体播放器"
            st.contains("MediaServer", ignoreCase = true) -> "媒体服务器"
            st.contains("printer", ignoreCase = true) -> "打印机"
            st.contains("Basic", ignoreCase = true) -> "UPnP 设备"
            else -> st.substringAfterLast(':').ifBlank { "UPnP 设备" }
        }

    val isGateway: Boolean get() = st.contains("InternetGatewayDevice", ignoreCase = true)
}

/**
 * SSDP/UPnP discovery, including the description fetch that turns an opaque
 * `urn:` into a friendly name and model.
 */
object SsdpDiscovery {

    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900

    suspend fun discover(context: Context, timeoutMs: Long = 3200): List<SsdpDevice> =
        withContext(Dispatchers.IO) {
            val lock = runCatching {
                context.getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("netdis-ssdp")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
            try {
                val raw = collect(timeoutMs)
                enrich(raw)
            } finally {
                runCatching { lock?.release() }
            }
        }

    private fun collect(timeoutMs: Long): List<SsdpDevice> {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }.getOrNull() ?: return emptyList()

        socket.use {
            val group = InetAddress.getByName(GROUP)
            val deadline = System.currentTimeMillis() + timeoutMs
            val queries = listOf("ssdp:all", "urn:schemas-upnp-org:device:InternetGatewayDevice:1")
            val seen = LinkedHashMap<String, SsdpDevice>()
            val buffer = ByteArray(8192)
            var lastSend = 0L
            while (System.currentTimeMillis() < deadline) {
                if (lastSend == 0L || System.currentTimeMillis() - lastSend > 850) {
                    for (target in queries) {
                        val body = buildString {
                            append("M-SEARCH * HTTP/1.1\r\n")
                            append("HOST: $GROUP:$PORT\r\n")
                            append("MAN: \"ssdp:discover\"\r\n")
                            append("MX: 2\r\n")
                            append("ST: $target\r\n")
                            append("USER-AGENT: NetDis/0.1 Android\r\n")
                            append("\r\n")
                        }
                        runCatching {
                            val bytes = body.toByteArray(StandardCharsets.UTF_8)
                            socket.send(DatagramPacket(bytes, bytes.size, group, PORT))
                        }
                    }
                    lastSend = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.soTimeout = 350
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val device = parseResponse(
                    String(packet.data, 0, packet.length, StandardCharsets.UTF_8),
                    packet.address.hostAddress,
                ) ?: continue
                val key = device.usn.ifBlank { device.location ?: device.address + device.st }
                seen.putIfAbsent(key, device)
            }
            return seen.values.toList()
        }
    }

    fun parseResponse(text: String, remoteAddress: String? = null): SsdpDevice? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        if (!lines.first().startsWith("HTTP/1.1 200", ignoreCase = true) &&
            !lines.first().startsWith("NOTIFY", ignoreCase = true)
        ) {
            return null
        }
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val st = headers["st"] ?: headers["nt"] ?: return null
        val usn = headers["usn"] ?: headers["nt"] ?: ""
        return SsdpDevice(
            usn = usn,
            st = st,
            location = headers["location"],
            server = headers["server"],
            address = remoteAddress,
        )
    }

    /** Fetches each description in parallel; a dead HTTP endpoint just loses its names. */
    private suspend fun enrich(devices: List<SsdpDevice>): List<SsdpDevice> = coroutineScope {
        devices.map { device ->
            async(Dispatchers.IO) {
                val location = device.location ?: return@async device
                val xml = fetchDescription(location) ?: return@async device
                device.copy(
                    friendlyName = tag(xml, "friendlyName") ?: device.friendlyName,
                    manufacturer = tag(xml, "manufacturer"),
                    modelName = tag(xml, "modelName") ?: tag(xml, "modelDescription"),
                    deviceType = tag(xml, "deviceType"),
                )
            }
        }.awaitAll()
    }

    private fun fetchDescription(location: String): String? {
        val url = runCatching { URL(location) }.getOrNull() ?: return null
        if (url.protocol !in setOf("http", "https")) return null
        return runCatching {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1400
            connection.readTimeout = 1800
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NetDis/0.1 Android")
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    val builder = StringBuilder()
                    val chunk = CharArray(4096)
                    while (builder.length < 128 * 1024) {
                        val read = reader.read(chunk)
                        if (read < 0) break
                        builder.append(chunk, 0, read)
                    }
                    builder.toString()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun tag(xml: String, name: String): String? {
        val regex = Regex(
            "<(?:[\\w-]+:)?${Regex.escape(name)}[^>]*>(.*?)</(?:[\\w-]+:)?${Regex.escape(name)}>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val value = regex.find(xml)?.groupValues?.getOrNull(1) ?: return null
        return unescape(value.trim()).takeIf { it.isNotBlank() }
    }

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
