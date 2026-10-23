package de.equinox.v05

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Step 46 canonical interchange format.
 *
 * External systems are not allowed to write arbitrary model weights into Equinox memory.
 * They must first provide a grounded structural document. Level-1 nodes reference existing
 * Equinox sensor-token IDs; deeper nodes reference lower external nodes. Optional embeddings
 * can only propose sparse relations among already-grounded nodes.
 */
object ExternalKnowledgeFormat {
    const val FORMAT = "equinox-external-knowledge-v1"
    const val MAX_NODES = 200_000
    const val MAX_RELATIONS = 1_000_000
    const val MAX_TOKENS_PER_NODE = 256
    const val MAX_EMBEDDING_DIMS = 4096
}

data class ExternalKnowledgeNode(
    val externalId: String,
    val level: Int,
    val modality: String,
    val quantTokens: List<Int>,
    val members: List<String>,
    val weight: Long,
    val representativeHz: Double,
    val confidence: Double,
    val embedding: DoubleArray?
)

data class ExternalKnowledgeRelation(
    val from: String,
    val to: String,
    val strength: Double,
    val bidirectional: Boolean
)

data class ExternalKnowledgeDocument(
    val sourceId: String,
    val sourceVersion: String,
    val packId: String,
    val packVersion: Int,
    val nodes: List<ExternalKnowledgeNode>,
    val relations: List<ExternalKnowledgeRelation>,
    val deriveEmbeddingRelations: Boolean,
    val embeddingThreshold: Double,
    val embeddingNeighbors: Int
)

data class ImportIssue(val severity: Severity, val message: String) {
    enum class Severity { WARNING, ERROR }
}

data class ExternalImportReport(
    val valid: Boolean,
    val sourceId: String,
    val sourceVersion: String,
    val packId: String,
    val packVersion: Int,
    val nodesRead: Int,
    val macrosCompiled: Int,
    val explicitRelationsRead: Int,
    val embeddingRelationsDerived: Int,
    val finalRelations: Int,
    val issues: List<ImportIssue>,
    val compiledPack: CompiledBasePack? = null
) {
    val errors: List<ImportIssue> get() = issues.filter { it.severity == ImportIssue.Severity.ERROR }
    val warnings: List<ImportIssue> get() = issues.filter { it.severity == ImportIssue.Severity.WARNING }
}

data class CompiledBasePack(
    val packId: String,
    val version: Int,
    val sourceId: String,
    val sourceVersion: String,
    val macros: List<IndexedLongTermStore.StoredMacro>,
    val relations: List<IndexedLongTermStore.StoredRelation>
)

class ExternalKnowledgeConverter {
    fun parseAndCompile(input: InputStream): ExternalImportReport =
        runCatching { input.bufferedReader().use { parseAndCompile(it.readText()) } }
            .getOrElse { failure("Input konnte nicht gelesen werden: ${it.message ?: it::class.java.simpleName}") }

    fun parseAndCompile(jsonText: String): ExternalImportReport {
        val issues = mutableListOf<ImportIssue>()
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return failure("Ungültiges JSON: ${e.message ?: "Parserfehler"}")
        }

        if (root.optString("format") != ExternalKnowledgeFormat.FORMAT) {
            issues += error("format muss '${ExternalKnowledgeFormat.FORMAT}' sein")
        }
        val source = root.optJSONObject("source") ?: JSONObject()
        val base = root.optJSONObject("base") ?: JSONObject()
        val sourceId = source.optString("id").trim()
        val sourceVersion = source.optString("version").trim()
        val packId = base.optString("packId").trim()
        val packVersion = base.optInt("version", 0)
        if (sourceId.isBlank()) issues += error("source.id fehlt")
        if (packId.isBlank()) issues += error("base.packId fehlt")
        if (packVersion <= 0) issues += error("base.version muss > 0 sein")

