package com.geno1024.ai.netdis.net

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * A small DNS/mDNS wire-format reader. Android has no public mDNS enumeration
 * API, and pulling in a full DNS library for six record types would be heavier
 * than the parser itself.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33

    data class Question(val name: String, val type: Int, val clazz: Int = 1)

    sealed interface RData {
        data class Address(val ip: String) : RData
        data class Target(val name: String) : RData
        data class Service(
            val priority: Int,
            val weight: Int,
            val port: Int,
            val target: String,
        ) : RData

        data class Text(val values: List<String>) : RData
        data class Raw(val bytes: ByteArray) : RData
    }

    data class Record(
        val name: String,
        val type: Int,
        val ttl: Long,
        val data: RData,
    )

    data class Message(
        val id: Int,
        val isResponse: Boolean,
        val questions: List<Question>,
        val answers: List<Record>,
        val authorities: List<Record>,
        val additionals: List<Record>,
    ) {
        val records: List<Record> get() = answers + authorities + additionals

        fun of(type: Int, name: String? = null): List<Record> = records.filter {
            it.type == type && (name == null || it.name.equals(name, ignoreCase = true))
        }
    }

    /**
     * [rawTypes] names record types whose RDATA the caller wants untouched.
     * NBSTAT (also type 33) collides with SRV, so NetBIOS parsing reads it raw.
     */
    fun parse(
        bytes: ByteArray,
        length: Int = bytes.size,
        rawTypes: Set<Int> = emptySet(),
    ): Message? {
        if (length < 12) return null
        val id = readU16(bytes, 0) ?: return null
        val flags = readU16(bytes, 2) ?: return null
        val qd = readU16(bytes, 4) ?: return null
        val an = readU16(bytes, 6) ?: return null
        val ns = readU16(bytes, 8) ?: return null
        val ar = readU16(bytes, 10) ?: return null
        if (qd > 128 || an > 512 || ns > 128 || ar > 512) return null

        var offset = 12
        val questions = ArrayList<Question>(qd)
        repeat(qd) {
            val (name, next) = readName(bytes, offset, length) ?: return null
            offset = next
            if (offset + 4 > length) return null
            val type = readU16(bytes, offset) ?: return null
            val clazz = readU16(bytes, offset + 2) ?: return null
            offset += 4
            questions += Question(name, type, clazz and 0x7FFF)
        }

        fun readRecords(count: Int): List<Record>? {
            val out = ArrayList<Record>(count)
            repeat(count) {
                val (name, afterName) = readName(bytes, offset, length) ?: return null
                offset = afterName
                if (offset + 10 > length) return null
                val type = readU16(bytes, offset) ?: return null
                val clazz = readU16(bytes, offset + 2) ?: return null
                val ttl = readU32(bytes, offset + 4) ?: return null
                val rdLength = readU16(bytes, offset + 8) ?: return null
                offset += 10
                if (offset + rdLength > length) return null
                val data = readRData(bytes, offset, rdLength, length, type, rawTypes) ?: return null
                offset += rdLength
                @Suppress("UNUSED_VARIABLE")
                val unusedClass = clazz
                out += Record(name, type, ttl, data)
            }
            return out
        }

        val answers = readRecords(an) ?: return null
        val authorities = readRecords(ns) ?: return null
        val additionals = readRecords(ar) ?: return null

        return Message(
            id = id,
            isResponse = (flags and 0x8000) != 0,
            questions = questions,
            answers = answers,
            authorities = authorities,
            additionals = additionals,
        )
    }

    private fun readRData(
        bytes: ByteArray,
        offset: Int,
        rdLength: Int,
        limit: Int,
        type: Int,
        rawTypes: Set<Int>,
    ): RData? {
        if (type in rawTypes) {
            return RData.Raw(bytes.copyOfRange(offset, (offset + rdLength).coerceAtMost(limit)))
        }
        return when (type) {
        TYPE_A -> {
            if (rdLength != 4) null
            else RData.Address(
                "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}." +
                    "${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}",
            )
        }

        TYPE_AAAA -> {
            if (rdLength != 16) null else RData.Address(formatIpv6(bytes, offset))
        }

        TYPE_PTR -> {
            val (name, _) = readName(bytes, offset, offset + rdLength) ?: return null
            RData.Target(name)
        }

        TYPE_SRV -> {
            if (rdLength < 7) null
            else {
                val priority = readU16(bytes, offset) ?: return null
                val weight = readU16(bytes, offset + 2) ?: return null
                val port = readU16(bytes, offset + 4) ?: return null
                val (target, _) = readName(bytes, offset + 6, offset + rdLength) ?: return null
                RData.Service(priority, weight, port, target)
            }
        }

        TYPE_TXT -> {
            val values = ArrayList<String>()
            var pos = offset
            val end = offset + rdLength
            while (pos < end) {
                val len = bytes[pos].toInt() and 0xFF
                pos++
                if (pos + len > end) break
                values += String(bytes, pos, len, StandardCharsets.UTF_8)
                pos += len
            }
            RData.Text(values)
        }

        else -> RData.Raw(bytes.copyOfRange(offset, (offset + rdLength).coerceAtMost(limit)))
        }
    }

    private fun formatIpv6(bytes: ByteArray, offset: Int): String {
        val parts = (0 until 8).map {
            val hi = bytes[offset + it * 2].toInt() and 0xFF
            val lo = bytes[offset + it * 2 + 1].toInt() and 0xFF
            (hi shl 8) or lo
        }
        // Compress the longest run of zero groups, the same way RFC 5952 does.
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        for (i in 0..8) {
            if (i < 8 && parts[i] == 0) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0) {
                    val len = i - runStart
                    if (len > bestLength) {
                        bestStart = runStart
                        bestLength = len
                    }
                    runStart = -1
                }
            }
        }
        if (bestLength < 2) return parts.joinToString(":") { it.toString(16) }
        val head = parts.take(bestStart).joinToString(":") { it.toString(16) }
        val tail = parts.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    /**
     * Reads a possibly-compressed name. Returns the decoded name (without the
     * trailing dot) and the offset just after the name at the original position.
     */
    fun readName(bytes: ByteArray, start: Int, limit: Int = bytes.size): Pair<String, Int>? {
        var pos = start
        var end = start
        var jumped = false
        var guard = 0
        val label = StringBuilder()
        while (true) {
            if (pos >= limit) return null
            val length = bytes[pos].toInt() and 0xFF
            if (length == 0) {
                pos++
                if (!jumped) end = pos
                break
            }
            if (length and 0xC0 == 0xC0) {
                if (pos + 1 >= limit) return null
                val pointer = ((length and 0x3F) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
                if (!jumped) end = pos + 2
                if (pointer >= limit) return null
                pos = pointer
                jumped = true
                guard++
                if (guard > 32) return null
                continue
            }
            if (length > 63 || pos + 1 + length > limit) return null
            if (label.isNotEmpty()) label.append('.')
            label.append(String(bytes, pos + 1, length, StandardCharsets.UTF_8))
            pos += 1 + length
            if (!jumped) end = pos
        }
        return (if (label.isEmpty()) "." else label.toString()) to end
    }

    fun buildQuery(
        questions: List<Pair<String, Int>>,
        id: Int = 0,
        recursionDesired: Boolean = false,
        unicastResponse: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id and 0xFFFF)
        writeU16(out, if (recursionDesired) 0x0100 else 0x0000)
        writeU16(out, questions.size)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        for ((name, type) in questions) {
            out.write(encodeName(name))
            writeU16(out, type)
            writeU16(out, if (unicastResponse) 0x8001 else 0x0001)
        }
        return out.toByteArray()
    }

    fun encodeName(name: String): ByteArray {
        val trimmed = name.trim().trimEnd('.')
        if (trimmed.isEmpty()) return byteArrayOf(0)
        val out = ByteArrayOutputStream()
        for (label in trimmed.split('.')) {
            val bytes = label.toByteArray(StandardCharsets.UTF_8)
            val clipped = bytes.take(63)
            out.write(clipped.size)
            out.write(clipped.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    fun reverseName(ip: String): String? {
        val value = Subnet.parseIp(ip) ?: return null
        return buildString {
            append((value shr 0) and 0xFF).append('.')
            append((value shr 8) and 0xFF).append('.')
            append((value shr 16) and 0xFF).append('.')
            append((value shr 24) and 0xFF)
            append(".in-addr.arpa")
        }
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int? {
        if (offset + 2 > bytes.size) return null
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long? {
        if (offset + 4 > bytes.size) return null
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
