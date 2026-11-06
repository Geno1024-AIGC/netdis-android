package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.RouterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterAdaptersTest {

    private val brands = listOf(
        RouterType.OPENWRT,
        RouterType.MIKROTIK,
        RouterType.ZTE,
        RouterType.TP_LINK,
        RouterType.MERCURY,
        RouterType.ASUS,
        RouterType.XIAOMI,
        RouterType.HUAWEI,
        RouterType.TENDA,
        RouterType.IKUAI,
        RouterType.RUIJIE,
        RouterType.H3C,
        RouterType.NETGEAR,
        RouterType.D_LINK,
    )

    @Test
    fun `registry is the single list of supported brands`() {
        assertEquals(brands, RouterAdapters.all.map { it.type })
        assertEquals(listOf(RouterType.AUTO) + brands, RouterAdapters.selectableTypes)
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

        val tpLink = RouterAdapters.candidates(RouterType.AUTO, "TP-Link Archer AX55")
        assertEquals(RouterType.TP_LINK, tpLink.first().type)

        val xiaomi = RouterAdapters.candidates(RouterType.AUTO, "小米 MiWiFi")
        assertEquals(RouterType.XIAOMI, xiaomi.first().type)
    }

    @Test
    fun `an explicit type runs only that adapter`() {
        assertEquals(listOf(ZteAdapter), RouterAdapters.candidates(RouterType.ZTE))
        assertEquals(listOf(MikroTikAdapter), RouterAdapters.candidates(RouterType.MIKROTIK))
        assertEquals(listOf(TpLinkAdapter), RouterAdapters.candidates(RouterType.TP_LINK))
        assertEquals(listOf(AsusAdapter), RouterAdapters.candidates(RouterType.ASUS))
        assertTrue(RouterAdapters.candidates(RouterType.UNKNOWN).isEmpty())
    }

    @Test
    fun `brand hints cover the vendor spellings users see`() {
        assertEquals(RouterType.ZTE, RouterAdapters.preferredType("ZTE"))
        assertEquals(RouterType.ZTE, RouterAdapters.preferredType("中兴"))
        assertEquals(RouterType.MIKROTIK, RouterAdapters.preferredType("mikrotik"))
        assertEquals(RouterType.OPENWRT, RouterAdapters.preferredType("OpenWrt"))
        assertEquals(RouterType.TP_LINK, RouterAdapters.preferredType("TP-Link"))
        assertEquals(RouterType.MERCURY, RouterAdapters.preferredType("水星"))
        assertEquals(RouterType.ASUS, RouterAdapters.preferredType("华硕"))
        assertEquals(RouterType.HUAWEI, RouterAdapters.preferredType("Huawei"))
        assertEquals(RouterType.IKUAI, RouterAdapters.preferredType("爱快"))
        assertEquals(RouterType.D_LINK, RouterAdapters.preferredType("D-Link"))
        assertEquals(null, RouterAdapters.preferredType("unknown vendor"))
        assertEquals(null, RouterAdapters.preferredType(null))
    }
}
