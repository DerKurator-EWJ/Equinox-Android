package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Step 12 adaptive, FFT-free vector quantizer.
 *
 * Unlike DirectAudioQuantizer's fixed 16-bit bins, this encoder starts with an empty
 * codebook. Every accepted PCM frame becomes a normalized six-dimensional time-domain
 * feature vector. It is matched against learned prototypes:
 *
 *   PCM -> feature vector -> nearest learned prototype -> stable ID
 *
 * If no prototype is close enough, a new stable ID is created. Reused prototypes move
 * slowly toward their observations and learn a local acceptance radius. Existing IDs
 * never change identity, so MemoryModel can safely store them over long periods.
 *
 * This is online vector quantization / codebook learning. Step 25 removes the former
 * 4096-ID ceiling. Identity growth is now evidence-driven: familiar, heavily reused
 * prototypes may split more finely while weakly supported regions stay coarse.
 */
class AdaptiveAudioQuantizer(private val context: Context) {
    companion object {
        private const val FILE_NAME = "equinox_v06_adaptive_quantizer.json"
        private const val FORMAT_VERSION = 1
        private const val ID_BASE = -1_000_000
        private const val SILENCE_RMS = 0.0025
        private const val SAVE_EVERY_ACCEPTED = 128L
        private const val RECENT_WINDOW = 256
        private val TEST_LAGS = intArrayOf(4, 8, 16, 32, 64, 128, 256, 512)
        private val FEATURE_WEIGHTS = doubleArrayOf(1.00, 1.00, 0.75, 1.15, 0.65, 0.65)

        /** Output-only pitch mapping. No acoustic frequency is stored in the adaptive ID. */
        fun projectionHzForId(id: Int): Double {
            val ordinal = abs(id.toLong() - ID_BASE.toLong()).coerceAtLeast(1L)
            val semitone = ((ordinal * 17L + (ordinal shr 3)) % 60L).toInt()
            return 82.4069 * Math.pow(2.0, semitone / 12.0)
        }
    }

    data class Result(
        val id: Int,
        val activity: Double,
        val silent: Boolean,
        val created: Boolean,
        val matchDistance: Double,
        val acceptanceRadius: Double,
        val codebookSize: Int,
        val familiarity: Double,
        val debugText: String,
        val recognized: Boolean = true
    )

    data class Stats(
        val prototypes: Int,
        val acceptedFrames: Long,
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
        var radiusEma: Double
    )

    private data class Features(
        val values: DoubleArray,
        val rms: Double,
        val silent: Boolean,
        val debug: String
    )

    private val file = File(context.filesDir, FILE_NAME)
    private val prototypes = ArrayList<Prototype>()
    // Coarse feature index keeps unbounded codebook growth from turning every frame into
    // a full O(N) scan. Only the local bucket and one-axis neighbor buckets are searched.
    private val prototypeBuckets = mutableMapOf<Int, MutableList<Prototype>>()
    private val recentNovelty = ArrayDeque<Boolean>()
    private var nextOrdinal = 1
    private var acceptedFrames = 0L
    private var createdIds = 0L
    private var reusedIds = 0L
    private var matchDistanceSum = 0.0
    private var matchDistanceCount = 0L
    private var learnedStructureCount = 0

    init { load() }


    /** Supplies slow-changing context from the relational core; it is not an ID cap. */
    @Synchronized
    fun updateStructureContext(totalAdaptiveMacros: Int, totalMacroRelations: Int) {
        learnedStructureCount = (totalAdaptiveMacros.coerceAtLeast(0) + totalMacroRelations.coerceAtLeast(0) / 4)
    }

    @Synchronized
    fun quantize(frame: ShortArray, learn: Boolean = true): Result {
        val f = extractFeatures(frame)
        if (f.silent) {
            return Result(
                id = ID_BASE - 1,
                activity = f.rms,
                silent = true,
                created = false,
                matchDistance = 0.0,
                acceptanceRadius = 0.0,
                codebookSize = prototypes.size,
                familiarity = 0.0,
                debugText = "Stille",
                recognized = false
            )
        }

        var nearest: Prototype? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for (p in candidatePrototypes(f.values)) {
            val d = distance(f.values, p.center)
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = p
            }
        }

        val p = nearest
        val radius = if (p == null) 0.0 else acceptanceRadius(p)
        val creationRadius = if (p == null) 0.0 else creationRadius(p)
        val create = p == null || nearestDistance > creationRadius

        // Step 42 PLAY path: classify only against the existing codebook. Novel input is
        // deliberately left unrecognized instead of being forced into the nearest identity.
        if (!learn) {
            val recognized = p != null && !create
            return Result(
                id = p?.id ?: ID_BASE - 1,
                activity = f.rms.coerceIn(0.0, 1.0),
                silent = false,
                created = false,
                matchDistance = if (p == null) 1.0 else nearestDistance,
                acceptanceRadius = radius,
                codebookSize = prototypes.size,
                familiarity = p?.let { familiarity(it) } ?: 0.0,
                debugText = if (recognized) "PLAY reuse · ${f.debug}" else "PLAY unbekannt · ${f.debug}",
                recognized = recognized
            )
        }

