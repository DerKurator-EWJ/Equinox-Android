package de.equinox.v05

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

enum class GraphDisplayMode {
    TWO_D,
    THREE_D
}

class RelationGraphView(context: Context) : View(context) {

    private var snapshot = GraphSnapshot(emptyList(), emptyList())
    private val model = VisualGraphModel()
    private var displayPrefs = DisplayPrefsStore(context).load()
    private var displayMode = GraphDisplayMode.TWO_D

    private val geometry = GeometryGroundMemory.standard()

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun setSnapshot(next: GraphSnapshot) {
        snapshot = next
        model.update(next)
        invalidate()
    }

    fun setDisplayPrefs(prefs: DisplayPrefs) {
        displayPrefs = prefs
        invalidate()
    }

    fun setDisplayMode(mode: GraphDisplayMode) {
        displayMode = mode
        invalidate()
    }

    fun getDisplayMode(): GraphDisplayMode = displayMode

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(displayPrefs.backgroundColor)

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom, width * 0.5f, height * 0.5f)

        if (displayMode == GraphDisplayMode.THREE_D) {
            drawThreeD(canvas)
        } else {
            drawTwoD(canvas)
        }

        canvas.restore()
    }

    private fun drawTwoD(canvas: Canvas) {
        drawFibers(canvas, false)
        drawNodes(canvas, false)
    }

    private fun drawThreeD(canvas: Canvas) {
        drawFibers(canvas, true)
        drawNodes(canvas, true)
    }

    private fun drawFibers(canvas: Canvas, useDepth: Boolean) {
        val fibers = model.fibers(width.toFloat(), height.toFloat())

        // Draw weak fibers first, strong fibers later.
        fibers.sortedBy { it.strength }.forEach { fiber ->
            val a = project(fiber.from, useDepth)
            val b = project(fiber.to, useDepth)

            val depth = if (useDepth) {
                ((fiber.from.z + fiber.to.z) * 0.5f + 1f).coerceIn(0.35f, 1.6f)
            } else 1f

            val alpha = (45 + 165 * fiber.strength * depth.coerceIn(0.5f, 1f))
                .roundToInt()
                .coerceIn(25, 220)

            val baseColor = edgeColorFor(fiber.from, fiber.to)

            if (displayPrefs.glowEnabled && fiber.strength > 0.28f) {
                glowPaint.color = withAlpha(baseColor, (alpha * 0.32f).roundToInt())
                glowPaint.strokeWidth = 5f + fiber.strength * 15f
                glowPaint.maskFilter = BlurMaskFilter(
                    5f + displayPrefs.glowStrength * 0.055f,
                    BlurMaskFilter.Blur.NORMAL
                )

                val glowPath = bezierPath(a, b, fiber, useDepth)
                canvas.drawPath(glowPath, glowPaint)
                glowPaint.maskFilter = null
            }

            edgePaint.color = withAlpha(baseColor, alpha)
            edgePaint.strokeWidth = (0.65f + fiber.strength * 3.3f) * depth.coerceIn(0.65f, 1.25f)

            val path = bezierPath(a, b, fiber, useDepth)
            canvas.drawPath(path, edgePaint)
        }
    }

    private fun drawNodes(canvas: Canvas, useDepth: Boolean) {
        val nodes = model.visibleNodes()
            .sortedBy { if (useDepth) it.z else it.abstractionLevel.toFloat() }

        nodes.forEach { node ->
            val p = project(node, useDepth)
            val depth = if (useDepth) (1f + node.z * 0.42f).coerceIn(0.62f, 1.42f) else 1f
            val base = nodeBaseRadius(node) * depth
            val color = nodeColor(node)

            if (displayPrefs.glowEnabled && node.importance > 1.1f) {
                nodePaint.style = Paint.Style.FILL
                nodePaint.color = withAlpha(color, 50)
                nodePaint.maskFilter = BlurMaskFilter(
                    5f + min(18f, node.importance * 2.8f),
                    BlurMaskFilter.Blur.NORMAL
                )
                canvas.drawCircle(p.x, p.y, base * 1.65f, nodePaint)
                nodePaint.maskFilter = null
            }

            if (node.isMacro || node.abstractionLevel > 0) {
                drawMacroGlyph(canvas, node, p, base, color)
            } else if (node.modality == "vision") {
                nodePaint.style = Paint.Style.FILL
                nodePaint.color = if (node.displayColor != 0) node.displayColor else color
                val r = base
                canvas.drawRoundRect(
                    p.x - r,
                    p.y - r,
                    p.x + r,
                    p.y + r,
                    r * 0.35f,
                    r * 0.35f,
                    nodePaint
                )
            } else {
                nodePaint.style = Paint.Style.FILL
                nodePaint.color = color
                canvas.drawCircle(p.x, p.y, base, nodePaint)
            }

            nodeStrokePaint.color = withAlpha(Color.WHITE, 70)
            canvas.drawCircle(p.x, p.y, base * 1.06f, nodeStrokePaint)
        }
    }

    private fun drawMacroGlyph(
        canvas: Canvas,
        node: VisualNodeState,
        p: PointF,
        radius: Float,
        color: Int
    ) {
        val petals = when {
            node.abstractionLevel >= 4 -> 7
            node.abstractionLevel == 3 -> 6
            node.abstractionLevel == 2 -> 5
            else -> 4
        }

        val path = Path()
        val points = petals * 2
        for (i in 0 until points) {
            val angle = -Math.PI / 2 + i * Math.PI / petals
            val localRadius = if (i % 2 == 0) radius * 1.3f else radius * 0.72f
            val x = p.x + cos(angle).toFloat() * localRadius
            val y = p.y + sin(angle).toFloat() * localRadius

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        nodePaint.style = Paint.Style.FILL
        nodePaint.color = withAlpha(color, 220)
        canvas.drawPath(path, nodePaint)

        nodeStrokePaint.color = withAlpha(Color.WHITE, 95)
        canvas.drawPath(path, nodeStrokePaint)
    }

    private fun bezierPath(
        a: PointF,
        b: PointF,
        fiber: VisualFiber,
        useDepth: Boolean
    ): Path {
        val path = Path()
        path.moveTo(a.x, a.y)

        var c1x = fiber.control1X
        var c1y = fiber.control1Y
        var c2x = fiber.control2X
        var c2y = fiber.control2Y

        if (useDepth) {
            val depthA = 1f + fiber.from.z * 0.22f
            val depthB = 1f + fiber.to.z * 0.22f
            c1x = width * 0.5f + (c1x - width * 0.5f) * depthA
            c1y = height * 0.5f + (c1y - height * 0.5f) * depthA
            c2x = width * 0.5f + (c2x - width * 0.5f) * depthB
            c2y = height * 0.5f + (c2y - height * 0.5f) * depthB
        }

        path.cubicTo(c1x, c1y, c2x, c2y, b.x, b.y)
        return path
    }

    private fun project(node: VisualNodeState, useDepth: Boolean): PointF {
        if (!useDepth) {
            return PointF(node.x * width, node.y * height)
        }

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val px = node.x * width
        val py = node.y * height

        val perspective = (1f + node.z * 0.32f).coerceIn(0.68f, 1.38f)
        val x = centerX + (px - centerX) * perspective
        val y = centerY + (py - centerY) * perspective
        return PointF(x, y)
    }

    private fun nodeBaseRadius(node: VisualNodeState): Float {
        val levelBoost = node.abstractionLevel * 2.3f
        return (3.5f + node.importance * 2.2f + levelBoost).coerceIn(4f, 22f)
    }

    private fun nodeColor(node: VisualNodeState): Int {
        if (node.modality == "vision" && node.displayColor != 0) return node.displayColor

        return when (node.abstractionLevel) {
            1 -> displayPrefs.macroLevel1Color
            2 -> displayPrefs.macroLevel2Color
            3 -> displayPrefs.macroLevel3Color
            in 4..Int.MAX_VALUE -> displayPrefs.macroLevel4Color
            else -> audioColorFromWeight(node.weight)
        }
    }

    private fun edgeColorFor(a: VisualNodeState, b: VisualNodeState): Int {
        val level = max(a.abstractionLevel, b.abstractionLevel)
        return when (level) {
            1 -> displayPrefs.macroLevel1Color
            2 -> displayPrefs.macroLevel2Color
            3 -> displayPrefs.macroLevel3Color
            in 4..Int.MAX_VALUE -> displayPrefs.macroLevel4Color
            else -> displayPrefs.audioEdgeColor
        }
    }

    private fun audioColorFromWeight(weight: Double): Int {
        val t = (1.0 - exp(-weight * 0.02)).toFloat().coerceIn(0f, 1f)
        val r = (45 + 60 * t).roundToInt()
        val g = (170 + 70 * t).roundToInt()
        val b = (190 + 45 * t).roundToInt()
        return Color.rgb(r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    panX += dx
                    panY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.55f, 4.0f)
            invalidate()
            return true
        }
    }
}
