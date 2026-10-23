package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class DiagnosticSample(
    val sampleIndex: Long,
    val observations: Long,
    val microCodes: Int,
    val macroCodes: Int,
    val sequenceCodes: Int,
    val relations: Int,
    val crossModalRelations: Int,
    val memoryFileBytes: Long,
    val javaHeapUsedBytes: Long
)

data class DiagnosticSummary(
    val samples: Int,
    val newMicroSinceFirst: Int,
    val newMacroSinceFirst: Int,
    val newSequencesSinceFirst: Int,
    val observationsSinceFirst: Long,
    val microPer1000Observations: Double,
    val sequencePer1000Observations: Double,
    val latestMemoryBytes: Long,
    val latestHeapBytes: Long
)

class DiagnosticsManager(private val context: Context) {
    private val file = File(context.filesDir, "equinox_v06_diagnostics.json")
    private val samples = mutableListOf<DiagnosticSample>()
    private var nextSampleIndex = 1L

    companion object {
        private const val MAX_SAMPLES = 240
    }

    init { load() }

    fun capture(stats: MemoryStats): DiagnosticSample {
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()

        val sample = DiagnosticSample(
            sampleIndex = nextSampleIndex++,
            observations = stats.observations,
            microCodes = stats.persistentCodes,
            macroCodes = stats.macroCodes,
            sequenceCodes = stats.sequenceCodes,
            relations = stats.relations + stats.macroRelations,
            crossModalRelations = stats.crossModalRelations,
            memoryFileBytes = stats.storageBytes,
            javaHeapUsedBytes = heapUsed
        )

        samples += sample
        if (samples.size > MAX_SAMPLES) {
            val drop = samples.size - MAX_SAMPLES
            repeat(drop) { samples.removeAt(0) }
        }
        save()
        return sample
    }

    fun summary(): DiagnosticSummary {
        if (samples.isEmpty()) {
            return DiagnosticSummary(
                samples = 0,
                newMicroSinceFirst = 0,
                newMacroSinceFirst = 0,
                newSequencesSinceFirst = 0,
                observationsSinceFirst = 0L,
                microPer1000Observations = 0.0,
                sequencePer1000Observations = 0.0,
                latestMemoryBytes = 0L,
                latestHeapBytes = 0L
            )
        }

        val first = samples.first()
        val last = samples.last()
        val obsDelta = max(0L, last.observations - first.observations)
        val microDelta = max(0, last.microCodes - first.microCodes)
        val macroDelta = max(0, last.macroCodes - first.macroCodes)
        val sequenceDelta = max(0, last.sequenceCodes - first.sequenceCodes)

        val denom = if (obsDelta > 0L) obsDelta.toDouble() else 1.0

        return DiagnosticSummary(
            samples = samples.size,
            newMicroSinceFirst = microDelta,
            newMacroSinceFirst = macroDelta,
            newSequencesSinceFirst = sequenceDelta,
            observationsSinceFirst = obsDelta,
            microPer1000Observations = microDelta * 1000.0 / denom,
            sequencePer1000Observations = sequenceDelta * 1000.0 / denom,
            latestMemoryBytes = last.memoryFileBytes,
            latestHeapBytes = last.javaHeapUsedBytes
        )
    }

    fun latest(): DiagnosticSample? = samples.lastOrNull()

    fun reset() {
        samples.clear()
        nextSampleIndex = 1L
        if (file.exists()) file.delete()
    }

    fun exportCsvText(): String {
        val sb = StringBuilder()
        sb.appendLine("sampleIndex,observations,microCodes,macroCodes,sequenceCodes,relations,crossModalRelations,memoryFileBytes,javaHeapUsedBytes")
        samples.forEach { s ->
            sb.appendLine(
                listOf(
                    s.sampleIndex,
                    s.observations,
                    s.microCodes,
                    s.macroCodes,
                    s.sequenceCodes,
                    s.relations,
                    s.crossModalRelations,
                    s.memoryFileBytes,
                    s.javaHeapUsedBytes
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    fun writeCsvExport(): File {
        val out = File(context.filesDir, "equinox_v06_diagnostics.csv")
        out.writeText(exportCsvText())
        return out
    }

    private fun save() {
        try {
            val root = JSONObject()
            root.put("nextSampleIndex", nextSampleIndex)
            val arr = JSONArray()

            samples.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("sampleIndex", s.sampleIndex)
                        .put("observations", s.observations)
                        .put("microCodes", s.microCodes)
                        .put("macroCodes", s.macroCodes)
                        .put("sequenceCodes", s.sequenceCodes)
                        .put("relations", s.relations)
                        .put("crossModalRelations", s.crossModalRelations)
                        .put("memoryFileBytes", s.memoryFileBytes)
                        .put("javaHeapUsedBytes", s.javaHeapUsedBytes)
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
            nextSampleIndex = root.optLong("nextSampleIndex", 1L)

            val arr = root.optJSONArray("samples") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                samples += DiagnosticSample(
                    sampleIndex = o.optLong("sampleIndex", i + 1L),
                    observations = o.optLong("observations", 0L),
                    microCodes = o.optInt("microCodes", 0),
                    macroCodes = o.optInt("macroCodes", 0),
                    sequenceCodes = o.optInt("sequenceCodes", 0),
                    relations = o.optInt("relations", 0),
                    crossModalRelations = o.optInt("crossModalRelations", 0),
                    memoryFileBytes = o.optLong("memoryFileBytes", 0L),
                    javaHeapUsedBytes = o.optLong("javaHeapUsedBytes", 0L)
                )
            }
        } catch (_: Exception) {
        }
    }
}
