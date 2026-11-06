package com.geno1024.ai.netdis.ui

import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.model.ScanSnapshot
import com.geno1024.ai.netdis.model.TopologyGraph
import com.geno1024.ai.netdis.model.TopologyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RouterCredentialsTest {

    private val config = RouterConfig(
        id = "zte",
        label = "二楼",
        host = "192.168.2.1",
        type = RouterType.ZTE,
        mac = "d4:b7:09:93:07:d2",
    )

    private val routerNode = TopologyNode(
        id = "router:zte",
        label = "二楼",
        subtitle = "192.168.2.1",
        kind = NodeKind.ROUTER,
        routerId = "zte",
        ip = "192.168.2.1",
        router = config,
        snapshot = RouterSnapshot(
            configId = "zte",
            label = "二楼",
            host = "192.168.2.1",
            reachable = true,
        ),
    )

    private val deviceNode = TopologyNode(
        id = "device:router:zte:192.168.2.36",
        label = "printer",
        subtitle = "192.168.2.36",
        kind = NodeKind.DEVICE,
        ip = "192.168.2.36",
        parentId = "router:zte",
        host = HostRecord(ip = "192.168.2.36", mac = "f0:13:c1:28:98:1a"),
    )

    private val snapshot = ScanSnapshot(
        startedAt = 1,
        finishedAt = 2,
        local = null,
        graph = TopologyGraph(nodes = listOf(routerNode, deviceNode)),
    )

    @Test
    fun `a router target points at the saved configuration`() {
        val target = routerCredentialTarget(routerNode)
        assertNotNull(target)
        requireNotNull(target)
        assertSame(config, target.config)
        assertEquals("192.168.2.1", target.host)
        assertEquals(RouterType.ZTE, target.type)
        assertSame(config, target.toConfig())
    }

    @Test
    fun `a discovered router without a configuration seeds a new form`() {
        val discovered = routerNode.copy(
            id = "router:auto:192.168.1.1",
            label = "华为网关",
            ip = "192.168.1.1",
            router = null,
            snapshot = RouterSnapshot(
                configId = null,
                label = "华为网关",
                host = "192.168.1.1",
                reachable = false,
                actualType = RouterType.HUAWEI,
                mac = "e8:68:19:11:b4:cc",
            ),
        )
        val target = routerCredentialTarget(discovered)
        requireNotNull(target)
        assertNull(target.config)
        assertEquals("192.168.1.1", target.host)
        assertEquals(RouterType.HUAWEI, target.type)
        assertEquals("e8:68:19:11:b4:cc", target.mac)

        val seeded = target.toConfig()
        assertEquals("", seeded.id)
        assertEquals("华为网关", seeded.label)
        assertEquals(RouterType.HUAWEI, seeded.type)
        assertEquals("e8:68:19:11:b4:cc", seeded.mac)
    }

    @Test
    fun `a device target leads to its parent router`() {
        val target = deviceCredentialTarget(deviceNode, snapshot)
        requireNotNull(target)
        assertSame(config, target.config)
        assertSame(config, target.toConfig())
    }

    @Test
    fun `unrelated nodes have no credential target`() {
        val internet = TopologyNode(
            id = "internet",
            label = "互联网",
            subtitle = "",
            kind = NodeKind.INTERNET,
        )
        assertNull(routerCredentialTarget(internet))
        assertNull(deviceCredentialTarget(internet, snapshot))
        assertNull(deviceCredentialTarget(deviceNode.copy(parentId = null), snapshot))
        assertNull(deviceCredentialTarget(deviceNode, snapshot.copy(graph = TopologyGraph())))
    }
}
