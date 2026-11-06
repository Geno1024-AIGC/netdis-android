package com.geno1024.ai.netdis.storage

import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterMatchTest {

    private val byMac = RouterConfig(
        id = "zte",
        label = "二楼",
        host = "192.168.2.1",
        mac = "d4:b7:09:93:07:d2",
    )

    private val byAddress = RouterConfig(
        id = "huawei",
        label = "一楼",
        host = "192.168.1.1",
    )

    private val configs = listOf(byAddress, byMac)

    @Test
    fun `normalizes MACs before comparing`() {
        assertEquals("d4:b7:09:93:07:d2", RouterMatch.normalizeMac("D4B7099307D2"))
        assertEquals("d4:b7:09:93:07:d2", RouterMatch.normalizeMac("D4-B7-09-93-07-D2"))
        assertNull(RouterMatch.normalizeMac("not a mac"))
        assertNull(RouterMatch.normalizeMac("00:00:00:00:00:00"))
    }

    @Test
    fun `a MAC wins even when the address moved`() {
        val found = RouterMatch.find(configs, ip = "10.0.0.99", mac = "D4B7099307D2")
        assertEquals(byMac, found)
    }

    @Test
    fun `falls back to the saved address`() {
        assertEquals(byAddress, RouterMatch.find(configs, ip = "192.168.1.1", mac = null))
        assertEquals(byAddress, RouterMatch.find(configs, ip = "192.168.1.1", mac = "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `returns null for an unknown router`() {
        assertNull(RouterMatch.find(configs, ip = "172.16.0.1", mac = "AA:BB:CC:DD:EE:FF"))
        assertNull(RouterMatch.find(configs, ip = null, mac = null))
    }

    @Test
    fun `matches a snapshot by id, MAC or address`() {
        val snapshot = RouterSnapshot(
            configId = null,
            label = "二楼",
            host = "10.0.0.99",
            reachable = true,
            mac = "D4B7099307D2",
        )
        assertEquals(byMac, RouterMatch.find(configs, snapshot))

        val identified = snapshot.copy(configId = "huawei", host = "10.0.0.98", mac = null)
        assertEquals(byAddress, RouterMatch.find(configs, identified))

        val addressed = snapshot.copy(configId = null, host = "192.168.1.1", mac = null)
        assertEquals(byAddress, RouterMatch.find(configs, addressed))
    }
}
