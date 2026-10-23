package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MACRO_ID_BASE = 1_000_000
private const val MAX_ABSTRACTION_LEVEL = 4
private const val MIN_PATTERN_LENGTH = 2
private const val MAX_PATTERN_LENGTH = 6
private const val MAX_PATTERN_CANDIDATES = 6_000
private const val MAX_PROMOTIONS_PER_LEVEL = 24

// Step 07: one-slot structural templates are indexed from already stable macros.
private const val MAX_TEMPLATE_BUCKETS = 12_000
private const val MIN_TEMPLATE_LENGTH = 3
private const val MIN_TEMPLATE_VARIANTS = 2
private const val SLOT_TOKEN = Int.MIN_VALUE

// Step 03: bounded event-driven working set.
private const val MAX_ACTIVE_MACROS = 48
private const val MAX_RETRIEVED_NEIGHBORS = 6
private const val ACTIVE_TTL_OBSERVATIONS = 24L
private const val ACTUAL_DECAY = 0.82
private const val EXPECTATION_DECAY = 0.78
private const val EXCHANGE_EMA_ALPHA = 0.15

/**
 * v0.6 Step 07
 *
 * Keeps the bounded active working set and indexed lazy retrieval, plus the
 * non-learning memory-playback path. Step 07 keeps read-only multidimensional
 * structural ranking and adds one-slot template abstraction derived from stable macros. External observations can still update long-term
 * memory; Memory mode only activates already learned macros and relations.
 *
 * Operational definitions used here:
 * - A_i = current/live activation of macro i
 * - B_i = contextual expectation of macro i retrieved from learned relations
 * - R_ij = A_i*B_j - B_i*A_j
 *
 * Only currently active/retrieved macros participate. The persistent memory can
 * therefore grow without forcing a full-graph calculation on every sensor event.
 */
data class MemoryStats(
    val persistentCodes: Int,
    val temporaryCodes: Int,
    val relations: Int,
    val observations: Long,
    val strongestWeight: Long,
    val macroCodes: Int,
    val macroRelations: Int,
    val macroFrozen: Boolean,
    val maxAbstractionLevel: Int,
    val levelCounts: Map<Int, Int>,
    val sequenceCodes: Int,
    val promotedSequences: Int,
    val strongestSequenceWeight: Long,
    val longestSequenceLength: Int,
    val visualPersistentCodes: Int,
    val crossModalRelations: Int,
    val sequenceCapacity: Int,
    val sequenceCapacityUsedPercent: Double,
    val activeMacros: Int = 0,
    val retrievedMacros: Int = 0,
    val overexchangePairs: Int = 0,
    val meanAbsOverexchange: Double = 0.0,
    val highestActiveRank: String = "—",
    val highestActiveRankScore: Double = 0.0,
    val templateCodes: Int = 0,
    val activeTemplates: Int = 0,
    val templateVariantLinks: Int = 0,
    val widestTemplateVariants: Int = 0
)

data class GraphNode(
    val id: Int,
    val weight: Long,
    val representativeHz: Double,
    val abstractionLevel: Int = 0,
    val memberCount: Int = 1,
    val kind: String = "micro",
    val modality: String = "audio",
    val displayColor: Int = 0,
    val activation: Double = 0.0,
    val expectation: Double = 0.0,
    val structuralRank: String = "F",
    val structuralRankIndex: Int = 0,
    val structuralScore: Double = 0.0
) {
    val isMacro: Boolean get() = abstractionLevel > 0
}

data class GraphEdge(
    val fromId: Int,
    val toId: Int,
    val weight: Long,
    val abstractionLevel: Int = 0,
    val isMembership: Boolean = false,
    val overexchange: Double = 0.0
) {
    val isMacro: Boolean get() = abstractionLevel > 0 || isMembership
}

data class GraphSnapshot(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)

data class SonificationNode(
    val id: Int,
    val hz: Double,
    val weight: Long
)

data class SonificationMacro(
    val id: Int,
    val hz: Double,
    val weight: Long,
    val memberHz: List<Double>,
    val abstractionLevel: Int,
    val structuralRank: String = "F",
    val structuralRankIndex: Int = 0,
    val structuralScore: Double = 0.0
)

data class SonificationSnapshot(
    val micro: List<SonificationNode>,
    val macros: List<SonificationMacro>
)

data class ActiveMacroNode(
    val id: Int,
    val abstractionLevel: Int,
    val modality: String,
    val activation: Double,
    val expectation: Double,
    val exchangeBias: Double,
    val retrieved: Boolean,
    val structuralRank: String = "F",
    val structuralRankIndex: Int = 0,
    val structuralScore: Double = 0.0,
    val recurrence: Double = 0.0,
    val reuse: Double = 0.0,
    val relationSupport: Double = 0.0,
    val specificity: Double = 0.0
)

data class ActiveExchange(
    val fromId: Int,
    val toId: Int,
    val value: Double
)

data class WorkingSetSnapshot(
    val nodes: List<ActiveMacroNode>,
    val exchanges: List<ActiveExchange>
)

/**
 * Read-only description of a learned structural template. SLOT_TOKEN is never exposed;
 * the variable position is represented explicitly by slotIndex.
 */
data class TemplateSlotSummary(
    val level: Int,
    val modality: String,
    val slotIndex: Int,
    val fixedTokens: List<Int?>,
    val variants: List<Int>,
    val supportingMacros: Int,
    val supportWeight: Long,
    val active: Boolean
)

data class ConsolidationResult(
    val microPromoted: Int,
    val macroPromoted: Int,
    val sequencePromoted: Int,
    val highestNewLevel: Int,
    val templatesDiscovered: Int = 0
)

class MemoryModel(context: Context) {
    private data class QuantCode(
        var weight: Long,
        var representativeHz: Double,
        val modality: String,
        var displayColor: Int = 0
    )

    private data class PatternCandidate(
        val streamLevel: Int,
        val modality: String,
        val tokens: List<Int>,
        var hits: Long
    )