        acceptedFrames++
        val chosen: Prototype
        val usedDistance: Double
        val usedRadius: Double
        if (create) {
            val id = ID_BASE - nextOrdinal
            nextOrdinal++
            chosen = Prototype(
                id = id,
                center = f.values.copyOf(),
                count = 1L,
                radiusEma = 0.015
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
            updatePrototype(chosen, f.values, nearestDistance)
            matchDistanceSum += nearestDistance
            matchDistanceCount++
        }

        if (acceptedFrames % SAVE_EVERY_ACCEPTED == 0L) saveNow()

        return Result(
            id = chosen.id,
            activity = f.rms.coerceIn(0.0, 1.0),
            silent = false,
            created = create,
            matchDistance = usedDistance,
            acceptanceRadius = usedRadius,
            codebookSize = prototypes.size,
            familiarity = familiarity(chosen),
            debugText = buildString {
                append(if (create) "NEU" else "reuse")
                append(" · ").append(f.debug)
                if (!create) append(" · d ").append("%.3f".format(usedDistance)).append("/r ").append("%.3f".format(usedRadius))
                append(" · fam ").append("%.2f".format(familiarity(chosen)))
            },
            recognized = true
        )
    }

    @Synchronized
    fun stats(): Stats = Stats(
        prototypes = prototypes.size,
        acceptedFrames = acceptedFrames,
        createdIds = createdIds,
        reusedIds = reusedIds,
        recentNoveltyRate = if (recentNovelty.isEmpty()) 0.0 else recentNovelty.count { it }.toDouble() / recentNovelty.size.toDouble(),
        meanMatchDistance = if (matchDistanceCount <= 0L) 0.0 else matchDistanceSum / matchDistanceCount.toDouble(),
        strongestPrototypeCount = prototypes.maxOfOrNull { it.count } ?: 0L,
        saturated = false
    )

