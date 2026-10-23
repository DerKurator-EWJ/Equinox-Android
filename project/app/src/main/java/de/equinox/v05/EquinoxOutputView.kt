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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Step 29 CANVAS renderer.
 *
 * This is deliberately no longer a hub-and-spoke projection of the momentary exchange set.
 * Learned relations define local renderer-only islands/tissue; prediction and overexchange are
 * overlays on top of that structure. Coordinates never enter MemoryModel.
 */
class EquinoxOutputView(context: Context) : View(context) {
    private var state: EngineOutputState? = null
    private var phase = 0f
    private var modeLabel = "CANVAS"

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val islandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 220, 228, 242)
        textSize = 14f
    }
    private val tierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 245, 245, 255)
        textAlign = Paint.Align.CENTER
        textSize = 11f
    }

    fun setState(newState: EngineOutputState) {
        state = newState
        phase = (phase + 0.017f) % 1f
        invalidate()
    }

    fun setModeLabel(label: String) {
        modeLabel = label.trim().uppercase().ifEmpty { "CANVAS" }
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
            drawFooter(canvas, "$modeLabel · noch kein aktives Makro")
            return
        }

        val activeIds = active.asSequence().map { it.id }.toHashSet()
        val structuralEdges = s.graph.edges
            .asSequence()
            .filter { !it.isMembership && it.fromId in activeIds && it.toId in activeIds && it.fromId != it.toId }
            .toList()

        val pad = 28f
        val w = max(1f, width.toFloat() - 2f * pad)
        val h = max(1f, height.toFloat() - 2f * pad - 58f)
        val layout = buildTissueLayout(active, structuralEdges, s.working.crossModalLinks, pad, w, h)

        // Soft islands first. They show that the active state consists of several local contexts,
        // not one privileged central node.
        layout.islands.forEach { island ->
            if (island.ids.size < 3) return@forEach
            val alpha = (12 + min(30, island.ids.size * 2)).coerceAtMost(38)
            islandPaint.color = Color.argb(alpha, 80, 110, 145)
            canvas.drawCircle(island.cx, island.cy, island.radius, islandPaint)
            haloPaint.color = Color.argb(alpha + 12, 120, 150, 185)
            haloPaint.strokeWidth = 1f
            canvas.drawCircle(island.cx, island.cy, island.radius, haloPaint)
        }

        // Learned relation tissue is the primary geometry.
        val relationBudget = max(80, active.size * 3)
        structuralEdges
            .sortedByDescending { it.weight }
            .take(relationBudget)
            .forEach { edge ->
                val a = layout.positions[edge.fromId] ?: return@forEach
                val b = layout.positions[edge.toId] ?: return@forEach
                drawStructuralRelation(canvas, a, b, edge)
            }

        // Cross-modal bridges remain explicit because they join two sensory families.
        s.working.crossModalLinks.forEach { link ->
            val joint = layout.positions[link.jointId] ?: return@forEach
            val audio = layout.positions[link.audioId] ?: return@forEach
            val visual = layout.positions[link.visualId] ?: return@forEach
            drawCrossModalBridge(canvas, audio, visual, joint, link.confidence)
        }

        // Prediction is secondary and intentionally thin.
        s.working.predictions.forEach { prediction ->
            val a = layout.positions[prediction.sourceId] ?: return@forEach
            val b = layout.positions[prediction.targetId] ?: return@forEach
            drawPrediction(canvas, a, b, prediction)
        }

        // Overexchange used to dominate the whole canvas as spokes from one hot point. In Step 29
        // it is a sparse overlay: show only the strongest currently informative exchanges.
        val exchangeBudget = max(10, sqrt(active.size.toDouble()).toInt() * 2)
        s.working.exchanges
            .sortedByDescending { abs(it.value) }
            .take(exchangeBudget)
            .forEach { ex ->
                val a = layout.positions[ex.fromId] ?: return@forEach
                val b = layout.positions[ex.toId] ?: return@forEach
                drawExchange(canvas, a, b, ex)
            }

        active.sortedBy { it.abstractionLevel }.forEach { node ->
            val p = layout.positions[node.id] ?: return@forEach
            drawActiveNode(canvas, p.first, p.second, node)
        }

        val meanR = if (s.working.exchanges.isEmpty()) 0.0 else
            s.working.exchanges.sumOf { abs(it.value) } / s.working.exchanges.size.toDouble()
        val topTier = active.maxByOrNull { it.abstractionLevel }
            ?.let { AbstractionTier.labelForMacroDepth(it.abstractionLevel) } ?: "—"
        drawFooter(
            canvas,
            "$modeLabel · ${active.size} aktiv · ${layout.islands.size} Felder · ${structuralEdges.size} Relationen · " +
                "${s.working.predictions.size} Prognosen · Ø|R| ${"%.3f".format(meanR)} · max Tier $topTier"
        )
    }

    private data class Island(val ids: List<Int>, val cx: Float, val cy: Float, val radius: Float)
    private data class TissueLayout(
        val positions: Map<Int, Pair<Float, Float>>,
        val islands: List<Island>
    )

    private fun buildTissueLayout(
        active: List<ActiveMacroNode>,
        edges: List<GraphEdge>,
        crossLinks: List<ActiveCrossModalLink>,
        pad: Float,
        w: Float,
        h: Float
    ): TissueLayout {
        val byId = active.associateBy { it.id }
        val adjacency = active.associate { it.id to mutableSetOf<Int>() }.toMutableMap()
        val degree = active.associate { it.id to 0 }.toMutableMap()

        fun connect(a: Int, b: Int) {
            if (a == b || a !in byId || b !in byId) return
            if (adjacency.getValue(a).add(b)) degree[a] = (degree[a] ?: 0) + 1
            if (adjacency.getValue(b).add(a)) degree[b] = (degree[b] ?: 0) + 1
        }

        edges.forEach { connect(it.fromId, it.toId) }
        crossLinks.forEach {
            connect(it.jointId, it.audioId)
            connect(it.jointId, it.visualId)
        }

        val unvisited = active.map { it.id }.toMutableSet()
        val components = mutableListOf<List<Int>>()
        while (unvisited.isNotEmpty()) {
            val seed = unvisited.minOrNull() ?: break
            val queue = ArrayDeque<Int>()
            val comp = mutableListOf<Int>()
            queue.add(seed)
            unvisited.remove(seed)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                comp += id
                adjacency[id].orEmpty().forEach { next ->
                    if (unvisited.remove(next)) queue.add(next)
                }
            }
            components += comp.sorted()
        }

        val ordered = components.sortedWith(
            compareByDescending<List<Int>> { it.size }.thenBy { it.firstOrNull() ?: Int.MAX_VALUE }
        )
        val positions = HashMap<Int, Pair<Float, Float>>(active.size)
        val islands = ArrayList<Island>(ordered.size)
        val nComp = max(1, ordered.size)
        val golden = 2.3999632f

        ordered.forEachIndexed { index, ids ->
            val t = (index + 0.55f) / nComp.toFloat()
            val spiralR = sqrt(t) * 0.39f
            val angle = golden * index + hash01(ids.firstOrNull() ?: index) * 0.55f
            val cx = pad + (0.5f + cos(angle) * spiralR) * w
            val cy = pad + (0.48f + sin(angle) * spiralR) * h
            val density = sqrt(ids.size.toFloat() / max(1f, active.size.toFloat()))
            val islandRadius = min(min(w, h) * 0.22f, min(w, h) * (0.035f + 0.20f * density))
                .coerceAtLeast(18f)
            islands += Island(ids, cx, cy, islandRadius)

            if (ids.size == 1) {
                positions[ids[0]] = Pair(cx, cy)
            } else {
                val maxDegree = ids.maxOfOrNull { degree[it] ?: 0 }?.coerceAtLeast(1) ?: 1
                ids.forEachIndexed { localIndex, id ->
                    val d = (degree[id] ?: 0).toFloat() / maxDegree.toFloat()
                    val a = golden * localIndex + hash01(id * 31 + ids.size) * 1.2f
                    val hashR = 0.34f + 0.66f * hash01(id xor 0x5bd1e995.toInt())
                    // Highly connected nodes sit nearer the local island centre, never the global centre.
                    val rr = islandRadius * hashR * (1f - 0.42f * d)
                    positions[id] = Pair(cx + cos(a) * rr, cy + sin(a) * rr)
                }
            }
        }

        return TissueLayout(positions, islands)
    }

    private fun drawStructuralRelation(
        canvas: Canvas,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        edge: GraphEdge
    ) {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val d = max(1f, sqrt(dx * dx + dy * dy))
        val nx = -dy / d
        val ny = dx / d
        val strength = (ln(1.0 + edge.weight.toDouble()) / 8.0).coerceIn(0.08, 1.0).toFloat()
        val sign = if (((edge.fromId xor edge.toId) and 1) == 0) 1f else -1f
        val bend = min(28f, d * 0.10f) * sign
        val cx = (a.first + b.first) * 0.5f + nx * bend
        val cy = (a.second + b.second) * 0.5f + ny * bend

        linePaint.strokeWidth = 0.7f + 2.3f * strength
        val ex = edge.overexchange.coerceIn(-1.0, 1.0)
        linePaint.color = when {
            ex > 0.08 -> Color.argb((35 + 95 * strength).toInt(), 70, 205, 230)
            ex < -0.08 -> Color.argb((35 + 95 * strength).toInt(), 220, 70, 160)
            else -> Color.argb((28 + 80 * strength).toInt(), 110, 130, 158)
        }
        val path = Path().apply {
            moveTo(a.first, a.second)
            quadTo(cx, cy, b.first, b.second)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawCrossModalBridge(
        canvas: Canvas,
        audio: Pair<Float, Float>,
        visual: Pair<Float, Float>,
        joint: Pair<Float, Float>,
        confidence: Double
    ) {
        val c = confidence.coerceIn(0.0, 1.0).toFloat()
        linePaint.strokeWidth = 1.0f + 2.2f * c
        linePaint.color = Color.argb((45 + 115 * c).toInt(), 80, 205, 235)
        canvas.drawLine(audio.first, audio.second, joint.first, joint.second, linePaint)
        linePaint.color = Color.argb((45 + 115 * c).toInt(), 230, 80, 170)
        canvas.drawLine(visual.first, visual.second, joint.first, joint.second, linePaint)
    }

    private fun drawPrediction(
        canvas: Canvas,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        prediction: ActivePrediction
    ) {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val d = max(1f, sqrt(dx * dx + dy * dy))
        val nx = -dy / d
        val ny = dx / d
        val c = prediction.confidence.coerceIn(0.0, 1.0).toFloat()
        val sign = if (((prediction.sourceId xor prediction.targetId) and 1) == 0) 1f else -1f
        val bend = min(18f, d * 0.08f) * sign
        linePaint.strokeWidth = 0.8f + 1.5f * c
        linePaint.color = Color.argb((34 + 105 * c).toInt(), 245, 210, 95)
        val path = Path().apply {
            moveTo(a.first, a.second)
            quadTo((a.first + b.first) * 0.5f + nx * bend, (a.second + b.second) * 0.5f + ny * bend, b.first, b.second)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawExchange(
        canvas: Canvas,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        ex: ActiveExchange
    ) {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val d = max(1f, sqrt(dx * dx + dy * dy))
        val nx = -dy / d
        val ny = dx / d
        val magnitude = abs(ex.value).coerceIn(0.0, 2.0).toFloat()
        val sign = if (ex.value >= 0.0) 1f else -1f
        val bend = min(24f, d * 0.12f) * sign
        linePaint.strokeWidth = 1.0f + 3.0f * sqrt(magnitude.coerceAtLeast(0.02f))
        val alpha = (48 + 130 * min(1f, magnitude)).toInt()
        linePaint.color = if (ex.value >= 0.0) {
            Color.argb(alpha, 60, 205, 230)
        } else {
            Color.argb(alpha, 230, 70, 165)
        }
        val path = Path().apply {
            moveTo(a.first, a.second)
            quadTo((a.first + b.first) * 0.5f + nx * bend, (a.second + b.second) * 0.5f + ny * bend, b.first, b.second)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawActiveNode(canvas: Canvas, x: Float, y: Float, node: ActiveMacroNode) {
        val a = node.activation.coerceIn(0.0, 1.5).toFloat()
        val e = node.expectation.coerceIn(0.0, 1.5).toFloat()
        val base = 4.5f + ln(1.0 + node.abstractionLevel.toDouble()).toFloat() * 4.2f
        val relevanceBoost = node.structuralRelevance.coerceIn(0.0, 1.0).toFloat() * 0.25f
        val radius = base * (1f + 0.48f * a + relevanceBoost)
        val color = if (node.modality == "crossmodal") Color.rgb(245, 225, 118) else levelColor(node.abstractionLevel)

        if (e > 0.02f) {
            haloPaint.color = withAlpha(color, (35 + 80 * min(1f, e)).toInt())
            haloPaint.strokeWidth = 1.0f + 2.8f * e
            canvas.drawCircle(x, y, radius + 5f + 7f * e, haloPaint)
        }
        if (node.retrieved) {
            haloPaint.color = Color.argb(60, 235, 240, 255)
            haloPaint.strokeWidth = 1.3f
            canvas.drawCircle(x, y, radius + 3f, haloPaint)
        }

        nodePaint.color = withAlpha(color, (125 + 110 * min(1f, 0.45f + a)).toInt())
        canvas.drawCircle(x, y, radius, nodePaint)

        if (node.abstractionTierIndex >= 5) {
            tierPaint.textSize = 10f + min(4f, (node.abstractionTierIndex - 5) * 0.55f)
            canvas.drawText(node.abstractionTier, x, y - radius - 6f, tierPaint)
        }
    }

    private fun drawMemoryField(canvas: Canvas, s: EngineOutputState) {
        val nodes = s.graph.nodes.filter { it.abstractionLevel > 0 }.take(64)
        if (nodes.isEmpty()) {
            drawCentered(canvas, "NOCH KEINE MAKROSTRUKTUR")
            return
        }
        val cx = width * 0.5f
        val cy = height * 0.46f
        val radius = min(width, height) * 0.36f
        nodes.forEachIndexed { index, node ->
            val a = 2.0 * PI * index / max(1, nodes.size) + phase * 2.0 * PI * 0.025
            val rr = radius * (0.32f + 0.68f * hash01(node.id))
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr
            val r = 3f + ln(1.0 + node.abstractionLevel.toDouble()).toFloat() * 3.5f
            nodePaint.color = withAlpha(levelColor(node.abstractionLevel), 92)
            canvas.drawCircle(x, y, r, nodePaint)
        }
    }

    private fun drawFooter(canvas: Canvas, text: String) {
        infoPaint.color = Color.argb(130, 215, 225, 245)
        canvas.drawText(text, 12f, height - 76f, infoPaint)
    }

    private fun drawCentered(canvas: Canvas, text: String) {
        infoPaint.textAlign = Paint.Align.CENTER
        infoPaint.textSize = 20f
        infoPaint.color = Color.argb(150, 205, 215, 230)
        canvas.drawText(text, width * 0.5f, height * 0.48f, infoPaint)
        infoPaint.textAlign = Paint.Align.LEFT
        infoPaint.textSize = 14f
    }

    private fun levelColor(level: Int): Int = when (level) {
        1 -> Color.rgb(255, 176, 68)
        2 -> Color.rgb(255, 92, 176)
        3 -> Color.rgb(125, 105, 255)
        4 -> Color.rgb(226, 82, 255)
        else -> {
            val hue = ((286f + (level - 4) * 27.5f) % 360f + 360f) % 360f
            Color.HSVToColor(floatArrayOf(hue, 0.62f, 1.0f))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun hash01(seed: Int): Float {
        val mixed = mix(seed.toLong() * 0x9E3779B97F4A7C15UL.toLong())
        return ((mixed ushr 11) and 0xffff).toFloat() / 65535f
    }

    private fun mix(x0: Long): Long {
        var x = x0
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return x xor (x ushr 31)
    }
}
