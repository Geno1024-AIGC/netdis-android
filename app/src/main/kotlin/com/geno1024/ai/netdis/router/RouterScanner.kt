package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.RouterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the right adapter for one configured router. AUTO tries OpenWrt first
 * (ubus answers only when it exists) and then RouterOS, so one credential entry
 * survives a router replacement.
 */
object RouterScanner {

    suspend fun scan(config: RouterConfig, timeoutMs: Int = 3500): RouterSnapshot =
        withContext(Dispatchers.IO) {
            val normalized = normalize(config)
            if (normalized.host.isBlank()) {
                return@withContext snapshot(
                    config = normalized,
                    reachable = false,
                    message = "未填写路由器地址",
                )
            }

            val fingerprint = RouterFingerprintProbe.probe(normalized, timeoutMs)
            val hasCredentials = normalized.username.isNotBlank() || normalized.password.isNotBlank()
            val outcomes = ArrayList<RouterScanOutcome>()

            if (hasCredentials) {
                when (normalized.type) {
                    RouterType.OPENWRT -> outcomes += OpenWrtAdapter.scan(normalized, timeoutMs)
                    RouterType.MIKROTIK -> outcomes += MikroTikAdapter.scan(normalized, timeoutMs)
                    RouterType.AUTO -> {
                        outcomes += OpenWrtAdapter.scan(normalized, timeoutMs)
                        if (outcomes.none { it.success }) {
                            outcomes += MikroTikAdapter.scan(normalized, timeoutMs)
                        }
                    }

                    RouterType.UNKNOWN -> Unit
                }
            }

            val success = outcomes.firstOrNull { it.success }
            if (success != null) {
                return@withContext snapshot(
                    config = normalized,
                    reachable = true,
                    message = success.message,
                    outcome = success,
                    brand = fingerprint.brand,
                )
            }

            val failure = outcomes.firstOrNull()
            val summary = fingerprint.summary
            val message = when {
                !hasCredentials && fingerprint.reachable ->
                    "已识别 ${summary ?: "路由器"}，未填写登录凭据"

                !hasCredentials -> fingerprint.message ?: "未填写登录凭据"
                fingerprint.reachable && failure != null ->
                    "${failure.message}（已识别 ${summary ?: "路由器"}）"

                failure != null -> failure.message
                fingerprint.reachable -> "路由器在线，但 ${normalized.type.label} 接口不可用"
                else -> fingerprint.message ?: "无法连接路由器"
            }

            snapshot(
                config = normalized,
                reachable = fingerprint.reachable,
                message = message,
                brand = fingerprint.brand,
                fingerprint = summary,
            )
        }

    private fun snapshot(
        config: RouterConfig,
        reachable: Boolean,
        message: String,
        outcome: RouterScanOutcome? = null,
        brand: String? = null,
        fingerprint: String? = null,
    ): RouterSnapshot = RouterSnapshot(
        configId = config.id,
        label = config.label.ifBlank { config.host },
        host = config.host,
        reachable = reachable,
        message = message,
        actualType = outcome?.type ?: config.type.takeIf { it != RouterType.AUTO },
        model = outcome?.model ?: brand,
        firmware = outcome?.firmware,
        uptime = outcome?.uptime,
        wanIp = outcome?.wanIp,
        wanGateway = outcome?.wanGateway,
        lanCidrs = outcome?.lanCidrs.orEmpty(),
        dnsServers = outcome?.dnsServers.orEmpty(),
        clients = outcome?.clients.orEmpty(),
        wirelessCount = outcome?.wirelessCount ?: 0,
        externalIp = outcome?.externalIp,
        fingerprint = outcome?.fingerprint ?: fingerprint,
    )

    /**
     * Accepts the shorthand people actually type (`192.168.1.1:8080`,
     * `http://router.lan`) and folds it into the explicit fields.
     */
    fun normalize(config: RouterConfig): RouterConfig {
        var host = config.host.trim()
        var scheme = config.scheme.ifBlank { "http" }
        var port = config.port
        if (host.startsWith("http://", ignoreCase = true)) {
            scheme = "http"
            host = host.substring(7)
        } else if (host.startsWith("https://", ignoreCase = true)) {
            scheme = "https"
            host = host.substring(8)
        }
        host = host.trimEnd('/').substringBefore('/')
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.indexOf(':') == colon) {
            val candidate = host.substring(colon + 1).toIntOrNull()
            if (candidate != null && candidate in 1..65535) {
                port = candidate
                host = host.substring(0, colon)
            }
        }
        return config.copy(host = host, scheme = scheme, port = port)
    }

    /** Used by the dashboard before a scan to show one row per known router. */
    fun unreachable(config: RouterConfig, message: String): RouterSnapshot = RouterSnapshot(
        configId = config.id,
        label = config.label.ifBlank { config.host },
        host = config.host,
        reachable = false,
        message = message,
    )

    fun hostRecordsOf(snapshot: RouterSnapshot): List<HostRecord> = snapshot.clients
}
