package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Step 18 adaptive visual codebook.
 *
 * A camera cell is represented by a compact seven-dimensional appearance/context vector:
 * luminance, two opponent chroma axes, saturation, local contrast and signed horizontal /
 * vertical gradients. The codebook starts empty and learns stable prototype IDs online.
 *
 * The ID deliberately contains no x/y coordinate. Step 25 removes the former 1024-prototype
 * ceiling. Familiar appearance regions can split into finer identities as evidence accumulates,
 * while spatial detail is handled independently by the adaptive coarse-to-fine camera analyzer.
 */
class AdaptiveVisualQuantizer(private val context: Context) {
    companion object {
        private const val FILE_NAME = "equinox_v06_adaptive_visual_quantizer.json"
        private const val FORMAT_VERSION = 1
        private const val ID_BASE = -50_000_000
        private const val SAVE_EVERY_ACCEPTED = 384L
        private const val RECENT_WINDOW = 256
        private val FEATURE_WEIGHTS = doubleArrayOf(0.65, 1.00, 1.00, 0.55, 0.95, 0.80, 0.80)
    }

    data class Result(
        val id: Int,
        val created: Boolean,
        val matchDistance: Double,
        val acceptanceRadius: Double,
        val codebookSize: Int,
        val displayColor: Int,
        val familiarity: Double,
        val recognized: Boolean = true
    )

    data class Stats(
        val prototypes: Int,
        val acceptedPatches: Long,
        val createdIds: Long,
        val reusedIds: Long,
        val recentNoveltyRate: Double,
        val meanMatchDistance: Double,
        val strongestPrototypeCount: Long,
        val saturated: Boolean
    )

    private data class Prototype(
        val id: Int,
        val center: DoubleArray,
        var count: Long,
        var radiusEma: Double,
        var displayColor: Int
    )

    private val file = File(context.filesDir, FILE_NAME)
    private val prototypes = ArrayList<Prototype>()
    // Coarse feature index keeps unbounded codebook growth from turning every frame into
    // a full O(N) scan. Only the local bucket and one-axis neighbor buckets are searched.
    private val prototypeBuckets = mutableMapOf<Int, MutableList<Prototype>>()
    private val recentNovelty = ArrayDeque<Boolean>()
    private var nextOrdinal = 1
    private var acceptedPatches = 0L
    private var createdIds = 0L
    private var reusedIds = 0L
    private var matchDistanceSum = 0.0
    private var matchDistanceCount = 0L
    private var learnedStructureCount = 0

    init { load() }


    @Synchronized
    fun updateStructureContext(totalVisualMacros: Int, crossModalMacros: Int) {
        learnedStructureCount = totalVisualMacros.coerceAtLeast(0) + crossModalMacros.coerceAtLeast(0)
    }

    @Synchronized
    fun quantize(features: DoubleArray, packedRgb: Int, learn: Boolean = true): Result {
        require(features.size == 7) { "AdaptiveVisualQuantizer expects 7 features" }

        var nearest: Prototype? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for (p in candidatePrototypes(features)) {
            val d = distance(features, p.center)
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = p
            }
        }

        val p = nearest
        val radius = if (p == null) 0.0 else acceptanceRadius(p)
        val creationRadius = if (p == null) 0.0 else creationRadius(p)
        val create = p == null || nearestDistance > creationRadius

        // Step 42 PLAY path: nearest-neighbour recognition without changing the codebook.
        // Novel appearance stays unknown so PLAY cannot silently teach the visual alphabet.
        if (!learn) {
            val recognized = p != null && !create
            return Result(
                id = p?.id ?: ID_BASE - 1,
                created = false,
                matchDistance = if (p == null) 1.0 else nearestDistance,
                acceptanceRadius = radius,
                codebookSize = prototypes.size,
                displayColor = p?.displayColor ?: packedRgb,
                familiarity = p?.let { familiarity(it) } ?: 0.0,
                recognized = recognized
            )
        }

