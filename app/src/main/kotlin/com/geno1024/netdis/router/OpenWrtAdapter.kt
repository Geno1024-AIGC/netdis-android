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
 * OpenWrt (and any LuCI install) over the ubus JSON-RPC endpoint. LuCI's
 * `luci-rpc` helpers expose the same host hints, DHCP leases and wireless
 * associations its own status pages use.
 */
object OpenWrtAdapter : RouterAdapter {

    override val type: RouterType = RouterType.OPENWRT

    private val ENDPOINTS = listOf("/ubus/", "/ubus", "/cgi-bin/luci/admin/ubus")

    private data class Session(val endpoint: String, val token: String)

    override suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome {
        val session = login(config, timeoutMs)
            ?: return RouterScanOutcome(
                success = false,
                type = type,
                message = "无法登录 OpenWrt（检查地址、用户名和密码）",
            )

        val board = call(config, session, "system", "board", timeoutMs)
        val info = call(config, session, "system", "info", timeoutMs)
        val interfaces = call(config, session, "network.interface", "dump", timeoutMs)
        val hints = call(config, session, "luci-rpc", "getHostHints", timeoutMs)
        val leases = call(config, session, "luci-rpc", "getDHCPLeases", timeoutMs)
        val wireless = call(config, session, "luci-rpc", "getWirelessDevices", timeoutMs)

        val release = board?.optJSONObject("release")
        val model = board?.optString("model")?.takeIf { it.isNotBlank() }
            ?: board?.optString("board_name")?.takeIf { it.isNotBlank() }
        val firmware = release?.optString("description")?.takeIf { it.isNotBlank() }
            ?: release?.optString("version")?.takeIf { it.isNotBlank() }
        val hostname = board?.optString("hostname")?.takeIf { it.isNotBlank() }
        val uptime = info?.optLong("uptime", -1)?.takeIf { it > 0 }
            ?.let(RouterParsing::formatUptime)

        val network = parseInterfaces(interfaces)
        val hosts = LinkedHashMap<String, HostRecord>()

        fun addClient(host: HostRecord) {
            val existing = hosts[host.ip]
            hosts[host.ip] = if (existing == null) host else existing.copy(
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

        parseHostHints(hints, config.id).forEach(::addClient)
        parseLeases(leases, config.id).forEach(::addClient)

        val wirelessMacs = parseWireless(wireless)
        if (wirelessMacs.isNotEmpty()) {
            for ((ip, host) in hosts.entries.toList()) {
                val mac = host.mac ?: continue
                val association = wirelessMacs[mac] ?: continue
                hosts[ip] = host.copy(
                    link = LinkKind.WIFI,
                    extra = host.extra + association,
                )
            }
        }

        return RouterScanOutcome(
            success = true,
            type = type,
            message = hostname?.let { "已登录 $it" } ?: "已登录 OpenWrt",
            model = model,
            firmware = firmware,
            uptime = uptime,
            wanIp = network.wanIp,
            wanGateway = network.wanGateway,
            lanCidrs = network.lanCidrs,
            dnsServers = network.dnsServers,
            clients = RouterParsing.mergeHosts(hosts.values.toList()),
            wirelessCount = wirelessMacs.size,
            fingerprint = listOfNotNull(model, firmware).joinToString(" · ").ifBlank { "OpenWrt" },
        )
    }

    private suspend fun login(config: RouterConfig, timeoutMs: Int): Session? {
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "call")
            put(
                "params",
                JSONArray().apply {
                    put("00000000000000000000000000000000")
                    put("session")
                    put("login")
                    put(
                        JSONObject().apply {
                            put("username", config.username)
                            put("password", config.password)
                        },
                    )
                },
            )
        }.toString()

        for (endpoint in ENDPOINTS) {
            val result = RouterHttp.request(
                url = config.baseUrl + endpoint,
                method = "POST",
                body = request,
                timeoutMs = timeoutMs,
            )
            if (result.error != null) continue
            if (result.code !in 200..299) continue
            val root = runCatching { JSONObject(result.body) }.getOrNull() ?: continue
            val array = root.optJSONArray("result") ?: continue
            if (array.optInt(0, -1) != 0) continue
            val data = array.optJSONObject(1) ?: continue
            val token = data.optString("ubus_rpc_session").takeIf { it.isNotBlank() } ?: continue
            return Session(endpoint, token)
        }
        return null
    }