    private data class MacroCode(
        val id: Int,
        val level: Int,
        val modality: String,
        val tokens: List<Int>,
        var weight: Long,
        var representativeHz: Double,
        var lastActivatedObservation: Long = 0L
    )

    /**
     * A template bucket is not a second relation graph. It is a compact structural index:
     * macros with the same fixed positions and different values at one slot share one bucket.
     */
    private data class TemplateBucket(
        val signature: String,
        val level: Int,
        val modality: String,
        val slotIndex: Int,
        val pattern: List<Int>,
        val macroIds: MutableSet<Int> = linkedSetOf(),
        val variants: MutableSet<Int> = linkedSetOf()
    )

    private data class WorkingMacro(
        var actual: Double,
        var expected: Double,
        var lastTouchedObservation: Long,
        var retrieved: Boolean,
        var exchangeBias: Double = 0.0
    )

    private data class ExchangeMemory(
        var ema: Double,
        var samples: Long
    )

    // New file on purpose. Step 02 changes the meaning of the memory graph and
    // therefore does not silently reinterpret Step12/Step01 relations.
    private val file = File(context.filesDir, "equinox_v06_memory.json")

    // Level 0: observed members of the fixed encoder alphabet. These counters are
    // diagnostics / lookup metadata only. No persistent level-0 relations exist.
    private val quants = mutableMapOf<Int, QuantCode>()

    // Candidate sequences are temporary evidence. Persistent structure starts at macro level.
    private val candidates = mutableMapOf<String, PatternCandidate>()

    private val macros = mutableMapOf<Int, MacroCode>()
    private val macroBySignature = mutableMapOf<String, Int>()
    // Number of higher macros that reuse a macro as a component. This is an index, not
    // another learned relation graph, and lets rank calculation stay local/on-demand.
    private val macroParentUseCount = mutableMapOf<Int, Int>()

    // Step 07 structural generalization index. Each stable macro contributes at most
    // MAX_PATTERN_LENGTH masked signatures; no pairwise macro comparison is required.
    private val templateBuckets = linkedMapOf<String, TemplateBucket>()
    private val templateSignaturesByMacro = mutableMapOf<Int, MutableSet<String>>()

    private val macroRelations = mutableMapOf<Long, Long>()

    // Relation lookup index: a macro can retrieve only its known local neighborhood.
    // The core never scans every relation just to react to a live event.
    private val relationIndex = mutableMapOf<Int, MutableSet<Long>>()

    // Momentary state is intentionally NOT persisted.
    private val activeWorking = mutableMapOf<Int, WorkingMacro>()

    // Long-term smoothed overexchange memory. Keys are canonical unordered pairs.
    private val exchangeMemory = mutableMapOf<Long, ExchangeMemory>()

    // Event-local exchange values used by the output. These are never persisted.
    private val currentExchanges = mutableMapOf<Long, Double>()

    // Memory-mode traversal cursor. It is runtime-only and never learned back.
    private var memoryPlaybackCursor: Int? = null
    private var memoryPlaybackSteps = 0L

    // One short active stream per modality and abstraction source level.
    private val rolling = mutableMapOf<String, ArrayDeque<Int>>()
    private val previousActiveMacro = mutableMapOf<String, Int>()

    private var nextMacroId = MACRO_ID_BASE
    private var macroFrozen = false
    private var observations = 0L

    private var lastRetrievedCount = 0
    private var lastExchangePairs = 0
    private var lastMeanAbsExchange = 0.0

    init { load() }

    @Synchronized
    fun observe(id: Int, hz: Double) {
        observations++
        val code = quants[id]
        if (code == null) {
            quants[id] = QuantCode(
                weight = 1L,
                representativeHz = hz,
                modality = "audio"
            )
        } else {
            code.weight++
            // Slow running average keeps a useful representative frequency without
            // making the quant identity dependent on the latest FFT estimate.
            code.representativeHz += (hz - code.representativeHz) / code.weight.coerceAtMost(1024L).toDouble()
        }

        feedToken(streamLevel = 0, modality = "audio", tokenId = id)
    }

    @Synchronized
    fun observeVisual(id: Int, packedRgb: Int) {
        observations++
        val code = quants[id]
        if (code == null) {
            quants[id] = QuantCode(
                weight = 1L,
                representativeHz = 0.0,
                modality = "vision",
                displayColor = packedRgb
            )
        } else {
            code.weight++
            code.displayColor = packedRgb
        }

        // Camera symbols get the same macro mechanism, but in an independent stream.
        // Audio and vision are intentionally NOT cross-bound in Step 02.
        feedToken(streamLevel = 0, modality = "vision", tokenId = id)
    }

    /**
     * Event-driven / lazy pattern processing.
     *
     * Cost per incoming token is bounded by MAX_PATTERN_LENGTH * abstraction levels.
     * We never scan all stored macros. Existing macros are addressed directly by signature.
     */
    private fun feedToken(streamLevel: Int, modality: String, tokenId: Int) {
        if (streamLevel >= MAX_ABSTRACTION_LEVEL) return

        val streamKey = streamKey(streamLevel, modality)
        val buffer = rolling.getOrPut(streamKey) { ArrayDeque() }
        buffer.addLast(tokenId)
        while (buffer.size > MAX_PATTERN_LENGTH) buffer.removeFirst()

        val current = buffer.toList()
        var bestActivated: MacroCode? = null

        for (length in MIN_PATTERN_LENGTH..minOf(MAX_PATTERN_LENGTH, current.size)) {
            val tail = current.takeLast(length)
            val signature = patternSignature(streamLevel, modality, tail)

            // Direct lookup: no global macro comparison.
            val existingMacro = macroBySignature[signature]?.let { macros[it] }
            if (existingMacro != null) {
                if (bestActivated == null || existingMacro.tokens.size > bestActivated.tokens.size) {
                    bestActivated = existingMacro
                }
                continue
            }

            // Unknown tails only accumulate temporary evidence. Candidate growth is bounded.
            val candidate = candidates[signature]
            if (candidate != null) {
                candidate.hits++
            } else if (candidates.size < MAX_PATTERN_CANDIDATES) {
                candidates[signature] = PatternCandidate(
                    streamLevel = streamLevel,
                    modality = modality,
                    tokens = tail,
                    hits = 1L
                )
            }
        }

        // Compression rule: at most the best (longest) known macro at this level activates.
        // This bounds recursive work and prevents every overlapping pattern from exploding upward.
        bestActivated?.let { activateMacro(it) }
    }

