package com.geno1024.ai.netdis.ui

import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.model.ScanSnapshot
import com.geno1024.ai.netdis.model.TopologyNode

/**
 * What the credential editor should open for a graph node. A router points at
 * its own configuration; a device points at the router that manages it, so the
 * same "edit credentials" action works from both the topology and the device
 * list. When the router has never been configured, [config] is `null` and the
 * remaining fields pre-fill a fresh form instead of a dialog.
 */
data class RouterCredentialTarget(
    val config: RouterConfig?,
    val host: String,
    val label: String,
    val type: RouterType = RouterType.AUTO,
    val mac: String? = null,
) {
    fun toConfig(): RouterConfig = config ?: RouterConfig(
        id = "",
        label = label,
        host = host,
        type = type,
        mac = mac,
    )
}

/** Target for a router node, configured or merely discovered. */
fun routerCredentialTarget(node: TopologyNode): RouterCredentialTarget? {
    if (node.kind != NodeKind.ROUTER) return null
    val config = node.router
    val host = config?.host ?: node.ip
    if (host.isNullOrBlank()) return null
    return RouterCredentialTarget(
        config = config,
        host = host,
        label = config?.label?.takeIf { it.isNotBlank() } ?: node.label,
        type = config?.type ?: node.snapshot?.actualType ?: RouterType.AUTO,
        mac = config?.mac ?: node.snapshot?.mac ?: node.host?.mac,
    )
}

/**
 * Target for a device node: its parent router. Devices have no credentials of
 * their own, but the device sheet should still lead to the router that owns
 * them instead of dead-ending.
 */
fun deviceCredentialTarget(
    node: TopologyNode,
    snapshot: ScanSnapshot?,
): RouterCredentialTarget? {
    if (node.kind != NodeKind.DEVICE) return null
    val parent = node.parentId?.let { id -> snapshot?.graph?.node(id) } ?: return null
    return routerCredentialTarget(parent)
}
