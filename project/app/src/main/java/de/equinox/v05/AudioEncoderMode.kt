package de.equinox.v05

import android.content.Context

enum class AudioEncoderMode(val label: String) {
    FFT_FREQUENCY("FFT → Frequenz-ID"),
    DIRECT_PATTERN("DIRECT → 16-bit Pattern-ID"),
    ADAPTIVE_PATTERN("ADAPTIVE → gelernte Pattern-ID")
}

class AudioEncoderModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("equinox_v06_audio_encoder", Context.MODE_PRIVATE)

    fun load(): AudioEncoderMode = runCatching {
        AudioEncoderMode.valueOf(prefs.getString("mode", AudioEncoderMode.FFT_FREQUENCY.name)!!)
    }.getOrDefault(AudioEncoderMode.FFT_FREQUENCY)

    fun save(mode: AudioEncoderMode) {
        prefs.edit().putString("mode", mode.name).apply()
    }
}
