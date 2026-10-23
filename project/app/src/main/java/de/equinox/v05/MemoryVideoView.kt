package de.equinox.v05

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight camera frame for the output renderer only.
 * It is intentionally not persisted in MemoryModel: long-term knowledge remains IDs/macros/relations.
 */
data class VideoFrameSnapshot(
    val width: Int,
    val height: Int,
    val rgb: IntArray,
    val timestampMs: Long
)

/**
 * Step 20 memory-driven video canvas.
 *
 * The camera frame is only pigment. Geometry is derived from the current read-only Equinox state:
 * live activation, recalled expectation, prediction, relevance and overexchange deform the image.
 * The renderer never writes anything back to memory and stores no physical x/y in the core.
 *
 * This is deliberately a Canvas prototype, not yet the later GPU/shader implementation.
 */
class MemoryVideoView(context: Context) : View(context) {
    private var frame: VideoFrameSnapshot? = null
    private var engine: EngineOutputState? = null
    private var workingOverride: WorkingSetSnapshot? = null
    private var phase = 0f
    private var modeLabel = "LIVE"
    @Volatile private var syncInfo = ""
    @Volatile private var lastDrawCostMs = 0.0

    private val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        color = Color.argb(205, 235, 240, 255)
    }

    fun setFrame(newFrame: VideoFrameSnapshot) {
        frame = newFrame
        phase = (phase + 0.021f) % 1f
        invalidate()
    }

    fun setEngineState(newState: EngineOutputState) {
        engine = newState
        workingOverride = null
        phase = (phase + 0.009f) % 1f
        invalidate()
    }

    /** Step 27 hot path: frame + compact WorkingSet arrive as one synchronized render packet. */
    fun setSynchronizedFrame(
        newFrame: VideoFrameSnapshot,
        working: WorkingSetSnapshot?,
        info: String = ""
    ) {
        frame = newFrame
        workingOverride = working
        syncInfo = info
        phase = (phase + 0.021f) % 1f
        invalidate()
    }

    fun lastRenderCostMs(): Double = lastDrawCostMs

    fun setModeLabel(label: String) {
        modeLabel = label.trim().uppercase().ifEmpty { "VIDEO" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val drawStartNs = System.nanoTime()
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(3, 5, 9))

        val f = frame
        if (f == null || f.width <= 0 || f.height <= 0 || f.rgb.size < f.width * f.height) {
            textPaint.color = Color.argb(180, 210, 220, 235)
            canvas.drawText("$modeLabel VIDEO · Kamera wartet", 18f, height * 0.5f, textPaint)
            return
        }

        val working = workingOverride ?: engine?.working
        val nodes = working?.nodes.orEmpty()
        val exchanges = working?.exchanges.orEmpty()
        val predictions = working?.predictions.orEmpty()

        val meanActivation = if (nodes.isEmpty()) 0.0 else nodes.map { it.activation.coerceIn(0.0, 1.0) }.average()
        val meanExpectation = if (nodes.isEmpty()) 0.0 else nodes.map { it.expectation.coerceIn(0.0, 1.0) }.average()
        val meanRelevance = if (nodes.isEmpty()) 0.0 else nodes.map { it.structuralRelevance.coerceIn(0.0, 1.0) }.average()
        val exchangeEnergy = if (exchanges.isEmpty()) 0.0 else exchanges.map { abs(it.value).coerceIn(0.0, 2.0) }.average()
        val signedExchange = if (exchanges.isEmpty()) 0.0 else exchanges.map { it.value.coerceIn(-2.0, 2.0) }.average()
        val predictionEnergy = if (predictions.isEmpty()) 0.0 else predictions.map { it.confidence.coerceIn(0.0, 1.0) }.average()

        // Memory makes the same camera pigment bend differently as the learned active context changes.
        val memoryEnergy = (0.26 * meanActivation + 0.20 * meanExpectation + 0.24 * meanRelevance +
            0.20 * min(1.0, exchangeEnergy) + 0.10 * predictionEnergy).coerceIn(0.0, 1.0)
        val globalWarp = (0.006 + 0.050 * memoryEnergy + 0.022 * min(1.0, exchangeEnergy)).toFloat()

        val viewW = max(1f, width.toFloat())
        val viewH = max(1f, height.toFloat())
        val cellW = viewW / f.width.toFloat()
        val cellH = viewH / f.height.toFloat()
        val drawW = cellW * (1.08f + 0.18f * memoryEnergy.toFloat())
        val drawH = cellH * (1.08f + 0.18f * memoryEnergy.toFloat())

        // At most 18 strongest active/recalled nodes become local deformation fields.
        val fieldNodes = nodes
            .sortedByDescending { max(it.activation, it.expectation) * (0.45 + 0.55 * it.structuralRelevance) }
            .take(18)

        for (gy in 0 until f.height) {
            val ny0 = (gy + 0.5f) / f.height.toFloat()
            for (gx in 0 until f.width) {
                val nx0 = (gx + 0.5f) / f.width.toFloat()
                var nx = nx0
                var ny = ny0

                // Global flow from the current working state.
                val waveA = sin((ny0 * 2.7f + phase * 1.3f) * (2f * PI.toFloat()))
                val waveB = cos((nx0 * 2.2f - phase * 1.1f) * (2f * PI.toFloat()))
                nx += waveA * globalWarp * (0.55f + 0.45f * predictionEnergy.toFloat())
                ny += waveB * globalWarp * (0.55f + 0.45f * meanExpectation.toFloat())

                // Local learned macro fields. These positions exist only in this renderer.
                for (node in fieldNodes) {
                    val p = fieldPosition(node.id)
                    val dx = nx0 - p.first
                    val dy = ny0 - p.second
                    val d2 = 0.018f + dx * dx + dy * dy
                    val activity = max(node.activation, node.expectation).coerceIn(0.0, 1.0).toFloat()
                    val relevance = node.structuralRelevance.coerceIn(0.0, 1.0).toFloat()
                    val strength = (0.0009f + 0.0048f * activity * (0.35f + 0.65f * relevance)) / d2
                    val twist = (0.35f + 0.65f * abs(node.exchangeBias).coerceIn(0.0, 1.5).toFloat())
                    val sign = if (node.exchangeBias >= 0.0) 1f else -1f
                    nx += (-dy * strength * twist * sign).coerceIn(-0.035f, 0.035f)
                    ny += ( dx * strength * twist * sign).coerceIn(-0.035f, 0.035f)
                }

                nx = nx.coerceIn(-0.08f, 1.08f)
                ny = ny.coerceIn(-0.08f, 1.08f)
                val cx = nx * viewW
                val cy = ny * viewH

                val rgb = f.rgb[gy * f.width + gx]
                pixelPaint.color = memoryColor(rgb, memoryEnergy, signedExchange, meanExpectation)
                canvas.drawRect(cx - drawW * 0.5f, cy - drawH * 0.5f, cx + drawW * 0.5f, cy + drawH * 0.5f, pixelPaint)
            }
        }

        // Output-only flow contours make strong prediction/exchange states legible without graph labels.
        if (memoryEnergy > 0.05) {
            overlayPaint.strokeWidth = 1.0f + (3.2f * memoryEnergy).toFloat()
            val alpha = (28 + 105 * memoryEnergy).toInt().coerceIn(28, 145)
            overlayPaint.color = if (signedExchange >= 0.0) {
                Color.argb(alpha, 85, 220, 255)
            } else {
                Color.argb(alpha, 255, 95, 190)
            }
            fieldNodes.take(8).forEach { node ->
                val p = fieldPosition(node.id)
                val cx = p.first * viewW
                val cy = p.second * viewH
                val rr = min(viewW, viewH) * (0.025f + 0.045f * max(node.activation, node.expectation).toFloat())
                canvas.drawCircle(cx, cy, rr, overlayPaint)
            }
        }

        textPaint.color = Color.argb(210, 240, 244, 255)
        val footer = buildString {
            append("$modeLabel VIDEO · Memory ${(memoryEnergy * 100.0).toInt()}% · ${nodes.size} aktiv · ${predictions.size} Prognosen · ${exchanges.size} Austausch")
            if (syncInfo.isNotBlank()) append(" · ").append(syncInfo)
        }
        canvas.drawText(footer, 14f, height - 16f, textPaint)
        lastDrawCostMs = (System.nanoTime() - drawStartNs) / 1_000_000.0
    }

    private fun memoryColor(rgb: Int, memoryEnergy: Double, signedExchange: Double, expectation: Double): Int {
        val r0 = (rgb shr 16) and 0xff
        val g0 = (rgb shr 8) and 0xff
        val b0 = rgb and 0xff
        val contrast = 1.0 + 0.32 * memoryEnergy
        fun contrast(v: Int): Double = ((v - 128.0) * contrast + 128.0)

        var r = contrast(r0)
        var g = contrast(g0)
        var b = contrast(b0)

        // Cyan/magenta bias mirrors the sign convention of overexchange without replacing camera color.
        val ex = signedExchange.coerceIn(-1.0, 1.0)
        if (ex >= 0.0) {
            g += 18.0 * ex * memoryEnergy
            b += 34.0 * ex * memoryEnergy
        } else {
            r += 34.0 * -ex * memoryEnergy
            b += 18.0 * -ex * memoryEnergy
        }

        val lift = 1.0 + 0.12 * expectation.coerceIn(0.0, 1.0)
        r *= lift; g *= lift; b *= lift
        return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    private fun fieldPosition(id: Int): Pair<Float, Float> {
        val a = mix(id.toLong() * 0x9E3779B97F4A7C15UL.toLong())
        val b = mix(a xor 0xC2B2AE3D27D4EB4FUL.toLong())
        val x = 0.08f + 0.84f * (((a ushr 11) and 0xffff).toFloat() / 65535f)
        val y = 0.08f + 0.84f * (((b ushr 13) and 0xffff).toFloat() / 65535f)
        return Pair(x, y)
    }

    private fun mix(x0: Long): Long {
        var x = x0
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }
}
