package com.geno1024.ai.netdis.net

import android.content.Context
import com.geno1024.ai.netdis.model.AppSettings
import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.model.ScanProgress
import com.geno1024.ai.netdis.model.ScanSnapshot
import com.geno1024.ai.netdis.model.ScanStage
import com.geno1024.ai.netdis.router.RouterScanner
import com.geno1024.ai.netdis.topo.TopologyBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** `/proc/self/net/arp` is the same table; try both spellings some builds expose. */
private val ARP_PATHS = listOf("/proc/net/arp", "/proc/self/net/arp")

/**
 * Orchestrates one scan. The stages are intentionally independent: direct
 * probes, mDNS/SSDP and router logins run concurrently, then their evidence is
 * merged by IP address before the topology is built.
 */
class DeviceScanner(private val context: Context) {

    private val progressLock = Any()
    private var lastProgress: ScanProgress = ScanProgress()

    suspend fun scan(
        settings: AppSettings,
        routers: List<RouterConfig>,
        onProgress: (ScanProgress) -> Unit,
    ): ScanSnapshot = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val warnings = ArrayList<String>()
        val found = AtomicInteger(0)

        fun report(stage: ScanStage, fraction: Float, detail: String = "") {
            val update = ScanProgress(stage, fraction.coerceIn(0f, 1f), detail, found.get())
            synchronized(progressLock) {
                if (update.stage == lastProgress.stage &&
                    update.fraction - lastProgress.fraction < 0.005f &&
                    update.detail == lastProgress.detail
                ) {
                    return
                }
                lastProgress = update
            }
            onProgress(update)
        }

        report(ScanStage.PREPARE, 0.02f, "读取网络接口")
        val local = LocalNetworkDetector.detect(context, settings.subnetOverride)
        if (local == null) {
            report(ScanStage.FAILED, 1f, "没有可用的局域网连接")
            return@withContext ScanSnapshot(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                local = null,
                warnings = listOf("没有可用的局域网连接，请先连接 Wi-Fi 或网线"),
            )
        }

        if (local.transport == "蜂窝网络") {
            warnings += "当前是蜂窝网络，运营商通常不允许扫描局域网设备"
        }
        if (settings.subnetOverride.isNotBlank()) {
            warnings += "已使用手动网段 ${local.cidr}"
        }

        val cidr = Subnet.parseCidr(local.cidr)
        if (cidr == null) {
            report(ScanStage.FAILED, 1f, "网段无效")
            return@withContext ScanSnapshot(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                local = local,
                warnings = listOf("无法解析网段 ${local.cidr}"),
            )
        }

        val addresses = cidr.hosts(4096)
            .map(Subnet::formatIp)
            .filter { it != local.localIp }
        val addressSet = addresses.toHashSet()
        report(ScanStage.WAKE, 0.06f, "唤醒 ${addresses.size} 个地址")
        wakeHosts(addresses)
        // ARP resolution runs asynchronously after the wake datagrams. Give the
        // kernel a moment so the first read already has usable entries instead
        // of a table full of INCOMPLETE rows.
        delay(350)

        coroutineContext.ensureActive()

        val arp = readArp(settings.readArpWithRoot)

        report(ScanStage.PROBE, 0.12f, "端口探测")
        val ports = if (settings.deepScan) TcpProbe.QUICK_PORTS + TcpProbe.DEEP_PORTS
        else TcpProbe.QUICK_PORTS
        val budget = if (settings.deepScan) 1400L else 900L
        val live = LinkedHashMap<String, HostRecord>()
        val mergeLock = Any()

        fun merge(host: HostRecord) {
            synchronized(mergeLock) {
                val existing = live[host.ip]
                live[host.ip] = if (existing == null) host else mergeHost(existing, host)
            }
        }

        // Service discovery does not depend on the TCP sweep, so it starts now
        // and overlaps with the slow part of the scan.
        val mdnsDeferred = async(Dispatchers.IO) {
            runCatching { MdnsDiscovery.discover(context, 3200) }.getOrDefault(emptyList())
        }
        val ssdpDeferred = async(Dispatchers.IO) {
            runCatching { SsdpDiscovery.discover(context, 3200) }.getOrDefault(emptyList())
        }

