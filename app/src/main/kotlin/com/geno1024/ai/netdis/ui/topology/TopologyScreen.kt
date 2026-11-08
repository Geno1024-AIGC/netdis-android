package com.geno1024.ai.netdis.ui.topology

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.ScanStage
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.ui.Backdrop
import com.geno1024.ai.netdis.ui.EmptyState
import com.geno1024.ai.netdis.ui.GlassCard
import com.geno1024.ai.netdis.ui.formatAgo
import com.geno1024.ai.netdis.ui.formatDuration
import com.geno1024.ai.netdis.ui.theme.Mint
import com.geno1024.ai.netdis.ui.theme.SignalBlue
import com.geno1024.ai.netdis.ui.theme.SignalViolet

@Composable
fun TopologyScreen(
    state: com.geno1024.ai.netdis.ui.AppUiState,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onNodeClick: (TopologyNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val graph = remember(snapshot?.graph, state.settings.showOffline) {
        val source = snapshot?.graph ?: com.geno1024.ai.netdis.model.TopologyGraph()
        if (state.settings.showOffline) {
            source
        } else {
            val hidden = source.nodes
                .filter { it.kind == NodeKind.DEVICE && !it.online }
                .map { it.id }
                .toSet()
            source.copy(
                nodes = source.nodes.filterNot { it.id in hidden },
                edges = source.edges.filterNot { it.from in hidden || it.to in hidden },
            )
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
                    "网络拓扑",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    buildString {
                        snapshot?.local?.let { append("${it.cidr} · ${it.transport}") }
                            ?: append("尚未扫描")
                        snapshot?.let {
                            append(" · ")
                            append(formatAgo(it.finishedAt))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.scanning) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            } else {
                Button(onClick = onScan) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (snapshot == null) "开始扫描" else "扫描")
                }
            }
        }

        if (state.scanning) {
            ScanStatusCard(state)
            Spacer(Modifier.size(10.dp))
        }

        state.error?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        "关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(4.dp).then(
                            Modifier.clickableNoRipple(onDismissError),
                        ),
                    )
                }
            }
        }

        if (snapshot == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.ZoomOutMap,
                    title = "还没画过这张网",
                    body = "扫描当前 Wi-Fi 下的所有地址，读出路由器里的 DHCP 客户端，再把多级路由连成一棵树。",
                    action = {
                        Button(onClick = onScan, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("扫描当前网络")
                        }
                    },
                )
            }
        } else {
            SnapshotSummary(state)
            Spacer(Modifier.size(10.dp))
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                ),
            ) {
                TopologyCanvas(
                    graph = graph,
                    scanning = state.scanning,
                    modifier = Modifier.fillMaxSize(),
                    onNodeClick = onNodeClick,
                )
            }
            Legend()
            if (snapshot.warnings.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp).padding(top = 2.dp),
                    )
                    Text(
                        snapshot.warnings.first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 2,
                    )
                }
            } else {
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun SnapshotSummary(state: com.geno1024.ai.netdis.ui.AppUiState) {
    val snapshot = state.snapshot ?: return
    val online = snapshot.hosts.count { it.online }
    val routers = snapshot.routers.count { it.reachable }
    GlassCard(padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(value = online.toString(), label = "在线设备")
            Divider()
            Stat(value = "${routers}/${snapshot.routers.size}", label = "已登录路由")
            Divider()
            Stat(value = formatDuration(snapshot.durationMs), label = "扫描用时")
        }
    }
}

@Composable
private fun Divider() {
    Surface(
        modifier = Modifier.size(width = 1.dp, height = 34.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    ) {}
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanStatusCard(state: com.geno1024.ai.netdis.ui.AppUiState) {
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadarDial(size = 56.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    state.progress.stage.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    state.progress.detail.ifBlank { "正在读取局域网" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                LinearProgressIndicator(
                    progress = { state.progress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
            Column(
                Modifier.padding(start = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.progress.found.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Mint,
                )
                Text(
                    "已发现",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(Mint, "有线")
        LegendDot(SignalViolet, "Wi-Fi")
        Spacer(Modifier.weight(1f))
        Text(
            "点按节点查看详情",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(6.dp)) {}
        Text(
            " $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The rotating sweep is the app's "scanning" signal, reused at two sizes. */
@Composable
fun RadarDial(size: Dp) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        for (ring in 1..3) {
            drawCircle(
                color = primary.copy(alpha = 0.16f),
                radius = radius * ring / 3f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        drawLine(
            color = primary.copy(alpha = 0.12f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = primary.copy(alpha = 0.12f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx(),
        )
        rotate(degrees = angle, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(primary.copy(alpha = 0f), primary.copy(alpha = 0.55f)),
                    center = center,
                ),
                startAngle = -8f,
                sweepAngle = 58f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
            )
        }
        drawCircle(primary, radius = 2.5.dp.toPx(), center = center)
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.padding(2.dp).clickable(onClick = onClick))
