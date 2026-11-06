package com.geno1024.ai.netdis.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketTest {

    private class Writer {
        val out = ByteArrayOutputStream()

        fun u16(value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun u32(value: Long) {
            out.write(((value shr 24) and 0xFF).toInt())
            out.write(((value shr 16) and 0xFF).toInt())
            out.write(((value shr 8) and 0xFF).toInt())
            out.write((value and 0xFF).toInt())
        }

        fun bytes(value: ByteArray) {
            out.write(value)
        }

        fun size(): Int = out.size()
    }

    private fun buildResponse(): ByteArray {
        val w = Writer()
        w.u16(0x1234)
        w.u16(0x8400) // response, authoritative
        w.u16(1) // questions
        w.u16(3) // answers
        w.u16(0)
        w.u16(0)

        val question = DnsPacket.encodeName("_http._tcp.local")
        w.bytes(question)
        w.u16(DnsPacket.TYPE_PTR)
        w.u16(1)

        // Answer 1: PTR whose RDATA holds a fresh (uncompressed) name.
        val target = DnsPacket.encodeName("printer._http._tcp.local")
        w.u16(0xC00C)
        w.u16(DnsPacket.TYPE_PTR)
        w.u16(0x8001) // cache-flush
        w.u32(120)
        w.u16(target.size)
        val targetOffset = w.size()
        w.bytes(target)

        // Answer 2: SRV pointing at the same name through compression.
        val srvName = DnsPacket.encodeName("_http._tcp.local")
        val srvRdata = ByteArrayOutputStream().apply {
            write(0); write(0) // priority
            write(0); write(0) // weight
            write(0x1F); write(0x90) // port 8080
            write(srvName)
        }.toByteArray()
        w.u16(0xC000 or targetOffset)
        w.u16(DnsPacket.TYPE_SRV)
        w.u16(0x8001)
        w.u32(60)
        w.u16(srvRdata.size)
        w.bytes(srvRdata)

        // Answer 3: TXT with two length-prefixed strings.
        val txt = byteArrayOf(5) + "model".toByteArray() + byteArrayOf(3) + "abc".toByteArray()
        w.u16(0xC000 or targetOffset)
        w.u16(DnsPacket.TYPE_TXT)
        w.u16(0x8001)
        w.u32(60)
        w.u16(txt.size)
        w.bytes(txt)

        return w.out.toByteArray()
    }

    @Test
    fun `parses questions, compressed names and typed rdata`() {
        val message = DnsPacket.parse(buildResponse())
        assertNotNull(message)
        requireNotNull(message)

        assertEquals(0x1234, message.id)
        assertTrue(message.isResponse)
        assertEquals("_http._tcp.local", message.questions.single().name)
        assertEquals(DnsPacket.TYPE_PTR, message.questions.single().type)
        assertEquals(3, message.answers.size)

        val ptr = message.answers[0]
        assertEquals("_http._tcp.local", ptr.name)
        assertEquals(DnsPacket.RData.Target("printer._http._tcp.local"), ptr.data)

        // The SRV record's owner name is a compression pointer into answer 1.
        val srv = message.answers[1]
        assertEquals("printer._http._tcp.local", srv.name)
        val service = srv.data as DnsPacket.RData.Service
        assertEquals(8080, service.port)
        assertEquals("_http._tcp.local", service.target)

        val txt = message.answers[2].data as DnsPacket.RData.Text
        assertEquals(listOf("model", "abc"), txt.values)
    }

    @Test
    fun `queries round-trip through the parser`() {
        val query = DnsPacket.buildQuery(
            listOf("_services._dns-sd._udp.local" to DnsPacket.TYPE_PTR),
            id = 0x4D2,
            unicastResponse = true,
        )
        val message = DnsPacket.parse(query)
        assertNotNull(message)
        requireNotNull(message)
        assertEquals(0x4D2, message.id)
        assertEquals(false, message.isResponse)
        assertEquals("_services._dns-sd._udp.local", message.questions.single().name)
        assertEquals(DnsPacket.TYPE_PTR, message.questions.single().type)
        assertEquals(1, message.questions.single().clazz)
        assertTrue(message.answers.isEmpty())
    }

    @Test
    fun `encodes the root and trims the trailing dot`() {
        assertArrayEquals(byteArrayOf(0), DnsPacket.encodeName(""))
        assertArrayEquals(byteArrayOf(0), DnsPacket.encodeName("."))
        assertArrayEquals(
            DnsPacket.encodeName("local"),
            DnsPacket.encodeName("local."),
        )
        assertTrue(
            (byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0))
                .contentEquals(DnsPacket.encodeName("local")),
        )
    }

    @Test
    fun `rejects truncated packets instead of throwing`() {
        assertNull(DnsPacket.parse(ByteArray(4)))
        assertNull(DnsPacket.parse(DnsPacket.buildQuery(listOf("a.b" to 1)).copyOfRange(0, 13)))
        // A compression pointer loop must terminate.
        val loop = byteArrayOf(
            0, 0, 0x84.toByte(), 0, 0, 1, 0, 0, 0, 0, 0, 0,
            0xC0.toByte(), 0x0C,
        )
        assertNull(DnsPacket.parse(loop))
    }

    @Test
    fun `builds reverse lookup names`() {
        assertEquals("1.1.168.192.in-addr.arpa", DnsPacket.reverseName("192.168.1.1"))
        assertNull(DnsPacket.reverseName("nope"))
    }

    @Test
    fun `formats ipv6 with rfc 5952 compression`() {
        val w = Writer()
        w.u16(1); w.u16(0x8400); w.u16(0); w.u16(1); w.u16(0); w.u16(0)
        w.bytes(DnsPacket.encodeName("host.local"))
        w.u16(DnsPacket.TYPE_AAAA); w.u16(1); w.u32(60); w.u16(16)
        val address = ByteArray(16).apply {
            this[0] = 0x20; this[1] = 0x01
            this[15] = 0x01
        }
        w.bytes(address)
        val message = DnsPacket.parse(w.out.toByteArray())
        assertNotNull(message)
        val data = message!!.answers.single().data as DnsPacket.RData.Address
        assertEquals("2001::1", data.ip)
    }
}
