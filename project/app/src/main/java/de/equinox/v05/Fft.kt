package de.equinox.v05

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Fft {
    fun magnitudes(samples: ShortArray): DoubleArray {
        val n = samples.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be power of two" }

        val re = DoubleArray(n)
        val im = DoubleArray(n)

        for (i in 0 until n) {
            val w = if (n == 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            re[i] = (samples[i].toDouble() / 32768.0) * w
        }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenRe = cos(angle)
            val wLenIm = sin(angle)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        return DoubleArray(n / 2) { k ->
            sqrt(re[k] * re[k] + im[k] * im[k])
        }
    }
}