    private fun activateMacro(macro: MacroCode) {
        macro.weight++
        macro.lastActivatedObservation = observations

        decayAndPruneWorkingSet()
        currentExchanges.clear()

        val relationStreamKey = streamKey(macro.level, macro.modality)
        val previous = previousActiveMacro[relationStreamKey]

        // A-state: this macro actually occurred in the current live stream.
        val liveState = activeWorking.getOrPut(macro.id) {
            WorkingMacro(
                actual = 0.0,
                expected = 0.0,
                lastTouchedObservation = observations,
                retrieved = false
            )
        }
        liveState.actual = 1.0
        liveState.lastTouchedObservation = observations
        liveState.retrieved = false

        // Persist only an observed macro-to-macro transition. Index it immediately,
        // so subsequent events can retrieve it without a global relation scan.
        if (previous != null && previous != macro.id) {
            val key = pairKey(previous, macro.id)
            macroRelations[key] = (macroRelations[key] ?: 0L) + 1L
            indexRelation(key)
        }
        previousActiveMacro[relationStreamKey] = macro.id

        // Lazy retrieval: only the strongest local neighbors of the live macro are
        // materialized into the working set. Their B-state is contextual expectation.
        val retrieved = retrieveNeighborsIntoWorkingSet(macro.id, macro.modality)

        val localIds = LinkedHashSet<Int>()
        localIds += macro.id
        previous?.takeIf { it != macro.id }?.let {
            touchAsContext(it, expected = relationConfidence(it, macro.id))
            localIds += it
        }
        localIds.addAll(retrieved)

        // Overexchange exists only in this small event-local frontier.
        val exchangeValues = ArrayList<Double>()
        for (otherId in localIds) {
            if (otherId == macro.id) continue
            val value = calculateAndRememberOverexchange(macro.id, otherId)
            if (value != null) exchangeValues += abs(value)
        }

        lastRetrievedCount = retrieved.size
        lastExchangePairs = exchangeValues.size
        lastMeanAbsExchange = if (exchangeValues.isEmpty()) 0.0 else exchangeValues.average()

        enforceWorkingSetLimit()

        // The activated macro becomes one token for the next abstraction stream.
        feedToken(
            streamLevel = macro.level,
            modality = macro.modality,
            tokenId = macro.id
        )
    }

    /** Pull only indexed neighbors of one active macro into momentary context. */
    private fun retrieveNeighborsIntoWorkingSet(sourceId: Int, modality: String): List<Int> {
        val keys = relationIndex[sourceId] ?: return emptyList()
        return keys.asSequence()
            .mapNotNull { key ->
                val from = keyFrom(key)
                val to = keyTo(key)
                val other = if (from == sourceId) to else from
                val otherMacro = macros[other] ?: return@mapNotNull null
                if (otherMacro.modality != modality) return@mapNotNull null
                val weight = macroRelations[key] ?: return@mapNotNull null
                Triple(other, weight, relationConfidenceByWeight(weight))
            }
            .sortedByDescending { it.second }
            .take(MAX_RETRIEVED_NEIGHBORS)
            .map { (id, _, expectation) ->
                touchAsContext(id, expectation)
                id
            }
            .toList()
    }

    private fun touchAsContext(id: Int, expected: Double) {
        if (!macros.containsKey(id)) return
        val state = activeWorking.getOrPut(id) {
            WorkingMacro(
                actual = 0.0,
                expected = 0.0,
                lastTouchedObservation = observations,
                retrieved = true
            )
        }
        state.expected = max(state.expected, expected.coerceIn(0.0, 1.0))
        state.lastTouchedObservation = observations
        if (state.actual < 0.5) state.retrieved = true

        // Retrieval is read-only: do not recursively activate or rewrite macro history.
    }

    private fun relationConfidence(fromId: Int, toId: Int): Double {
        val direct = macroRelations[pairKey(fromId, toId)] ?: 0L
        val reverse = macroRelations[pairKey(toId, fromId)] ?: 0L
        return relationConfidenceByWeight(max(direct, reverse))
    }

    private fun relationConfidenceByWeight(weight: Long): Double =
        (weight.toDouble() / (weight.toDouble() + 8.0)).coerceIn(0.0, 1.0)

    private fun calculateAndRememberOverexchange(aId: Int, bId: Int): Double? {
        if (aId == bId) return null
        val a = activeWorking[aId] ?: return null
        val b = activeWorking[bId] ?: return null

        // Operational Step-03 meaning:
        // A = live activation, B = retrieved contextual expectation.
        val r = a.actual * b.expected - a.expected * b.actual
        if (!r.isFinite()) return null

        a.exchangeBias = a.exchangeBias * 0.80 + r * 0.20
        b.exchangeBias = b.exchangeBias * 0.80 - r * 0.20

        val key = exchangePairKey(aId, bId)
        val canonicalValue = if (aId <= bId) r else -r
        currentExchanges[key] = canonicalValue
        val memory = exchangeMemory.getOrPut(key) { ExchangeMemory(ema = 0.0, samples = 0L) }
        memory.ema = if (memory.samples == 0L) {
            canonicalValue
        } else {
            (1.0 - EXCHANGE_EMA_ALPHA) * memory.ema + EXCHANGE_EMA_ALPHA * canonicalValue
        }
        memory.samples++
        return r
    }

