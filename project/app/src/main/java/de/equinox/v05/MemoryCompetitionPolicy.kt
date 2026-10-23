package de.equinox.v05

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v0.6 Step 25 — adaptive, dependency-safe memory competition.
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
    const val MIN_IDLE_OBSERVATIONS = 384L

    /**
     * No fixed macros-per-stream ceiling. A stream earns more persistent structure as
     * evidence accumulates; deeper tiers also receive a larger structural allowance.
     */
    fun targetMacrosForStream(totalObservations: Long, abstractionLevel: Int): Int {
        val experience = sqrt(totalObservations.coerceAtLeast(1L).toDouble())
        val depthFactor = 1.0 + ln(1.0 + abstractionLevel.coerceAtLeast(1).toDouble()) * 0.45
        return max(48, (experience * 2.4 * depthFactor).toInt())
    }

    fun pressureMacrosForStream(totalObservations: Long, abstractionLevel: Int): Int =
        (targetMacrosForStream(totalObservations, abstractionLevel) * 1.35).toInt()

    fun forcedPruneBudget(candidateCount: Int): Int =
        if (candidateCount <= 0) 0 else max(1, sqrt(candidateCount.toDouble()).toInt())

    fun evaluate(
        weight: Long,
        parentReuseCount: Int,
        relationMass: Long,
        relationDegree: Int,
        templateRoleCount: Int,
        ageObservations: Long,
        active: Boolean,
        previousContext: Boolean,
        profile: StructuralProfile
    ): MemoryCompetitionScore {
        val age = ageObservations.coerceAtLeast(0L)
        val recency = 1.0 / (1.0 + age.toDouble() / 768.0)
        val frequency = weight.coerceAtLeast(0L).toDouble() / (weight.coerceAtLeast(0L).toDouble() + 16.0)
        val relationSupport = relationMass.coerceAtLeast(0L).toDouble() / (relationMass.coerceAtLeast(0L).toDouble() + 32.0)
        val relationBreadth = relationDegree.coerceAtLeast(0).toDouble() / (relationDegree.coerceAtLeast(0).toDouble() + 6.0)
        val templateSupport = templateRoleCount.coerceAtLeast(0).toDouble() / (templateRoleCount.coerceAtLeast(0).toDouble() + 4.0)
        val maturity = (ln(1.0 + profile.score.coerceAtLeast(0.0)) / ln(1.0 + 20.0)).coerceIn(0.0, 1.0)

        // Abstraction tier is NOT an importance signal. A deep node does not survive
        // merely because it is deep; survival comes from reuse, relevance, persistence,
        // relations and current context.
        val utility = (
            0.14 * frequency +
            0.28 * profile.relevance +
            0.14 * relationSupport +
            0.06 * relationBreadth +
            0.14 * templateSupport +
            0.12 * recency +
            0.12 * maturity
        ).coerceIn(0.0, 1.0)

        val protected = active ||
            previousContext ||
            parentReuseCount > 0 ||
            templateRoleCount >= 5 ||
            (profile.relevance >= 0.88 && profile.persistence >= 0.72)

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
