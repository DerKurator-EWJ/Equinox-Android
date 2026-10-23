package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Step 25 removes semantic hard ceilings. Physical resources are still finite, so transient
 * work is governed by evidence and pressure rather than by fixed node-count limits.
 */
object AdaptiveScalePolicy {
    /**
     * Pattern horizon grows slowly with learned structure at this source depth.
     * There is no global maximum; growth is logarithmic to keep live work practical.
     */
    fun patternHorizon(macrosAtDepth: Int, observationsAtDepth: Long): Int {
        val structure = ln(1.0 + macrosAtDepth.coerceAtLeast(0).toDouble())
        val experience = ln(1.0 + observationsAtDepth.coerceAtLeast(0L).toDouble())
        return max(6, (6.0 + 1.8 * structure + 0.55 * experience).toInt())
    }

    /**
     * Temporary candidate evidence is allowed to grow with the amount of persistent structure.
     * This is a soft pressure target, not an identity ceiling: pruning removes weakest evidence
     * only when transient candidates greatly outgrow what the learned structure can justify.
     */
    fun candidateSoftTarget(persistentQuants: Int, persistentMacros: Int): Int {
        val base = persistentQuants.coerceAtLeast(1) + persistentMacros.coerceAtLeast(1)
        return max(512, (base * 1.75 + 16.0 * sqrt(base.toDouble())).toInt())
    }

    /** Number of consolidation promotions grows with available eligible evidence. */
    fun promotionBudget(eligibleCount: Int): Int {
        if (eligibleCount <= 0) return 0
        return max(1, sqrt(eligibleCount.toDouble()).toInt())
    }

    /** Keep enough ranked working-state mass to explain the current context. */
    const val WORKING_MASS_TARGET = 0.975

    /** Retrieve relation mass rather than a fixed neighbor count. */
    const val RETRIEVAL_MASS_TARGET = 0.94

    /** Keep prediction probability mass rather than Top-4. */
    const val PREDICTION_MASS_TARGET = 0.94

    /**
     * Familiar regions can be resolved more deeply. New/surprising regions receive exploratory
     * refinement as well, so unknown things do not remain permanently low-resolution.
     */
    fun visualRefinementPriority(
        familiarity: Double,
        noveltyRatio: Double,
        created: Boolean,
        depth: Int
    ): Double {
        val known = familiarity.coerceIn(0.0, 1.0)
        val surprise = noveltyRatio.coerceIn(0.0, 3.0) / 3.0
        val exploration = if (created) 0.72 else surprise * 0.58
        val depthCost = 1.0 / (1.0 + depth.coerceAtLeast(0) * 0.42)
        return (max(known, exploration) * depthCost).coerceIn(0.0, 1.0)
    }

    fun shouldRefineVisual(
        familiarity: Double,
        noveltyRatio: Double,
        created: Boolean,
        depth: Int,
        regionWidthPx: Int,
        regionHeightPx: Int
    ): Boolean {
        // Natural stop: the source image no longer contains enough pixels to split usefully.
        if (regionWidthPx < 4 || regionHeightPx < 4) return false
        val priority = visualRefinementPriority(familiarity, noveltyRatio, created, depth)
        val threshold = (0.46 + depth * 0.055).coerceAtMost(0.90)
        return priority >= threshold
    }
}