        val options = root.optJSONObject("options") ?: JSONObject()
        val deriveEmbeddings = options.optBoolean("deriveEmbeddingRelations", false)
        val embeddingThreshold = options.optDouble("embeddingThreshold", 0.82).coerceIn(0.0, 0.999999)
        val embeddingNeighbors = options.optInt("embeddingNeighbors", 6).coerceIn(1, 24)

        val nodesArray = root.optJSONArray("nodes") ?: JSONArray()
        if (nodesArray.length() > ExternalKnowledgeFormat.MAX_NODES) {
            issues += error("Zu viele Nodes: ${nodesArray.length()} > ${ExternalKnowledgeFormat.MAX_NODES}")
        }
        val nodes = ArrayList<ExternalKnowledgeNode>(min(nodesArray.length(), ExternalKnowledgeFormat.MAX_NODES))
        val seenIds = HashSet<String>()
        for (i in 0 until min(nodesArray.length(), ExternalKnowledgeFormat.MAX_NODES)) {
            val obj = nodesArray.optJSONObject(i)
            if (obj == null) {
                issues += error("nodes[$i] ist kein Objekt")
                continue
            }
            val id = obj.optString("id").trim()
            val level = obj.optInt("level", 0)
            val modality = obj.optString("modality").trim()
            val weight = obj.optLong("weight", 1L).coerceAtLeast(1L)
            val hz = obj.optDouble("hz", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val confidence = obj.optDouble("confidence", 1.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0
            val quantTokens = intList(obj.optJSONArray("quantTokens"), ExternalKnowledgeFormat.MAX_TOKENS_PER_NODE)
            val members = stringList(obj.optJSONArray("members"), ExternalKnowledgeFormat.MAX_TOKENS_PER_NODE)
            val embeddingJson = obj.optJSONArray("embedding")
            if (embeddingJson != null && embeddingJson.length() > ExternalKnowledgeFormat.MAX_EMBEDDING_DIMS) {
                issues += error("Node '$id': embedding hat ${embeddingJson.length()} Dimensionen; Maximum ${ExternalKnowledgeFormat.MAX_EMBEDDING_DIMS}")
            }
            val embedding = doubleArray(embeddingJson, ExternalKnowledgeFormat.MAX_EMBEDDING_DIMS)

            if (id.isBlank()) issues += error("nodes[$i].id fehlt")
            else if (!seenIds.add(id)) issues += error("Doppelte node id '$id'")
            if (level <= 0) issues += error("Node '$id': level muss >= 1 sein")
            if (modality.isBlank()) issues += error("Node '$id': modality fehlt")
            if (quantTokens.size > ExternalKnowledgeFormat.MAX_TOKENS_PER_NODE || members.size > ExternalKnowledgeFormat.MAX_TOKENS_PER_NODE) {
                issues += error("Node '$id': zu viele Tokens/Members")
            }
            if (level == 1) {
                if (quantTokens.size < 2) issues += error("Node '$id': Level 1 benötigt mindestens 2 quantTokens")
                if (members.isNotEmpty()) issues += error("Node '$id': Level 1 darf keine members benutzen")
            } else {
                if (members.size < 2) issues += error("Node '$id': Level $level benötigt mindestens 2 members")
                if (quantTokens.isNotEmpty()) issues += error("Node '$id': Level >1 darf keine quantTokens benutzen")
            }
            if (embedding != null && embedding.all { abs(it) < 1e-15 }) {
                issues += warning("Node '$id': Null-Embedding wird für Relationsableitung ignoriert")
            }
            nodes += ExternalKnowledgeNode(id, level, modality, quantTokens, members, weight, hz, confidence, embedding)
        }

        val nodeById = nodes.associateBy { it.externalId }
        nodes.forEach { node ->
            if (node.level > 1) {
                node.members.forEach { memberId ->
                    val child = nodeById[memberId]
                    if (child == null) {
                        issues += error("Node '${node.externalId}': unbekanntes member '$memberId'")
                    } else if (node.modality != "crossmodal" && child.modality != node.modality) {
                        issues += error("Node '${node.externalId}': Modality-Mix ohne crossmodal ('$memberId')")
                    } else if (node.modality != "crossmodal" && child.level != node.level - 1) {
                        issues += error("Node '${node.externalId}': member '$memberId' muss Level ${node.level - 1} haben")
                    } else if (node.modality == "crossmodal" && child.level >= node.level) {
                        issues += error("Crossmodal-Node '${node.externalId}': member '$memberId' muss unterhalb von Level ${node.level} liegen")
                    }
                }
                if (node.modality == "crossmodal") {
                    val children = node.members.mapNotNull(nodeById::get)
                    val expectedLevel = (children.maxOfOrNull { it.level } ?: 0) + 1
                    if (children.isNotEmpty() && node.level != expectedLevel) {
                        issues += error("Crossmodal-Node '${node.externalId}': level muss max(member.level)+1 = $expectedLevel sein")
                    }
                    val sensorModalities = children.map { it.modality }.filter { it != "crossmodal" }.toSet()
                    if (sensorModalities.size < 2) {
                        issues += error("Crossmodal-Node '${node.externalId}': benötigt mindestens zwei unterschiedliche Sensor-Modalitäten")
                    }
                }
            }
        }

        val relationArray = root.optJSONArray("relations") ?: JSONArray()
        if (relationArray.length() > ExternalKnowledgeFormat.MAX_RELATIONS) {
            issues += error("Zu viele Relationen: ${relationArray.length()} > ${ExternalKnowledgeFormat.MAX_RELATIONS}")
        }
        val externalRelations = ArrayList<ExternalKnowledgeRelation>(min(relationArray.length(), ExternalKnowledgeFormat.MAX_RELATIONS))
        for (i in 0 until min(relationArray.length(), ExternalKnowledgeFormat.MAX_RELATIONS)) {
            val obj = relationArray.optJSONObject(i) ?: continue
            val from = obj.optString("from").trim()
            val to = obj.optString("to").trim()
            val strength = obj.optDouble("strength", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
            val bidirectional = obj.optBoolean("bidirectional", false)
            if (from !in nodeById || to !in nodeById) issues += error("Relation[$i] referenziert unbekannte Node")
            if (from == to) issues += warning("Relation[$i] Self-Link wird verworfen")
            if (strength <= 0.0) issues += warning("Relation[$i] strength<=0 wird verworfen")
            externalRelations += ExternalKnowledgeRelation(from, to, strength, bidirectional)
        }

        if (issues.any { it.severity == ImportIssue.Severity.ERROR }) {
            return report(sourceId, sourceVersion, packId, packVersion, nodes.size, 0, externalRelations.size, 0, 0, issues, null)
        }

        val stableIds = assignStableIds(packId, nodes, issues)
        if (issues.any { it.severity == ImportIssue.Severity.ERROR }) {
            return report(sourceId, sourceVersion, packId, packVersion, nodes.size, 0, externalRelations.size, 0, 0, issues, null)
        }

        val compiledMacros = nodes.sortedWith(compareBy<ExternalKnowledgeNode> { it.level }.thenBy { it.externalId }).map { node ->
            val tokens = if (node.level == 1) node.quantTokens else node.members.mapNotNull(stableIds::get)
            val id = stableIds.getValue(node.externalId)
            IndexedLongTermStore.StoredMacro(
                id = id,
                level = node.level,
                modality = node.modality,
                tokens = tokens,
                weight = max(1L, (node.weight.toDouble() * (0.25 + 0.75 * node.confidence)).toLong()),
                hz = node.representativeHz,
                firstObs = 0L,
                lastObs = 0L,
                signature = signature(node.level - 1, node.modality, tokens)
            )
        }

        val relationWeights = LinkedHashMap<Long, Long>()
        fun addRelation(from: String, to: String, strength: Double) {
            if (from == to || strength <= 0.0) return
            val a = stableIds[from] ?: return
            val b = stableIds[to] ?: return
            val w = strengthToWeight(strength)
            if (w <= 0L) return
            val key = pairKey(a, b)
            relationWeights[key] = max(relationWeights[key] ?: 0L, w)
        }
        externalRelations.forEach { relation ->
            addRelation(relation.from, relation.to, relation.strength)
            if (relation.bidirectional) addRelation(relation.to, relation.from, relation.strength)
        }

        var derivedCount = 0
        if (deriveEmbeddings) {
            val proposals = SparseEmbeddingRelationBuilder().derive(nodes, embeddingThreshold, embeddingNeighbors)
            proposals.forEach { p ->
                val before = relationWeights.size
                addRelation(p.from, p.to, p.similarity)
                if (relationWeights.size > before) derivedCount++
            }
            if (proposals.isEmpty()) issues += warning("Embedding-Relationsableitung aktiv, aber keine ausreichend ähnlichen grounded Nodes gefunden")
        }

        val compiled = CompiledBasePack(
            packId = packId,
            version = packVersion,
            sourceId = sourceId,
            sourceVersion = sourceVersion,
            macros = compiledMacros,
            relations = relationWeights.map { IndexedLongTermStore.StoredRelation(it.key, it.value) }
        )
        return report(
            sourceId, sourceVersion, packId, packVersion,
            nodes.size, compiledMacros.size, externalRelations.size, derivedCount,
            relationWeights.size, issues, compiled
        )
    }

    private fun assignStableIds(packId: String, nodes: List<ExternalKnowledgeNode>, issues: MutableList<ImportIssue>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>(nodes.size)
        val used = HashSet<Int>(nodes.size)
        val range = KnowledgeIdSpace.BASE_MACRO_MAX.toLong() - KnowledgeIdSpace.BASE_MACRO_MIN.toLong() + 1L
        nodes.sortedBy { it.externalId }.forEach { node ->
            val hash = fnv1a64("$packId|${node.externalId}") and Long.MAX_VALUE
            val candidate = KnowledgeIdSpace.BASE_MACRO_MIN.toLong() + (hash % range)
            val id = candidate.toInt()
            if (!used.add(id)) {
                issues += error("Stable-ID-Kollision bei '${node.externalId}'. External IDs/packId ändern oder expliziten Adapter verwenden.")
            } else out[node.externalId] = id
        }
        return out
    }

    private fun intList(array: JSONArray?, maxItems: Int): List<Int> {
        if (array == null) return emptyList()
        val n = min(array.length(), maxItems + 1)
        return List(n) { array.optInt(it, Int.MIN_VALUE) }.filter { it != Int.MIN_VALUE }
    }

    private fun stringList(array: JSONArray?, maxItems: Int): List<String> {
        if (array == null) return emptyList()
        val n = min(array.length(), maxItems + 1)
        return List(n) { array.optString(it).trim() }.filter { it.isNotBlank() }
    }

    private fun doubleArray(array: JSONArray?, maxDims: Int): DoubleArray? {
        if (array == null || array.length() == 0) return null
        if (array.length() > maxDims) return null
        return DoubleArray(array.length()) { i -> array.optDouble(i, 0.0).takeIf(Double::isFinite) ?: 0.0 }
    }

    private fun strengthToWeight(strength: Double): Long = max(1L, (strength.coerceIn(0.0, 1.0) * 1000.0).toLong())

    private fun signature(level: Int, modality: String, tokens: List<Int>): String = buildString {
        append(modality).append('|').append(level).append('|')
        tokens.forEachIndexed { index, token -> if (index > 0) append(','); append(token) }
    }

    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)

    private fun fnv1a64(text: String): Long {
        var hash = -3750763034362895579L // unsigned FNV offset represented as signed Long
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 1099511628211L
        }
        return hash
    }

