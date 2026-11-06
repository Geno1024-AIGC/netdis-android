package com.geno1024.netdis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geno1024.netdis.model.NodeKind
import com.geno1024.netdis.model.RouterConfig
import com.geno1024.netdis.model.RouterSnapshot
import com.geno1024.netdis.model.RouterType
import com.geno1024.netdis.model.TopologyNode
import com.geno1024.netdis.ui.theme.Mint

/**
 * Credentials live here, not in the topology. Each saved router keeps its own
 * enable switch so a broken or foreign device can stay configured without
 * slowing every scan down.
 */
@Composable
fun RoutersScreen(
    state: AppUiState,
    onSave: (RouterConfig) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onNodeClick: (TopologyNode) -> Unit,
    modifier: Modifier = Modifier,
    initialHost: String? = null,
    onInitialHostConsumed: () -> Unit = {},
) {
    var draft by remember { mutableStateOf<RouterConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }

    LaunchedEffect(initialHost) {
        val host = initialHost?.trim().orEmpty()
        if (host.isNotEmpty()) {
            draft = RouterConfig(id = "", label = "", host = host)
            onInitialHostConsumed()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "路由器",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "添加管理账号后，扫描会读取 DHCP 客户端和 WAN 上联",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { draft = RouterConfig(id = "", label = "", host = "") }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        }

        if (state.routers.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Router,
                    title = "还没有路由器账号",
                    body = "主路由即使不登录也会出现在拓扑里。登录 OpenWrt 或 MikroTik 后，才能读到它下面的设备与多级上联关系。",
                    action = {
                        FilledTonalButton(
                            onClick = { draft = RouterConfig(id = "", label = "", host = "") },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加第一台路由器")
                        }
                    },
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 14.dp),
            ) {
                items(state.routers, key = { it.id }) { config ->
                    val snapshot = state.snapshot?.routers?.firstOrNull {
                        it.configId == config.id || it.host == config.host
                    }
                    RouterCard(
                        config = config,
                        snapshot = snapshot,
                        onEdit = { draft = config },
                        onDelete = { pendingDelete = config },
                        onToggle = { onToggle(config.id, it) },
                        onOpen = {
                            state.snapshot?.graph?.nodes
                                ?.firstOrNull { node -> node.kind == NodeKind.ROUTER && node.routerId == config.id }
                                ?.let(onNodeClick)
                        },
                    )
                }
                item {
                    Text(
                        "密码保存在 Android Keystore 加密的本地存储中，只用于局域网登录，不会上传。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    draft?.let { editing ->
        RouterEditorDialog(
            initial = editing,
            onDismiss = { draft = null },
            onSave = {
                onSave(it)
                draft = null
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除路由器？") },
            text = {
                Text("将删除「${target.label.ifBlank { target.host }}」的地址、账号与已保存的密码。已完成的扫描结果不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RouterCard(
    config: RouterConfig,
    snapshot: RouterSnapshot?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val reachable = snapshot?.reachable == true
    val accent = if (reachable) Mint else scheme.outline
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surface.copy(alpha = if (config.enabled) 0.78f else 0.5f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindAvatar(Icons.Outlined.Router, if (reachable) Mint else scheme.onSurfaceVariant, size = 42.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            config.label.ifBlank { config.host },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = scheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        ) {
                            Text(
                                if (config.type == RouterType.AUTO) "自动识别" else config.type.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        buildString {
                            append(config.scheme).append("://").append(config.displayHost)
                            if (!config.enabled) append(" · 已停用")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onToggle)
            }

            val detail = when {
                !config.enabled -> "扫描时会跳过这台路由器"
                snapshot == null -> "尚未登录，下一次扫描会尝试连接"
                reachable -> buildString {
                    append("已登录")
                    snapshot.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    append(" · ").append(snapshot.clientCount).append(" 台客户端")
                    snapshot.wanIp?.takeIf { it.isNotBlank() }?.let { append(" · WAN ").append(it) }
                }

                else -> snapshot.message ?: "连接失败，请检查地址与账号"
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Lan,
                    contentDescription = null,
                    tint = if (reachable) Mint.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    " $detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (reachable) Mint.copy(alpha = 0.9f) else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        tint = scheme.error,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            if (config.note.isNotBlank()) {
                Text(
                    config.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RouterEditorDialog(
    initial: RouterConfig,
    onDismiss: () -> Unit,
    onSave: (RouterConfig) -> Unit,
) {
    val scheme = initial.scheme
    var label by remember { mutableStateOf(initial.label) }
    var host by remember { mutableStateOf(initial.host) }
    var selectedScheme by remember { mutableStateOf(scheme) }
    var portText by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var type by remember { mutableStateOf(initial.type) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var note by remember { mutableStateOf(initial.note) }
    var passwordVisible by remember { mutableStateOf(false) }

    val port = portText.toIntOrNull()
    val hostValid = host.trim().isNotEmpty()
    val portValid = portText.isBlank() || (port != null && port in 1..65535)
    val canSave = hostValid && portValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "添加路由器" else "编辑路由器") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（可选）") },
                    placeholder = { Text("客厅主路由") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("地址") },
                    placeholder = { Text("192.168.1.1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("管理协议", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in listOf("http", "https")) {
                        FilterChip(
                            selected = selectedScheme == option,
                            onClick = { selectedScheme = option },
                            label = { Text(option.uppercase()) },
                            shape = CircleShape,
                        )
                    }
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { text ->
                        portText = text.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("端口（留空自动）") },
                    placeholder = { Text("80 / 443 / 8080") },
                    singleLine = true,
                    isError = !portValid,
                    supportingText = if (!portValid) {
                        { Text("端口需在 1–65535 之间") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("识别方式", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in listOf(RouterType.AUTO, RouterType.OPENWRT, RouterType.MIKROTIK)) {
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label) },
                            shape = CircleShape,
                        )
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    placeholder = { Text("root / admin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：弱电箱里的二级路由") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "OpenWrt 使用 LuCI 账号；MikroTik 需要开启 REST（www-ssl）并使用同一管理账号。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        initial.copy(
                            label = label.trim(),
                            host = host.trim(),
                            scheme = selectedScheme,
                            port = port ?: 0,
                            type = type,
                            username = username.trim(),
                            password = password,
                            note = note.trim(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