        val completed = AtomicInteger(0)
        addresses.parallelMap(48) { ip ->
            coroutineContext.ensureActive()
            val mac = arp.macs[ip]
            val open = TcpProbe.probeHost(
                ip = ip,
                ports = ports,
                connectTimeoutMs = settings.probeTimeoutMs,
                budgetMs = budget,
            )
            var alive = open.isNotEmpty() || mac != null
            var icmp = false
            if (!alive) {
                icmp = TcpProbe.icmpReachable(ip, settings.probeTimeoutMs.coerceAtMost(450))
                alive = icmp
            }
            if (alive) {
                val sources = buildSet {
                    if (open.isNotEmpty()) add(HostSource.TCP)
                    if (mac != null) add(HostSource.ARP)
                    if (icmp) add(HostSource.ICMP)
                }
                merge(
                    HostRecord(
                        ip = ip,
                        mac = mac,
                        openPorts = open,
                        online = true,
                        sources = sources,
                    ),
                )
                found.incrementAndGet()
            }
            val done = completed.incrementAndGet()
            if (done % 16 == 0) {
                report(
                    ScanStage.PROBE,
                    0.12f + 0.38f * done / addresses.size.coerceAtLeast(1),
                    "已探测 $done/${addresses.size}",
                )
            }
        }

        coroutineContext.ensureActive()

        // The TCP sweep forces the kernel to resolve neighbours for every
        // address that answered. Read the table a second time so MACs that
        // appeared during probing are not lost, and add hosts that only the
        // neighbour table knows about.
        val arpAfter = readArp(settings.readArpWithRoot)
        for ((ip, mac) in arpAfter.macs) {
            if (ip !in addressSet) continue
            val existing = live[ip]
            if (existing == null) {
                merge(
                    HostRecord(
                        ip = ip,
                        mac = mac,
                        online = true,
                        sources = setOf(HostSource.ARP),
                    ),
                )
            } else if (existing.mac != mac) {
                merge(existing.copy(mac = mac, sources = existing.sources + HostSource.ARP))
            }
        }
        // Deferred until the routers have been merged: if they supplied the
        // MACs, the local table being blocked is no longer worth warning about.
        val arpEmpty = arp.macs.isEmpty() && arpAfter.macs.isEmpty()

        report(ScanStage.SERVICES, 0.56f, "收集 mDNS / SSDP")
        val mdns = mdnsDeferred.await()
        val ssdp = ssdpDeferred.await()

        for (service in mdns) {
            val extra = LinkedHashMap<String, String>()
            service.port?.let { extra["port"] = it.toString() }
            service.host?.let { extra["mdnsHost"] = it }
            service.txt["model"]?.let { extra["model"] = it }
            for (ip in service.addresses) {
                if (Subnet.parseIp(ip) == null) continue
                merge(
                    HostRecord(
                        ip = ip,
                        hostname = service.displayName.takeIf { it.isNotBlank() },
                        services = listOf(service.friendlyType),
                        sources = setOf(HostSource.MDNS),
                        extra = extra,
                    ),
                )
                found.incrementAndGet()
            }
        }

        for (device in ssdp) {
            val extra = LinkedHashMap<String, String>()
            device.manufacturer?.let { extra["manufacturer"] = it }
            device.modelName?.let { extra["model"] = it }
            device.location?.let { extra["upnpLocation"] = it }
            val ip = device.address ?: continue
            if (Subnet.parseIp(ip) == null) continue
            merge(
                HostRecord(
                    ip = ip,
                    hostname = device.friendlyName?.takeIf { it.isNotBlank() },
                    services = listOf(device.friendlySt),
                    sources = setOf(HostSource.SSDP),
                    kind = if (device.isGateway) DeviceKind.ROUTER else DeviceKind.UNKNOWN,
                    extra = extra,
                ),
            )
            found.incrementAndGet()
        }

        coroutineContext.ensureActive()

        report(ScanStage.NAMES, 0.7f, "解析主机名")
        val snapshotsNow = live.values.toList()
        val dnsServer = local.dnsServers.firstOrNull() ?: local.gateway
        snapshotsNow.parallelMap(24) { host ->
            coroutineContext.ensureActive()
            var updated = host
            val needsNbstat = settings.deepScan || host.openPorts.any { it in setOf(139, 445) }
            if (needsNbstat) {
                val nb = Netbios.query(host.ip, settings.probeTimeoutMs.coerceAtLeast(450))
                if (nb != null) {
                    updated = updated.copy(
                        hostname = updated.hostname ?: nb.name,
                        mac = updated.mac ?: nb.mac,
                        sources = updated.sources + HostSource.NETBIOS,
                        extra = updated.extra + buildMap {
                            nb.workgroup?.let { put("workgroup", it) }
                        },
                    )
                }
            }
            if (dnsServer != null && updated.hostname == null) {
                val name = DnsReverse.lookup(host.ip, dnsServer, 700)
                if (name != null) {
                    updated = updated.copy(
                        hostname = cleanHostname(name),
                        sources = updated.sources + HostSource.DNS,
                    )
                }
            }
            if (updated != host) merge(updated)
        }

