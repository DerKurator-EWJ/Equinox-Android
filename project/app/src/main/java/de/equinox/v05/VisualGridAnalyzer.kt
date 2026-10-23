package de.equinox.v05

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max

/** One adaptive appearance identity observed at one sensor-grid role. */
data class VisualObservation(
    val tokenId: Int,
    val packedRgb: Int,
    val cellIndex: Int,
    val prototypeCreated: Boolean,
    val matchDistance: Double
)

data class VisualFrameResult(
    val observations: List<VisualObservation>,
    val averageRgb: Int,
    val codebookSize: Int,
    val createdThisFrame: Int,
    val meanMatchDistance: Double
)

/**
 * Step 20 camera analyzer with two strictly separated outputs:
 * - slow 8x6 adaptive observations for learning,
 * - faster 48x27 RGB pigment frames for the read-only video renderer.
 *
 * The video frame is never persisted and never becomes a second memory representation.
 */
class VisualGridAnalyzer(
    private val quantizer: AdaptiveVisualQuantizer,
    private val onFrame: (VisualFrameResult) -> Unit,
    private val onVideoFrame: ((VideoFrameSnapshot) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    companion object {
        const val GRID_W = 8
        const val GRID_H = 6
        const val VIDEO_W = 48
        const val VIDEO_H = 27
        private const val MIN_LEARNING_FRAME_INTERVAL_MS = 650L
        private const val MIN_VIDEO_FRAME_INTERVAL_MS = 90L
    }

    private var lastLearningFrameMs = 0L
    private var lastVideoFrameMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (image.planes.size < 3) return
            val now = System.currentTimeMillis()
            val videoDue = onVideoFrame != null && now - lastVideoFrameMs >= MIN_VIDEO_FRAME_INTERVAL_MS
            val learningDue = now - lastLearningFrameMs >= MIN_LEARNING_FRAME_INTERVAL_MS
            if (!videoDue && !learningDue) return

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val width = image.width
            val height = image.height

            if (videoDue) {
                lastVideoFrameMs = now
                val raw = IntArray(VIDEO_W * VIDEO_H)
                for (gy in 0 until VIDEO_H) {
                    for (gx in 0 until VIDEO_W) {
                        val x = (((gx + 0.5) / VIDEO_W) * width).toInt().coerceIn(0, width - 1)
                        val y = (((gy + 0.5) / VIDEO_H) * height).toInt().coerceIn(0, height - 1)
                        raw[gy * VIDEO_W + gx] = sampleRgb(x, y, yPlane, uPlane, vPlane)
                    }
                }
                val rotated = rotateGrid(raw, VIDEO_W, VIDEO_H, image.imageInfo.rotationDegrees)
                onVideoFrame?.invoke(VideoFrameSnapshot(rotated.first, rotated.second, rotated.third, now))
            }

            if (!learningDue) return
            lastLearningFrameMs = now

            val rgbGrid = IntArray(GRID_W * GRID_H)
            val lum = DoubleArray(GRID_W * GRID_H)
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L

            for (gy in 0 until GRID_H) {
                for (gx in 0 until GRID_W) {
                    val x = (((gx + 0.5) / GRID_W) * width).toInt().coerceIn(0, width - 1)
                    val y = (((gy + 0.5) / GRID_H) * height).toInt().coerceIn(0, height - 1)
                    val rgb = sampleRgb(x, y, yPlane, uPlane, vPlane)
                    val idx = gy * GRID_W + gx
                    rgbGrid[idx] = rgb
                    val r = (rgb shr 16) and 0xff
                    val g = (rgb shr 8) and 0xff
                    val b = rgb and 0xff
                    lum[idx] = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                    sumR += r; sumG += g; sumB += b
                }
            }

            val observations = ArrayList<VisualObservation>(GRID_W * GRID_H)
            var created = 0
            var distanceSum = 0.0
            var distanceCount = 0

            for (gy in 0 until GRID_H) {
                for (gx in 0 until GRID_W) {
                    val idx = gy * GRID_W + gx
                    val rgb = rgbGrid[idx]
                    val r = (rgb shr 16) and 0xff
                    val g = (rgb shr 8) and 0xff
                    val b = rgb and 0xff
                    val maxC = max(r, max(g, b))
                    val minC = minOf(r, g, b)
                    val saturation = (maxC - minC) / 255.0
                    val chromaRg = ((r - g) / 510.0 + 0.5).coerceIn(0.0, 1.0)
                    val chromaBg = ((b - g) / 510.0 + 0.5).coerceIn(0.0, 1.0)

                    fun l(xx: Int, yy: Int): Double = lum[yy.coerceIn(0, GRID_H - 1) * GRID_W + xx.coerceIn(0, GRID_W - 1)]
                    val center = lum[idx]
                    val left = l(gx - 1, gy)
                    val right = l(gx + 1, gy)
                    val up = l(gx, gy - 1)
                    val down = l(gx, gy + 1)
                    val localContrast = ((abs(center - left) + abs(center - right) + abs(center - up) + abs(center - down)) * 0.25).coerceIn(0.0, 1.0)
                    val gradX = ((right - left) * 0.5 + 0.5).coerceIn(0.0, 1.0)
                    val gradY = ((down - up) * 0.5 + 0.5).coerceIn(0.0, 1.0)

                    val features = doubleArrayOf(
                        center.coerceIn(0.0, 1.0), chromaRg, chromaBg,
                        saturation.coerceIn(0.0, 1.0), localContrast, gradX, gradY
                    )
                    val q = quantizer.quantize(features, rgb)
                    if (q.created) created++
                    if (!q.created) { distanceSum += q.matchDistance; distanceCount++ }
                    observations += VisualObservation(q.id, q.displayColor, idx, q.created, q.matchDistance)
                }
            }

            val count = GRID_W * GRID_H
            val avg = if (count > 0) {
                ((sumR / count).toInt() shl 16) or ((sumG / count).toInt() shl 8) or (sumB / count).toInt()
            } else 0

            onFrame(
                VisualFrameResult(
                    observations = observations,
                    averageRgb = avg,
                    codebookSize = quantizer.stats().prototypes,
                    createdThisFrame = created,
                    meanMatchDistance = if (distanceCount == 0) 0.0 else distanceSum / distanceCount.toDouble()
                )
            )
        } finally {
            image.close()
        }
    }

    private fun rotateGrid(input: IntArray, w: Int, h: Int, degrees: Int): Triple<Int, Int, IntArray> {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return Triple(w, h, input)
        if (rotation == 180) {
            val out = IntArray(input.size)
            for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = input[y * w + x]
            return Triple(w, h, out)
        }
        val outW = h
        val outH = w
        val out = IntArray(input.size)
        if (rotation == 90) {
            for (y in 0 until h) for (x in 0 until w) {
                val dx = h - 1 - y
                val dy = x
                out[dy * outW + dx] = input[y * w + x]
            }
        } else { // 270 or uncommon value: use counter-clockwise 90 for 270, identity-ish fallback otherwise
            for (y in 0 until h) for (x in 0 until w) {
                val dx = y
                val dy = w - 1 - x
                out[dy * outW + dx] = input[y * w + x]
            }
        }
        return Triple(outW, outH, out)
    }

    private fun sampleRgb(
        x: Int,
        y: Int,
        yPlane: ImageProxy.PlaneProxy,
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy
    ): Int {
        val yy = readPlane(yPlane, x, y)
        val uu = readPlane(uPlane, x / 2, y / 2)
        val vv = readPlane(vPlane, x / 2, y / 2)
        val yf = max(0, yy - 16) * 1.164f
        val uf = uu - 128
        val vf = vv - 128
        val r = (yf + 1.596f * vf).toInt().coerceIn(0, 255)
        val g = (yf - 0.392f * uf - 0.813f * vf).toInt().coerceIn(0, 255)
        val b = (yf + 2.017f * uf).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun readPlane(plane: ImageProxy.PlaneProxy, x: Int, y: Int): Int {
        val index = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer
        if (index < 0 || index >= buffer.limit()) return 128
        return buffer.get(index).toInt() and 0xff
    }
}
