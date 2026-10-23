package de.equinox.v05

import kotlin.math.ln

/**
 * v0.6 Step 14 — bounded, dependency-safe memory competition.
 *
 * The policy does not decide semantic value. It estimates whether a LEAF macro still earns
 * its storage cost from recurrence, learned structural relevance, relations, template reuse,
 * abstraction and recency. Structural dependencies always win: a macro that is a component
 * of a higher macro is protected and cannot be deleted by competition.
 */
data class MemoryCompetitionScore(
    val utility: Double,
    val protected: Boolean,
    val stale: Boolean,
    val recency: Double,
    val relationSupport: Double,
    val templateSupport: Double
)

data class MemoryCompetitionResult(
    val runs: Long,
    val macrosExamined: Int,
    val macrosPruned: Int,
    val relationsPruned: Int,
    val candidatesPruned: Int,
    val protectedMacros: Int,
    val pressuredGroups: Int,
    val remainingMacros: Int
)

object MemoryCompetitionPolicy {
    const val TARGET_MACROS_PER_STREAM = 320
    const val HARD_MACROS_PER_STREAM = 384
    const val MAX_PRUNE_PER_RUN = 128
    const val MIN_IDLE_OBSERVATIONS = 384L

    fun evaluate(
        weight: Long,
        abstractionLevel: Int,
        parentReuseCount: Int,
        relationMass: Long,
        relationDegree: Int,
        templateRoleCount: Int,
        ageObservations: Long,
        active: Boolean,
        previousContext: Boolean,
        profile: StructuralRankProfile
    ): MemoryCompetitionScore {
        val age = ageObservations.coerceAtLeast(0L)
        val recency = 1.0 / (1.0 + age.toDouble() / 768.0)
        val frequency = weight.coerceAtLeast(0L).toDouble() / (weight.coerceAtLeast(0L).toDouble() + 16.0)
        val relationSupport = relationMass.coerceAtLeast(0L).toDouble() / (relationMass.coerceAtLeast(0L).toDouble() + 32.0)
        val relationBreadth = relationDegree.coerceAtLeast(0).toDouble() / (relationDegree.coerceAtLeast(0).toDouble() + 6.0)
        val templateSupport = templateRoleCount.coerceAtLeast(0).toDouble() / (templateRoleCount.coerceAtLeast(0).toDouble() + 4.0)
        val levelSupport = (abstractionLevel.coerceAtLeast(0).toDouble() / 4.0).coerceIn(0.0, 1.0)
        val maturity = (ln(1.0 + profile.score.coerceAtLeast(0.0)) / ln(1.0 + 20.0)).coerceIn(0.0, 1.0)

        val utility = (
            0.13 * frequency +
            0.26 * profile.relevance +
            0.14 * relationSupport +
            0.06 * relationBreadth +
            0.13 * templateSupport +
            0.12 * recency +
            0.08 * levelSupport +
            0.08 * maturity
        ).coerceIn(0.0, 1.0)

        val protected = active ||
            previousContext ||
            parentReuseCount > 0 ||
            profile.rankIndex >= 6 || // S and above survive automatic competition.
            templateRoleCount >= 5

        return MemoryCompetitionScore(
            utility = utility,
            protected = protected,
            stale = age >= MIN_IDLE_OBSERVATIONS,
            recency = recency,
            relationSupport = relationSupport,
            templateSupport = templateSupport
        )
    }
}