        // The phone itself is part of the picture. It has no scan entry because
        // probing your own address says nothing.
        merge(
            HostRecord(
                ip = local.localIp,
                hostname = "本机",
                kind = DeviceKind.PHONE,
                sources = setOf(HostSource.MANUAL),
                online = true,
            ),
        )

        coroutineContext.ensureActive()

        report(ScanStage.ROUTERS, 0.82f, "读取路由器")
        val gateway = local.gateway
        // A saved router can reappear on a different address. If its MAC was
        // learned earlier, follow the MAC and scan the current address while
        // keeping the same credentials.
        val normalizedConfigs = routers.map(RouterScanner::normalize).map { config ->
            val savedMac = ArpTable.normalizeMac(config.mac)
            val moved = savedMac?.let { wanted ->
                live.values.firstOrNull {
                    ArpTable.normalizeMac(it.mac) == wanted
                }
            }
            if (moved != null && moved.ip != config.host) config.copy(host = moved.ip) else config
        }
        val gatewayConfigured = gateway != null && normalizedConfigs.any {
            it.enabled && it.host == gateway
        }
        val toScan = buildList {
            addAll(normalizedConfigs.filter { it.enabled })
            if (gateway != null && !gatewayConfigured) {
                add(
                    RouterConfig(
                        id = "gateway",
                        label = "主路由",
                        host = gateway,
                        type = RouterType.AUTO,
                        note = "自动发现",
                    ),
                )
            }
        }

        val routerSnapshots = toScan.parallelMap(4) { config ->
            report(ScanStage.ROUTERS, 0.82f, "读取 ${config.label.ifBlank { config.host }}")
            val snapshot = RouterScanner.scan(config, settings.probeTimeoutMs * 8 + 1200)
            if (!snapshot.reachable && snapshot.message != null) {
                synchronized(warnings) {
                    if (warnings.size < 8) {
                        warnings += "${config.label.ifBlank { config.host }}：${snapshot.message}"
                    }
                }
            }
            snapshot
        }

        val configuredWithCreds = normalizedConfigs.any {
            it.enabled && (it.username.isNotBlank() || it.password.isNotBlank())
        }
        if (!configuredWithCreds) {
            warnings += "未配置路由器账号，拓扑仅包含本机探测结果；在“路由器”页添加后可读取 DHCP 与多级结构"
        }
        if (routerSnapshots.any { it.reachable && it.clients.isEmpty() }) {
            warnings += "部分路由器已连接但未返回客户端列表（可能权限不足或接口不兼容）"
        }

        for (snapshot in routerSnapshots) {
            val owner = snapshot.configId
            for (client in snapshot.clients) {
                if (Subnet.parseIp(client.ip) == null) continue
                merge(client.copy(routerId = owner ?: client.routerId))
            }
        }

        // Vendor lookup and classification run after the router merge as well,
        // so clients that only the router knows (the normal case when Android
        // blocks the local ARP table) still get a vendor and a device kind.
        for (host in live.values.toList()) {
            val vendor = Oui.vendorFor(host.mac)
            val withVendor = if (vendor != null && host.vendor == null) {
                host.copy(vendor = vendor)
            } else {
                host
            }
            val classified = withVendor.copy(kind = DeviceClassifier.classify(withVendor))
            live[host.ip] = classified
        }

        if (arpEmpty && live.values.none { it.mac != null }) {
            warnings += arpWarning(arp.denied, settings.readArpWithRoot)
        }

        coroutineContext.ensureActive()

        report(ScanStage.TOPOLOGY, 0.94f, "生成拓扑")
        val graph = TopologyBuilder.build(
            local = local,
            hosts = live.values.toList(),
            routers = routerSnapshots,
            configs = normalizedConfigs,
        )

