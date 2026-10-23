package de.equinox.v05

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Step 21: shared, library-free orchestra model + synthesizer.
 *
 * This layer is deliberately read-only with respect to MemoryModel. It translates the
 * current Equinox output state into an ephemeral musical scene. No sample library and
 * no prerecorded material is used: every sample is synthesized from oscillators,
 * deterministic noise and envelopes.
 *
 * Interpretation used only for OUTPUT:
 * - F -> pulse / transient section
 * - E/D -> motif / arpeggio section
 * - C/B/A -> harmonic pad section
 * - S and deeper -> drone / foundation section
 * - prediction -> anticipatory ghost energy
 * - overexchange -> tension / FM / detune
 * - cross-modal links -> spectral brightness / breath
 * - relevance -> presence; abstraction tier -> orchestral role/timbre only
 */
data class OrchestraVoiceSpec(
    val id: Int,
    val baseHz: Double,
    val memberHz: List<Double>,
    val level: Int,
    val amplitude: Double,
    val activation: Double,
    val expectation: Double,
    val exchangeBias: Double,
    val tierIndex: Int,
    val relevance: Double,
    val generality: Double,
    val predictionConfidence: Double,
    val crossmodalConfidence: Double
) {
    val section: String
        get() = when (level) {
            1 -> "PULSE"
            2, 3 -> "MOTIF"
            4, 5, 6 -> "HARMONY"
            else -> "DRONE"
        }
}

data class OrchestraScene(
    val voices: List<OrchestraVoiceSpec>,
    val tempoBpm: Double,
    val tension: Double,
    val predictionDrive: Double,
    val crossmodalDrive: Double,
    val relationDrive: Double,
    val memoryBlend: Double
) {
    fun compactSummary(): String {
        if (voices.isEmpty()) return "0 Stimmen"
        val groups = voices.groupingBy { it.section }.eachCount()
        val sections = listOf("PULSE", "MOTIF", "HARMONY", "DRONE")
            .mapNotNull { key -> groups[key]?.takeIf { it > 0 }?.let { "$key:$it" } }
            .joinToString(" ")
        return "${voices.size} Stimmen · $sections · ${tempoBpm.toInt()} BPM · T ${"%.2f".format(tension)} · M ${(memoryBlend * 100).toInt()}%"
    }
}

object GenerativeOrchestraSceneBuilder {
    private fun isAudio(modality: String): Boolean =
        modality == "audio" || modality == "audio_direct" || modality == "audio_adaptive"

