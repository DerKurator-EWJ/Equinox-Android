package de.equinox.v05

import kotlin.math.*
import java.util.Random

data class VisualNodeState(
    val id: Long,
    var x: Float,
    var y: Float,
    var z: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    var importance: Float = 1f,
    var abstractionLevel: Int = 0,
    var modality: String = "audio",
    var displayColor: Int = 0,
    var weight: Double = 1.0,
    var isMacro: Boolean = false
)

data class VisualEdgeState(
    val from: Long,
    val to: Long,
    val weight: Double,
    val kind: String,
    val abstractionLevel: Int
)

data class VisualFiber(
    val from: VisualNodeState,
    val to: VisualNodeState,
    val control1X: Float,
    val control1Y: Float,
    val control2X: Float,
    val control2Y: Float,
    val strength: Float,
    val bundleKey: Int
)

class VisualGraphModel {
    private val nodes = LinkedHashMap<Long, VisualNodeState>()
    private var edges: List<VisualEdgeState> = emptyList()
    private val random = Random(14L)

    companion object {
        private const val MAX_VISIBLE_NODES = 140
        private const val MAX_VISIBLE_EDGES = 280
        private const val RELAX_STEPS = 8
    }

    fun update(snapshot: GraphSnapshot) {
        val sortedNodes = snapshot.nodes
            .sortedByDescending { it.weight }
            .take(MAX_VISIBLE_NODES)

        val keep = sortedNodes.map { it.id }.toHashSet()

        val stale = nodes.keys.filter { it !in keep }
        stale.forEach { nodes.remove(it) }

        sortedNodes.forEachIndexed { index, n ->
            val existing = nodes[n.id]
            if (existing == null) {
                val angle = index * 2.3999632f
                val radius = 0.15f + 0.62f * sqrt((index + 1f) / max(1f, sortedNodes.size.toFloat()))
                nodes[n.id] = VisualNodeState(
                    id = n.id,
                    x = 0.5f + cos(angle) * radius * 0.5f,
                    y = 0.5f + sin(angle) * radius * 0.5f,
                    z = deterministicZ(n.id, n.abstractionLevel),
                    importance = importanceFor(n.weight, n.abstractionLevel),
                    abstractionLevel = n.abstractionLevel,
                    modality = n.modality,
                    displayColor = n.displayColor,
                    weight = n.weight,
                    isMacro = n.isMacro
                )
            } else {
                existing.importance = importanceFor(n.weight, n.abstractionLevel)
                existing.abstractionLevel = n.abstractionLevel
                existing.modality = n.modality
                existing.displayColor = n.displayColor
                existing.weight = n.weight
                existing.isMacro = n.isMacro
            }
        }

        edges = snapshot.edges
            .asSequence()
            .filter { it.from in keep && it.to in keep }
            .sortedByDescending { it.weight }
            .take(MAX_VISIBLE_EDGES)
            .map {
                VisualEdgeState(
                    from = it.from,
                    to = it.to,
                    weight = it.weight,
                    kind = it.kind,
                    abstractionLevel = it.abstractionLevel
                )
            }
            .toList()

        repeat(RELAX_STEPS) { relaxOnce() }
        normalize()
    }

    fun visibleNodes(): List<VisualNodeState> = nodes.values.toList()

    fun visibleEdges(): List<VisualEdgeState> = edges

