package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max

/**
 * Read-only structural maturity profile.
 *
 * A rank is NOT a semantic label and does not change learning. It is derived from
 * already observed evidence so UI/audio can expose how mature a macro has become.
 * The four dimensions deliberately separate "often seen" from "structurally useful".
 */
data class StructuralRankProfile(
    val score: Double,
    val rankIndex: Int,
    val label: String,
    val recurrence: Double,
    val reuse: Double,
    val relationSupport: Double,
    val specificity: Double
)

object StructuralRankEngine {
    private const val OMEGA_SCORE = 18.0
    private const val OMEGA_STEP = 3.0

    fun evaluate(
        occurrenceWeight: Long,
        abstractionLevel: Int,
        parentReuseCount: Int,
        relationWeights: List<Long>
    ): StructuralRankProfile {
        val safeOccurrence = occurrenceWeight.coerceAtLeast(0L)
        val safeReuse = parentReuseCount.coerceAtLeast(0)
        val positiveRelations = relationWeights.filter { it > 0L }
        val relationMass = positiveRelations.sumOf { it.toDouble() }

        // Bounded 0..1 diagnostic dimensions. They are intentionally not interchangeable.
        val recurrence = safeOccurrence.toDouble() / (safeOccurrence.toDouble() + 12.0)
        val reuse = safeReuse.toDouble() / (safeReuse.toDouble() + 4.0)
        val relationSupport = relationMass / (relationMass + 24.0)
        val specificity = contextSpecificity(positiveRelations)

        // Unbounded-but-slow structural score. Log growth prevents a single raw counter from
        // racing through ranks while still allowing Omega+n to continue without a hard cap.
        val score =
            0.95 * ln(1.0 + safeOccurrence.toDouble()) +
            1.75 * ln(1.0 + safeReuse.toDouble()) +
            0.85 * ln(1.0 + relationMass) +
            1.40 * specificity +
            1.10 * abstractionLevel.coerceAtLeast(0).toDouble()

        val rankIndex = rankIndexFor(score)
        return StructuralRankProfile(
            score = score,
            rankIndex = rankIndex,
            label = labelFor(rankIndex),
            recurrence = recurrence.coerceIn(0.0, 1.0),
            reuse = reuse.coerceIn(0.0, 1.0),
            relationSupport = relationSupport.coerceIn(0.0, 1.0),
            specificity = specificity.coerceIn(0.0, 1.0)
        )
    }

    private fun contextSpecificity(weights: List<Long>): Double {
        if (weights.isEmpty()) return 0.0
        if (weights.size == 1) return 1.0

        val total = weights.sumOf { it.toDouble() }
        if (total <= 0.0) return 0.0

        var entropy = 0.0
        for (w in weights) {
            val p = w.toDouble() / total
            if (p > 0.0) entropy -= p * ln(p)
        }
        val maxEntropy = ln(max(2, weights.size).toDouble())
        if (maxEntropy <= 0.0) return 0.0
        return (1.0 - entropy / maxEntropy).coerceIn(0.0, 1.0)
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
