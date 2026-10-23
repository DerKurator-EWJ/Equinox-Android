package de.equinox.v05

/**
 * Step 39 — sparse hierarchical routing for already learned macro compositions.
 *
 * The index is deliberately not a second memory graph. It is an address index over
 * persistent macro signatures. For each (modality, source-level) stream it keeps only
 * the final two tokens of a learned composition as an anchor, then groups exact stored
 * signatures by their span. A live tail therefore probes only spans that could actually
 * lead to a learned macro instead of testing every possible tail length.
 *
 * Unknown/novel tails remain outside this class and are still handled by MemoryModel's
 * ordinary candidate learning. Tier/depth is routing structure only, never importance.
 */
class HierarchicalRouteIndex {
    data class RouteMatch(val macroId: Int, val span: Int)

    data class Probe(
        val match: RouteMatch?,
        /** Exact signatures already materialized while probing, keyed by tail span. */
        val checkedSignatures: Map<Int, String>,
        /** Number of learned spans reachable from the current two-token anchor. */
        val routedSpanCandidates: Int
    )

    data class Stats(
        val streams: Int,
        val anchors: Int,
        val signatures: Int
    )

    private data class AnchorBucket(
        val bySpan: MutableMap<Int, MutableMap<String, Int>> = linkedMapOf()
    )

    private val routes = linkedMapOf<String, MutableMap<Long, AnchorBucket>>()

    fun clear() = routes.clear()

    fun registerTokens(
        sourceLevel: Int,
        modality: String,
        tokens: List<Int>,
        macroId: Int
    ) {
        if (tokens.size < 2) return
        register(
            sourceLevel = sourceLevel,
            modality = modality,
            signature = signature(sourceLevel, modality, tokens),
            macroId = macroId,
            tokenCount = tokens.size,
            penultimate = tokens[tokens.lastIndex - 1],
            last = tokens[tokens.lastIndex]
        )
    }

    /** Register a persisted macro without touching its disk-backed token payload. */
    fun registerPersisted(
        sourceLevel: Int,
        modality: String,
        signature: String,
        macroId: Int,
        tokenCount: Int
    ) {
        if (tokenCount < 2) return
        val tail = parseLastTwoTokens(signature) ?: return
        register(sourceLevel, modality, signature, macroId, tokenCount, tail.first, tail.second)
    }

    private fun register(
        sourceLevel: Int,
        modality: String,
        signature: String,
        macroId: Int,
        tokenCount: Int,
        penultimate: Int,
        last: Int
    ) {
        val stream = routes.getOrPut(streamKey(sourceLevel, modality)) { linkedMapOf() }
        val bucket = stream.getOrPut(pairKey(penultimate, last)) { AnchorBucket() }
        bucket.bySpan.getOrPut(tokenCount) { linkedMapOf() }[signature] = macroId
    }

    /**
     * Find the longest non-overlapping learned tail addressable by the current stream.
     * Only signatures for spans present under the current two-token anchor are built.
     */
    fun probe(
        sourceLevel: Int,
        modality: String,
        current: List<Int>,
        maxAllowedSpan: Int
    ): Probe {
        if (current.size < 2 || maxAllowedSpan < 2) return Probe(null, emptyMap(), 0)
        val stream = routes[streamKey(sourceLevel, modality)] ?: return Probe(null, emptyMap(), 0)
        val anchor = pairKey(current[current.lastIndex - 1], current[current.lastIndex])
        val bucket = stream[anchor] ?: return Probe(null, emptyMap(), 0)

        val eligibleSpans = bucket.bySpan.keys
            .asSequence()
            .filter { it in 2..current.size && it <= maxAllowedSpan }
            .sortedDescending()
            .toList()
        if (eligibleSpans.isEmpty()) return Probe(null, emptyMap(), 0)

        val checked = linkedMapOf<Int, String>()
        for (span in eligibleSpans) {
            val sig = signatureForTail(sourceLevel, modality, current, span)
            checked[span] = sig
            val macroId = bucket.bySpan[span]?.get(sig)
            if (macroId != null) {
                return Probe(RouteMatch(macroId, span), checked, eligibleSpans.size)
            }
        }
        return Probe(null, checked, eligibleSpans.size)
    }

    fun stats(): Stats {
        var anchors = 0
        var signatures = 0
        routes.values.forEach { stream ->
            anchors += stream.size
            stream.values.forEach { bucket ->
                signatures += bucket.bySpan.values.sumOf { it.size }
            }
        }
        return Stats(routes.size, anchors, signatures)
    }

    private fun signatureForTail(level: Int, modality: String, tokens: List<Int>, span: Int): String =
        buildString {
            append(modality).append('|').append(level).append('|')
            val start = tokens.size - span
            for (i in start until tokens.size) {
                if (i > start) append(',')
                append(tokens[i])
            }
        }

    private fun signature(level: Int, modality: String, tokens: List<Int>): String =
        buildString {
            append(modality).append('|').append(level).append('|')
            tokens.forEachIndexed { index, token ->
                if (index > 0) append(',')
                append(token)
            }
        }

    private fun parseLastTwoTokens(signature: String): Pair<Int, Int>? {
        val tokenPart = signature.substringAfterLast('|', missingDelimiterValue = "")
        if (tokenPart.isEmpty()) return null
        val lastComma = tokenPart.lastIndexOf(',')
        if (lastComma <= 0 || lastComma >= tokenPart.lastIndex) return null
        val previousComma = tokenPart.lastIndexOf(',', lastComma - 1)
        val penultimateStart = previousComma + 1
        val penultimate = tokenPart.substring(penultimateStart, lastComma).toIntOrNull() ?: return null
        val last = tokenPart.substring(lastComma + 1).toIntOrNull() ?: return null
        return penultimate to last
    }

    private fun streamKey(level: Int, modality: String): String = "$modality|$level"

    private fun pairKey(a: Int, b: Int): Long =
        (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
}
