package com.geno1024.ai.netdis.ui.topology

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.TopologyGraph
import com.geno1024.ai.netdis.model.TopologyNode
import com.geno1024.ai.netdis.topo.TopologyLayout
import com.geno1024.ai.netdis.ui.icon
import com.geno1024.ai.netdis.ui.label
import com.geno1024.ai.netdis.ui.tint
import com.geno1024.ai.netdis.ui.theme.Mint
import com.geno1024.ai.netdis.ui.theme.SignalBlue
import com.geno1024.ai.netdis.ui.theme.SignalViolet

private const val MIN_SCALE = 0.22f
private const val MAX_SCALE = 3.2f

/**
 * The graph is drawn as a set of router "cards" with their clients, then pan
 * and zoom are applied to the whole scene. Nodes are real composables rather
 * than canvas text, so labels ellipsize, icons render identically to the rest
 * of the app, and every node is a proper tap target.
 */
@Composable
fun TopologyCanvas(
    graph: TopologyGraph,
    scanning: Boolean,
    modifier: Modifier = Modifier,
    onNodeClick: (TopologyNode) -> Unit,
) {
    val layout = remember(graph) { TopologyLayout.compute(graph) }
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(layout) { mutableFloatStateOf(1f) }
    var translation by remember(layout) { mutableStateOf(Offset.Zero) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    val scheme = MaterialTheme.colorScheme

    fun fit() {
        if (viewport.width == 0 || viewport.height == 0) return
        val padding = with(density) { 22.dp.toPx() }
        val transform = layout.fitTransform(
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            padding,
        )
        scale = transform.scale
        translation = Offset(transform.offsetX, transform.offsetY)
    }

    LaunchedEffect(layout, viewport) { fit() }

    val transition = rememberInfiniteTransition(label = "topology")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )
    val glow = if (scanning) pulse else 1f

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    val ratio = next / scale
                    translation = centroid - (centroid - translation) * ratio + pan
                    scale = next
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 34.dp.toPx()
            val startX = ((translation.x % step) + step) % step
            val startY = ((translation.y % step) + step) % step
            val dot = scheme.onSurface.copy(alpha = 0.10f)
            var x = startX
            while (x < size.width) {
                var y = startY
                while (y < size.height) {
                    drawCircle(dot, radius = 1.1.dp.toPx(), center = Offset(x, y))
                    y += step
                }
                x += step
            }
        }

        Box(
            Modifier
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = translation.x
                    translationY = translation.y
                }
                .size(layout.width.dp, layout.height.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = 22.dp.toPx()
                for (cluster in layout.clusters) {
                    val box = cluster.box
                    val topLeft = Offset(box.x.dp.toPx(), box.y.dp.toPx())
                    val size = androidx.compose.ui.geometry.Size(
                        box.w.dp.toPx(),
                        box.h.dp.toPx(),
                    )
                    drawRoundRect(
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.42f),
                        topLeft = topLeft,
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    )
                    drawRoundRect(
                        color = scheme.outlineVariant.copy(alpha = 0.55f),
                        topLeft = topLeft,
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

                val dashed = PathEffect.dashPathEffect(
                    floatArrayOf(7.dp.toPx(), 6.dp.toPx()),
                )
                for (link in layout.links) {
                    val color = when (link.link) {
                        LinkKind.WIFI -> SignalViolet
                        LinkKind.ETHERNET -> Mint
                        LinkKind.UNKNOWN -> scheme.onSurfaceVariant
                    }
                    val x1 = link.x1.dp.toPx()
                    val y1 = link.y1.dp.toPx()
                    val x2 = link.x2.dp.toPx()
                    val y2 = link.y2.dp.toPx()
                    val bend = ((y2 - y1) * 0.55f).coerceAtLeast(0f)
                    val path = Path().apply {
                        moveTo(x1, y1)
                        cubicTo(x1, y1 + bend, x2, y2 - bend, x2, y2)
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.45f * glow + 0.12f),
                        style = Stroke(
                            width = 1.6.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (link.link == LinkKind.WIFI) dashed else null,
                        ),
                    )
                    drawCircle(color.copy(alpha = 0.65f * glow), radius = 2.2.dp.toPx(), center = Offset(x2, y2))
                }
            }

            for (node in graph.nodes) {
                val box = layout.nodes[node.id] ?: continue
                val selected = node.id == selectedId
                Box(
                    Modifier
                        .offset { IntOffset(box.x.dp.roundToPx(), box.y.dp.roundToPx()) }
                        .size(box.w.dp, box.h.dp),
                ) {
                    when (node.kind) {
                        NodeKind.INTERNET -> InternetCard(node)
                        NodeKind.ROUTER -> RouterCard(node, selected)
                        NodeKind.DEVICE -> DeviceCard(node, selected)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                selectedId = node.id
                                onNodeClick(node)
                            },
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZoomButton(Icons.Outlined.Add, "放大") {
                scale = (scale * 1.25f).coerceAtMost(MAX_SCALE)
            }
            ZoomButton(Icons.Outlined.Remove, "缩小") {
                scale = (scale / 1.25f).coerceAtLeast(MIN_SCALE)
            }
            ZoomButton(Icons.Outlined.ZoomOutMap, "适应屏幕") { fit() }
        }
    }
}

@Composable
private fun ZoomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InternetCard(node: TopologyNode) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        SignalBlue.copy(alpha = 0.26f),
                        SignalViolet.copy(alpha = 0.18f),
                    ),
                ),
            )
            .border(1.dp, SignalBlue.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                com.geno1024.ai.netdis.model.NodeKind.INTERNET.icon(),
                contentDescription = null,
                tint = SignalBlue,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "  ${node.label}",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
            )
        }
        Text(
            node.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RouterCard(node: TopologyNode, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (node.online) Mint else scheme.outline
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (selected) Modifier.border(2.dp, scheme.primary, RoundedCornerShape(18.dp))
                else Modifier,
            ),
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    NodeKind.ROUTER.icon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (node.online) Mint else scheme.outline),
                )
            }
            Column(Modifier.padding(start = 9.dp).weight(1f)) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (node.clientCount > 0) {
                        "${node.ip} · ${node.clientCount} 台"
                    } else {
                        node.ip.orEmpty()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    node.snapshot?.let { it.model ?: it.message } ?: "未登录",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = if (node.online) Mint.copy(alpha = 0.9f) else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(node: TopologyNode, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val kind = node.host?.kind ?: com.geno1024.ai.netdis.model.DeviceKind.UNKNOWN
    val tint = kind.tint()
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (selected) Modifier.border(2.dp, scheme.primary, RoundedCornerShape(16.dp))
                else Modifier,
            ),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface.copy(alpha = if (node.online) 0.9f else 0.55f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            tint.copy(alpha = if (node.online) 0.45f else 0.18f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    kind.icon(),
                    contentDescription = null,
                    tint = tint.copy(alpha = if (node.online) 1f else 0.5f),
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(Modifier.padding(start = 7.dp).weight(1f)) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface.copy(alpha = if (node.online) 1f else 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !node.online -> "${node.ip} · 离线"
                        kind != com.geno1024.ai.netdis.model.DeviceKind.UNKNOWN -> kind.label()
                        else -> node.ip.orEmpty()
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
