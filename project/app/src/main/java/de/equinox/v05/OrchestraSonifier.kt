package de.equinox.v05

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Step 21 finite projection of the same generative orchestra used by Continuous I/O.
 * It is useful in MEMORY/LAB: a snapshot becomes a short deterministic performance.
 */
class OrchestraSonifier {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val DURATION_SECONDS = 8.0
    }

    @Volatile private var playing = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun isPlaying(): Boolean = playing

    fun play(state: EngineOutputState, onState: (String) -> Unit) {
        stop()
        val scene = GenerativeOrchestraSceneBuilder.build(state.sound, state.working, maxVoices = 10)
        if (scene.voices.isEmpty()) {
            onState("Generative Orchestra: noch keine Audio-Makros")
            return
        }

        playing = true
        worker = thread(name = "EquinoxFiniteGenerativeOrchestra") {
            try {
                onState("Generative Orchestra: ${scene.compactSummary()}")
                val total = (SAMPLE_RATE * DURATION_SECONDS).toInt()
                val synth = GenerativeOrchestraSynth(SAMPLE_RATE)
                val pcm = synth.render(scene, total)
                applyFade(pcm)
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
                    .setBufferSizeInBytes(max(4096, pcm.size * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track = audioTrack
                audioTrack.write(pcm, 0, pcm.size)
                if (playing) {
                    audioTrack.play()
                    val durationMs = pcm.size * 1000L / SAMPLE_RATE
                    var waited = 0L
                    while (playing && waited < durationMs + 100L) {
                        Thread.sleep(25L)
                        waited += 25L
                    }
                }
            } catch (e: Exception) {
                onState("Generative Orchestra: Fehler ${e.javaClass.simpleName}")
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                track = null
                playing = false
                onState("Generative Orchestra: AUS")
            }
        }
    }

    fun stop() {
        playing = false
        try { track?.stop() } catch (_: Exception) { }
        try { worker?.join(250L) } catch (_: InterruptedException) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        worker = null
    }

    private fun applyFade(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val fadeIn = (SAMPLE_RATE * 0.18).toInt().coerceAtLeast(1)
        val fadeOut = (SAMPLE_RATE * 0.45).toInt().coerceAtLeast(1)
        for (i in pcm.indices) {
            val a = min(1.0, i.toDouble() / fadeIn.toDouble())
            val remaining = pcm.size - 1 - i
            val b = min(1.0, remaining.toDouble() / fadeOut.toDouble())
            val gain = min(a, b).coerceIn(0.0, 1.0)
            pcm[i] = (pcm[i].toInt() * gain).toInt().toShort()
        }
    }
}