    fun build(
        sound: SonificationSnapshot,
        working: WorkingSetSnapshot,
        maxVoices: Int = 9,
        memoryBlend: Double = 0.35
    ): OrchestraScene {
        val blend = memoryBlend.coerceIn(0.0, 1.0)
        val realityWeight = 1.0 - blend
        val macroById = sound.macros.associateBy { it.id }
        val predictionsByTarget = working.predictions
            .groupBy { it.targetId }
            .mapValues { (_, values) -> values.maxOfOrNull { it.confidence } ?: 0.0 }
        val crossmodalByAudio = working.crossModalLinks
            .groupBy { it.audioId }
            .mapValues { (_, values) -> values.maxOfOrNull { it.confidence } ?: 0.0 }

        val active = working.nodes.asSequence()
            .filter { isAudio(it.modality) && macroById.containsKey(it.id) }
            .sortedByDescending { node ->
                // Reality favours immediate sensor activation; Memory favours recalled / predicted structure.
                node.activation * (0.85 + 1.55 * realityWeight) +
                    node.expectation * (0.45 + 1.70 * blend) +
                    abs(node.exchangeBias) * (0.30 + 0.35 * blend) +
                    node.structuralRelevance * (0.45 + 0.85 * blend) +
                    (predictionsByTarget[node.id] ?: 0.0) * (0.15 + 1.05 * blend)
            }
            .take(maxVoices)
            .map { node ->
                val macro = macroById.getValue(node.id)
                val levelScale = 1.0 / (1.0 + 0.22 * (node.abstractionLevel - 1).coerceAtLeast(0))
                val prediction = (predictionsByTarget[node.id] ?: 0.0).coerceIn(0.0, 1.0)
                val cross = (crossmodalByAudio[node.id] ?: 0.0).coerceIn(0.0, 1.0)
                val amp = (
                    0.020 +
                        node.activation * (0.040 + 0.070 * realityWeight) +
                        node.expectation * (0.018 + 0.060 * blend) +
                        node.structuralRelevance * (0.028 + 0.046 * blend) +
                        prediction * (0.008 + 0.034 * blend)
                    ).coerceIn(0.014, 0.175)

                OrchestraVoiceSpec(
                    id = node.id,
                    baseHz = fold(macro.hz * levelScale),
                    memberHz = macro.memberHz.map { fold(it * levelScale) }.distinctBy { (it * 4.0).toInt() }.take(5),
                    level = node.abstractionLevel,
                    amplitude = amp,
                    activation = node.activation.coerceIn(0.0, 1.0),
                    expectation = node.expectation.coerceIn(0.0, 1.0),
                    exchangeBias = node.exchangeBias.coerceIn(-3.0, 3.0),
                    tierIndex = node.abstractionTierIndex,
                    relevance = node.structuralRelevance.coerceIn(0.0, 1.0),
                    generality = node.generality.coerceIn(0.0, 1.0),
                    predictionConfidence = prediction,
                    crossmodalConfidence = cross
                )
            }
            .toList()

        val voices = if (active.isNotEmpty()) active else {
            // Quiet read-only memory bed until the active frontier contains audio.
            sound.macros.take(min(4, maxVoices)).mapIndexed { index, macro ->
                val scale = 0.82 / (1.0 + 0.20 * (macro.abstractionLevel - 1).coerceAtLeast(0))
                OrchestraVoiceSpec(
                    id = macro.id,
                    baseHz = fold(macro.hz * scale),
                    memberHz = macro.memberHz.map { fold(it * scale) }.distinctBy { (it * 4.0).toInt() }.take(4),
                    level = macro.abstractionLevel,
                    amplitude = ((0.008 + macro.structuralRelevance * 0.032) * blend).coerceAtMost(0.048),
                    activation = if (index == 0) 0.20 else 0.10,
                    expectation = 0.12,
                    exchangeBias = 0.0,
                    tierIndex = macro.abstractionTierIndex,
                    relevance = macro.structuralRelevance,
                    generality = macro.generality,
                    predictionConfidence = 0.0,
                    crossmodalConfidence = 0.0
                )
            }
        }

        val relationDrive = working.exchanges
            .map { abs(it.value) }
            .let { if (it.isEmpty()) 0.0 else (it.sum() / it.size).coerceIn(0.0, 2.5) }
        val predictionDrive = working.predictions
            .map { it.confidence }
            .let { if (it.isEmpty()) 0.0 else ((it.sum() / it.size) * (0.20 + 0.80 * blend)).coerceIn(0.0, 1.0) }
        val crossDrive = working.crossModalLinks
            .map { it.confidence }
            .let { if (it.isEmpty()) 0.0 else ((it.sum() / it.size) * (0.35 + 0.65 * blend)).coerceIn(0.0, 1.0) }

        val density = (voices.size / max(1.0, maxVoices.toDouble())).coerceIn(0.0, 1.0)
        val meanActivation = voices.map { it.activation }.let { if (it.isEmpty()) 0.0 else it.average() }
        val tempo = (
            46.0 + density * 34.0 + meanActivation * 24.0 + predictionDrive * 16.0 + min(18.0, relationDrive * 9.0)
            ).coerceIn(42.0, 138.0)
        val tension = (relationDrive * 0.52 + voices.map { abs(it.exchangeBias) }.let {
            if (it.isEmpty()) 0.0 else it.average() * 0.22
        }).coerceIn(0.0, 1.5)

        return OrchestraScene(
            voices = voices,
            tempoBpm = tempo,
            tension = tension,
            predictionDrive = predictionDrive,
            crossmodalDrive = crossDrive,
            relationDrive = relationDrive,
            memoryBlend = blend
        )
    }

    fun fold(input: Double): Double {
        var hz = abs(input).coerceAtLeast(1.0)
        while (hz < 72.0) hz *= 2.0
        while (hz > 1480.0) hz /= 2.0
        return hz.coerceIn(72.0, 1480.0)
    }
}

/** Stateful oscillator bank shared by finite and streaming orchestra outputs. */
class GenerativeOrchestraSynth(private val sampleRate: Int = 48_000) {
    private data class RuntimeVoice(
        var phaseA: Double = 0.0,
        var phaseB: Double = 0.0,
        var phaseC: Double = 0.0,
        var currentHz: Double = 220.0,
        var currentAmp: Double = 0.0,
        var noiseState: Int = 1
    )

    private val runtimes = linkedMapOf<Int, RuntimeVoice>()
    private var sampleCursor = 0L

