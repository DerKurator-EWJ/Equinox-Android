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
private const val MAX_PROMOTIONS_PER_LEVEL = 8
private const val AUTO_PROMOTION_COOLDOWN_BASE_TOKENS = 48L

// Step 07/08: one-slot templates feed the sparse substitution-class layer.
private const val MAX_TEMPLATE_BUCKETS = 12_000
private const val MIN_TEMPLATE_LENGTH = 3
private const val MIN_TEMPLATE_VARIANTS = 2
private const val SLOT_TOKEN = Int.MIN_VALUE

// Step 08: sparse substitution classes above the raw quant alphabet.
// Two macro tokens become structurally linked only when they can replace each other
// in at least two distinct learned template roles.
private const val MIN_SUBSTITUTION_ROLE_SUPPORT = 2
private const val MAX_SUBSTITUTION_PAIR_INDEX = 24_000
private const val MAX_CLASS_VARIANTS_PER_TEMPLATE = 24

// Step 03: bounded event-driven working set.
private const val MAX_ACTIVE_MACROS = 48
private const val MAX_RETRIEVED_NEIGHBORS = 6
private const val ACTIVE_TTL_OBSERVATIONS = 24L
private const val ACTUAL_DECAY = 0.82
private const val EXPECTATION_DECAY = 0.78
private const val EXCHANGE_EMA_ALPHA = 0.15

// Step 15: sparse directional recall/prediction. Predictions are derived only from
// already learned macro-to-macro transitions and materialized into the bounded working set.
private const val MAX_PREDICTIONS_PER_EVENT = 4
private const val MIN_PREDICTION_SUPPORT = 2L
private const val MIN_PREDICTION_CONFIDENCE = 0.06

// Step 16: hybrid mode lets a live event seed a short read-only memory walk.
// Internal propagation never masquerades as a new sensor observation.
private const val HYBRID_CONTEXT_FLOOR = 0.22

// Step 19: cross-modal coincidence lives above the independent sensor alphabets.
// Wall-clock time is used only as a transient sensor-coincidence window; it is never
// persisted as a property of a node or interpreted as fundamental time.
private const val MIN_CROSSMODAL_SUPPORT = 4L
private const val CROSSMODAL_WINDOW_NS = 900_000_000L
private const val CROSSMODAL_EVIDENCE_COOLDOWN_NS = 220_000_000L
private const val MAX_RECENT_SENSOR_MACROS = 12
private const val MAX_CROSSMODAL_PARTNERS_PER_EVENT = 3
private const val MAX_CROSSMODAL_CANDIDATES = 4_000

/**
 * v0.6 Step 19
 *
 * Keeps the bounded active working set, indexed lazy retrieval, structural ranks,
 * templates and substitution classes. Step 09 added a read-only multidimensional
 * relevance profile and now supports three isolated audio alphabets: legacy FFT
 * frequency IDs ("audio"), fixed 16-bit direct IDs ("audio_direct") and the learned
 * adaptive vector-quantizer IDs ("audio_adaptive"). They never mix sequence streams.
 * Step 13 added adaptive hierarchy promotion and non-overlapping macro emission.
 * Step 14 added dependency-safe memory competition. Step 15 added sparse directional
 * predictive recall. Step 16 added HYBRID mode. Step 19 adds a sparse cross-modal layer above the independent
 * audio/vision streams: only already-learned sensor macros can become joint audio+visual macros,
 * and only after repeated near-simultaneous evidence. Raw quants remain modality-local.
 * The coincidence clock is runtime-only and is never persisted into the learned identities.
 * External observations can still update long-term memory; Memory mode only
 * activates already learned macros and relations.
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
    val widestTemplateVariants: Int = 0,
    val substitutionClasses: Int = 0,
    val activeSubstitutionClasses: Int = 0,
    val substitutionMembers: Int = 0,
    val strongestSubstitutionSupport: Int = 0,
    val meanActiveRelevance: Double = 0.0,
    val highestActiveRelevance: Double = 0.0,
    val meanActiveContextEntropy: Double = 0.0,
    val highestActiveGenerality: Double = 0.0,
    val fftAudioCodes: Int = 0,
    val directAudioCodes: Int = 0,
    val adaptiveAudioCodes: Int = 0,
    val fftAudioMacros: Int = 0,
    val directAudioMacros: Int = 0,
    val adaptiveAudioMacros: Int = 0,
    val adaptiveVisualCodes: Int = 0,
    val adaptiveVisualMacros: Int = 0,
    val hierarchyBaseInputTokens: Long = 0L,
    val hierarchyBaseMacroEmissions: Long = 0L,
    val hierarchyBaseSavedTokens: Long = 0L,
    val hierarchyBaseCompressionGain: Double = 0.0,
    val hierarchyAllMacroEmissions: Long = 0L,
    val hierarchyAutoPromotions: Long = 0L,
    val hierarchyMeanAcceptedSpan: Double = 0.0,
    val competitionRuns: Long = 0L,
    val competitionMacrosPruned: Long = 0L,
    val competitionRelationsPruned: Long = 0L,
    val competitionCandidatesPruned: Long = 0L,
    val competitionLastPruned: Int = 0,
    val competitionLastProtected: Int = 0,
    val competitionLastPressuredGroups: Int = 0,
    val predictionCandidates: Int = 0,
    val predictionTopConfidence: Double = 0.0,
    val predictionChecks: Long = 0L,
    val predictionHits: Long = 0L,
    val predictionHitRate: Double = 0.0,
    val hybridSteps: Long = 0L,
    val hybridSeedId: Int = 0,
    val crossModalMacros: Int = 0,
    val crossModalCandidates: Int = 0,
    val crossModalActivations: Long = 0L,
    val strongestCrossModalSupport: Long = 0L
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
    val structuralScore: Double = 0.0,
    val structuralRelevance: Double = 0.0,
    val contextEntropy: Double = 0.0,
    val persistence: Double = 0.0,
    val generality: Double = 0.0
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
    val structuralScore: Double = 0.0,
    val structuralRelevance: Double = 0.0,
    val contextEntropy: Double = 0.0,
    val persistence: Double = 0.0,
    val generality: Double = 0.0
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
    val specificity: Double = 0.0,
    val contextEntropy: Double = 0.0,
    val persistence: Double = 0.0,
    val generality: Double = 0.0,
    val structuralRelevance: Double = 0.0
)

data class ActiveExchange(
    val fromId: Int,
    val toId: Int,
    val value: Double
)

/** Directional, read-only next-state expectation derived from learned transitions. */
data class ActivePrediction(
    val sourceId: Int,
    val targetId: Int,
    val confidence: Double,
    val support: Long
)

/** Runtime projection showing which audio + visual macro compose one active joint macro. */
data class ActiveCrossModalLink(
    val jointId: Int,
    val audioId: Int,
    val visualId: Int,
    val confidence: Double
)

