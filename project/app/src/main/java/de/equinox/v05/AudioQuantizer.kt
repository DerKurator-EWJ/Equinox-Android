package de.equinox.v05

import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Sensor-side quantizer. The memory core receives discrete identities; it does
 * not need to know how microphone frequencies were measured.
 *
 * Step 01 intentionally keeps the old 24 bins/octave mapping so existing
 * memories remain compatible. The FFT can later be replaced without changing
 * the identity contract below.
 */
object AudioQuantizer {
    private const val REFERENCE_HZ = 55.0
    private const val BINS_PER_OCTAVE = 24.0

    fun idForHz(hz: Double): Int =
        if (hz > 0.0) (BINS_PER_OCTAVE * log2(hz / REFERENCE_HZ)).roundToInt() else 0
}
