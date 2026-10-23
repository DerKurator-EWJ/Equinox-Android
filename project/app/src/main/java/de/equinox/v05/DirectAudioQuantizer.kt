package de.equinox.v05

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Step 10 direct time-domain audio quantizer.
 *
 * This path deliberately does NOT call the FFT. A 2048-sample PCM frame is reduced
 * to a bounded 16-bit acoustic identity assembled from coarse time-domain behaviour:
 *
 *  - zero-crossing rate          4 bits
 *  - normalized roughness       4 bits
 *  - strongest autocorr lag     3 bits
 *  - autocorr strength          3 bits
 *  - envelope trend             2 bits
 *
 * Therefore the direct alphabet has exactly 65,536 possible identities. IDs are
 * mapped to -1 .. -65,536 so they cannot collide with the legacy frequency IDs or
 * the positive macro-ID space.
 *
 * The feature choice is intentionally simple. It is a hard-test encoder, not a claim
 * that these dimensions are the final representation of sound.
 */
object DirectAudioQuantizer {
    private val TEST_LAGS = intArrayOf(4, 8, 16, 32, 64, 128, 256, 512)
    private const val SILENCE_RMS = 0.0025

    data class Result(
        val id: Int,
        val activity: Double,
        val silent: Boolean,
        val zeroCrossingBin: Int,
        val roughnessBin: Int,
        val lagBin: Int,
        val periodicityBin: Int,
        val envelopeBin: Int
    ) {
        val debugText: String
            get() = "ZCR $zeroCrossingBin · rough $roughnessBin · lag $lagBin · periodic $periodicityBin · env $envelopeBin"
    }

    fun quantize(frame: ShortArray): Result {
        if (frame.isEmpty()) return Result(-1, 0.0, true, 0, 0, 0, 0, 0)

        var sumSq = 0.0
        var peak = 1.0
        var zeroCrossings = 0
        var diffSum = 0.0
        var previous = frame[0].toDouble() / 32768.0

        for (i in frame.indices) {
            val x = frame[i].toDouble() / 32768.0
            sumSq += x * x
            peak = max(peak, abs(x))
            if (i > 0) {
                if ((x >= 0.0) != (previous >= 0.0)) zeroCrossings++
                diffSum += abs(x - previous)
            }
            previous = x
        }

        val rms = sqrt(sumSq / frame.size.toDouble())
        val silent = rms < SILENCE_RMS
        if (silent) {
            // One stable silence identity; MainActivity currently does not learn it.
            return Result(-1, rms, true, 0, 0, 0, 0, 0)
        }

        // These two features are normalized so absolute microphone gain does not
        // dominate identity formation.
        val zcr = zeroCrossings.toDouble() / max(1, frame.size - 1).toDouble()
        val roughness = (diffSum / max(1, frame.size - 1).toDouble()) / max(rms, 1e-9)
        val zcrBin = quantize01((zcr / 0.50).coerceIn(0.0, 1.0), 16)
        val roughBin = quantize01((roughness / 2.8).coerceIn(0.0, 1.0), 16)

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
            val denom = sqrt(max(1.0, xx * yy))
            // Negative correlation is useful waveform information but for this first
            // bounded encoder we measure repeat strength independent of polarity.
            val corr = abs(xy / denom).coerceIn(0.0, 1.0)
            if (corr > bestCorrelation) {
                bestCorrelation = corr
                bestLagIndex = index
            }
        }
        val periodicityBin = quantize01(bestCorrelation.coerceIn(0.0, 1.0), 8)

        // Four coarse envelope quarters become one of four trend classes.
        val quarter = max(1, frame.size / 4)
        val env = DoubleArray(4)
        for (q in 0 until 4) {
            val start = q * quarter
            val end = if (q == 3) frame.size else minOf(frame.size, start + quarter)
            var sum = 0.0
            for (i in start until end) sum += abs(frame[i].toDouble() / 32768.0)
            env[q] = if (end > start) sum / (end - start).toDouble() else 0.0
        }
        val firstHalf = (env[0] + env[1]) * 0.5
        val secondHalf = (env[2] + env[3]) * 0.5
        val swing = (env.maxOrNull() ?: 0.0) - (env.minOrNull() ?: 0.0)
        val envelopeBin = when {
            swing > rms * 0.75 -> 3 // strongly pulsed / uneven
            secondHalf > firstHalf * 1.18 -> 2 // rising
            firstHalf > secondHalf * 1.18 -> 0 // falling
            else -> 1 // approximately stable
        }

        // 16-bit fixed alphabet: [zzzz rrrr lll ppp ee]
        val packed =
            (zcrBin and 0xF) or
                ((roughBin and 0xF) shl 4) or
                ((bestLagIndex and 0x7) shl 8) or
                ((periodicityBin and 0x7) shl 11) or
                ((envelopeBin and 0x3) shl 14)

        return Result(
            id = -(packed + 1),
            activity = rms.coerceIn(0.0, 1.0),
            silent = false,
            zeroCrossingBin = zcrBin,
            roughnessBin = roughBin,
            lagBin = bestLagIndex,
            periodicityBin = periodicityBin,
            envelopeBin = envelopeBin
        )
    }

    /** Output-only pitch mapping. It is not stored as a measured frequency. */
    fun projectionHzForId(id: Int): Double {
        val raw = ((-id - 1).coerceAtLeast(0)) and 0xFFFF
        val semitone = raw % 48
        return 110.0 * Math.pow(2.0, semitone / 12.0)
    }

    private fun quantize01(value: Double, bins: Int): Int =
        (value.coerceIn(0.0, 0.999999) * bins.toDouble()).toInt().coerceIn(0, bins - 1)
}
