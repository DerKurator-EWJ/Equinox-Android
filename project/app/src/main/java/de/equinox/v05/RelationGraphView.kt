package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    private val positions = mutableMapOf<Int, P>()

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
        rebuildLayout()
        invalidate()
    }

    private fun rebuildLayout() {
        positions.clear()
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) return

        // Initial coordinates are only a deterministic seed for the renderer.
        // The relation graph then relaxes them; the model itself stores no x/y.
        val n = nodes.size
        for ((index, node) in nodes.withIndex()) {
            val angle = 2.0 * PI * index / max(1, n)
            val radius = 0.34f + ((node.id * 37 and 7) * 0.012f)
            positions[node.id] = P(
                0.5f + cos(angle).toFloat() * radius,
                0.5f + sin(angle).toFloat() * radius
            )
        }

        // Lightweight force relaxation based only on graph relations.
        repeat(90) {
            val dx = mutableMapOf<Int, Float>()
            val dy = mutableMapOf<Int, Float>()
            nodes.forEach { dx[it.id] = 0f; dy[it.id] = 0f }

            // Repulsion keeps unrelated codes apart.
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val a = positions[nodes[i].id] ?: continue
                    val b = positions[nodes[j].id] ?: continue
                    var vx = a.x - b.x
                    var vy = a.y - b.y
                    val d2 = max(0.0025f, vx * vx + vy * vy)
                    val d = sqrt(d2)
                    vx /= d; vy /= d
                    val f = 0.00035f / d2
                    dx[nodes[i].id] = dx.getValue(nodes[i].id) + vx * f
                    dy[nodes[i].id] = dy.getValue(nodes[i].id) + vy * f
                    dx[nodes[j].id] = dx.getValue(nodes[j].id) - vx * f
                    dy[nodes[j].id] = dy.getValue(nodes[j].id) - vy * f
                }
            }

            // Stronger relations pull codes closer together.
            val strongest = max(1L, snapshot.edges.maxOfOrNull { it.weight } ?: 1L)
            for (edge in snapshot.edges) {
                val a = positions[edge.fromId] ?: continue
                val b = positions[edge.toId] ?: continue
                val vx = b.x - a.x
                val vy = b.y - a.y
                val d = max(0.001f, sqrt(vx * vx + vy * vy))
                val normalized = edge.weight.toFloat() / strongest.toFloat()
                val desired = 0.29f - 0.16f * normalized
                val f = (d - desired) * (0.005f + 0.012f * normalized)
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

        val strongestEdge = max(1L, snapshot.edges.maxOfOrNull { it.weight } ?: 1L)

        for (edge in snapshot.edges.asReversed()) {
            val a = positions[edge.fromId] ?: continue
            val b = positions[edge.toId] ?: continue
            val x1 = pad + a.x * w
            val y1 = pad + a.y * h
            val x2 = pad + b.x * w
            val y2 = pad + b.y * h
            val dx = x2 - x1
            val dy = y2 - y1
            val dist = max(1f, sqrt(dx * dx + dy * dy))

            // kappa(w) = 1 - exp(-0.08 w): repeated interaction bends the visual relation.
            val kappa = (1.0 - exp(-0.08 * edge.weight.toDouble())).toFloat()
            val sign = if (((edge.fromId * 31 + edge.toId) and 1) == 0) 1f else -1f
            val nx = -dy / dist
            val ny = dx / dist
            val bend = min(dist * 0.48f, 18f + dist * 0.36f * kappa) * sign
            val cx = (x1 + x2) * 0.5f + nx * bend
            val cy = (y1 + y2) * 0.5f + ny * bend

            val norm = edge.weight.toFloat() / strongestEdge.toFloat()
            edgePaint.strokeWidth = 1.5f + 7f * sqrt(norm)
            edgePaint.color = Color.argb((55 + 190 * norm).toInt().coerceIn(0, 255), 70, 220, 205)

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
            val radius = 11f + 20f * sqrt(normalized)
            nodePaint.color = frequencyColor(node.representativeHz)
            canvas.drawCircle(x, y, radius, nodePaint)

            textPaint.textSize = 20f
            textPaint.color = Color.WHITE
            canvas.drawText(node.id.toString(), x, y + 7f, textPaint)
        }

        infoPaint.textSize = 22f
        infoPaint.color = Color.LTGRAY
        canvas.drawText(
            "${snapshot.nodes.size} Codes · ${snapshot.edges.size} sichtbare Relationen",
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
        // HSV hue 10° -> 285° across logarithmic frequency range.
        val hue = 10f + 275f * t
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 1.0f))
    }
}
