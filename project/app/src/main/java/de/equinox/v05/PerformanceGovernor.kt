package de.equinox.v05

import kotlin.math.max

/**
 * Step 34 — adaptive resource governor.
 *
 * The governor does not change memory semantics. It only budgets presentation and transient
 * analysis work. Persistent IDs, macros, relations, tiers and learned weights are untouched.
 *
 * Inputs are intentionally small and observable: recent render/analysis cost, frame-drop ratio
 * and Android thermal status. Hysteresis prevents rapid quality oscillation.
 */
class PerformanceGovernor {
    enum class Tier {
        ECO, BALANCED, HIGH, ULTRA
    }

    data class Profile(
        val tier: Tier,
        val fieldBudget: Int,
        val linkBudget: Int,
        val videoWorkScale: Double,
        val learningWorkScale: Double,
        val minVideoIntervalMs: Long,
        val label: String
    )

    data class Snapshot(
        val profile: Profile,
        val renderMs: Double,
        val analysisMs: Double,
        val dropRatio: Double,
        val thermalStatus: Int,
        val pressure: Double
    )

    private var currentTier = Tier.HIGH
    private var renderEwma = 10.0
    private var analysisEwma = 45.0
    private var dropEwma = 0.0
    private var lastChangeMs = 0L
    private var snapshot = Snapshot(profileFor(currentTier), renderEwma, analysisEwma, dropEwma, 0, 0.0)

    @Synchronized
    fun update(
        renderMs: Double,
        analysisMs: Double,
        dropRatio: Double,
        thermalStatus: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Snapshot {
        if (renderMs.isFinite() && renderMs >= 0.0) renderEwma = ewma(renderEwma, renderMs.coerceAtMost(200.0), 0.16)
        if (analysisMs.isFinite() && analysisMs >= 0.0) analysisEwma = ewma(analysisEwma, analysisMs.coerceAtMost(500.0), 0.10)
        if (dropRatio.isFinite() && dropRatio >= 0.0) dropEwma = ewma(dropEwma, dropRatio.coerceIn(0.0, 1.0), 0.18)

        // Render pressure is dominant because it is user-visible. Analysis runs less frequently,
        // so its target is deliberately much looser. Thermal status >= 4 is severe on Android.
        val renderPressure = renderEwma / 16.7
        val analysisPressure = analysisEwma / 85.0
        val dropPressure = dropEwma * 2.5
        val thermalPressure = when {
            thermalStatus >= 5 -> 2.2
            thermalStatus >= 4 -> 1.65
            thermalStatus >= 3 -> 1.25
            thermalStatus >= 2 -> 0.85
            else -> 0.0
        }
        val pressure = max(max(renderPressure, analysisPressure), max(dropPressure, thermalPressure))

        val desired = when {
            pressure >= 1.75 -> Tier.ECO
            pressure >= 1.20 -> Tier.BALANCED
            pressure <= 0.58 && dropEwma < 0.03 && thermalStatus < 2 -> Tier.ULTRA
            pressure <= 0.92 && dropEwma < 0.08 && thermalStatus < 3 -> Tier.HIGH
            else -> currentTier
        }

        // Downgrade quickly under stress; upgrade slowly after a stable period.
        if (desired != currentTier) {
            val downgrade = desired.ordinal < currentTier.ordinal
            val holdMs = if (downgrade) 900L else 4_000L
            if (nowMs - lastChangeMs >= holdMs) {
                currentTier = desired
                lastChangeMs = nowMs
            }
        }

        snapshot = Snapshot(
            profile = profileFor(currentTier),
            renderMs = renderEwma,
            analysisMs = analysisEwma,
            dropRatio = dropEwma,
            thermalStatus = thermalStatus,
            pressure = pressure
        )
        return snapshot
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshot

    private fun profileFor(tier: Tier): Profile = when (tier) {
        Tier.ECO -> Profile(tier, fieldBudget = 3, linkBudget = 2, videoWorkScale = 0.55, learningWorkScale = 0.58, minVideoIntervalMs = 66L, label = "ECO")
        Tier.BALANCED -> Profile(tier, fieldBudget = 5, linkBudget = 3, videoWorkScale = 0.78, learningWorkScale = 0.78, minVideoIntervalMs = 50L, label = "BALANCED")
        Tier.HIGH -> Profile(tier, fieldBudget = 7, linkBudget = 5, videoWorkScale = 1.0, learningWorkScale = 1.0, minVideoIntervalMs = 33L, label = "HIGH")
        Tier.ULTRA -> Profile(tier, fieldBudget = 7, linkBudget = 5, videoWorkScale = 1.28, learningWorkScale = 1.22, minVideoIntervalMs = 33L, label = "ULTRA")
    }

    private fun ewma(old: Double, sample: Double, alpha: Double): Double = old + alpha * (sample - old)
}
