package com.geno1024.netdis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArpTableTest {

    private val sample = """
        IP address       HW type     Flags       HW address            Mask     Device
        192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0
        192.168.1.20     0x1         0x0         12:22:33:44:55:66     *        wlan0
        192.168.1.30     0x1         0x2         00:00:00:00:00:00     *        wlan0
        garbage line
        10.0.0.9         0x1         0x2         0A-1B-2C-3D-4E-5F     *        eth0
    """.trimIndent()

    @Test
    fun `parses complete and incomplete entries`() {
        val entries = ArpTable.parse(sample)
        assertEquals(3, entries.size)

        val gateway = entries.first { it.ip == "192.168.1.1" }
        assertEquals("aa:bb:cc:dd:ee:ff", gateway.mac)
        assertEquals("wlan0", gateway.device)
        assertTrue(gateway.complete)

        val pending = entries.first { it.ip == "192.168.1.20" }
        assertFalse(pending.complete)

        assertEquals("0a:1b:2c:3d:4e:5f", entries.first { it.ip == "10.0.0.9" }.mac)
    }

    @Test
    fun `skips the header, junk and null macs`() {
        val entries = ArpTable.parse(sample)
        assertTrue(entries.none { it.mac == "00:00:00:00:00:00" })
        assertTrue(entries.none { it.ip == "IP" })
    }

    @Test
    fun `normalises every common mac spelling`() {
        assertEquals("aa:bb:cc:dd:ee:ff", ArpTable.normalizeMac("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", ArpTable.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("aa:bb:cc:dd:ee:ff", ArpTable.normalizeMac("aabbccddeeff"))
        assertEquals("aa:bb:cc:dd:ee:ff", ArpTable.normalizeMac("AABB.CCDD.EEFF"))
    }

    @Test
    fun `rejects unusable macs`() {
        assertNull(ArpTable.normalizeMac(null))
        assertNull(ArpTable.normalizeMac(""))
        assertNull(ArpTable.normalizeMac("aa:bb:cc"))
        assertNull(ArpTable.normalizeMac("00:00:00:00:00:00"))
        // Multicast/broadcast bits cannot be a station address.
        assertNull(ArpTable.normalizeMac("01:00:5e:00:00:01"))
        assertNull(ArpTable.normalizeMac("ff:ff:ff:ff:ff:ff"))
    }
}
