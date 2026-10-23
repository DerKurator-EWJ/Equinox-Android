package de.equinox.v05

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Step 38: spatial coupling for adaptive camera observations.
 *
 * The camera analyzer is deliberately allowed to oversample a familiar/interesting region at
 * several quadtree depths.  Before those samples enter the learning stream we first collapse
 * fully refined parents, then union only spatially touching leaf regions whose learned visual
 * identity/appearance is sufficiently close.  The result is a sparse list of contiguous visual
 * regions rather than a long list of almost identical patches.
 *
 * This class is stateless and read-only with respect to MemoryModel.  It never invents knowledge;
 * it only reduces redundant sensor observations before learning.
 */
object SpatialVisualCoupler {

    data class CoupledRegion(
        val tokenId: Int,
        val packedRgb: Int,
        val centerX: Double,
        val centerY: Double,
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
        val familiarity: Double,
        val resolutionDepth: Int,
        val memberCount: Int,
        val normalizedArea: Double
    )

    data class Result(
        val leafCount: Int,
        val regions: List<CoupledRegion>
    ) {
        val reductionRatio: Double
            get() = if (leafCount <= 0) 0.0 else (1.0 - regions.size.toDouble() / leafCount.toDouble()).coerceIn(0.0, 1.0)
    }

    fun couple(observations: List<VisualObservation>): Result {
        if (observations.isEmpty()) return Result(0, emptyList())

        val leaves = removeFullyRefinedParents(observations)
        if (leaves.size <= 1) {
            return Result(leaves.size, leaves.map { it.toCoupledRegion() })
        }

        val parent = IntArray(leaves.size) { it }
        val rank = IntArray(leaves.size)

        fun find(i: Int): Int {
            var x = i
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
            if (rank[ra] == rank[rb]) rank[ra]++
        }

        // A few hundred leaves are normal on-device.  Pair checks stay local in effect because
        // the adjacency gate rejects non-touching regions before appearance work is relevant.
        for (i in 0 until leaves.lastIndex) {
            for (j in i + 1 until leaves.size) {
                val a = leaves[i]
                val b = leaves[j]
                if (!touches(a, b)) continue
                if (abs(a.resolutionDepth - b.resolutionDepth) > 1) continue
                if (appearanceCompatible(a, b)) union(i, j)
            }
        }

        val groups = LinkedHashMap<Int, MutableList<VisualObservation>>()
        for (i in leaves.indices) groups.getOrPut(find(i)) { ArrayList() }.add(leaves[i])

        val out = groups.values.map { aggregate(it) }
            .sortedWith(compareBy<CoupledRegion> { it.centerY }.thenBy { it.centerX })
        return Result(leaves.size, out)
    }

    /**
     * A parent remains a leaf until all four direct quadtree children have actually been sampled.
     * This matters when the adaptive work budget expires mid-refinement: no part of the image is
     * silently discarded just because one child happened to win the priority queue.
     */
    private fun removeFullyRefinedParents(input: List<VisualObservation>): List<VisualObservation> {
        val byDepth = input.groupBy { it.resolutionDepth }
        return input.filter { p ->
            val children = byDepth[p.resolutionDepth + 1] ?: return@filter true
            var q0 = false; var q1 = false; var q2 = false; var q3 = false
            val mx = (p.x0 + p.x1) * 0.5
            val my = (p.y0 + p.y1) * 0.5
            for (c in children) {
                if (!inside(c, p)) continue
                val left = c.centerX < mx
                val top = c.centerY < my
                when {
                    left && top -> q0 = true
                    !left && top -> q1 = true
                    left && !top -> q2 = true
                    else -> q3 = true
                }
                if (q0 && q1 && q2 && q3) break
            }
            !(q0 && q1 && q2 && q3)
        }
    }

    private fun inside(c: VisualObservation, p: VisualObservation): Boolean {
        val e = 1e-7
        return c.x0 >= p.x0 - e && c.y0 >= p.y0 - e && c.x1 <= p.x1 + e && c.y1 <= p.y1 + e
    }