    fun reset() {
        runtimes.clear()
        sampleCursor = 0L
    }

    fun render(scene: OrchestraScene, sampleCount: Int): ShortArray {
        if (sampleCount <= 0) return ShortArray(0)
        val voiceById = scene.voices.associateBy { it.id }
        scene.voices.forEach { spec ->
            runtimes.getOrPut(spec.id) {
                RuntimeVoice(
                    currentHz = spec.baseHz,
                    noiseState = (spec.id * 1103515245 + 12345).let { if (it == 0) 1 else it }
                )
            }
        }

        val out = ShortArray(sampleCount)
        val secondsPerBeat = 60.0 / scene.tempoBpm.coerceAtLeast(1.0)
        val master = (0.82 / max(1.0, scene.voices.size * 0.36)).coerceIn(0.22, 0.82)

        for (i in 0 until sampleCount) {
            val absoluteSample = sampleCursor + i
            val t = absoluteSample.toDouble() / sampleRate.toDouble()
            val beat = t / secondsPerBeat
            val beatPhase = beat - kotlin.math.floor(beat)
            val halfBeatPhase = (beat * 2.0) - kotlin.math.floor(beat * 2.0)
            var sum = 0.0

            val iterator = runtimes.entries.iterator()
            while (iterator.hasNext()) {
                val (id, rt) = iterator.next()
                val spec = voiceById[id]
                val targetAmp = spec?.amplitude ?: 0.0
                rt.currentAmp += (targetAmp - rt.currentAmp) * if (spec == null) 0.0028 else 0.0048
                if (spec == null && rt.currentAmp < 0.00018) {
                    iterator.remove()
                    continue
                }
                if (spec == null) continue

                val motifHz = chooseMotifHz(spec, beat)
                rt.currentHz += (motifHz - rt.currentHz) * when (spec.level) {
                    1 -> 0.010
                    2 -> 0.006
                    else -> 0.0022
                }

                val exchange = spec.exchangeBias
                val tension = scene.tension
                val fmDepth = (
                    0.015 + abs(exchange) * 0.030 + tension * 0.022 + spec.predictionConfidence * 0.010
                    ).coerceIn(0.01, 0.15)
                val slowMod = sin(2.0 * PI * (0.045 + (id and 7) * 0.006) * t)
                val freq = rt.currentHz * (1.0 + slowMod * fmDepth * 0.12)

                rt.phaseA = advance(rt.phaseA, freq)
                rt.phaseB = advance(rt.phaseB, freq * (1.998 + exchange * 0.0025))
                rt.phaseC = advance(rt.phaseC, freq * (3.002 - exchange * 0.0015))

                val env = sectionEnvelope(spec, beatPhase, halfBeatPhase, t)
                val ghost = predictionGhost(spec, beatPhase) * (0.20 + 0.80 * scene.memoryBlend)
                val brightness = (
                    0.08 + spec.level * 0.035 + spec.generality * 0.09 +
                        scene.crossmodalDrive * 0.08 + spec.crossmodalConfidence * 0.11 +
                        min(0.08, spec.tierIndex * 0.005)
                    ).coerceIn(0.06, 0.52)

                val fmCarrier = sin(rt.phaseA + sin(rt.phaseB * 0.5) * fmDepth * 2.6)
                val second = sin(rt.phaseB + exchange * 0.13) * brightness
                val third = sin(rt.phaseC + (id and 31) * 0.037) * brightness * 0.34
                val sub = if (spec.level >= 3) sin(rt.phaseA * 0.5) * (0.10 + spec.relevance * 0.10) else 0.0
                val chord = chordLayer(spec, rt.phaseA, t) * when {
                    spec.level >= 3 -> 0.24 + spec.relevance * 0.20
                    spec.level == 2 -> 0.10
                    else -> 0.0
                }
                val breath = deterministicNoise(rt) * (
                    0.005 + spec.generality * 0.012 + spec.crossmodalConfidence * 0.010
                    )

                val presence = (
                    0.56 + spec.activation * (0.22 + 0.22 * (1.0 - scene.memoryBlend)) + spec.expectation * (0.08 + 0.20 * scene.memoryBlend) +
                        spec.relevance * 0.22 + spec.generality * 0.06
                    ).coerceIn(0.45, 1.34)

                sum += (fmCarrier + second + third + sub + chord + breath) *
                    rt.currentAmp * presence * (env + ghost).coerceIn(0.0, 1.30)
            }

            // A tiny global motion makes relations audible without becoming another voice.
            val relationPulse = sin(2.0 * PI * (0.5 / secondsPerBeat) * t) * scene.relationDrive * 0.015
            val sample = tanh((sum + relationPulse) * master * (1.0 + scene.predictionDrive * 0.08))
            out[i] = (sample.coerceIn(-0.96, 0.96) * Short.MAX_VALUE).toInt().toShort()
        }
        sampleCursor += sampleCount
        return out
    }

