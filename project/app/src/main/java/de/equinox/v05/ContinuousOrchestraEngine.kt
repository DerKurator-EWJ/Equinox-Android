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
 * Step 17: continuous, library-free orchestra.
 *
 * The engine is a pure output projection. It consumes read-only memory snapshots and
 * never writes to MemoryModel. AudioTrack runs in MODE_STREAM so microphone capture
 * can continue at the same time.
 */
class ContinuousOrchestraEngine {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK_SAMPLES = 960 // 20 ms @ 48 kHz
        private const val MAX_VOICES = 6
    }

    private data class VoiceTarget(
        val id: Int,
        val hz: Double,
        val level: Int,
        val amplitude: Double,
        val exchangeBias: Double,
        val harmonic: Double
    )

    private data class VoiceRuntime(
        var phase: Double = 0.0,
        var currentHz: Double = 220.0,
        var currentAmp: Double = 0.0
    )

    @Volatile private var running = false
    @Volatile private var latestSound = SonificationSnapshot(emptyList(), emptyList())
    @Volatile private var latestWorking = WorkingSetSnapshot(emptyList(), emptyList())
    @Volatile private var suppressionHz = DoubleArray(0)
    @Volatile private var activeVoiceCount = 0

    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun isRunning(): Boolean = running
    fun voiceCount(): Int = activeVoiceCount

    /** Frequencies currently synthesized, including the first harmonics. */
    fun suppressionFrequencies(): DoubleArray = suppressionHz.copyOf()

    fun updateState(sound: SonificationSnapshot, working: WorkingSetSnapshot) {
        latestSound = sound
        latestWorking = working
    }

    fun start(onState: (String) -> Unit) {
        if (running) return
        running = true
        worker = thread(name = "EquinoxContinuousOrchestra") {
            val runtimes = linkedMapOf<Int, VoiceRuntime>()
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
                onState("Continuous I/O: Orchester AKTIV")

                var lastStatusAt = 0L
                while (running) {
                    val targets = buildTargets(latestSound, latestWorking)
                    activeVoiceCount = targets.size
                    updateSuppressionFrequencies(targets)
                    val pcm = renderChunk(targets, runtimes)
                    if (!running) break
                    audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)

                    val now = System.currentTimeMillis()
                    if (now - lastStatusAt > 900L) {
                        lastStatusAt = now
                        onState("Continuous I/O: AKTIV · ${targets.size} Stimmen")
                    }
                }
            } catch (e: Exception) {
                if (running) onState("Continuous I/O: Fehler ${e.javaClass.simpleName}")
            } finally {
                running = false
                activeVoiceCount = 0
                suppressionHz = DoubleArray(0)
                try { track?.pause() } catch (_: Exception) { }
                try { track?.flush() } catch (_: Exception) { }
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                track = null
                onState("Continuous I/O: AUS")
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
    }

    private fun buildTargets(sound: SonificationSnapshot, working: WorkingSetSnapshot): List<VoiceTarget> {
        val macroById = sound.macros.associateBy { it.id }
        val active = working.nodes.asSequence()
            .filter {
                (it.modality == "audio" || it.modality == "audio_direct" || it.modality == "audio_adaptive") &&
                    macroById.containsKey(it.id)
            }
            .sortedByDescending {
                it.activation * 2.0 + it.expectation + abs(it.exchangeBias) * 0.45 + it.structuralRelevance * 0.8
            }
            .take(MAX_VOICES)
            .map { node ->
                val macro = macroById.getValue(node.id)
                val levelScale = when (node.abstractionLevel) {
                    1 -> 1.0
                    2 -> 0.75
                    3 -> 0.50
                    else -> 0.375
                }
                val hz = fold(macro.hz * levelScale)
                val amp = (
                    0.035 + node.activation * 0.095 + node.expectation * 0.050 +
                        node.structuralRelevance * 0.050 + min(0.025, node.structuralRankIndex * 0.0025)
                    ).coerceIn(0.025, 0.19)
                val harmonic = (
                    0.08 + node.abstractionLevel * 0.025 + abs(node.exchangeBias) * 0.030 +
                        node.generality * 0.055
                    ).coerceIn(0.08, 0.34)
                VoiceTarget(node.id, hz, node.abstractionLevel, amp, node.exchangeBias, harmonic)
            }
            .toList()

        if (active.isNotEmpty()) return active

        // Read-only fallback: a quiet memory bed until a live working set exists.
        return sound.macros.take(3).mapIndexed { index, macro ->
            VoiceTarget(
                id = macro.id,
                hz = fold(macro.hz * if (index == 0) 0.5 else 1.0),
                level = macro.abstractionLevel,
                amplitude = (0.025 + macro.structuralRelevance * 0.025).coerceAtMost(0.06),
                exchangeBias = 0.0,
                harmonic = (0.08 + macro.generality * 0.04).coerceAtMost(0.15)
            )
        }
    }

    private fun renderChunk(targets: List<VoiceTarget>, runtimes: MutableMap<Int, VoiceRuntime>): ShortArray {
        val targetById = targets.associateBy { it.id }
        targets.forEach { target ->
            runtimes.getOrPut(target.id) { VoiceRuntime(currentHz = target.hz) }
        }

        val out = ShortArray(CHUNK_SAMPLES)
        for (i in 0 until CHUNK_SAMPLES) {
            var sum = 0.0
            val fadeFactor = 0.0045
            val iterator = runtimes.entries.iterator()
            while (iterator.hasNext()) {
                val (id, runtime) = iterator.next()
                val target = targetById[id]
                val targetAmp = target?.amplitude ?: 0.0
                val targetHz = target?.hz ?: runtime.currentHz
                runtime.currentAmp += (targetAmp - runtime.currentAmp) * fadeFactor
                runtime.currentHz += (targetHz - runtime.currentHz) * 0.0025

                if (target == null && runtime.currentAmp < 0.0003) {
                    iterator.remove()
                    continue
                }

                val exchange = target?.exchangeBias ?: 0.0
                val harmonic = target?.harmonic ?: 0.08
                val vibrato = 1.0 + exchange.coerceIn(-2.0, 2.0) * 0.0035 *
                    sin(runtime.phase * 0.037 + id * 0.11)
                runtime.phase += 2.0 * PI * runtime.currentHz * vibrato / SAMPLE_RATE.toDouble()
                if (runtime.phase > 2.0 * PI) runtime.phase %= (2.0 * PI)

                val fundamental = sin(runtime.phase)
                val second = sin(runtime.phase * 2.0 + exchange * 0.17) * harmonic
                val third = sin(runtime.phase * 3.0 + (id and 15) * 0.09) * harmonic * 0.36
                sum += (fundamental + second + third) * runtime.currentAmp
            }

            val sample = (sum * 0.78).coerceIn(-0.82, 0.82)
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun updateSuppressionFrequencies(targets: List<VoiceTarget>) {
        val list = ArrayList<Double>(targets.size * 3)
        targets.forEach { v ->
            list += v.hz
            if (v.hz * 2.0 < SAMPLE_RATE * 0.46) list += v.hz * 2.0
            if (v.hz * 3.0 < SAMPLE_RATE * 0.46) list += v.hz * 3.0
        }
        suppressionHz = list.distinctBy { (it / 2.0).toInt() }.take(18).toDoubleArray()
    }

    private fun fold(input: Double): Double {
        var hz = abs(input).coerceAtLeast(1.0)
        while (hz < 82.0) hz *= 2.0
        while (hz > 1320.0) hz /= 2.0
        return hz.coerceIn(82.0, 1320.0)
    }
}
