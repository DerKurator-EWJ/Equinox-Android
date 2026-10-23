package de.equinox.v05

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Display/output layer only: turns a read-only memory snapshot into sound.
 * It never writes observations back into MemoryModel.
 */
class Sonifier {
    companion object {
        private const val SAMPLE_RATE = 48_000
    }

    @Volatile private var playing = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun isPlaying(): Boolean = playing

    fun play(snapshot: SonificationSnapshot, onState: (String) -> Unit) {
        stop()
        if (snapshot.micro.isEmpty() && snapshot.macros.isEmpty()) {
            onState("Sonifikation: noch kein persistentes Gedächtnis")
            return
        }

        playing = true
        worker = thread(name = "EquinoxSonifier") {
            try {
                onState("Sonifikation: GEDÄCHTNIS WIRD AUSGEGEBEN")
                val pcm = buildMemoryPhrase(snapshot)
                if (!playing || pcm.isEmpty()) return@thread

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(pcm.size * 2, 4096))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track = audioTrack
                audioTrack.write(pcm, 0, pcm.size)
                if (playing) {
                    audioTrack.play()
                    val durationMs = pcm.size * 1000L / SAMPLE_RATE
                    var waited = 0L
                    while (playing && waited < durationMs + 80L) {
                        Thread.sleep(25L)
                        waited += 25L
                    }
                }
            } catch (e: Exception) {
                onState("Sonifikation: Fehler ${e.javaClass.simpleName}")
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                track = null
                playing = false
                onState("Sonifikation: AUS")
            }
        }
    }

    fun stop() {
        playing = false
        try { track?.stop() } catch (_: Exception) { }
        try { worker?.join(250) } catch (_: InterruptedException) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        worker = null
    }

    private fun buildMemoryPhrase(snapshot: SonificationSnapshot): ShortArray {
        val out = ArrayList<Short>()

        // 1) strongest micro memories as a short ordered motif
        snapshot.micro.take(6).forEach { node ->
            appendTone(
                out = out,
                frequencies = doubleArrayOf(foldToSpeakerRange(node.hz)),
                durationMs = 150 + min(180L, node.weight * 2L).toInt(),
                amplitude = 0.20
            )
            appendSilence(out, 35)
        }

        // 2) macro memories as chords: their member frequencies sound simultaneously
        snapshot.macros.take(4).forEach { macro ->
            val freqs = macro.memberHz
                .take(4)
                .map(::foldToSpeakerRange)
                .distinctBy { (it * 10.0).toInt() }
                .toDoubleArray()
                .ifEmpty { doubleArrayOf(foldToSpeakerRange(macro.hz)) }

            appendTone(
                out = out,
                frequencies = freqs,
                durationMs = 260 + min(300L, macro.weight * 2L).toInt(),
                amplitude = 0.24
            )
            appendSilence(out, 70)
        }

        return ShortArray(out.size) { out[it] }
    }

    private fun appendTone(
        out: MutableList<Short>,
        frequencies: DoubleArray,
        durationMs: Int,
        amplitude: Double
    ) {
        if (frequencies.isEmpty()) return
        val samples = max(1, SAMPLE_RATE * durationMs / 1000)
        val fadeSamples = min(samples / 4, SAMPLE_RATE / 100) // ~10 ms

        for (i in 0 until samples) {
            var v = 0.0
            for (f in frequencies) {
                v += sin(2.0 * PI * f * i / SAMPLE_RATE)
            }
            v /= frequencies.size.toDouble()

            val fadeIn = if (fadeSamples > 0) min(1.0, i.toDouble() / fadeSamples) else 1.0
            val remain = samples - 1 - i
            val fadeOut = if (fadeSamples > 0) min(1.0, remain.toDouble() / fadeSamples) else 1.0
            val env = min(fadeIn, fadeOut)

            val sample = (v * amplitude * env * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt()
                .toShort()
            out += sample
        }
    }

    private fun appendSilence(out: MutableList<Short>, durationMs: Int) {
        repeat(SAMPLE_RATE * durationMs / 1000) { out += 0 }
    }

    /** Preserve octave relation while keeping tones in a practical phone-speaker range. */
    private fun foldToSpeakerRange(inputHz: Double): Double {
        var hz = abs(inputHz).coerceAtLeast(1.0)
        while (hz < 110.0) hz *= 2.0
        while (hz > 1760.0) hz /= 2.0
        return hz.coerceIn(110.0, 1760.0)
    }
}
