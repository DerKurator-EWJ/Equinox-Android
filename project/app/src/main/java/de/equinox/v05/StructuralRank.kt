package de.equinox.v05

import kotlin.math.ln

/**
 * Structural maturity rank.
 *
 * Rank and relevance are separate:
 * - rank expresses accumulated maturity / abstraction,
 * - relevance is a bounded multidimensional read-only estimate.
 *
 * Neither directly changes learning.
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
    private const val OMEGA_SCORE = 18.0
    private const val OMEGA_STEP = 3.0

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

        val rankIndex = rankIndexFor(score)
        return StructuralRankProfile(
            score = score,
            rankIndex = rankIndex,
            label = labelFor(rankIndex),
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

    private fun rankIndexFor(score: Double): Int {
        if (!score.isFinite() || score <= 0.0) return 0
        if (score < OMEGA_SCORE) return (score / 2.0).toInt().coerceIn(0, 8)
        return 9 + ((score - OMEGA_SCORE) / OMEGA_STEP).toInt().coerceAtLeast(0)
    }

    fun labelFor(rankIndex: Int): String = when (rankIndex) {
        0 -> "F"
        1 -> "E"
        2 -> "D"
        3 -> "C"
        4 -> "B"
        5 -> "A"
        6 -> "S"
        7 -> "SS"
        8 -> "SSS"
        9 -> "Ω"
        else -> "Ω+${rankIndex - 9}"
    }
}
