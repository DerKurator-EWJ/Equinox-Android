package de.equinox.v05

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Step 38: frame-local spatial coupler.
 *
 * The adaptive visual quantizer still owns appearance identities. This stage does not invent
 * another persistent semantic alphabet. Instead it groups neighboring, nearly identical
 * observations into one connected region and forwards only one event per region to MemoryModel.
 *
 * That preserves the learned visual IDs while preventing a large homogeneous surface from
 * generating hundreds of near-duplicate core events. Region support is retained as evidence
 * weight, so a large surface is not treated the same as a one-patch speck.
 */
data class CoupledVisualRegion(
    val tokenId: Int,
    val packedRgb: Int,
    val supportCount: Int,
    val normalizedArea: Double,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val meanFamiliarity: Double,
    val meanResolutionDepth: Double
) {
    val centerX: Double get() = (x0 + x1) * 0.5
    val centerY: Double get() = (y0 + y1) * 0.5
}

object SpatialVisualCoupler {
    private val FEATURE_WEIGHTS = doubleArrayOf(0.65, 1.00, 1.00, 0.55, 0.95, 0.80, 0.80)
    private const val SAME_ID_DISTANCE = 0.235
    private const val NEAR_ID_DISTANCE = 0.085
    private const val EDGE_BARRIER = 0.34

    fun couple(observations: List<VisualObservation>): List<CoupledVisualRegion> {
        if (observations.isEmpty()) return emptyList()
        if (observations.size == 1) return listOf(toRegion(listOf(observations.first())))

        val n = observations.size
        val parent = IntArray(n) { it }
        val rank = ByteArray(n)

        fun find(x0: Int): Int {
            var x = x0
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            var ra = find(a)
            var rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) {
                val t = ra; ra = rb; rb = t
            }
            parent[rb] = ra
            if (rank[ra] == rank[rb]) rank[ra] = (rank[ra] + 1).toByte()
        }

        // Spatial hash: observations are registered in every bin their bounds overlap.
        // This keeps coupling local instead of comparing every patch with every other patch.
        val bins = ceil(sqrt(n.toDouble())).toInt().coerceIn(4, 40)
        val buckets = HashMap<Int, MutableList<Int>>()
        observations.forEachIndexed { index, o ->
            val bx0 = (floor(o.x0.coerceIn(0.0, 0.999999) * bins).toInt() - 1).coerceIn(0, bins - 1)
            val by0 = (floor(o.y0.coerceIn(0.0, 0.999999) * bins).toInt() - 1).coerceIn(0, bins - 1)
            val bx1 = (floor((o.x1.coerceIn(0.0, 1.0) - 1e-9).coerceAtLeast(0.0) * bins).toInt() + 1).coerceIn(0, bins - 1)
            val by1 = (floor((o.y1.coerceIn(0.0, 1.0) - 1e-9).coerceAtLeast(0.0) * bins).toInt() + 1).coerceIn(0, bins - 1)
            for (by in by0..by1) for (bx in bx0..bx1) {
                buckets.getOrPut(by * bins + bx) { ArrayList() }.add(index)
            }
        }