    private fun decayAndPruneWorkingSet() {
        val iterator = activeWorking.iterator()
        while (iterator.hasNext()) {
            val (_, state) = iterator.next()
            state.actual *= ACTUAL_DECAY
            state.expected *= EXPECTATION_DECAY
            state.exchangeBias *= 0.90

            val age = observations - state.lastTouchedObservation
            if (age > ACTIVE_TTL_OBSERVATIONS ||
                (state.actual < 0.01 && state.expected < 0.01 && abs(state.exchangeBias) < 0.01)
            ) {
                iterator.remove()
            }
        }
    }

    private fun enforceWorkingSetLimit() {
        if (activeWorking.size <= MAX_ACTIVE_MACROS) return
        val keepIds = activeWorking.entries
            .sortedByDescending { (_, state) ->
                state.actual * 2.0 + state.expected + abs(state.exchangeBias) * 0.5 -
                    (observations - state.lastTouchedObservation).coerceAtLeast(0L) * 0.01
            }
            .take(MAX_ACTIVE_MACROS)
            .map { it.key }
            .toSet()
        activeWorking.keys.retainAll(keepIds)
    }


    @Synchronized
    fun consolidate(
        minHits: Int = 3,
        baseRelationThreshold: Long = 10L,
        minSequenceHits: Long = 6L
    ): ConsolidationResult {
        // minHits/baseRelationThreshold remain in the signature for UI/API compatibility.
        // Step 02 promotes only recurring patterns; quant symbols never need promotion.
        if (macroFrozen) {
            decayCandidates()
            pruneMacroRelations()
            save()
            return ConsolidationResult(0, 0, 0, 0)
        }

        val threshold = max(2L, minSequenceHits)
        val templatesBefore = learnedTemplateCount()
        var created = 0
        var highest = 0

        for (sourceLevel in 0 until MAX_ABSTRACTION_LEVEL) {
            val eligible = candidates.entries
                .asSequence()
                .filter { (_, c) -> c.streamLevel == sourceLevel && c.hits >= threshold }
                .sortedWith(
                    compareByDescending<Map.Entry<String, PatternCandidate>> { it.value.hits }
                        .thenByDescending { it.value.tokens.size }
                )
                .take(MAX_PROMOTIONS_PER_LEVEL)
                .toList()

            for ((signature, c) in eligible) {
                if (macroBySignature.containsKey(signature)) continue

                val macro = MacroCode(
                    id = nextMacroId++,
                    level = sourceLevel + 1,
                    modality = c.modality,
                    tokens = c.tokens.toList(),
                    weight = c.hits,
                    representativeHz = representativeHzForTokens(c.tokens),
                    lastActivatedObservation = observations
                )
                macros[macro.id] = macro
                macroBySignature[signature] = macro.id
                indexMacroComposition(macro)
                indexMacroForTemplates(macro)
                created++
                highest = max(highest, macro.level)
            }
        }

        // Candidates are evidence, not a second permanent memory. Keep weak recent evidence
        // with decay so the system can learn across multiple consolidation windows.
        decayCandidates()
        pruneMacroRelations()
        save()

        return ConsolidationResult(
            microPromoted = 0,
            macroPromoted = created,
            sequencePromoted = created,
            highestNewLevel = highest,
            templatesDiscovered = (learnedTemplateCount() - templatesBefore).coerceAtLeast(0)
        )
    }

    private fun decayCandidates() {
        val iterator = candidates.iterator()
        while (iterator.hasNext()) {
            val (_, c) = iterator.next()
            c.hits /= 2L
            if (c.hits <= 0L) iterator.remove()
        }
    }

    private fun representativeHzForTokens(tokens: List<Int>): Double {
        val values = tokens.mapNotNull { representativeHzOf(it) }.filter { it > 0.0 }
        return if (values.isEmpty()) 0.0 else values.average()
    }

    private fun representativeHzOf(id: Int): Double? =
        quants[id]?.representativeHz ?: macros[id]?.representativeHz

    private fun leafHzOf(id: Int, depth: Int = 0): List<Double> {
        if (depth > MAX_ABSTRACTION_LEVEL + 2) return emptyList()
        quants[id]?.let { return if (it.representativeHz > 0.0) listOf(it.representativeHz) else emptyList() }
        val macro = macros[id] ?: return emptyList()
        return macro.tokens.flatMap { leafHzOf(it, depth + 1) }
    }

    /**
     * Step 05 memory-only activation.
     *
     * No quant/macro weights, candidate hits, macro relations, observations or persistent
     * overexchange memories are changed. The method only materializes a small working set
     * from already learned relations so renderers / sonifiers can play the memory itself.
     */
    @Synchronized
    fun memoryPlaybackStep(): Boolean {
        if (macros.isEmpty()) return false

        decayAndPruneWorkingSet()
        currentExchanges.clear()

        val seed = chooseMemoryPlaybackSeed() ?: return false
        val seedState = activeWorking.getOrPut(seed.id) {
            WorkingMacro(
                actual = 0.0,
                expected = 0.0,
                lastTouchedObservation = observations,
                retrieved = true
            )
        }
        // "actual" here means currently materialized in the output working set, not
        // an external sensor observation. Memory mode never calls observe()/feedToken().
        seedState.actual = max(seedState.actual, 0.86)
        seedState.expected = max(seedState.expected, 0.16)
        seedState.lastTouchedObservation = observations
        seedState.retrieved = true

        val retrieved = retrieveNeighborsIntoWorkingSet(seed.id, seed.modality)
        val localIds = LinkedHashSet<Int>()
        localIds += seed.id
        localIds.addAll(retrieved)

        val values = ArrayList<Double>()
        for (otherId in localIds) {
            if (otherId == seed.id) continue
            val r = calculateTransientOverexchange(seed.id, otherId) ?: continue
            values += abs(r)
        }

        lastRetrievedCount = retrieved.size
        lastExchangePairs = values.size
        lastMeanAbsExchange = if (values.isEmpty()) 0.0 else values.average()
        enforceWorkingSetLimit()

        memoryPlaybackSteps++
        memoryPlaybackCursor = chooseNextMemoryCursor(seed.id, seed.modality)
        return true
    }