    @Synchronized
    fun saveNow() {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("nextOrdinal", nextOrdinal)
        root.put("acceptedFrames", acceptedFrames)
        root.put("createdIds", createdIds)
        root.put("reusedIds", reusedIds)
        root.put("matchDistanceSum", matchDistanceSum)
        root.put("matchDistanceCount", matchDistanceCount)
        val array = JSONArray()
        prototypes.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("count", p.count)
            o.put("radiusEma", p.radiusEma)
            val c = JSONArray()
            p.center.forEach { c.put(it) }
            o.put("center", c)
            array.put(o)
        }
        root.put("prototypes", array)
        file.writeText(root.toString())
    }

    @Synchronized
    fun reset() {
        prototypes.clear()
        prototypeBuckets.clear()
        recentNovelty.clear()
        nextOrdinal = 1
        acceptedFrames = 0L
        createdIds = 0L
        reusedIds = 0L
        matchDistanceSum = 0.0
        matchDistanceCount = 0L
        if (file.exists()) file.delete()
    }

    private fun updatePrototype(p: Prototype, x: DoubleArray, distance: Double) {
        val oldBucket = bucketKey(p.center)
        p.count++
        // Learning slows continuously with experience, but never becomes completely rigid.
        val lr = (1.0 / sqrt(p.count.toDouble() + 24.0)).coerceIn(0.008, 0.10)
        for (i in p.center.indices) p.center[i] += lr * (x[i] - p.center[i])
        p.radiusEma = 0.95 * p.radiusEma + 0.05 * distance.coerceIn(0.0, 1.0)
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
        (p.count.toDouble() / (p.count.toDouble() + 24.0)).coerceIn(0.0, 1.0)

    /**
     * Familiar prototypes are allowed to resolve finer distinctions. This does not impose
     * a global codebook capacity; it only changes the local split threshold.
     */
    private fun creationRadius(p: Prototype): Double {
        val familiar = familiarity(p)
        val structuralResolution = (1.0 - 1.0 / (1.0 + ln(1.0 + learnedStructureCount.toDouble()))).coerceIn(0.0, 0.85)
        return (acceptanceRadius(p) * (1.08 - 0.34 * familiar - 0.16 * structuralResolution)).coerceAtLeast(0.024)
    }

    private fun acceptanceRadius(p: Prototype): Double {
        // Frequently reused clusters are allowed to learn their own local spread. A hard
        // clamp prevents one prototype from swallowing the entire acoustic feature space.
        val familiarity = min(0.025, kotlin.math.ln(1.0 + p.count.toDouble()) * 0.0035)
        return (0.035 + p.radiusEma * 0.85 + familiarity).coerceIn(0.048, 0.190)
    }

    private fun pushRecent(isNew: Boolean) {
        recentNovelty.addLast(isNew)
        while (recentNovelty.size > RECENT_WINDOW) recentNovelty.removeFirst()
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

    private fun extractFeatures(frame: ShortArray): Features {
        if (frame.isEmpty()) return Features(DoubleArray(6), 0.0, true, "leer")

        var sumSq = 0.0
        var zeroCrossings = 0
        var diffSum = 0.0
        var previous = frame[0].toDouble() / 32768.0
        for (i in frame.indices) {
            val x = frame[i].toDouble() / 32768.0
            sumSq += x * x
            if (i > 0) {
                if ((x >= 0.0) != (previous >= 0.0)) zeroCrossings++
                diffSum += abs(x - previous)
            }
            previous = x
        }
        val rms = sqrt(sumSq / frame.size.toDouble())
        if (rms < SILENCE_RMS) return Features(DoubleArray(6), rms, true, "Stille")

        val zcrRaw = zeroCrossings.toDouble() / max(1, frame.size - 1).toDouble()
        // Log zero-crossing pitch proxy: still purely time-domain, but octave changes are
        // no longer compressed into tiny linear ZCR differences.
        val zcrHzProxy = (zcrRaw * 24_000.0).coerceIn(40.0, 18_000.0)
        val zcr = (ln(zcrHzProxy / 40.0) / ln(18_000.0 / 40.0)).coerceIn(0.0, 1.0)
        val roughness = ((diffSum / max(1, frame.size - 1).toDouble()) / max(rms, 1e-9) / 2.8).coerceIn(0.0, 1.0)

        var bestLagIndex = 0
        var bestCorrelation = -1.0
        TEST_LAGS.forEachIndexed { index, lag ->
            if (lag >= frame.size) return@forEachIndexed
            var xy = 0.0
            var xx = 0.0
            var yy = 0.0
            var i = lag
            while (i < frame.size) {
                val a = frame[i].toDouble()
                val b = frame[i - lag].toDouble()
                xy += a * b
                xx += a * a
                yy += b * b
                i++
            }
            val corr = abs(xy / sqrt(max(1.0, xx * yy))).coerceIn(0.0, 1.0)
            if (corr > bestCorrelation) {
                bestCorrelation = corr
                bestLagIndex = index
            }
        }
        val lag = bestLagIndex.toDouble() / (TEST_LAGS.size - 1).toDouble()
        val periodicity = bestCorrelation.coerceIn(0.0, 1.0)

        val quarter = max(1, frame.size / 4)
        val env = DoubleArray(4)
        for (q in 0 until 4) {
            val start = q * quarter
            val end = if (q == 3) frame.size else min(frame.size, start + quarter)
            var sum = 0.0
            for (i in start until end) sum += abs(frame[i].toDouble() / 32768.0)
            env[q] = if (end > start) sum / (end - start).toDouble() else 0.0
        }
        val firstHalf = (env[0] + env[1]) * 0.5
        val secondHalf = (env[2] + env[3]) * 0.5
        val slopeSigned = ((secondHalf - firstHalf) / max(rms, 1e-9)).coerceIn(-1.0, 1.0)
        val slope = (slopeSigned + 1.0) * 0.5
        val pulse = (((env.maxOrNull() ?: 0.0) - (env.minOrNull() ?: 0.0)) / max(rms * 1.5, 1e-9)).coerceIn(0.0, 1.0)

        return Features(
            values = doubleArrayOf(zcr, roughness, lag, periodicity, slope, pulse),
            rms = rms,
            silent = false,
            debug = "z %.2f · r %.2f · lag %.2f · p %.2f · env %.2f/%.2f".format(zcr, roughness, lag, periodicity, slope, pulse)
        )
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("format", 0) != FORMAT_VERSION) return@runCatching
            nextOrdinal = root.optInt("nextOrdinal", 1).coerceAtLeast(1)
            acceptedFrames = root.optLong("acceptedFrames", 0L)
            createdIds = root.optLong("createdIds", 0L)
            reusedIds = root.optLong("reusedIds", 0L)
            matchDistanceSum = root.optDouble("matchDistanceSum", 0.0)
            matchDistanceCount = root.optLong("matchDistanceCount", 0L)
            val array = root.optJSONArray("prototypes") ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val centerJson = o.optJSONArray("center") ?: continue
                if (centerJson.length() != 6) continue
                val center = DoubleArray(6) { idx -> centerJson.optDouble(idx, 0.0).coerceIn(0.0, 1.0) }
                prototypes += Prototype(
                    id = o.optInt("id"),
                    center = center,
                    count = o.optLong("count", 1L).coerceAtLeast(1L),
                    radiusEma = o.optDouble("radiusEma", 0.055).coerceIn(0.0, 1.0)
                )
            }
            prototypeBuckets.clear()
            prototypes.forEach { indexPrototype(it) }
        }.onFailure {
            // Corrupt encoder state should never prevent the app from starting.
            prototypes.clear()
            prototypeBuckets.clear()
            nextOrdinal = 1
        }
    }
}
