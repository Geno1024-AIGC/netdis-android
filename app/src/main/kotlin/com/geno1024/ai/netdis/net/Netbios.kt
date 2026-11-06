package com.geno1024.ai.netdis.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

data class NetbiosInfo(
    val name: String,
    val workgroup: String? = null,
    val mac: String? = null,
)

/**
 * NetBIOS node status (NBSTAT) is still the quickest way to get a human name
 * from Windows machines and Samba NAS boxes that advertise nothing else.
 */
object Netbios {

    private const val TYPE_NBSTAT = 0x21

    suspend fun query(ip: String, timeoutMs: Int = 650): NetbiosInfo? =
        withContext(Dispatchers.IO) {
            val query = buildQuery()
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val address = InetAddress.getByName(ip)
                    socket.send(DatagramPacket(query, query.size, address, 137))
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseResponse(buffer, packet.length)
                }
            } catch (_: Exception) {
                null
            }
        }

    fun buildQuery(transactionId: Int = 0x8228): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((transactionId shr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        out.write(0x00) // flags: query
        out.write(0x00)
        out.write(0x00) // questions
        out.write(0x01)
        out.write(0x00) // answers
        out.write(0x00)
        out.write(0x00) // authorities
        out.write(0x00)
        out.write(0x00) // additionals
        out.write(0x00)
        out.write(0x20) // encoded name length
        // "*" followed by fifteen zero bytes, each nibble shifted into A..P.
        val name = ByteArray(16)
        name[0] = '*'.code.toByte()
        for (byte in name) {
            val value = byte.toInt() and 0xFF
            out.write(((value shr 4) and 0x0F) + 'A'.code)
            out.write((value and 0x0F) + 'A'.code)
        }
        out.write(0x00)
        out.write(0x00)
        out.write(TYPE_NBSTAT)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    fun parseResponse(bytes: ByteArray, length: Int = bytes.size): NetbiosInfo? {
        val message = DnsPacket.parse(bytes, length, rawTypes = setOf(TYPE_NBSTAT)) ?: return null
        val record = message.answers.firstOrNull { it.type == TYPE_NBSTAT } ?: return null
        val data = (record.data as? DnsPacket.RData.Raw)?.bytes ?: return null
        if (data.isEmpty()) return null
        val count = data[0].toInt() and 0xFF
        var offset = 1
        var workstation: String? = null
        var workgroup: String? = null
        repeat(count) {
            if (offset + 18 > data.size) return@repeat
            val rawName = String(data, offset, 15, StandardCharsets.ISO_8859_1).trim()
            val suffix = data[offset + 15].toInt() and 0xFF
            offset += 16
            val flags = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            val group = flags and 0x8000 != 0
            if (rawName.isEmpty() || rawName == "__MSBROWSE__") return@repeat
            when {
                suffix == 0x00 && !group -> if (workstation == null) workstation = rawName
                suffix == 0x00 && group -> if (workgroup == null) workgroup = rawName
                suffix == 0x1D && !group -> if (workgroup == null) workgroup = rawName
            }
        }
        val mac = if (offset + 6 <= data.size) {
            (0 until 6).joinToString(":") { index ->
                "%02x".format(data[offset + index].toInt() and 0xFF)
            }
        } else {
            null
        }
        val name = workstation ?: workgroup ?: return null
        return NetbiosInfo(
            name = name,
            workgroup = workgroup,
            mac = ArpTable.normalizeMac(mac),
        )
    }
}
