package de.equinox.v05

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

data class VisualObservation(
    val tokenId: Int,
    val packedRgb: Int,
    val cellIndex: Int,
    val colorClass: Int
)

data class VisualFrameResult(
    val observations: List<VisualObservation>,
    val averageRgb: Int
)

/**
 * Coarse, intentionally cheap 8x6 visual encoder.
 *
 * It samples the YUV camera frame, converts those samples to RGB, quantizes
 * each channel into 4 bins (4*4*4 = 64 color classes) and creates a visual
 * token from (cell position, color class).
 *
 * No x/y camera coordinate enters the Equinox memory model itself: the
 * cell index is only part of the encoder's symbol identity.
 */
class VisualGridAnalyzer(
    private val onFrame: (VisualFrameResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        const val GRID_W = 8
        const val GRID_H = 6
        const val VISUAL_ID_BASE = 500_000_000
        private const val CLASSES = 64
        private const val MIN_FRAME_INTERVAL_MS = 700L
    }

    private var lastFrameMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < MIN_FRAME_INTERVAL_MS) return
            lastFrameMs = now

            if (image.planes.size < 3) return

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val width = image.width
            val height = image.height

            val observations = ArrayList<VisualObservation>(GRID_W * GRID_H)

            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var count = 0

            for (gy in 0 until GRID_H) {
                for (gx in 0 until GRID_W) {
                    val x = (((gx + 0.5) / GRID_W) * width)
                        .toInt()
                        .coerceIn(0, width - 1)
                    val y = (((gy + 0.5) / GRID_H) * height)
                        .toInt()
                        .coerceIn(0, height - 1)

                    val rgb = sampleRgb(
                        x = x,
                        y = y,
                        yPlane = yPlane,
                        uPlane = uPlane,
                        vPlane = vPlane
                    )

                    val r = (rgb shr 16) and 0xff
                    val g = (rgb shr 8) and 0xff
                    val b = rgb and 0xff

                    val colorClass = quantize64(r, g, b)
                    val cellIndex = gy * GRID_W + gx
                    val tokenId = VISUAL_ID_BASE + cellIndex * CLASSES + colorClass

                    observations += VisualObservation(
                        tokenId = tokenId,
                        packedRgb = rgb,
                        cellIndex = cellIndex,
                        colorClass = colorClass
                    )

                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                }
            }

            val avg = if (count > 0) {
                val r = (sumR / count).toInt()
                val g = (sumG / count).toInt()
                val b = (sumB / count).toInt()
                (r shl 16) or (g shl 8) or b
            } else {
                0
            }

            onFrame(VisualFrameResult(observations, avg))
        } finally {
            image.close()
        }
    }

    private fun sampleRgb(
        x: Int,
        y: Int,
        yPlane: ImageProxy.PlaneProxy,
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy
    ): Int {
        val yy = readPlane(yPlane, x, y)
        val ux = x / 2
        val uy = y / 2
        val uu = readPlane(uPlane, ux, uy)
        val vv = readPlane(vPlane, ux, uy)

        val yf = max(0, yy - 16) * 1.164f
        val uf = uu - 128
        val vf = vv - 128

        val r = (yf + 1.596f * vf).toInt().coerceIn(0, 255)
        val g = (yf - 0.392f * uf - 0.813f * vf).toInt().coerceIn(0, 255)
        val b = (yf + 2.017f * uf).toInt().coerceIn(0, 255)

        return (r shl 16) or (g shl 8) or b
    }

    private fun readPlane(
        plane: ImageProxy.PlaneProxy,
        x: Int,
        y: Int
    ): Int {
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val index = y * rowStride + x * pixelStride
        if (index < 0 || index >= buffer.limit()) return 128
        return buffer.get(index).toInt() and 0xff
    }

    private fun quantize64(r: Int, g: Int, b: Int): Int {
        val qr = min(3, r / 64)
        val qg = min(3, g / 64)
        val qb = min(3, b / 64)
        return qr * 16 + qg * 4 + qb
    }
}