    fun suppressionFrequencies(scene: OrchestraScene, maxCount: Int = 24): DoubleArray {
        val values = ArrayList<Double>(scene.voices.size * 4)
        scene.voices.forEach { v ->
            val base = v.baseHz
            values += base
            if (base * 2.0 < sampleRate * 0.46) values += base * 2.0
            if (base * 3.0 < sampleRate * 0.46) values += base * 3.0
            v.memberHz.take(2).forEach { member -> if (member < sampleRate * 0.46) values += member }
        }
        return values
            .filter { it > 35.0 }
            .distinctBy { (it / 2.5).toInt() }
            .take(maxCount)
            .toDoubleArray()
    }

    private fun chooseMotifHz(spec: OrchestraVoiceSpec, beat: Double): Double {
        if (spec.memberHz.isEmpty()) return spec.baseHz
        return when (spec.level) {
            1 -> spec.baseHz
            2 -> {
                val step = (kotlin.math.floor(beat * 2.0).toLong() + (spec.id and 3)).toInt()
                spec.memberHz[Math.floorMod(step, spec.memberHz.size)]
            }
            3 -> spec.baseHz
            else -> GenerativeOrchestraSceneBuilder.fold(spec.baseHz * 0.5)
        }
    }

    private fun sectionEnvelope(spec: OrchestraVoiceSpec, beatPhase: Double, halfBeatPhase: Double, t: Double): Double {
        return when (spec.level) {
            1 -> {
                val p = halfBeatPhase
                val attack = min(1.0, p / 0.025)
                val decay = exp(-p * (6.5 - spec.relevance * 2.2))
                attack * decay * (0.70 + spec.activation * 0.30)
            }
            2 -> {
                val p = halfBeatPhase
                val attack = min(1.0, p / 0.045)
                val decay = exp(-p * 3.1)
                (attack * decay * 0.82 + 0.10) * (0.65 + spec.activation * 0.35)
            }
            3 -> {
                val swell = 0.62 + 0.25 * sin(2.0 * PI * (0.07 + (spec.id and 3) * 0.012) * t)
                (swell + spec.expectation * 0.12).coerceIn(0.32, 1.0)
            }
            else -> {
                val slow = 0.72 + 0.16 * sin(2.0 * PI * (0.025 + (spec.id and 7) * 0.004) * t)
                (slow + spec.relevance * 0.10).coerceIn(0.50, 1.0)
            }
        }
    }

    private fun predictionGhost(spec: OrchestraVoiceSpec, beatPhase: Double): Double {
        if (spec.predictionConfidence <= 0.001 && spec.expectation <= 0.001) return 0.0
        // Expectation becomes audible just before the next beat rather than pretending to be an observation.
        val lead = ((beatPhase - 0.76) / 0.24).coerceIn(0.0, 1.0)
        return lead * (spec.predictionConfidence * 0.18 + spec.expectation * 0.08)
    }

    private fun chordLayer(spec: OrchestraVoiceSpec, basePhase: Double, t: Double): Double {
        if (spec.memberHz.isEmpty()) return 0.0
        var sum = 0.0
        var count = 0
        spec.memberHz.take(3).forEachIndexed { index, hz ->
            if (hz <= 0.0) return@forEachIndexed
            // We use an absolute phase for members so changing macro membership stays smooth enough for output.
            val phase = 2.0 * PI * hz * t + (spec.id and 15) * 0.05 + index * 0.17
            sum += sin(phase)
            count++
        }
        if (count == 0) return sin(basePhase)
        return sum / count.toDouble()
    }

    private fun advance(phase: Double, hz: Double): Double {
        var next = phase + 2.0 * PI * hz / sampleRate.toDouble()
        if (next > 2.0 * PI) next %= (2.0 * PI)
        return next
    }

    private fun deterministicNoise(rt: RuntimeVoice): Double {
        var x = rt.noiseState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        if (x == 0) x = 1
        rt.noiseState = x
        return ((x ushr 1) / Int.MAX_VALUE.toDouble()) * 2.0 - 1.0
    }
}
