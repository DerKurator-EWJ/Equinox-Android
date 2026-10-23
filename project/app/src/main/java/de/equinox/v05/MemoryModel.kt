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
private const val MIN_PATTERN_LENGTH = 2
private const val AUTO_PROMOTION_COOLDOWN_BASE_TOKENS = 48L

// Step 07/08: one-slot templates feed the sparse substitution-class layer.
private const val MIN_TEMPLATE_LENGTH = 3
private const val MIN_TEMPLATE_VARIANTS = 2
private const val SLOT_TOKEN = Int.MIN_VALUE

// Step 08: sparse substitution classes above the raw quant alphabet.
// Two macro tokens become structurally linked only when they can replace each other
// in at least two distinct learned template roles.
private const val MIN_SUBSTITUTION_ROLE_SUPPORT = 2

// Step 25: event-driven working set with relevance-mass scaling; no fixed node count.
private const val ACTIVE_TTL_OBSERVATIONS = 24L
private const val ACTUAL_DECAY = 0.82
private const val EXPECTATION_DECAY = 0.78
private const val EXCHANGE_EMA_ALPHA = 0.15

// Step 25: sparse directional recall/prediction. Prediction breadth is selected by probability mass,
// not a fixed Top-k count.
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

/**
 * v0.6 Step 23
 *
 * Keeps the active working set, indexed lazy retrieval, structural tiers,
 * templates and substitution classes. Step 09 added a read-only multidimensional
 * relevance profile and now supports three isolated audio alphabets: legacy FFT
 * frequency IDs ("audio"), fixed 16-bit direct IDs ("audio_direct") and the learned
 * adaptive vector-quantizer IDs ("audio_adaptive"). They never mix sequence streams.
 * Step 13 added adaptive hierarchy promotion and non-overlapping macro emission.
 * Step 14 added dependency-safe memory competition. Step 15 added sparse directional
 * predictive recall. Step 16 added HYBRID mode. Step 19 added a sparse cross-modal layer above the independent
 * audio/vision streams: only already-learned sensor macros can become joint audio+visual macros,
 * and only after repeated near-simultaneous evidence. Raw quants remain modality-local.
 * Step 22 adds an autonomous read-only DREAM traversal that can switch among learned
 * predictions, ordinary relations, substitution alternatives and cross-modal bridges.
 * Step 23 replaces the monolithic JSON checkpoint with a compact indexed long-term store.
 * Macro headers stay lightweight in RAM while token payloads are disk-backed and read lazily.
 * Step 25 removes semantic ceilings: hierarchy depth, working-set breadth, candidate/template counts
 * and adaptive codebook sizes are evidence/resource-driven rather than fixed constants.
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
    val highestActiveTier: String = "—",
    val highestActiveMaturityScore: Double = 0.0,
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
    val strongestCrossModalSupport: Long = 0L,
    val dreamSteps: Long = 0L,
    val dreamPredictionHops: Long = 0L,
    val dreamRelationHops: Long = 0L,
    val dreamSubstitutionHops: Long = 0L,
    val dreamCrossModalHops: Long = 0L,
    val dreamRestarts: Long = 0L,
    val dreamLastMode: String = "—",
    val dreamCursorId: Int = 0,
    val storageBackend: String = "indexed-binary",
    val storageBytes: Long = 0L,
    val storageDiskBackedMacros: Int = 0,
    val storageTokenReads: Long = 0L
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
    val abstractionTier: String = "F",
    val abstractionTierIndex: Int = 0,
    val maturityScore: Double = 0.0,
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
    val abstractionTier: String = "F",
    val abstractionTierIndex: Int = 0,
    val maturityScore: Double = 0.0,
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
    val abstractionTier: String = "F",
    val abstractionTierIndex: Int = 0,
    val maturityScore: Double = 0.0,
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
        var tokens: List<Int>,
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

    // Step 23: indexed binary store is primary. The old JSON file is retained only as
    // a one-time migration source for users upgrading from Step 22 or earlier.
    private val legacyFile = File(context.filesDir, "equinox_v06_memory.json")
    private val longTermStore = IndexedLongTermStore(context)

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
    // Step 22: member -> learned joint-macro index. DREAM can jump across modalities
    // without scanning every cross-modal macro on each autonomous step.
    private val crossModalMemberIndex = mutableMapOf<Int, MutableSet<Int>>()
    private val recentSensorMacros = mutableMapOf(
        "audio" to ArrayDeque<RecentSensorMacro>(),
        "vision" to ArrayDeque<RecentSensorMacro>()
    )
    private var crossModalActivations = 0L

    // Step 07/08 structural generalization index. Each stable macro contributes at most
    // one masked signature per token position; no pairwise macro comparison is required.
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

    // Step 22 autonomous DREAM traversal. All fields are runtime-only. The dream can
    // explore learned alternatives but never calls observe(), feedToken() or writes relations.
    private enum class DreamHopKind { PREDICTION, RELATION, SUBSTITUTION, CROSSMODAL, RESTART }
    private data class DreamChoice(val targetId: Int, val kind: DreamHopKind, val score: Double)
    private var dreamPlaybackCursor: Int? = null
    private var dreamPlaybackSteps = 0L
    private var dreamPredictionHops = 0L
    private var dreamRelationHops = 0L
    private var dreamSubstitutionHops = 0L
    private var dreamCrossModalHops = 0L
    private var dreamRestarts = 0L
    private var dreamLastMode = "—"
    private val recentDreamIds = ArrayDeque<Int>()

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
     * Pattern horizon grows logarithmically with learned structure/experience. There is no
     * abstraction ceiling and no global sequence-capacity number. Existing macros are still
     * addressed directly by signature, so persistent memory is never globally scanned here.
     */
    private fun feedToken(streamLevel: Int, modality: String, tokenId: Int) {
        val streamKey = streamKey(streamLevel, modality)
        val streamPosition = (hierarchyStreamPositions[streamKey] ?: 0L) + 1L
        hierarchyStreamPositions[streamKey] = streamPosition
        if (streamLevel == 0) hierarchyBaseInputTokens++

        val horizon = AdaptiveScalePolicy.patternHorizon(
            macrosAtDepth = macros.size,
            observationsAtDepth = streamPosition
        )
        val buffer = rolling.getOrPut(streamKey) { ArrayDeque() }
        buffer.addLast(tokenId)
        while (buffer.size > horizon) buffer.removeFirst()

        val current = buffer.toList()
        val coveredUntilBeforeEvent = hierarchyCoveredUntil[streamKey] ?: 0L
        var bestActivated: MacroCode? = null
        var bestAutoSignature: String? = null
        var bestAutoCandidate: PatternCandidate? = null
        var bestAutoPriority = 0.0
        val promotionCooldown = (AUTO_PROMOTION_COOLDOWN_BASE_TOKENS shr streamLevel.coerceIn(0, 2)).coerceAtLeast(8L)
        val lastAutoPromotionAt = hierarchyLastAutoPromotionAt[streamKey] ?: Long.MIN_VALUE / 4L
        val autoPromotionAllowed = streamPosition - lastAutoPromotionAt >= promotionCooldown

        for (length in MIN_PATTERN_LENGTH..current.size) {
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
            } else {
                PatternCandidate(
                    streamLevel = streamLevel,
                    modality = modality,
                    tokens = tail,
                    hits = 1L
                ).also { candidates[signature] = it }
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

        pruneTransientCandidatePressureIfNeeded()

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
        if (candidate.streamLevel < 0) return null

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

        // Evaluate the previous event's probability-mass forecast before creating the next one.
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
        // supported audio+visual joint macro into the same adaptive working set.
        observeCrossModalEvidence(macro)

        enforceAdaptiveWorkingSet()

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

        val otherQueue = recentSensorMacros.getValue(opposite)
        pruneRecentSensorQueue(otherQueue, now)
        if (otherQueue.isEmpty()) return

        val recentPartners = otherQueue.asSequence()
            .mapNotNull { recent -> macros[recent.macroId]?.let { recent to it } }
            .filter { (_, m) -> m.id != source.id }
            .distinctBy { (_, m) -> m.id }
            .sortedByDescending { (recent, _) -> recent.seenNs }
            .toList()
        val temporalWeights = recentPartners.map { (recent, _) ->
            (1.0 - ((now - recent.seenNs).toDouble() / CROSSMODAL_WINDOW_NS.toDouble())).coerceIn(0.01, 1.0)
        }
        val totalTemporal = temporalWeights.sum().coerceAtLeast(1e-9)
        var temporalMass = 0.0
        recentPartners.forEachIndexed { index, (_, other) ->
            if (temporalMass / totalTemporal >= AdaptiveScalePolicy.RETRIEVAL_MASS_TARGET && index > 0) return@forEachIndexed
            temporalMass += temporalWeights[index]
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
            pruneCrossModalCandidateEvidence(now)
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
        val level = max(audio.level, visual.level) + 1
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
        crossModalMemberIndex.getOrPut(audio.id) { linkedSetOf() }.add(joint.id)
        crossModalMemberIndex.getOrPut(visual.id) { linkedSetOf() }.add(joint.id)
        macroBySignature[patternSignature((joint.level - 1).coerceAtLeast(0), joint.modality, joint.tokens)] = joint.id
        indexMacroComposition(joint)
        crossModalCandidates.remove(signature)
        return joint
    }

    /**
     * A joint macro is a live derived event, but it does not recursively manufacture sensor
     * evidence. It enters the same adaptive working set and a dedicated cross-modal sequence
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

        val ranked = outgoing.asSequence()
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
            .toList()
        if (ranked.isEmpty()) return emptyList()
        val confidenceMass = ranked.sumOf { it.confidence }.coerceAtLeast(1e-9)
        val selected = ArrayList<ActivePrediction>()
        var accumulated = 0.0
        for (prediction in ranked) {
            selected += prediction
            accumulated += prediction.confidence
            if (accumulated / confidenceMass >= AdaptiveScalePolicy.PREDICTION_MASS_TARGET) break
        }
        selected.forEach { prediction ->
            // B-state expectation only. Prediction itself is not a learned event.
            touchAsContext(prediction.targetId, prediction.confidence)
        }
        return selected
    }

    /** Pull only indexed neighbors of one active macro into momentary context. */
    private fun retrieveNeighborsIntoWorkingSet(sourceId: Int, modality: String): List<Int> {
        val keys = relationIndex[sourceId] ?: return emptyList()
        val ranked = keys.asSequence()
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
            .toList()
        if (ranked.isEmpty()) return emptyList()
        val totalWeight = ranked.sumOf { it.second.toDouble() }.coerceAtLeast(1e-9)
        val selected = ArrayList<Int>()
        var mass = 0.0
        for ((id, weight, expectation) in ranked) {
            touchAsContext(id, expectation)
            selected += id
            mass += weight.toDouble()
            if (mass / totalWeight >= AdaptiveScalePolicy.RETRIEVAL_MASS_TARGET) break
        }
        return selected
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

    private fun enforceAdaptiveWorkingSet() {
        if (activeWorking.isEmpty()) return
        val ranked = activeWorking.entries
            .map { entry ->
                val state = entry.value
                val score = (state.actual * 2.0 + state.expected + abs(state.exchangeBias) * 0.5 -
                    (observations - state.lastTouchedObservation).coerceAtLeast(0L) * 0.01).coerceAtLeast(0.0)
                entry to score
            }
            .sortedByDescending { it.second }
        val mandatory = ranked.filter { it.first.value.actual >= 0.50 }.map { it.first.key }.toMutableSet()
        val total = ranked.sumOf { it.second }.coerceAtLeast(1e-9)
        var mass = ranked.filter { it.first.key in mandatory }.sumOf { it.second }
        val keepIds = mandatory
        for ((entry, score) in ranked) {
            if (entry.key in keepIds) continue
            if (mass / total >= AdaptiveScalePolicy.WORKING_MASS_TARGET && keepIds.isNotEmpty()) break
            keepIds += entry.key
            mass += score
        }
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

        val sourceLevels = candidates.values.map { it.streamLevel }.distinct().sorted()
        for (sourceLevel in sourceLevels) {
            val eligibleAll = candidates.entries
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
                .toList()
            val eligible = eligibleAll.take(AdaptiveScalePolicy.promotionBudget(eligibleAll.size))

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

    private fun pruneTransientCandidatePressureIfNeeded() {
        val target = AdaptiveScalePolicy.candidateSoftTarget(quants.size, macros.size)
        if (candidates.size <= target * 2) return
        val keep = candidates.entries
            .sortedWith(compareByDescending<Map.Entry<String, PatternCandidate>> { it.value.hits }
                .thenByDescending { it.value.tokens.size })
            .take(target)
            .associate { it.key to it.value }
        candidates.clear()
        candidates.putAll(keep)
    }

    private fun pruneCrossModalCandidateEvidence(now: Long) {
        val staleAfter = CROSSMODAL_WINDOW_NS * 6L
        val it = crossModalCandidates.iterator()
        while (it.hasNext()) {
            val (_, candidate) = it.next()
            if (now - candidate.lastEvidenceNs > staleAfter && candidate.hits < MIN_CROSSMODAL_SUPPORT) it.remove()
        }
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

    private fun leafHzOf(id: Int): List<Double> {
        val out = ArrayList<Double>()
        val stack = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        stack.addLast(id)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            val q = quants[current]
            if (q != null) {
                when {
                    q.representativeHz > 0.0 -> out += q.representativeHz
                    q.modality == "audio_adaptive" -> out += AdaptiveAudioQuantizer.projectionHzForId(current)
                    q.modality == "audio_direct" -> out += DirectAudioQuantizer.projectionHzForId(current)
                }
                continue
            }
            macros[current]?.tokens?.forEach { stack.addLast(it) }
        }
        return out
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
        enforceAdaptiveWorkingSet()

        memoryPlaybackSteps++
        memoryPlaybackCursor = predictions.maxByOrNull { it.confidence }?.targetId
            ?: chooseNextMemoryCursor(seed.id, seed.modality)
        return true
    }


    /**
     * Step 22 autonomous DREAM activation.
     *
     * DREAM is deliberately read-only. One materialized macro becomes the temporary
     * foreground, while the next cursor is chosen from learned prediction, relation,
     * substitution or cross-modal alternatives. No observation counters, macro weights,
     * relation counts, candidate hits or persistent overexchange values are changed.
     */
    @Synchronized
    fun autonomousDreamStep(): Boolean {
        if (macros.isEmpty()) return false

        decayAndPruneWorkingSet()
        currentExchanges.clear()
        currentPredictions.clear()

        val seed = dreamPlaybackCursor?.let { macros[it] } ?: chooseDreamSeed() ?: return false
        val seedState = activeWorking.getOrPut(seed.id) {
            WorkingMacro(
                actual = 0.0,
                expected = 0.0,
                lastTouchedObservation = observations,
                retrieved = true
            )
        }
        // In DREAM, actual means "foreground of the internal projection", never sensor truth.
        seedState.actual = max(seedState.actual, 0.82)
        seedState.expected = max(seedState.expected, 0.18)
        seedState.lastTouchedObservation = observations
        seedState.retrieved = true

        val retrieved = retrieveNeighborsIntoWorkingSet(seed.id, seed.modality)
        val predictions = predictNextIntoWorkingSet(seed)
        currentPredictions.addAll(predictions)
        lastPredictionCandidates = predictions.size
        lastTopPredictionConfidence = predictions.maxOfOrNull { it.confidence } ?: 0.0

        val choices = buildDreamChoices(seed, predictions)
        val chosen = chooseDreamChoice(choices)
        if (chosen != null) {
            val expectation = chosen.score.coerceIn(0.18, 0.96)
            touchAsContext(chosen.targetId, expectation)
            when (chosen.kind) {
                DreamHopKind.PREDICTION -> dreamPredictionHops++
                DreamHopKind.RELATION -> dreamRelationHops++
                DreamHopKind.SUBSTITUTION -> dreamSubstitutionHops++
                DreamHopKind.CROSSMODAL -> dreamCrossModalHops++
                DreamHopKind.RESTART -> dreamRestarts++
            }
            dreamLastMode = chosen.kind.name
        } else {
            dreamLastMode = DreamHopKind.RESTART.name
            dreamRestarts++
        }

        val localIds = LinkedHashSet<Int>()
        localIds += seed.id
        localIds.addAll(retrieved)
        localIds.addAll(predictions.map { it.targetId })
        chosen?.let { localIds += it.targetId }

        // If a joint cross-modal macro is in the local dream frontier, expose both learned
        // sensor components as context. This is projection only; it creates no coincidence evidence.
        localIds.toList().forEach { id ->
            val joint = macros[id] ?: return@forEach
            if (joint.modality == "crossmodal") {
                joint.tokens.filter { macros.containsKey(it) }.forEach { child ->
                    touchAsContext(child, 0.36)
                    localIds += child
                }
            }
        }

        val values = ArrayList<Double>()
        for (otherId in localIds) {
            if (otherId == seed.id) continue
            val r = calculateTransientOverexchange(seed.id, otherId) ?: continue
            values += abs(r)
        }

        lastRetrievedCount = retrieved.size
        lastExchangePairs = values.size
        lastMeanAbsExchange = if (values.isEmpty()) 0.0 else values.average()
        enforceAdaptiveWorkingSet()

        rememberDreamId(seed.id)
        dreamPlaybackSteps++
        dreamPlaybackCursor = chosen?.targetId ?: chooseDreamSeed(excludeId = seed.id)?.id
        return true
    }

    private fun buildDreamChoices(seed: MacroCode, predictions: List<ActivePrediction>): List<DreamChoice> {
        val choices = LinkedHashMap<Int, DreamChoice>()

        fun offer(targetId: Int, kind: DreamHopKind, rawScore: Double) {
            if (targetId == seed.id) return
            val target = macros[targetId] ?: return
            val profile = structuralProfileFor(target)
            val recentPenalty = if (targetId in recentDreamIds) 0.34 else 1.0
            val levelAffinity = 1.0 / (1.0 + 0.18 * abs(target.level - seed.level).toDouble())
            val score = (rawScore + 0.22 * profile.relevance) * recentPenalty * levelAffinity
            val previous = choices[targetId]
            if (previous == null || score > previous.score) {
                choices[targetId] = DreamChoice(targetId, kind, score.coerceAtLeast(0.0))
            }
        }

        predictions.forEach { p -> offer(p.targetId, DreamHopKind.PREDICTION, 0.55 + 0.95 * p.confidence) }

        relationIndex[seed.id].orEmpty().asSequence()
            .mapNotNull { key ->
                val from = keyFrom(key)
                val to = keyTo(key)
                val other = if (from == seed.id) to else from
                val target = macros[other] ?: return@mapNotNull null
                if (target.modality != seed.modality) return@mapNotNull null
                val weight = macroRelations[key] ?: return@mapNotNull null
                Triple(other, weight, relationConfidenceByWeight(weight))
            }
            .sortedByDescending { it.second }
            .forEach { (id, _, confidence) -> offer(id, DreamHopKind.RELATION, 0.42 + 0.72 * confidence) }

        dreamSubstitutionAlternatives(seed.id, seed.modality)
            .forEach { id -> offer(id, DreamHopKind.SUBSTITUTION, 0.56) }

        dreamCrossModalAlternatives(seed.id)
            .forEach { id -> offer(id, DreamHopKind.CROSSMODAL, 0.66) }

        return choices.values.toList()
    }

    private fun chooseDreamChoice(choices: List<DreamChoice>): DreamChoice? {
        if (choices.isEmpty()) return null

        // Deterministic exploration schedule: autonomous and reproducible, but not locked
        // forever to the strongest edge. Missing categories simply fall back to best score.
        val preferred = when ((dreamPlaybackSteps % 9L).toInt()) {
            0, 4 -> DreamHopKind.PREDICTION
            1, 6 -> DreamHopKind.RELATION
            2, 7 -> DreamHopKind.CROSSMODAL
            3 -> DreamHopKind.SUBSTITUTION
            else -> null
        }
        val pool = preferred?.let { kind -> choices.filter { it.kind == kind } }.orEmpty().ifEmpty { choices }
        return pool.maxByOrNull { choice -> choice.score + dreamJitter(choice.targetId) }
    }

    private fun dreamJitter(id: Int): Double {
        var x = id.toLong() xor (dreamPlaybackSteps * -7046029254386353131L)
        x = x xor (x ushr 33)
        x *= -49064778989728563L
        x = x xor (x ushr 33)
        val unit = ((x ushr 11) and 0xffffL).toDouble() / 65535.0
        return unit * 0.14
    }

    private fun dreamSubstitutionAlternatives(sourceId: Int, modality: String): List<Int> =
        derivedSubstitutionClasses()
            .asSequence()
            .filter { it.modality == modality && sourceId in it.members }
            .flatMap { it.members.asSequence() }
            .filter { it != sourceId && macros[it]?.modality == modality }
            .distinct()
            .sortedByDescending { macros[it]?.weight ?: 0L }
            .toList()

    private fun dreamCrossModalAlternatives(sourceId: Int): List<Int> {
        val source = macros[sourceId] ?: return emptyList()
        if (source.modality == "crossmodal") {
            return source.tokens.asSequence()
                .filter { macros.containsKey(it) }
                    .toList()
        }
        return crossModalMemberIndex[sourceId].orEmpty().asSequence()
            .filter { macros[it]?.modality == "crossmodal" }
            .sortedByDescending { macros[it]?.weight ?: 0L }
            .toList()
    }

    private fun chooseDreamSeed(excludeId: Int? = null): MacroCode? {
        val top = macros.values.asSequence()
            .filter { it.id != excludeId }
            .sortedWith(
                compareByDescending<MacroCode> { structuralProfileFor(it).relevance }
                    .thenByDescending { it.level }
                    .thenByDescending { it.weight }
            )
            .toList()
        if (top.isEmpty()) return null
        val preferred = top.filter { it.id !in recentDreamIds }.ifEmpty { top }
        val index = ((dreamPlaybackSteps + dreamRestarts) % preferred.size.toLong()).toInt()
        return preferred[index]
    }

    private fun rememberDreamId(id: Int) {
        recentDreamIds.remove(id)
        recentDreamIds.addLast(id)
        val recentTarget = kotlin.math.max(8, kotlin.math.sqrt(macros.size.coerceAtLeast(1).toDouble()).toInt())
        while (recentDreamIds.size > recentTarget) recentDreamIds.removeFirst()
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
        enforceAdaptiveWorkingSet()

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
        dreamPlaybackCursor = null
        dreamPlaybackSteps = 0L
        dreamPredictionHops = 0L
        dreamRelationHops = 0L
        dreamSubstitutionHops = 0L
        dreamCrossModalHops = 0L
        dreamRestarts = 0L
        dreamLastMode = "—"
        recentDreamIds.clear()
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

        val selectedMacros = macros.values.map { it.level }.distinct().sorted().flatMap { level ->
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
            val profile = structuralProfileFor(m)
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
                abstractionTier = profile.tierLabel,
                abstractionTierIndex = profile.tierIndex,
                maturityScore = profile.score,
                structuralRelevance = profile.relevance,
                contextEntropy = profile.contextEntropy,
                persistence = profile.persistence,
                generality = profile.generality
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
                val profile = structuralProfileFor(m)
                SonificationMacro(
                    id = m.id,
                    hz = if (m.representativeHz > 0.0) m.representativeHz else projectionHzForMacro(m),
                    weight = m.weight,
                    memberHz = leafHzOf(m.id).distinct().take(8),
                    abstractionLevel = m.level,
                    abstractionTier = profile.tierLabel,
                    abstractionTierIndex = profile.tierIndex,
                    maturityScore = profile.score,
                    structuralRelevance = profile.relevance,
                    contextEntropy = profile.contextEntropy,
                    persistence = profile.persistence,
                    generality = profile.generality
                )
            }

        return SonificationSnapshot(micro, macro)
    }

    @Synchronized
    fun workingSetSnapshot(maxNodes: Int = Int.MAX_VALUE): WorkingSetSnapshot {
        val selected = activeWorking.entries
            .filter { macros.containsKey(it.key) }
            .sortedByDescending { (_, state) ->
                state.actual * 2.0 + state.expected + abs(state.exchangeBias) * 0.5
            }
            .let { list -> if (maxNodes == Int.MAX_VALUE) list else list.take(maxNodes) }

        val selectedIds = selected.map { it.key }.toSet()
        val nodes = selected.mapNotNull { (id, state) ->
            val macro = macros[id] ?: return@mapNotNull null
            val profile = structuralProfileFor(macro)
            ActiveMacroNode(
                id = id,
                abstractionLevel = macro.level,
                modality = macro.modality,
                activation = state.actual,
                expectation = state.expected,
                exchangeBias = state.exchangeBias,
                retrieved = state.retrieved,
                abstractionTier = profile.tierLabel,
                abstractionTierIndex = profile.tierIndex,
                maturityScore = profile.score,
                recurrence = profile.recurrence,
                reuse = profile.reuse,
                relationSupport = profile.relationSupport,
                specificity = profile.specificity,
                contextEntropy = profile.contextEntropy,
                persistence = profile.persistence,
                generality = profile.generality,
                structuralRelevance = profile.relevance
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
            .let { seq -> if (maxNodes == Int.MAX_VALUE) seq.toList() else seq.take((maxNodes * 2).coerceAtLeast(maxNodes)).toList() }

        val predictions = currentPredictions
            .asSequence()
            .filter { it.sourceId in selectedIds && it.targetId in selectedIds }
            .sortedByDescending { it.confidence }
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
        val activeMacrosNow = activeWorking.keys
            .asSequence()
            .mapNotNull { macros[it] }
            .toList()
        val activeProfiles = activeMacrosNow.map { structuralProfileFor(it) }
        val highestActiveDepth = activeMacrosNow.maxOfOrNull { it.level } ?: 0
        val highestActiveTier = if (highestActiveDepth > 0) AbstractionTier.labelForMacroDepth(highestActiveDepth) else "—"
        val highestActiveMaturityScore = activeProfiles.maxOfOrNull { it.score } ?: 0.0
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
        val storageStats = longTermStore.stats()

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
            sequenceCapacity = 0,
            sequenceCapacityUsedPercent = 0.0,
            activeMacros = activeWorking.size,
            retrievedMacros = activeWorking.values.count { it.retrieved },
            overexchangePairs = lastExchangePairs,
            meanAbsOverexchange = lastMeanAbsExchange,
            highestActiveTier = highestActiveTier,
            highestActiveMaturityScore = highestActiveMaturityScore,
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
            strongestCrossModalSupport = crossModalCandidates.values.maxOfOrNull { it.hits } ?: 0L,
            dreamSteps = dreamPlaybackSteps,
            dreamPredictionHops = dreamPredictionHops,
            dreamRelationHops = dreamRelationHops,
            dreamSubstitutionHops = dreamSubstitutionHops,
            dreamCrossModalHops = dreamCrossModalHops,
            dreamRestarts = dreamRestarts,
            dreamLastMode = dreamLastMode,
            dreamCursorId = dreamPlaybackCursor ?: 0,
            storageBackend = "indexed-binary",
            storageBytes = storageStats.bytes,
            storageDiskBackedMacros = macros.values.count { it.tokens.javaClass.name.contains("DiskTokenList") },
            storageTokenReads = storageStats.tokenReads
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
        crossModalMemberIndex.clear()
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
        dreamPlaybackCursor = null
        dreamPlaybackSteps = 0L
        dreamPredictionHops = 0L
        dreamRelationHops = 0L
        dreamSubstitutionHops = 0L
        dreamCrossModalHops = 0L
        dreamRestarts = 0L
        dreamLastMode = "—"
        recentDreamIds.clear()
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
        crossModalCandidates.clear()
        crossModalByPair.clear()
        crossModalMemberIndex.clear()
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
        dreamPlaybackCursor = null
        dreamPlaybackSteps = 0L
        dreamPredictionHops = 0L
        dreamRelationHops = 0L
        dreamSubstitutionHops = 0L
        dreamCrossModalHops = 0L
        dreamRestarts = 0L
        dreamLastMode = "—"
        recentDreamIds.clear()
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
        longTermStore.deleteAll()
        if (legacyFile.exists()) legacyFile.delete()
    }

    private fun save() {
        try {
            val macroSnapshot = macros.values.map { m ->
                IndexedLongTermStore.StoredMacro(
                    id = m.id,
                    level = m.level,
                    modality = m.modality,
                    tokens = m.tokens,
                    weight = m.weight,
                    hz = m.representativeHz,
                    firstObs = m.firstActivatedObservation,
                    lastObs = m.lastActivatedObservation,
                    signature = patternSignature(
                        level = (m.level - 1).coerceAtLeast(0),
                        modality = m.modality,
                        tokens = m.tokens
                    )
                )
            }

            val result = longTermStore.writeSnapshot(
                meta = IndexedLongTermStore.Meta(
                    observations = observations,
                    nextMacroId = nextMacroId,
                    macroFrozen = macroFrozen
                ),
                quants = quants.map { (id, q) ->
                    IndexedLongTermStore.StoredQuant(
                        id = id,
                        weight = q.weight,
                        hz = q.representativeHz,
                        modality = q.modality,
                        displayColor = q.displayColor
                    )
                },
                macros = macroSnapshot,
                relations = macroRelations.map { (key, weight) ->
                    IndexedLongTermStore.StoredRelation(key, weight)
                },
                exchanges = exchangeMemory.map { (key, memory) ->
                    IndexedLongTermStore.StoredExchange(key, memory.ema, memory.samples)
                }
            )

            // Rebind every persistent macro to the just-written token index. The token arrays
            // no longer need to remain resident after a checkpoint; reads become on-demand.
            macros.values.forEach { macro ->
                val location = result.macroLocations[macro.id] ?: return@forEach
                macro.tokens = longTermStore.tokenList(location.offsetBytes, location.tokenCount)
            }
        } catch (_: Exception) {
        }
    }

    private fun load() {
        if (longTermStore.exists()) {
            loadIndexedStore()
            return
        }

        // One-time compatibility path for Step 22 and earlier. After a successful parse the
        // legacy JSON is immediately checkpointed into the indexed store and renamed.
        if (legacyFile.exists() && loadLegacyJson()) {
            save()
            runCatching {
                legacyFile.renameTo(File(legacyFile.parentFile, "equinox_v06_memory.migrated.json"))
            }
        }
    }

    private fun loadIndexedStore() {
        try {
            val meta = longTermStore.readMeta() ?: return
            observations = meta.observations
            nextMacroId = meta.nextMacroId
            macroFrozen = meta.macroFrozen

            longTermStore.readQuants().forEach { q ->
                quants[q.id] = QuantCode(
                    weight = q.weight,
                    representativeHz = q.hz,
                    modality = q.modality,
                    displayColor = q.displayColor
                )
            }

            longTermStore.readMacroHeaders().forEach { h ->
                val macro = MacroCode(
                    id = h.id,
                    level = h.level.coerceAtLeast(1),
                    modality = h.modality,
                    tokens = longTermStore.tokenList(h.tokenOffsetBytes, h.tokenCount),
                    weight = h.weight,
                    representativeHz = h.hz,
                    firstActivatedObservation = h.firstObs,
                    lastActivatedObservation = h.lastObs
                )
                macros[macro.id] = macro
                macroBySignature[h.signature] = macro.id
                nextMacroId = max(nextMacroId, macro.id + 1)
            }

            // These are compact runtime indexes. Rebuilding them scans token payload from disk
            // but does not retain every token list in RAM.
            rebuildCrossModalIndex()
            rebuildMacroCompositionIndex()
            rebuildTemplateIndex()

            longTermStore.readRelations().forEach { r ->
                macroRelations[r.key] = r.weight
                indexRelation(r.key)
            }

            longTermStore.readExchanges().forEach { e ->
                exchangeMemory[e.key] = ExchangeMemory(e.ema, e.samples)
            }
        } catch (_: Exception) {
        }
    }

    private fun loadLegacyJson(): Boolean {
        if (!legacyFile.exists()) return false
        return try {
            val root = JSONObject(legacyFile.readText())
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
                    level = o.optInt("level", 1).coerceAtLeast(1),
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
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Read-only, on-demand structural profile. It never changes learning or persistence. */
    private fun structuralProfileFor(macro: MacroCode): StructuralProfile {
        val relationWeights = relationIndex[macro.id]
            ?.asSequence()
            ?.mapNotNull { macroRelations[it] }
            ?.toList()
            ?: emptyList()

        return StructuralProfileEngine.evaluate(
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
                // macro tokens are observed as alternatives. There is no global pair-count ceiling;
                // only actually observed alternatives create sparse evidence.
                bucket.variants.asSequence()
                    .filter { macros.containsKey(it) }
                    .forEach { other ->
                        if (other == variant) return@forEach
                        val key = canonicalPairKey(variant, other)
                        substitutionPairSupport[key] = (substitutionPairSupport[key] ?: 0) + 1
                        substitutionClassesDirty = true
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
        // Rebuild all observed template roles; no global template-bucket ceiling remains.
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
    fun substitutionClassSnapshot(maxClasses: Int = Int.MAX_VALUE): List<SubstitutionClassSummary> =
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
            .let { seq -> if (maxClasses == Int.MAX_VALUE) seq.toList() else seq.take(maxClasses.coerceAtLeast(0)).toList() }

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
            val groupLevel = group.maxOfOrNull { it.level } ?: 1
            val targetForGroup = MemoryCompetitionPolicy.targetMacrosForStream(observations, groupLevel)
            val pressureLimit = MemoryCompetitionPolicy.pressureMacrosForStream(observations, groupLevel)
            val pressure = group.size > pressureLimit
            if (pressure) pressuredGroups++

            val candidatesForGroup = ArrayList<Candidate>()
            for (macro in group) {
                examined++
                val relationKeys = relationIndex[macro.id].orEmpty()
                val relationWeights = relationKeys.mapNotNull { macroRelations[it] }
                val relationMass = relationWeights.sum()
                val profile = structuralProfileFor(macro)
                val score = MemoryCompetitionPolicy.evaluate(
                    weight = macro.weight,
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
            val pressureNeed = (group.size - targetForGroup).coerceAtLeast(0)
            val budget = if (pressure) {
                pressureNeed
            } else if (force) {
                MemoryCompetitionPolicy.forcedPruneBudget(candidatesForGroup.size)
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
        dreamPlaybackCursor = dreamPlaybackCursor?.takeUnless { it in removeIds }
        recentDreamIds.removeIf { it in removeIds }

        // Rolling buffers are runtime state only. Remove any now-invalid macro tokens.
        rolling.values.forEach { buffer ->
            if (buffer.any { it in removeIds }) {
                val kept = buffer.filterNot { it in removeIds }
                buffer.clear()
                kept.forEach { buffer.addLast(it) }
            }
        }

        // Rebuild sparse derived indexes from surviving identities. This is maintenance-time
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
        // Relation storage scales with the amount of learned macro structure. This is a soft
        // pressure policy, not a semantic relation-count ceiling.
        val relationTarget = max(1_024, macros.size * 8 + activeWorking.size * 12)
        if (macroRelations.size > relationTarget * 2) {
            val keep = macroRelations.entries.sortedByDescending { it.value }.take(relationTarget)
            macroRelations.clear()
            relationIndex.clear()
            keep.forEach {
                macroRelations[it.key] = it.value
                indexRelation(it.key)
            }
        }

        val exchangeTarget = max(1_024, macros.size * 6 + activeWorking.size * 12)
        if (exchangeMemory.size > exchangeTarget * 2) {
            val keepExchange = exchangeMemory.entries
                .sortedByDescending { abs(it.value.ema) * min(64L, it.value.samples).toDouble() }
                .take(exchangeTarget)
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
        crossModalMemberIndex.clear()
        macros.values.asSequence()
            .filter { it.modality == "crossmodal" && it.tokens.size == 2 }
            .forEach { joint ->
                val audioId = joint.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "audio" } == true }
                val visualId = joint.tokens.firstOrNull { child -> macros[child]?.let { sensorFamily(it.modality) == "vision" } == true }
                if (audioId != null && visualId != null) {
                    crossModalByPair[crossModalPairSignature(audioId, visualId)] = joint.id
                    crossModalMemberIndex.getOrPut(audioId) { linkedSetOf() }.add(joint.id)
                    crossModalMemberIndex.getOrPut(visualId) { linkedSetOf() }.add(joint.id)
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

    private fun leafIdsOfModality(id: Int, modality: String): List<Int> {
        val out = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        stack.addLast(id)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            val q = quants[current]
            if (q != null) {
                if (q.modality == modality) out += current
                continue
            }
            macros[current]?.tokens?.forEach { stack.addLast(it) }
        }
        return out
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
