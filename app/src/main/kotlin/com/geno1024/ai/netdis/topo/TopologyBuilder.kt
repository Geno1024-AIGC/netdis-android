package com.geno1024.ai.netdis.topo

import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.LocalNetwork
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.TopologyEdge
import com.geno1024.ai.netdis.model.TopologyGraph
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.net.Subnet

/**
 * Builds the multi-level graph from three sources: what the phone saw directly,
 * what each router reported about its own LAN, and the WAN addresses that link
 * one router to the next.
 */
object TopologyBuilder {

    fun build(
        local: LocalNetwork?,
        hosts: List<HostRecord>,
        routers: List<RouterSnapshot>,
        configs: List<RouterConfig> = emptyList(),
    ): TopologyGraph {
        val gatewayHost = local?.gateway
        val configById = configs.associateBy { it.id }

        val visible = LinkedHashMap<String, RouterSnapshot>()
        for (snapshot in routers) {
            val isGateway = gatewayHost != null &&
                (snapshot.host == gatewayHost || snapshot.wanGateway == gatewayHost)
            if (!snapshot.reachable && !isGateway) continue
            val id = routerId(snapshot)
            visible[id] = snapshot
        }

        // Always show the gateway, even if no credentials are configured: the
        // topology would otherwise start in mid-air.
        if (gatewayHost != null && visible.values.none { it.host == gatewayHost }) {
            visible[routerId(null, gatewayHost)] = RouterSnapshot(
                configId = null,
                label = "主路由",
                host = gatewayHost,
                reachable = false,
                message = "未登录路由器",
                lanCidrs = listOfNotNull(local?.cidr),
            )
        }

        if (visible.isEmpty()) {
            val fallbackHost = gatewayHost ?: local?.localIp
            if (fallbackHost != null) {
                visible[routerId(null, fallbackHost)] = RouterSnapshot(
                    configId = null,
                    label = "本机网络",
                    host = fallbackHost,
                    reachable = false,
                    message = "未发现路由器信息",
                    lanCidrs = listOfNotNull(local?.cidr),
                )
            }
        }

        val routerIds = visible.keys.toList()
        val parentOf = resolveParents(visible)
        val depthOf = resolveDepths(parentOf)
        val configOf: (RouterSnapshot) -> RouterConfig? = { snapshot ->
            snapshot.configId?.let(configById::get)
        }

        val routerNodes = visible.map { (id, snapshot) ->
            TopologyNode(
                id = id,
                label = snapshot.label.ifBlank { "路由器" },
                subtitle = buildString {
                    append(snapshot.host)
                    val detail = snapshot.model ?: snapshot.fingerprint ?: snapshot.message
                    if (!detail.isNullOrBlank()) {
                        append(" · ")
                        append(detail)
                    }
                },
                kind = NodeKind.ROUTER,
                deviceKind = DeviceKind.ROUTER,
                routerId = snapshot.configId,
                ip = snapshot.host,
                online = snapshot.reachable,
                depth = depthOf[id] ?: 1,
                parentId = parentOf[id],
                router = configOf(snapshot),
                snapshot = snapshot,
            )
        }

        val routerAddresses = visible.values.flatMap { snapshot ->
            listOfNotNull(snapshot.host, snapshot.wanIp)
        }.toSet()

        // Merge the phone's view with each router's client table. A device that
        // both saw is one device, not two, so evidence is unioned field by field.
        val merged = LinkedHashMap<String, HostRecord>()
        for (host in hosts) {
            mergeInto(merged, host, host.routerId)
        }
        for ((id, snapshot) in visible) {
            val owner = snapshot.configId ?: id
            for (client in snapshot.clients) {
                mergeInto(merged, client, owner)
            }
        }

        val defaultRouterId = visible.entries
            .firstOrNull { it.value.host == gatewayHost }?.key
            ?: routerIds.firstOrNull()

        val devices = ArrayList<HostRecord>()
        for (host in merged.values) {
            if (host.ip in routerAddresses) continue
            if (host.openPorts.isEmpty() && host.hostname == null && host.sources.isEmpty()) continue
            val owner = resolveOwner(host, visible, defaultRouterId)
            devices += host.copy(
                routerId = owner,
                kind = if (host.kind == DeviceKind.UNKNOWN) {
                    com.geno1024.ai.netdis.net.DeviceClassifier.classify(host)
                } else {
                    host.kind
                },
            )
        }

        val deviceNodes = devices.map { host ->
            val ownerId = host.routerId
            TopologyNode(
                id = deviceId(ownerId, host.ip),
                label = host.displayName,
                subtitle = host.ip,
                kind = NodeKind.DEVICE,
                deviceKind = host.kind,
                routerId = ownerId,
                ip = host.ip,
                online = host.online,
                depth = ((depthOf[ownerId] ?: 1) + 1),
                parentId = ownerId,
                host = host,
            )
        }

        val internet = TopologyNode(
            id = INTERNET_ID,
            label = "互联网",
            subtitle = visible.values.firstOrNull { parentOf[routerId(it)] == null }
                ?.externalIp ?: "公网出口",
            kind = NodeKind.INTERNET,
            deviceKind = DeviceKind.UNKNOWN,
            depth = 0,
            online = true,
        )

        val routerEdges = visible.keys.map { id ->
            val parent = parentOf[id] ?: INTERNET_ID
            TopologyEdge(
                from = parent,
                to = id,
                link = LinkKind.ETHERNET,
                label = visible[id]?.wanIp?.takeIf { it.isNotBlank() },
            )
        }
        val deviceEdges = deviceNodes.map { node ->
            TopologyEdge(
                from = node.parentId ?: INTERNET_ID,
                to = node.id,
                link = node.host?.link ?: LinkKind.UNKNOWN,
            )
        }

        val counts = deviceNodes.groupingBy { it.parentId }.eachCount()
        val finalRouters = routerNodes.map { it.copy(clientCount = counts[it.id] ?: 0) }

        return TopologyGraph(
            nodes = listOf(internet) + finalRouters + deviceNodes,
            edges = routerEdges + deviceEdges,
        )
    }

