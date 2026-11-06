package com.geno1024.netdis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuiTest {

    @Test
    fun `looks vendors up case insensitively`() {
        assertEquals("Apple", Oui.vendorFor("f0:18:98:11:22:33"))
        assertEquals("Apple", Oui.vendorFor("F0-18-98-11-22-33"))
        assertEquals("Espressif", Oui.vendorFor("24:0a:c4:00:00:01"))
        assertEquals("MikroTik", Oui.vendorFor("2c:c8:1b:aa:bb:cc"))
    }

    @Test
    fun `unknown and invalid macs have no vendor`() {
        assertNull(Oui.vendorFor("02:00:00:00:00:01"))
        assertNull(Oui.vendorFor(null))
        assertNull(Oui.vendorFor("nope"))
    }

    @Test
    fun `classifies network and iot vendors`() {
        assertTrue(Oui.isNetworkVendor("TP-Link"))
        assertTrue(Oui.isNetworkVendor("MikroTik"))
        assertFalse(Oui.isNetworkVendor("Apple"))
        assertTrue(Oui.isIotVendor("Espressif"))
        assertFalse(Oui.isIotVendor("Samsung"))
    }

    @Test
    fun `table is populated`() {
        assertTrue(Oui.entryCount() > 300)
    }
}
