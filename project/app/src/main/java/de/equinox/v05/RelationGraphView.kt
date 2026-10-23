package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class RelationGraphView(context: Context) : View(context) {
    private data class P(var x: Float, var y: Float)

    private var snapshot = GraphSnapshot(emptyList(), emptyList())
    private var visibleEdges: List<GraphEdge> = emptyList()
    private val positions = mutableMapOf<Int, P>()

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val macroOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 24f
    }

    fun setSnapshot(newSnapshot: GraphSnapshot) {
        snapshot = newSnapshot
        // Renderer filter only: memory keeps every relation; the view keeps the strongest local strands.
        visibleEdges = newSnapshot.edges
            .sortedByDescending { it.weight }
            .fold(mutableListOf()) { acc: MutableList<GraphEdge>, e ->
                val fromCount = acc.count { it.fromId == e.fromId || it.toId == e.fromId }
                val toCount = acc.count { it.fromId == e.toId || it.toId == e.toId }
                if ((fromCount < 4 && toCount < 4) || e.isMacro) acc.add(e)
                acc
            }
            .take(110)
        rebuildLayout()
        invalidate()
    }

    private fun rebuildLayout() {
        positions.clear()
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) return

        val n = nodes.size
        for ((index, node) in nodes.withIndex()) {
            val angle = 2.0 * PI * index / max(1, n)
            val baseRadius = if (node.isMacro) 0.24f else 0.38f
            val jitter = ((node.id * 37 and 7) * 0.010f)
            positions[node.id] = P(
                0.5f + cos(angle).toFloat() * (baseRadius + jitter),
                0.5f + sin(angle).toFloat() * (baseRadius + jitter)
            )
        }

        repeat(95) {
            val dx = mutableMapOf<Int, Float>()
            val dy = mutableMapOf<Int, Float>()
            nodes.forEach { dx[it.id] = 0f; dy[it.id] = 0f }

            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val a = positions[nodes[i].id] ?: continue
                    val b = positions[nodes[j].id] ?: continue
                    var vx = a.x - b.x
                    var vy = a.y - b.y
                    val d2 = max(0.0025f, vx * vx + vy * vy)
                    val d = sqrt(d2)
                    vx /= d; vy /= d
                    val f = 0.00034f / d2
                    dx[nodes[i].id] = dx.getValue(nodes[i].id) + vx * f
                    dy[nodes[i].id] = dy.getValue(nodes[i].id) + vy * f
                    dx[nodes[j].id] = dx.getValue(nodes[j].id) - vx * f
                    dy[nodes[j].id] = dy.getValue(nodes[j].id) - vy * f
                }
            }

            val strongest = max(1L, visibleEdges.maxOfOrNull { it.weight } ?: 1L)
            for (edge in visibleEdges) {
                val a = positions[edge.fromId] ?: continue
                val b = positions[edge.toId] ?: continue
                val vx = b.x - a.x
                val vy = b.y - a.y
                val d = max(0.001f, sqrt(vx * vx + vy * vy))
                val normalized = edge.weight.toFloat() / strongest.toFloat()
                val desired = if (edge.isMacro) 0.15f else 0.28f - 0.14f * normalized
                val spring = if (edge.isMacro) 0.022f else 0.006f + 0.012f * normalized
                val f = (d - desired) * spring
                val ux = vx / d; val uy = vy / d
                dx[edge.fromId] = dx.getValue(edge.fromId) + ux * f
                dy[edge.fromId] = dy.getValue(edge.fromId) + uy * f
                dx[edge.toId] = dx.getValue(edge.toId) - ux * f
                dy[edge.toId] = dy.getValue(edge.toId) - uy * f
            }

            for (node in nodes) {
                val p = positions[node.id] ?: continue
                p.x = (p.x + dx.getValue(node.id)).coerceIn(0.07f, 0.93f)
                p.y = (p.y + dy.getValue(node.id)).coerceIn(0.10f, 0.90f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(10, 12, 16))

        val pad = 28f
        val w = max(1f, width.toFloat() - pad * 2)
        val h = max(1f, height.toFloat() - pad * 2)

        if (snapshot.nodes.isEmpty()) {
            textPaint.textSize = 32f
            textPaint.color = Color.LTGRAY
            canvas.drawText("Noch keine persistenten Codes", width / 2f, height / 2f, textPaint)
            return
        }

        val strongestEdge = max(1L, visibleEdges.maxOfOrNull { it.weight } ?: 1L)
        for (edge in visibleEdges.asReversed()) {
            val a = positions[edge.fromId] ?: continue
            val b = positions[edge.toId] ?: continue
            val x1 = pad + a.x * w
            val y1 = pad + a.y * h
            val x2 = pad + b.x * w
            val y2 = pad + b.y * h
            val dx = x2 - x1
            val dy = y2 - y1
            val dist = max(1f, sqrt(dx * dx + dy * dy))

            val kappa = (1.0 - exp(-0.08 * edge.weight.toDouble())).toFloat()
            val sign = if (((edge.fromId * 31 + edge.toId) and 1) == 0) 1f else -1f
            val nx = -dy / dist
            val ny = dx / dist
            val bendScale = if (edge.isMacro) 0.22f else 0.36f
            val bend = min(dist * 0.48f, 18f + dist * bendScale * kappa) * sign
            val cx = (x1 + x2) * 0.5f + nx * bend
            val cy = (y1 + y2) * 0.5f + ny * bend

            val norm = edge.weight.toFloat() / strongestEdge.toFloat()
            edgePaint.strokeWidth = if (edge.isMacro) 2.5f + 8f * sqrt(norm) else 1.2f + 6f * sqrt(norm)
            edgePaint.color = if (edge.isMacro) {
                Color.argb((100 + 155 * norm).toInt().coerceIn(0, 255), 255, 180, 70)
            } else {
                Color.argb((45 + 175 * norm).toInt().coerceIn(0, 255), 70, 220, 205)
            }

            val path = Path().apply {
                moveTo(x1, y1)
                quadTo(cx, cy, x2, y2)
            }
            canvas.drawPath(path, edgePaint)
        }

        val strongestNode = max(1L, snapshot.nodes.maxOfOrNull { it.weight } ?: 1L)
        for (node in snapshot.nodes) {
            val p = positions[node.id] ?: continue
            val x = pad + p.x * w
            val y = pad + p.y * h
            val normalized = node.weight.toFloat() / strongestNode.toFloat()
            val radius = if (node.isMacro) 22f + 24f * sqrt(normalized) else 10f + 18f * sqrt(normalized)

            nodePaint.color = if (node.isMacro) Color.rgb(245, 155, 45) else frequencyColor(node.representativeHz)
            if (node.isMacro) {
                // Organic four-lobed macro glyph: deliberately no debug ID in world view.
                val path = Path()
                val lobes = 8
                for (i in 0 until lobes) {
                    val a = 2.0 * PI * i / lobes
                    val rr = if (i % 2 == 0) radius * 1.35f else radius * 0.82f
                    val px = x + cos(a).toFloat() * rr
                    val py = y + sin(a).toFloat() * rr
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, nodePaint)
                canvas.drawPath(path, macroOutline)
            } else {
                // Micro nodes remain compact but no longer carry numeric labels.
                canvas.drawCircle(x, y, radius, nodePaint)
                val halo = Paint(nodePaint).apply { style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 90 }
                canvas.drawCircle(x, y, radius + 4f, halo)
            }
        }

        val microCount = snapshot.nodes.count { !it.isMacro }
        val macroCount = snapshot.nodes.count { it.isMacro }
        infoPaint.textSize = 21f
        infoPaint.color = Color.LTGRAY
        canvas.drawText(
            "$microCount Mikro · $macroCount Makro · ${visibleEdges.size}/${snapshot.edges.size} sichtbare Relationen",
            18f,
            height - 18f,
            infoPaint
        )
    }

    private fun frequencyColor(hz: Double): Int {
        if (hz <= 0.0) return Color.rgb(180, 180, 180)
        val minHz = 55.0
        val maxHz = 7040.0
        val t = ((ln(hz / minHz) / ln(maxHz / minHz)).coerceIn(0.0, 1.0)).toFloat()
        val hue = 10f + 275f * t
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 1.0f))
    }

    companion object {
        private const val MACRO_ID_BASE_FOR_VIEW = 1_000_000
    }
}
