package de.equinox.v05

import kotlin.math.ln

/**
 * Step 25: tier and relevance are separate.
 *
 * The visible RPG tier is now *only* abstraction depth:
 * Q -> F -> E -> ... -> SSS -> Ω -> Ω+n.
 *
 * score remains a diagnostic maturity score, while relevance stays a bounded
 * multidimensional estimate. Neither value changes the tier label.
 */
data class StructuralRankProfile(
    val score: Double,
    val rankIndex: Int,
    val label: String,
    val recurrence: Double,
    val reuse: Double,
    val relationSupport: Double,
    val specificity: Double,
    val contextEntropy: Double = 0.0,
    val persistence: Double = 0.0,
    val generality: Double = 0.0,
    val relevance: Double = 0.0
)

object StructuralRankEngine {

    fun evaluate(
        occurrenceWeight: Long,
        abstractionLevel: Int,
        parentReuseCount: Int,
        relationWeights: List<Long>,
        firstActivatedObservation: Long = 0L,
        lastActivatedObservation: Long = 0L,
        currentObservation: Long = 0L
    ): StructuralRankProfile {
        val safeOccurrence = occurrenceWeight.coerceAtLeast(0L)
        val safeReuse = parentReuseCount.coerceAtLeast(0)
        val positiveRelations = relationWeights.filter { it > 0L }
        val relationMass = positiveRelations.sumOf { it.toDouble() }

        val relevance = StructuralRelevanceEngine.evaluate(
            occurrenceWeight = safeOccurrence,
            parentReuseCount = safeReuse,
            relationWeights = positiveRelations,
            firstActivatedObservation = firstActivatedObservation,
            lastActivatedObservation = lastActivatedObservation,
            currentObservation = currentObservation
        )

        // Maturity remains slowly unbounded for F -> ... -> Ω+n.
        // Raw repetition contributes, but structural reuse and abstraction carry more weight.
        val score =
            0.72 * ln(1.0 + safeOccurrence.toDouble()) +
            1.65 * ln(1.0 + safeReuse.toDouble()) +
            0.72 * ln(1.0 + relationMass) +
            1.35 * relevance.specificity +
            1.05 * relevance.persistence +
            1.10 * abstractionLevel.coerceAtLeast(0).toDouble()

        val rankIndex = AbstractionTier.indexForMacroDepth(abstractionLevel)
        return StructuralRankProfile(
            score = score,
            rankIndex = rankIndex,
            label = AbstractionTier.labelForMacroDepth(abstractionLevel),
            recurrence = relevance.frequency,
            reuse = relevance.reuse,
            relationSupport = relevance.relationSupport,
            specificity = relevance.specificity,
            contextEntropy = relevance.contextEntropy,
            persistence = relevance.persistence,
            generality = relevance.generality,
            relevance = relevance.relevance
        )
    }

    /** Compatibility helper for older UI call sites. */
    fun labelFor(rankIndex: Int): String = AbstractionTier.labelForIndex(rankIndex)

}
