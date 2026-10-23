package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max

/**
 * v0.6 Step 13 — adaptive hierarchy / compression policy.
 *
 * The policy does not assign meaning. It asks a narrower engineering question:
 * is a recurring token pattern common enough that representing each occurrence by
 * one macro token would reduce the stream after paying a small dictionary cost?
 *
 * rawCost        = hits * patternLength
 * compressedCost = hits + dictionaryCost
 * gainRatio      = (rawCost - compressedCost) / rawCost
 *
 * Higher abstraction levels require slightly more evidence and a slightly larger
 * gain so accidental high-level combinations do not explode the hierarchy.
 */
data class AdaptivePromotionScore(
    val eligible: Boolean,
    val requiredHits: Long,
    val gainRatio: Double,
    val priority: Double,
    val rawCost: Double,
    val compressedCost: Double
)

object AdaptiveHierarchyPolicy {
    fun evaluate(
        patternLength: Int,
        hits: Long,
        sourceLevel: Int,
        minimumHits: Long = 0L
    ): AdaptivePromotionScore {
        if (patternLength < 2 || hits <= 0L) {
            return AdaptivePromotionScore(false, Long.MAX_VALUE, 0.0, 0.0, 0.0, 0.0)
        }

        val requiredHits = max(minimumHits, 4L + sourceLevel.coerceAtLeast(0) * 2L)
        val dictionaryCost = patternLength.toDouble() + 2.0 + sourceLevel.coerceAtLeast(0) * 0.5
        val rawCost = hits.toDouble() * patternLength.toDouble()
        val compressedCost = hits.toDouble() + dictionaryCost
        val gainRatio = if (rawCost <= 0.0) 0.0 else ((rawCost - compressedCost) / rawCost).coerceIn(-1.0, 1.0)
        val minimumGain = (0.20 + sourceLevel.coerceAtLeast(0) * 0.03).coerceAtMost(0.38)
        val eligible = hits >= requiredHits && gainRatio >= minimumGain

        // Long, frequently recurring patterns with genuine compression gain are preferred.
        val priority = if (eligible) {
            gainRatio * (patternLength - 1).toDouble() * ln(1.0 + hits.toDouble())
        } else 0.0

        return AdaptivePromotionScore(
            eligible = eligible,
            requiredHits = requiredHits,
            gainRatio = gainRatio,
            priority = priority,
            rawCost = rawCost,
            compressedCost = compressedCost
        )
    }
}