        val compared = HashSet<Long>(n * 4)
        buckets.values.forEach { indices ->
            for (i in 0 until indices.size) {
                val a = indices[i]
                for (j in i + 1 until indices.size) {
                    val b = indices[j]
                    val low = min(a, b)
                    val high = max(a, b)
                    val pair = (low.toLong() shl 32) xor (high.toLong() and 0xffffffffL)
                    if (!compared.add(pair)) continue
                    if (shouldCouple(observations[a], observations[b])) union(a, b)
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<VisualObservation>>()
        observations.forEachIndexed { index, observation ->
            groups.getOrPut(find(index)) { ArrayList() }.add(observation)
        }

        return groups.values
            .map(::toRegion)
            .sortedWith(compareBy<CoupledVisualRegion> { it.centerY }.thenBy { it.centerX })
    }

    private fun shouldCouple(a: VisualObservation, b: VisualObservation): Boolean {
        if (!spatiallyTouching(a, b)) return false
        if (kotlin.math.abs(a.resolutionDepth - b.resolutionDepth) > 3) return false

        val distance = appearanceDistance(a, b)
        val sameIdentity = a.tokenId == b.tokenId
        if (sameIdentity) return distance <= SAME_ID_DISTANCE

        // For different quantizer IDs we are conservative. A strong local edge is treated as a
        // boundary even if the average colors happen to be similar.
        if (max(edgeStrength(a), edgeStrength(b)) >= EDGE_BARRIER) return false
        return distance <= NEAR_ID_DISTANCE
    }

    private fun spatiallyTouching(a: VisualObservation, b: VisualObservation): Boolean {
        val aW = (a.x1 - a.x0).coerceAtLeast(1e-6)
        val aH = (a.y1 - a.y0).coerceAtLeast(1e-6)
        val bW = (b.x1 - b.x0).coerceAtLeast(1e-6)
        val bH = (b.y1 - b.y0).coerceAtLeast(1e-6)
        val tolerance = (min(min(aW, aH), min(bW, bH)) * 0.10).coerceIn(0.001, 0.025)

        val xNear = a.x0 <= b.x1 + tolerance && b.x0 <= a.x1 + tolerance
        val yNear = a.y0 <= b.y1 + tolerance && b.y0 <= a.y1 + tolerance
        return xNear && yNear
    }

    private fun edgeStrength(o: VisualObservation): Double {
        val gx = kotlin.math.abs(o.gradientX - 0.5) * 2.0
        val gy = kotlin.math.abs(o.gradientY - 0.5) * 2.0
        return max(o.localContrast, max(gx, gy)).coerceIn(0.0, 1.0)
    }

    private fun appearanceDistance(a: VisualObservation, b: VisualObservation): Double {
        val av = doubleArrayOf(a.luminance, a.chromaRg, a.chromaBg, a.saturation, a.localContrast, a.gradientX, a.gradientY)
        val bv = doubleArrayOf(b.luminance, b.chromaRg, b.chromaBg, b.saturation, b.localContrast, b.gradientX, b.gradientY)
        var sum = 0.0
        var total = 0.0
        for (i in av.indices) {
            val d = av[i] - bv[i]
            val w = FEATURE_WEIGHTS[i]
            sum += w * d * d
            total += w
        }
        return sqrt(sum / total.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
    }

    private fun toRegion(members: List<VisualObservation>): CoupledVisualRegion {
        var minX = 1.0
        var minY = 1.0
        var maxX = 0.0
        var maxY = 0.0
        var familiarity = 0.0
        var depth = 0.0

        val supportByToken = LinkedHashMap<Int, Double>()
        members.forEach { o ->
            minX = min(minX, o.x0)
            minY = min(minY, o.y0)
            maxX = max(maxX, o.x1)
            maxY = max(maxY, o.y1)
            val area = ((o.x1 - o.x0).coerceAtLeast(1e-5) * (o.y1 - o.y0).coerceAtLeast(1e-5))
            val weight = area * (0.75 + 0.25 * o.familiarity.coerceIn(0.0, 1.0))
            supportByToken[o.tokenId] = (supportByToken[o.tokenId] ?: 0.0) + weight
            familiarity += o.familiarity
            depth += o.resolutionDepth.toDouble()
        }

        val token = supportByToken.maxByOrNull { it.value }?.key ?: members.first().tokenId

        // Preserve the learned prototype color of the representative visual ID. The region may
        // contain a few neighboring near-identities, but coupling must not slowly recolor the
        // persistent quant identity merely because it represented a larger connected surface.
        val representativeMembers = members.filter { it.tokenId == token }
        var repWeight = 0.0
        var repR = 0.0
        var repG = 0.0
        var repB = 0.0
        representativeMembers.forEach { o ->
            val area = ((o.x1 - o.x0).coerceAtLeast(1e-5) * (o.y1 - o.y0).coerceAtLeast(1e-5))
            repWeight += area
            repR += ((o.packedRgb shr 16) and 0xff) * area
            repG += ((o.packedRgb shr 8) and 0xff) * area
            repB += (o.packedRgb and 0xff) * area
        }
        val safeRepWeight = repWeight.coerceAtLeast(1e-9)
        val rgb = ((repR / safeRepWeight).toInt().coerceIn(0, 255) shl 16) or
            ((repG / safeRepWeight).toInt().coerceIn(0, 255) shl 8) or
            (repB / safeRepWeight).toInt().coerceIn(0, 255)

        return CoupledVisualRegion(
            tokenId = token,
            packedRgb = rgb,
            supportCount = members.size.coerceAtLeast(1),
            normalizedArea = ((maxX - minX).coerceAtLeast(0.0) * (maxY - minY).coerceAtLeast(0.0)).coerceIn(0.0, 1.0),
            x0 = minX.coerceIn(0.0, 1.0),
            y0 = minY.coerceIn(0.0, 1.0),
            x1 = maxX.coerceIn(0.0, 1.0),
            y1 = maxY.coerceIn(0.0, 1.0),
            meanFamiliarity = (familiarity / members.size.toDouble()).coerceIn(0.0, 1.0),
            meanResolutionDepth = depth / members.size.toDouble()
        )
    }
}
