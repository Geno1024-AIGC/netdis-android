package com.geno1024.ai.netdis.net

/**
 * IPv4 arithmetic used by the scanner. Addresses are held as unsigned 32-bit
 * values in a Long so no sign juggling leaks into prefix comparisons.
 */
object Subnet {

    const val MAX_ADDRESS = 0xFFFF_FFFFL

    fun parseIp(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun formatIp(value: Long): String {
        val v = value and MAX_ADDRESS
        return buildString {
            append((v shr 24) and 0xFF)
            append('.')
            append((v shr 16) and 0xFF)
            append('.')
            append((v shr 8) and 0xFF)
            append('.')
            append(v and 0xFF)
        }
    }

    fun maskFor(prefix: Int): Long {
        require(prefix in 0..32) { "prefix out of range: $prefix" }
        if (prefix == 0) return 0L
        return (MAX_ADDRESS shl (32 - prefix)) and MAX_ADDRESS
    }

    data class Cidr(val network: Long, val prefix: Int) {
        val mask: Long get() = maskFor(prefix)
        val size: Long get() = 1L shl (32 - prefix)
        val lastAddress: Long get() = (network + size - 1) and MAX_ADDRESS

        fun contains(value: Long): Boolean = (value and mask) == network

        fun contains(ip: String): Boolean = parseIp(ip)?.let(::contains) == true

        /** Addresses worth probing, skipping network/broadcast where they exist. */
        fun hostRange(): LongRange {
            val first = if (prefix <= 30) network + 1 else network
            val last = if (prefix <= 30) lastAddress - 1 else lastAddress
            return first..last
        }

        val hostCount: Int
            get() = when {
                prefix >= 31 -> size.toInt()
                else -> (size - 2).coerceAtLeast(0).toInt()
            }

        fun hosts(limit: Int = 4096): List<Long> {
            val range = hostRange()
            val count = (range.last - range.first + 1).coerceAtLeast(0)
            if (count <= 0) return emptyList()
            val capped = minOf(count, limit.toLong())
            return (0 until capped).map { range.first + it }
        }

        override fun toString(): String = "${formatIp(network)}/$prefix"
    }

    fun cidrOf(ip: Long, prefix: Int): Cidr {
        require(prefix in 0..32) { "prefix out of range: $prefix" }
        return Cidr(ip and maskFor(prefix), prefix)
    }

    fun cidrOf(ip: String, prefix: Int): Cidr? = parseIp(ip)?.let { cidrOf(it, prefix) }

    /**
     * Accepts `192.168.1.0/24` and, as a convenience for the subnet override
     * field, a bare address, which is treated as a /24 around that address.
     */
    fun parseCidr(text: String): Cidr? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val slash = trimmed.indexOf('/')
        if (slash < 0) {
            val ip = parseIp(trimmed) ?: return null
            return cidrOf(ip, 24)
        }
        val ip = parseIp(trimmed.substring(0, slash)) ?: return null
        val prefix = trimmed.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        return cidrOf(ip, prefix)
    }

    /** Best effort gateway guess used only when LinkProperties has no route. */
    fun guessGateway(ip: String): String? {
        val value = parseIp(ip) ?: return null
        for (hostOctet in listOf(1L, 254L)) {
            val candidate = (value and maskFor(24)) or hostOctet
            if (candidate != value) return formatIp(candidate)
        }
        return null
    }
}
