package com.geno1024.ai.netdis.storage

import android.content.Context
import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.LocalNetwork
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.model.ScanSnapshot
import com.geno1024.ai.netdis.topo.TopologyBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the last scan on disk so the app opens on the previous picture while a
 * new scan runs (or when the user is away from their network). The topology is
 * rebuilt from the hosts and routers rather than stored, which keeps the file
 * small and guarantees it matches the current builder.
 */
object SnapshotJson {

    fun encode(snapshot: ScanSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("startedAt", snapshot.startedAt)
        put("finishedAt", snapshot.finishedAt)
        put("local", snapshot.local?.let(::localObject))
        put("hosts", JSONArray().apply { snapshot.hosts.forEach { put(hostObject(it)) } })
        put("routers", JSONArray().apply { snapshot.routers.forEach { put(routerObject(it)) } })
        put("warnings", JSONArray().apply { snapshot.warnings.forEach(::put) })
    }.toString()

    fun decode(text: String): ScanSnapshot? {
        if (text.isBlank()) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("version", 0) != 1) return null
        val startedAt = root.optLong("startedAt", 0)
        val finishedAt = root.optLong("finishedAt", 0)
        if (startedAt <= 0) return null
        val local = root.optJSONObject("local")?.let(::localFrom)
        val hosts = root.optJSONArray("hosts").objects().mapNotNull(::hostFrom)
        val routers = root.optJSONArray("routers").objects().mapNotNull(::routerFrom)
        val warnings = root.optJSONArray("warnings")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return ScanSnapshot(
            startedAt = startedAt,
            finishedAt = finishedAt,
            local = local,
            hosts = hosts,
            routers = routers,
            graph = TopologyBuilder.build(local, hosts, routers),
            warnings = warnings,
        )
    }

    private fun localObject(local: LocalNetwork): JSONObject = JSONObject().apply {
        put("interfaceName", local.interfaceName)
        put("localIp", local.localIp)
        put("prefixLength", local.prefixLength)
        put("gateway", local.gateway)
        put("dnsServers", JSONArray(local.dnsServers))
        put("cidr", local.cidr)
        put("hostCount", local.hostCount)
        put("transport", local.transport)
    }

    private fun localFrom(json: JSONObject): LocalNetwork? {
        val localIp = json.optString("localIp").takeIf { it.isNotBlank() } ?: return null
        val cidr = json.optString("cidr").takeIf { it.isNotBlank() } ?: return null
        return LocalNetwork(
            interfaceName = json.optString("interfaceName"),
            localIp = localIp,
            prefixLength = json.optInt("prefixLength", 24),
            gateway = json.optString("gateway").takeIf { it.isNotBlank() },
            dnsServers = json.optJSONArray("dnsServers").toStrings(),
            cidr = cidr,
            hostCount = json.optInt("hostCount", 0),
            transport = json.optString("transport"),
        )
    }

    private fun hostObject(host: HostRecord): JSONObject = JSONObject().apply {
        put("ip", host.ip)
        put("mac", host.mac)
        put("hostname", host.hostname)
        put("vendor", host.vendor)
        put("openPorts", JSONArray(host.openPorts))
        put("services", JSONArray(host.services))
        put("kind", host.kind.name)
        put("link", host.link.name)
        put("online", host.online)
        put("sources", JSONArray(host.sources.map { it.name }))
        put("routerId", host.routerId)
        put("extra", JSONObject(host.extra))
    }

    private fun hostFrom(json: JSONObject): HostRecord? {
        val ip = json.optString("ip").takeIf { it.isNotBlank() } ?: return null
        return HostRecord(
            ip = ip,
            mac = json.optString("mac").takeIf { it.isNotBlank() },
            hostname = json.optString("hostname").takeIf { it.isNotBlank() },
            vendor = json.optString("vendor").takeIf { it.isNotBlank() },
            openPorts = json.optJSONArray("openPorts").toStrings()
                .mapNotNull(String::toIntOrNull)
                .filter { it in 1..65535 },
            services = json.optJSONArray("services").toStrings(),
            kind = DeviceKind.entries.firstOrNull { it.name == json.optString("kind") }
                ?: DeviceKind.UNKNOWN,
            link = LinkKind.entries.firstOrNull { it.name == json.optString("link") }
                ?: LinkKind.UNKNOWN,
            online = json.optBoolean("online", true),
            sources = json.optJSONArray("sources").toStrings()
                .mapNotNull { name -> HostSource.entries.firstOrNull { it.name == name } }
                .toSet(),
            routerId = json.optString("routerId").takeIf { it.isNotBlank() },
            extra = json.optJSONObject("extra")?.let { objectMap ->
                objectMap.keys().asSequence().associateWith { objectMap.optString(it) }
            }.orEmpty(),
        )
    }

    private fun routerObject(snapshot: RouterSnapshot): JSONObject = JSONObject().apply {
        put("configId", snapshot.configId)
        put("label", snapshot.label)
        put("host", snapshot.host)
        put("reachable", snapshot.reachable)
        put("message", snapshot.message)
        put("actualType", snapshot.actualType?.name)
        put("model", snapshot.model)
        put("firmware", snapshot.firmware)
        put("uptime", snapshot.uptime)
        put("wanIp", snapshot.wanIp)
        put("wanGateway", snapshot.wanGateway)
        put("lanCidrs", JSONArray(snapshot.lanCidrs))
        put("dnsServers", JSONArray(snapshot.dnsServers))
        put("clients", JSONArray().apply { snapshot.clients.forEach { put(hostObject(it)) } })
        put("wirelessCount", snapshot.wirelessCount)
        put("parentId", snapshot.parentId)
        put("externalIp", snapshot.externalIp)
        put("fingerprint", snapshot.fingerprint)
    }

    private fun routerFrom(json: JSONObject): RouterSnapshot? {
        val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
        return RouterSnapshot(
            configId = json.optString("configId").takeIf { it.isNotBlank() },
            label = json.optString("label"),
            host = host,
            reachable = json.optBoolean("reachable", false),
            message = json.optString("message").takeIf { it.isNotBlank() },
            actualType = RouterType.entries.firstOrNull { it.name == json.optString("actualType") },
            model = json.optString("model").takeIf { it.isNotBlank() },
            firmware = json.optString("firmware").takeIf { it.isNotBlank() },
            uptime = json.optString("uptime").takeIf { it.isNotBlank() },
            wanIp = json.optString("wanIp").takeIf { it.isNotBlank() },
            wanGateway = json.optString("wanGateway").takeIf { it.isNotBlank() },
            lanCidrs = json.optJSONArray("lanCidrs").toStrings(),
            dnsServers = json.optJSONArray("dnsServers").toStrings(),
            clients = json.optJSONArray("clients").objects().mapNotNull(::hostFrom),
            wirelessCount = json.optInt("wirelessCount", 0),
            parentId = json.optString("parentId").takeIf { it.isNotBlank() },
            externalIp = json.optString("externalIp").takeIf { it.isNotBlank() },
            fingerprint = json.optString("fingerprint").takeIf { it.isNotBlank() },
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        val result = ArrayList<JSONObject>(length())
        for (index in 0 until length()) {
            optJSONObject(index)?.let(result::add)
        }
        return result
    }

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        val result = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) result += value
        }
        return result
    }
}

class SnapshotStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ScanSnapshot? = SnapshotJson.decode(prefs.getString(KEY, null).orEmpty())

    fun save(snapshot: ScanSnapshot) {
        prefs.edit().putString(KEY, SnapshotJson.encode(snapshot)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "netdis.snapshot"
        const val KEY = "last.v1"
    }
}
