package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

private const val MACRO_ID_BASE = 1_000_000

data class MemoryStats(
    val persistentCodes: Int,
    val temporaryCodes: Int,
    val relations: Int,
    val observations: Long,
    val strongestWeight: Long,
    val macroCodes: Int,
    val macroRelations: Int,
    val macroFrozen: Boolean
)

data class GraphNode(
    val id: Int,
    val weight: Long,
    val representativeHz: Double,
    val isMacro: Boolean = false,
    val memberCount: Int = 1
)

data class GraphEdge(
    val fromId: Int,
    val toId: Int,
    val weight: Long,
    val isMacro: Boolean = false
)

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
    val memberHz: List<Double>
)

data class SonificationSnapshot(
    val micro: List<SonificationNode>,
    val macros: List<SonificationMacro>
)

class MemoryModel(context: Context) {
    private data class Code(var weight: Long, val representativeHz: Double)
    private data class Candidate(var hits: Int, var hzSum: Double)
    private data class MacroCode(
        val id: Int,
        val members: MutableSet<Int>,
        var weight: Long,
        var representativeHz: Double
    )

    // Same persistent file as Step 03/04 on purpose so existing learned micro-memory survives the update.
    private val file = File(context.filesDir, "equinox_step03_memory.json")
    private val persistent = mutableMapOf<Int, Code>()
    private val candidates = mutableMapOf<Int, Candidate>()
    private val relations = mutableMapOf<Long, Long>()

    private val macros = mutableMapOf<Int, MacroCode>()
    private val macroRelations = mutableMapOf<Long, Long>()
    private var nextMacroId = MACRO_ID_BASE
    private var macroFrozen = false

    private var previousId: Int? = null
    private var previousMacroId: Int? = null
    private var observations = 0L

    init { load() }

