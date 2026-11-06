package com.geno1024.netdis.net

/**
 * `/proc/net/arp` is the only public way for an unrooted app to learn MAC
 * addresses, and some Android builds restrict it. Parsing is kept separate so a
 * restricted device simply produces an empty table instead of failing a scan.
 */
object ArpTable {

    data class Entry(
        val ip: String,
        val mac: String,
        val device: String,
        val complete: Boolean,
    )

    fun parse(text: String): List<Entry> {
        val result = ArrayList<Entry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("IP address")) continue
            val fields = line.split(Regex("\\s+"))
            if (fields.size < 5) continue
            val ip = fields[0]
            if (Subnet.parseIp(ip) == null) continue
            val flags = fields[2].removePrefix("0x").toIntOrNull(16)
                ?: fields[2].removePrefix("0x").toIntOrNull()
                ?: 0
            val mac = normalizeMac(fields[3]) ?: continue
            val device = fields.getOrElse(5) { "" }
            result += Entry(
                ip = ip,
                mac = mac,
                device = device,
                complete = (flags and 0x2) != 0,
            )
        }
        return result
    }

    /**
     * Normalises the several MAC spellings routers and `/proc` use into lower
     * case colon form, or null when the value is not a usable unicast address.
     */
    fun normalizeMac(raw: String?): String? {
        if (raw == null) return null
        val hex = raw.trim().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (hex.length != 12) return null
        val bytes = hex.chunked(2)
        if (bytes.all { it == "00" }) return null
        val first = bytes[0].toInt(16)
        if (first and 0x1 != 0) return null // multicast/broadcast source
        return bytes.joinToString(":")
    }
}
