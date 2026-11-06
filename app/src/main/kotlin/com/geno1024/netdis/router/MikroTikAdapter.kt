package com.geno1024.netdis.router

import com.geno1024.netdis.model.HostRecord
import com.geno1024.netdis.model.HostSource
import com.geno1024.netdis.model.LinkKind
import com.geno1024.netdis.model.RouterConfig
import com.geno1024.netdis.model.RouterType
import com.geno1024.netdis.net.ArpTable
import com.geno1024.netdis.net.Subnet
import org.json.JSONArray
import org.json.JSONObject

/**
 * MikroTik RouterOS 7's REST API. It is a clean, documented interface: basic
 * auth plus JSON arrays, so both the WAN link and the full lease/ARP tables are
 * available without screen-scraping.
 */
object MikroTikAdapter : RouterAdapter {

    override val type: RouterType = RouterType.MIKROTIK

    override suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome {
        val identity = fetch(config, "/rest/system/identity", timeoutMs)
        if (!identity.ok) {
            val message = when {
                identity.code == 401 -> "登录失败（RouterOS 用户名或密码错误）"
                identity.code == 404 -> "未找到 REST API，需要 RouterOS 7 并启用 www-ssl/www 服务"
                identity.error != null -> "无法连接：${identity.error}"
                else -> "连接失败（HTTP ${identity.code}）"
            }
            return RouterScanOutcome(success = false, type = type, message = message)
        }
        val identityJson = runCatching { JSONObject(identity.body) }.getOrNull()
        val identityName = identityJson?.optString("name")?.takeIf { it.isNotBlank() }

        val resource = fetchJsonObject(config, "/rest/system/resource", timeoutMs)
        val addresses = fetchJsonArray(config, "/rest/ip/address", timeoutMs).orEmpty()
        val routes = fetchJsonArray(config, "/rest/ip/route", timeoutMs).orEmpty()
        val leases = fetchJsonArray(config, "/rest/ip/dhcp-server/lease", timeoutMs).orEmpty()
        val arp = fetchJsonArray(config, "/rest/ip/arp", timeoutMs).orEmpty()
        val dns = fetchJsonObject(config, "/rest/ip/dns", timeoutMs)
        val wireless = fetchJsonArray(config, "/rest/interface/wifi/registration-table", timeoutMs)
            ?: fetchJsonArray(config, "/rest/interface/wireless/registration-table", timeoutMs)
            .orEmpty()

        val model = resource?.optString("board-name")?.takeIf { it.isNotBlank() }
            ?: resource?.optString("architecture-name")?.takeIf { it.isNotBlank() }
        val firmware = resource?.optString("version")?.takeIf { it.isNotBlank() }
        val uptime = resource?.optString("uptime")?.takeIf { it.isNotBlank() }

        val network = parseNetwork(addresses, routes, dns)

        val hostByMac = LinkedHashMap<String, HostRecord>()
        val hostByIp = LinkedHashMap<String, HostRecord>()

        fun add(host: HostRecord) {
            host.mac?.let { hostByMac[it] = host }
            val existing = hostByIp[host.ip]
            hostByIp[host.ip] = if (existing == null) host else existing.copy(
                mac = existing.mac ?: host.mac,
                hostname = existing.hostname ?: host.hostname,
                vendor = existing.vendor ?: host.vendor,
                openPorts = (existing.openPorts + host.openPorts).distinct(),
                services = (existing.services + host.services).distinct(),
                link = if (existing.link != LinkKind.UNKNOWN) existing.link else host.link,
                online = existing.online || host.online,
                sources = existing.sources + host.sources,
                extra = existing.extra + host.extra,
            )
        }

        for (entry in leases) {
            val ip = entry.optString("address").takeIf { it.isNotBlank() } ?: continue
            val mac = entry.optString("mac-address").takeIf { it.isNotBlank() }
                ?.let(ArpTable::normalizeMac)
            val hostname = entry.optString("host-name").takeIf { it.isNotBlank() && it != "*" }
                ?: entry.optString("comment").takeIf { it.isNotBlank() }
            val status = entry.optString("status")
            val disabled = bool(entry, "disabled")
            val online = !disabled && (status.isEmpty() || status == "bound")
            add(
                HostRecord(
                    ip = ip,
                    mac = mac,
                    hostname = hostname,
                    online = online,
                    sources = setOf(HostSource.DHCP),
                    routerId = config.id,
                    extra = buildMap { put("lease", "DHCP") },
                ),
            )
        }

        val lanInterfaces = network.lanInterfaces
        for (entry in arp) {
            val ip = entry.optString("address").takeIf { it.isNotBlank() } ?: continue
            if (ip == "0.0.0.0") continue
            val interfaceName = entry.optString("interface")
            if (lanInterfaces.isNotEmpty() && interfaceName.isNotBlank() &&
                interfaceName !in lanInterfaces && interfaceName == network.wanInterface
            ) {
                continue
            }
            val mac = entry.optString("mac-address").takeIf { it.isNotBlank() }
                ?.let(ArpTable::normalizeMac)
            val complete = !entry.has("complete") || bool(entry, "complete", default = true)
            add(
                HostRecord(
                    ip = ip,
                    mac = mac,
                    online = complete,
                    sources = setOf(HostSource.ROUTER_ARP),
                    routerId = config.id,
                ),
            )
        }

        for (entry in wireless) {
            val mac = entry.optString("mac-address").takeIf { it.isNotBlank() }
                ?.let(ArpTable::normalizeMac) ?: continue
            val existing = hostByMac[mac] ?: continue
            val extra = LinkedHashMap<String, String>()
            entry.optString("signal-strength").takeIf { it.isNotBlank() }
                ?.let { extra["signal"] = it }
            entry.optString("ssid").takeIf { it.isNotBlank() }?.let { extra["ssid"] = it }
            add(existing.copy(link = LinkKind.WIFI, extra = existing.extra + extra))
        }

        return RouterScanOutcome(
            success = true,
            type = type,
            message = identityName?.let { "已登录 $it" } ?: "已登录 RouterOS",
            model = model ?: identityName,
            firmware = firmware,
            uptime = uptime,
            wanIp = network.wanIp,
            wanGateway = network.wanGateway,
            lanCidrs = network.lanCidrs,
            dnsServers = network.dnsServers,
            clients = RouterParsing.mergeHosts(hostByIp.values.toList()),
            wirelessCount = wireless.size,
            fingerprint = listOfNotNull(model, firmware).joinToString(" · ").ifBlank { "MikroTik" },
        )
    }