    private suspend fun call(
        config: RouterConfig,
        session: Session,
        obj: String,
        method: String,
        timeoutMs: Int,
        args: JSONObject = JSONObject(),
    ): JSONObject? {
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "call")
            put(
                "params",
                JSONArray().apply {
                    put(session.token)
                    put(obj)
                    put(method)
                    put(args)
                },
            )
        }.toString()
        val result = RouterHttp.request(
            url = config.baseUrl + session.endpoint,
            method = "POST",
            body = request,
            timeoutMs = timeoutMs,
        )
        if (!result.ok) return null
        val root = runCatching { JSONObject(result.body) }.getOrNull() ?: return null
        val array = root.optJSONArray("result") ?: return null
        if (array.optInt(0, -1) != 0) return null
        return array.optJSONObject(1)
    }

    private data class NetworkSummary(
        val wanIp: String? = null,
        val wanGateway: String? = null,
        val lanCidrs: List<String> = emptyList(),
        val dnsServers: List<String> = emptyList(),
    )

    private fun parseInterfaces(payload: JSONObject?): NetworkSummary {
        val array = payload?.optJSONArray("interface") ?: return NetworkSummary()
        val entries = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let(entries::add)
        }
        var wanIp: String? = null
        var wanGateway: String? = null
        val lan = ArrayList<String>()
        val dns = ArrayList<String>()
        for (entry in entries) {
            val name = entry.optString("interface")
            val up = entry.optBoolean("up", true)
            if (!up) continue
            val addresses = entry.optJSONArray("ipv4-address")
            val cidrs = ArrayList<String>()
            val ips = ArrayList<String>()
            if (addresses != null) {
                for (i in 0 until addresses.length()) {
                    val address = addresses.optJSONObject(i) ?: continue
                    val ip = address.optString("address").takeIf { it.isNotBlank() } ?: continue
                    ips += ip
                    RouterParsing.cidrFrom(ip, address.optString("mask").takeIf { it.isNotBlank() })
                        ?.let(cidrs::add)
                }
            }
            val routes = entry.optJSONArray("route")
            var defaultGateway: String? = null
            if (routes != null) {
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    val target = route.optString("target")
                    val mask = route.optString("mask")
                    val isDefault = target in setOf("0.0.0.0", "0.0.0.0/0") ||
                        (target == "0.0.0.0" && mask in setOf("0.0.0.0", "0"))
                    if (isDefault) {
                        defaultGateway = route.optString("nexthop").takeIf { it.isNotBlank() }
                    }
                }
            }
            val dnsArray = entry.optJSONArray("dns-server")
            if (dnsArray != null) {
                for (i in 0 until dnsArray.length()) {
                    dnsArray.optString(i).takeIf { it.isNotBlank() }?.let(dns::add)
                }
            }

            val isWan = name.startsWith("wan") || name.startsWith("wwan") ||
                name.startsWith("pppoe") || name.startsWith("lte") || name.startsWith("usb")
            if (isWan) {
                if (wanIp == null) wanIp = ips.firstOrNull()
                if (wanGateway == null) wanGateway = defaultGateway
            } else if (defaultGateway != null && wanIp == null) {
                wanIp = ips.firstOrNull()
                wanGateway = defaultGateway
            } else {
                for (cidr in cidrs) {
                    val parsed = Subnet.parseCidr(cidr) ?: continue
                    if (parsed.prefix == 32) continue
                    if (!lan.contains(cidr)) lan += cidr
                }
            }
        }
        return NetworkSummary(
            wanIp = wanIp,
            wanGateway = wanGateway,
            lanCidrs = lan,
            dnsServers = dns.distinct(),
        )
    }

    private fun parseHostHints(payload: JSONObject?, routerId: String): List<HostRecord> {
        if (payload == null) return emptyList()
        val result = ArrayList<HostRecord>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val mac = ArpTable.normalizeMac(rawKey) ?: continue
            val value = payload.optJSONObject(rawKey) ?: continue
            val ipv4 = value.optJSONArray("ipv4")
            val ip = ipv4?.optString(0)?.takeIf { it.isNotBlank() } ?: continue
            val name = value.optString("name").takeIf { it.isNotBlank() && it != "*" }
            val model = value.optString("model").takeIf { it.isNotBlank() }
            result += HostRecord(
                ip = ip,
                mac = mac,
                hostname = name,
                sources = setOf(HostSource.ROUTER_ARP),
                routerId = routerId,
                extra = buildMap {
                    model?.let { put("model", it) }
                },
            )
        }
        return result
    }

    private fun parseLeases(payload: JSONObject?, routerId: String): List<HostRecord> {
        if (payload == null) return emptyList()
        val arrays = listOf("leases", "dhcp_leases", "dhcpLeases", "dhcp6_leases")
        val result = ArrayList<HostRecord>()
        for (key in arrays) {
            val array = payload.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val lease = array.optJSONObject(i) ?: continue
                val ip = sequenceOf("ip", "ipaddr", "address")
                    .map { lease.optString(it) }
                    .firstOrNull { it.isNotBlank() } ?: continue
                val mac = sequenceOf("mac", "macaddr", "mac-address")
                    .map { lease.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                    ?.let(ArpTable::normalizeMac)
                val hostname = sequenceOf("hostname", "name")
                    .map { lease.optString(it) }
                    .firstOrNull { it.isNotBlank() && it != "*" }
                val valid = lease.opt("valid")
                val online = when (valid) {
                    is Boolean -> valid
                    is Number -> valid.toLong() > 0
                    else -> lease.optString("status").let { it.isBlank() || it == "1" }
                }
                result += HostRecord(
                    ip = ip,
                    mac = mac,
                    hostname = hostname,
                    online = online,
                    sources = setOf(HostSource.DHCP),
                    routerId = routerId,
                    extra = buildMap { put("lease", "DHCP") },
                )
            }
        }
        return result
    }

    /** Finds association objects anywhere in the wireless dump and records their signal. */
    private fun parseWireless(payload: JSONObject?): Map<String, Map<String, String>> {
        if (payload == null) return emptyMap()
        val result = LinkedHashMap<String, Map<String, String>>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val mac = sequenceOf("mac", "macaddr", "station", "address")
                        .map { value.optString(it) }
                        .firstOrNull { it.isNotBlank() }
                        ?.let(ArpTable::normalizeMac)
                    val signal = sequenceOf("signal", "signalStrength", "rssi", "noise")
                        .map { value.optString(it) }
                        .firstOrNull { it.isNotBlank() }
                    if (mac != null && signal != null) {
                        val extra = LinkedHashMap<String, String>()
                        extra["signal"] = signal
                        value.optString("ssid").takeIf { it.isNotBlank() }?.let { extra["ssid"] = it }
                        value.optString("mode").takeIf { it.isNotBlank() }?.let { extra["wifiMode"] = it }
                        result[mac] = extra
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }

                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                else -> Unit
            }
        }
        walk(payload)
        return result
    }
}
