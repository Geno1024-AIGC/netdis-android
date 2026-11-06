package com.geno1024.ai.netdis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetTest {

    @Test
    fun `parses and formats dotted quads`() {
        assertEquals(0xC0A80101L, Subnet.parseIp("192.168.1.1"))
        assertEquals("192.168.1.1", Subnet.formatIp(0xC0A80101L))
        assertEquals("255.255.255.255", Subnet.formatIp(Subnet.MAX_ADDRESS))
        assertEquals(0L, Subnet.parseIp("0.0.0.0"))
    }

    @Test
    fun `rejects malformed addresses`() {
        assertNull(Subnet.parseIp(""))
        assertNull(Subnet.parseIp("192.168.1"))
        assertNull(Subnet.parseIp("192.168.1.256"))
        assertNull(Subnet.parseIp("192.168.1.a"))
        assertNull(Subnet.parseIp("192.168.1.1.1"))
    }

    @Test
    fun `builds masks from prefixes`() {
        assertEquals(0xFFFFFF00L, Subnet.maskFor(24))
        assertEquals(0xFFFF0000L, Subnet.maskFor(16))
        assertEquals(0L, Subnet.maskFor(0))
        assertEquals(Subnet.MAX_ADDRESS, Subnet.maskFor(32))
    }

    @Test
    fun `cidr contains only its own addresses`() {
        val cidr = Subnet.parseCidr("192.168.1.0/24")!!
        assertTrue(cidr.contains("192.168.1.1"))
        assertTrue(cidr.contains("192.168.1.255"))
        assertFalse(cidr.contains("192.168.2.1"))
        assertEquals("192.168.1.0/24", cidr.toString())
    }

    @Test
    fun `host range skips network and broadcast`() {
        val cidr = Subnet.parseCidr("192.168.1.0/24")!!
        val range = cidr.hostRange()
        assertEquals("192.168.1.1", Subnet.formatIp(range.first))
        assertEquals("192.168.1.254", Subnet.formatIp(range.last))
        assertEquals(254, cidr.hostCount)
        assertEquals(254, cidr.hosts().size)
    }

    @Test
    fun `host range keeps both addresses on point to point links`() {
        val cidr = Subnet.parseCidr("10.0.0.0/31")!!
        assertEquals(2, cidr.hostCount)
        assertEquals(2, cidr.hosts().size)
    }

    @Test
    fun `bare address is treated as a slash 24`() {
        assertEquals("192.168.7.0/24", Subnet.parseCidr("192.168.7.42")?.toString())
        assertNull(Subnet.parseCidr("192.168.7.0/33"))
        assertNull(Subnet.parseCidr("not-a-network"))
        assertNull(Subnet.parseCidr(""))
    }

    @Test
    fun `cidrOf normalises the network address`() {
        val cidr = Subnet.cidrOf("192.168.1.130", 25)
        assertNotNull(cidr)
        assertEquals("192.168.1.128/25", cidr.toString())
        assertEquals("192.168.1.255", Subnet.formatIp(cidr!!.lastAddress))
    }

    @Test
    fun `gateway guess prefers the first host`() {
        assertEquals("192.168.1.1", Subnet.guessGateway("192.168.1.57"))
        assertEquals("192.168.1.254", Subnet.guessGateway("192.168.1.1"))
        assertNull(Subnet.guessGateway("garbage"))
    }
}