    private data class NetworkSummary(
        val wanIp: String? = null,
        val wanGateway: String? = null,
        val wanInterface: String? = null,
        val lanCidrs: List<String> = emptyList(),
        val lanInterfaces: Set<String> = emptySet(),
        val dnsServers: List<String> = emptyList(),
    )

    private fun parseNetwork(
        addresses: List<JSONObject>,
        routes: List<JSONObject>,
        dns: JSONObject?,
    ): NetworkSummary {
        data class Address(val ip: String, val cidr: String, val interfaceName: String)

        val parsed = ArrayList<Address>()
        for (entry in addresses) {
            if (bool(entry, "disabled")) continue
            val raw = entry.optString("address")
            val ip = raw.substringBefore('/').takeIf { Subnet.parseIp(it) != null } ?: continue
            val prefix = raw.substringAfter('/', "32").toIntOrNull() ?: 32
            val cidr = Subnet.cidrOf(ip, prefix)?.toString() ?: continue
            val interfaceName = entry.optString("interface").ifBlank { "?" }
            parsed += Address(ip, cidr, interfaceName)
        }

        val defaultRoute = routes.firstOrNull {
            val dst = it.optString("dst-address")
            val active = !it.has("active") || bool(it, "active", default = true)
            active && (dst == "0.0.0.0/0" || dst == "0.0.0.0")
        }
        val wanGateway = defaultRoute?.optString("gateway")
            ?.substringBefore('%')
            ?.takeIf { Subnet.parseIp(it) != null }

        val wanAddress = wanGateway?.let { gateway ->
            parsed.firstOrNull { address ->
                Subnet.parseCidr(address.cidr)?.contains(gateway) == true
            }
        } ?: parsed.firstOrNull {
            it.interfaceName.startsWith("wan") || it.interfaceName.startsWith("pppoe") ||
                it.interfaceName.startsWith("lte") || it.interfaceName.startsWith("usb")
        } ?: parsed.firstOrNull { it.interfaceName.startsWith("ether1") }

        val wanInterface = wanAddress?.interfaceName
        val lan = parsed.filter {
            it.interfaceName != wanInterface && it.interfaceName != "lo" &&
                !it.interfaceName.startsWith("wan") &&
                Subnet.parseCidr(it.cidr)?.let { cidr ->
                    com.geno1024.netdis.topo.TopologyBuilder.isPrivate(Subnet.formatIp(cidr.network))
                } == true
        }
        val dnsServers = dns?.optString("servers")
            ?.split(',', ' ', ';')
            ?.map { it.trim() }
            ?.filter { Subnet.parseIp(it) != null }
            .orEmpty()

        return NetworkSummary(
            wanIp = wanAddress?.ip,
            wanGateway = wanGateway,
            wanInterface = wanInterface,
            lanCidrs = lan.map { it.cidr }.distinct(),
            lanInterfaces = lan.map { it.interfaceName }.toSet(),
            dnsServers = dnsServers,
        )
    }

    private suspend fun fetch(config: RouterConfig, path: String, timeoutMs: Int): HttpResult =
        RouterHttp.request(
            url = config.baseUrl + path,
            username = config.username,
            password = config.password,
            timeoutMs = timeoutMs,
        )

    private suspend fun fetchJsonObject(
        config: RouterConfig,
        path: String,
        timeoutMs: Int,
    ): JSONObject? {
        val result = fetch(config, path, timeoutMs)
        if (!result.ok) return null
        return runCatching { JSONObject(result.body) }.getOrNull()
    }

    private suspend fun fetchJsonArray(
        config: RouterConfig,
        path: String,
        timeoutMs: Int,
    ): List<JSONObject>? {
        val result = fetch(config, path, timeoutMs)
        if (!result.ok) return null
        val array = runCatching { JSONArray(result.body) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun bool(obj: JSONObject, key: String, default: Boolean = false): Boolean {
        if (!obj.has(key)) return default
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) ||
                value.equals("yes", ignoreCase = true) || value == "1"

            is Number -> value.toInt() != 0
            else -> default
        }
    }
}
