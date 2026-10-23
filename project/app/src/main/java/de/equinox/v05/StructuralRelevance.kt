package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Step 09: read-only multidimensional structural relevance.
 *
 * This is deliberately NOT semantic "meaning". It separates several kinds of evidence
 * so a macro that merely occurs often is not automatically treated as important.
 *
 * All dimensions are normalized to 0..1.
 */
data class StructuralRelevanceProfile(
    val relevance: Double,
    val frequency: Double,
    val reuse: Double,
    val relationSupport: Double,
    val contextEntropy: Double,
    val specificity: Double,
    val persistence: Double,
    val generality: Double
)

object StructuralRelevanceEngine {
    fun evaluate(
        occurrenceWeight: Long,
        parentReuseCount: Int,
        relationWeights: List<Long>,
        firstActivatedObservation: Long,
        lastActivatedObservation: Long,
        currentObservation: Long
    ): StructuralRelevanceProfile {
        val occurrence = occurrenceWeight.coerceAtLeast(0L).toDouble()
        val reuseCount = parentReuseCount.coerceAtLeast(0).toDouble()
        val positiveRelations = relationWeights.filter { it > 0L }
        val relationMass = positiveRelations.sumOf { it.toDouble() }

        val frequency = occurrence / (occurrence + 12.0)
        val reuse = reuseCount / (reuseCount + 4.0)
        val relationSupport = relationMass / (relationMass + 24.0)

        val entropy = normalizedEntropy(positiveRelations)
        val breadth = positiveRelations.size.toDouble() / (positiveRelations.size.toDouble() + 6.0)

        // A token with no learned context is "unknown", not automatically maximally specific.
        val specificity = ((1.0 - entropy) * relationSupport).coerceIn(0.0, 1.0)
        val generality = (entropy * breadth * relationSupport).coerceIn(0.0, 1.0)

        val persistence = temporalPersistence(
            occurrence = occurrence,
            first = firstActivatedObservation,
            last = lastActivatedObservation,
            now = currentObservation
        )

        // Frequency intentionally contributes only 15%. Reuse, relation support,
        // specificity and persistence dominate the read-only relevance estimate.
        val base =
            0.15 * frequency +
            0.24 * reuse +
            0.20 * relationSupport +
            0.21 * specificity +
            0.20 * persistence

        // A mild selectivity gate prevents ubiquitous high-frequency patterns from
        // becoming maximally relevant solely because they are everywhere.
        val selectivityGate = 0.72 + 0.28 * specificity
        val relevance = (base * selectivityGate).coerceIn(0.0, 1.0)

        return StructuralRelevanceProfile(
            relevance = relevance,
            frequency = frequency.coerceIn(0.0, 1.0),
            reuse = reuse.coerceIn(0.0, 1.0),
            relationSupport = relationSupport.coerceIn(0.0, 1.0),
            contextEntropy = entropy.coerceIn(0.0, 1.0),
            specificity = specificity,
            persistence = persistence,
            generality = generality
        )
    }

    private fun normalizedEntropy(weights: List<Long>): Double {
        if (weights.size <= 1) return 0.0
        val total = weights.sumOf { it.toDouble() }
        if (total <= 0.0) return 0.0

        var entropy = 0.0
        for (w in weights) {
            val p = w.toDouble() / total
            if (p > 0.0) entropy -= p * ln(p)
        }
        val maxEntropy = ln(max(2, weights.size).toDouble())
        return if (maxEntropy <= 0.0) 0.0 else (entropy / maxEntropy).coerceIn(0.0, 1.0)
    }

    private fun temporalPersistence(
        occurrence: Double,
        first: Long,
        last: Long,
        now: Long
    ): Double {
        if (occurrence <= 0.0) return 0.0
        val safeFirst = first.coerceAtLeast(0L)
        val safeLast = last.coerceAtLeast(safeFirst)
        val safeNow = now.coerceAtLeast(safeLast)

        val span = (safeLast - safeFirst).toDouble()
        val availableSpan = (safeNow - safeFirst).coerceAtLeast(1L).toDouble()
        val coverage = (span / availableSpan).coerceIn(0.0, 1.0)
        val evidence = occurrence / (occurrence + 8.0)
        return sqrt(coverage * evidence).coerceIn(0.0, 1.0)
    }
}
