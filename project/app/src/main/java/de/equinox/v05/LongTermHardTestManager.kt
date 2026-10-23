package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Step 24: long-term hard-test recorder.
 *
 * This deliberately does not modify learning. It periodically snapshots already existing
 * metrics so that convergence, compression, prediction, forgetting and resource growth can
 * be inspected over hours/days instead of inferred from one screenshot.
 */
data class HardTestSample(
    val index: Long,
    val epochMs: Long,
    val observations: Long,
    val persistentCodes: Int,
    val macroCodes: Int,
    val macroRelations: Int,
    val crossModalMacros: Int,
    val audioNovelty: Double,
    val visualNovelty: Double,
    val compressionGain: Double,
    val predictionChecks: Long,
    val predictionHits: Long,
    val competitionPruned: Long,
    val storageBytes: Long,
    val diskBackedMacros: Int,
    val tokenReads: Long,
    val heapBytes: Long
)

data class HardTestSummary(
    val running: Boolean,
    val samples: Int,
    val elapsedMs: Long,
    val observationsDelta: Long,
    val macroDelta: Int,
    val relationDelta: Int,
    val storageDeltaBytes: Long,
    val heapDeltaBytes: Long,
    val macrosPer10kObservations: Double,
    val bytesPer10kObservations: Double,
    val latestAudioNovelty: Double,
    val latestVisualNovelty: Double,
    val latestCompressionGain: Double,
    val intervalPredictionChecks: Long,
    val intervalPredictionHits: Long,
    val intervalPredictionHitRate: Double,
    val prunedDelta: Long,
    val earlyMacroRate: Double?,
    val recentMacroRate: Double?,
    val macroGrowthTrend: String,
    val latestStorageBytes: Long,
    val latestHeapBytes: Long,
    val latestDiskBackedMacros: Int,
    val latestTokenReads: Long
)

