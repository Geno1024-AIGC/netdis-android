package com.geno1024.ai.netdis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterSnapshot
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.storage.CredentialVault
import com.geno1024.ai.netdis.ui.theme.Mint

/**
 * The router list. Editing happens on [RouterEditorScreen], so this screen only
 * has to answer "which routers are configured and are they logged in".
 */
@Composable
fun RoutersScreen(
    state: AppUiState,
    onAdd: () -> Unit,
    onEdit: (RouterConfig) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onLogin: (String) -> Unit,
    onNodeClick: (TopologyNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }

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
            FilledTonalButton(onClick = onAdd) {
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
                    body = "主路由即使不登录也会出现在拓扑里。登录 OpenWrt、MikroTik、中兴或华为后，才能读到它下面的设备与多级上联关系。",
                    action = {
                        FilledTonalButton(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) {
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
                        it.configId == config.id ||
                            it.host == config.host ||
                            (config.mac != null && it.mac == config.mac)
                    }
                    RouterCard(
                        config = config,
                        snapshot = snapshot,
                        login = state.routerLogins[config.id],
                        onEdit = { onEdit(config) },
                        onDelete = { pendingDelete = config },
                        onToggle = { onToggle(config.id, it) },
                        onLogin = { onLogin(config.id) },
                        onOpen = {
                            state.snapshot?.graph?.nodes
                                ?.firstOrNull { node ->
                                    node.kind == NodeKind.ROUTER &&
                                        (node.routerId == config.id || node.ip == config.host)
                                }
                                ?.let(onNodeClick)
                        },
                    )
                }
                item {
                    Text(
                        when (state.credentialMode) {
                            CredentialVault.Mode.SOFTWARE ->
                                "本机 Keystore 不可用，密码改用应用私有存储中的软件密钥加密；仍只用于局域网登录，不会上传。"
                            else ->
                                "密码保存在 Android Keystore 加密的本地存储中，只用于局域网登录，不会上传。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
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
    login: RouterLoginUiState?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val reachable = snapshot?.reachable == true
    val hasCredentials = config.username.isNotBlank() || config.password.isNotBlank()
    val loginFailed = login?.success == false
    val accent = when {
        login?.running == true -> scheme.primary
        reachable -> Mint
        loginFailed -> scheme.error
        else -> scheme.outline
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surface.copy(alpha = if (config.enabled) 0.78f else 0.5f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        // Tapping the card is the primary action: log in with the stored
        // credentials. Without credentials it opens the editor instead.
        onClick = { if (hasCredentials) onLogin() else onEdit() },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindAvatar(Icons.Outlined.Router, accent, size = 42.dp)
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
                                config.type.label,
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
                login?.running == true -> "正在登录并刷新…"
                login?.success == false -> login.message
                login?.success == true -> buildString {
                    append("刚刚登录成功")
                    if (login.clientCount > 0) append(" · ").append(login.clientCount).append(" 台客户端")
                }

                !config.enabled -> "扫描时会跳过这台路由器"
                !hasCredentials -> "点击卡片填写管理账号后可登录"
                snapshot == null -> "尚未登录，点击卡片立即登录"
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
                if (login?.running == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Lan,
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    " $detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        loginFailed -> scheme.error
                        reachable || login?.success == true -> Mint.copy(alpha = 0.9f)
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onLogin,
                    enabled = hasCredentials && login?.running != true,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Login,
                        contentDescription = "登录并刷新",
                        tint = if (hasCredentials) Mint else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(onClick = onOpen, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Lan,
                        contentDescription = "查看拓扑节点",
                        modifier = Modifier.size(17.dp),
                    )
                }
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
            if (config.mac != null) {
                Text(
                    "MAC ${config.mac}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