        report(ScanStage.DONE, 1f, "发现 ${live.size} 个节点")
        ScanSnapshot(
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            local = local,
            hosts = live.values.sortedWith(compareBy<HostRecord> { !it.online }.thenBy { Subnet.parseIp(it.ip) ?: 0 }),
            routers = routerSnapshots,
            graph = graph,
            warnings = warnings.distinct(),
        )
    }

    private fun mergeHost(existing: HostRecord, incoming: HostRecord): HostRecord = existing.copy(
        mac = existing.mac ?: incoming.mac,
        hostname = existing.hostname ?: incoming.hostname,
        vendor = existing.vendor ?: incoming.vendor,
        openPorts = (existing.openPorts + incoming.openPorts).distinct().sorted(),
        services = (existing.services + incoming.services).distinct(),
        kind = if (existing.kind != DeviceKind.UNKNOWN) existing.kind else incoming.kind,
        link = if (existing.link != LinkKind.UNKNOWN) existing.link else incoming.link,
        online = existing.online || incoming.online,
        sources = existing.sources + incoming.sources,
        routerId = existing.routerId ?: incoming.routerId,
        extra = existing.extra + incoming.extra,
    )

    /** Sends one datagram per address so the kernel populates its ARP cache. */
    private fun wakeHosts(addresses: List<String>) {
        runCatching {
            DatagramSocket().use { socket ->
                val payload = byteArrayOf(0)
                for (ip in addresses) {
                    runCatching {
                        val address = InetAddress.getByName(ip)
                        socket.send(DatagramPacket(payload, payload.size, address, 9))
                    }
                }
            }
        }
    }

    private data class ArpSnapshot(
        val macs: Map<String, String> = emptyMap(),
        val denied: Boolean = false,
    )

    /**
     * Android 10 blocks `/proc/net/arp` for apps and Android 12+ additionally
     * blocks the netlink neighbour dump, so this normally fails with EACCES.
     * There is no runtime permission that lifts it; a networked router (which
     * the scan reads anyway) or root is the way to get MACs on modern devices.
     */
    private fun readArp(withRoot: Boolean): ArpSnapshot {
        val macs = LinkedHashMap<String, String>()
        var denied = false
        for (path in ARP_PATHS) {
            val text = runCatching { File(path).readText() }.getOrElse { error ->
                if (error.isPermissionDenied()) denied = true
                null
            } ?: continue
            collectArpEntries(text, macs)
        }
        if (macs.isEmpty() && denied && withRoot) {
            readArpAsRoot()?.let { collectArpEntries(it, macs) }
        }
        return ArpSnapshot(macs, denied)
    }

    private fun collectArpEntries(text: String, into: MutableMap<String, String>) {
        for (entry in ArpTable.parse(text)) {
            if (entry.complete) into[entry.ip] = entry.mac
        }
    }

    private fun Throwable.isPermissionDenied(): Boolean =
        this is SecurityException ||
            message?.contains("EACCES") == true ||
            message?.contains("Permission denied", ignoreCase = true) == true

    /**
     * `su -c` runs in the root domain, where the neighbour table is readable.
     * The timeout matters because the first call may wait for the user to
     * approve the request and must not hang the scan.
     */
    private fun readArpAsRoot(): String? = runCatching {
        val process = ProcessBuilder("su", "-c", "cat /proc/net/arp")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun arpWarning(denied: Boolean, rootEnabled: Boolean): String = when {
        denied && rootEnabled ->
            "读取本机 ARP 表被拒绝（root 授权可能未生效），MAC 与厂商信息将由路由器数据补全"
        denied ->
            "Android 10+ 禁止应用读取本机 ARP 表；可登录路由器读取，或在设置中开启 root 读取"
        else ->
            "本机 ARP 表为空，MAC 与厂商信息可能缺失；登录路由器可补全"
    }

    private fun cleanHostname(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.')
        if (trimmed.isEmpty() || trimmed == "localhost") return null
        return trimmed
            .removeSuffix(".local")
            .removeSuffix(".lan")
            .removeSuffix(".localdomain")
            .replace('_', '-')
            .takeIf { it.isNotBlank() }
    }

    private suspend fun <T, R> Iterable<T>.parallelMap(
        limit: Int,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(limit)
        map { item ->
            async(Dispatchers.IO) { semaphore.withPermit { block(item) } }
        }.awaitAll()
    }
}