    @Synchronized
    fun observe(id: Int, hz: Double) {
        observations++
        val known = persistent[id]
        if (known != null) {
            known.weight++
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

        // Existing macro nodes can react even while creation of NEW macros is frozen.
        val activeMacro = macros.values
            .asSequence()
            .filter { id in it.members }
            .maxByOrNull { it.weight }
        if (activeMacro != null) {
            activeMacro.weight++
            previousMacroId?.let { prev ->
                if (prev != activeMacro.id) {
                    val key = pairKey(prev, activeMacro.id)
                    macroRelations[key] = (macroRelations[key] ?: 0L) + 1L
                }
            }
            previousMacroId = activeMacro.id
        }
    }

    /**
     * Promotes repeated micro IDs and then discovers stable relation pairs as first-level macro nodes.
     * This is intentionally conservative: Step 05 proves the mechanism before deeper recursion is added.
     */
    @Synchronized
    fun consolidate(minHits: Int = 3, macroRelationThreshold: Long = 10L): ConsolidationResult {
        var promotedMicro = 0
        val it = candidates.iterator()
        while (it.hasNext()) {
            val (id, c) = it.next()
            if (c.hits >= minHits) {
                val existing = persistent[id]
                if (existing == null) {
                    persistent[id] = Code(c.hits.toLong(), c.hzSum / max(1, c.hits))
                } else {
                    existing.weight += c.hits
                }
                promotedMicro++
            }
            it.remove()
        }

        var promotedMacro = 0
        if (!macroFrozen) {
            val candidatesForMacro = relations.entries
                .asSequence()
                .map { (key, weight) -> Triple(keyFrom(key), keyTo(key), weight) }
                .filter { (a, b, w) ->
                    a != b && w >= macroRelationThreshold && persistent.containsKey(a) && persistent.containsKey(b)
                }
                .sortedByDescending { it.third }
                .take(64)
                .toList()

            for ((a, b, weight) in candidatesForMacro) {
                if (macroForPair(a, b) != null) continue
                val ca = persistent[a] ?: continue
                val cb = persistent[b] ?: continue
                val hz = weightedHz(ca, cb)
                val macro = MacroCode(
                    id = nextMacroId++,
                    members = mutableSetOf(a, b),
                    weight = weight,
                    representativeHz = hz
                )
                macros[macro.id] = macro
                promotedMacro++
            }
        }

        pruneRelations()
        pruneMacroRelations()
        save()
        return ConsolidationResult(promotedMicro, promotedMacro)
    }

    private fun weightedHz(a: Code, b: Code): Double {
        val total = max(1L, a.weight + b.weight).toDouble()
        return (a.representativeHz * a.weight + b.representativeHz * b.weight) / total
    }

    private fun macroForPair(a: Int, b: Int): MacroCode? = macros.values.firstOrNull {
        it.members.size == 2 && a in it.members && b in it.members
    }

    @Synchronized
    fun setMacroFrozen(frozen: Boolean) {
        macroFrozen = frozen
        save()
    }

    @Synchronized
    fun isMacroFrozen(): Boolean = macroFrozen

    @Synchronized
    fun graphSnapshot(maxMicroNodes: Int = 40, maxMacroNodes: Int = 18, maxEdges: Int = 180): GraphSnapshot {
        if (persistent.isEmpty() && macros.isEmpty()) return GraphSnapshot(emptyList(), emptyList())

        val strongestMicroIds = persistent.entries
            .sortedByDescending { it.value.weight }
            .take(maxMicroNodes)
            .map { it.key }
            .toSet()

        val strongestMacros = macros.values
            .sortedByDescending { it.weight }
            .take(maxMacroNodes)
        val macroIds = strongestMacros.map { it.id }.toSet()

        val nodes = mutableListOf<GraphNode>()
        strongestMicroIds.forEach { id ->
            persistent[id]?.let { nodes += GraphNode(id, it.weight, it.representativeHz, false, 1) }
        }
        strongestMacros.forEach { m ->
            nodes += GraphNode(m.id, m.weight, m.representativeHz, true, m.members.size)
        }

        val visible = strongestMicroIds + macroIds
        val edges = mutableListOf<GraphEdge>()

        relations.entries
            .asSequence()
            .map { (key, weight) -> GraphEdge(keyFrom(key), keyTo(key), weight, false) }
            .filter { it.fromId in strongestMicroIds && it.toId in strongestMicroIds }
            .sortedByDescending { it.weight }
            .take(maxEdges)
            .forEach { edges += it }

        // Membership edges make the abstraction boundary visible without making coordinates part of the core.
        strongestMacros.forEach { m ->
            m.members.filter { it in strongestMicroIds }.forEach { member ->
                edges += GraphEdge(member, m.id, max(2L, m.weight / 2L), true)
            }
        }

        macroRelations.entries
            .asSequence()
            .map { (key, weight) -> GraphEdge(keyFrom(key), keyTo(key), weight, true) }
            .filter { it.fromId in visible && it.toId in visible }
            .sortedByDescending { it.weight }
            .take(80)
            .forEach { edges += it }

        return GraphSnapshot(nodes.sortedWith(compareBy<GraphNode> { it.isMacro }.thenBy { it.id }), edges)
    }


    @Synchronized
    fun sonificationSnapshot(maxMicro: Int = 8, maxMacros: Int = 6): SonificationSnapshot {
        val micro = persistent.entries
            .sortedByDescending { it.value.weight }
            .take(maxMicro)
            .map { (id, code) -> SonificationNode(id, code.representativeHz, code.weight) }

        val macro = macros.values
            .sortedByDescending { it.weight }
            .take(maxMacros)
            .map { m ->
                SonificationMacro(
                    id = m.id,
                    hz = m.representativeHz,
                    weight = m.weight,
                    memberHz = m.members.mapNotNull { persistent[it]?.representativeHz }
                )
            }

        return SonificationSnapshot(micro, macro)
    }

    @Synchronized
    fun stats(): MemoryStats = MemoryStats(
        persistentCodes = persistent.size,
        temporaryCodes = candidates.size,
        relations = relations.size,
        observations = observations,
        strongestWeight = persistent.values.maxOfOrNull { it.weight } ?: 0L,
        macroCodes = macros.size,
        macroRelations = macroRelations.size,
        macroFrozen = macroFrozen
    )

    @Synchronized
    fun reset() {
        persistent.clear()
        candidates.clear()
        relations.clear()
        macros.clear()
        macroRelations.clear()
        previousId = null
        previousMacroId = null
        observations = 0L
        nextMacroId = MACRO_ID_BASE
        macroFrozen = false
        if (file.exists()) file.delete()
    }

    private fun pairKey(a: Int, b: Int): Long =
        (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)

    private fun keyFrom(key: Long): Int = (key shr 32).toInt()
    private fun keyTo(key: Long): Int = key.toInt()

    private fun pruneRelations() {
        if (relations.size <= 4096) return
        val keep = relations.entries.sortedByDescending { it.value }.take(3072)
        relations.clear()
        keep.forEach { relations[it.key] = it.value }
    }

    private fun pruneMacroRelations() {
        if (macroRelations.size <= 2048) return
        val keep = macroRelations.entries.sortedByDescending { it.value }.take(1536)
        macroRelations.clear()
        keep.forEach { macroRelations[it.key] = it.value }
    }

    private fun save() {
        try {
            val root = JSONObject()
            root.put("observations", observations)
            root.put("nextMacroId", nextMacroId)
            root.put("macroFrozen", macroFrozen)

            val codes = JSONArray()
            persistent.forEach { (id, c) ->
                codes.put(JSONObject().put("id", id).put("weight", c.weight).put("hz", c.representativeHz))
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
                        .put("weight", m.weight)
                        .put("hz", m.representativeHz)
                        .put("members", members)
                )
            }
            root.put("macros", macroArray)

            val macroEdgeArray = JSONArray()
            macroRelations.forEach { (key, weight) ->
                macroEdgeArray.put(JSONObject().put("key", key).put("weight", weight))
            }
            root.put("macroRelations", macroEdgeArray)

            file.writeText(root.toString())
        } catch (_: Exception) { }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            observations = root.optLong("observations", 0L)
            nextMacroId = root.optInt("nextMacroId", MACRO_ID_BASE)
            macroFrozen = root.optBoolean("macroFrozen", false)

            val codes = root.optJSONArray("codes") ?: JSONArray()
            for (i in 0 until codes.length()) {
                val o = codes.getJSONObject(i)
                persistent[o.getInt("id")] = Code(o.getLong("weight"), o.getDouble("hz"))
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
                for (j in 0 until membersJson.length()) members += membersJson.getInt(j)
                val id = o.getInt("id")
                macros[id] = MacroCode(id, members, o.getLong("weight"), o.getDouble("hz"))
                nextMacroId = max(nextMacroId, id + 1)
            }

            val macroEdgeArray = root.optJSONArray("macroRelations") ?: JSONArray()
            for (i in 0 until macroEdgeArray.length()) {
                val o = macroEdgeArray.getJSONObject(i)
                macroRelations[o.getLong("key")] = o.getLong("weight")
            }
        } catch (_: Exception) { }
    }
}

data class ConsolidationResult(val microPromoted: Int, val macroPromoted: Int)
