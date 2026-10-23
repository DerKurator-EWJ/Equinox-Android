package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max

private const val MACRO_ID_BASE = 1_000_000
private const val MAX_ABSTRACTION_LEVEL = 4
private const val MIN_PATTERN_LENGTH = 2
private const val MAX_PATTERN_LENGTH = 6
private const val MAX_PATTERN_CANDIDATES = 6_000
private const val MAX_PROMOTIONS_PER_LEVEL = 24

/**
 * v0.6 Step 02
 *
 * Architecture change:
 * - level-0 sensor symbols are a quant alphabet, not a relational graph
 * - only recurring patterns are promoted to persistent macro nodes
 * - only macro nodes create persistent relations
 * - matching is lazy: each new token checks only tails of the currently active stream
 * - higher abstraction levels receive activated macro IDs as their own token stream
 *
 * The public API deliberately stays compatible with the stable Step12 UI.
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
    val sequenceCapacityUsedPercent: Double
)

data class GraphNode(
    val id: Int,
    val weight: Long,
    val representativeHz: Double,
    val abstractionLevel: Int = 0,
    val memberCount: Int = 1,
    val kind: String = "micro",
    val modality: String = "audio",
    val displayColor: Int = 0
) {
    val isMacro: Boolean get() = abstractionLevel > 0
}

data class GraphEdge(
    val fromId: Int,
    val toId: Int,
    val weight: Long,
    val abstractionLevel: Int = 0,
    val isMembership: Boolean = false
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
    val abstractionLevel: Int
)

data class SonificationSnapshot(
    val micro: List<SonificationNode>,
    val macros: List<SonificationMacro>
)

data class ConsolidationResult(
    val microPromoted: Int,
    val macroPromoted: Int,
    val sequencePromoted: Int,
    val highestNewLevel: Int
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
    private val macroRelations = mutableMapOf<Long, Long>()

    // One short active stream per modality and abstraction source level.
    private val rolling = mutableMapOf<String, ArrayDeque<Int>>()
    private val previousActiveMacro = mutableMapOf<String, Int>()

    private var nextMacroId = MACRO_ID_BASE
    private var macroFrozen = false
    private var observations = 0L

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

        val relationKey = streamKey(macro.level, macro.modality)
        previousActiveMacro[relationKey]?.let { previous ->
            if (previous != macro.id) {
                val key = pairKey(previous, macro.id)
                macroRelations[key] = (macroRelations[key] ?: 0L) + 1L
            }
        }
        previousActiveMacro[relationKey] = macro.id

        // The activated macro becomes one token for the next abstraction stream.
        feedToken(
            streamLevel = macro.level,
            modality = macro.modality,
            tokenId = macro.id
        )
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
            highestNewLevel = highest
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
                    compareByDescending<MacroCode> { it.lastActivatedObservation }
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
            nodes += GraphNode(
                id = m.id,
                weight = m.weight,
                representativeHz = m.representativeHz,
                abstractionLevel = m.level,
                memberCount = m.tokens.size,
                kind = "pattern",
                modality = m.modality,
                displayColor = 0
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
                GraphEdge(from, to, weight, abstractionLevel = level)
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
                compareByDescending<MacroCode> { it.lastActivatedObservation }
                    .thenByDescending { it.level }
                    .thenByDescending { it.weight }
            )
            .take(maxMacros)
            .map { m ->
                SonificationMacro(
                    id = m.id,
                    hz = m.representativeHz,
                    weight = m.weight,
                    memberHz = leafHzOf(m.id).distinct().take(8),
                    abstractionLevel = m.level
                )
            }

        return SonificationSnapshot(micro, macro)
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
            sequenceCapacityUsedPercent = candidates.size * 100.0 / MAX_PATTERN_CANDIDATES.toDouble()
        )
    }

    @Synchronized
    fun reloadFromDisk() {
        quants.clear()
        candidates.clear()
        macros.clear()
        macroBySignature.clear()
        macroRelations.clear()
        rolling.clear()
        previousActiveMacro.clear()
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
        macroRelations.clear()
        rolling.clear()
        previousActiveMacro.clear()
        nextMacroId = MACRO_ID_BASE
        macroFrozen = false
        observations = 0L
        if (file.exists()) file.delete()
    }

    private fun save() {
        try {
            val root = JSONObject()
                .put("formatVersion", 60_02)
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

            val relationArray = root.optJSONArray("macroRelations") ?: JSONArray()
            for (i in 0 until relationArray.length()) {
                val o = relationArray.getJSONObject(i)
                macroRelations[o.getLong("key")] = o.optLong("weight", 0L)
            }
        } catch (_: Exception) {
        }
    }

    private fun pruneMacroRelations() {
        if (macroRelations.size <= 4096) return
        val keep = macroRelations.entries.sortedByDescending { it.value }.take(3072)
        macroRelations.clear()
        keep.forEach { macroRelations[it.key] = it.value }
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
