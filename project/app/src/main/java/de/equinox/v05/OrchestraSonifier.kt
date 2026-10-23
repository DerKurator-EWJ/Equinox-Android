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
 * Step 08: library-free generative audio projection of EngineOutputState.
 * It uses only oscillators. No sample pack, no media library and no writes to memory.
 */
class OrchestraSonifier {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val DURATION_SECONDS = 6.0
    }

    @Volatile private var playing = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun isPlaying(): Boolean = playing

    fun play(state: EngineOutputState, onState: (String) -> Unit) {
        stop()
        val voices = buildVoices(state)
        if (voices.isEmpty()) {
            onState("Orchester: noch keine Audio-Makros")
            return
        }

        playing = true
        worker = thread(name = "EquinoxOrchestra") {
            try {
                onState("Orchester: STATE WIRD GESPIELT · ${voices.size} Stimmen")
                val pcm = render(voices, state.working.exchanges)
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
                    while (playing && waited < durationMs + 80L) {
                        Thread.sleep(25L)
                        waited += 25L
                    }
                }
            } catch (e: Exception) {
                onState("Orchester: Fehler ${e.javaClass.simpleName}")
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                track = null
                playing = false
                onState("Orchester: AUS")
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

    private data class Voice(
        val id: Int,
        val hz: Double,
        val level: Int,
        val activation: Double,
        val expectation: Double,
        val exchangeBias: Double,
        val rankIndex: Int
    )

    private fun buildVoices(state: EngineOutputState): List<Voice> {
        val macroById = state.sound.macros.associateBy { it.id }
        val live = state.working.nodes
            .filter { it.modality == "audio" && macroById.containsKey(it.id) }
            .sortedByDescending { it.activation * 2.0 + it.expectation + abs(it.exchangeBias) * 0.35 }
            .take(8)
            .map { n ->
                Voice(
                    id = n.id,
                    hz = fold(macroById.getValue(n.id).hz),
                    level = n.abstractionLevel,
                    activation = n.activation,
                    expectation = n.expectation,
                    exchangeBias = n.exchangeBias,
                    rankIndex = n.structuralRankIndex
                )
            }

        if (live.isNotEmpty()) return live

        // If no working-set audio macro is active, the orchestra can still project
        // the strongest stored audio macros. This is a read-only memory fallback.
        return state.sound.macros.take(6).map { m ->
            Voice(
                id = m.id,
                hz = fold(m.hz),
                level = m.abstractionLevel,
                activation = 0.25,
                expectation = 0.15,
                exchangeBias = 0.0,
                rankIndex = m.structuralRankIndex
            )
        }
    }

    private fun render(voices: List<Voice>, exchanges: List<ActiveExchange>): ShortArray {
        val total = (SAMPLE_RATE * DURATION_SECONDS).toInt()
        val out = ShortArray(total)
        val relationEnergy = exchanges.sumOf { abs(it.value) }.let { if (exchanges.isEmpty()) 0.0 else it / exchanges.size }
        val globalDrive = (0.90 + min(0.28, relationEnergy * 0.18)).coerceAtMost(1.18)

        for (i in 0 until total) {
            if (!playing) break
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            var sum = 0.0

            voices.forEachIndexed { index, v ->
                val stagger = index * 0.22
                val local = t - stagger
                if (local < 0.0) return@forEachIndexed

                val gatePeriod = 1.20 + (v.id and 7) * 0.11
                val gatePhase = (local % gatePeriod) / gatePeriod
                val gate = when {
                    gatePhase < 0.06 -> gatePhase / 0.06
                    gatePhase < 0.68 -> 1.0
                    gatePhase < 0.94 -> (0.94 - gatePhase) / 0.26
                    else -> 0.0
                }.coerceIn(0.0, 1.0)

                val rankPresence = min(0.22, v.rankIndex * 0.018)
                val importance = (0.18 + 0.50 * v.activation + 0.24 * v.expectation + rankPresence).coerceIn(0.10, 1.0)
                val levelOctave = when (v.level) {
                    1 -> 1.0
                    2 -> 0.75
                    3 -> 0.50
                    else -> 0.375
                }
                val base = fold(v.hz * levelOctave)
                val drift = 1.0 + 0.008 * v.exchangeBias * sin(2.0 * PI * (0.17 + index * 0.021) * t)
                val f = base * drift
                val phase = 2.0 * PI * f * t

                // Additive oscillator family. Higher levels become smoother/deeper;
                // exchange bias changes harmonic balance rather than selecting samples.
                val harmonic = min(0.52, 0.12 + 0.05 * v.level + abs(v.exchangeBias) * 0.05 + min(0.12, v.rankIndex * 0.01))
                val fundamental = sin(phase)
                val second = sin(phase * 2.0 + v.exchangeBias * 0.35) * harmonic
                val third = sin(phase * 3.0 + (v.id and 15) * 0.07) * harmonic * 0.42
                val slow = sin(2.0 * PI * (0.07 + index * 0.013) * t) * 0.08

                sum += (fundamental + second + third + slow) * gate * importance
            }

            val normalized = sum / max(1.0, voices.size * 0.72)
            val fade = min(1.0, min(t / 0.12, (DURATION_SECONDS - t) / 0.22).coerceAtLeast(0.0))
            val v = (normalized * globalDrive * fade * 0.34).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun fold(input: Double): Double {
        var hz = abs(input).coerceAtLeast(1.0)
        while (hz < 82.0) hz *= 2.0
        while (hz > 1320.0) hz /= 2.0
        return hz.coerceIn(82.0, 1320.0)
    }
}
