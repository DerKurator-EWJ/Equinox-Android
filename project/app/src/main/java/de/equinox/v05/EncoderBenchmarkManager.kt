package de.equinox.v05

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import kotlin.math.ln
import kotlin.math.max

/**
 * Step 11 parallel encoder convergence benchmark.
 *
 * This is deliberately outside the Equinox learning rules. It observes the two
 * encoder identity streams while both are fed the same PCM frame and reports
 * recurrence/convergence diagnostics. It never changes a quant ID, macro, relation,
 * rank or relevance value.
 *
 * Important: "more converged" here only means "reuses a smaller/stabler discrete
 * vocabulary on the tested stream". It is NOT a claim that the encoder preserves
 * more acoustic information or is semantically better.
 */
data class EncoderTrackSummary(
    val inputFrames: Long,
    val acceptedFrames: Long,
    val uniqueIds: Int,
    val newIdsPer1000Accepted: Double,
    val revisitRate: Double,
    val immediateRepeatRate: Double,
    val recentNoveltyRate: Double,
    val transitions: Long,
    val uniqueTransitions: Int,
    val recentTransitionNoveltyRate: Double,
    val entropyBits: Double,
    val quantDelta: Int,
    val macroDelta: Int,
    val macrosPer1000Accepted: Double
)

data class EncoderBenchmarkSummary(
    val active: Boolean,
    val pairedFrames: Long,
    val fft: EncoderTrackSummary,
    val direct: EncoderTrackSummary,
    val interpretation: String
)

private data class BenchmarkSample(
    val pairedFrames: Long,
    val fftUnique: Int,
    val fftRecentNovelty: Double,
    val fftTransitionNovelty: Double,
    val fftEntropy: Double,
    val fftMacros: Int,
    val directUnique: Int,
    val directRecentNovelty: Double,
    val directTransitionNovelty: Double,
    val directEntropy: Double,
    val directMacros: Int
)