    /** Drop only runtime activation. Persistent learned memory remains untouched. */
    @Synchronized
    fun clearMomentaryState() {
        activeWorking.clear()
        currentExchanges.clear()
        previousActiveMacro.clear()
        lastRetrievedCount = 0
        lastExchangePairs = 0
        lastMeanAbsExchange = 0.0
        memoryPlaybackCursor = null
        memoryPlaybackSteps = 0L
    }

    @Synchronized
    fun hasLearnedMacros(): Boolean = macros.isNotEmpty()

    private fun chooseMemoryPlaybackSeed(): MacroCode? {
        val cursor = memoryPlaybackCursor
        if (cursor != null) {
            macros[cursor]?.let { return it }
        }

        // Only used to enter/re-enter the learned graph. Subsequent steps use the local
        // relation index and therefore do not scan the entire memory on every tick.
        val top = macros.values
            .sortedWith(compareByDescending<MacroCode> { it.weight }.thenByDescending { it.level })
            .take(24)
        if (top.isEmpty()) return null
        return top[(memoryPlaybackSteps % top.size.toLong()).toInt()]
    }

    private fun chooseNextMemoryCursor(sourceId: Int, modality: String): Int? {
        val local = relationIndex[sourceId].orEmpty().asSequence()
            .mapNotNull { key ->
                val from = keyFrom(key)
                val to = keyTo(key)
                val other = if (from == sourceId) to else from
                val macro = macros[other] ?: return@mapNotNull null
                if (macro.modality != modality) return@mapNotNull null
                val weight = macroRelations[key] ?: return@mapNotNull null
                other to weight
            }
            .sortedByDescending { it.second }
            .take(4)
            .toList()

        if (local.isNotEmpty()) {
            return local[(memoryPlaybackSteps % local.size.toLong()).toInt()].first
        }

        // Dead end: jump to another strong macro. This is traversal, not learning.
        val fallback = macros.values
            .asSequence()
            .filter { it.id != sourceId }
            .sortedWith(compareByDescending<MacroCode> { it.weight }.thenByDescending { it.level })
            .take(24)
            .toList()
        if (fallback.isEmpty()) return sourceId
        return fallback[(memoryPlaybackSteps % fallback.size.toLong()).toInt()].id
    }

    private fun calculateTransientOverexchange(aId: Int, bId: Int): Double? {
        if (aId == bId) return null
        val a = activeWorking[aId] ?: return null
        val b = activeWorking[bId] ?: return null
        val r = a.actual * b.expected - a.expected * b.actual
        if (!r.isFinite()) return null

        a.exchangeBias = a.exchangeBias * 0.80 + r * 0.20
        b.exchangeBias = b.exchangeBias * 0.80 - r * 0.20

        val key = exchangePairKey(aId, bId)
        currentExchanges[key] = if (aId <= bId) r else -r
        return r
    }

    @Synchronized
    fun setMacroFrozen(frozen: Boolean) {
        macroFrozen = frozen
        save()
    }

    @Synchronized
    fun isMacroFrozen(): Boolean = macroFrozen

    @Synchronized
    fun graphSnapshot(
        maxMicroNodes: Int = 34,
        maxMacroNodesPerLevel: Int = 12,
        maxEdges: Int = 150
    ): GraphSnapshot {
        if (quants.isEmpty() && macros.isEmpty()) return GraphSnapshot(emptyList(), emptyList())

        val strongestQuantIds = quants.entries
            .sortedByDescending { it.value.weight }
            .take(maxMicroNodes)
            .map { it.key }
            .toSet()

        val selectedMacros = (1..MAX_ABSTRACTION_LEVEL).flatMap { level ->
            macros.values
                .filter { it.level == level }
                .sortedWith(
                    compareByDescending<MacroCode> {
                        val state = activeWorking[it.id]
                        if (state == null) 0.0 else state.actual * 2.0 + state.expected
                    }
                        .thenByDescending { it.lastActivatedObservation }
                        .thenByDescending { it.weight }
                )
                .take(maxMacroNodesPerLevel)
        }

        val macroIds = selectedMacros.map { it.id }.toSet()
        val visible = strongestQuantIds + macroIds

        val nodes = mutableListOf<GraphNode>()
        strongestQuantIds.forEach { id ->
            quants[id]?.let { q ->
                nodes += GraphNode(
                    id = id,
                    weight = q.weight,
                    representativeHz = q.representativeHz,
                    abstractionLevel = 0,
                    memberCount = 1,
                    kind = "quant",
                    modality = q.modality,
                    displayColor = q.displayColor
                )
            }
        }

        selectedMacros.forEach { m ->
            val rank = structuralRankFor(m)
            nodes += GraphNode(
                id = m.id,
                weight = m.weight,
                representativeHz = m.representativeHz,
                abstractionLevel = m.level,
                memberCount = m.tokens.size,
                kind = "pattern",
                modality = m.modality,
                displayColor = 0,
                activation = activeWorking[m.id]?.actual ?: 0.0,
                expectation = activeWorking[m.id]?.expected ?: 0.0,
                structuralRank = rank.label,
                structuralRankIndex = rank.rankIndex,
                structuralScore = rank.score
            )
        }

        val edges = mutableListOf<GraphEdge>()

        // Membership edges are projection-only. They are NOT persistent level-0 relations.
        selectedMacros.forEach { m ->
            m.tokens.filter { it in visible }.forEach { member ->
                edges += GraphEdge(
                    fromId = member,
                    toId = m.id,
                    weight = max(2L, m.weight / 2L),
                    abstractionLevel = m.level,
                    isMembership = true
                )
            }
        }

        macroRelations.entries
            .asSequence()
            .mapNotNull { (key, weight) ->
                val from = keyFrom(key)
                val to = keyTo(key)
                if (from !in macroIds || to !in macroIds) return@mapNotNull null
                val level = macros[from]?.level ?: return@mapNotNull null
                GraphEdge(
                    fromId = from,
                    toId = to,
                    weight = weight,
                    abstractionLevel = level,
                    overexchange = exchangeValue(from, to)
                )
            }
            .sortedByDescending { it.weight }
            .take(maxEdges)
            .forEach { edges += it }

        return GraphSnapshot(
            nodes = nodes.sortedWith(compareBy<GraphNode> { it.abstractionLevel }.thenByDescending { it.weight }),
            edges = edges
        )
    }