data class WorkingSetSnapshot(
    val nodes: List<ActiveMacroNode>,
    val exchanges: List<ActiveExchange>,
    val predictions: List<ActivePrediction> = emptyList(),
    val crossModalLinks: List<ActiveCrossModalLink> = emptyList()
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

/**
 * A derived structural class. Members share multiple independently observed
 * substitution roles. No semantic label is assigned.
 */
data class SubstitutionClassSummary(
    val classId: Int,
    val memberLevel: Int,
    val modality: String,
    val members: List<Int>,
    val roleEvidence: Int,
    val strongestPairSupport: Int,
    val activeMembers: Int
)

data class ConsolidationResult(
    val microPromoted: Int,
    val macroPromoted: Int,
    val sequencePromoted: Int,
    val highestNewLevel: Int,
    val templatesDiscovered: Int = 0,
    val substitutionClassesDiscovered: Int = 0,
    val competitionPruned: Int = 0
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
        var firstActivatedObservation: Long = 0L,
        var lastActivatedObservation: Long = 0L
    )

    private data class CrossModalCandidate(
        val audioMacroId: Int,
        val visualMacroId: Int,
        var hits: Long,
        var lastEvidenceNs: Long
    )

    private data class RecentSensorMacro(
        val macroId: Int,
        val seenNs: Long
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

    private data class SubstitutionTokenMeta(
        val memberLevel: Int,
        val modality: String
    )

    private data class DerivedSubstitutionClass(
        val classId: Int,
        val memberLevel: Int,
        val modality: String,
        val members: List<Int>,
        val roleEvidence: Int,
        val strongestPairSupport: Int
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

    // Step 19 cross-modal layer. Candidate evidence is runtime-only; learned joint macros
    // persist in the normal macro table with modality="crossmodal". Pair lookup is sparse.
    private val crossModalCandidates = linkedMapOf<String, CrossModalCandidate>()
    private val crossModalByPair = mutableMapOf<String, Int>()
    private val recentSensorMacros = mutableMapOf(
        "audio" to ArrayDeque<RecentSensorMacro>(),
        "vision" to ArrayDeque<RecentSensorMacro>()
    )
    private var crossModalActivations = 0L

    // Step 07/08 structural generalization index. Each stable macro contributes at most
    // MAX_PATTERN_LENGTH masked signatures; no pairwise macro comparison is required.
    private val templateBuckets = linkedMapOf<String, TemplateBucket>()
    private val templateSignaturesByMacro = mutableMapOf<Int, MutableSet<String>>()

    // Sparse structural-role evidence. Only macro tokens (never raw quant IDs) enter
    // this index, and support counts distinct template roles rather than raw frequency.
    private val substitutionPairSupport = mutableMapOf<Long, Int>()
    private val substitutionTokenMeta = mutableMapOf<Int, SubstitutionTokenMeta>()
    private var substitutionClassesCache: List<DerivedSubstitutionClass> = emptyList()
    private var substitutionClassesDirty = true

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

    // Step 15 predictions are runtime projections of persistent directional transition counts.
    // They are never written back as relations merely because they were predicted.
    private val currentPredictions = mutableListOf<ActivePrediction>()
    private val pendingPredictionsByStream = mutableMapOf<String, Set<Int>>()
    private var predictionChecks = 0L
    private var predictionHits = 0L
    private var lastPredictionCandidates = 0
    private var lastTopPredictionConfidence = 0.0

    // Memory-mode traversal cursor. It is runtime-only and never learned back.
    private var memoryPlaybackCursor: Int? = null
    private var memoryPlaybackSteps = 0L

    // Step 16 hybrid cursor is reset by every real live macro activation, then may walk
    // through predicted/related memory between sensor events. It is runtime-only.
    private var hybridPlaybackCursor: Int? = null
    private var hybridPlaybackSteps = 0L
    private var lastHybridSeedId = 0

    // One short active stream per modality and abstraction source level.
    private val rolling = mutableMapOf<String, ArrayDeque<Int>>()
    private val previousActiveMacro = mutableMapOf<String, Int>()

    // Step 13 adaptive hierarchy state. Positions are per stream so accepted macro spans
    // can be kept non-overlapping before they are emitted to the next abstraction level.
    private val hierarchyStreamPositions = mutableMapOf<String, Long>()
    private val hierarchyCoveredUntil = mutableMapOf<String, Long>()
    private val hierarchyLastAutoPromotionAt = mutableMapOf<String, Long>()
    private var hierarchyBaseInputTokens = 0L
    private var hierarchyBaseMacroEmissions = 0L
    private var hierarchyBaseSavedTokens = 0L
    private var hierarchyAllMacroEmissions = 0L
    private var hierarchyAcceptedSpanTotal = 0L
    private var hierarchyAutoPromotions = 0L
    private var hierarchyAutoPromotionsSinceSave = 0

    // Step 14 memory-competition diagnostics. The pruning decision itself is derived
    // from live memory state; these counters are runtime diagnostics and are not semantics.
    private var competitionRuns = 0L
    private var competitionMacrosPruned = 0L
    private var competitionRelationsPruned = 0L
    private var competitionCandidatesPruned = 0L
    private var competitionLastPruned = 0
    private var competitionLastProtected = 0
    private var competitionLastPressuredGroups = 0

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

    /**
     * FFT-free audio identity observation. The negative token ID already encodes the
     * fixed 16-bit direct alphabet. No measured frequency is attached to this code.
     */
    @Synchronized
    fun observeDirectAudio(id: Int) {
        observations++
        val code = quants[id]
        if (code == null) {
            quants[id] = QuantCode(
                weight = 1L,
                representativeHz = 0.0,
                modality = "audio_direct"
            )
        } else {
            code.weight++
        }

        feedToken(streamLevel = 0, modality = "audio_direct", tokenId = id)
    }

    /** Adaptive FFT-free audio identity from the learned Step-12 codebook. */
    @Synchronized
    fun observeAdaptiveAudio(id: Int) {
        observations++
        val code = quants[id]
        if (code == null) {
            quants[id] = QuantCode(
                weight = 1L,
                representativeHz = 0.0,
                modality = "audio_adaptive"
            )
        } else {
            code.weight++
        }

        feedToken(streamLevel = 0, modality = "audio_adaptive", tokenId = id)
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
        feedToken(streamLevel = 0, modality = "vision", tokenId = id)
    }

    /** Step 18 adaptive visual identity. IDs are learned by AdaptiveVisualQuantizer. */
    @Synchronized
    fun observeAdaptiveVisual(id: Int, packedRgb: Int) {
        observations++
        val code = quants[id]
        if (code == null) {
            quants[id] = QuantCode(
                weight = 1L,
                representativeHz = 0.0,
                modality = "vision_adaptive",
                displayColor = packedRgb
            )
        } else {
            code.weight++
            // The quantizer already supplies its slowly learned prototype color.
            code.displayColor = packedRgb
        }
        feedToken(streamLevel = 0, modality = "vision_adaptive", tokenId = id)
    }

    /**
     * Prevent a macro candidate from accidentally spanning the last cells of one image and
     * the first cells of the next. Learned macros remain persistent and can immediately be
     * recognized again inside the next frame. Temporal relations between activated macros
     * are intentionally left intact for later cross-frame prediction.
     */
    @Synchronized
    fun beginAdaptiveVisualFrame() {
        rolling.keys.filter { it.startsWith("vision_adaptive|") }.toList().forEach { rolling.remove(it) }
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
        val streamPosition = (hierarchyStreamPositions[streamKey] ?: 0L) + 1L
        hierarchyStreamPositions[streamKey] = streamPosition
        if (streamLevel == 0) hierarchyBaseInputTokens++

        val buffer = rolling.getOrPut(streamKey) { ArrayDeque() }
        buffer.addLast(tokenId)
        while (buffer.size > MAX_PATTERN_LENGTH) buffer.removeFirst()

        val current = buffer.toList()
        val coveredUntilBeforeEvent = hierarchyCoveredUntil[streamKey] ?: 0L
        var bestActivated: MacroCode? = null
        var bestAutoSignature: String? = null
        var bestAutoCandidate: PatternCandidate? = null
        var bestAutoPriority = 0.0
        val promotionCooldown = (AUTO_PROMOTION_COOLDOWN_BASE_TOKENS shr streamLevel.coerceIn(0, 2)).coerceAtLeast(8L)
        val lastAutoPromotionAt = hierarchyLastAutoPromotionAt[streamKey] ?: Long.MIN_VALUE / 4L
        val autoPromotionAllowed = streamPosition - lastAutoPromotionAt >= promotionCooldown

        for (length in MIN_PATTERN_LENGTH..minOf(MAX_PATTERN_LENGTH, current.size)) {
            val tail = current.takeLast(length)
            val signature = patternSignature(streamLevel, modality, tail)

            // Direct lookup: no global macro comparison.
            val existingMacro = macroBySignature[signature]?.let { macros[it] }
            if (existingMacro != null) {
                val spanStart = streamPosition - existingMacro.tokens.size.toLong() + 1L
                if (spanStart > coveredUntilBeforeEvent &&
                    (bestActivated == null || existingMacro.tokens.size > bestActivated.tokens.size)
                ) {
                    bestActivated = existingMacro
                }
                continue
            }

            // Unknown tails accumulate temporary evidence. Step 13 evaluates that evidence
            // against a compression objective instead of waiting for one fixed hit count.
            val candidate = candidates[signature]
            val effectiveCandidate = if (candidate != null) {
                candidate.hits++
                candidate
            } else if (candidates.size < MAX_PATTERN_CANDIDATES) {
                PatternCandidate(
                    streamLevel = streamLevel,
                    modality = modality,
                    tokens = tail,
                    hits = 1L
                ).also { candidates[signature] = it }
            } else {
                null
            }

            if (!macroFrozen && autoPromotionAllowed && effectiveCandidate != null) {
                val score = AdaptiveHierarchyPolicy.evaluate(
                    patternLength = effectiveCandidate.tokens.size,
                    hits = effectiveCandidate.hits,
                    sourceLevel = effectiveCandidate.streamLevel
                )
                if (score.eligible && score.priority > bestAutoPriority) {
                    bestAutoPriority = score.priority
                    bestAutoSignature = signature
                    bestAutoCandidate = effectiveCandidate
                }
            }
        }

        // At most one self-promotion per input token. This keeps live learning bounded even
        // when several overlapping tails become compressible at the same instant.
        if (!macroFrozen && bestAutoSignature != null && bestAutoCandidate != null) {
            val promoted = promoteCandidate(bestAutoSignature!!, bestAutoCandidate!!, autoPromotion = true)
            if (promoted != null) {
                hierarchyLastAutoPromotionAt[streamKey] = streamPosition
                val spanStart = streamPosition - promoted.tokens.size.toLong() + 1L
                if (spanStart > coveredUntilBeforeEvent &&
                    (bestActivated == null || promoted.tokens.size > bestActivated.tokens.size)
                ) {
                    bestActivated = promoted
                }
            }
        }

        // Step 13 emits only non-overlapping recognized spans upward. This makes each
        // abstraction stream genuinely shorter instead of recursively activating every
        // overlapping match. Lower-level raw input remains available in the level-0 alphabet.
        bestActivated?.let { macro ->
            val span = macro.tokens.size.coerceAtLeast(1)
            val spanStart = streamPosition - span.toLong() + 1L
            val coveredUntil = hierarchyCoveredUntil[streamKey] ?: 0L
            if (spanStart > coveredUntil) {
                hierarchyCoveredUntil[streamKey] = streamPosition
                hierarchyAllMacroEmissions++
                hierarchyAcceptedSpanTotal += span.toLong()
                if (streamLevel == 0) {
                    hierarchyBaseMacroEmissions++
                    hierarchyBaseSavedTokens += (span - 1).toLong()
                }
                activateMacro(macro)
            }
        }
    }

    private fun promoteCandidate(
        signature: String,
        candidate: PatternCandidate,
        autoPromotion: Boolean
    ): MacroCode? {
        if (macroFrozen || macroBySignature.containsKey(signature)) return null
        if (candidate.streamLevel !in 0 until MAX_ABSTRACTION_LEVEL) return null

        val macro = MacroCode(
            id = nextMacroId++,
            level = candidate.streamLevel + 1,
            modality = candidate.modality,
            tokens = candidate.tokens.toList(),
            weight = candidate.hits,
            representativeHz = representativeHzForTokens(candidate.tokens),
            firstActivatedObservation = observations,
            lastActivatedObservation = observations
        )
        macros[macro.id] = macro
        macroBySignature[signature] = macro.id
        indexMacroComposition(macro)
        indexMacroForTemplates(macro)
        candidates.remove(signature)

        if (autoPromotion) {
            hierarchyAutoPromotions++
            hierarchyAutoPromotionsSinceSave++
            // Persist in small batches: learned identities survive normal use without
            // forcing a JSON rewrite on every newly promoted macro.
            if (hierarchyAutoPromotionsSinceSave >= 8) {
                hierarchyAutoPromotionsSinceSave = 0
                save()
            }
        }
        return macro
    }

    private fun activateMacro(macro: MacroCode) {
        macro.weight++
        if (macro.firstActivatedObservation <= 0L) {
            macro.firstActivatedObservation = observations
        }
        macro.lastActivatedObservation = observations

        decayAndPruneWorkingSet()
        currentExchanges.clear()

        val relationStreamKey = streamKey(macro.level, macro.modality)
        val previous = previousActiveMacro[relationStreamKey]

        // Evaluate the previous event's top-k forecast before creating the next one.
        evaluatePendingPrediction(relationStreamKey, macro.id)

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

        // A real event always re-anchors Hybrid mode in the present.
        hybridPlaybackCursor = macro.id
        lastHybridSeedId = macro.id

        // Lazy retrieval: only the strongest local neighbors of the live macro are
        // materialized into the working set. Their B-state is contextual expectation.
        val retrieved = retrieveNeighborsIntoWorkingSet(macro.id, macro.modality)

        // Step 15: directional prediction is stricter than generic neighbor recall. Only
        // learned outgoing transitions from this exact macro/level/modality can forecast.
        currentPredictions.clear()
        val predictions = predictNextIntoWorkingSet(macro)
        currentPredictions.addAll(predictions)
        pendingPredictionsByStream[relationStreamKey] = predictions.map { it.targetId }.toSet()
        lastPredictionCandidates = predictions.size
        lastTopPredictionConfidence = predictions.maxOfOrNull { it.confidence } ?: 0.0

        val localIds = LinkedHashSet<Int>()
        localIds += macro.id
        previous?.takeIf { it != macro.id }?.let {
            touchAsContext(it, expected = relationConfidence(it, macro.id))
            localIds += it
        }
        localIds.addAll(retrieved)
        localIds.addAll(predictions.map { it.targetId })

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

        // Cross-modal learning is triggered only by real sensor macro activations. Retrieved
        // context/predictions never create joint memories. This may materialize one already
        // supported audio+visual joint macro into the same bounded working set.
        observeCrossModalEvidence(macro)

        enforceWorkingSetLimit()

        // The activated macro becomes one token for the next abstraction stream.
        feedToken(
            streamLevel = macro.level,
            modality = macro.modality,
            tokenId = macro.id
        )
    }

    private fun observeCrossModalEvidence(source: MacroCode) {
        val family = sensorFamily(source.modality) ?: return
        val opposite = if (family == "audio") "vision" else "audio"
        val now = System.nanoTime()

        val ownQueue = recentSensorMacros.getValue(family)
        pruneRecentSensorQueue(ownQueue, now)
        ownQueue.removeAll { it.macroId == source.id }
        ownQueue.addLast(RecentSensorMacro(source.id, now))
        while (ownQueue.size > MAX_RECENT_SENSOR_MACROS) ownQueue.removeFirst()

        val otherQueue = recentSensorMacros.getValue(opposite)
        pruneRecentSensorQueue(otherQueue, now)
        if (otherQueue.isEmpty()) return

        otherQueue.asSequence()
            .mapNotNull { recent -> macros[recent.macroId] }
            .filter { it.id != source.id }
            .distinctBy { it.id }
            .sortedByDescending { it.lastActivatedObservation }
            .take(MAX_CROSSMODAL_PARTNERS_PER_EVENT)
            .forEach { other ->
                val audio = if (family == "audio") source else other
                val visual = if (family == "vision") source else other
                registerCrossModalPair(audio, visual, now)
            }
    }

    private fun pruneRecentSensorQueue(queue: ArrayDeque<RecentSensorMacro>, now: Long) {
        while (queue.isNotEmpty() && now - queue.first().seenNs > CROSSMODAL_WINDOW_NS) {
            queue.removeFirst()
        }
    }

    private fun registerCrossModalPair(audio: MacroCode, visual: MacroCode, now: Long) {
        if (sensorFamily(audio.modality) != "audio" || sensorFamily(visual.modality) != "vision") return
        val signature = crossModalPairSignature(audio.id, visual.id)

        val existingId = crossModalByPair[signature]
        if (existingId != null) {
            val joint = macros[existingId] ?: run {
                crossModalByPair.remove(signature)
                return
            }
            activateCrossModalMacro(joint, supportConfidence = 1.0)
            return
        }

        val candidate = crossModalCandidates[signature]
        if (candidate == null) {
            if (crossModalCandidates.size >= MAX_CROSSMODAL_CANDIDATES) return
            crossModalCandidates[signature] = CrossModalCandidate(
                audioMacroId = audio.id,
                visualMacroId = visual.id,
                hits = 1L,
                lastEvidenceNs = now
            )
            return
        }

        // Many audio frames can overlap one camera frame. Count repeated evidence, but not
        // hundreds of copies of the same physical coincidence burst.
        if (now - candidate.lastEvidenceNs < CROSSMODAL_EVIDENCE_COOLDOWN_NS) return
        candidate.lastEvidenceNs = now
        candidate.hits++

        if (!macroFrozen && candidate.hits >= MIN_CROSSMODAL_SUPPORT) {
            val joint = promoteCrossModalCandidate(signature, candidate, audio, visual) ?: return
            activateCrossModalMacro(
                joint,
                supportConfidence = (candidate.hits.toDouble() / (candidate.hits + 3.0)).coerceIn(0.0, 1.0)
            )
        }
    }

    private fun promoteCrossModalCandidate(
        signature: String,
        candidate: CrossModalCandidate,
        audio: MacroCode,
        visual: MacroCode
    ): MacroCode? {
        crossModalByPair[signature]?.let { return macros[it] }
        val level = (max(audio.level, visual.level) + 1).coerceAtMost(MAX_ABSTRACTION_LEVEL)
        val joint = MacroCode(
            id = nextMacroId++,
            level = level,
            modality = "crossmodal",
            tokens = listOf(audio.id, visual.id),
            weight = candidate.hits,
            representativeHz = audio.representativeHz,
            firstActivatedObservation = observations,
            lastActivatedObservation = observations
        )
        macros[joint.id] = joint
        crossModalByPair[signature] = joint.id
        macroBySignature[patternSignature((joint.level - 1).coerceAtLeast(0), joint.modality, joint.tokens)] = joint.id
        indexMacroComposition(joint)
        crossModalCandidates.remove(signature)
        return joint
    }

    /**
     * A joint macro is a live derived event, but it does not recursively manufacture sensor
     * evidence. It enters the same bounded working set and a dedicated cross-modal sequence
     * stream, so repeated joint events can still form higher cross-modal temporal structure.
     */
    private fun activateCrossModalMacro(joint: MacroCode, supportConfidence: Double) {
        joint.weight++
        if (joint.firstActivatedObservation <= 0L) joint.firstActivatedObservation = observations
        joint.lastActivatedObservation = observations

        val state = activeWorking.getOrPut(joint.id) {
            WorkingMacro(
                actual = 0.0,
                expected = 0.0,
                lastTouchedObservation = observations,
                retrieved = false
            )
        }
        state.actual = max(state.actual, 0.92)
        state.expected = max(state.expected, supportConfidence * 0.25)
        state.lastTouchedObservation = observations
        state.retrieved = false
        crossModalActivations++

        // Cross-modal joint events have their own temporal stream; no audio↔vision raw relation
        // is added. Only joint macro -> joint macro transitions persist.
        val stream = streamKey(joint.level, joint.modality)
        val previous = previousActiveMacro[stream]
        if (previous != null && previous != joint.id) {
            val key = pairKey(previous, joint.id)
            macroRelations[key] = (macroRelations[key] ?: 0L) + 1L
            indexRelation(key)
        }
        previousActiveMacro[stream] = joint.id

        feedToken(streamLevel = joint.level, modality = joint.modality, tokenId = joint.id)
    }

    private fun evaluatePendingPrediction(streamKey: String, actualMacroId: Int) {
        val pending = pendingPredictionsByStream.remove(streamKey).orEmpty()
        if (pending.isEmpty()) return
        predictionChecks++
        if (actualMacroId in pending) predictionHits++
    }

    /**
     * Directional next-state recall. The relation index is used only as an address index;
     * reverse/incoming edges are ignored. Confidence is a normalized transition share
     * multiplied by a small support term so one-off transitions do not dominate.
     */
    private fun predictNextIntoWorkingSet(source: MacroCode): List<ActivePrediction> {
        val outgoing = relationIndex[source.id].orEmpty().asSequence()
            .mapNotNull { key ->
                if (keyFrom(key) != source.id) return@mapNotNull null
                val targetId = keyTo(key)
                if (targetId == source.id) return@mapNotNull null
                val target = macros[targetId] ?: return@mapNotNull null
                if (target.modality != source.modality || target.level != source.level) return@mapNotNull null
                val support = macroRelations[key] ?: return@mapNotNull null
                if (support < MIN_PREDICTION_SUPPORT) return@mapNotNull null
                targetId to support
            }
            .toList()

        if (outgoing.isEmpty()) return emptyList()
        val total = outgoing.sumOf { it.second }.coerceAtLeast(1L).toDouble()

        return outgoing.asSequence()
            .map { (targetId, support) ->
                val share = support.toDouble() / total
                val supportConfidence = support.toDouble() / (support.toDouble() + 4.0)
                ActivePrediction(
                    sourceId = source.id,
                    targetId = targetId,
                    confidence = (share * supportConfidence).coerceIn(0.0, 1.0),
                    support = support
                )
            }
            .filter { it.confidence >= MIN_PREDICTION_CONFIDENCE }
            .sortedWith(compareByDescending<ActivePrediction> { it.confidence }.thenByDescending { it.support })
            .take(MAX_PREDICTIONS_PER_EVENT)
            .onEach { prediction ->
                // B-state expectation only. Prediction itself is not a learned event.
                touchAsContext(prediction.targetId, prediction.confidence)
            }
            .toList()
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
            val competition = runMemoryCompetitionInternal(force = false)
            save()
            return ConsolidationResult(0, 0, 0, 0, competitionPruned = competition.macrosPruned)
        }

        val threshold = max(2L, minSequenceHits)
        val templatesBefore = learnedTemplateCount()
        val substitutionClassesBefore = derivedSubstitutionClasses().size
        var created = 0
        var highest = 0

        for (sourceLevel in 0 until MAX_ABSTRACTION_LEVEL) {
            val eligible = candidates.entries
                .asSequence()
                .filter { (_, c) -> c.streamLevel == sourceLevel }
                .mapNotNull { entry ->
                    val c = entry.value
                    val score = AdaptiveHierarchyPolicy.evaluate(
                        patternLength = c.tokens.size,
                        hits = c.hits,
                        sourceLevel = c.streamLevel,
                        minimumHits = threshold
                    )
                    if (score.eligible) Triple(entry.key, c, score) else null
                }
                .sortedByDescending { it.third.priority }
                .take(MAX_PROMOTIONS_PER_LEVEL)
                .toList()

            for ((signature, c, _) in eligible) {
                val macro = promoteCandidate(signature, c, autoPromotion = false) ?: continue
                created++
                highest = max(highest, macro.level)
            }
        }

        // Candidates are evidence, not a second permanent memory. Keep weak recent evidence
        // with decay so the system can learn across multiple consolidation windows.
        decayCandidates()
        pruneMacroRelations()
        val competition = runMemoryCompetitionInternal(force = false)
        save()

        return ConsolidationResult(
            microPromoted = 0,
            macroPromoted = created,
            sequencePromoted = created,
            highestNewLevel = highest,
            templatesDiscovered = (learnedTemplateCount() - templatesBefore).coerceAtLeast(0),
            substitutionClassesDiscovered =
                (derivedSubstitutionClasses().size - substitutionClassesBefore).coerceAtLeast(0),
            competitionPruned = competition.macrosPruned
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
        quants[id]?.let { q ->
            return when {
                q.representativeHz > 0.0 -> listOf(q.representativeHz)
                q.modality == "audio_adaptive" -> listOf(AdaptiveAudioQuantizer.projectionHzForId(id))
                q.modality == "audio_direct" -> listOf(DirectAudioQuantizer.projectionHzForId(id))
                else -> emptyList()
            }
        }
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
        currentPredictions.clear()

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
        val predictions = predictNextIntoWorkingSet(seed)
        currentPredictions.addAll(predictions)
        lastPredictionCandidates = predictions.size
        lastTopPredictionConfidence = predictions.maxOfOrNull { it.confidence } ?: 0.0

        val localIds = LinkedHashSet<Int>()
        localIds += seed.id
        localIds.addAll(retrieved)
        localIds.addAll(predictions.map { it.targetId })

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
        memoryPlaybackCursor = predictions.maxByOrNull { it.confidence }?.targetId
            ?: chooseNextMemoryCursor(seed.id, seed.modality)
        return true
    }


    /**
     * Step 16 HYBRID activation.
     *
     * The current live macro is the anchor. Between sensor events the cursor may follow one
     * already learned prediction/relation hop. Only expectation/B-state and transient
     * overexchange are materialized. No observations, macro weights, transition counts,
     * candidates or persistent exchange memory are changed here.
     */
    @Synchronized
    fun hybridPlaybackStep(): Boolean {
        if (macros.isEmpty()) return false

        decayAndPruneWorkingSet()
        currentExchanges.clear()
        currentPredictions.clear()

        val seed = hybridPlaybackCursor?.let { macros[it] }
            ?: activeWorking.entries
                .asSequence()
                .filter { (_, state) -> state.actual >= 0.12 }
                .sortedByDescending { (_, state) -> state.actual * 2.0 + state.expected }
                .mapNotNull { (id, _) -> macros[id] }
                .firstOrNull()
            ?: return false

        // Do not create artificial A-state. If the seed is already a live node, preserve it;
        // otherwise it remains an expected memory continuation only.
        touchAsContext(seed.id, HYBRID_CONTEXT_FLOOR)

        val retrieved = retrieveNeighborsIntoWorkingSet(seed.id, seed.modality)
        val predictions = predictNextIntoWorkingSet(seed)
        currentPredictions.addAll(predictions)
        lastPredictionCandidates = predictions.size
        lastTopPredictionConfidence = predictions.maxOfOrNull { it.confidence } ?: 0.0

        val localIds = LinkedHashSet<Int>()
        localIds += seed.id
        localIds.addAll(retrieved)
        localIds.addAll(predictions.map { it.targetId })

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

        hybridPlaybackSteps++
        lastHybridSeedId = seed.id
        hybridPlaybackCursor = predictions.maxByOrNull { it.confidence }?.targetId
            ?: chooseNextMemoryCursor(seed.id, seed.modality)
        return true
    }

    /** Drop only runtime activation. Persistent learned memory remains untouched. */
    @Synchronized
    fun clearMomentaryState() {
        activeWorking.clear()
        currentExchanges.clear()
        currentPredictions.clear()
        pendingPredictionsByStream.clear()
        previousActiveMacro.clear()
        lastRetrievedCount = 0
        lastExchangePairs = 0
        lastMeanAbsExchange = 0.0
        memoryPlaybackCursor = null
        memoryPlaybackSteps = 0L
        hybridPlaybackCursor = null
        hybridPlaybackSteps = 0L
        lastHybridSeedId = 0
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

        // Step 08: if the ordinary relation graph has no continuation, a learned
        // structural alternative may continue the memory-playback walk.
        chooseSubstitutionAlternative(sourceId, modality)?.let { return it }

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
                structuralScore = rank.score,
                structuralRelevance = rank.relevance,
                contextEntropy = rank.contextEntropy,
                persistence = rank.persistence,
                generality = rank.generality
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
            .filter { isAudioModality(it.value.modality) }
            .sortedByDescending { it.value.weight }
            .take(maxMicro)
            .map { (id, q) ->
                val outputHz = when {
                    q.representativeHz > 0.0 -> q.representativeHz
                    q.modality == "audio_adaptive" -> AdaptiveAudioQuantizer.projectionHzForId(id)
                    else -> DirectAudioQuantizer.projectionHzForId(id)
                }
                SonificationNode(id, outputHz, q.weight)
            }

        val macro = macros.values
            .filter { isAudioModality(it.modality) }
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
                    hz = if (m.representativeHz > 0.0) m.representativeHz else projectionHzForMacro(m),
                    weight = m.weight,
                    memberHz = leafHzOf(m.id).distinct().take(8),
                    abstractionLevel = m.level,
                    structuralRank = rank.label,
                    structuralRankIndex = rank.rankIndex,
                    structuralScore = rank.score,
                    structuralRelevance = rank.relevance,
                    contextEntropy = rank.contextEntropy,
                    persistence = rank.persistence,
                    generality = rank.generality
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
                specificity = rank.specificity,
                contextEntropy = rank.contextEntropy,
                persistence = rank.persistence,
                generality = rank.generality,
                structuralRelevance = rank.relevance
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

        val predictions = currentPredictions
            .asSequence()
            .filter { it.sourceId in selectedIds && it.targetId in selectedIds }
            .sortedByDescending { it.confidence }
            .take(MAX_PREDICTIONS_PER_EVENT)
            .toList()

        val crossModalLinks = selected.asSequence()
            .mapNotNull { (id, state) ->
                val joint = macros[id] ?: return@mapNotNull null
                if (joint.modality != "crossmodal" || joint.tokens.size != 2) return@mapNotNull null
                val audioId = joint.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "audio" } == true }
                    ?: return@mapNotNull null
                val visualId = joint.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "vision" } == true }
                    ?: return@mapNotNull null
                if (audioId !in selectedIds || visualId !in selectedIds) return@mapNotNull null
                ActiveCrossModalLink(
                    jointId = id,
                    audioId = audioId,
                    visualId = visualId,
                    confidence = (state.actual * 0.7 + state.expected * 0.3).coerceIn(0.0, 1.0)
                )
            }
            .take(12)
            .toList()

        return WorkingSetSnapshot(nodes, exchanges, predictions, crossModalLinks)
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
        val activeProfiles = activeWorking.keys
            .asSequence()
            .mapNotNull { macros[it] }
            .map { structuralRankFor(it) }
            .toList()
        val highestActiveRank = activeProfiles.maxByOrNull { it.score }
        val meanActiveRelevance =
            if (activeProfiles.isEmpty()) 0.0 else activeProfiles.map { it.relevance }.average()
        val highestActiveRelevance = activeProfiles.maxOfOrNull { it.relevance } ?: 0.0
        val meanActiveContextEntropy =
            if (activeProfiles.isEmpty()) 0.0 else activeProfiles.map { it.contextEntropy }.average()
        val highestActiveGenerality = activeProfiles.maxOfOrNull { it.generality } ?: 0.0

        val learnedTemplates = learnedTemplateBuckets()
        val activeTemplateCount = learnedTemplates.count { bucket ->
            bucket.macroIds.any { activeWorking.containsKey(it) }
        }
        val templateVariantLinks = learnedTemplates.sumOf { it.variants.size }
        val widestTemplate = learnedTemplates.maxOfOrNull { it.variants.size } ?: 0

        val substitutionClassesNow = derivedSubstitutionClasses()
        val activeSubstitutionClasses = substitutionClassesNow.count { clazz ->
            clazz.members.any { activeWorking.containsKey(it) }
        }
        val substitutionMembers = substitutionClassesNow
            .asSequence()
            .flatMap { it.members.asSequence() }
            .distinct()
            .count()
        val strongestSubstitutionSupport =
            substitutionClassesNow.maxOfOrNull { it.strongestPairSupport } ?: 0

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
            visualPersistentCodes = quants.values.count { it.modality.startsWith("vision") },
            crossModalRelations = macros.values.count { it.modality == "crossmodal" },
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
            widestTemplateVariants = widestTemplate,
            substitutionClasses = substitutionClassesNow.size,
            activeSubstitutionClasses = activeSubstitutionClasses,
            substitutionMembers = substitutionMembers,
            strongestSubstitutionSupport = strongestSubstitutionSupport,
            meanActiveRelevance = meanActiveRelevance,
            highestActiveRelevance = highestActiveRelevance,
            meanActiveContextEntropy = meanActiveContextEntropy,
            highestActiveGenerality = highestActiveGenerality,
            fftAudioCodes = quants.values.count { it.modality == "audio" },
            directAudioCodes = quants.values.count { it.modality == "audio_direct" },
            adaptiveAudioCodes = quants.values.count { it.modality == "audio_adaptive" },
            fftAudioMacros = macros.values.count { it.modality == "audio" },
            directAudioMacros = macros.values.count { it.modality == "audio_direct" },
            adaptiveAudioMacros = macros.values.count { it.modality == "audio_adaptive" },
            adaptiveVisualCodes = quants.values.count { it.modality == "vision_adaptive" },
            adaptiveVisualMacros = macros.values.count { it.modality == "vision_adaptive" },
            hierarchyBaseInputTokens = hierarchyBaseInputTokens,
            hierarchyBaseMacroEmissions = hierarchyBaseMacroEmissions,
            hierarchyBaseSavedTokens = hierarchyBaseSavedTokens,
            hierarchyBaseCompressionGain = if (hierarchyBaseInputTokens <= 0L) 0.0 else
                (hierarchyBaseSavedTokens.toDouble() / hierarchyBaseInputTokens.toDouble()).coerceIn(0.0, 1.0),
            hierarchyAllMacroEmissions = hierarchyAllMacroEmissions,
            hierarchyAutoPromotions = hierarchyAutoPromotions,
            hierarchyMeanAcceptedSpan = if (hierarchyAllMacroEmissions <= 0L) 0.0 else
                hierarchyAcceptedSpanTotal.toDouble() / hierarchyAllMacroEmissions.toDouble(),
            competitionRuns = competitionRuns,
            competitionMacrosPruned = competitionMacrosPruned,
            competitionRelationsPruned = competitionRelationsPruned,
            competitionCandidatesPruned = competitionCandidatesPruned,
            competitionLastPruned = competitionLastPruned,
            competitionLastProtected = competitionLastProtected,
            competitionLastPressuredGroups = competitionLastPressuredGroups,
            predictionCandidates = lastPredictionCandidates,
            predictionTopConfidence = lastTopPredictionConfidence,
            predictionChecks = predictionChecks,
            predictionHits = predictionHits,
            predictionHitRate = if (predictionChecks <= 0L) 0.0 else predictionHits.toDouble() / predictionChecks.toDouble(),
            hybridSteps = hybridPlaybackSteps,
            hybridSeedId = lastHybridSeedId,
            crossModalMacros = macros.values.count { it.modality == "crossmodal" },
            crossModalCandidates = crossModalCandidates.size,
            crossModalActivations = crossModalActivations,
            strongestCrossModalSupport = crossModalCandidates.values.maxOfOrNull { it.hits } ?: 0L
        )
    }

    @Synchronized
    fun reloadFromDisk() {
        quants.clear()
        candidates.clear()
        macros.clear()
        macroBySignature.clear()
        macroParentUseCount.clear()
        crossModalCandidates.clear()
        crossModalByPair.clear()
        recentSensorMacros.values.forEach { it.clear() }
        crossModalActivations = 0L
        templateBuckets.clear()
        templateSignaturesByMacro.clear()
        substitutionPairSupport.clear()
        substitutionTokenMeta.clear()
        substitutionClassesCache = emptyList()
        substitutionClassesDirty = true
        macroRelations.clear()
        relationIndex.clear()
        activeWorking.clear()
        exchangeMemory.clear()
        currentExchanges.clear()
        currentPredictions.clear()
        pendingPredictionsByStream.clear()
        memoryPlaybackCursor = null
        memoryPlaybackSteps = 0L
        hybridPlaybackCursor = null
        hybridPlaybackSteps = 0L
        lastHybridSeedId = 0
        rolling.clear()
        previousActiveMacro.clear()
        hierarchyStreamPositions.clear()
        hierarchyCoveredUntil.clear()
        hierarchyLastAutoPromotionAt.clear()
        hierarchyBaseInputTokens = 0L
        hierarchyBaseMacroEmissions = 0L
        hierarchyBaseSavedTokens = 0L
        hierarchyAllMacroEmissions = 0L
        hierarchyAcceptedSpanTotal = 0L
        hierarchyAutoPromotions = 0L
        hierarchyAutoPromotionsSinceSave = 0
        competitionRuns = 0L
        competitionMacrosPruned = 0L
        competitionRelationsPruned = 0L
        competitionCandidatesPruned = 0L
        competitionLastPruned = 0
        competitionLastProtected = 0
        competitionLastPressuredGroups = 0
        predictionChecks = 0L
        predictionHits = 0L
        lastPredictionCandidates = 0
        lastTopPredictionConfidence = 0.0
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
        substitutionPairSupport.clear()
        substitutionTokenMeta.clear()
        substitutionClassesCache = emptyList()
        substitutionClassesDirty = true
        macroRelations.clear()
        relationIndex.clear()
        activeWorking.clear()
        exchangeMemory.clear()
        currentExchanges.clear()
        currentPredictions.clear()
        pendingPredictionsByStream.clear()
        memoryPlaybackCursor = null
        memoryPlaybackSteps = 0L
        hybridPlaybackCursor = null
        hybridPlaybackSteps = 0L
        lastHybridSeedId = 0
        rolling.clear()
        previousActiveMacro.clear()
        hierarchyStreamPositions.clear()
        hierarchyCoveredUntil.clear()
        hierarchyLastAutoPromotionAt.clear()
        hierarchyBaseInputTokens = 0L
        hierarchyBaseMacroEmissions = 0L
        hierarchyBaseSavedTokens = 0L
        hierarchyAllMacroEmissions = 0L
        hierarchyAcceptedSpanTotal = 0L
        hierarchyAutoPromotions = 0L
        hierarchyAutoPromotionsSinceSave = 0
        competitionRuns = 0L
        competitionMacrosPruned = 0L
        competitionRelationsPruned = 0L
        competitionCandidatesPruned = 0L
        competitionLastPruned = 0
        competitionLastProtected = 0
        competitionLastPressuredGroups = 0
        predictionChecks = 0L
        predictionHits = 0L
        lastPredictionCandidates = 0
        lastTopPredictionConfidence = 0.0
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
                .put("formatVersion", 60_19)
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
                        .put("firstActivatedObservation", m.firstActivatedObservation)
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
                    firstActivatedObservation = o.optLong(
                        "firstActivatedObservation",
                        o.optLong("lastActivatedObservation", observations)
                    ),
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

            rebuildCrossModalIndex()
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
            relationWeights = relationWeights,
            firstActivatedObservation = macro.firstActivatedObservation,
            lastActivatedObservation = macro.lastActivatedObservation,
            currentObservation = observations
        )
    }

    private fun indexMacroComposition(macro: MacroCode) {
        macro.tokens.asSequence()
            .filter { macros.containsKey(it) }
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
        // Cross-modal pair identity is intentionally not treated as a same-modality slot grammar.
        if (macro.modality == "crossmodal") return
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

            val variant = macro.tokens[slot]
            val isNewVariant = variant !in bucket.variants

            // Step 08 only forms substitution classes above the raw quant alphabet.
            // A level-2+ template contains macro tokens as its slot variants.
            if (isNewVariant && macros.containsKey(variant)) {
                substitutionTokenMeta[variant] = SubstitutionTokenMeta(
                    memberLevel = (macro.level - 1).coerceAtLeast(1),
                    modality = macro.modality
                )

                // Count one unit of evidence per distinct structural role in which two
                // macro tokens are observed as alternatives. The local cap keeps a very
                // broad template from creating an unbounded pair burst.
                bucket.variants.asSequence()
                    .filter { macros.containsKey(it) }
                    .take(MAX_CLASS_VARIANTS_PER_TEMPLATE)
                    .forEach { other ->
                        if (other == variant) return@forEach
                        val key = canonicalPairKey(variant, other)
                        if (substitutionPairSupport.containsKey(key) ||
                            substitutionPairSupport.size < MAX_SUBSTITUTION_PAIR_INDEX
                        ) {
                            substitutionPairSupport[key] = (substitutionPairSupport[key] ?: 0) + 1
                            substitutionClassesDirty = true
                        }
                    }
            }

            bucket.variants += variant
            templateSignaturesByMacro.getOrPut(macro.id) { linkedSetOf() }.add(signature)
        }
    }

    private fun rebuildTemplateIndex() {
        templateBuckets.clear()
        templateSignaturesByMacro.clear()
        substitutionPairSupport.clear()
        substitutionTokenMeta.clear()
        substitutionClassesCache = emptyList()
        substitutionClassesDirty = true
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


    /**
     * Derived substitution classes. A class is a connected component of macro tokens
     * whose pair evidence reached MIN_SUBSTITUTION_ROLE_SUPPORT in the same modality
     * and abstraction level. The classes are cached and rebuilt only when the template
     * index changes.
     */
    private fun derivedSubstitutionClasses(): List<DerivedSubstitutionClass> {
        if (!substitutionClassesDirty) return substitutionClassesCache

        val qualifyingEdges = substitutionPairSupport.entries.asSequence()
            .filter { it.value >= MIN_SUBSTITUTION_ROLE_SUPPORT }
            .mapNotNull { (key, support) ->
                val a = keyFrom(key)
                val b = keyTo(key)
                val ma = substitutionTokenMeta[a] ?: return@mapNotNull null
                val mb = substitutionTokenMeta[b] ?: return@mapNotNull null
                if (ma != mb) return@mapNotNull null
                Triple(a, b, support)
            }
            .toList()

        if (qualifyingEdges.isEmpty()) {
            substitutionClassesCache = emptyList()
            substitutionClassesDirty = false
            return substitutionClassesCache
        }

        val parent = mutableMapOf<Int, Int>()

        fun find(x: Int): Int {
            val p = parent.getOrPut(x) { x }
            if (p == x) return x
            val root = find(p)
            parent[x] = root
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            // Deterministic root keeps class IDs stable across rebuilds.
            if (ra <= rb) parent[rb] = ra else parent[ra] = rb
        }

        qualifyingEdges.forEach { (a, b, _) -> union(a, b) }

        val groups = linkedMapOf<Int, MutableSet<Int>>()
        parent.keys.forEach { token ->
            groups.getOrPut(find(token)) { linkedSetOf() }.add(token)
        }

        val edgeSupportByRoot = mutableMapOf<Int, Int>()
        val strongestByRoot = mutableMapOf<Int, Int>()
        qualifyingEdges.forEach { (a, b, support) ->
            val root = find(a)
            if (root != find(b)) return@forEach
            edgeSupportByRoot[root] = (edgeSupportByRoot[root] ?: 0) + support
            strongestByRoot[root] = max(strongestByRoot[root] ?: 0, support)
        }

        substitutionClassesCache = groups.entries.asSequence()
            .mapNotNull { (root, memberSet) ->
                if (memberSet.size < 2) return@mapNotNull null
                val members = memberSet.toList()
                    .sortedWith(
                        compareByDescending<Int> { macros[it]?.weight ?: 0L }
                            .thenBy { it }
                    )
                val meta = members.firstNotNullOfOrNull { substitutionTokenMeta[it] }
                    ?: return@mapNotNull null
                DerivedSubstitutionClass(
                    classId = members.minOrNull() ?: root,
                    memberLevel = meta.memberLevel,
                    modality = meta.modality,
                    members = members,
                    roleEvidence = edgeSupportByRoot[root] ?: 0,
                    strongestPairSupport = strongestByRoot[root] ?: 0
                )
            }
            .sortedWith(
                compareByDescending<DerivedSubstitutionClass> { it.strongestPairSupport }
                    .thenByDescending { it.roleEvidence }
                    .thenByDescending { it.members.size }
                    .thenBy { it.classId }
            )
            .toList()

        substitutionClassesDirty = false
        return substitutionClassesCache
    }

    /**
     * Public read-only view for LAB/diagnostics. These are structural equivalence
     * candidates, not semantic categories.
     */
    @Synchronized
    fun substitutionClassSnapshot(maxClasses: Int = 24): List<SubstitutionClassSummary> =
        derivedSubstitutionClasses()
            .asSequence()
            .map { clazz ->
                SubstitutionClassSummary(
                    classId = clazz.classId,
                    memberLevel = clazz.memberLevel,
                    modality = clazz.modality,
                    members = clazz.members,
                    roleEvidence = clazz.roleEvidence,
                    strongestPairSupport = clazz.strongestPairSupport,
                    activeMembers = clazz.members.count { activeWorking.containsKey(it) }
                )
            }
            .sortedWith(
                compareByDescending<SubstitutionClassSummary> { it.activeMembers > 0 }
                    .thenByDescending { it.strongestPairSupport }
                    .thenByDescending { it.roleEvidence }
                    .thenByDescending { it.members.size }
            )
            .take(maxClasses.coerceAtLeast(0))
            .toList()

    /**
     * Memory-only playback can use structural alternatives as a fallback when the
     * ordinary relation graph reaches a dead end. This never changes live expectation,
     * never creates relations and never learns a class back into memory.
     */
    private fun chooseSubstitutionAlternative(sourceId: Int, modality: String): Int? {
        val clazz = derivedSubstitutionClasses()
            .firstOrNull { it.modality == modality && sourceId in it.members }
            ?: return null

        val alternatives = clazz.members
            .asSequence()
            .filter { it != sourceId && macros[it]?.modality == modality }
            .sortedByDescending { macros[it]?.weight ?: 0L }
            .take(12)
            .toList()

        if (alternatives.isEmpty()) return null
        return alternatives[(memoryPlaybackSteps % alternatives.size.toLong()).toInt()]
    }

    private fun templateSignature(level: Int, modality: String, tokens: List<Int>, slotIndex: Int): String =
        buildString {
            append(modality).append('|').append(level).append("|slot=").append(slotIndex).append('|')
            tokens.forEachIndexed { index, token ->
                if (index > 0) append(',')
                if (index == slotIndex) append('*') else append(token)
            }
        }


    /**
     * Step 14 manual maintenance entry point. Automatic competition also runs at consolidation.
     * force=true does NOT bypass dependency protection; it only allows clearly stale, very
     * low-utility leaf macros to compete before a stream reaches its soft capacity target.
     */
    @Synchronized
    fun runMemoryCompetition(force: Boolean = true): MemoryCompetitionResult {
        val result = runMemoryCompetitionInternal(force)
        if (result.macrosPruned > 0 || result.relationsPruned > 0 || result.candidatesPruned > 0) {
            save()
        }
        return result
    }

    private fun runMemoryCompetitionInternal(force: Boolean): MemoryCompetitionResult {
        competitionRuns++
        rebuildMacroCompositionIndex()

        if (macros.isEmpty()) {
            competitionLastPruned = 0
            competitionLastProtected = 0
            competitionLastPressuredGroups = 0
            return MemoryCompetitionResult(
                runs = competitionRuns,
                macrosExamined = 0,
                macrosPruned = 0,
                relationsPruned = 0,
                candidatesPruned = 0,
                protectedMacros = 0,
                pressuredGroups = 0,
                remainingMacros = 0
            )
        }

        data class Candidate(
            val macro: MacroCode,
            val score: MemoryCompetitionScore
        )

        val previousIds = previousActiveMacro.values.toSet()
        val grouped = macros.values.groupBy { "${it.modality}|${it.level}" }
        val removeIds = linkedSetOf<Int>()
        var protectedCount = 0
        var pressuredGroups = 0
        var examined = 0

        for ((_, group) in grouped) {
            val pressure = group.size > MemoryCompetitionPolicy.HARD_MACROS_PER_STREAM
            if (pressure) pressuredGroups++

            val candidatesForGroup = ArrayList<Candidate>()
            for (macro in group) {
                examined++
                val relationKeys = relationIndex[macro.id].orEmpty()
                val relationWeights = relationKeys.mapNotNull { macroRelations[it] }
                val relationMass = relationWeights.sum()
                val profile = structuralRankFor(macro)
                val score = MemoryCompetitionPolicy.evaluate(
                    weight = macro.weight,
                    abstractionLevel = macro.level,
                    parentReuseCount = macroParentUseCount[macro.id] ?: 0,
                    relationMass = relationMass,
                    relationDegree = relationWeights.size,
                    templateRoleCount = templateSignaturesByMacro[macro.id]?.size ?: 0,
                    ageObservations = (observations - macro.lastActivatedObservation).coerceAtLeast(0L),
                    active = activeWorking.containsKey(macro.id),
                    previousContext = macro.id in previousIds,
                    profile = profile
                )
                if (score.protected) {
                    protectedCount++
                } else {
                    candidatesForGroup += Candidate(macro, score)
                }
            }

            if (candidatesForGroup.isEmpty()) continue
            val pressureNeed = (group.size - MemoryCompetitionPolicy.TARGET_MACROS_PER_STREAM).coerceAtLeast(0)
            val budget = if (pressure) {
                pressureNeed.coerceAtMost(MemoryCompetitionPolicy.MAX_PRUNE_PER_RUN - removeIds.size)
            } else if (force) {
                minOf(16, MemoryCompetitionPolicy.MAX_PRUNE_PER_RUN - removeIds.size)
            } else 0
            if (budget <= 0) continue

            candidatesForGroup
                .asSequence()
                .filter { c ->
                    val age = (observations - c.macro.lastActivatedObservation).coerceAtLeast(0L)
                    if (pressure) {
                        c.score.stale || c.score.utility < 0.34
                    } else {
                        c.score.stale && c.score.utility < 0.20 && age >= MemoryCompetitionPolicy.MIN_IDLE_OBSERVATIONS * 2L
                    }
                }
                .sortedWith(
                    compareBy<Candidate> { it.score.utility }
                        .thenBy { it.macro.weight }
                        .thenBy { it.macro.lastActivatedObservation }
                )
                .take(budget)
                .forEach { removeIds += it.macro.id }

            if (removeIds.size >= MemoryCompetitionPolicy.MAX_PRUNE_PER_RUN) break
        }

        if (removeIds.isEmpty()) {
            competitionLastPruned = 0
            competitionLastProtected = protectedCount
            competitionLastPressuredGroups = pressuredGroups
            return MemoryCompetitionResult(
                runs = competitionRuns,
                macrosExamined = examined,
                macrosPruned = 0,
                relationsPruned = 0,
                candidatesPruned = 0,
                protectedMacros = protectedCount,
                pressuredGroups = pressuredGroups,
                remainingMacros = macros.size
            )
        }

        // Invalid higher-level candidates must disappear with a removed ingredient.
        val candidatesBefore = candidates.size
        candidates.entries.removeIf { (_, candidate) -> candidate.tokens.any { it in removeIds } }
        val candidatesPruned = candidatesBefore - candidates.size

        val relationsBefore = macroRelations.size
        macroRelations.entries.removeIf { (key, _) -> keyFrom(key) in removeIds || keyTo(key) in removeIds }
        val relationsPruned = relationsBefore - macroRelations.size
        exchangeMemory.entries.removeIf { (key, _) -> keyFrom(key) in removeIds || keyTo(key) in removeIds }
        currentExchanges.entries.removeIf { (key, _) -> keyFrom(key) in removeIds || keyTo(key) in removeIds }

        removeIds.forEach { id ->
            val macro = macros.remove(id) ?: return@forEach
            val signature = patternSignature(macro.level - 1, macro.modality, macro.tokens)
            if (macroBySignature[signature] == id) macroBySignature.remove(signature)
            if (macro.modality == "crossmodal" && macro.tokens.size == 2) {
                val audioId = macro.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "audio" } == true }
                val visualId = macro.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "vision" } == true }
                if (audioId != null && visualId != null) crossModalByPair.remove(crossModalPairSignature(audioId, visualId))
            }
            activeWorking.remove(id)
        }

        previousActiveMacro.entries.removeIf { it.value in removeIds }
        memoryPlaybackCursor = memoryPlaybackCursor?.takeUnless { it in removeIds }

        // Rolling buffers are runtime state only. Remove any now-invalid macro tokens.
        rolling.values.forEach { buffer ->
            if (buffer.any { it in removeIds }) {
                val kept = buffer.filterNot { it in removeIds }
                buffer.clear()
                kept.forEach { buffer.addLast(it) }
            }
        }

        // Rebuild bounded derived indexes from surviving identities. This is maintenance-time
        // work only and never runs per audio frame.
        macroParentUseCount.clear()
        rebuildCrossModalIndex()
        rebuildMacroCompositionIndex()
        rebuildTemplateIndex()
        relationIndex.clear()
        macroRelations.keys.forEach { indexRelation(it) }

        competitionMacrosPruned += removeIds.size.toLong()
        competitionRelationsPruned += relationsPruned.toLong()
        competitionCandidatesPruned += candidatesPruned.toLong()
        competitionLastPruned = removeIds.size
        competitionLastProtected = protectedCount
        competitionLastPressuredGroups = pressuredGroups

        return MemoryCompetitionResult(
            runs = competitionRuns,
            macrosExamined = examined,
            macrosPruned = removeIds.size,
            relationsPruned = relationsPruned,
            candidatesPruned = candidatesPruned,
            protectedMacros = protectedCount,
            pressuredGroups = pressuredGroups,
            remainingMacros = macros.size
        )
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

    private fun canonicalPairKey(a: Int, b: Int): Long =
        if (a <= b) pairKey(a, b) else pairKey(b, a)

    private fun exchangePairKey(a: Int, b: Int): Long = canonicalPairKey(a, b)

    private fun exchangeValue(from: Int, to: Int): Double {
        val memory = exchangeMemory[exchangePairKey(from, to)] ?: return 0.0
        return if (from <= to) memory.ema else -memory.ema
    }

    private fun isAudioModality(modality: String): Boolean =
        modality == "audio" || modality == "audio_direct" || modality == "audio_adaptive"

    private fun sensorFamily(modality: String): String? = when {
        isAudioModality(modality) -> "audio"
        modality == "vision" || modality == "vision_adaptive" -> "vision"
        else -> null
    }

    private fun crossModalPairSignature(audioMacroId: Int, visualMacroId: Int): String =
        "xm|$audioMacroId|$visualMacroId"

    private fun rebuildCrossModalIndex() {
        crossModalByPair.clear()
        macros.values.asSequence()
            .filter { it.modality == "crossmodal" && it.tokens.size == 2 }
            .forEach { joint ->
                val audioId = joint.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "audio" } == true }
                val visualId = joint.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "vision" } == true }
                if (audioId != null && visualId != null) {
                    crossModalByPair[crossModalPairSignature(audioId, visualId)] = joint.id
                }
            }
    }

    /**
     * Direct-ID audio deliberately stores no pseudo-frequency in memory. For the
     * orchestra only, a stable pitch is projected from the macro composition.
     */
    private fun projectionHzForMacro(macro: MacroCode): Double {
        if (macro.modality == "audio_adaptive") {
            val leaves = leafIdsOfModality(macro.id, "audio_adaptive").take(12)
            if (leaves.isNotEmpty()) return leaves.map { AdaptiveAudioQuantizer.projectionHzForId(it) }.average()
            return AdaptiveAudioQuantizer.projectionHzForId(-1_000_001 - (macro.id % 65_536))
        }
        val leaves = leafIdsOfModality(macro.id, "audio_direct").take(12)
        if (leaves.isNotEmpty()) return leaves.map { DirectAudioQuantizer.projectionHzForId(it) }.average()
        return DirectAudioQuantizer.projectionHzForId(-((macro.id % 65_536) + 1))
    }

    private fun leafIdsOfModality(id: Int, modality: String, depth: Int = 0): List<Int> {
        if (depth > MAX_ABSTRACTION_LEVEL + 2) return emptyList()
        quants[id]?.let { q -> return if (q.modality == modality) listOf(id) else emptyList() }
        val macro = macros[id] ?: return emptyList()
        return macro.tokens.flatMap { leafIdsOfModality(it, modality, depth + 1) }
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
