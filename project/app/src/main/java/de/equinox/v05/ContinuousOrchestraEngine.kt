package de.equinox.v05

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Step 21: continuous generative orchestra.
 *
 * The output is synthesized directly from the active Equinox state. It is not a sample
 * player and it never writes back into MemoryModel. The same WorkingSetSnapshot can
 * therefore be rendered, muted or replaced without changing learning.
 */
class ContinuousOrchestraEngine {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK_SAMPLES = 960 // 20 ms @ 48 kHz
    }

    @Volatile private var running = false
    @Volatile private var latestSound = SonificationSnapshot(emptyList(), emptyList())
    @Volatile private var latestWorking = WorkingSetSnapshot(emptyList(), emptyList())
    @Volatile private var suppressionHz = DoubleArray(0)
    @Volatile private var activeVoiceCount = 0
    @Volatile private var latestSummary = "0 Stimmen"
    @Volatile private var memoryBlend = 0.35

    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun isRunning(): Boolean = running
    fun voiceCount(): Int = activeVoiceCount
    fun sceneSummary(): String = latestSummary

    /** Same continuous Reality ↔ Memory state as the unified GPU canvas. */
    fun setMemoryBlend(value: Float) { memoryBlend = value.coerceIn(0f, 1f).toDouble() }
    fun memoryBlend(): Double = memoryBlend

    /** Frequencies currently synthesized, including important members/harmonics. */
    fun suppressionFrequencies(): DoubleArray = suppressionHz.copyOf()

    fun updateState(sound: SonificationSnapshot, working: WorkingSetSnapshot) {
        latestSound = sound
        latestWorking = working
    }

    fun start(onState: (String) -> Unit) {
        if (running) return
        running = true
        worker = thread(name = "EquinoxGenerativeOrchestra") {
            val synth = GenerativeOrchestraSynth(SAMPLE_RATE)
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferBytes = max(minBuffer.coerceAtLeast(0), CHUNK_SAMPLES * 2 * 6)
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
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track = audioTrack
                audioTrack.play()
                onState("Generative Orchestra: AKTIV")

                var lastStatusAt = 0L
                while (running) {
                    val scene = GenerativeOrchestraSceneBuilder.build(latestSound, latestWorking, memoryBlend = memoryBlend)
                    activeVoiceCount = scene.voices.size
                    latestSummary = scene.compactSummary()
                    suppressionHz = synth.suppressionFrequencies(scene)

                    val pcm = synth.render(scene, CHUNK_SAMPLES)
                    if (!running) break
                    audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)

                    val now = System.currentTimeMillis()
                    if (now - lastStatusAt > 900L) {
                        lastStatusAt = now
                        onState("Generative Orchestra: ${scene.compactSummary()}")
                    }
                }
            } catch (e: Exception) {
                if (running) onState("Generative Orchestra: Fehler ${e.javaClass.simpleName}")
            } finally {
                running = false
                activeVoiceCount = 0
                suppressionHz = DoubleArray(0)
                latestSummary = "0 Stimmen"
                try { track?.pause() } catch (_: Exception) { }
                try { track?.flush() } catch (_: Exception) { }
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                track = null
                onState("Generative Orchestra: AUS")
            }
        }
    }

    fun stop() {
        running = false
        try { track?.pause() } catch (_: Exception) { }
        try { track?.flush() } catch (_: Exception) { }
        try { worker?.join(350L) } catch (_: InterruptedException) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        worker = null
        activeVoiceCount = 0
        suppressionHz = DoubleArray(0)
        latestSummary = "0 Stimmen"
    }
}