    @Synchronized
    fun sonificationSnapshot(maxMicro: Int = 8, maxMacros: Int = 8): SonificationSnapshot {
        val micro = quants.entries
            .filter { it.value.modality == "audio" && it.value.representativeHz > 0.0 }
            .sortedByDescending { it.value.weight }
            .take(maxMicro)
            .map { (id, q) -> SonificationNode(id, q.representativeHz, q.weight) }

        val macro = macros.values
            .filter { it.modality == "audio" }
            .sortedWith(
                compareByDescending<MacroCode> {
                    val state = activeWorking[it.id]
                    if (state == null) 0.0 else state.actual * 2.0 + state.expected
                }
                    .thenByDescending { it.lastActivatedObservation }
                    .thenByDescending { it.level }
                    .thenByDescending { it.weight }
            )
            .take(maxMacros)
            .map { m ->
                val rank = structuralRankFor(m)
                SonificationMacro(
                    id = m.id,
                    hz = m.representativeHz,
                    weight = m.weight,
                    memberHz = leafHzOf(m.id).distinct().take(8),
                    abstractionLevel = m.level,
                    structuralRank = rank.label,
                    structuralRankIndex = rank.rankIndex,
                    structuralScore = rank.score
                )
            }

        return SonificationSnapshot(micro, macro)
    }

    @Synchronized
    fun workingSetSnapshot(maxNodes: Int = MAX_ACTIVE_MACROS): WorkingSetSnapshot {
        val selected = activeWorking.entries
            .filter { macros.containsKey(it.key) }
            .sortedByDescending { (_, state) ->
                state.actual * 2.0 + state.expected + abs(state.exchangeBias) * 0.5
            }
            .take(maxNodes)

        val selectedIds = selected.map { it.key }.toSet()
        val nodes = selected.mapNotNull { (id, state) ->
            val macro = macros[id] ?: return@mapNotNull null
            val rank = structuralRankFor(macro)
            ActiveMacroNode(
                id = id,
                abstractionLevel = macro.level,
                modality = macro.modality,
                activation = state.actual,
                expectation = state.expected,
                exchangeBias = state.exchangeBias,
                retrieved = state.retrieved,
                structuralRank = rank.label,
                structuralRankIndex = rank.rankIndex,
                structuralScore = rank.score,
                recurrence = rank.recurrence,
                reuse = rank.reuse,
                relationSupport = rank.relationSupport,
                specificity = rank.specificity
            )
        }

        val exchanges = currentExchanges.entries.asSequence()
            .mapNotNull { (key, value) ->
                val a = keyFrom(key)
                val b = keyTo(key)
                if (a !in selectedIds || b !in selectedIds) return@mapNotNull null
                ActiveExchange(a, b, value)
            }
            .sortedByDescending { abs(it.value) }
            .take(maxNodes * 2)
            .toList()

        return WorkingSetSnapshot(nodes, exchanges)
    }

    @Synchronized
    fun stats(): MemoryStats {
        val levelCounts = macros.values.groupingBy { it.level }.eachCount().toSortedMap()
        val strongestCandidate = candidates.values.maxOfOrNull { it.hits } ?: 0L
        val strongestMacro = macros.values.maxOfOrNull { it.weight } ?: 0L
        val longest = max(
            candidates.values.maxOfOrNull { it.tokens.size } ?: 0,
            macros.values.maxOfOrNull { it.tokens.size } ?: 0
        )
        val highestActiveRank = activeWorking.keys
            .asSequence()
            .mapNotNull { macros[it] }
            .map { structuralRankFor(it) }
            .maxByOrNull { it.score }

        val learnedTemplates = learnedTemplateBuckets()
        val activeTemplateCount = learnedTemplates.count { bucket ->
            bucket.macroIds.any { activeWorking.containsKey(it) }
        }
        val templateVariantLinks = learnedTemplates.sumOf { it.variants.size }
        val widestTemplate = learnedTemplates.maxOfOrNull { it.variants.size } ?: 0

        return MemoryStats(
            persistentCodes = quants.size,
            temporaryCodes = candidates.size,
            relations = 0, // by design: no persistent quant-to-quant graph in v0.6 Step 02
            observations = observations,
            strongestWeight = quants.values.maxOfOrNull { it.weight } ?: 0L,
            macroCodes = macros.size,
            macroRelations = macroRelations.size,
            macroFrozen = macroFrozen,
            maxAbstractionLevel = macros.values.maxOfOrNull { it.level } ?: 0,
            levelCounts = levelCounts,
            sequenceCodes = candidates.size + macros.size,
            promotedSequences = macros.size,
            strongestSequenceWeight = max(strongestCandidate, strongestMacro),
            longestSequenceLength = longest,
            visualPersistentCodes = quants.values.count { it.modality == "vision" },
            crossModalRelations = 0,
            sequenceCapacity = MAX_PATTERN_CANDIDATES,
            sequenceCapacityUsedPercent = candidates.size * 100.0 / MAX_PATTERN_CANDIDATES.toDouble(),
            activeMacros = activeWorking.size,
            retrievedMacros = activeWorking.values.count { it.retrieved },
            overexchangePairs = lastExchangePairs,
            meanAbsOverexchange = lastMeanAbsExchange,
            highestActiveRank = highestActiveRank?.label ?: "—",
            highestActiveRankScore = highestActiveRank?.score ?: 0.0,
            templateCodes = learnedTemplates.size,
            activeTemplates = activeTemplateCount,
            templateVariantLinks = templateVariantLinks,
            widestTemplateVariants = widestTemplate
        )
    }

