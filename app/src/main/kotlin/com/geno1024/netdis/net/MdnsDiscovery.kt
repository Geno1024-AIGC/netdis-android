package com.geno1024.netdis.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.math.min

data class MdnsService(
    val instance: String,
    val serviceType: String,
    val host: String? = null,
    val port: Int? = null,
    val addresses: List<String> = emptyList(),
    val txt: Map<String, String> = emptyMap(),
) {
    val friendlyType: String get() = friendlyServiceName(serviceType)

    /** The instance label, which is usually the user-visible device name. */
    val displayName: String
        get() = txt["fn"]?.takeIf { it.isNotBlank() }
            ?: txt["model"]?.takeIf { it.isNotBlank() }
            ?: instance.substringBefore("._").ifBlank { instance }

    companion object {
        fun friendlyServiceName(type: String): String = when {
            type.startsWith("_googlecast") -> "Chromecast"
            type.startsWith("_airplay") -> "AirPlay"
            type.startsWith("_raop") -> "AirPlay 音频"
            type.startsWith("_ipp") || type.startsWith("_printer") -> "打印机"
            type.startsWith("_smb") -> "文件共享"
            type.startsWith("_ssh") -> "SSH"
            type.startsWith("_workstation") -> "工作站"
            type.startsWith("_device-info") -> "设备信息"
            type.startsWith("_hap") -> "HomeKit"
            type.startsWith("_matter") -> "Matter"
            type.startsWith("_sonos") -> "Sonos"
            type.startsWith("_spotify") -> "Spotify"
            type.startsWith("_home-assistant") -> "Home Assistant"
            type.startsWith("_esphomelib") -> "ESPHome"
            type.startsWith("_http") -> "HTTP"
            type.startsWith("_https") -> "HTTPS"
            type.startsWith("_companion-link") -> "Apple 设备"
            type.startsWith("_sleep-proxy") -> "Apple 设备"
            else -> type.removePrefix("_").substringBefore("._").replace('-', ' ')
        }
    }
}

/**
 * Raw multicast DNS discovery. `NsdManager` can only watch one service type at
 * a time and gives no addresses, so the packets are read directly.
 */
object MdnsDiscovery {

    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353

    private val QUERY_TYPES = listOf(
        "_services._dns-sd._udp.local",
        "_http._tcp.local",
        "_https._tcp.local",
        "_googlecast._tcp.local",
        "_airplay._tcp.local",
        "_raop._tcp.local",
        "_ipps._tcp.local",
        "_ipp._tcp.local",
        "_printer._tcp.local",
        "_smb._tcp.local",
        "_ssh._tcp.local",
        "_sftp-ssh._tcp.local",
        "_workstation._tcp.local",
        "_device-info._tcp.local",
        "_hap._tcp.local",
        "_matter._tcp.local",
        "_sonos._tcp.local",
        "_spotify-connect._tcp.local",
        "_home-assistant._tcp.local",
        "_esphomelib._tcp.local",
        "_companion-link._tcp.local",
    )

    suspend fun discover(context: Context, timeoutMs: Long = 3200): List<MdnsService> =
        withContext(Dispatchers.IO) {
            val lock = runCatching {
                context.getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("netdis-mdns")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
            try {
                collect(timeoutMs)
            } finally {
                runCatching { lock?.release() }
            }
        }

    private fun collect(timeoutMs: Long): List<MdnsService> {
        val socket = openSocket() ?: return emptyList()
        socket.use {
            val group = InetAddress.getByName(GROUP)
            joinGroups(socket, group)
            val query = DnsPacket.buildQuery(
                questions = QUERY_TYPES.map { it to DnsPacket.TYPE_PTR },
                id = 0,
                unicastResponse = true,
            )
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val records = ArrayList<DnsPacket.Record>()
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                if (lastSend == 0L || System.currentTimeMillis() - lastSend > 900) {
                    runCatching {
                        socket.send(DatagramPacket(query, query.size, group, PORT))
                    }
                    lastSend = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.soTimeout = min(350L, (deadline - System.currentTimeMillis()).coerceAtLeast(50L)).toInt()
                    socket.receive(packet)
                } catch (_: Exception) {
                    continue
                }
                val message = DnsPacket.parse(buffer, packet.length) ?: continue
                records += message.records
            }
            return aggregate(records)
        }
    }

    private fun openSocket(): MulticastSocket? {
        val shared = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                timeToLive = 255
            }
        }.getOrNull()
        if (shared != null) return shared

        // Another app may own 5353. The QU bit asks responders to unicast, so
        // an ephemeral port still hears them.
        return runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                timeToLive = 255
            }
        }.getOrNull()
    }

    private fun joinGroups(socket: MulticastSocket, group: InetAddress) {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            if (!runCatching { iface.supportsMulticast() }.getOrDefault(false)) continue
            runCatching {
                socket.joinGroup(InetSocketAddress(group, PORT), iface)
            }
        }
    }

    private fun aggregate(records: List<DnsPacket.Record>): List<MdnsService> {
        fun key(name: String) = name.trimEnd('.').lowercase()

        val ptrByInstance = LinkedHashMap<String, Pair<String, String>>() // instance -> (type, display)
        for (record in records) {
            if (record.type != DnsPacket.TYPE_PTR) continue
            val type = key(record.name)
            if (!type.startsWith("_") || type.startsWith("_services._dns-sd")) continue
            val target = (record.data as? DnsPacket.RData.Target)?.name ?: continue
            ptrByInstance.putIfAbsent(key(target), type to target.trimEnd('.'))
        }
        if (ptrByInstance.isEmpty()) return emptyList()

        val srv = HashMap<String, DnsPacket.RData.Service>()
        val txt = HashMap<String, Map<String, String>>()
        val addresses = HashMap<String, MutableList<String>>()
        for (record in records) {
            val name = key(record.name)
            when (val data = record.data) {
                is DnsPacket.RData.Service -> srv[name] = data
                is DnsPacket.RData.Text -> txt[name] = data.values.associate { entry ->
                    val eq = entry.indexOf('=')
                    if (eq < 0) entry to "" else entry.substring(0, eq) to entry.substring(eq + 1)
                }

                is DnsPacket.RData.Address -> if ('.' in data.ip) {
                    addresses.getOrPut(name) { ArrayList() } += data.ip
                }

                else -> Unit
            }
        }

        return ptrByInstance.map { (instanceKey, typeAndName) ->
            val (type, instance) = typeAndName
            val service = srv[instanceKey]
            val hostKey = service?.target?.let(::key)
            val serviceTxt = txt[instanceKey].orEmpty()
            MdnsService(
                instance = instance,
                serviceType = type,
                host = service?.target?.trimEnd('.'),
                port = service?.port,
                addresses = hostKey?.let { addresses[it]?.distinct() }.orEmpty(),
                txt = serviceTxt,
            )
        }.distinctBy { it.instance.lowercase() to it.serviceType }
    }
}
