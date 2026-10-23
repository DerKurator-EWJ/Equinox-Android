package de.equinox.v05

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One adaptive appearance identity observed at one dynamically resolved region. */
data class VisualObservation(
    val tokenId: Int,
    val packedRgb: Int,
    val cellIndex: Int,
    val prototypeCreated: Boolean,
    val matchDistance: Double,
    val resolutionDepth: Int = 0,
    val centerX: Double = 0.5,
    val centerY: Double = 0.5,
    val familiarity: Double = 0.0,
    val recognized: Boolean = true,
    val x0: Double = 0.0,
    val y0: Double = 0.0,
    val x1: Double = 1.0,
    val y1: Double = 1.0
)

data class VisualFrameResult(
    val observations: List<VisualObservation>,
    val averageRgb: Int,
    val codebookSize: Int,
    val createdThisFrame: Int,
    val meanMatchDistance: Double,
    val meanFamiliarity: Double = 0.0,
    val maxResolutionDepth: Int = 0,
    val sampledRegions: Int = 0,
    val spatialLeafRegions: Int = 0,
    val spatialCoupledRegions: Int = 0,
    val spatialCouplingRatio: Double = 0.0,
    val coupledObservations: List<SpatialVisualCoupler.CoupledRegion> = emptyList(),
    val analysisCostMs: Long = 0L,
    val videoSampleCostMs: Long = 0L,
    val videoIntervalMs: Long = 90L
)

/**
 * Step 25 adaptive coarse-to-fine camera analyzer.
 *
 * The old 8x6 learning raster is now only the entry lattice. Every coarse region may split
 * into 2x2 children. Familiar regions receive progressively finer analysis, while new or
 * surprising regions receive temporary exploratory refinement so that novelty is not trapped
 * at low resolution. There is no configured maximum depth; recursion stops at the natural
 * pixel granularity of the camera frame or when the current device-time budget is exhausted.
 *
 * Output pigment resolution is also dynamic. It scales with learned familiarity/detail and is
 * bounded only by the source frame dimensions plus the adaptive work budget, not by 48x27.
 */