        acceptedPatches++
        val chosen: Prototype
        val usedDistance: Double
        val usedRadius: Double
        if (create) {
            chosen = Prototype(
                id = ID_BASE - nextOrdinal++,
                center = features.copyOf(),
                count = 1L,
                radiusEma = 0.045,
                displayColor = packedRgb
            )
            prototypes += chosen
            indexPrototype(chosen)
            createdIds++
            usedDistance = 0.0
            usedRadius = acceptanceRadius(chosen)
            pushRecent(true)
        } else {
            chosen = p!!
            usedDistance = nearestDistance
            usedRadius = radius
            reusedIds++
            pushRecent(false)
            updatePrototype(chosen, features, packedRgb, nearestDistance)
            matchDistanceSum += nearestDistance
            matchDistanceCount++
        }

        if (acceptedPatches % SAVE_EVERY_ACCEPTED == 0L) saveNow()

        return Result(
            id = chosen.id,
            created = create,
            matchDistance = usedDistance,
            acceptanceRadius = usedRadius,
            codebookSize = prototypes.size,
            displayColor = chosen.displayColor,
            familiarity = familiarity(chosen),
            recognized = true
        )
    }

    @Synchronized
    fun stats(): Stats = Stats(
        prototypes = prototypes.size,
        acceptedPatches = acceptedPatches,
        createdIds = createdIds,
        reusedIds = reusedIds,
        recentNoveltyRate = if (recentNovelty.isEmpty()) 0.0 else recentNovelty.count { it }.toDouble() / recentNovelty.size.toDouble(),
        meanMatchDistance = if (matchDistanceCount <= 0L) 0.0 else matchDistanceSum / matchDistanceCount.toDouble(),
        strongestPrototypeCount = prototypes.maxOfOrNull { it.count } ?: 0L,
        saturated = false
    )

    @Synchronized
    fun saveNow() {
        runCatching {
            val root = JSONObject()
                .put("format", FORMAT_VERSION)
                .put("nextOrdinal", nextOrdinal)
                .put("acceptedPatches", acceptedPatches)
                .put("createdIds", createdIds)
                .put("reusedIds", reusedIds)
                .put("matchDistanceSum", matchDistanceSum)
                .put("matchDistanceCount", matchDistanceCount)

            val array = JSONArray()
            prototypes.forEach { p ->
                val center = JSONArray()
                p.center.forEach { center.put(it) }
                array.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("count", p.count)
                        .put("radiusEma", p.radiusEma)
                        .put("displayColor", p.displayColor)
                        .put("center", center)
                )
            }
            root.put("prototypes", array)
            file.writeText(root.toString())
        }
    }

    @Synchronized
    fun reset() {
        prototypes.clear()
        prototypeBuckets.clear()
        recentNovelty.clear()
        nextOrdinal = 1
        acceptedPatches = 0L
        createdIds = 0L
        reusedIds = 0L
        matchDistanceSum = 0.0
        matchDistanceCount = 0L
        if (file.exists()) file.delete()
    }

    private fun updatePrototype(p: Prototype, x: DoubleArray, packedRgb: Int, distance: Double) {
        val oldBucket = bucketKey(p.center)
        p.count++
        val lr = (1.0 / sqrt(p.count.toDouble() + 28.0)).coerceIn(0.006, 0.085)
        for (i in p.center.indices) p.center[i] += lr * (x[i] - p.center[i])
        p.radiusEma = 0.96 * p.radiusEma + 0.04 * distance.coerceIn(0.0, 1.0)
        p.displayColor = blendRgb(p.displayColor, packedRgb, lr.coerceIn(0.01, 0.08))
        reindexPrototype(p, oldBucket)
    }

    private fun bucketCoords(values: DoubleArray): IntArray = IntArray(values.size) { i ->
        (values[i].coerceIn(0.0, 1.0) * 5.0).toInt().coerceIn(0, 4)
    }

    private fun bucketKey(values: DoubleArray): Int = bucketKey(bucketCoords(values))

    private fun bucketKey(coords: IntArray): Int {
        var key = 0
        for (c in coords) key = key * 5 + c.coerceIn(0, 4)
        return key
    }

    private fun candidatePrototypes(values: DoubleArray): List<Prototype> {
        if (prototypes.isEmpty()) return emptyList()
        val coords = bucketCoords(values)
        val out = LinkedHashSet<Prototype>()
        prototypeBuckets[bucketKey(coords)]?.let { out.addAll(it) }
        for (axis in coords.indices) {
            val original = coords[axis]
            if (original > 0) {
                coords[axis] = original - 1
                prototypeBuckets[bucketKey(coords)]?.let { out.addAll(it) }
            }
            if (original < 4) {
                coords[axis] = original + 1
                prototypeBuckets[bucketKey(coords)]?.let { out.addAll(it) }
            }
            coords[axis] = original
        }
        return out.toList()
    }

    private fun indexPrototype(p: Prototype) {
        prototypeBuckets.getOrPut(bucketKey(p.center)) { ArrayList() }.add(p)
    }

    private fun reindexPrototype(p: Prototype, oldBucket: Int) {
        val newBucket = bucketKey(p.center)
        if (oldBucket == newBucket) return
        prototypeBuckets[oldBucket]?.let { list ->
            list.remove(p)
            if (list.isEmpty()) prototypeBuckets.remove(oldBucket)
        }
        prototypeBuckets.getOrPut(newBucket) { ArrayList() }.add(p)
    }

    private fun familiarity(p: Prototype): Double =
        (p.count.toDouble() / (p.count.toDouble() + 20.0)).coerceIn(0.0, 1.0)

    private fun creationRadius(p: Prototype): Double {
        val familiar = familiarity(p)
        val structuralResolution = (1.0 - 1.0 / (1.0 + ln(1.0 + learnedStructureCount.toDouble()))).coerceIn(0.0, 0.85)
        return (acceptanceRadius(p) * (1.10 - 0.38 * familiar - 0.18 * structuralResolution)).coerceAtLeast(0.032)
    }

    private fun acceptanceRadius(p: Prototype): Double {
        val familiarity = min(0.025, ln(1.0 + p.count.toDouble()) * 0.0032)
        return (0.055 + p.radiusEma * 0.80 + familiarity).coerceIn(0.070, 0.205)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var weighted = 0.0
        var totalWeight = 0.0
        for (i in a.indices) {
            val w = FEATURE_WEIGHTS[i]
            val d = a[i] - b[i]
            weighted += w * d * d
            totalWeight += w
        }
        return sqrt(weighted / max(1e-9, totalWeight)).coerceIn(0.0, 1.0)
    }

    private fun pushRecent(isNew: Boolean) {
        recentNovelty.addLast(isNew)
        while (recentNovelty.size > RECENT_WINDOW) recentNovelty.removeFirst()
    }

    private fun blendRgb(a: Int, b: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xff
            val cb = (b shr shift) and 0xff
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("format", 0) != FORMAT_VERSION) return@runCatching
            nextOrdinal = root.optInt("nextOrdinal", 1).coerceAtLeast(1)
            acceptedPatches = root.optLong("acceptedPatches", 0L)
            createdIds = root.optLong("createdIds", 0L)
            reusedIds = root.optLong("reusedIds", 0L)
            matchDistanceSum = root.optDouble("matchDistanceSum", 0.0)
            matchDistanceCount = root.optLong("matchDistanceCount", 0L)
            val array = root.optJSONArray("prototypes") ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val c = o.optJSONArray("center") ?: continue
                if (c.length() != 7) continue
                prototypes += Prototype(
                    id = o.optInt("id"),
                    center = DoubleArray(7) { idx -> c.optDouble(idx, 0.0).coerceIn(0.0, 1.0) },
                    count = o.optLong("count", 1L).coerceAtLeast(1L),
                    radiusEma = o.optDouble("radiusEma", 0.055).coerceIn(0.0, 1.0),
                    displayColor = o.optInt("displayColor", 0)
                )
            }
            prototypeBuckets.clear()
            prototypes.forEach { indexPrototype(it) }
        }.onFailure {
            prototypes.clear()
            prototypeBuckets.clear()
            nextOrdinal = 1
        }
    }
}
