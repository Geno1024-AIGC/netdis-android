package com.geno1024.netdis.topo

import com.geno1024.netdis.model.DeviceKind
import com.geno1024.netdis.model.HostRecord
import com.geno1024.netdis.model.HostSource
import com.geno1024.netdis.model.LinkKind
import com.geno1024.netdis.model.LocalNetwork
import com.geno1024.netdis.model.NodeKind
import com.geno1024.netdis.model.RouterConfig
import com.geno1024.netdis.model.RouterSnapshot
import com.geno1024.netdis.model.TopologyGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyBuilderTest {

    private val local = LocalNetwork(
        interfaceName = "wlan0",
        localIp = "192.168.1.50",
        prefixLength = 24,
        gateway = "192.168.1.1",
        dnsServers = listOf("192.168.1.1"),
        cidr = "192.168.1.0/24",
        hostCount = 254,
        transport = "wifi",
    )

    private fun gatewaySnapshot(): RouterSnapshot = RouterSnapshot(
        configId = "gw",
        label = "客厅主路由",
        host = "192.168.1.1",
        reachable = true,
        lanCidrs = listOf("192.168.1.0/24"),
        wanIp = "100.64.0.2",
        wanGateway = "100.64.0.1",
        clients = listOf(
            HostRecord(
                ip = "192.168.1.10",
                mac = "aa:bb:cc:dd:ee:01",
                hostname = "livingroom-tv",
                sources = setOf(HostSource.DHCP),
            ),
            // A router reporting itself must not become a client node.
            HostRecord(
                ip = "192.168.1.1",
                mac = "aa:bb:cc:dd:ee:02",
                hostname = "openwrt",
                sources = setOf(HostSource.ROUTER_ARP),
            ),
        ),
    )

    private fun childSnapshot(): RouterSnapshot = RouterSnapshot(
        configId = "study",
        label = "书房路由",
        host = "192.168.2.1",
        reachable = true,
        lanCidrs = listOf("192.168.2.0/24"),
        wanIp = "192.168.1.2",
        wanGateway = "192.168.1.1",
        clients = listOf(
            HostRecord(
                ip = "192.168.2.30",
                mac = "aa:bb:cc:dd:ee:03",
                hostname = "study-pc",
                sources = setOf(HostSource.DHCP, HostSource.ROUTER_ARP),
            ),
        ),
    )

    private val phoneHost = HostRecord(
        ip = "192.168.1.50",
        mac = "aa:bb:cc:dd:ee:04",
        hostname = "pixel",
        openPorts = listOf(22),
        sources = setOf(HostSource.TCP),
        kind = DeviceKind.PHONE,
    )

    private fun graph(): TopologyGraph = TopologyBuilder.build(
        local = local,
        hosts = listOf(phoneHost),
        routers = listOf(gatewaySnapshot(), childSnapshot()),
        configs = listOf(
            RouterConfig(id = "gw", label = "客厅主路由", host = "192.168.1.1"),
            RouterConfig(id = "study", label = "书房路由", host = "192.168.2.1"),
        ),
    )

    @Test
    fun `links the child router behind the gateway`() {
        val graph = graph()
        val gateway = graph.node("router:gw")
        val child = graph.node("router:study")
        assertNotNull(gateway)
        assertNotNull(child)
        assertEquals(NodeKind.ROUTER, child!!.kind)
        assertNull(gateway!!.parentId)
        assertEquals("router:gw", child.parentId)
        assertEquals(1, gateway.depth)
        assertEquals(2, child.depth)

        val edge = graph.edges.first { it.to == child.id }
        assertEquals("router:gw", edge.from)
        assertEquals("192.168.1.2", edge.label)
        assertTrue(graph.edges.any { it.from == "router:gw" && it.to == "router:study" })
    }

    @Test
    fun `places the internet above every root router`() {
        val graph = graph()
        val internet = graph.node(TopologyBuilder.INTERNET_ID)
        assertNotNull(internet)
        assertEquals(NodeKind.INTERNET, internet!!.kind)
        assertEquals(0, internet.depth)
        assertTrue(graph.edges.any { it.from == TopologyBuilder.INTERNET_ID && it.to == "router:gw" })
    }

    @Test
    fun `assigns devices to the router whose lan contains them`() {
        val graph = graph()
        val tv = graph.nodes.first { it.ip == "192.168.1.10" }
        assertEquals("router:gw", tv.parentId)
        assertEquals(2, tv.depth)

        val pc = graph.nodes.first { it.ip == "192.168.2.30" }
        assertEquals("router:study", pc.parentId)
        assertEquals(3, pc.depth)

        // The phone was seen by the phone itself and by no router.
        val phone = graph.nodes.first { it.ip == "192.168.1.50" }
        assertEquals("router:gw", phone.parentId)
        assertEquals(DeviceKind.PHONE, phone.deviceKind)
    }

    @Test
    fun `does not draw routers as devices`() {
        val graph = graph()
        assertTrue(graph.nodes.none { it.kind == NodeKind.DEVICE && it.ip == "192.168.1.1" })
        assertTrue(graph.nodes.none { it.kind == NodeKind.DEVICE && it.ip == "192.168.2.1" })
        assertTrue(graph.nodes.none { it.kind == NodeKind.DEVICE && it.ip == "192.168.1.2" })
    }

    @Test
    fun `merges duplicate reports of the same address`() {
        val graph = TopologyBuilder.build(
            local = local,
            hosts = listOf(phoneHost),
            routers = listOf(
                gatewaySnapshot().copy(
                    clients = listOf(
                        HostRecord(
                            ip = "192.168.1.60",
                            mac = "aa:bb:cc:00:00:60",
                            sources = setOf(HostSource.DHCP),
                            link = LinkKind.WIFI,
                        ),
                    ),
                ),
            ),
        )
        val device = graph.nodes.single { it.ip == "192.168.1.60" }
        assertEquals("aa:bb:cc:00:00:60", device.host?.mac)
        assertEquals(LinkKind.WIFI, device.host?.link)
        // One node per address, not one per reporter.
        assertEquals(1, graph.nodes.count { it.ip == "192.168.1.60" })
    }

    @Test
    fun `shows the gateway even without credentials`() {
        val graph = TopologyBuilder.build(local = local, hosts = emptyList(), routers = emptyList())
        val router = graph.nodes.singleOrNull { it.kind == NodeKind.ROUTER }
        assertNotNull(router)
        requireNotNull(router)
        assertFalse(router.online)
        assertEquals("192.168.1.1", router.ip)
        assertNull(router.parentId)
        assertTrue(graph.edges.any { it.from == TopologyBuilder.INTERNET_ID && it.to == router.id })
    }

    @Test
    fun `survives an empty scan`() {
        val graph = TopologyBuilder.build(local = null, hosts = emptyList(), routers = emptyList())
        assertEquals(listOf(TopologyBuilder.INTERNET_ID), graph.nodes.map { it.id })
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun `reports the most specific subnet owner`() {
        val wide = gatewaySnapshot().copy(
            lanCidrs = listOf("192.168.0.0/16"),
            clients = emptyList(),
        )
        val narrow = childSnapshot().copy(
            configId = "narrow",
            host = "192.168.2.1",
            lanCidrs = listOf("192.168.2.0/24"),
            wanIp = null,
            wanGateway = null,
            clients = emptyList(),
        )
        val graph = TopologyBuilder.build(
            local = local,
            hosts = listOf(HostRecord(ip = "192.168.2.44", hostname = "nas", sources = setOf(HostSource.TCP))),
            routers = listOf(wide, narrow),
        )
        val device = graph.nodes.single { it.ip == "192.168.2.44" }
        assertEquals("router:narrow", device.parentId)
    }

    @Test
    fun `breaks parent cycles`() {
        val a = RouterSnapshot(
            configId = "a",
            label = "A",
            host = "10.0.0.1",
            reachable = true,
            lanCidrs = listOf("10.0.1.0/24"),
            wanIp = "10.0.1.2",
            wanGateway = "10.0.2.1",
        )
        val b = RouterSnapshot(
            configId = "b",
            label = "B",
            host = "10.0.2.1",
            reachable = true,
            lanCidrs = listOf("10.0.2.0/24"),
            wanIp = "10.0.2.2",
            wanGateway = "10.0.1.1",
        )
        val graph = TopologyBuilder.build(local = null, hosts = emptyList(), routers = listOf(a, b))
        fun parentOf(id: String): String? = graph.node(id)?.parentId
        var cursor = "router:a"
        val seen = HashSet<String>()
        while (true) {
            assertTrue("cycle at $cursor", seen.add(cursor))
            cursor = parentOf(cursor) ?: break
        }
        assertTrue(graph.nodes.any { it.id == "router:a" && it.parentId == null } ||
            graph.nodes.any { it.id == "router:b" && it.parentId == null })
    }
}
