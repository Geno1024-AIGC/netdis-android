package com.geno1024.ai.netdis.topo

import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.TopologyEdge
import com.geno1024.ai.netdis.model.TopologyGraph
import com.geno1024.ai.netdis.model.TopologyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyLayoutTest {

    private fun router(
        id: String,
        parent: String? = null,
        depth: Int = 1,
    ) = TopologyNode(
        id = id,
        label = id,
        subtitle = "192.168.1.1",
        kind = NodeKind.ROUTER,
        deviceKind = DeviceKind.ROUTER,
        depth = depth,
        parentId = parent,
    )

    private fun device(id: String, parent: String, depth: Int = 2) = TopologyNode(
        id = id,
        label = id,
        subtitle = "192.168.1.x",
        kind = NodeKind.DEVICE,
        deviceKind = DeviceKind.PHONE,
        depth = depth,
        parentId = parent,
    )

    private fun graph(
        devices: Int = 3,
        childRouters: Int = 0,
    ): TopologyGraph {
        val nodes = ArrayList<TopologyNode>()
        val edges = ArrayList<TopologyEdge>()
        nodes += TopologyNode(
            id = TopologyBuilder.INTERNET_ID,
            label = "internet",
            subtitle = "",
            kind = NodeKind.INTERNET,
            depth = 0,
        )
        nodes += router("router:a")
        edges += TopologyEdge(from = TopologyBuilder.INTERNET_ID, to = "router:a")
        repeat(devices) { index ->
            val id = "device:a:$index"
            nodes += device(id, "router:a")
            edges += TopologyEdge(from = "router:a", to = id, link = LinkKind.WIFI)
        }
        repeat(childRouters) { index ->
            val id = "router:c$index"
            nodes += router(id, parent = "router:a", depth = 2)
            edges += TopologyEdge(from = "router:a", to = id)
            nodes += device("device:c$index:0", id, depth = 3)
            edges += TopologyEdge(from = id, to = "device:c$index:0")
        }
        return TopologyGraph(nodes = nodes, edges = edges)
    }

    @Test
    fun `positions every node and the internet root`() {
        val layout = TopologyLayout.compute(graph())
        assertTrue(layout.width > 0f)
        assertTrue(layout.height > 0f)
        assertEquals(TopologyLayout.INTERNET_W, layout.internet.w)
        for (node in graph().nodes) {
            val box = layout.nodes[node.id]
            assertTrue("missing box for ${node.id}", box != null)
            assertTrue(box!!.w > 0f && box.h > 0f)
        }
    }

    @Test
    fun `paints internet above routers and devices below them`() {
        val layout = TopologyLayout.compute(graph(devices = 4))
        val internet = layout.internet
        val routerBox = layout.nodes.getValue("router:a")
        val deviceBox = layout.nodes.getValue("device:a:0")
        assertTrue(internet.bottom <= routerBox.y)
        assertTrue(routerBox.bottom <= deviceBox.y)
        assertEquals(routerBox.centerX, internet.centerX, 0.5f)
    }

    @Test
    fun `keeps devices inside their router cluster`() {
        val layout = TopologyLayout.compute(graph(devices = 6))
        val cluster = layout.clusters.single { it.routerId == "router:a" }.box
        val routerBox = layout.nodes.getValue("router:a")
        assertTrue(routerBox.x >= cluster.x - 0.01f)
        assertTrue(routerBox.right <= cluster.right + 0.01f)
        for (index in 0 until 6) {
            val box = layout.nodes.getValue("device:a:$index")
            assertTrue("device $index x", box.x >= cluster.x - 0.01f)
            assertTrue("device $index right", box.right <= cluster.right + 0.01f)
            assertTrue("device $index y", box.y >= routerBox.bottom)
            assertTrue("device $index bottom", box.bottom <= cluster.bottom + 0.01f)
        }
    }

    @Test
    fun `does not overlap devices in the grid`() {
        val layout = TopologyLayout.compute(graph(devices = 8))
        val boxes = (0 until 8).map { layout.nodes.getValue("device:a:$it") }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val a = boxes[i]
                val b = boxes[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertTrue("devices $i and $j overlap", !overlap)
            }
        }
    }

    @Test
    fun `hangs child routers below their parent cluster`() {
        val layout = TopologyLayout.compute(graph(devices = 1, childRouters = 2))
        val parentCluster = layout.clusters.single { it.routerId == "router:a" }.box
        for (index in 0 until 2) {
            val child = layout.nodes.getValue("router:c$index")
            assertTrue(child.y >= parentCluster.bottom)
        }
        val childLinks = layout.links.filter { it.from == "router:a" && it.to.startsWith("router:c") }
        assertEquals(2, childLinks.size)
        for (link in childLinks) {
            val childBox = layout.nodes.getValue(link.to)
            assertTrue(link.y2 <= childBox.y + 1f)
        }
    }

    @Test
    fun `links touch the boxes they connect`() {
        val graph = graph(devices = 2)
        val layout = TopologyLayout.compute(graph)
        val internet = layout.internet
        val routerBox = layout.nodes.getValue("router:a")
        val internetLink = layout.links.single { it.from == TopologyBuilder.INTERNET_ID }
        assertEquals(internet.centerX, internetLink.x1, 0.01f)
        assertEquals(internet.bottom, internetLink.y1, 0.01f)
        assertEquals(routerBox.centerX, internetLink.x2, 0.01f)
        assertEquals(routerBox.y, internetLink.y2, 0.01f)

        val deviceLink = layout.links.single { it.to == "device:a:0" }
        assertEquals(routerBox.centerX, deviceLink.x1, 0.01f)
        assertEquals(routerBox.bottom, deviceLink.y1, 0.01f)
    }

    @Test
    fun `fit transform keeps content inside the viewport`() {
        val layout = TopologyLayout.compute(graph(devices = 12))
        val transform = layout.fitTransform(1000f, 1600f)
        assertTrue(transform.scale > 0f)
        val left = transform.offsetX
        val top = transform.offsetY
        val right = transform.offsetX + layout.width * transform.scale
        val bottom = transform.offsetY + layout.height * transform.scale
        assertTrue(left >= -0.01f)
        assertTrue(top >= -0.01f)
        assertTrue(right <= 1000.01f)
        assertTrue(bottom <= 1600.01f)
    }

    @Test
    fun `handles a graph with no routers`() {
        val layout = TopologyLayout.compute(TopologyGraph())
        assertEquals(TopologyLayout.INTERNET_H, layout.internet.h)
        assertTrue(layout.nodes.containsKey(TopologyBuilder.INTERNET_ID))
        assertTrue(layout.links.isEmpty())
    }
}
