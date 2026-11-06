package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.RouterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterAdaptersTest {

    @Test
    fun `registry is the single list of supported brands`() {
        assertEquals(
            listOf(RouterType.OPENWRT, RouterType.MIKROTIK, RouterType.ZTE),
            RouterAdapters.all.map { it.type },
        )
        assertEquals(
            listOf(RouterType.AUTO, RouterType.OPENWRT, RouterType.MIKROTIK, RouterType.ZTE),
            RouterAdapters.selectableTypes,
        )
    }

    @Test
    fun `auto probes every adapter and stops at the first success`() {
        val candidates = RouterAdapters.candidates(RouterType.AUTO)
        assertEquals(RouterAdapters.all, candidates)
    }

    @Test
    fun `fingerprint reorders auto probes without dropping any`() {
        val zte = RouterAdapters.candidates(RouterType.AUTO, "中兴 ZXHN F7607P")
        assertEquals(RouterType.ZTE, zte.first().type)
        assertEquals(RouterAdapters.all.size, zte.size)

        val mikrotik = RouterAdapters.candidates(RouterType.AUTO, "RouterOS 7")
        assertEquals(RouterType.MIKROTIK, mikrotik.first().type)

        val openwrt = RouterAdapters.candidates(RouterType.AUTO, "LuCI - OpenWrt")
        assertEquals(RouterType.OPENWRT, openwrt.first().type)
    }

    @Test
    fun `an explicit type runs only that adapter`() {
        assertEquals(listOf(ZteAdapter), RouterAdapters.candidates(RouterType.ZTE))
        assertEquals(listOf(MikroTikAdapter), RouterAdapters.candidates(RouterType.MIKROTIK))
        assertTrue(RouterAdapters.candidates(RouterType.UNKNOWN).isEmpty())
    }

    @Test
    fun `brand hints cover the vendor spellings users see`() {
        assertEquals(RouterType.ZTE, RouterAdapters.preferredType("ZTE"))
        assertEquals(RouterType.ZTE, RouterAdapters.preferredType("中兴"))
        assertEquals(RouterType.MIKROTIK, RouterAdapters.preferredType("mikrotik"))
        assertEquals(RouterType.OPENWRT, RouterAdapters.preferredType("OpenWrt"))
        assertEquals(null, RouterAdapters.preferredType("TP-Link"))
        assertEquals(null, RouterAdapters.preferredType(null))
    }
}