    @Synchronized
    fun reloadFromDisk() {
        quants.clear()
        candidates.clear()
        macros.clear()
        macroBySignature.clear()
        macroParentUseCount.clear()
        templateBuckets.clear()
        templateSignaturesByMacro.clear()
        macroRelations.clear()
        relationIndex.clear()
        activeWorking.clear()
        exchangeMemory.clear()
        currentExchanges.clear()
        memoryPlaybackCursor = null
        memoryPlaybackSteps = 0L
        rolling.clear()
        previousActiveMacro.clear()
        lastRetrievedCount = 0
        lastExchangePairs = 0
        lastMeanAbsExchange = 0.0
        nextMacroId = MACRO_ID_BASE
        macroFrozen = false
        observations = 0L
        load()
    }

    @Synchronized
    fun saveNow() { save() }

    @Synchronized
    fun reset() {
        quants.clear()
        candidates.clear()
        macros.clear()
        macroBySignature.clear()
        macroParentUseCount.clear()
        templateBuckets.clear()
        templateSignaturesByMacro.clear()
        macroRelations.clear()
        relationIndex.clear()
        activeWorking.clear()
        exchangeMemory.clear()
        currentExchanges.clear()
        memoryPlaybackCursor = null
        memoryPlaybackSteps = 0L
        rolling.clear()
        previousActiveMacro.clear()
        lastRetrievedCount = 0
        lastExchangePairs = 0
        lastMeanAbsExchange = 0.0
        nextMacroId = MACRO_ID_BASE
        macroFrozen = false
        observations = 0L
        if (file.exists()) file.delete()
    }