class LongTermHardTestManager(private val context: Context) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L
        private const val MAX_SAMPLES = 2_016 // seven days at five-minute sampling
        private const val FILE_NAME = "equinox_v06_hard_test.json"
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val samples = mutableListOf<HardTestSample>()
    private var nextIndex = 1L
    private var running = false
    private var startedEpochMs = 0L

    init { load() }

    @Synchronized
    fun startNewSession(now: Long = System.currentTimeMillis()) {
        samples.clear()
        nextIndex = 1L
        running = true
        startedEpochMs = now
        save()
    }

    @Synchronized
    fun stop() {
        running = false
        save()
    }

    @Synchronized
    fun isRunning(): Boolean = running

    @Synchronized
    fun capture(
        memoryStats: MemoryStats,
        audioStats: AdaptiveAudioQuantizer.Stats,
        visualStats: AdaptiveVisualQuantizer.Stats,
        now: Long = System.currentTimeMillis()
    ): HardTestSample {
        val runtime = Runtime.getRuntime()
        val heap = runtime.totalMemory() - runtime.freeMemory()
        val sample = HardTestSample(
            index = nextIndex++,
            epochMs = now,
            observations = memoryStats.observations,
            persistentCodes = memoryStats.persistentCodes,
            macroCodes = memoryStats.macroCodes,
            macroRelations = memoryStats.macroRelations,
            crossModalMacros = memoryStats.crossModalMacros,
            audioNovelty = audioStats.recentNoveltyRate.coerceIn(0.0, 1.0),
            visualNovelty = visualStats.recentNoveltyRate.coerceIn(0.0, 1.0),
            compressionGain = memoryStats.hierarchyBaseCompressionGain.coerceIn(0.0, 1.0),
            predictionChecks = memoryStats.predictionChecks,
            predictionHits = memoryStats.predictionHits,
            competitionPruned = memoryStats.competitionMacrosPruned,
            storageBytes = memoryStats.storageBytes,
            diskBackedMacros = memoryStats.storageDiskBackedMacros,
            tokenReads = memoryStats.storageTokenReads,
            heapBytes = heap
        )
        samples += sample
        if (samples.size > MAX_SAMPLES) {
            repeat(samples.size - MAX_SAMPLES) { samples.removeAt(0) }
        }
        save()
        return sample
    }

    @Synchronized
    fun summary(now: Long = System.currentTimeMillis()): HardTestSummary {
        if (samples.isEmpty()) {
            return HardTestSummary(
                running, 0, 0L, 0L, 0, 0, 0L, 0L,
                0.0, 0.0, 0.0, 0.0, 0.0,
                0L, 0L, 0.0, 0L, null, null, "noch keine Daten",
                0L, 0L, 0, 0L
            )
        }

        val first = samples.first()
        val last = samples.last()
        val obsDelta = max(0L, last.observations - first.observations)
        val macroDelta = last.macroCodes - first.macroCodes
        val relationDelta = last.macroRelations - first.macroRelations
        val storageDelta = last.storageBytes - first.storageBytes
        val heapDelta = last.heapBytes - first.heapBytes
        val denom = if (obsDelta > 0L) obsDelta.toDouble() else 1.0
        val predChecks = max(0L, last.predictionChecks - first.predictionChecks)
        val predHits = max(0L, last.predictionHits - first.predictionHits)
        val predRate = if (predChecks > 0L) predHits.toDouble() / predChecks else 0.0
        val pruned = max(0L, last.competitionPruned - first.competitionPruned)

        val earlyRate = if (samples.size >= 4) rate(samples[0], samples[minOf(2, samples.lastIndex)]) else null
        val recentRate = if (samples.size >= 4) rate(samples[max(0, samples.lastIndex - 2)], samples.last()) else null
        val trend = when {
            earlyRate == null || recentRate == null -> "zu wenig Daten"
            earlyRate < 1e-9 && recentRate < 1e-9 -> "stabil niedrig"
            earlyRate < 1e-9 -> "steigend"
            recentRate < earlyRate * 0.75 -> "abnehmend"
            recentRate > earlyRate * 1.25 -> "steigend"
            else -> "ähnlich"
        }

        val elapsed = if (samples.size >= 2) {
            max(0L, last.epochMs - first.epochMs)
        } else if (running && startedEpochMs > 0L) {
            max(0L, now - startedEpochMs)
        } else 0L

        return HardTestSummary(
            running = running,
            samples = samples.size,
            elapsedMs = elapsed,
            observationsDelta = obsDelta,
            macroDelta = macroDelta,
            relationDelta = relationDelta,
            storageDeltaBytes = storageDelta,
            heapDeltaBytes = heapDelta,
            macrosPer10kObservations = macroDelta * 10_000.0 / denom,
            bytesPer10kObservations = storageDelta * 10_000.0 / denom,
            latestAudioNovelty = last.audioNovelty,
            latestVisualNovelty = last.visualNovelty,
            latestCompressionGain = last.compressionGain,
            intervalPredictionChecks = predChecks,
            intervalPredictionHits = predHits,
            intervalPredictionHitRate = predRate,
            prunedDelta = pruned,
            earlyMacroRate = earlyRate,
            recentMacroRate = recentRate,
            macroGrowthTrend = trend,
            latestStorageBytes = last.storageBytes,
            latestHeapBytes = last.heapBytes,
            latestDiskBackedMacros = last.diskBackedMacros,
            latestTokenReads = last.tokenReads
        )
    }

    @Synchronized
    fun reset() {
        running = false
        startedEpochMs = 0L
        samples.clear()
        nextIndex = 1L
        if (file.exists()) file.delete()
    }

    @Synchronized
    fun writeCsvExport(): File {
        val out = File(context.filesDir, "equinox_v06_hard_test.csv")
        out.writeText(exportCsvText())
        return out
    }

    @Synchronized
    fun exportCsvText(): String {
        val sb = StringBuilder()
        sb.appendLine("index,epochMs,observations,persistentCodes,macroCodes,macroRelations,crossModalMacros,audioNovelty,visualNovelty,compressionGain,predictionChecks,predictionHits,competitionPruned,storageBytes,diskBackedMacros,tokenReads,heapBytes")
        samples.forEach { s ->
            sb.appendLine(
                listOf(
                    s.index, s.epochMs, s.observations, s.persistentCodes, s.macroCodes,
                    s.macroRelations, s.crossModalMacros, s.audioNovelty, s.visualNovelty,
                    s.compressionGain, s.predictionChecks, s.predictionHits, s.competitionPruned,
                    s.storageBytes, s.diskBackedMacros, s.tokenReads, s.heapBytes
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    private fun rate(a: HardTestSample, b: HardTestSample): Double? {
        val dObs = b.observations - a.observations
        if (dObs <= 0L) return null
        return (b.macroCodes - a.macroCodes) * 10_000.0 / dObs.toDouble()
    }

    private fun save() {
        try {
            val root = JSONObject()
                .put("nextIndex", nextIndex)
                .put("running", running)
                .put("startedEpochMs", startedEpochMs)
            val arr = JSONArray()
            samples.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("index", s.index)
                        .put("epochMs", s.epochMs)
                        .put("observations", s.observations)
                        .put("persistentCodes", s.persistentCodes)
                        .put("macroCodes", s.macroCodes)
                        .put("macroRelations", s.macroRelations)
                        .put("crossModalMacros", s.crossModalMacros)
                        .put("audioNovelty", s.audioNovelty)
                        .put("visualNovelty", s.visualNovelty)
                        .put("compressionGain", s.compressionGain)
                        .put("predictionChecks", s.predictionChecks)
                        .put("predictionHits", s.predictionHits)
                        .put("competitionPruned", s.competitionPruned)
                        .put("storageBytes", s.storageBytes)
                        .put("diskBackedMacros", s.diskBackedMacros)
                        .put("tokenReads", s.tokenReads)
                        .put("heapBytes", s.heapBytes)
                )
            }
            root.put("samples", arr)
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            nextIndex = root.optLong("nextIndex", 1L)
            running = root.optBoolean("running", false)
            startedEpochMs = root.optLong("startedEpochMs", 0L)
            val arr = root.optJSONArray("samples") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                samples += HardTestSample(
                    index = o.optLong("index", i + 1L),
                    epochMs = o.optLong("epochMs", 0L),
                    observations = o.optLong("observations", 0L),
                    persistentCodes = o.optInt("persistentCodes", 0),
                    macroCodes = o.optInt("macroCodes", 0),
                    macroRelations = o.optInt("macroRelations", 0),
                    crossModalMacros = o.optInt("crossModalMacros", 0),
                    audioNovelty = o.optDouble("audioNovelty", 0.0),
                    visualNovelty = o.optDouble("visualNovelty", 0.0),
                    compressionGain = o.optDouble("compressionGain", 0.0),
                    predictionChecks = o.optLong("predictionChecks", 0L),
                    predictionHits = o.optLong("predictionHits", 0L),
                    competitionPruned = o.optLong("competitionPruned", 0L),
                    storageBytes = o.optLong("storageBytes", 0L),
                    diskBackedMacros = o.optInt("diskBackedMacros", 0),
                    tokenReads = o.optLong("tokenReads", 0L),
                    heapBytes = o.optLong("heapBytes", 0L)
                )
            }
        } catch (_: Exception) {
            running = false
            startedEpochMs = 0L
            samples.clear()
            nextIndex = 1L
        }
    }
}
