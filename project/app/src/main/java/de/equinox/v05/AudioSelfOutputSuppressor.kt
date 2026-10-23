package de.equinox.v05

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Removes a conservative projection of the tones Equinox itself is currently
 * synthesizing. This is not a full acoustic echo canceller; it is a second guard
 * behind Android's AcousticEchoCanceler and is useful because the orchestra is
 * oscillator based and therefore its active tonal components are known.
 */
class AudioSelfOutputSuppressor(
    private val sampleRate: Int = 48_000,
    private val removalGain: Double = 0.58
) {
    data class Result(
        val samples: ShortArray,
        val removedRmsRatio: Double,
        val removedComponents: Int
    )

    fun suppress(input: ShortArray, frequencies: DoubleArray): Result {
        if (input.isEmpty() || frequencies.isEmpty()) {
            return Result(input, 0.0, 0)
        }

        val n = input.size
        val data = DoubleArray(n) { input[it].toDouble() }
        var originalSq = 0.0
        input.forEach { originalSq += it.toDouble() * it.toDouble() }
        val originalRms = sqrt(originalSq / n.toDouble()).coerceAtLeast(1.0)
        var components = 0

        frequencies.asSequence()
            .filter { it >= 55.0 && it <= sampleRate * 0.46 }
            .distinctBy { (it / 2.0).toInt() }
            .take(18)
            .forEach { hz ->
                val omega = 2.0 * PI * hz / sampleRate.toDouble()
                val cw = cos(omega)
                val sw = sin(omega)
                var c = 1.0
                var s = 0.0
                var sumC = 0.0
                var sumS = 0.0

                for (i in 0 until n) {
                    val x = data[i]
                    sumC += x * c
                    sumS += x * s
                    val nc = c * cw - s * sw
                    s = s * cw + c * sw
                    c = nc
                }

                val a = 2.0 * sumC / n.toDouble()
                val b = 2.0 * sumS / n.toDouble()
                val componentRms = sqrt((a * a + b * b) * 0.5)
                // Do not chase extremely weak numerical components.
                if (componentRms < max(18.0, originalRms * 0.010)) return@forEach

                c = 1.0
                s = 0.0
                for (i in 0 until n) {
                    data[i] -= removalGain * (a * c + b * s)
                    val nc = c * cw - s * sw
                    s = s * cw + c * sw
                    c = nc
                }
                components++
            }

        var residualSq = 0.0
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = data[i].coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
            residualSq += v * v
            out[i] = v.toInt().toShort()
        }
        val residualRms = sqrt(residualSq / n.toDouble())
        val removedRatio = (1.0 - residualRms / originalRms).coerceIn(0.0, 1.0)
        return Result(out, removedRatio, components)
    }
}
