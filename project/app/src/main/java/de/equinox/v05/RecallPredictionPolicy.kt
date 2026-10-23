package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Step 40 keeps recall/prediction useful without allowing a flat relation neighborhood to
 * materialize an ever-growing frontier. All limits below are transient work budgets; they do
 * not delete persistent memory and they grow slowly with the amount of local evidence.
 */
object RecallPredictionPolicy {
    const val RECALL_SCORE_MASS_TARGET = 0.88
    const val RECALL_RELATIVE_FLOOR = 0.18
    const val MIN_RECALL_SCORE = 0.075

    const val PREDICTION_SCORE_MASS_TARGET = 0.90
    const val PREDICTION_RELATIVE_FLOOR = 0.16

    /** Local positive information gain: association share compared with local target prior. */
    fun informationGain(conditionalShare: Double, localPrior: Double): Double {
        val c = conditionalShare.coerceIn(1e-9, 1.0)
        val p = localPrior.coerceIn(1e-9, 1.0)
        return max(0.0, ln(c / p))
    }

    fun normalizedInformationGain(conditionalShare: Double, localPrior: Double): Double {
        val gain = informationGain(conditionalShare, localPrior)
        return gain / (gain + 2.0)
    }

    /**
     * Recall score combines conditional relation probability, support confidence, structural
     * relevance and information gain. Relevance cannot by itself rescue an unsupported edge.
     */
    fun recallScore(
        conditionalShare: Double,
        supportConfidence: Double,
        structuralRelevance: Double,
        localPrior: Double
    ): Double {
        val info = normalizedInformationGain(conditionalShare, localPrior)
        return (
            0.42 * conditionalShare.coerceIn(0.0, 1.0) +
                0.26 * supportConfidence.coerceIn(0.0, 1.0) +
                0.20 * structuralRelevance.coerceIn(0.0, 1.0) +
                0.12 * info
            ).coerceIn(0.0, 1.0)
    }

    /** Logarithmic local work budget: large neighborhoods grow sub-linearly, not unboundedly. */
    fun retrievalBudget(candidateCount: Int, currentWorkingSetSize: Int): Int {
        if (candidateCount <= 0) return 0
        val local = 2.0 + 2.15 * ln(1.0 + candidateCount.toDouble())
        val context = 0.35 * sqrt(currentWorkingSetSize.coerceAtLeast(0).toDouble())
        return max(2, (local + context).toInt()).coerceAtMost(candidateCount)
    }

    /** Prediction breadth grows logarithmically with outgoing alternatives. */
    fun predictionBudget(candidateCount: Int): Int {
        if (candidateCount <= 0) return 0
        return max(1, (1.0 + 2.0 * ln(1.0 + candidateCount.toDouble())).toInt())
            .coerceAtMost(candidateCount)
    }

    /**
     * Weak temporary pattern evidence expires in stream-event time. Stronger evidence gets
     * proportionally longer to recur; candidates with >3 hits are left to normal pressure/
     * consolidation because they are no longer weak noise.
     */
    fun weakCandidateIdleLimit(hits: Long, patternLength: Int, sourceLevel: Int): Long {
        val base = 36L + patternLength.coerceAtLeast(2) * 8L + sourceLevel.coerceAtLeast(0) * 12L
        return base * (hits.coerceIn(1L, 3L) + 1L)
    }
}
