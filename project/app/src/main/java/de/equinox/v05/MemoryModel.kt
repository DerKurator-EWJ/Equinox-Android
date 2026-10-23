package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class MemoryStats(
    val persistentCodes: Int,
    val temporaryCodes: Int,
    val relations: Int,
    val observations: Long,
    val strongestWeight: Long
)

data class GraphNode(
    val id: Int,
    val weight: Long,
    val representativeHz: Double
)

data class GraphEdge(
    val fromId: Int,
    val toId: Int,
    val weight: Long
)

data class GraphSnapshot(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)

class MemoryModel(context: Context) {
    private data class Code(var weight: Long, val representativeHz: Double)
    private data class Candidate(var hits: Int, var hzSum: Double)

    // Same file name as Step 03 on purpose: an update keeps the learned memory.
    private val file = File(context.filesDir, "equinox_step03_memory.json")
    private val persistent = mutableMapOf<Int, Code>()
    private val candidates = mutableMapOf<Int, Candidate>()
    private val relations = mutableMapOf<Long, Long>()
    private var previousId: Int? = null
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
    }

    @Synchronized
    fun consolidate(minHits: Int = 3): Int {
        var promoted = 0
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
                promoted++
            }
            it.remove()
        }
        pruneRelations()
        save()
        return promoted
    }

    @Synchronized
    fun graphSnapshot(maxNodes: Int = 48, maxEdges: Int = 160): GraphSnapshot {
        if (persistent.isEmpty()) return GraphSnapshot(emptyList(), emptyList())

        val strongestIds = persistent.entries
            .sortedByDescending { it.value.weight }
            .take(maxNodes)
            .map { it.key }
            .toSet()

        val nodes = strongestIds.mapNotNull { id ->
            persistent[id]?.let { GraphNode(id, it.weight, it.representativeHz) }
        }.sortedBy { it.id }

        val edges = relations.entries
            .asSequence()
            .map { (key, weight) -> GraphEdge(keyFrom(key), keyTo(key), weight) }
            .filter { it.fromId in strongestIds && it.toId in strongestIds }
            .sortedByDescending { it.weight }
            .take(maxEdges)
            .toList()

        return GraphSnapshot(nodes, edges)
    }

    @Synchronized
    fun stats(): MemoryStats = MemoryStats(
        persistentCodes = persistent.size,
        temporaryCodes = candidates.size,
        relations = relations.size,
        observations = observations,
        strongestWeight = persistent.values.maxOfOrNull { it.weight } ?: 0L
    )

    @Synchronized
    fun reset() {
        persistent.clear()
        candidates.clear()
        relations.clear()
        previousId = null
        observations = 0L
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

    private fun save() {
        try {
            val root = JSONObject()
            root.put("observations", observations)
            val codes = JSONArray()
            persistent.forEach { (id, c) ->
                codes.put(JSONObject().put("id", id).put("weight", c.weight).put("hz", c.representativeHz))
            }
            val edges = JSONArray()
            relations.forEach { (key, weight) ->
                edges.put(JSONObject().put("key", key).put("weight", weight))
            }
            root.put("codes", codes)
            root.put("relations", edges)
            file.writeText(root.toString())
        } catch (_: Exception) { }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            observations = root.optLong("observations", 0L)
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
        } catch (_: Exception) { }
    }
}