    private fun save() {
        try {
            val root = JSONObject()
                .put("formatVersion", 60_07)
                .put("observations", observations)
                .put("nextMacroId", nextMacroId)
                .put("macroFrozen", macroFrozen)

            val quantArray = JSONArray()
            quants.forEach { (id, q) ->
                quantArray.put(
                    JSONObject()
                        .put("id", id)
                        .put("weight", q.weight)
                        .put("hz", q.representativeHz)
                        .put("modality", q.modality)
                        .put("displayColor", q.displayColor)
                )
            }
            root.put("quants", quantArray)

            val macroArray = JSONArray()
            macros.values.forEach { m ->
                val tokenArray = JSONArray()
                m.tokens.forEach { tokenArray.put(it) }
                macroArray.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("level", m.level)
                        .put("modality", m.modality)
                        .put("tokens", tokenArray)
                        .put("weight", m.weight)
                        .put("hz", m.representativeHz)
                        .put("lastActivatedObservation", m.lastActivatedObservation)
                )
            }
            root.put("macros", macroArray)

            val relationArray = JSONArray()
            macroRelations.forEach { (key, weight) ->
                relationArray.put(JSONObject().put("key", key).put("weight", weight))
            }
            root.put("macroRelations", relationArray)

            val exchangeArray = JSONArray()
            exchangeMemory.forEach { (key, memory) ->
                exchangeArray.put(
                    JSONObject()
                        .put("key", key)
                        .put("ema", memory.ema)
                        .put("samples", memory.samples)
                )
            }
            root.put("overexchange", exchangeArray)

            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            observations = root.optLong("observations", 0L)
            nextMacroId = root.optInt("nextMacroId", MACRO_ID_BASE)
            macroFrozen = root.optBoolean("macroFrozen", false)

            val quantArray = root.optJSONArray("quants") ?: JSONArray()
            for (i in 0 until quantArray.length()) {
                val o = quantArray.getJSONObject(i)
                quants[o.getInt("id")] = QuantCode(
                    weight = o.optLong("weight", 0L),
                    representativeHz = o.optDouble("hz", 0.0),
                    modality = o.optString("modality", "audio"),
                    displayColor = o.optInt("displayColor", 0)
                )
            }

            val macroArray = root.optJSONArray("macros") ?: JSONArray()
            for (i in 0 until macroArray.length()) {
                val o = macroArray.getJSONObject(i)
                val tokenJson = o.optJSONArray("tokens") ?: JSONArray()
                val tokens = ArrayList<Int>(tokenJson.length())
                for (j in 0 until tokenJson.length()) tokens += tokenJson.getInt(j)
                val macro = MacroCode(
                    id = o.getInt("id"),
                    level = o.optInt("level", 1).coerceIn(1, MAX_ABSTRACTION_LEVEL),
                    modality = o.optString("modality", "audio"),
                    tokens = tokens,
                    weight = o.optLong("weight", 0L),
                    representativeHz = o.optDouble("hz", 0.0),
                    lastActivatedObservation = o.optLong("lastActivatedObservation", 0L)
                )
                macros[macro.id] = macro
                val signature = patternSignature(
                    level = macro.level - 1,
                    modality = macro.modality,
                    tokens = macro.tokens
                )
                macroBySignature[signature] = macro.id
                nextMacroId = max(nextMacroId, macro.id + 1)
            }

            rebuildMacroCompositionIndex()
            rebuildTemplateIndex()

            val relationArray = root.optJSONArray("macroRelations") ?: JSONArray()
            for (i in 0 until relationArray.length()) {
                val o = relationArray.getJSONObject(i)
                val key = o.getLong("key")
                macroRelations[key] = o.optLong("weight", 0L)
                indexRelation(key)
            }

            val exchangeArray = root.optJSONArray("overexchange") ?: JSONArray()
            for (i in 0 until exchangeArray.length()) {
                val o = exchangeArray.getJSONObject(i)
                exchangeMemory[o.getLong("key")] = ExchangeMemory(
                    ema = o.optDouble("ema", 0.0),
                    samples = o.optLong("samples", 0L)
                )
            }
        } catch (_: Exception) {
        }
    }

    /** Read-only, on-demand structural profile. It never changes learning or persistence. */
    private fun structuralRankFor(macro: MacroCode): StructuralRankProfile {
        val relationWeights = relationIndex[macro.id]
            ?.asSequence()
            ?.mapNotNull { macroRelations[it] }
            ?.toList()
            ?: emptyList()

        return StructuralRankEngine.evaluate(
            occurrenceWeight = macro.weight,
            abstractionLevel = macro.level,
            parentReuseCount = macroParentUseCount[macro.id] ?: 0,
            relationWeights = relationWeights
        )
    }

    private fun indexMacroComposition(macro: MacroCode) {
        macro.tokens.asSequence()
            .filter { it >= MACRO_ID_BASE }
            .distinct()
            .forEach { child ->
                macroParentUseCount[child] = (macroParentUseCount[child] ?: 0) + 1
            }
    }

    private fun rebuildMacroCompositionIndex() {
        macroParentUseCount.clear()
        macros.values.forEach { indexMacroComposition(it) }
    }

    /**
     * One-slot structural abstraction. For a macro [A,B,C,D], four masked signatures
     * are possible. If another stable macro later supplies [A,B,C,E], both land in
     * the same [A,B,C,□] bucket without an O(N²) comparison.
     */
    private fun indexMacroForTemplates(macro: MacroCode) {
        if (macro.tokens.size < MIN_TEMPLATE_LENGTH) return

        for (slot in macro.tokens.indices) {
            val signature = templateSignature(macro.level, macro.modality, macro.tokens, slot)
            var bucket = templateBuckets[signature]
            if (bucket == null) {
                if (templateBuckets.size >= MAX_TEMPLATE_BUCKETS) continue
                val pattern = macro.tokens.toMutableList().apply { this[slot] = SLOT_TOKEN }
                bucket = TemplateBucket(
                    signature = signature,
                    level = macro.level,
                    modality = macro.modality,
                    slotIndex = slot,
                    pattern = pattern
                )
                templateBuckets[signature] = bucket
            }

            bucket.macroIds += macro.id
            bucket.variants += macro.tokens[slot]
            templateSignaturesByMacro.getOrPut(macro.id) { linkedSetOf() }.add(signature)
        }
    }

    private fun rebuildTemplateIndex() {
        templateBuckets.clear()
        templateSignaturesByMacro.clear()
        // Higher-weight structures get first access if the bounded index ever fills.
        macros.values
            .sortedWith(compareByDescending<MacroCode> { it.weight }.thenByDescending { it.level })
            .forEach { indexMacroForTemplates(it) }
    }

    private fun learnedTemplateBuckets(): List<TemplateBucket> =
        templateBuckets.values.filter {
            it.macroIds.size >= MIN_TEMPLATE_VARIANTS && it.variants.size >= MIN_TEMPLATE_VARIANTS
        }

    private fun learnedTemplateCount(): Int = templateBuckets.values.count {
        it.macroIds.size >= MIN_TEMPLATE_VARIANTS && it.variants.size >= MIN_TEMPLATE_VARIANTS
    }

    /**
     * Read-only top templates for LAB / future output layers. Templates are inferred
     * structure, not semantic labels: a slot means "observed substitution here" only.
     */
    @Synchronized
    fun templateSlotSnapshot(maxTemplates: Int = 24): List<TemplateSlotSummary> =
        learnedTemplateBuckets()
            .asSequence()
            .map { bucket ->
                val supportWeight = bucket.macroIds.sumOf { macros[it]?.weight ?: 0L }
                TemplateSlotSummary(
                    level = bucket.level,
                    modality = bucket.modality,
                    slotIndex = bucket.slotIndex,
                    fixedTokens = bucket.pattern.map { token -> if (token == SLOT_TOKEN) null else token },
                    variants = bucket.variants.toList(),
                    supportingMacros = bucket.macroIds.size,
                    supportWeight = supportWeight,
                    active = bucket.macroIds.any { activeWorking.containsKey(it) }
                )
            }
            .sortedWith(
                compareByDescending<TemplateSlotSummary> { it.active }
                    .thenByDescending { it.variants.size }
                    .thenByDescending { it.supportWeight }
                    .thenByDescending { it.level }
            )
            .take(maxTemplates.coerceAtLeast(0))
            .toList()

    private fun templateSignature(level: Int, modality: String, tokens: List<Int>, slotIndex: Int): String =
        buildString {
            append(modality).append('|').append(level).append("|slot=").append(slotIndex).append('|')
            tokens.forEachIndexed { index, token ->
                if (index > 0) append(',')
                if (index == slotIndex) append('*') else append(token)
            }
        }

    private fun pruneMacroRelations() {
        if (macroRelations.size > 4096) {
            val keep = macroRelations.entries.sortedByDescending { it.value }.take(3072)
            macroRelations.clear()
            relationIndex.clear()
            keep.forEach {
                macroRelations[it.key] = it.value
                indexRelation(it.key)
            }
        }

        if (exchangeMemory.size > 4096) {
            val keepExchange = exchangeMemory.entries
                .sortedByDescending { abs(it.value.ema) * min(64L, it.value.samples).toDouble() }
                .take(3072)
            exchangeMemory.clear()
            keepExchange.forEach { exchangeMemory[it.key] = it.value }
        }
    }

    private fun indexRelation(key: Long) {
        val from = keyFrom(key)
        val to = keyTo(key)
        relationIndex.getOrPut(from) { linkedSetOf() }.add(key)
        relationIndex.getOrPut(to) { linkedSetOf() }.add(key)
    }

    private fun exchangePairKey(a: Int, b: Int): Long =
        if (a <= b) pairKey(a, b) else pairKey(b, a)

    private fun exchangeValue(from: Int, to: Int): Double {
        val memory = exchangeMemory[exchangePairKey(from, to)] ?: return 0.0
        return if (from <= to) memory.ema else -memory.ema
    }

    private fun streamKey(level: Int, modality: String): String = "$modality|$level"

    private fun patternSignature(level: Int, modality: String, tokens: List<Int>): String =
        buildString {
            append(modality).append('|').append(level).append('|')
            tokens.forEachIndexed { index, token ->
                if (index > 0) append(',')
                append(token)
            }
        }

    private fun pairKey(a: Int, b: Int): Long =
        (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)

    private fun keyFrom(key: Long): Int = (key shr 32).toInt()
    private fun keyTo(key: Long): Int = key.toInt()
}
