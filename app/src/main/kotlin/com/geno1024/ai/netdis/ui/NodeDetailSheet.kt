package com.geno1024.ai.netdis.ui

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
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.PortScanState
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import com.geno1024.ai.netdis.model.ScanSnapshot
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.ui.theme.Mint

/**
 * One sheet for every node kind: a device shows its identity evidence and lets
 * the user give it a private name/note, a router its firmware, uplink, clients
 * and an in-app login button. The sheet is also where an unauthenticated router
 * becomes actionable, by jumping to the router form with its address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    node: TopologyNode,
    snapshot: ScanSnapshot?,
    onDismiss: () -> Unit,
    credentialTarget: RouterCredentialTarget? = null,
    onEditCredentials: (RouterCredentialTarget) -> Unit = {},
    onRescan: () -> Unit,
    onSaveNote: (HostRecord, String, String) -> Unit = { _, _, _ -> },
    onDeleteNote: (HostRecord) -> Unit = {},
    routerLogin: RouterLoginUiState? = null,
    onLoginRouter: (RouterConfig) -> Unit = {},
    portScan: PortScanState? = null,
    onStartPortScan: (HostRecord) -> Unit = {},
    onCancelPortScan: () -> Unit = {},
    onDismissPortScan: () -> Unit = {},
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

                NodeKind.ROUTER -> RouterDetails(
                    node = node,
                    snapshot = snapshot,
                    credentialTarget = credentialTarget,
                    onEditCredentials = onEditCredentials,
                    login = routerLogin,
                    onLogin = onLoginRouter,
                )

                NodeKind.DEVICE -> DeviceDetails(
                    node = node,
                    credentialTarget = credentialTarget,
                    onEditCredentials = onEditCredentials,
                    onSaveNote = onSaveNote,
                    onDeleteNote = onDeleteNote,
                    portScan = portScan,
                    onStartPortScan = onStartPortScan,
                    onCancelPortScan = onCancelPortScan,
                    onDismissPortScan = onDismissPortScan,
                )
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
    credentialTarget: RouterCredentialTarget?,
    onEditCredentials: (RouterCredentialTarget) -> Unit,
    login: RouterLoginUiState?,
    onLogin: (RouterConfig) -> Unit,
) {
    val router = node.snapshot
    val config = credentialTarget?.config ?: node.router
    val hasCredentials = config != null &&
        (config.username.isNotBlank() || config.password.isNotBlank())
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

    if (credentialTarget != null) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("路由器凭据", style = MaterialTheme.typography.titleSmall)
                Text(
                    login?.message?.takeIf { it.isNotBlank() }
                        ?: if (hasCredentials) {
                            "使用已保存的账号登录，拉取客户端与 WAN 上联并立即更新拓扑。"
                        } else if (config == null) {
                            "这台路由器还没有保存账号。填写后会按 MAC 绑定，地址变化也能自动找回。"
                        } else {
                            "还没有保存账号。先编辑路由器填入管理账号，之后即可登录。"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (login?.success) {
                        false -> MaterialTheme.colorScheme.error
                        true -> Mint
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { config?.let(onLogin) },
                        enabled = hasCredentials && login?.running != true,
                    ) {
                        if (login?.running == true) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (login?.running == true) "登录中…" else "登录并刷新")
                    }
                    TextButton(onClick = { onEditCredentials(credentialTarget) }) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (config == null) "填写账号" else "编辑凭据")
                    }
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
private fun DeviceDetails(
    node: TopologyNode,
    credentialTarget: RouterCredentialTarget?,
    onEditCredentials: (RouterCredentialTarget) -> Unit,
    onSaveNote: (HostRecord, String, String) -> Unit,
    onDeleteNote: (HostRecord) -> Unit,
    portScan: PortScanState?,
    onStartPortScan: (HostRecord) -> Unit,
    onCancelPortScan: () -> Unit,
    onDismissPortScan: () -> Unit,
) {
    val host = node.host ?: return
    var editing by remember(host.ip, host.mac) { mutableStateOf(false) }
    val hasNote = !host.alias.isNullOrBlank() || !host.note.isNullOrBlank()
    val thisScan = portScan?.takeIf { it.ip == host.ip }
    val otherScanRunning = portScan?.running == true && portScan.ip != host.ip

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
    if (!host.alias.isNullOrBlank()) InfoRow("自定义名称", host.alias)
    if (!host.note.isNullOrBlank()) InfoRow("备注", host.note)
    for ((label, value) in hostExtraLines(host)) {
        InfoRow(label, value)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hasNote) "设备备注" else "这是未知设备？",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (hasNote) {
                    "备注按 MAC 保存，IP 变化或下次重新扫描仍会跟着这台设备。"
                } else {
                    "给它起个名字或写一条备注，下次扫描就能一眼认出它。备注只保存在本机。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasNote) "编辑备注" else "添加备注")
                }
                if (hasNote) {
                    TextButton(
                        onClick = {
                            onDeleteNote(host)
                            editing = false
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    PortScanCard(
        host = host,
        scan = thisScan,
        otherScanRunning = otherScanRunning,
        onStart = { onStartPortScan(host) },
        onCancel = onCancelPortScan,
        onDismiss = onDismissPortScan,
    )

    if (credentialTarget != null) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上级路由器", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${credentialTarget.label} 负责管理这台设备。读取更多客户端、修正登录凭据或重新登录时，可以直接前往它的编辑页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onEditCredentials(credentialTarget) }) {
                    Icon(
                        Icons.Outlined.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (credentialTarget.config == null) {
                            "添加上级路由凭据"
                        } else {
                            "编辑上级路由凭据"
                        },
                    )
                }
            }
        }
    }

    if (editing) {
        DeviceNoteDialog(
            host = host,
            onDismiss = { editing = false },
            onSave = { alias, note ->
                onSaveNote(host, alias, note)
                editing = false
            },
            onDelete = {
                onDeleteNote(host)
                editing = false
            },
        )
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

@Composable
private fun PortScanCard(
    host: HostRecord,
    scan: PortScanState?,
    otherScanRunning: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("全端口扫描", style = MaterialTheme.typography.titleSmall)
            Text(
                "依次探测 1–65535 全部 TCP 端口，通常需要几分钟到十几分钟。" +
                    "建议连接电源后开始；切到后台也会继续，并在通知栏显示同样的进度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (scan == null) {
                if (otherScanRunning) {
                    Text(
                        "另一台设备的全端口扫描正在进行，完成或取消后才能开始新的扫描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onStart, enabled = !otherScanRunning) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫描全部端口")
                }
            } else {
                LinearProgressIndicator(
                    progress = { scan.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        scan.running -> "正在扫描 ${scan.scanned}/${scan.total} 个端口…"
                        scan.cancelled -> "扫描已取消，已扫描 ${scan.scanned}/${scan.total} 个端口"
                        scan.message?.contains("失败") == true ->
                            "扫描失败，已扫描 ${scan.scanned}/${scan.total} 个端口"
                        else -> "扫描完成，共 ${scan.total} 个端口"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (scan.openPorts.isEmpty()) {
                        if (scan.running) "已发现 0 个开放端口" else "没有发现开放端口"
                    } else {
                        "开放端口（${scan.openPorts.size}）：" +
                            scan.openPorts.take(40).joinToString("，") +
                            if (scan.openPorts.size > 40) " …" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                scan.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scan.running) {
                        TextButton(onClick = onCancel) {
                            Text("取消扫描", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("收起结果") }
                        Button(onClick = onStart, enabled = !otherScanRunning) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新扫描")
                        }
                    }
                }
                if (!host.online) {
                    Text(
                        "这台设备当前显示为离线，扫描可能得到全部超时的结果。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceNoteDialog(
    host: HostRecord,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var alias by remember(host.ip, host.mac) { mutableStateOf(host.alias.orEmpty()) }
    var note by remember(host.ip, host.mac) { mutableStateOf(host.note.orEmpty()) }
    val hasNote = !host.alias.isNullOrBlank() || !host.note.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    host.ip + (host.mac?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.take(48) },
                    label = { Text("名称（可选）") },
                    placeholder = { Text("客厅扫地机器人") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：孩子房间的平板，MAC 随机化") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(alias, note) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (hasNote) {
                    TextButton(onClick = onDelete) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun nodeKindLabel(node: TopologyNode): String = when (node.kind) {
    NodeKind.INTERNET -> "互联网出口"
    NodeKind.ROUTER -> buildString {
        append("路由器")
        node.snapshot?.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }

    NodeKind.DEVICE -> node.host?.kind?.label() ?: "设备"
}
