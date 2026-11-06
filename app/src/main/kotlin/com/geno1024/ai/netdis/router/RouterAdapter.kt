package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType

data class RouterScanOutcome(
    val success: Boolean,
    val type: RouterType,
    val message: String = "",
    val model: String? = null,
    val firmware: String? = null,
    val uptime: String? = null,
    val wanIp: String? = null,
    val wanGateway: String? = null,
    val externalIp: String? = null,
    val lanCidrs: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val clients: List<HostRecord> = emptyList(),
    val wirelessCount: Int = 0,
    val fingerprint: String? = null,
)

interface RouterAdapter {
    val type: RouterType

    suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome
}

object RouterParsing {

    fun prefixFromNetmask(mask: String): Int? {
        val value = com.geno1024.ai.netdis.net.Subnet.parseIp(mask) ?: return null
        var prefix = 0
        var seenZero = false
        for (bit in 31 downTo 0) {
            val set = (value shr bit) and 1L == 1L
            if (set && seenZero) return null
            if (set) prefix++ else seenZero = true
        }
        return prefix
    }

    fun cidrFrom(address: String, maskOrPrefix: String?): String? {
        val ip = com.geno1024.ai.netdis.net.Subnet.parseIp(address) ?: return null
        val prefix = when {
            maskOrPrefix == null -> 32
            maskOrPrefix.contains('.') -> prefixFromNetmask(maskOrPrefix) ?: return null
            else -> maskOrPrefix.toIntOrNull() ?: return null
        }
        if (prefix !in 0..32) return null
        return com.geno1024.ai.netdis.net.Subnet.cidrOf(ip, prefix).toString()
    }

    fun formatUptime(totalSeconds: Long): String {
        if (totalSeconds <= 0) return ""
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        return buildString {
            if (days > 0) append("${days}天")
            if (days > 0 || hours > 0) append("${hours}小时")
            if (days == 0L && (hours == 0L || minutes > 0)) append("${minutes}分钟")
        }
    }

    /** Keeps the first host per IP and folds the evidence of later duplicates in. */
    fun mergeHosts(hosts: List<HostRecord>): List<HostRecord> {
        val map = LinkedHashMap<String, HostRecord>()
        for (host in hosts) {
            val existing = map[host.ip]
            if (existing == null) {
                map[host.ip] = host
                continue
            }
            map[host.ip] = existing.copy(
                mac = existing.mac ?: host.mac,
                hostname = existing.hostname ?: host.hostname,
                vendor = existing.vendor ?: host.vendor,
                openPorts = (existing.openPorts + host.openPorts).distinct().sorted(),
                services = (existing.services + host.services).distinct(),
                kind = if (existing.kind != com.geno1024.ai.netdis.model.DeviceKind.UNKNOWN) {
                    existing.kind
                } else {
                    host.kind
                },
                link = if (existing.link != com.geno1024.ai.netdis.model.LinkKind.UNKNOWN) {
                    existing.link
                } else {
                    host.link
                },
                online = existing.online || host.online,
                sources = existing.sources + host.sources,
                extra = existing.extra + host.extra,
            )
        }
        return map.values.toList()
    }
}

data class RouterFingerprint(
    val reachable: Boolean,
    val title: String? = null,
    val server: String? = null,
    val brand: String? = null,
    val message: String? = null,
) {
    val summary: String?
        get() = listOfNotNull(brand, title)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
            ?: server?.takeIf { it.isNotBlank() }
}

/**
 * Reads the login page when credentials are absent or the API failed. It is
 * enough to name the router and prove the address answers.
 */
object RouterFingerprintProbe {

    suspend fun probe(config: RouterConfig, timeoutMs: Int = 3500): RouterFingerprint {
        val paths = listOf("/", "/cgi-bin/luci", "/login")
        var last: HttpResult? = null
        for (path in paths) {
            val result = RouterHttp.request(
                url = config.baseUrl + path,
                timeoutMs = timeoutMs,
            )
            last = result
            if (result.code in 200..399 || (result.code in 400..499 && result.body.isNotEmpty())) {
                val title = extractTitle(result.body)
                val brand = detectBrand(
                    buildString {
                        append(result.body.take(64 * 1024))
                        append(' ')
                        append(result.headers.entries.joinToString(" ") { "${it.key}=${it.value}" })
                        append(' ')
                        append(path)
                    },
                )
                return RouterFingerprint(
                    reachable = true,
                    title = title,
                    server = result.headers.entries.firstOrNull {
                        it.key.equals("Server", ignoreCase = true)
                    }?.value,
                    brand = brand,
                    message = "HTTP ${result.code}",
                )
            }
            if (result.error != null) break
        }
        return RouterFingerprint(
            reachable = false,
            message = last?.error ?: last?.let { "HTTP ${it.code}" } ?: "无法连接",
        )
    }

    fun extractTitle(html: String): String? {
        val match = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html) ?: return null
        return match.groupValues.getOrNull(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(80)
    }

    fun detectBrand(text: String): String? {
        val lower = text.lowercase()
        return when {
            "openwrt" in lower || "luci" in lower -> "OpenWrt"
            "routeros" in lower || "mikrotik" in lower -> "MikroTik"
            "tplink" in lower || "tp-link" in lower -> "TP-Link"
            "asus" in lower || "asuswrt" in lower -> "ASUS"
            "miwifi" in lower || "小米" in lower || "xiaomi" in lower -> "小米"
            "huawei" in lower || "华为" in lower -> "华为"
            "tenda" in lower || "腾达" in lower -> "腾达"
            "ikuai" in lower || "爱快" in lower -> "iKuai"
            "ruijie" in lower || "锐捷" in lower -> "锐捷"
            "h3c" in lower -> "H3C"
            "zte" in lower || "中兴" in lower -> "中兴"
            "mercury" in lower || "水星" in lower -> "水星"
            "netgear" in lower -> "Netgear"
            "dlink" in lower || "d-link" in lower -> "D-Link"
            "ubiquiti" in lower || "unifi" in lower -> "Ubiquiti"
            "synology" in lower -> "Synology"
            else -> null
        }
    }
}
