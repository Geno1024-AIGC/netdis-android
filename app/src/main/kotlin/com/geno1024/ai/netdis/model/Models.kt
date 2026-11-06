package com.geno1024.ai.netdis.model

/** How the app should pick its colour scheme. Persisted as a name. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** What a discovered device most likely is. Used for icons and colour coding. */
enum class DeviceKind {
    ROUTER, PHONE, COMPUTER, TV, SPEAKER, PRINTER, CAMERA, NAS, IOT, CONSOLE, UNKNOWN,
}

/** The medium a device is attached through, when we can tell. */
enum class LinkKind { ETHERNET, WIFI, UNKNOWN }

/** Which router web API a configuration should use. AUTO tries each adapter. */
enum class RouterType {
    AUTO,
    OPENWRT,
    MIKROTIK,
    ZTE,
    TP_LINK,
    MERCURY,
    ASUS,
    XIAOMI,
    HUAWEI,
    TENDA,
    IKUAI,
    RUIJIE,
    H3C,
    NETGEAR,
    D_LINK,
    UNKNOWN;

    val label: String
        get() = when (this) {
            AUTO -> "自动识别"
            OPENWRT -> "OpenWrt / LuCI"
            MIKROTIK -> "MikroTik RouterOS"
            ZTE -> "中兴 ZTE"
            TP_LINK -> "TP-Link"
            MERCURY -> "水星 Mercury"
            ASUS -> "华硕 ASUS"
            XIAOMI -> "小米 MiWiFi"
            HUAWEI -> "华为 Huawei"
            TENDA -> "腾达 Tenda"
            IKUAI -> "爱快 iKuai"
            RUIJIE -> "锐捷 Ruijie"
            H3C -> "H3C"
            NETGEAR -> "Netgear"
            D_LINK -> "D-Link"
            UNKNOWN -> "仅指纹"
        }
}

/** Evidence that a host exists. Kept so the detail sheet can explain itself. */
enum class HostSource {
    ARP, TCP, ICMP, MDNS, SSDP, NETBIOS, DNS, DHCP, WIFI, ROUTER_ARP, MANUAL,
}

enum class NodeKind { INTERNET, ROUTER, DEVICE }

data class LocalNetwork(
    val interfaceName: String,
    val localIp: String,
    val prefixLength: Int,
    val gateway: String?,
    val dnsServers: List<String>,
    val cidr: String,
    val hostCount: Int,
    val transport: String,
)

data class HostRecord(
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val vendor: String? = null,
    val openPorts: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    val kind: DeviceKind = DeviceKind.UNKNOWN,
    val link: LinkKind = LinkKind.UNKNOWN,
    val online: Boolean = true,
    val sources: Set<HostSource> = emptySet(),
    val routerId: String? = null,
    /** User-supplied name, kept separately so a later DHCP hostname still shows up. */
    val alias: String? = null,
    /** User-supplied free-form note for an otherwise unknown device. */
    val note: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val displayName: String
        get() = alias?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
            ?: services.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: vendor?.takeIf { it.isNotBlank() }
            ?: ip

    /** Best name we can offer without the address, for labels under a node. */
    val shortName: String
        get() = alias?.takeIf { it.isNotBlank() }?.substringBefore('.')
            ?: hostname?.takeIf { it.isNotBlank() }?.substringBefore('.')
            ?: services.firstOrNull()?.takeIf { it.isNotBlank() }?.substringBefore('.')
            ?: vendor?.takeIf { it.isNotBlank() }
            ?: ip.substringAfterLast('.')
}

data class RouterConfig(
    val id: String,
    val label: String,
    val host: String,
    val scheme: String = "http",
    val port: Int = 0,
    val type: RouterType = RouterType.AUTO,
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val note: String = "",
) {
    val displayHost: String
        get() = if (port > 0) "$host:$port" else host

    val baseUrl: String
        get() {
            val p = if (port > 0) port else if (scheme == "https") 443 else 80
            return "$scheme://$host:$p"
        }
}

data class RouterSnapshot(
    val configId: String?,
    val label: String,
    val host: String,
    val reachable: Boolean,
    val message: String? = null,
    val actualType: RouterType? = null,
    val model: String? = null,
    val firmware: String? = null,
    val uptime: String? = null,
    val wanIp: String? = null,
    val wanGateway: String? = null,
    val lanCidrs: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val clients: List<HostRecord> = emptyList(),
    val wirelessCount: Int = 0,
    val parentId: String? = null,
    val externalIp: String? = null,
    val fingerprint: String? = null,
) {
    val clientCount: Int get() = clients.size
}

data class TopologyNode(
    val id: String,
    val label: String,
    val subtitle: String,
    val kind: NodeKind,
    val deviceKind: DeviceKind? = null,
    val routerId: String? = null,
    val ip: String? = null,
    val online: Boolean = true,
    val depth: Int = 0,
    val parentId: String? = null,
    val host: HostRecord? = null,
    val router: RouterConfig? = null,
    val snapshot: RouterSnapshot? = null,
    val clientCount: Int = 0,
)

data class TopologyEdge(
    val from: String,
    val to: String,
    val link: LinkKind = LinkKind.UNKNOWN,
    val label: String? = null,
)

data class TopologyGraph(
    val nodes: List<TopologyNode> = emptyList(),
    val edges: List<TopologyEdge> = emptyList(),
) {
    fun node(id: String): TopologyNode? = nodes.firstOrNull { it.id == id }
}

data class ScanSnapshot(
    val startedAt: Long,
    val finishedAt: Long,
    val local: LocalNetwork?,
    val hosts: List<HostRecord> = emptyList(),
    val routers: List<RouterSnapshot> = emptyList(),
    val graph: TopologyGraph = TopologyGraph(),
    val warnings: List<String> = emptyList(),
) {
    val durationMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0)
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val subnetOverride: String = "",
    val deepScan: Boolean = true,
    val probeTimeoutMs: Int = 320,
    val showOffline: Boolean = true,
    val updateMirrorPrefix: String = "",
    /** Modern Android blocks the neighbour table; a rooted phone can still read it. */
    val readArpWithRoot: Boolean = false,
    /** Record app↔router HTTP exchanges so a new firmware can be adapted. */
    val captureTraffic: Boolean = false,
)

enum class ScanStage(val label: String) {
    IDLE("待命"),
    PREPARE("读取网络信息"),
    WAKE("唤醒局域网设备"),
    PROBE("探测在线主机"),
    SERVICES("发现局域网服务"),
    NAMES("解析主机名"),
    ROUTERS("读取路由器"),
    TOPOLOGY("生成拓扑"),
    DONE("扫描完成"),
    FAILED("扫描失败"),
}

data class ScanProgress(
    val stage: ScanStage = ScanStage.IDLE,
    val fraction: Float = 0f,
    val detail: String = "",
    val found: Int = 0,
)
