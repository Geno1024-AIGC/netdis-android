package com.geno1024.ai.netdis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class NetbiosTest {

    private fun encodeResponse(names: List<Pair<String, Pair<Int, Boolean>>>, mac: ByteArray): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(names.size)
        for ((name, suffixAndGroup) in names) {
            val (suffix, group) = suffixAndGroup
            val padded = name.padEnd(15, ' ').take(15).toByteArray(StandardCharsets.ISO_8859_1)
            data.write(padded)
            data.write(suffix)
            val flags = if (group) 0x8000 else 0x0000
            data.write((flags shr 8) and 0xFF)
            data.write(flags and 0xFF)
        }
        data.write(mac)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x82.toByte(), 0x28)) // transaction id
        out.write(byteArrayOf(0x84.toByte(), 0x00)) // response, authoritative
        out.write(byteArrayOf(0, 0)) // questions
        out.write(byteArrayOf(0, 1)) // answers
        out.write(byteArrayOf(0, 0))
        out.write(byteArrayOf(0, 0))
        out.write(DnsPacket.encodeName("*"))
        out.write(byteArrayOf(0, 0x21)) // NBSTAT
        out.write(byteArrayOf(0, 1)) // IN
        out.write(byteArrayOf(0, 0, 0, 60)) // ttl
        val rdata = data.toByteArray()
        out.write(byteArrayOf(((rdata.size shr 8) and 0xFF).toByte(), (rdata.size and 0xFF).toByte()))
        out.write(rdata)
        return out.toByteArray()
    }

    @Test
    fun `query is a well-formed nbstat datagram`() {
        val query = Netbios.buildQuery()
        // Header (12) + encoded name (34) + type/class (4).
        assertEquals(50, query.size)
        assertEquals(0x82, query[0].toInt() and 0xFF)
        assertEquals(0x28, query[1].toInt() and 0xFF)
        assertEquals(0, query[2].toInt())
        assertEquals(1, query[5].toInt())
        assertEquals(0x20, query[12].toInt() and 0xFF)
        // 0x21 is the NBSTAT type; the class is IN.
        assertEquals(0x21, query[47].toInt() and 0xFF)
        assertEquals(0x01, query[49].toInt())
        // Name nibbles are shifted into A..P, and "*" is the wildcard.
        assertEquals('C'.code, query[13].toInt())
        assertEquals('K'.code, query[14].toInt())
    }

    @Test
    fun `extracts the workstation name, workgroup and mac`() {
        val packet = encodeResponse(
            names = listOf(
                "DESKTOP-ABC" to (0x00 to false),
                "WORKGROUP" to (0x00 to true),
            ),
            mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
        )
        val info = Netbios.parseResponse(packet)
        assertNotNull(info)
        requireNotNull(info)
        assertEquals("DESKTOP-ABC", info.name)
        assertEquals("WORKGROUP", info.workgroup)
        assertEquals("aa:bb:cc:dd:ee:ff", info.mac)
    }

    @Test
    fun `falls back to the group name when no workstation entry exists`() {
        val packet = encodeResponse(
            names = listOf("NAS-SERVER" to (0x1D to false)),
            mac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55),
        )
        val info = Netbios.parseResponse(packet)
        assertNotNull(info)
        requireNotNull(info)
        assertEquals("NAS-SERVER", info.name)
        assertEquals("NAS-SERVER", info.workgroup)
    }

    @Test
    fun `ignores msbrowse and rejects unrelated packets`() {
        val packet = encodeResponse(
            names = listOf(
                "__MSBROWSE__" to (0x01 to true),
                "OFFICE-PC" to (0x00 to false),
            ),
            mac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01),
        )
        assertEquals("OFFICE-PC", Netbios.parseResponse(packet)?.name)

        assertNull(Netbios.parseResponse(ByteArray(0)))
        assertNull(Netbios.parseResponse(ByteArray(32)))
    }
}