class EncoderBenchmarkManager(private val context: Context) {
    companion object {
        private const val ROLLING_WINDOW = 256
        private const val SAMPLE_EVERY_FRAMES = 64L
        private const val MAX_SAMPLES = 1_500

        private fun pushRolling(queue: ArrayDeque<Boolean>, value: Boolean) {
            queue.addLast(value)
            while (queue.size > ROLLING_WINDOW) queue.removeFirst()
        }

        private fun rollingRate(queue: ArrayDeque<Boolean>): Double =
            if (queue.isEmpty()) 0.0 else queue.count { it }.toDouble() / queue.size.toDouble()

        private fun orderedPairKey(a: Int, b: Int): Long =
            (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
    }

    private class Track {
        var inputFrames = 0L
        var acceptedFrames = 0L
        val counts = HashMap<Int, Long>()
        var previousId: Int? = null
        var immediateRepeats = 0L
        var transitions = 0L
        val transitionSet = HashSet<Long>()
        val recentNovelty = ArrayDeque<Boolean>()
        val recentTransitionNovelty = ArrayDeque<Boolean>()
        var baselineQuants = 0
        var currentQuants = 0
        var baselineMacros = 0
        var currentMacros = 0

        fun reset(quants: Int, macros: Int) {
            inputFrames = 0L
            acceptedFrames = 0L
            counts.clear()
            previousId = null
            immediateRepeats = 0L
            transitions = 0L
            transitionSet.clear()
            recentNovelty.clear()
            recentTransitionNovelty.clear()
            baselineQuants = quants
            currentQuants = quants
            baselineMacros = macros
            currentMacros = macros
        }

        fun observe(id: Int?) {
            inputFrames++
            if (id == null) return
            acceptedFrames++

            val isNew = !counts.containsKey(id)
            counts[id] = (counts[id] ?: 0L) + 1L
            pushRolling(recentNovelty, isNew)

            val prev = previousId
            if (prev != null) {
                transitions++
                if (prev == id) immediateRepeats++
                val key = orderedPairKey(prev, id)
                val newTransition = transitionSet.add(key)
                pushRolling(recentTransitionNovelty, newTransition)
            }
            previousId = id
        }

        fun updateMemory(quants: Int, macros: Int) {
            currentQuants = quants
            currentMacros = macros
        }

        fun summary(): EncoderTrackSummary {
            val accepted = acceptedFrames.coerceAtLeast(1L).toDouble()
            val revisits = (acceptedFrames - counts.size.toLong()).coerceAtLeast(0L)
            val entropy = if (acceptedFrames <= 0L) 0.0 else {
                counts.values.sumOf { count ->
                    val p = count.toDouble() / acceptedFrames.toDouble()
                    if (p <= 0.0) 0.0 else -p * (ln(p) / ln(2.0))
                }
            }
            val macroDelta = (currentMacros - baselineMacros).coerceAtLeast(0)
            val quantDelta = (currentQuants - baselineQuants).coerceAtLeast(0)
            return EncoderTrackSummary(
                inputFrames = inputFrames,
                acceptedFrames = acceptedFrames,
                uniqueIds = counts.size,
                newIdsPer1000Accepted = counts.size * 1000.0 / accepted,
                revisitRate = revisits.toDouble() / accepted,
                immediateRepeatRate = if (transitions > 0L) immediateRepeats.toDouble() / transitions.toDouble() else 0.0,
                recentNoveltyRate = rollingRate(recentNovelty),
                transitions = transitions,
                uniqueTransitions = transitionSet.size,
                recentTransitionNoveltyRate = rollingRate(recentTransitionNovelty),
                entropyBits = entropy,
                quantDelta = quantDelta,
                macroDelta = macroDelta,
                macrosPer1000Accepted = macroDelta * 1000.0 / accepted
            )
        }
    }

    private val fft = Track()
    private val direct = Track()
    private val samples = mutableListOf<BenchmarkSample>()
    private var pairedFrames = 0L
    private var active = false

    @Synchronized
    fun resetAndStart(stats: MemoryStats) {
        fft.reset(stats.fftAudioCodes, stats.fftAudioMacros)
        direct.reset(stats.directAudioCodes, stats.directAudioMacros)
        pairedFrames = 0L
        samples.clear()
        active = true
    }

    @Synchronized
    fun stop() {
        active = false
    }

    @Synchronized
    fun isActive(): Boolean = active

    /** Records one PCM frame seen by both encoders. null means the encoder rejected it (e.g. direct silence gate). */
    @Synchronized
    fun recordPair(fftId: Int?, directId: Int?) {
        if (!active) return
        pairedFrames++
        fft.observe(fftId)
        direct.observe(directId)
        if (pairedFrames % SAMPLE_EVERY_FRAMES == 0L) captureSample()
    }

    @Synchronized
    fun updateMemory(stats: MemoryStats) {
        if (!active) return
        fft.updateMemory(stats.fftAudioCodes, stats.fftAudioMacros)
        direct.updateMemory(stats.directAudioCodes, stats.directAudioMacros)
    }

    @Synchronized
    fun summary(): EncoderBenchmarkSummary {
        val fs = fft.summary()
        val ds = direct.summary()
        return EncoderBenchmarkSummary(
            active = active,
            pairedFrames = pairedFrames,
            fft = fs,
            direct = ds,
            interpretation = interpret(fs, ds)
        )
    }

    @Synchronized
    fun writeCsvExport(): File {
        captureSample()
        val out = File(context.filesDir, "equinox_v06_encoder_benchmark.csv")
        val sb = StringBuilder()
        sb.appendLine("pairedFrames,fftUnique,fftRecentNovelty,fftTransitionNovelty,fftEntropyBits,fftMacroDelta,directUnique,directRecentNovelty,directTransitionNovelty,directEntropyBits,directMacroDelta")
        samples.forEach { s ->
            sb.appendLine(
                listOf(
                    s.pairedFrames,
                    s.fftUnique,
                    s.fftRecentNovelty,
                    s.fftTransitionNovelty,
                    s.fftEntropy,
                    s.fftMacros,
                    s.directUnique,
                    s.directRecentNovelty,
                    s.directTransitionNovelty,
                    s.directEntropy,
                    s.directMacros
                ).joinToString(",")
            )
        }
        out.writeText(sb.toString())
        return out
    }

    @Synchronized
    fun clear() {
        active = false
        pairedFrames = 0L
        samples.clear()
        fft.reset(0, 0)
        direct.reset(0, 0)
    }

    private fun captureSample() {
        if (pairedFrames <= 0L) return
        val fs = fft.summary()
        val ds = direct.summary()
        samples += BenchmarkSample(
            pairedFrames = pairedFrames,
            fftUnique = fs.uniqueIds,
            fftRecentNovelty = fs.recentNoveltyRate,
            fftTransitionNovelty = fs.recentTransitionNoveltyRate,
            fftEntropy = fs.entropyBits,
            fftMacros = fs.macroDelta,
            directUnique = ds.uniqueIds,
            directRecentNovelty = ds.recentNoveltyRate,
            directTransitionNovelty = ds.recentTransitionNoveltyRate,
            directEntropy = ds.entropyBits,
            directMacros = ds.macroDelta
        )
        if (samples.size > MAX_SAMPLES) {
            repeat(samples.size - MAX_SAMPLES) { samples.removeAt(0) }
        }
    }

    private fun interpret(a: EncoderTrackSummary, b: EncoderTrackSummary): String {
        if (minOf(a.acceptedFrames, b.acceptedFrames) < 128L) {
            return "Noch zu wenig gemeinsame Daten für einen Konvergenz-Hinweis (mindestens 128 akzeptierte Frames je Encoder)."
        }
        // Heuristic only: recurrence of IDs + recurrence of transitions in the recent window.
        val aStability = 1.0 - (0.60 * a.recentNoveltyRate + 0.40 * a.recentTransitionNoveltyRate)
        val bStability = 1.0 - (0.60 * b.recentNoveltyRate + 0.40 * b.recentTransitionNoveltyRate)
        val diff = bStability - aStability
        return when {
            kotlin.math.abs(diff) < 0.04 -> "ID-Konvergenz derzeit ähnlich. Das ist kein Qualitätsurteil über Informationsgehalt."
            diff > 0.0 -> "DIRECT-ID wiederholt im aktuellen Fenster seine Identitäten/Übergänge stabiler. Kein Qualitätsurteil über Informationsgehalt."
            else -> "FFT-ID wiederholt im aktuellen Fenster seine Identitäten/Übergänge stabiler. Kein Qualitätsurteil über Informationsgehalt."
        }
    }

}