    private fun failure(message: String): ExternalImportReport = report("", "", "", 0, 0, 0, 0, 0, 0, listOf(error(message)), null)
    private fun error(message: String) = ImportIssue(ImportIssue.Severity.ERROR, message)
    private fun warning(message: String) = ImportIssue(ImportIssue.Severity.WARNING, message)

    private fun report(
        sourceId: String,
        sourceVersion: String,
        packId: String,
        packVersion: Int,
        nodes: Int,
        macros: Int,
        explicitRelations: Int,
        embeddingRelations: Int,
        finalRelations: Int,
        issues: List<ImportIssue>,
        compiled: CompiledBasePack?
    ) = ExternalImportReport(
        valid = issues.none { it.severity == ImportIssue.Severity.ERROR } && compiled != null,
        sourceId = sourceId,
        sourceVersion = sourceVersion,
        packId = packId,
        packVersion = packVersion,
        nodesRead = nodes,
        macrosCompiled = macros,
        explicitRelationsRead = explicitRelations,
        embeddingRelationsDerived = embeddingRelations,
        finalRelations = finalRelations,
        issues = issues,
        compiledPack = compiled
    )
}

/**
 * Bounded embedding adapter. It deliberately avoids a global all-to-all similarity matrix.
 * Deterministic sign sketches create local buckets; only same/one-bit-neighbor buckets are
 * cosine-checked, then each node keeps a bounded number of outgoing proposals.
 */
