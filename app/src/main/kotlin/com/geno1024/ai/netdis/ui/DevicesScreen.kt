package com.geno1024.ai.netdis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.ui.theme.Mint

private enum class DeviceFilter(val label: String) {
    ALL("全部"),
    ONLINE("在线"),
    OFFLINE("离线"),
    ROUTERS("路由器"),
}

/**
 * The flat inventory of the last scan. The topology answers "how is it wired",
 * this tab answers "what exactly was found" without making the user hunt for a
 * card in the graph.
 */
@Composable
fun DevicesScreen(
    state: AppUiState,
    onNodeClick: (TopologyNode) -> Unit,
    onEditCredentials: (RouterCredentialTarget) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }

    val nodes = remember(snapshot?.graph, filter, state.settings.showOffline) {
        val graph = snapshot?.graph?.nodes.orEmpty()
            .filter { state.settings.showOffline || it.online }
        when (filter) {
            DeviceFilter.ALL -> graph.filter { it.kind != NodeKind.INTERNET }
            DeviceFilter.ONLINE -> graph.filter { it.kind != NodeKind.INTERNET && it.online }
            DeviceFilter.OFFLINE -> graph.filter { it.kind != NodeKind.INTERNET && !it.online }
            DeviceFilter.ROUTERS -> graph.filter { it.kind == NodeKind.ROUTER }
        }
    }
    val visible = remember(nodes, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            nodes
        } else {
            nodes.filter { node ->
                sequenceOf(
                    node.label,
                    node.ip,
                    node.host?.mac,
                    node.host?.vendor,
                    node.host?.hostname,
                    node.snapshot?.model,
                ).filterNotNull().any { it.lowercase().contains(needle) }
            }
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
                    "设备",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    snapshot?.let {
                        val online = it.hosts.count { host -> host.online }
                        "${it.hosts.size} 台已记录 · $online 台在线"
                    } ?: "尚未扫描",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onScan) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.scanning) "扫描中" else "扫描")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("名称、IP、MAC 或厂商") },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in DeviceFilter.entries) {
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                    shape = CircleShape,
                )
            }
        }

        when {
            snapshot == null -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.DevicesOther,
                    title = "还没有设备清单",
                    body = "完成一次扫描后，这里会列出所有在线与离线设备，并允许按名称、地址和厂商筛选。",
                    action = {
                        Button(onClick = onScan, modifier = Modifier.padding(top = 8.dp)) {
                            Text("扫描当前网络")
                        }
                    },
                )
            }

            visible.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    title = if (query.isBlank()) "这个筛选下没有设备" else "没有匹配的设备",
                    body = if (query.isBlank()) "换一个筛选条件，或者重新扫描当前网络。" else "试试更短的关键词，或清除搜索。",
                )
            }

            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 14.dp),
            ) {
                items(visible, key = { it.id }) { node ->
                    NodeRow(
                        node = node,
                        onClick = { onNodeClick(node) },
                        onEditCredentials = onEditCredentials,
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: TopologyNode,
    onClick: () -> Unit,
    onEditCredentials: (RouterCredentialTarget) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint: Color
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    if (node.kind == NodeKind.ROUTER) {
        tint = Mint
        icon = NodeKind.ROUTER.icon()
    } else {
        val kind = node.host?.kind ?: DeviceKind.UNKNOWN
        tint = kind.tint()
        icon = kind.icon()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surface.copy(alpha = if (node.online) 0.78f else 0.48f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            scheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindAvatar(icon, tint, size = 40.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface.copy(alpha = if (node.online) 1f else 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(node),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (node.kind == NodeKind.ROUTER) {
                val reachable = node.snapshot?.reachable == true
                Surface(
                    shape = CircleShape,
                    color = if (reachable) Mint.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
                ) {
                    Text(
                        if (reachable) "已登录" else "未登录",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (reachable) Mint else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                val target = routerCredentialTarget(node)
                if (target != null) {
                    IconButton(
                        onClick = { onEditCredentials(target) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.LockOpen,
                            contentDescription = "编辑路由器凭据",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    node.host?.link?.takeIf { it != LinkKind.UNKNOWN }?.let { link ->
                        Icon(
                            link.icon(),
                            contentDescription = link.label(),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (node.online) Mint else scheme.outline.copy(alpha = 0.55f),
                        modifier = Modifier.size(7.dp),
                    ) {}
                }
            }
        }
    }
}

private fun subtitle(node: TopologyNode): String {
    if (node.kind == NodeKind.ROUTER) {
        return buildString {
            append(node.ip.orEmpty())
            val model = node.snapshot?.model
            if (!model.isNullOrBlank()) append(" · ").append(model)
            if (node.clientCount > 0) append(" · ").append(node.clientCount).append(" 台客户端")
            if (!node.online) append(" · 离线")
        }
    }
    val host = node.host
    return buildList {
        node.ip?.let(::add)
        host?.vendor?.takeIf { it.isNotBlank() }?.let(::add)
        if (host != null && host.kind != DeviceKind.UNKNOWN) add(host.kind.label())
        if (!node.online) add("离线")
    }.joinToString(" · ")
}