    private fun touches(a: VisualObservation, b: VisualObservation): Boolean {
        val e = 1e-6
        val horizontalTouch = (abs(a.x1 - b.x0) <= e || abs(b.x1 - a.x0) <= e) &&
            overlap(a.y0, a.y1, b.y0, b.y1) > e
        val verticalTouch = (abs(a.y1 - b.y0) <= e || abs(b.y1 - a.y0) <= e) &&
            overlap(a.x0, a.x1, b.x0, b.x1) > e
        return horizontalTouch || verticalTouch
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double =
        max(0.0, minOf(a1, b1) - maxOf(a0, b0))

    private fun appearanceCompatible(a: VisualObservation, b: VisualObservation): Boolean {
        val rgb = rgbDistance(a.packedRgb, b.packedRgb)
        // Same learned prototype is strong evidence.  Different prototypes may still be merged
        // when their prototype colours are nearly identical, which prevents codebook splitting
        // from reintroducing patch-level redundancy.
        val limit = if (a.tokenId == b.tokenId) 0.19 else 0.075
        if (rgb > limit) return false

        // Very novel cells are kept separate unless they already share the same prototype.  This
        // preserves exploratory detail at object boundaries while familiar flat surfaces collapse.
        val familiar = minOf(a.familiarity, b.familiarity)
        return a.tokenId == b.tokenId || familiar >= 0.42
    }

    private fun rgbDistance(a: Int, b: Int): Double {
        val dr = (((a shr 16) and 0xff) - ((b shr 16) and 0xff)) / 255.0
        val dg = (((a shr 8) and 0xff) - ((b shr 8) and 0xff)) / 255.0
        val db = ((a and 0xff) - (b and 0xff)) / 255.0
        return sqrt((dr * dr + dg * dg + db * db) / 3.0)
    }

    private fun aggregate(items: List<VisualObservation>): CoupledRegion {
        if (items.size == 1) return items[0].toCoupledRegion()

        var x0 = 1.0; var y0 = 1.0; var x1 = 0.0; var y1 = 0.0
        var weightedX = 0.0; var weightedY = 0.0; var totalArea = 0.0
        var fam = 0.0
        var r = 0.0; var g = 0.0; var b = 0.0
        var maxDepth = 0
        val tokenArea = HashMap<Int, Double>()

        for (o in items) {
            val area = ((o.x1 - o.x0).coerceAtLeast(1e-9) * (o.y1 - o.y0).coerceAtLeast(1e-9))
            x0 = minOf(x0, o.x0); y0 = minOf(y0, o.y0)
            x1 = maxOf(x1, o.x1); y1 = maxOf(y1, o.y1)
            weightedX += o.centerX * area
            weightedY += o.centerY * area
            fam += o.familiarity * area
            r += ((o.packedRgb shr 16) and 0xff) * area
            g += ((o.packedRgb shr 8) and 0xff) * area
            b += (o.packedRgb and 0xff) * area
            totalArea += area
            maxDepth = max(maxDepth, o.resolutionDepth)
            tokenArea[o.tokenId] = (tokenArea[o.tokenId] ?: 0.0) + area
        }

        val safeArea = totalArea.coerceAtLeast(1e-9)
        val representative = tokenArea.maxByOrNull { it.value }?.key ?: items.first().tokenId
        val packed = (((r / safeArea).toInt().coerceIn(0, 255)) shl 16) or
            (((g / safeArea).toInt().coerceIn(0, 255)) shl 8) or
            ((b / safeArea).toInt().coerceIn(0, 255))

        return CoupledRegion(
            tokenId = representative,
            packedRgb = packed,
            centerX = (weightedX / safeArea).coerceIn(0.0, 1.0),
            centerY = (weightedY / safeArea).coerceIn(0.0, 1.0),
            x0 = x0.coerceIn(0.0, 1.0),
            y0 = y0.coerceIn(0.0, 1.0),
            x1 = x1.coerceIn(0.0, 1.0),
            y1 = y1.coerceIn(0.0, 1.0),
            familiarity = (fam / safeArea).coerceIn(0.0, 1.0),
            resolutionDepth = maxDepth,
            memberCount = items.size,
            normalizedArea = safeArea.coerceIn(0.0, 1.0)
        )
    }

    private fun VisualObservation.toCoupledRegion(): CoupledRegion = CoupledRegion(
        tokenId = tokenId,
        packedRgb = packedRgb,
        centerX = centerX,
        centerY = centerY,
        x0 = x0,
        y0 = y0,
        x1 = x1,
        y1 = y1,
        familiarity = familiarity,
        resolutionDepth = resolutionDepth,
        memberCount = 1,
        normalizedArea = ((x1 - x0).coerceAtLeast(0.0) * (y1 - y0).coerceAtLeast(0.0)).coerceIn(0.0, 1.0)
    )
}