class SparseEmbeddingRelationBuilder {
    data class Proposal(val from: String, val to: String, val similarity: Double)

    fun derive(nodes: List<ExternalKnowledgeNode>, threshold: Double, maxNeighbors: Int): List<Proposal> {
        val usable = nodes.filter { node ->
            val e = node.embedding
            e != null && e.isNotEmpty() && e.any { abs(it) > 1e-15 }
        }
        if (usable.size < 2) return emptyList()

        val buckets = HashMap<Int, MutableList<ExternalKnowledgeNode>>()
        usable.forEach { node -> buckets.getOrPut(sketch(node.embedding!!)) { ArrayList() }.add(node) }
        val out = ArrayList<Proposal>()
        usable.forEach { node ->
            val key = sketch(node.embedding!!)
            val candidateMap = LinkedHashMap<String, ExternalKnowledgeNode>()
            fun absorb(bucketKey: Int) {
                buckets[bucketKey].orEmpty().take(MAX_BUCKET_CANDIDATES).forEach { other ->
                    if (other.externalId != node.externalId && other.modality == node.modality) candidateMap.putIfAbsent(other.externalId, other)
                }
            }
            absorb(key)
            for (bit in 0 until SKETCH_BITS) absorb(key xor (1 shl bit))
            candidateMap.values.asSequence()
                .map { it to cosine(node.embedding, it.embedding) }
                .filter { (_, sim) -> sim >= threshold }
                .sortedByDescending { it.second }
                .take(maxNeighbors)
                .forEach { (other, sim) -> out += Proposal(node.externalId, other.externalId, sim) }
        }
        return out
    }

    private fun sketch(vector: DoubleArray): Int {
        var bits = 0
        for (bit in 0 until SKETCH_BITS) {
            var projection = 0.0
            vector.forEachIndexed { dim, value ->
                val h = mix32(dim * 0x45d9f3b + bit * 0x119de1f3)
                projection += if ((h and 1) == 0) value else -value
            }
            if (projection >= 0.0) bits = bits or (1 shl bit)
        }
        return bits
    }

    private fun cosine(a: DoubleArray?, b: DoubleArray?): Double {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa <= 1e-20 || bb <= 1e-20) return -1.0
        return dot / sqrt(aa * bb)
    }

    private fun mix32(x0: Int): Int {
        var x = x0
        x = x xor (x ushr 16)
        x *= 0x45d9f3b
        x = x xor (x ushr 16)
        x *= 0x45d9f3b
        x = x xor (x ushr 16)
        return x
    }

    companion object {
        private const val SKETCH_BITS = 12
        private const val MAX_BUCKET_CANDIDATES = 96
    }
}