class VisualGridAnalyzer(
    private val quantizer: AdaptiveVisualQuantizer,
    private val onFrame: (VisualFrameResult) -> Unit,
    private val onVideoFrame: ((VideoFrameSnapshot) -> Unit)? = null,
    private val learningEnabled: () -> Boolean = { true }
) : ImageAnalysis.Analyzer {

    companion object {
        const val BASE_GRID_W = 8
        const val BASE_GRID_H = 6
        private const val MIN_LEARNING_FRAME_INTERVAL_MS = 650L
        private const val TARGET_LEARNING_WORK_MS = 70L
    }

    private data class Region(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val depth: Int,
        val priority: Double
    ) {
        val width: Int get() = (x1 - x0).coerceAtLeast(1)
        val height: Int get() = (y1 - y0).coerceAtLeast(1)
    }

    private data class RegionSample(
        val rgb: Int,
        val features: DoubleArray
    )

    private var lastLearningFrameMs = 0L
    private var lastVideoFrameMs = 0L
    private var videoFrameIntervalMs = 66L
    private var lastVideoSampleCostMs = 0L
    // Step 28: output pigment density is a device-time budget, not a fixed raster ceiling.
    // It may grow toward the source frame resolution when conversion remains cheap.
    private var adaptiveVideoCellBudget = 72 * 40
    private var adaptivePatchBudget = BASE_GRID_W * BASE_GRID_H * 2
    private var lastMeanFamiliarity = 0.0
    private var lastMaxDepth = 0
    @Volatile private var governorVideoScale = 1.0
    @Volatile private var governorLearningScale = 1.0
    @Volatile private var governorMinVideoIntervalMs = 33L

    fun setPerformanceProfile(profile: PerformanceGovernor.Profile) {
        governorVideoScale = profile.videoWorkScale.coerceIn(0.35, 1.6)
        governorLearningScale = profile.learningWorkScale.coerceIn(0.35, 1.5)
        governorMinVideoIntervalMs = profile.minVideoIntervalMs.coerceIn(16L, 250L)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (image.planes.size < 3) return
            val now = System.currentTimeMillis()
            val videoDue = onVideoFrame != null && now - lastVideoFrameMs >= videoFrameIntervalMs
            val learningDue = now - lastLearningFrameMs >= MIN_LEARNING_FRAME_INTERVAL_MS
            if (!videoDue && !learningDue) return

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val width = image.width
            val height = image.height

            if (videoDue) {
                lastVideoFrameMs = now
                val videoStartNs = System.nanoTime()
                val detailFactor = 1.0 + 1.7 * lastMeanFamiliarity + 0.22 * lastMaxDepth.toDouble()
                val desiredCells = (adaptiveVideoCellBudget.toDouble() * detailFactor * governorVideoScale)
                    .toInt()
                    .coerceIn(48 * 27, width * height)
                val aspect = width.toDouble() / height.coerceAtLeast(1).toDouble()
                val videoH = sqrt(desiredCells / aspect).toInt().coerceIn(1, height)
                val videoW = (videoH * aspect).toInt().coerceIn(1, width)
                val raw = IntArray(videoW * videoH)
                for (gy in 0 until videoH) {
                    for (gx in 0 until videoW) {
                        val x = (((gx + 0.5) / videoW) * width).toInt().coerceIn(0, width - 1)
                        val y = (((gy + 0.5) / videoH) * height).toInt().coerceIn(0, height - 1)
                        raw[gy * videoW + gx] = sampleRgb(x, y, yPlane, uPlane, vPlane)
                    }
                }
                val rotated = rotateGrid(raw, videoW, videoH, image.imageInfo.rotationDegrees)
                lastVideoSampleCostMs = ((System.nanoTime() - videoStartNs) / 1_000_000L).coerceAtLeast(0L)

                // GPU drawing is no longer the raster bottleneck. Let the CPU pigment sampler grow
                // or shrink toward the device's measured conversion budget. The only hard ceiling is
                // the actual source-frame pixel count; there is no semantic output-raster maximum.
                adaptiveVideoCellBudget = when {
                    lastVideoSampleCostMs <= 8L -> (adaptiveVideoCellBudget * 1.15).roundToInt()
                    lastVideoSampleCostMs <= 14L -> (adaptiveVideoCellBudget * 1.05).roundToInt()
                    lastVideoSampleCostMs >= 45L -> (adaptiveVideoCellBudget * 0.72).roundToInt()
                    lastVideoSampleCostMs >= 28L -> (adaptiveVideoCellBudget * 0.86).roundToInt()
                    else -> adaptiveVideoCellBudget
                }.coerceIn(48 * 27, width * height)

                // Prefer fresh frames over a backlog. Faster devices automatically approach 30 fps;
                // slower ones back off before conversion itself starts starving learning.
                videoFrameIntervalMs = (when {
                    lastVideoSampleCostMs <= 10L -> 33L
                    lastVideoSampleCostMs <= 20L -> 50L
                    lastVideoSampleCostMs <= 34L -> 66L
                    lastVideoSampleCostMs <= 55L -> 90L
                    else -> 120L
                }).coerceAtLeast(governorMinVideoIntervalMs)
                onVideoFrame?.invoke(VideoFrameSnapshot(rotated.first, rotated.second, rotated.third, now))
            }

            if (!learningDue) return
            lastLearningFrameMs = now
            val workStartNs = System.nanoTime()

            val observations = ArrayList<VisualObservation>()
            val learnNow = learningEnabled()
            val queue = PriorityQueue<Region>(compareByDescending<Region> { it.priority }.thenBy { it.depth })

            // The base lattice guarantees whole-frame coverage before detail is allocated.
            for (gy in 0 until BASE_GRID_H) {
                val y0 = (gy * height) / BASE_GRID_H
                val y1 = ((gy + 1) * height) / BASE_GRID_H
                for (gx in 0 until BASE_GRID_W) {
                    val x0 = (gx * width) / BASE_GRID_W
                    val x1 = ((gx + 1) * width) / BASE_GRID_W
                    queue.add(Region(x0, y0, x1, y1, 0, 1.0))
                }
            }

            var created = 0
            var distanceSum = 0.0
            var distanceCount = 0
            var familiaritySum = 0.0
            var maxDepth = 0
            var rgbSumR = 0L
            var rgbSumG = 0L
            var rgbSumB = 0L
            var processed = 0

            val effectivePatchBudget = (adaptivePatchBudget * governorLearningScale).toInt().coerceAtLeast(BASE_GRID_W * BASE_GRID_H)
            while (queue.isNotEmpty() && processed < effectivePatchBudget) {
                val region = queue.remove()
                val sample = sampleRegion(region, yPlane, uPlane, vPlane)
                val q = quantizer.quantize(sample.features, sample.rgb, learn = learnNow)
                val noveltyRatio = if (q.created || q.acceptanceRadius <= 1e-9) 2.0 else q.matchDistance / q.acceptanceRadius

                val cx = (region.x0 + region.x1) * 0.5 / width.coerceAtLeast(1).toDouble()
                val cy = (region.y0 + region.y1) * 0.5 / height.coerceAtLeast(1).toDouble()
                observations += VisualObservation(
                    tokenId = q.id,
                    packedRgb = q.displayColor,
                    cellIndex = processed,
                    prototypeCreated = q.created,
                    matchDistance = q.matchDistance,
                    resolutionDepth = region.depth,
                    centerX = cx.coerceIn(0.0, 1.0),
                    centerY = cy.coerceIn(0.0, 1.0),
                    familiarity = q.familiarity,
                    recognized = q.recognized,
                    x0 = region.x0.toDouble() / width.coerceAtLeast(1).toDouble(),
                    y0 = region.y0.toDouble() / height.coerceAtLeast(1).toDouble(),
                    x1 = region.x1.toDouble() / width.coerceAtLeast(1).toDouble(),
                    y1 = region.y1.toDouble() / height.coerceAtLeast(1).toDouble()
                )
                processed++
                if (q.created) created++
                if (!q.created) { distanceSum += q.matchDistance; distanceCount++ }
                familiaritySum += q.familiarity
                maxDepth = max(maxDepth, region.depth)
                rgbSumR += (sample.rgb shr 16) and 0xff
                rgbSumG += (sample.rgb shr 8) and 0xff
                rgbSumB += sample.rgb and 0xff

                if (AdaptiveScalePolicy.shouldRefineVisual(
                        familiarity = q.familiarity,
                        noveltyRatio = noveltyRatio,
                        created = q.created,
                        depth = region.depth,
                        regionWidthPx = region.width,
                        regionHeightPx = region.height
                    )
                ) {
                    split(region).forEach { child ->
                        queue.add(
                            child.copy(
                                priority = AdaptiveScalePolicy.visualRefinementPriority(
                                    q.familiarity,
                                    noveltyRatio,
                                    q.created,
                                    child.depth
                                )
                            )
                        )
                    }
                }
            }

            // Stable spatial order: macro sequences encode image structure, not priority-queue order.
            observations.sortWith(
                compareBy<VisualObservation> { it.centerY }
                    .thenBy { it.centerX }
                    .thenBy { it.resolutionDepth }
            )

            // Step 38: collapse fully refined quadtree parents and spatially couple adjacent,
            // near-identical leaf regions before they enter the persistent learning stream.
            val spatial = SpatialVisualCoupler.couple(observations.filter { it.recognized })

            val elapsedMs = (System.nanoTime() - workStartNs) / 1_000_000L
            // Device-speed adaptation: faster hardware automatically resolves more regions.
            adaptivePatchBudget = when {
                elapsedMs < TARGET_LEARNING_WORK_MS / 2 -> (adaptivePatchBudget * 1.35).toInt().coerceAtLeast(adaptivePatchBudget + 1)
                elapsedMs > TARGET_LEARNING_WORK_MS * 2 -> (adaptivePatchBudget * 0.72).toInt().coerceAtLeast(BASE_GRID_W * BASE_GRID_H)
                else -> adaptivePatchBudget
            }

            val count = observations.size.coerceAtLeast(1)
            val avg = ((rgbSumR / count).toInt().coerceIn(0,255) shl 16) or
                ((rgbSumG / count).toInt().coerceIn(0,255) shl 8) or
                (rgbSumB / count).toInt().coerceIn(0,255)
            lastMeanFamiliarity = (familiaritySum / count.toDouble()).coerceIn(0.0, 1.0)
            lastMaxDepth = maxDepth

            onFrame(
                VisualFrameResult(
                    observations = observations,
                    averageRgb = avg,
                    codebookSize = quantizer.stats().prototypes,
                    createdThisFrame = created,
                    meanMatchDistance = if (distanceCount == 0) 0.0 else distanceSum / distanceCount.toDouble(),
                    meanFamiliarity = lastMeanFamiliarity,
                    maxResolutionDepth = maxDepth,
                    sampledRegions = observations.size,
                    spatialLeafRegions = spatial.leafCount,
                    spatialCoupledRegions = spatial.regions.size,
                    spatialCouplingRatio = spatial.reductionRatio,
                    coupledObservations = spatial.regions,
                    analysisCostMs = elapsedMs,
                    videoSampleCostMs = lastVideoSampleCostMs,
                    videoIntervalMs = videoFrameIntervalMs
                )
            )
        } finally {
            image.close()
        }
    }

    private fun split(region: Region): List<Region> {
        val mx = (region.x0 + region.x1) / 2
        val my = (region.y0 + region.y1) / 2
        if (mx <= region.x0 || mx >= region.x1 || my <= region.y0 || my >= region.y1) return emptyList()
        val d = region.depth + 1
        return listOf(
            Region(region.x0, region.y0, mx, my, d, region.priority),
            Region(mx, region.y0, region.x1, my, d, region.priority),
            Region(region.x0, my, mx, region.y1, d, region.priority),
            Region(mx, my, region.x1, region.y1, d, region.priority)
        )
    }

    private fun sampleRegion(
        region: Region,
        yPlane: ImageProxy.PlaneProxy,
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy
    ): RegionSample {
        val cx = ((region.x0 + region.x1) / 2).coerceAtLeast(0)
        val cy = ((region.y0 + region.y1) / 2).coerceAtLeast(0)
        val dx = max(1, region.width / 4)
        val dy = max(1, region.height / 4)
        val centerRgb = sampleRgb(cx, cy, yPlane, uPlane, vPlane)
        val leftRgb = sampleRgb((cx - dx).coerceAtLeast(region.x0), cy, yPlane, uPlane, vPlane)
        val rightRgb = sampleRgb((cx + dx).coerceAtMost(region.x1 - 1), cy, yPlane, uPlane, vPlane)
        val upRgb = sampleRgb(cx, (cy - dy).coerceAtLeast(region.y0), yPlane, uPlane, vPlane)
        val downRgb = sampleRgb(cx, (cy + dy).coerceAtMost(region.y1 - 1), yPlane, uPlane, vPlane)

        fun luminance(rgb: Int): Double {
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        val r = (centerRgb shr 16) and 0xff
        val g = (centerRgb shr 8) and 0xff
        val b = centerRgb and 0xff
        val maxC = max(r, max(g, b))
        val minC = minOf(r, g, b)
        val saturation = (maxC - minC) / 255.0
        val center = luminance(centerRgb)
        val left = luminance(leftRgb)
        val right = luminance(rightRgb)
        val up = luminance(upRgb)
        val down = luminance(downRgb)
        val localContrast = ((abs(center-left)+abs(center-right)+abs(center-up)+abs(center-down))*0.25).coerceIn(0.0,1.0)
        val gradX = ((right-left)*0.5+0.5).coerceIn(0.0,1.0)
        val gradY = ((down-up)*0.5+0.5).coerceIn(0.0,1.0)
        return RegionSample(
            rgb = centerRgb,
            features = doubleArrayOf(
                center.coerceIn(0.0,1.0),
                ((r-g)/510.0+0.5).coerceIn(0.0,1.0),
                ((b-g)/510.0+0.5).coerceIn(0.0,1.0),
                saturation.coerceIn(0.0,1.0),
                localContrast,
                gradX,
                gradY
            )
        )
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
        } else {
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
