package com.geno1024.netdis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geno1024.netdis.model.DeviceKind
import com.geno1024.netdis.model.HostSource
import com.geno1024.netdis.model.LinkKind
import com.geno1024.netdis.model.NodeKind
import com.geno1024.netdis.model.RouterType
import com.geno1024.netdis.model.ScanSnapshot
import com.geno1024.netdis.model.TopologyNode
import com.geno1024.netdis.ui.theme.Mint

/**
 * One sheet for every node kind: a device shows its identity evidence, a
 * router its firmware and WAN uplink. The sheet is also where an unauthenticated
 * router becomes actionable, by jumping to the router form with its address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    node: TopologyNode,
    snapshot: ScanSnapshot?,
    onDismiss: () -> Unit,
    onAddRouter: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val avatarIcon = when (node.kind) {
                    NodeKind.INTERNET -> node.kind.icon()
                    NodeKind.ROUTER -> node.kind.icon()
                    NodeKind.DEVICE -> node.host?.kind?.icon() ?: DeviceKind.UNKNOWN.icon()
                }
                val tint = when (node.kind) {
                    NodeKind.INTERNET -> node.kind.tint()
                    NodeKind.ROUTER -> node.kind.tint()
                    NodeKind.DEVICE -> node.host?.kind?.tint() ?: DeviceKind.UNKNOWN.tint()
                }
                KindAvatar(avatarIcon, tint, size = 52.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        node.label,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        nodeKindLabel(node),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusDot(online = node.online)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            when (node.kind) {
                NodeKind.INTERNET -> {
                    InfoRow("说明", "光猫或运营商边界，拓扑从这里开始。")
                    InfoRow("出口地址", node.subtitle)
                }

                NodeKind.ROUTER -> RouterDetails(node, snapshot, onAddRouter)

                NodeKind.DEVICE -> DeviceDetails(node)
            }

            Spacer(Modifier.size(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onRescan, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(0.6f)) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (online) Mint.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (online) Mint else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(7.dp),
            ) {}
            Text(
                if (online) " 在线" else " 离线",
                style = MaterialTheme.typography.labelSmall,
                color = if (online) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RouterDetails(
    node: TopologyNode,
    snapshot: ScanSnapshot?,
    onAddRouter: (String) -> Unit,
) {
    val router = node.snapshot
    val config = node.router
    InfoRow("地址", node.ip)
    InfoRow("管理方式", router?.actualType?.label ?: config?.type?.label ?: RouterType.AUTO.label)
    InfoRow("型号", router?.model ?: config?.label ?: node.label)
    InfoRow("固件", router?.firmware)
    InfoRow("运行时间", router?.uptime)
    InfoRow("WAN 地址", router?.wanIp)
    InfoRow("上级网关", router?.wanGateway)
    InfoRow("内网网段", router?.lanCidrs?.takeIf { it.isNotEmpty() }?.joinToString("，"))
    InfoRow("DNS", router?.dnsServers?.takeIf { it.isNotEmpty() }?.joinToString("，"))
    InfoRow(
        "客户端",
        router?.let {
            buildString {
                append("${it.clientCount} 台")
                if (it.wirelessCount > 0) append("，其中无线 ${it.wirelessCount} 台")
            }
        },
    )
    InfoRow("登录账号", config?.username)
    InfoRow("备注", router?.message ?: config?.note)

    val needsCredentials = router != null && !router.reachable
    if (needsCredentials) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "这台路由器还没有登录凭据",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "添加 OpenWrt 或 MikroTik 的管理账号后，可以读取 DHCP 客户端、无线关联和 WAN 上联，下级路由会被自动挂到它下面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(onClick = { node.ip?.let(onAddRouter) }) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加登录凭据")
                }
            }
        }
    }

    if (router != null && router.clients.isNotEmpty()) {
        Text(
            "已知客户端",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        for (client in router.clients.take(8)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    client.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    client.ip,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (router.clients.size > 8) {
            Text(
                "还有 ${router.clients.size - 8} 台…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // The parent name is easier to read than a node id in the graph.
    val parent = snapshot?.graph?.nodes?.firstOrNull { it.id == node.parentId }
    InfoRow("上一级", parent?.label)
}

@Composable
private fun DeviceDetails(node: TopologyNode) {
    val host = node.host ?: return
    InfoRow("IP 地址", host.ip)
    InfoRow("MAC", host.mac)
    InfoRow("厂商", host.vendor)
    InfoRow("设备类型", host.kind.label())
    InfoRow("连接方式", host.link.takeIf { it != LinkKind.UNKNOWN }?.label())
    InfoRow("开放端口", host.openPorts.takeIf { it.isNotEmpty() }?.joinToString("，"))
    InfoRow("服务", host.services.takeIf { it.isNotEmpty() }?.joinToString("，"))
    InfoRow(
        "发现方式",
        host.sources.takeIf { it.isNotEmpty() }?.map(HostSource::label)?.joinToString(" · "),
    )
    for ((label, value) in hostExtraLines(host)) {
        InfoRow(label, value)
    }
    if (host.sources.contains(HostSource.MANUAL)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ) {
            Text(
                "这是正在运行扫描的手机，所以不会探测自己的端口。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private fun nodeKindLabel(node: TopologyNode): String = when (node.kind) {
    NodeKind.INTERNET -> "互联网出口"
    NodeKind.ROUTER -> buildString {
        append("路由器")
        node.snapshot?.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }

    NodeKind.DEVICE -> node.host?.kind?.label() ?: "设备"
}
