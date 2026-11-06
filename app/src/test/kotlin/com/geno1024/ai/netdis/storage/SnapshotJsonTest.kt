package com.geno1024.ai.netdis.storage

import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.LocalNetwork
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.model.ScanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotJsonTest {

    private val local = LocalNetwork(
        interfaceName = "wlan0",
        localIp = "192.168.1.50",
        prefixLength = 24,
        gateway = "192.168.1.1",
        dnsServers = listOf("192.168.1.1", "8.8.8.8"),
        cidr = "192.168.1.0/24",
        hostCount = 254,
        transport = "Wi-Fi",
    )

    private val phone = HostRecord(
        ip = "192.168.1.50",
        mac = "aa:bb:cc:dd:ee:50",
        hostname = "pixel",
        vendor = "Google",
        openPorts = listOf(22, 8080),
        services = listOf("ssh"),
        kind = DeviceKind.PHONE,
        link = LinkKind.WIFI,
        online = true,
        sources = setOf(HostSource.TCP, HostSource.MDNS),
        routerId = "gw",
        extra = mapOf("model" to "Pixel 9"),
    )

    private val router = RouterSnapshot(
        configId = "gw",
        label = "主路由",
        host = "192.168.1.1",
        reachable = true,
        message = "已登录",
        actualType = RouterType.OPENWRT,
        model = "Xiaomi AX3000",
        firmware = "OpenWrt 24.10",
        uptime = "3天4小时",
        wanIp = "100.64.0.2",
        wanGateway = "100.64.0.1",
        lanCidrs = listOf("192.168.1.0/24"),
        dnsServers = listOf("192.168.1.1"),
        clients = listOf(
            HostRecord(
                ip = "192.168.1.60",
                mac = "aa:bb:cc:dd:ee:60",
                hostname = "nas",
                sources = setOf(HostSource.DHCP),
            ),
        ),
        wirelessCount = 3,
        fingerprint = "Xiaomi AX3000 · OpenWrt 24.10",
    )

    private fun snapshot() = ScanSnapshot(
        startedAt = 1_700_000_000_000,
        finishedAt = 1_700_000_012_345,
        local = local,
        hosts = listOf(phone),
        routers = listOf(router),
        warnings = listOf("未配置路由器账号"),
    )

    @Test
    fun `round-trips the scan and rebuilds the graph`() {
        val decoded = SnapshotJson.decode(SnapshotJson.encode(snapshot()))
        assertNotNull(decoded)
        requireNotNull(decoded)

        assertEquals(1_700_000_000_000, decoded.startedAt)
        assertEquals(12_345, decoded.durationMs)
        assertEquals(local, decoded.local)
        assertEquals(listOf(phone), decoded.hosts)
        assertEquals(listOf(router), decoded.routers)
        assertEquals(listOf("未配置路由器账号"), decoded.warnings)

        // The graph is derived, not stored, but must come back complete.
        assertTrue(decoded.graph.nodes.any { it.kind == NodeKind.INTERNET })
        assertTrue(decoded.graph.nodes.any { it.kind == NodeKind.ROUTER && it.id == "router:gw" })
        assertTrue(decoded.graph.nodes.any { it.kind == NodeKind.DEVICE && it.ip == "192.168.1.60" })
    }

    @Test
    fun `keeps optional fields optional`() {
        val minimal = ScanSnapshot(
            startedAt = 5,
            finishedAt = 6,
            local = null,
        )
        val decoded = SnapshotJson.decode(SnapshotJson.encode(minimal))
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertNull(decoded.local)
        assertTrue(decoded.hosts.isEmpty())
        assertTrue(decoded.routers.isEmpty())
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun `rejects missing or unknown versions`() {
        assertNull(SnapshotJson.decode(""))
        assertNull(SnapshotJson.decode("{}"))
        assertNull(SnapshotJson.decode("not json"))
        assertNull(SnapshotJson.decode("""{"version":2,"startedAt":1}"""))
        assertNull(SnapshotJson.decode("""{"version":1,"startedAt":0}"""))
    }

    @Test
    fun `ignores malformed entries instead of failing the whole file`() {
        val decoded = SnapshotJson.decode(
            """
            {"version":1,"startedAt":10,"finishedAt":20,
             "hosts":[{"ip":"192.168.1.10"},{"nope":true}],
             "routers":[{"label":"no host"},{"host":"192.168.1.1"}],
             "warnings":["ok",""]}
            """.trimIndent(),
        )
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(1, decoded.hosts.size)
        assertEquals(1, decoded.routers.size)
        assertEquals(listOf("ok"), decoded.warnings)
        assertEquals(LinkKind.UNKNOWN, decoded.hosts.single().link)
        assertEquals(DeviceKind.UNKNOWN, decoded.hosts.single().kind)
    }
}
