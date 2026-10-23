package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Step 06 output-only canvas with read-only structural ranks.
 *
 * This view never mutates MemoryModel. It renders a read-only EngineOutputState.
 * Positions are projection coordinates derived from IDs and the active working set;
 * they are not stored as physical coordinates in the core.
 */
class EquinoxOutputView(context: Context) : View(context) {
    private var state: EngineOutputState? = null
    private var phase = 0f
    private var modeLabel = "LIVE"

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 235, 240, 255)
        textSize = 18f
    }
    private val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 245, 245, 255)
        textAlign = Paint.Align.CENTER
        textSize = 12f
    }

    fun setState(newState: EngineOutputState) {
        state = newState
        phase = (phase + 0.037f) % 1f
        invalidate()
    }

    fun setModeLabel(label: String) {
        modeLabel = label.trim().uppercase().ifEmpty { "OUTPUT" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(5, 7, 12))

        val s = state
        if (s == null) {
            drawCentered(canvas, "OUTPUT WARTET AUF CORE")
            return
        }

        val active = s.working.nodes
        if (active.isEmpty()) {
            drawMemoryField(canvas, s)
            infoPaint.color = Color.argb(150, 200, 210, 225)
            canvas.drawText("$modeLabel · noch kein aktives Makro", 18f, height - 18f, infoPaint)
            return
        }

        val pad = 38f
        val w = max(1f, width.toFloat() - 2f * pad)
        val h = max(1f, height.toFloat() - 2f * pad)

        val positions = active.associate { node ->
            node.id to positionFor(node, pad, w, h)
        }

        // Draw overexchange first. Positive/negative exchange gets opposite hue bias.
        s.working.exchanges
            .sortedBy { abs(it.value) }
            .forEach { ex ->
                val a = positions[ex.fromId] ?: return@forEach
                val b = positions[ex.toId] ?: return@forEach
                drawExchange(canvas, a.first, a.second, b.first, b.second, ex.value, ex.fromId, ex.toId)
            }

        // Nodes are an output glyph of actual activation + retrieved expectation.
        active.sortedBy { it.abstractionLevel }.forEach { node ->
            val p = positions[node.id] ?: return@forEach
            drawActiveNode(canvas, p.first, p.second, node)
        }

        val meanR = if (s.working.exchanges.isEmpty()) 0.0 else
            s.working.exchanges.sumOf { abs(it.value) } / s.working.exchanges.size.toDouble()
        infoPaint.color = Color.argb(175, 215, 225, 245)
        val topRank = active.maxByOrNull { it.structuralScore }?.structuralRank ?: "—"
        canvas.drawText(
            "$modeLabel · ${active.size} aktiv · ${s.working.exchanges.size} Austausch · Ø|R| ${"%.3f".format(meanR)} · max $topRank",
            18f,
            height - 18f,
            infoPaint
        )
    }

    private fun drawMemoryField(canvas: Canvas, s: EngineOutputState) {
        val nodes = s.graph.nodes.filter { it.abstractionLevel > 0 }.take(36)
        if (nodes.isEmpty()) {
            drawCentered(canvas, "NOCH KEINE MAKROSTRUKTUR")
            return
        }

        val cx = width * 0.5f
        val cy = height * 0.48f
        val radius = min(width, height) * 0.34f
        nodes.forEachIndexed { index, node ->
            val a = 2.0 * PI * index / max(1, nodes.size) + phase * 2.0 * PI * 0.08
            val rr = radius * (0.45f + 0.55f * ((node.id ushr 3) and 255) / 255f)
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr
            val r = 3.5f + 2.0f * node.abstractionLevel + min(6f, node.structuralRankIndex * 0.65f)
            nodePaint.color = withAlpha(levelColor(node.abstractionLevel), 95)
            canvas.drawCircle(x, y, r, nodePaint)
        }
    }

    private fun positionFor(node: ActiveMacroNode, pad: Float, w: Float, h: Float): Pair<Float, Float> {
        val h1 = mix(node.id.toLong() * 0x9E3779B97F4A7C15UL.toLong())
        val h2 = mix(h1 xor 0xC2B2AE3D27D4EB4FUL.toLong())
        val nx = ((h1 ushr 11) and 0xffff).toFloat() / 65535f
        val ny = ((h2 ushr 13) and 0xffff).toFloat() / 65535f

        // Higher abstractions drift closer to the visual centre; this is renderer-only.
        val centrePull = (0.08f * node.abstractionLevel).coerceAtMost(0.32f)
        val xNorm = nx * (1f - centrePull) + 0.5f * centrePull
        val yNorm = ny * (1f - centrePull) + 0.5f * centrePull

        // Exchange bias creates a tiny live visual displacement, never persisted.
        val bias = node.exchangeBias.coerceIn(-1.5, 1.5).toFloat()
        val dx = sin((node.id % 97) * 0.31f + phase * 6.28f) * 0.025f * bias
        val dy = cos((node.id % 83) * 0.27f + phase * 6.28f) * 0.025f * bias

        return Pair(
            pad + (xNorm + dx).coerceIn(0.03f, 0.97f) * w,
            pad + (yNorm + dy).coerceIn(0.03f, 0.97f) * h
        )
    }

    private fun drawExchange(
        canvas: Canvas,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        value: Double,
        fromId: Int,
        toId: Int
    ) {
        val dx = bx - ax
        val dy = by - ay
        val d = max(1f, sqrt(dx * dx + dy * dy))
        val nx = -dy / d
        val ny = dx / d
        val magnitude = abs(value).coerceIn(0.0, 2.0).toFloat()
        val sign = if (value >= 0.0) 1f else -1f
        val deterministic = if (((fromId * 31 + toId) and 1) == 0) 1f else -1f
        val bend = min(d * 0.35f, 18f + d * 0.18f * magnitude) * sign * deterministic
        val cx = (ax + bx) * 0.5f + nx * bend
        val cy = (ay + by) * 0.5f + ny * bend

        linePaint.strokeWidth = 1.5f + 5.5f * sqrt(magnitude.coerceAtLeast(0.02f))
        val alpha = (70 + 155 * min(1f, magnitude)).toInt().coerceIn(0, 255)
        linePaint.color = if (value >= 0.0) {
            Color.argb(alpha, 70, 220, 255)
        } else {
            Color.argb(alpha, 255, 90, 185)
        }

        val path = Path().apply {
            moveTo(ax, ay)
            quadTo(cx, cy, bx, by)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawActiveNode(canvas: Canvas, x: Float, y: Float, node: ActiveMacroNode) {
        val a = node.activation.coerceIn(0.0, 1.5).toFloat()
        val e = node.expectation.coerceIn(0.0, 1.5).toFloat()
        val base = 7f + node.abstractionLevel * 3.5f
        val rankBoost = min(0.55f, node.structuralRankIndex * 0.045f)
        val radius = base * (1f + 0.70f * a + rankBoost)
        val color = levelColor(node.abstractionLevel)

        if (e > 0.01f) {
            haloPaint.color = withAlpha(color, (45 + 110 * min(1f, e)).toInt())
            haloPaint.strokeWidth = 1.5f + 5f * e
            canvas.drawCircle(x, y, radius + 7f + 12f * e, haloPaint)
        }

        if (node.retrieved) {
            haloPaint.color = Color.argb(80, 235, 240, 255)
            haloPaint.strokeWidth = 2f
            canvas.drawCircle(x, y, radius + 3f, haloPaint)
        }

        nodePaint.color = withAlpha(color, (125 + 120 * min(1f, 0.55f + a)).toInt().coerceIn(0, 255))
        canvas.drawCircle(x, y, radius, nodePaint)

        if (abs(node.exchangeBias) > 0.03) {
            val dir = if (node.exchangeBias >= 0.0) 1f else -1f
            linePaint.color = if (dir > 0) Color.argb(170, 90, 225, 255) else Color.argb(170, 255, 100, 190)
            linePaint.strokeWidth = 2.5f
            val arcR = radius + 11f
            canvas.drawArc(x - arcR, y - arcR, x + arcR, y + arcR, if (dir > 0) -70f else 110f, 125f * dir, false, linePaint)
        }

        // RPG-style rank is a derived output annotation only; it never feeds back into learning.
        if (node.structuralRankIndex >= 5) {
            if (node.structuralRankIndex >= 6) {
                haloPaint.color = Color.argb((70 + min(120, node.structuralRankIndex * 8)).coerceAtMost(190), 245, 245, 255)
                haloPaint.strokeWidth = 1.2f + min(3.5f, (node.structuralRankIndex - 5) * 0.35f)
                canvas.drawCircle(x, y, radius + 17f + min(10f, node.structuralRankIndex.toFloat()), haloPaint)
            }
            rankPaint.textSize = 11f + min(5f, (node.structuralRankIndex - 5) * 0.7f)
            canvas.drawText(node.structuralRank, x, y - radius - 8f, rankPaint)
        }
    }

    private fun drawCentered(canvas: Canvas, text: String) {
        infoPaint.textAlign = Paint.Align.CENTER
        infoPaint.textSize = 22f
        infoPaint.color = Color.argb(150, 205, 215, 230)
        canvas.drawText(text, width * 0.5f, height * 0.5f, infoPaint)
        infoPaint.textAlign = Paint.Align.LEFT
        infoPaint.textSize = 18f
    }

    private fun levelColor(level: Int): Int = when (level) {
        1 -> Color.rgb(255, 176, 68)
        2 -> Color.rgb(255, 92, 176)
        3 -> Color.rgb(125, 105, 255)
        else -> Color.rgb(226, 82, 255)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun mix(x0: Long): Long {
        var x = x0
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }
}
