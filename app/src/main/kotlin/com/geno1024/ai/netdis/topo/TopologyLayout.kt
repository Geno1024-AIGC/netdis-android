package com.geno1024.ai.netdis.topo

import com.geno1024.ai.netdis.model.LinkKind
import com.geno1024.ai.netdis.model.NodeKind
import com.geno1024.ai.netdis.model.TopologyGraph
import com.geno1024.ai.netdis.model.TopologyNode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Positions the graph as a set of router "zones": each router sits at the top
 * of a card containing its clients, and child routers hang below it. This keeps
 * a 40-device home readable without the hairball a force layout would produce.
 */
object TopologyLayout {

    const val INTERNET_W = 148f
    const val INTERNET_H = 58f
    const val ROUTER_W = 156f
    const val ROUTER_H = 66f
    const val DEVICE_W = 116f
    const val DEVICE_H = 54f

    private const val CLUSTER_PAD = 18f
    private const val CLUSTER_TOP_GAP = 16f
    private const val DEVICE_GAP_X = 12f
    private const val DEVICE_GAP_Y = 10f
    private const val CHILD_GAP_X = 44f
    private const val CHILD_GAP_Y = 46f
    private const val ROOT_GAP_X = 56f
    private const val INTERNET_GAP_Y = 72f
    private const val MAX_DEVICE_COLS = 4

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        val centerX: Float get() = x + w / 2f
        val centerY: Float get() = y + h / 2f
        val bottom: Float get() = y + h
        val right: Float get() = x + w
    }

    data class Cluster(val routerId: String, val box: Box)

    data class Link(
        val from: String,
        val to: String,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val link: LinkKind,
    )

    data class Layout(
        val nodes: Map<String, Box>,
        val clusters: List<Cluster>,
        val links: List<Link>,
        val internet: Box,
        val width: Float,
        val height: Float,
    ) {
        fun fitTransform(viewWidth: Float, viewHeight: Float, padding: Float = 40f): Transform {
            val usableW = (viewWidth - padding * 2).coerceAtLeast(1f)
            val usableH = (viewHeight - padding * 2).coerceAtLeast(1f)
            val scale = min(usableW / width.coerceAtLeast(1f), usableH / height.coerceAtLeast(1f))
                .coerceIn(0.08f, 2.4f)
            val offsetX = (viewWidth - width * scale) / 2f
            val offsetY = (viewHeight - height * scale) / 2f
            return Transform(scale, offsetX, offsetY)
        }
    }

    data class Transform(val scale: Float, val offsetX: Float, val offsetY: Float)

    private data class Geometry(
        val clusterWidth: Float,
        val clusterHeight: Float,
        val cols: Int,
        val rows: Int,
        val gridWidth: Float,
    )

    private data class Block(
        val width: Float,
        val height: Float,
        val geometry: Geometry,
        val children: List<Pair<TopologyNode, Block>>,
    )

    fun compute(graph: TopologyGraph): Layout {
        val routers = graph.nodes.filter { it.kind == NodeKind.ROUTER }
        val routerById = routers.associateBy { it.id }
        val devicesByRouter = graph.nodes
            .filter { it.kind == NodeKind.DEVICE }
            .groupBy { it.parentId }
        val childrenByRouter = routers
            .filter { it.parentId != null && routerById.containsKey(it.parentId) }
            .groupBy { it.parentId!! }

        fun geometryFor(count: Int): Geometry {
            val cols = if (count == 0) 0 else
                ceil(sqrt(count.toDouble())).toInt().coerceIn(1, MAX_DEVICE_COLS)
            val rows = if (cols == 0) 0 else (count + cols - 1) / cols
            val gridWidth = if (cols == 0) 0f else
                cols * DEVICE_W + (cols - 1) * DEVICE_GAP_X
            val width = max(ROUTER_W + CLUSTER_PAD * 2, gridWidth + CLUSTER_PAD * 2)
            val height = if (rows == 0) {
                ROUTER_H + CLUSTER_PAD * 2
            } else {
                CLUSTER_PAD + ROUTER_H + CLUSTER_TOP_GAP +
                    rows * DEVICE_H + (rows - 1) * DEVICE_GAP_Y + CLUSTER_PAD
            }
            return Geometry(width, height, cols, rows, gridWidth)
        }

        val stack = HashSet<String>()

        fun measure(router: TopologyNode): Block {
            stack += router.id
            val geometry = geometryFor(devicesByRouter[router.id].orEmpty().size)
            val children = childrenByRouter[router.id].orEmpty()
                .filter { it.id !in stack }
                .map { child -> child to measure(child) }
            stack -= router.id

            if (children.isEmpty()) {
                return Block(geometry.clusterWidth, geometry.clusterHeight, geometry, emptyList())
            }
            val childAreaWidth = children.sumOf { it.second.width.toDouble() }.toFloat() +
                (children.size - 1) * CHILD_GAP_X
            val width = max(geometry.clusterWidth, childAreaWidth)
            val height = geometry.clusterHeight + CHILD_GAP_Y +
                children.maxOf { it.second.height }
            return Block(width, height, geometry, children)
        }

        val placedNodes = LinkedHashMap<String, Box>()
        val clusters = ArrayList<Cluster>()

        fun place(router: TopologyNode, block: Block, originX: Float, originY: Float) {
            val geometry = block.geometry
            val clusterX = originX + (block.width - geometry.clusterWidth) / 2f
            clusters += Cluster(router.id, Box(clusterX, originY, geometry.clusterWidth, geometry.clusterHeight))
            placedNodes[router.id] = Box(
                x = clusterX + (geometry.clusterWidth - ROUTER_W) / 2f,
                y = originY + CLUSTER_PAD,
                w = ROUTER_W,
                h = ROUTER_H,
            )

            val devices = devicesByRouter[router.id].orEmpty()
            if (devices.isNotEmpty()) {
                val gridX = clusterX + (geometry.clusterWidth - geometry.gridWidth) / 2f
                val gridY = originY + CLUSTER_PAD + ROUTER_H + CLUSTER_TOP_GAP
                devices.forEachIndexed { index, device ->
                    val row = index / geometry.cols
                    val col = index % geometry.cols
                    placedNodes[device.id] = Box(
                        x = gridX + col * (DEVICE_W + DEVICE_GAP_X),
                        y = gridY + row * (DEVICE_H + DEVICE_GAP_Y),
                        w = DEVICE_W,
                        h = DEVICE_H,
                    )
                }
            }

            if (block.children.isNotEmpty()) {
                val childAreaWidth = block.children.sumOf { it.second.width.toDouble() }.toFloat() +
                    (block.children.size - 1) * CHILD_GAP_X
                var childX = originX + (block.width - childAreaWidth) / 2f
                val childY = originY + geometry.clusterHeight + CHILD_GAP_Y
                for ((child, childBlock) in block.children) {
                    place(child, childBlock, childX, childY)
                    childX += childBlock.width + CHILD_GAP_X
                }
            }
        }

        val roots = routers.filter { it.parentId == null || !routerById.containsKey(it.parentId) }
        val rootBlocks = roots.map { it to measure(it) }
        val totalRootWidth = if (rootBlocks.isEmpty()) 0f else
            rootBlocks.sumOf { it.second.width.toDouble() }.toFloat() +
                (rootBlocks.size - 1) * ROOT_GAP_X
        val rootsY = INTERNET_H + INTERNET_GAP_Y
        val maxRootHeight = rootBlocks.maxOfOrNull { it.second.height } ?: 0f
        val width = max(INTERNET_W, totalRootWidth)
        val height = if (rootBlocks.isEmpty()) INTERNET_H else rootsY + maxRootHeight

        val internet = Box((width - INTERNET_W) / 2f, 0f, INTERNET_W, INTERNET_H)
        placedNodes[TopologyBuilder.INTERNET_ID] = internet

        var rootX = (width - totalRootWidth) / 2f
        for ((root, block) in rootBlocks) {
            place(root, block, rootX, rootsY)
            rootX += block.width + ROOT_GAP_X
        }

        val links = buildLinks(graph, placedNodes, clusters)
        return Layout(
            nodes = placedNodes,
            clusters = clusters,
            links = links,
            internet = internet,
            width = width,
            height = height,
        )
    }

    private fun buildLinks(
        graph: TopologyGraph,
        boxes: Map<String, Box>,
        clusters: List<Cluster>,
    ): List<Link> {
        val clusterByRouter = clusters.associateBy { it.routerId }
        val result = ArrayList<Link>()
        for (edge in graph.edges) {
            val to = graph.node(edge.to) ?: continue
            val toBox = boxes[edge.to] ?: continue
            when (to.kind) {
                NodeKind.DEVICE -> {
                    val parent = boxes[edge.from] ?: continue
                    result += Link(
                        from = edge.from,
                        to = edge.to,
                        x1 = parent.centerX,
                        y1 = parent.bottom,
                        x2 = toBox.centerX,
                        y2 = toBox.y,
                        link = edge.link,
                    )
                }

                NodeKind.ROUTER -> {
                    if (edge.from == TopologyBuilder.INTERNET_ID) {
                        val from = boxes[TopologyBuilder.INTERNET_ID] ?: continue
                        result += Link(
                            from = edge.from,
                            to = edge.to,
                            x1 = from.centerX,
                            y1 = from.bottom,
                            x2 = toBox.centerX,
                            y2 = toBox.y,
                            link = edge.link,
                        )
                    } else {
                        val fromCluster = clusterByRouter[edge.from] ?: continue
                        val toCluster = clusterByRouter[edge.to] ?: continue
                        result += Link(
                            from = edge.from,
                            to = edge.to,
                            x1 = fromCluster.box.centerX,
                            y1 = fromCluster.box.bottom,
                            x2 = toCluster.box.centerX,
                            y2 = toCluster.box.y,
                            link = edge.link,
                        )
                    }
                }

                NodeKind.INTERNET -> Unit
            }
        }
        return result
    }
}