    fun fibers(width: Float, height: Float): List<VisualFiber> {
        return edges.mapNotNull { edge ->
            val a = nodes[edge.from] ?: return@mapNotNull null
            val b = nodes[edge.to] ?: return@mapNotNull null

            val ax = a.x * width
            val ay = a.y * height
            val bx = b.x * width
            val by = b.y * height

            val dx = bx - ax
            val dy = by - ay
            val dist = max(1f, hypot(dx, dy))
            val nx = -dy / dist
            val ny = dx / dist

            val bundleKey = bundleKey(a, b, edge)
            val bundleBias = ((bundleKey % 7) - 3) * 0.035f
            val strength = edgeStrength(edge.weight)

            val curvature = (18f + 70f * strength) * bundleBias
            val c1x = ax + dx * 0.33f + nx * curvature
            val c1y = ay + dy * 0.33f + ny * curvature
            val c2x = ax + dx * 0.66f + nx * curvature
            val c2y = ay + dy * 0.66f + ny * curvature

            VisualFiber(
                from = a,
                to = b,
                control1X = c1x,
                control1Y = c1y,
                control2X = c2x,
                control2Y = c2y,
                strength = strength,
                bundleKey = bundleKey
            )
        }
    }

    private fun relaxOnce() {
        val values = nodes.values.toList()
        if (values.isEmpty()) return

        // Repulsion between visible nodes.
        for (i in values.indices) {
            val a = values[i]
            for (j in i + 1 until values.size) {
                val b = values[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                var d2 = dx * dx + dy * dy
                if (d2 < 0.0006f) {
                    dx += (random.nextFloat() - 0.5f) * 0.02f
                    dy += (random.nextFloat() - 0.5f) * 0.02f
                    d2 = dx * dx + dy * dy
                }

                val rep = 0.00013f / max(0.0006f, d2)
                a.vx -= dx * rep
                a.vy -= dy * rep
                b.vx += dx * rep
                b.vy += dy * rep
            }
        }

        // Attraction follows relations, with abstraction-aware preferred length.
        edges.forEach { e ->
            val a = nodes[e.from] ?: return@forEach
            val b = nodes[e.to] ?: return@forEach
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = max(0.001f, hypot(dx, dy))
            val level = max(a.abstractionLevel, b.abstractionLevel)
            val target = when {
                level >= 4 -> 0.30f
                level == 3 -> 0.24f
                level == 2 -> 0.19f
                level == 1 -> 0.15f
                else -> 0.105f
            }
            val pull = (d - target) * (0.0025f + edgeStrength(e.weight) * 0.010f)
            val fx = dx / d * pull
            val fy = dy / d * pull

            a.vx += fx
            a.vy += fy
            b.vx -= fx
            b.vy -= fy
        }

        // Macro nodes pull weakly toward center, helping tissue-like hierarchy.
        values.forEach { n ->
            val centerPull = if (n.abstractionLevel > 0) 0.0032f else 0.0011f
            n.vx += (0.5f - n.x) * centerPull
            n.vy += (0.5f - n.y) * centerPull

            n.vx *= 0.82f
            n.vy *= 0.82f
            n.x += n.vx
            n.y += n.vy
        }
    }

    private fun normalize() {
        nodes.values.forEach { n ->
            n.x = n.x.coerceIn(0.06f, 0.94f)
            n.y = n.y.coerceIn(0.06f, 0.94f)
        }
    }

    private fun importanceFor(weight: Double, level: Int): Float {
        val w = ln(1.0 + max(0.0, weight)).toFloat()
        return (0.5f + w * 0.22f + level * 0.30f).coerceIn(0.5f, 4.5f)
    }

    private fun edgeStrength(weight: Double): Float {
        return (1.0 - exp(-0.055 * max(0.0, weight))).toFloat().coerceIn(0.05f, 1f)
    }

    private fun deterministicZ(id: Long, level: Int): Float {
        var x = id xor (id ushr 33)
        x *= -49064778989728563L
        x = x xor (x ushr 29)
        val unit = ((x and 0xFFFF).toFloat() / 65535f) - 0.5f
        return unit * 0.55f + level * 0.055f
    }

    private fun bundleKey(a: VisualNodeState, b: VisualNodeState, e: VisualEdgeState): Int {
        val l = max(a.abstractionLevel, b.abstractionLevel)
        val modality = if (a.modality == b.modality) a.modality.hashCode() else 97
        return (l * 31 + modality * 7 + e.kind.hashCode()).absoluteValue
    }
}
