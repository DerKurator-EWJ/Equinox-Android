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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class GraphDisplayMode {
    TWO_D,
    THREE_D
}

class RelationGraphView(context: Context) : View(context) {
    private data class P(var x: Float, var y: Float, var z: Float)
    private data class Projected(val x: Float, val y: Float, val scale: Float, val depth: Float)

    private var snapshot = GraphSnapshot(emptyList(), emptyList())
    private var visibleEdges: List<GraphEdge> = emptyList()
    private val positions = mutableMapOf<Int, P>()
    private val geometry = GeometryGroundMemory.standard()
    private var displayPrefs = DisplayPrefsStore(context).load()

    private var displayMode = GraphDisplayMode.TWO_D
    private var yaw = -0.62f
    private var pitch = 0.42f

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
    private val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(95, 230, 235, 245)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 20f
    }

    fun setDisplayMode(mode: GraphDisplayMode) {
        displayMode = mode
        invalidate()
    }

    fun getDisplayMode(): GraphDisplayMode = displayMode

    fun setDisplayPrefs(prefs: DisplayPrefs) {
        displayPrefs = prefs
        invalidate()
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

            // z is renderer-only and deterministically derived from graph metadata.
            // It is never persisted in MemoryModel.
            val hashDepth = ((((node.id * 1103515245L + 12345L) ushr 16) and 0xffff).toFloat() / 65535f) - 0.5f
            val levelDepth = (kotlin.math.ln(1.0 + node.abstractionLevel.coerceAtLeast(1).toDouble()) * 0.16 - 0.10).toFloat().coerceIn(-0.10f, 0.46f)
            val z = (hashDepth * 0.42f + levelDepth).coerceIn(-0.48f, 0.48f)

            positions[node.id] = P(
                x = 0.5f + cos(angle).toFloat() * (ring + jitter),
                y = 0.5f + sin(angle).toFloat() * (ring + jitter),
                z = z
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
        canvas.drawColor(displayPrefs.backgroundColor)

        val pad = 30f
        val w = max(1f, width.toFloat() - pad * 2)
        val h = max(1f, height.toFloat() - pad * 2)

        if (snapshot.nodes.isEmpty()) {
            textPaint.textSize = 31f
            textPaint.color = Color.LTGRAY
            canvas.drawText("Noch keine persistenten Codes", width / 2f, height / 2f, textPaint)
            return
        }

        if (displayMode == GraphDisplayMode.THREE_D) {
            drawDepthGuide(canvas, pad, w, h)
        }

        val projected = positions.mapValues { (_, p) -> project(p, pad, w, h) }
        val strongestEdge = max(1L, visibleEdges.maxOfOrNull { it.weight } ?: 1L)

        // Far edges first.
        visibleEdges
            .sortedBy { e ->
                ((projected[e.fromId]?.depth ?: 0f) + (projected[e.toId]?.depth ?: 0f)) * 0.5f
            }
            .forEach { edge ->
                val a = projected[edge.fromId] ?: return@forEach
                val b = projected[edge.toId] ?: return@forEach
                drawEdge(canvas, edge, a, b, strongestEdge)
            }

        val strongestNode = max(1L, snapshot.nodes.maxOfOrNull { it.weight } ?: 1L)

        // Far nodes first.
        snapshot.nodes
            .sortedBy { projected[it.id]?.depth ?: 0f }
            .forEach { node ->
                val p = projected[node.id] ?: return@forEach
                drawNode(canvas, node, p, strongestNode)
            }

        val microCount = snapshot.nodes.count { it.abstractionLevel == 0 }
        val audioCount = snapshot.nodes.count { it.abstractionLevel == 0 && (it.modality == "audio" || it.modality == "audio_direct" || it.modality == "audio_adaptive") }
        val visionCount = snapshot.nodes.count { it.abstractionLevel == 0 && it.modality.startsWith("vision") }
        val levels = snapshot.nodes
            .filter { it.abstractionLevel > 0 }
            .groupingBy { it.abstractionLevel }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(" · ") { "${AbstractionTier.labelForMacroDepth(it.key)}:${it.value}" }

        val suffix = if (levels.isBlank()) "keine Makroebene" else levels
        val mode = if (displayMode == GraphDisplayMode.TWO_D) "2D" else "3D"

        infoPaint.color = Color.LTGRAY
        infoPaint.textSize = 19f
        canvas.drawText(
            "$mode · $microCount Mikro (A:$audioCount · V:$visionCount) · $suffix · ${visibleEdges.size}/${snapshot.edges.size} Rel.",
            16f,
            height - 16f,
            infoPaint
        )
    }

    private fun project(p: P, pad: Float, w: Float, h: Float): Projected {
        if (displayMode == GraphDisplayMode.TWO_D) {
            return Projected(
                x = pad + p.x * w,
                y = pad + p.y * h,
                scale = 1f,
                depth = p.z
            )
        }

        val x0 = p.x - 0.5f
        val y0 = p.y - 0.5f
        val z0 = p.z

        val cy = cos(yaw)
        val sy = sin(yaw)
        val x1 = x0 * cy - z0 * sy
        val z1 = x0 * sy + z0 * cy

        val cp = cos(pitch)
        val sp = sin(pitch)
        val y1 = y0 * cp - z1 * sp
        val z2 = y0 * sp + z1 * cp

        val perspective = (1.0f / (1.18f - z2 * 0.55f)).coerceIn(0.70f, 1.45f)
        val screenX = pad + (0.5f + x1 * perspective) * w
        val screenY = pad + (0.5f + y1 * perspective) * h

        return Projected(
            x = screenX,
            y = screenY,
            scale = perspective,
            depth = z2
        )
    }

    private fun drawDepthGuide(canvas: Canvas, pad: Float, w: Float, h: Float) {
        val cx = pad + w * 0.5f
        val cy = pad + h * 0.50f
        val rx = w * 0.38f
        val ry = h * 0.26f

        faintPaint.alpha = 65
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, faintPaint)
        canvas.drawOval(cx - rx * 0.64f, cy - ry * 0.64f, cx + rx * 0.64f, cy + ry * 0.64f, faintPaint)
        canvas.drawLine(cx - rx, cy, cx + rx, cy, faintPaint)
        canvas.drawLine(cx, cy - ry, cx, cy + ry, faintPaint)
    }

    private fun drawEdge(
        canvas: Canvas,
        edge: GraphEdge,
        a: Projected,
        b: Projected,
        strongestEdge: Long
    ) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dist = max(1f, sqrt(dx * dx + dy * dy))

        val kappa = (1.0 - exp(-0.08 * edge.weight.toDouble())).toFloat()
        val sign = if (((edge.fromId * 31 + edge.toId) and 1) == 0) 1f else -1f
        val nx = -dy / dist
        val ny = dx / dist

        val bendScale = when {
            edge.isMembership -> 0.10f
            edge.abstractionLevel >= 3 -> 0.14f
            edge.abstractionLevel == 2 -> 0.18f
            edge.abstractionLevel == 1 -> 0.22f
            else -> 0.34f
        }

        val depthWarp = if (displayMode == GraphDisplayMode.THREE_D) {
            abs(a.depth - b.depth) * 0.17f
        } else 0f

        val bend = min(
            dist * 0.45f,
            12f + dist * (bendScale + depthWarp) * kappa
        ) * sign

        val cx = (a.x + b.x) * 0.5f + nx * bend
        val cy = (a.y + b.y) * 0.5f + ny * bend

        val norm = edge.weight.toFloat() / strongestEdge.toFloat()
        val depthScale = ((a.scale + b.scale) * 0.5f).coerceIn(0.72f, 1.35f)

        edgePaint.strokeWidth = when {
            edge.isMembership -> (1.7f + 4.2f * sqrt(norm)) * depthScale
            edge.abstractionLevel >= 2 -> (2.8f + 7.5f * sqrt(norm)) * depthScale
            edge.abstractionLevel == 1 -> (2.2f + 6.5f * sqrt(norm)) * depthScale
            else -> (1.0f + 5.0f * sqrt(norm)) * depthScale
        }

        val depthAlpha = if (displayMode == GraphDisplayMode.THREE_D) {
            (0.72f + 0.28f * ((a.depth + b.depth) * 0.5f + 0.5f)).coerceIn(0.48f, 1f)
        } else 1f

        edgePaint.color = edgeColor(edge, norm, depthAlpha)

        val path = Path().apply {
            moveTo(a.x, a.y)
            quadTo(cx, cy, b.x, b.y)
        }
        canvas.drawPath(path, edgePaint)
    }

    private fun drawNode(
        canvas: Canvas,
        node: GraphNode,
        p: Projected,
        strongestNode: Long
    ) {
        val normalized = node.weight.toFloat() / strongestNode.toFloat()
        val baseRadius = (10f + 17f * sqrt(normalized)) * p.scale

        if (node.abstractionLevel == 0) {
            nodePaint.color = if (node.modality.startsWith("vision") && node.displayColor != 0) {
                Color.rgb(
                    (node.displayColor shr 16) and 0xff,
                    (node.displayColor shr 8) and 0xff,
                    node.displayColor and 0xff
                )
            } else {
                frequencyColor(node.representativeHz)
            }

            if (node.modality.startsWith("vision")) {
                val r = baseRadius * 0.92f
                canvas.drawRect(p.x - r, p.y - r, p.x + r, p.y + r, nodePaint)
                val halo = Paint(nodePaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * p.scale
                    alpha = if (displayPrefs.glowEnabled) displayPrefs.glowStrength.coerceIn(0, 255) else 0
                }
                canvas.drawRect(p.x - r - 4f, p.y - r - 4f, p.x + r + 4f, p.y + r + 4f, halo)
            } else {
                canvas.drawCircle(p.x, p.y, baseRadius, nodePaint)
                val halo = Paint(nodePaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * p.scale
                    alpha = if (displayPrefs.glowEnabled) displayPrefs.glowStrength.coerceIn(0, 255) else 0
                }
                canvas.drawCircle(p.x, p.y, baseRadius + 4f * p.scale, halo)
            }
            return
        }

        val radius = baseRadius + (8f + (kotlin.math.ln(1.0 + node.abstractionLevel.toDouble()) * 8.0).toFloat()) * p.scale
        nodePaint.color = levelColor(node.abstractionLevel)

        if (displayMode == GraphDisplayMode.THREE_D) {
            val template = geometry.suggest3D(node.memberCount, node.kind == "sequence")
            draw3DTemplate(canvas, p.x, p.y, radius, template, p.scale)
        } else {
            val template = geometry.suggest2D(node.memberCount, node.kind == "sequence")
            draw2DTemplate(canvas, p.x, p.y, radius, template, node.kind == "sequence")
        }
    }

    private fun draw2DTemplate(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        template: GeometryTemplate,
        sequence: Boolean
    ) {
        when (template) {
            GeometryTemplate.LINE -> {
                val path = Path().apply {
                    moveTo(x - radius, y)
                    quadTo(x, y - radius * 0.70f, x + radius, y)
                    quadTo(x, y + radius * 0.70f, x - radius, y)
                    close()
                }
                canvas.drawPath(path, nodePaint)
                canvas.drawPath(path, outlinePaint)
            }
            GeometryTemplate.ARC -> drawSequenceGlyph(canvas, x, y, radius, 1)
            GeometryTemplate.TRIANGLE -> drawPolygon(canvas, x, y, radius, 3)
            GeometryTemplate.QUAD -> drawPolygon(canvas, x, y, radius, 4)
            GeometryTemplate.PENTAGON -> drawPolygon(canvas, x, y, radius, 5)
            GeometryTemplate.HEXAGON -> drawPolygon(canvas, x, y, radius, 6)
            GeometryTemplate.ELLIPSE -> {
                canvas.drawOval(x - radius * 1.25f, y - radius * 0.72f, x + radius * 1.25f, y + radius * 0.72f, nodePaint)
                canvas.drawOval(x - radius * 1.25f, y - radius * 0.72f, x + radius * 1.25f, y + radius * 0.72f, outlinePaint)
            }
            else -> {
                canvas.drawCircle(x, y, radius, nodePaint)
                canvas.drawCircle(x, y, radius, outlinePaint)
            }
        }

        if (sequence && template != GeometryTemplate.ARC) {
            val chainPaint = Paint(outlinePaint).apply {
                alpha = 175
                strokeWidth = 2f
            }
            canvas.drawLine(x - radius * 0.52f, y, x + radius * 0.52f, y, chainPaint)
        }
    }

    private fun drawPolygon(canvas: Canvas, x: Float, y: Float, radius: Float, sides: Int) {
        val path = Path()
        for (i in 0 until sides) {
            val a = -PI / 2.0 + 2.0 * PI * i / sides
            val px = x + cos(a).toFloat() * radius
            val py = y + sin(a).toFloat() * radius
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, nodePaint)
        canvas.drawPath(path, outlinePaint)
    }

    private fun draw3DTemplate(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        template: GeometryTemplate,
        scale: Float
    ) {
        when (template) {
            GeometryTemplate.CUBE -> drawWireCube(canvas, x, y, radius)
            GeometryTemplate.TETRAHEDRON -> drawWireTetra(canvas, x, y, radius)
            GeometryTemplate.OCTAHEDRON -> drawWireOcta(canvas, x, y, radius)
            GeometryTemplate.PRISM -> drawWirePrism(canvas, x, y, radius)
            GeometryTemplate.PYRAMID -> drawWirePyramid(canvas, x, y, radius)
            GeometryTemplate.CYLINDER -> drawWireCylinder(canvas, x, y, radius)
            GeometryTemplate.CONE -> drawWireCone(canvas, x, y, radius)
            GeometryTemplate.TORUS -> drawWireTorus(canvas, x, y, radius)
            else -> drawWireSphere(canvas, x, y, radius)
        }

        // Small filled nucleus preserves abstraction-level color.
        canvas.drawCircle(x, y, max(3f, radius * 0.22f), nodePaint)
        outlinePaint.strokeWidth = 2.4f * scale
    }

    private fun drawWireCube(canvas: Canvas, x: Float, y: Float, r: Float) {
        val o = r * 0.42f
        val aL = x - r * 0.72f
        val aT = y - r * 0.72f
        val aR = x + r * 0.72f
        val aB = y + r * 0.72f

        canvas.drawRect(aL, aT, aR, aB, outlinePaint)
        canvas.drawRect(aL + o, aT - o, aR + o, aB - o, faintPaint)
        canvas.drawLine(aL, aT, aL + o, aT - o, faintPaint)
        canvas.drawLine(aR, aT, aR + o, aT - o, faintPaint)
        canvas.drawLine(aL, aB, aL + o, aB - o, faintPaint)
        canvas.drawLine(aR, aB, aR + o, aB - o, faintPaint)
    }

    private fun drawWireTetra(canvas: Canvas, x: Float, y: Float, r: Float) {
        val topX = x
        val topY = y - r
        val leftX = x - r * 0.90f
        val leftY = y + r * 0.66f
        val rightX = x + r * 0.90f
        val rightY = y + r * 0.66f
        val backX = x
        val backY = y + r * 0.12f

        canvas.drawLine(topX, topY, leftX, leftY, outlinePaint)
        canvas.drawLine(topX, topY, rightX, rightY, outlinePaint)
        canvas.drawLine(leftX, leftY, rightX, rightY, outlinePaint)
        canvas.drawLine(topX, topY, backX, backY, faintPaint)
        canvas.drawLine(leftX, leftY, backX, backY, faintPaint)
        canvas.drawLine(rightX, rightY, backX, backY, faintPaint)
    }

    private fun drawWireOcta(canvas: Canvas, x: Float, y: Float, r: Float) {
        val top = y - r
        val bottom = y + r
        val left = x - r
        val right = x + r

        canvas.drawLine(x, top, left, y, outlinePaint)
        canvas.drawLine(x, top, right, y, outlinePaint)
        canvas.drawLine(left, y, x, bottom, outlinePaint)
        canvas.drawLine(right, y, x, bottom, outlinePaint)
        canvas.drawLine(left, y, right, y, faintPaint)
        canvas.drawLine(x, top, x, bottom, faintPaint)
    }

    private fun drawWirePrism(canvas: Canvas, x: Float, y: Float, r: Float) {
        val path1 = trianglePath(x - r * 0.24f, y + r * 0.15f, r * 0.72f)
        val path2 = trianglePath(x + r * 0.28f, y - r * 0.22f, r * 0.72f)
        canvas.drawPath(path1, outlinePaint)
        canvas.drawPath(path2, faintPaint)
        canvas.drawLine(x - r * 0.24f, y - r * 0.57f, x + r * 0.28f, y - r * 0.94f, faintPaint)
        canvas.drawLine(x - r * 0.86f, y + r * 0.51f, x - r * 0.34f, y + r * 0.14f, faintPaint)
        canvas.drawLine(x + r * 0.38f, y + r * 0.51f, x + r * 0.90f, y + r * 0.14f, faintPaint)
    }

    private fun trianglePath(x: Float, y: Float, r: Float): Path {
        return Path().apply {
            moveTo(x, y - r)
            lineTo(x - r * 0.86f, y + r * 0.50f)
            lineTo(x + r * 0.86f, y + r * 0.50f)
            close()
        }
    }

    private fun drawWirePyramid(canvas: Canvas, x: Float, y: Float, r: Float) {
        val topY = y - r
        val left = x - r * 0.82f
        val right = x + r * 0.82f
        val bottom = y + r * 0.70f
        val backY = y + r * 0.20f

        canvas.drawLine(x, topY, left, bottom, outlinePaint)
        canvas.drawLine(x, topY, right, bottom, outlinePaint)
        canvas.drawLine(left, bottom, right, bottom, outlinePaint)
        canvas.drawLine(x, topY, x, backY, faintPaint)
        canvas.drawLine(left, bottom, x, backY, faintPaint)
        canvas.drawLine(right, bottom, x, backY, faintPaint)
    }

    private fun drawWireSphere(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, outlinePaint)
        canvas.drawOval(x - r * 0.36f, y - r, x + r * 0.36f, y + r, faintPaint)
        canvas.drawOval(x - r, y - r * 0.34f, x + r, y + r * 0.34f, faintPaint)
    }

    private fun drawWireCylinder(canvas: Canvas, x: Float, y: Float, r: Float) {
        val h = r * 0.86f
        canvas.drawOval(x - r * 0.72f, y - h, x + r * 0.72f, y - h * 0.54f, faintPaint)
        canvas.drawOval(x - r * 0.72f, y + h * 0.54f, x + r * 0.72f, y + h, outlinePaint)
        canvas.drawLine(x - r * 0.72f, y - h * 0.77f, x - r * 0.72f, y + h * 0.77f, outlinePaint)
        canvas.drawLine(x + r * 0.72f, y - h * 0.77f, x + r * 0.72f, y + h * 0.77f, outlinePaint)
    }

    private fun drawWireCone(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawOval(x - r * 0.78f, y + r * 0.46f, x + r * 0.78f, y + r * 0.92f, outlinePaint)
        canvas.drawLine(x, y - r, x - r * 0.78f, y + r * 0.69f, outlinePaint)
        canvas.drawLine(x, y - r, x + r * 0.78f, y + r * 0.69f, outlinePaint)
    }

    private fun drawWireTorus(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawOval(x - r, y - r * 0.55f, x + r, y + r * 0.55f, outlinePaint)
        canvas.drawOval(x - r * 0.53f, y - r * 0.23f, x + r * 0.53f, y + r * 0.23f, faintPaint)
        canvas.drawArc(x - r, y - r * 0.55f, x + r, y + r * 0.55f, 180f, 180f, false, faintPaint)
    }

    private fun drawSequenceGlyph(canvas: Canvas, x: Float, y: Float, radius: Float, level: Int) {
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
        canvas.drawPath(path, outlinePaint)

        val inner = Paint(outlinePaint).apply {
            alpha = 175
            strokeWidth = 2f
        }
        canvas.drawLine(x - radius * 0.55f, y, x + radius * 0.55f, y, inner)
        canvas.drawCircle(x - radius * 0.42f, y, radius * 0.11f, inner)
        canvas.drawCircle(x, y, radius * 0.11f, inner)
        canvas.drawCircle(x + radius * 0.42f, y, radius * 0.11f, inner)
    }

    private fun edgeColor(edge: GraphEdge, norm: Float, depthAlpha: Float): Int {
        val alpha = ((55 + 190 * norm) * depthAlpha).toInt().coerceIn(0, 255)
        return when {
            edge.isMembership -> Color.argb(alpha, 235, 155, 55)
            edge.abstractionLevel > 0 -> {
                val c = levelColor(edge.abstractionLevel)
                Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            }
            else -> Color.argb(
                alpha,
                Color.red(displayPrefs.audioEdgeColor),
                Color.green(displayPrefs.audioEdgeColor),
                Color.blue(displayPrefs.audioEdgeColor)
            )
        }
    }

    private fun levelColor(level: Int): Int {
        return when (level) {
            1 -> displayPrefs.macroLevel1Color // F
            2 -> displayPrefs.macroLevel2Color // E
            3 -> displayPrefs.macroLevel3Color // D
            4 -> displayPrefs.macroLevel4Color // C
            else -> {
                // Higher RPG tiers get a deterministic cyclic hue instead of collapsing into L4.
                val hue = ((286f + (level - 4) * 27.5f) % 360f + 360f) % 360f
                Color.HSVToColor(floatArrayOf(hue, 0.62f, 1.0f))
            }
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
