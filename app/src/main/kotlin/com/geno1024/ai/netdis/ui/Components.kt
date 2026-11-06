package com.geno1024.ai.netdis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.TopologyNode

/**
 * The whole app sits on one instrument-panel gradient. A single soft glow in
 * the top corner keeps the flat background from looking like an empty page
 * without competing with the graph.
 */
@Composable
fun Backdrop(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.background,
                        scheme.surfaceContainerLow,
                        scheme.background,
                    ),
                ),
            )
            .drawBehind {
                val radius = size.width * 0.9f
                val glow = scheme.primary.copy(alpha = 0.10f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow, Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.02f),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(size.width * 0.85f, size.height * 0.02f),
                )
            },
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun KindAvatar(
    icon: ImageVector,
    tint: Color,
    size: Dp = 40.dp,
    selected: Boolean = false,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (selected) 0.28f else 0.14f))
            .border(
                width = 1.dp,
                color = tint.copy(alpha = if (selected) 0.9f else 0.35f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun KindAvatar(kind: DeviceKind, size: Dp = 40.dp, selected: Boolean = false) {
    KindAvatar(kind.icon(), kind.tint(), size, selected)
}

@Composable
fun InfoRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(
    values: List<String>,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (value in values) {
            AssistChip(
                onClick = {},
                label = { Text(value, style = MaterialTheme.typography.labelSmall) },
                shape = CircleShape,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KindAvatar(icon, MaterialTheme.colorScheme.primary, size = 72.dp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.invoke()
    }
}

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.padding(start = 8.dp)) {
                    Text("重试", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "关闭",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(4.dp),
            )
        }
    }
}

fun extraLabel(key: String): String = when (key) {
    "model" -> "型号"
    "ssid" -> "Wi-Fi"
    "signal" -> "信号"
    "wifiMode" -> "Wi-Fi 模式"
    "workgroup" -> "工作组"
    "port" -> "服务端口"
    "mdnsHost" -> "mDNS 主机"
    "manufacturer" -> "厂商（UPnP）"
    "upnpLocation" -> "UPnP 描述"
    "lease" -> "地址来源"
    else -> key
}

fun hostExtraLines(host: HostRecord): List<Pair<String, String>> = buildList {
    for ((key, value) in host.extra) {
        if (value.isBlank()) continue
        add(extraLabel(key) to value)
    }
}

fun nodeSubtitle(node: TopologyNode): String = when (node.kind) {
    NodeKind.INTERNET -> node.subtitle.ifBlank { "公网出口" }
    NodeKind.ROUTER -> listOfNotNull(
        node.ip,
        node.snapshot?.model,
        node.snapshot?.message,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    NodeKind.DEVICE -> node.ip.orEmpty()
}

fun linkSummary(node: TopologyNode): String? = node.host?.link?.takeIf { it != LinkKind.UNKNOWN }?.label()

@Composable
fun TinyLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
