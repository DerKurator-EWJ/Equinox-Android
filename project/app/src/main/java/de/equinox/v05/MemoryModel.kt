package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

private const val MACRO_ID_BASE = 1_000_000
private const val SEQUENCE_ID_BASE = 1_500_000_000
private const val MAX_ABSTRACTION_LEVEL = 4
private const val MAX_SEQUENCE_DEPTH = 6
private const val MAX_SEQUENCE_NODES = 12_000

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
    val longestSequenceLength: Int
)

data class GraphNode(
    val id: Int,
    val weight: Long,
    val representativeHz: Double,
    val abstractionLevel: Int = 0,
    val memberCount: Int = 1,
    val kind: String = "micro"
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
    private data class Code(var weight: Long, val representativeHz: Double)
    private data class Candidate(var hits: Int, var hzSum: Double)

    private data class MacroCode(
        val id: Int,
        val level: Int,
        val members: MutableSet<Int>,
        var weight: Long,
        var representativeHz: Double,
        val kind: String = "relation",
        val sourceSequenceId: Int? = null
    )

    private data class SequenceNode(
        val id: Int,
        val streamLevel: Int,
        val parentSequenceId: Int,
        val nextTokenId: Int,
        val length: Int,
        var weight: Long,
        var promotedMacroId: Int? = null
    )

    // Same file name deliberately: Step 08 migrates the existing Equinox memory.
    private val file = File(context.filesDir, "equinox_step03_memory.json")

    private val persistent = mutableMapOf<Int, Code>()
    private val candidates = mutableMapOf<Int, Candidate>()
    private val relations = mutableMapOf<Long, Long>()

    private val macros = mutableMapOf<Int, MacroCode>()
    private val macroRelations = mutableMapOf<Long, Long>()

    private val sequences = mutableMapOf<Int, SequenceNode>()
    private val sequenceIndex = mutableMapOf<String, Int>()
    private val activeSequenceSuffixes = mutableMapOf<Int, MutableList<Int>>()

    private var nextMacroId = MACRO_ID_BASE
    private var nextSequenceId = SEQUENCE_ID_BASE
    private var macroFrozen = false
    private var previousId: Int? = null
    private val previousByLevel = mutableMapOf<Int, Int>()
    private var observations = 0L

    init { load() }

    @Synchronized
    fun observe(id: Int, hz: Double) {
        observations++

        val known = persistent[id]
        if (known != null) {
            known.weight++
            feedSequenceToken(streamLevel = 0, tokenId = id)
        } else {
            val c = candidates.getOrPut(id) { Candidate(0, 0.0) }
            c.hits++
            c.hzSum += hz
        }

        previousId?.let { prev ->
            if (prev != id) {
                val key = pairKey(prev, id)
                relations[key] = (relations[key] ?: 0L) + 1L
            }
        }
        previousId = id

        // Relation-derived macro activations.
        var activeChildId = id
        for (level in 1..MAX_ABSTRACTION_LEVEL) {
            val active = macros.values
                .asSequence()
                .filter {
                    it.level == level &&
                    it.kind == "relation" &&
                    activeChildId in it.members
                }
                .maxByOrNull { it.weight }
                ?: break

            recordMacroActivation(level, active.id)
            activeChildId = active.id
        }
    }

    /**
     * Interns the current token and all active suffixes up to MAX_SEQUENCE_DEPTH.
     * A repeated sequence is represented by one SequenceNode whose weight increases.
     */
    private fun feedSequenceToken(streamLevel: Int, tokenId: Int) {
        if (streamLevel >= MAX_ABSTRACTION_LEVEL) return

        val previousSuffixes = activeSequenceSuffixes[streamLevel]?.toList().orEmpty()
        val newSuffixes = mutableListOf<Int>()

        val single = getOrCreateSequence(
            streamLevel = streamLevel,
            parentSequenceId = 0,
            tokenId = tokenId,
            length = 1
        )
        if (single != null) {
            single.weight++
            newSuffixes += single.id
            single.promotedMacroId?.let { recordMacroActivation(streamLevel + 1, it) }
        }

        for (parentId in previousSuffixes) {
            val parent = sequences[parentId] ?: continue
            if (parent.length >= MAX_SEQUENCE_DEPTH) continue

            val seq = getOrCreateSequence(
                streamLevel = streamLevel,
                parentSequenceId = parent.id,
                tokenId = tokenId,
                length = parent.length + 1
            ) ?: continue

            seq.weight++
            newSuffixes += seq.id

            seq.promotedMacroId?.let { promoted ->
                recordMacroActivation(streamLevel + 1, promoted)
            }
        }

        activeSequenceSuffixes[streamLevel] = newSuffixes
            .distinct()
            .sortedByDescending { sequences[it]?.length ?: 0 }
            .take(MAX_SEQUENCE_DEPTH)
            .toMutableList()
    }

    private fun getOrCreateSequence(
        streamLevel: Int,
        parentSequenceId: Int,
        tokenId: Int,
        length: Int
    ): SequenceNode? {
        val key = sequenceKey(streamLevel, parentSequenceId, tokenId)
        sequenceIndex[key]?.let { return sequences[it] }

        if (sequences.size >= MAX_SEQUENCE_NODES) return null

        val node = SequenceNode(
            id = nextSequenceId++,
            streamLevel = streamLevel,
            parentSequenceId = parentSequenceId,
            nextTokenId = tokenId,
            length = length,
            weight = 0L
        )
        sequences[node.id] = node
        sequenceIndex[key] = node.id
        return node
    }

    private fun recordMacroActivation(level: Int, macroId: Int) {
        if (level !in 1..MAX_ABSTRACTION_LEVEL) return
        val macro = macros[macroId] ?: return
        macro.weight++

        previousByLevel[level]?.let { prev ->
            if (prev != macroId) {
                val key = pairKey(prev, macroId)
                macroRelations[key] = (macroRelations[key] ?: 0L) + 1L
            }
        }
        previousByLevel[level] = macroId

        // The activated macro becomes a token in the sequence stream of its own level.
        feedSequenceToken(streamLevel = level, tokenId = macroId)
    }

    @Synchronized
    fun consolidate(
        minHits: Int = 3,
        baseRelationThreshold: Long = 10L,
        minSequenceHits: Long = 6L
    ): ConsolidationResult {
        var promotedMicro = 0

        val it = candidates.iterator()
        while (it.hasNext()) {
            val (id, c) = it.next()
            if (c.hits >= minHits) {
                val existing = persistent[id]
                if (existing == null) {
                    persistent[id] = Code(
                        weight = c.hits.toLong(),
                        representativeHz = c.hzSum / max(1, c.hits)
                    )
                } else {
                    existing.weight += c.hits
                }
                promotedMicro++
            }
            it.remove()
        }

        var promotedMacro = 0
        var sequencePromoted = 0
        var highestNewLevel = 0

        if (!macroFrozen) {
            sequencePromoted = promoteStableSequences(minSequenceHits)
            promotedMacro += sequencePromoted

            if (sequencePromoted > 0) {
                highestNewLevel = macros.values
                    .filter { it.kind == "sequence" }
                    .maxOfOrNull { it.level }
                    ?: highestNewLevel
            }

            for (targetLevel in 1..MAX_ABSTRACTION_LEVEL) {
                val created = createRelationMacrosForLevel(targetLevel, baseRelationThreshold)
                if (created > 0) {
                    promotedMacro += created
                    highestNewLevel = max(highestNewLevel, targetLevel)
                }
            }
        }

        pruneRelations()
        pruneMacroRelations()
        save()

        return ConsolidationResult(
            microPromoted = promotedMicro,
            macroPromoted = promotedMacro,
            sequencePromoted = sequencePromoted,
            highestNewLevel = highestNewLevel
        )
    }

    private fun promoteStableSequences(minSequenceHits: Long): Int {
        val eligible = sequences.values
            .asSequence()
            .filter {
                it.length >= 2 &&
                it.weight >= minSequenceHits &&
                it.promotedMacroId == null &&
                it.streamLevel < MAX_ABSTRACTION_LEVEL
            }
            .sortedWith(
                compareByDescending<SequenceNode> { it.weight }
                    .thenByDescending { it.length }
            )
            .take(16)
            .toList()

        var created = 0

        for (seq in eligible) {
            val orderedTokens = resolveSequenceTokens(seq.id)
            if (orderedTokens.size < 2) continue

            val hzValues = orderedTokens.mapNotNull { representativeHzOf(it) }
            if (hzValues.isEmpty()) continue

            val macro = MacroCode(
                id = nextMacroId++,
                level = seq.streamLevel + 1,
                members = orderedTokens.toMutableSet(),
                weight = seq.weight,
                representativeHz = hzValues.average(),
                kind = "sequence",
                sourceSequenceId = seq.id
            )
            macros[macro.id] = macro
            seq.promotedMacroId = macro.id
            created++
        }

        return created
    }

    private fun resolveSequenceTokens(sequenceId: Int): List<Int> {
        val reversed = mutableListOf<Int>()
        var currentId = sequenceId
        var guard = 0

        while (currentId != 0 && guard < MAX_SEQUENCE_DEPTH + 2) {
            val node = sequences[currentId] ?: break
            reversed += node.nextTokenId
            currentId = node.parentSequenceId
            guard++
        }

        return reversed.asReversed()
    }

    private fun createRelationMacrosForLevel(targetLevel: Int, baseThreshold: Long): Int {
        val sourceLevel = targetLevel - 1
        val threshold = baseThreshold + (sourceLevel * 4L)

        val sourceIds: Set<Int> = if (sourceLevel == 0) {
            persistent.keys.toSet()
        } else {
            macros.values.filter { it.level == sourceLevel }.map { it.id }.toSet()
        }

        if (sourceIds.size < 2) return 0

        val sourceRelations = if (sourceLevel == 0) relations else macroRelations

        val candidatesForMacro = sourceRelations.entries
            .asSequence()
            .map { (key, weight) -> Triple(keyFrom(key), keyTo(key), weight) }
            .filter { (a, b, w) ->
                a != b && w >= threshold && a in sourceIds && b in sourceIds
            }
            .sortedByDescending { it.third }
            .take(if (targetLevel == 1) 48 else 20)
            .toList()

        var created = 0

        for ((a, b, weight) in candidatesForMacro) {
            if (relationMacroForPairAtLevel(targetLevel, a, b) != null) continue

            val hzA = representativeHzOf(a) ?: continue
            val hzB = representativeHzOf(b) ?: continue
            val weightA = weightOf(a).coerceAtLeast(1L)
            val weightB = weightOf(b).coerceAtLeast(1L)
            val total = (weightA + weightB).toDouble()
            val hz = (hzA * weightA + hzB * weightB) / total

            val macro = MacroCode(
                id = nextMacroId++,
                level = targetLevel,
                members = mutableSetOf(a, b),
                weight = weight,
                representativeHz = hz,
                kind = "relation"
            )
            macros[macro.id] = macro
            created++
        }

        return created
    }

    private fun relationMacroForPairAtLevel(level: Int, a: Int, b: Int): MacroCode? =
        macros.values.firstOrNull {
            it.level == level &&
            it.kind == "relation" &&
            it.members.size == 2 &&
            a in it.members &&
            b in it.members
        }

    private fun representativeHzOf(id: Int): Double? =
        persistent[id]?.representativeHz ?: macros[id]?.representativeHz

    private fun weightOf(id: Int): Long =
        persistent[id]?.weight ?: macros[id]?.weight ?: 0L

    private fun leafHzOf(id: Int, depth: Int = 0): List<Double> {
        if (depth > MAX_ABSTRACTION_LEVEL + 2) return emptyList()
        persistent[id]?.let { return listOf(it.representativeHz) }
        val macro = macros[id] ?: return emptyList()

        if (macro.kind == "sequence" && macro.sourceSequenceId != null) {
            return resolveSequenceTokens(macro.sourceSequenceId)
                .flatMap { leafHzOf(it, depth + 1) }
        }

        return macro.members.flatMap { leafHzOf(it, depth + 1) }
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
        if (persistent.isEmpty() && macros.isEmpty()) {
            return GraphSnapshot(emptyList(), emptyList())
        }

        val strongestMicroIds = persistent.entries
            .sortedByDescending { it.value.weight }
            .take(maxMicroNodes)
            .map { it.key }
            .toSet()

        val selectedMacros = (1..MAX_ABSTRACTION_LEVEL).flatMap { level ->
            macros.values
                .filter { it.level == level }
                .sortedByDescending { it.weight }
                .take(maxMacroNodesPerLevel)
        }

        val macroIds = selectedMacros.map { it.id }.toSet()
        val visible = strongestMicroIds + macroIds

        val nodes = mutableListOf<GraphNode>()

        strongestMicroIds.forEach { id ->
            persistent[id]?.let {
                nodes += GraphNode(
                    id = id,
                    weight = it.weight,
                    representativeHz = it.representativeHz,
                    abstractionLevel = 0,
                    memberCount = 1,
                    kind = "micro"
                )
            }
        }

        selectedMacros.forEach { m ->
            nodes += GraphNode(
                id = m.id,
                weight = m.weight,
                representativeHz = m.representativeHz,
                abstractionLevel = m.level,
                memberCount = if (m.kind == "sequence" && m.sourceSequenceId != null) {
                    resolveSequenceTokens(m.sourceSequenceId).size
                } else {
                    m.members.size
                },
                kind = m.kind
            )
        }

        val edges = mutableListOf<GraphEdge>()

        relations.entries
            .asSequence()
            .map { (key, weight) ->
                GraphEdge(keyFrom(key), keyTo(key), weight, abstractionLevel = 0)
            }
            .filter { it.fromId in strongestMicroIds && it.toId in strongestMicroIds }
            .sortedByDescending { it.weight }
            .take(maxEdges)
            .forEach { edges += it }

        selectedMacros.forEach { m ->
            val memberIds = if (m.kind == "sequence" && m.sourceSequenceId != null) {
                resolveSequenceTokens(m.sourceSequenceId).toSet()
            } else {
                m.members
            }

            memberIds.filter { it in visible }.forEach { member ->
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
                val lf = macros[from]?.level ?: return@mapNotNull null
                val lt = macros[to]?.level ?: return@mapNotNull null
                if (from !in visible || to !in visible || lf != lt) return@mapNotNull null
                GraphEdge(
                    fromId = from,
                    toId = to,
                    weight = weight,
                    abstractionLevel = lf
                )
            }
            .sortedByDescending { it.weight }
            .take(100)
            .forEach { edges += it }

        return GraphSnapshot(
            nodes = nodes.sortedWith(
                compareBy<GraphNode> { it.abstractionLevel }.thenByDescending { it.weight }
            ),
            edges = edges
        )
    }

    @Synchronized
    fun sonificationSnapshot(maxMicro: Int = 8, maxMacros: Int = 8): SonificationSnapshot {
        val micro = persistent.entries
            .sortedByDescending { it.value.weight }
            .take(maxMicro)
            .map { (id, code) ->
                SonificationNode(id, code.representativeHz, code.weight)
            }

        val macro = macros.values
            .sortedWith(compareByDescending<MacroCode> { it.level }.thenByDescending { it.weight })
            .take(maxMacros)
            .map { m ->
                SonificationMacro(
                    id = m.id,
                    hz = m.representativeHz,
                    weight = m.weight,
                    memberHz = leafHzOf(m.id).distinct().take(6),
                    abstractionLevel = m.level
                )
            }

        return SonificationSnapshot(micro, macro)
    }

    @Synchronized
    fun stats(): MemoryStats {
        val levelCounts = macros.values
            .groupingBy { it.level }
            .eachCount()
            .toSortedMap()

        return MemoryStats(
            persistentCodes = persistent.size,
            temporaryCodes = candidates.size,
            relations = relations.size,
            observations = observations,
            strongestWeight = persistent.values.maxOfOrNull { it.weight } ?: 0L,
            macroCodes = macros.size,
            macroRelations = macroRelations.size,
            macroFrozen = macroFrozen,
            maxAbstractionLevel = macros.values.maxOfOrNull { it.level } ?: 0,
            levelCounts = levelCounts,
            sequenceCodes = sequences.size,
            promotedSequences = sequences.values.count { it.promotedMacroId != null },
            strongestSequenceWeight = sequences.values
                .filter { it.length >= 2 }
                .maxOfOrNull { it.weight } ?: 0L,
            longestSequenceLength = sequences.values.maxOfOrNull { it.length } ?: 0
        )
    }

    @Synchronized
    fun reset() {
        persistent.clear()
        candidates.clear()
        relations.clear()
        macros.clear()
        macroRelations.clear()
        sequences.clear()
        sequenceIndex.clear()
        activeSequenceSuffixes.clear()
        previousId = null
        previousByLevel.clear()
        observations = 0L
        nextMacroId = MACRO_ID_BASE
        nextSequenceId = SEQUENCE_ID_BASE
        macroFrozen = false
        if (file.exists()) file.delete()
    }

    private fun pairKey(a: Int, b: Int): Long =
        (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)

    private fun keyFrom(key: Long): Int = (key shr 32).toInt()
    private fun keyTo(key: Long): Int = key.toInt()

    private fun sequenceKey(streamLevel: Int, parentSequenceId: Int, tokenId: Int): String =
        "$streamLevel|$parentSequenceId|$tokenId"

    private fun pruneRelations() {
        if (relations.size <= 4096) return
        val keep = relations.entries.sortedByDescending { it.value }.take(3072)
        relations.clear()
        keep.forEach { relations[it.key] = it.value }
    }

    private fun pruneMacroRelations() {
        if (macroRelations.size <= 4096) return
        val keep = macroRelations.entries.sortedByDescending { it.value }.take(3072)
        macroRelations.clear()
        keep.forEach { macroRelations[it.key] = it.value }
    }

    private fun save() {
        try {
            val root = JSONObject()
            root.put("formatVersion", 8)
            root.put("observations", observations)
            root.put("nextMacroId", nextMacroId)
            root.put("nextSequenceId", nextSequenceId)
            root.put("macroFrozen", macroFrozen)

            val codes = JSONArray()
            persistent.forEach { (id, c) ->
                codes.put(
                    JSONObject()
                        .put("id", id)
                        .put("weight", c.weight)
                        .put("hz", c.representativeHz)
                )
            }
            root.put("codes", codes)

            val edges = JSONArray()
            relations.forEach { (key, weight) ->
                edges.put(JSONObject().put("key", key).put("weight", weight))
            }
            root.put("relations", edges)

            val macroArray = JSONArray()
            macros.values.forEach { m ->
                val members = JSONArray()
                m.members.forEach { members.put(it) }
                macroArray.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("level", m.level)
                        .put("weight", m.weight)
                        .put("hz", m.representativeHz)
                        .put("members", members)
                        .put("kind", m.kind)
                        .put("sourceSequenceId", m.sourceSequenceId ?: JSONObject.NULL)
                )
            }
            root.put("macros", macroArray)

            val macroEdgeArray = JSONArray()
            macroRelations.forEach { (key, weight) ->
                macroEdgeArray.put(JSONObject().put("key", key).put("weight", weight))
            }
            root.put("macroRelations", macroEdgeArray)

            val sequenceArray = JSONArray()
            sequences.values.forEach { s ->
                sequenceArray.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("streamLevel", s.streamLevel)
                        .put("parentSequenceId", s.parentSequenceId)
                        .put("nextTokenId", s.nextTokenId)
                        .put("length", s.length)
                        .put("weight", s.weight)
                        .put("promotedMacroId", s.promotedMacroId ?: JSONObject.NULL)
                )
            }
            root.put("sequences", sequenceArray)

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
            nextSequenceId = root.optInt("nextSequenceId", SEQUENCE_ID_BASE)
            macroFrozen = root.optBoolean("macroFrozen", false)

            val codes = root.optJSONArray("codes") ?: JSONArray()
            for (i in 0 until codes.length()) {
                val o = codes.getJSONObject(i)
                persistent[o.getInt("id")] = Code(
                    o.getLong("weight"),
                    o.getDouble("hz")
                )
            }

            val edges = root.optJSONArray("relations") ?: JSONArray()
            for (i in 0 until edges.length()) {
                val o = edges.getJSONObject(i)
                relations[o.getLong("key")] = o.getLong("weight")
            }

            val macroArray = root.optJSONArray("macros") ?: JSONArray()
            for (i in 0 until macroArray.length()) {
                val o = macroArray.getJSONObject(i)
                val membersJson = o.optJSONArray("members") ?: JSONArray()
                val members = mutableSetOf<Int>()
                for (j in 0 until membersJson.length()) {
                    members += membersJson.getInt(j)
                }

                val id = o.getInt("id")
                val level = o.optInt("level", 1).coerceIn(1, MAX_ABSTRACTION_LEVEL)
                val sourceSequenceId = if (o.isNull("sourceSequenceId")) null else o.optInt("sourceSequenceId")

                macros[id] = MacroCode(
                    id = id,
                    level = level,
                    members = members,
                    weight = o.getLong("weight"),
                    representativeHz = o.getDouble("hz"),
                    kind = o.optString("kind", "relation"),
                    sourceSequenceId = sourceSequenceId
                )
                nextMacroId = max(nextMacroId, id + 1)
            }

            val macroEdgeArray = root.optJSONArray("macroRelations") ?: JSONArray()
            for (i in 0 until macroEdgeArray.length()) {
                val o = macroEdgeArray.getJSONObject(i)
                macroRelations[o.getLong("key")] = o.getLong("weight")
            }

            val sequenceArray = root.optJSONArray("sequences") ?: JSONArray()
            for (i in 0 until sequenceArray.length()) {
                val o = sequenceArray.getJSONObject(i)
                val promoted = if (o.isNull("promotedMacroId")) null else o.optInt("promotedMacroId")
                val node = SequenceNode(
                    id = o.getInt("id"),
                    streamLevel = o.optInt("streamLevel", 0),
                    parentSequenceId = o.optInt("parentSequenceId", 0),
                    nextTokenId = o.getInt("nextTokenId"),
                    length = o.optInt("length", 1),
                    weight = o.optLong("weight", 0L),
                    promotedMacroId = promoted
                )
                sequences[node.id] = node
                sequenceIndex[sequenceKey(node.streamLevel, node.parentSequenceId, node.nextTokenId)] = node.id
                nextSequenceId = max(nextSequenceId, node.id + 1)
            }
        } catch (_: Exception) {
        }
    }
}