    const val INTERNET_ID = "internet"

    private fun routerId(snapshot: RouterSnapshot): String =
        routerId(snapshot.configId, snapshot.host)

    private fun routerId(configId: String?, host: String): String =
        if (configId != null) "router:$configId" else "router:auto:$host"

    /** Key devices by IP only: routers sometimes report the same client twice. */
    private fun mergeInto(map: LinkedHashMap<String, HostRecord>, host: HostRecord, owner: String?) {
        val key = host.ip
        val existing = map[key]
        if (existing == null) {
            map[key] = host.copy(routerId = owner ?: host.routerId)
            return
        }
        map[key] = existing.copy(
            mac = existing.mac ?: host.mac,
            hostname = existing.hostname ?: host.hostname,
            vendor = existing.vendor ?: host.vendor,
            openPorts = (existing.openPorts + host.openPorts).distinct().sorted(),
            services = (existing.services + host.services).distinct(),
            kind = if (existing.kind != DeviceKind.UNKNOWN) existing.kind else host.kind,
            link = if (existing.link != LinkKind.UNKNOWN) existing.link else host.link,
            online = existing.online || host.online,
            sources = existing.sources + host.sources,
            routerId = existing.routerId ?: host.routerId ?: owner,
            extra = existing.extra + host.extra,
        )
    }

    private fun resolveOwner(
        host: HostRecord,
        visible: Map<String, RouterSnapshot>,
        fallback: String?,
    ): String? {
        host.routerId?.let { configured ->
            val id = if (configured.startsWith("router:")) configured else "router:$configured"
            if (visible.containsKey(id)) return id
        }
        val address = Subnet.parseIp(host.ip)
        if (address != null) {
            var best: Pair<String, Int>? = null
            for ((id, snapshot) in visible) {
                for (cidrText in snapshot.lanCidrs) {
                    val cidr = Subnet.parseCidr(cidrText) ?: continue
                    if (!cidr.contains(address)) continue
                    if (best == null || cidr.prefix > best.second) best = id to cidr.prefix
                }
            }
            if (best != null) return best.first
        }
        return fallback
    }

    private fun resolveParents(
        visible: Map<String, RouterSnapshot>,
    ): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        for ((id, snapshot) in visible) {
            val manual = snapshot.parentId?.let { candidate ->
                if (visible.containsKey("router:$candidate")) "router:$candidate" else null
            }
            if (manual != null && manual != id) {
                result[id] = manual
                continue
            }
            val candidates = visible.filterKeys { it != id }
            val byGateway = snapshot.wanGateway?.let { gateway ->
                candidates.entries.firstOrNull { it.value.host == gateway }?.key
                    ?: bestSubnetMatch(candidates, gateway)
            }
            val byWan = snapshot.wanIp
                ?.takeIf { isPrivate(it) }
                ?.let { wan -> bestSubnetMatch(candidates, wan) }
            result[id] = byGateway ?: byWan
        }
        // Break cycles by pointing the offending router at the internet.
        for (id in result.keys) {
            var cursor: String? = result[id]
            var hops = 0
            while (cursor != null && hops < 16) {
                if (cursor == id) {
                    result[id] = null
                    break
                }
                cursor = result[cursor]
                hops++
            }
        }
        return result
    }

    private fun bestSubnetMatch(
        candidates: Map<String, RouterSnapshot>,
        address: String,
    ): String? {
        val value = Subnet.parseIp(address) ?: return null
        var best: Pair<String, Int>? = null
        for ((id, snapshot) in candidates) {
            for (text in snapshot.lanCidrs) {
                val cidr = Subnet.parseCidr(text) ?: continue
                if (!cidr.contains(value)) continue
                if (best == null || cidr.prefix > best.second) best = id to cidr.prefix
            }
        }
        return best?.first
    }

    private fun resolveDepths(parents: Map<String, String?>): Map<String, Int> {
        val memo = HashMap<String, Int>()
        fun depth(id: String, seen: MutableSet<String>): Int {
            memo[id]?.let { return it }
            if (!seen.add(id)) return 1
            val parent = parents[id]
            val value = if (parent == null) 1 else depth(parent, seen) + 1
            memo[id] = value
            return value
        }
        return parents.keys.associateWith { depth(it, HashSet()) }
    }

    fun isPrivate(ip: String): Boolean {
        val value = Subnet.parseIp(ip) ?: return false
        return Subnet.cidrOf("10.0.0.0", 8)!!.contains(value) ||
            Subnet.cidrOf("172.16.0.0", 12)!!.contains(value) ||
            Subnet.cidrOf("192.168.0.0", 16)!!.contains(value) ||
            Subnet.cidrOf("169.254.0.0", 16)!!.contains(value) ||
            Subnet.cidrOf("100.64.0.0", 10)!!.contains(value)
    }

    fun deviceId(routerId: String?, ip: String): String = "device:${routerId ?: "?"}:$ip"
}
