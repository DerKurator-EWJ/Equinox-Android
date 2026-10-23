package de.equinox.v05

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ln

/**
 * Step 12 three-way encoder convergence benchmark.
 *
 * FFT, fixed DIRECT and learned ADAPTIVE receive the same PCM frame. The benchmark
 * observes recurrence/convergence only; it never changes the learning rules itself.
 * Lower novelty is NOT automatically better: a degenerate encoder could converge by
 * collapsing distinct sounds into one identity.
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
    val adaptive: EncoderTrackSummary,
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
    val directMacros: Int,
    val adaptiveUnique: Int,
    val adaptiveRecentNovelty: Double,
    val adaptiveTransitionNovelty: Double,
    val adaptiveEntropy: Double,
    val adaptiveMacros: Int
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
                pushRolling(recentTransitionNovelty, transitionSet.add(key))
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
            val entropy = if (acceptedFrames <= 0L) 0.0 else counts.values.sumOf { count ->
                val p = count.toDouble() / acceptedFrames.toDouble()
                if (p <= 0.0) 0.0 else -p * (ln(p) / ln(2.0))
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
    private val adaptive = Track()
    private val samples = mutableListOf<BenchmarkSample>()
    private var pairedFrames = 0L
    private var active = false

    @Synchronized
    fun resetAndStart(stats: MemoryStats) {
        fft.reset(stats.fftAudioCodes, stats.fftAudioMacros)
        direct.reset(stats.directAudioCodes, stats.directAudioMacros)
        adaptive.reset(stats.adaptiveAudioCodes, stats.adaptiveAudioMacros)
        pairedFrames = 0L
        samples.clear()
        active = true
    }

    @Synchronized fun stop() { active = false }
    @Synchronized fun isActive(): Boolean = active

    /** Records one PCM frame seen by all three encoders. null means rejected (e.g. silence). */
    @Synchronized
    fun recordTriple(fftId: Int?, directId: Int?, adaptiveId: Int?) {
        if (!active) return
        pairedFrames++
        fft.observe(fftId)
        direct.observe(directId)
        adaptive.observe(adaptiveId)
        if (pairedFrames % SAMPLE_EVERY_FRAMES == 0L) captureSample()
    }

    @Synchronized
    fun updateMemory(stats: MemoryStats) {
        if (!active) return
        fft.updateMemory(stats.fftAudioCodes, stats.fftAudioMacros)
        direct.updateMemory(stats.directAudioCodes, stats.directAudioMacros)
        adaptive.updateMemory(stats.adaptiveAudioCodes, stats.adaptiveAudioMacros)
    }

    @Synchronized
    fun summary(): EncoderBenchmarkSummary {
        val fs = fft.summary()
        val ds = direct.summary()
        val as_ = adaptive.summary()
        return EncoderBenchmarkSummary(
            active = active,
            pairedFrames = pairedFrames,
            fft = fs,
            direct = ds,
            adaptive = as_,
            interpretation = interpret(fs, ds, as_)
        )
    }

    @Synchronized
    fun writeCsvExport(): File {
        captureSample()
        val out = File(context.filesDir, "equinox_v06_encoder_benchmark.csv")
        val sb = StringBuilder()
        sb.appendLine("pairedFrames,fftUnique,fftRecentNovelty,fftTransitionNovelty,fftEntropyBits,fftMacroDelta,directUnique,directRecentNovelty,directTransitionNovelty,directEntropyBits,directMacroDelta,adaptiveUnique,adaptiveRecentNovelty,adaptiveTransitionNovelty,adaptiveEntropyBits,adaptiveMacroDelta")
        samples.forEach { s ->
            sb.appendLine(listOf(
                s.pairedFrames,
                s.fftUnique, s.fftRecentNovelty, s.fftTransitionNovelty, s.fftEntropy, s.fftMacros,
                s.directUnique, s.directRecentNovelty, s.directTransitionNovelty, s.directEntropy, s.directMacros,
                s.adaptiveUnique, s.adaptiveRecentNovelty, s.adaptiveTransitionNovelty, s.adaptiveEntropy, s.adaptiveMacros
            ).joinToString(","))
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
        adaptive.reset(0, 0)
    }

    private fun captureSample() {
        if (pairedFrames <= 0L) return
        val fs = fft.summary()
        val ds = direct.summary()
        val as_ = adaptive.summary()
        samples += BenchmarkSample(
            pairedFrames,
            fs.uniqueIds, fs.recentNoveltyRate, fs.recentTransitionNoveltyRate, fs.entropyBits, fs.macroDelta,
            ds.uniqueIds, ds.recentNoveltyRate, ds.recentTransitionNoveltyRate, ds.entropyBits, ds.macroDelta,
            as_.uniqueIds, as_.recentNoveltyRate, as_.recentTransitionNoveltyRate, as_.entropyBits, as_.macroDelta
        )
        if (samples.size > MAX_SAMPLES) repeat(samples.size - MAX_SAMPLES) { samples.removeAt(0) }
    }

    private fun interpret(fft: EncoderTrackSummary, direct: EncoderTrackSummary, adaptive: EncoderTrackSummary): String {
        if (minOf(fft.acceptedFrames, direct.acceptedFrames, adaptive.acceptedFrames) < 128L) {
            return "Noch zu wenig gemeinsame Daten für einen Konvergenz-Hinweis (mindestens 128 akzeptierte Frames je Encoder)."
        }
        fun stability(t: EncoderTrackSummary): Double =
            1.0 - (0.60 * t.recentNoveltyRate + 0.40 * t.recentTransitionNoveltyRate)

        val scores = listOf(
            "FFT" to stability(fft),
            "DIRECT" to stability(direct),
            "ADAPTIVE" to stability(adaptive)
        ).sortedByDescending { it.second }
        val diff = scores[0].second - scores[1].second
        return if (abs(diff) < 0.04) {
            "ID-Konvergenz der besten Encoder derzeit ähnlich. Kein Qualitätsurteil über Informationsgehalt."
        } else {
            "${scores[0].first} wiederholt im aktuellen Fenster Identitäten/Übergänge am stabilsten. Kein Qualitätsurteil über Informationsgehalt."
        }
    }
}
