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
    private var visibleEdges: List<GraphEdge> = emptyList()
    private val positions = mutableMapOf<Int, P>()

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 21f
    }

    fun setSnapshot(newSnapshot: GraphSnapshot) {
        snapshot = newSnapshot

        visibleEdges = newSnapshot.edges
            .sortedByDescending { it.weight }
            .fold(mutableListOf()) { acc: MutableList<GraphEdge>, e ->
                val fromCount = acc.count { it.fromId == e.fromId || it.toId == e.fromId }
                val toCount = acc.count { it.fromId == e.toId || it.toId == e.toId }
                val limit = if (e.abstractionLevel >= 2 || e.isMembership) 6 else 4
                if (fromCount < limit && toCount < limit) acc.add(e)
                acc
            }
            .take(120)

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
            val ring = when (node.abstractionLevel) {
                0 -> 0.40f
                1 -> 0.30f
                2 -> 0.22f
                3 -> 0.15f
                else -> 0.09f
            }
            val jitter = ((node.id * 37 and 7) * 0.008f)
            positions[node.id] = P(
                0.5f + cos(angle).toFloat() * (ring + jitter),
                0.5f + sin(angle).toFloat() * (ring + jitter)
            )
        }

        repeat(105) {
            val dx = mutableMapOf<Int, Float>()
            val dy = mutableMapOf<Int, Float>()
            nodes.forEach {
                dx[it.id] = 0f
                dy[it.id] = 0f
            }

            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val aNode = nodes[i]
                    val bNode = nodes[j]
                    val a = positions[aNode.id] ?: continue
                    val b = positions[bNode.id] ?: continue

                    var vx = a.x - b.x
                    var vy = a.y - b.y
                    val d2 = max(0.0025f, vx * vx + vy * vy)
                    val d = sqrt(d2)
                    vx /= d
                    vy /= d

                    val levelBoost = 1f + 0.15f * (aNode.abstractionLevel + bNode.abstractionLevel)
                    val f = 0.00030f * levelBoost / d2

                    dx[aNode.id] = dx.getValue(aNode.id) + vx * f
                    dy[aNode.id] = dy.getValue(aNode.id) + vy * f
                    dx[bNode.id] = dx.getValue(bNode.id) - vx * f
                    dy[bNode.id] = dy.getValue(bNode.id) - vy * f
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

                val desired = when {
                    edge.isMembership -> 0.11f
                    edge.abstractionLevel >= 3 -> 0.13f
                    edge.abstractionLevel == 2 -> 0.16f
                    edge.abstractionLevel == 1 -> 0.20f
                    else -> 0.28f - 0.13f * normalized
                }

                val spring = if (edge.abstractionLevel > 0 || edge.isMembership) {
                    0.020f
                } else {
                    0.006f + 0.012f * normalized
                }

                val f = (d - desired) * spring
                val ux = vx / d
                val uy = vy / d

                dx[edge.fromId] = dx.getValue(edge.fromId) + ux * f
                dy[edge.fromId] = dy.getValue(edge.fromId) + uy * f
                dx[edge.toId] = dx.getValue(edge.toId) - ux * f
                dy[edge.toId] = dy.getValue(edge.toId) - uy * f
            }

            for (node in nodes) {
                val p = positions[node.id] ?: continue
                p.x = (p.x + dx.getValue(node.id)).coerceIn(0.07f, 0.93f)
                p.y = (p.y + dy.getValue(node.id)).coerceIn(0.09f, 0.91f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 11, 15))

        val pad = 28f
        val w = max(1f, width.toFloat() - pad * 2)
        val h = max(1f, height.toFloat() - pad * 2)

        if (snapshot.nodes.isEmpty()) {
            textPaint.textSize = 32f
            textPaint.color = Color.LTGRAY
            canvas.drawText(
                "Noch keine persistenten Codes",
                width / 2f,
                height / 2f,
                textPaint
            )
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

            val bendScale = when {
                edge.isMembership -> 0.12f
                edge.abstractionLevel >= 3 -> 0.16f
                edge.abstractionLevel == 2 -> 0.20f
                edge.abstractionLevel == 1 -> 0.24f
                else -> 0.36f
            }

            val bend = min(
                dist * 0.46f,
                14f + dist * bendScale * kappa
            ) * sign

            val cx = (x1 + x2) * 0.5f + nx * bend
            val cy = (y1 + y2) * 0.5f + ny * bend

            val norm = edge.weight.toFloat() / strongestEdge.toFloat()
            edgePaint.strokeWidth = when {
                edge.isMembership -> 1.8f + 4.5f * sqrt(norm)
                edge.abstractionLevel >= 2 -> 3.0f + 8.0f * sqrt(norm)
                edge.abstractionLevel == 1 -> 2.3f + 7.0f * sqrt(norm)
                else -> 1.1f + 5.5f * sqrt(norm)
            }

            edgePaint.color = edgeColor(edge, norm)

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
            val baseRadius = 10f + 17f * sqrt(normalized)

            if (node.abstractionLevel == 0) {
                nodePaint.color = frequencyColor(node.representativeHz)
                canvas.drawCircle(x, y, baseRadius, nodePaint)
                val halo = Paint(nodePaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    alpha = 80
                }
                canvas.drawCircle(x, y, baseRadius + 4f, halo)
            } else {
                val radius = baseRadius + 8f + node.abstractionLevel * 4f
                nodePaint.color = levelColor(node.abstractionLevel, node.representativeHz)
                if (node.kind == "sequence") {
                    drawSequenceGlyph(
                        canvas = canvas,
                        x = x,
                        y = y,
                        radius = radius,
                        level = node.abstractionLevel
                    )
                } else {
                    drawMacroGlyph(
                        canvas = canvas,
                        x = x,
                        y = y,
                        radius = radius,
                        level = node.abstractionLevel
                    )
                }
            }
        }

        val microCount = snapshot.nodes.count { it.abstractionLevel == 0 }
        val levels = snapshot.nodes
            .filter { it.abstractionLevel > 0 }
            .groupingBy { it.abstractionLevel }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(" · ") { "L${it.key}:${it.value}" }

        infoPaint.color = Color.LTGRAY
        infoPaint.textSize = 20f
        val suffix = if (levels.isBlank()) "keine Makroebene" else levels
        canvas.drawText(
            "$microCount Mikro · $suffix · ${visibleEdges.size}/${snapshot.edges.size} Relationen",
            18f,
            height - 18f,
            infoPaint
        )
    }

    private fun drawSequenceGlyph(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        level: Int
    ) {
        val path = Path()
        val halfW = radius * 1.35f
        val halfH = radius * 0.72f

        path.moveTo(x - halfW, y)
        path.cubicTo(
            x - radius * 0.70f, y - halfH,
            x + radius * 0.70f, y - halfH,
            x + halfW, y
        )
        path.cubicTo(
            x + radius * 0.70f, y + halfH,
            x - radius * 0.70f, y + halfH,
            x - halfW, y
        )
        path.close()

        canvas.drawPath(path, nodePaint)

        outlinePaint.alpha = (220 - level * 18).coerceAtLeast(110)
        outlinePaint.strokeWidth = 2.8f + level * 0.5f
        canvas.drawPath(path, outlinePaint)

        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(175, 255, 255, 255)
        }

        canvas.drawLine(x - radius * 0.55f, y, x + radius * 0.55f, y, innerPaint)
        canvas.drawCircle(x - radius * 0.42f, y, radius * 0.11f, innerPaint)
        canvas.drawCircle(x, y, radius * 0.11f, innerPaint)
        canvas.drawCircle(x + radius * 0.42f, y, radius * 0.11f, innerPaint)
    }

    private fun drawMacroGlyph(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        level: Int
    ) {
        val points = when (level) {
            1 -> 8
            2 -> 10
            3 -> 12
            else -> 14
        }

        val path = Path()
        for (i in 0 until points) {
            val a = 2.0 * PI * i / points - PI / 2.0
            val pulse = when {
                level == 1 -> if (i % 2 == 0) 1.22f else 0.82f
                level == 2 -> if (i % 2 == 0) 1.28f else 0.72f
                level == 3 -> 0.88f + 0.25f * (1f + sin(i * 1.7).toFloat())
                else -> if (i % 3 == 0) 1.35f else 0.80f
            }
            val rr = radius * pulse
            val px = x + cos(a).toFloat() * rr
            val py = y + sin(a).toFloat() * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()

        canvas.drawPath(path, nodePaint)

        outlinePaint.alpha = (210 - level * 20).coerceAtLeast(100)
        outlinePaint.strokeWidth = 2.5f + level * 0.5f
        canvas.drawPath(path, outlinePaint)

        if (level >= 2) {
            val inner = radius * (0.28f + level * 0.05f)
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(170, 255, 255, 255)
            }
            canvas.drawCircle(x, y, inner, innerPaint)
        }
    }

    private fun edgeColor(edge: GraphEdge, norm: Float): Int {
        val alpha = (55 + 190 * norm).toInt().coerceIn(0, 255)
        return when {
            edge.isMembership -> Color.argb(alpha, 235, 155, 55)
            edge.abstractionLevel >= 4 -> Color.argb(alpha, 235, 90, 255)
            edge.abstractionLevel == 3 -> Color.argb(alpha, 120, 110, 255)
            edge.abstractionLevel == 2 -> Color.argb(alpha, 255, 95, 180)
            edge.abstractionLevel == 1 -> Color.argb(alpha, 255, 180, 70)
            else -> Color.argb(alpha, 70, 220, 205)
        }
    }

    private fun levelColor(level: Int, hz: Double): Int {
        return when (level) {
            1 -> Color.rgb(245, 155, 45)
            2 -> Color.rgb(235, 75, 155)
            3 -> Color.rgb(105, 95, 245)
            else -> Color.rgb(210, 85, 245)
        }
    }

    private fun frequencyColor(hz: Double): Int {
        if (hz <= 0.0) return Color.rgb(180, 180, 180)
        val minHz = 55.0
        val maxHz = 7040.0
        val t = ((ln(hz / minHz) / ln(maxHz / minHz)).coerceIn(0.0, 1.0)).toFloat()
        val hue = 10f + 275f * t
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 1.0f))
    }
}
