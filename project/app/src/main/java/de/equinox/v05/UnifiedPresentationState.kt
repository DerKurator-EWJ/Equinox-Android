package de.equinox.v05

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Step 44: one normalized, read-only presentation vector shared by HYPNO and Unified Audio.
 *
 * This is an OUTPUT projection only. It is derived from WorkingSetSnapshot, is never persisted,
 * and never writes back into MemoryModel. Visual and audio consumers are therefore guaranteed to
 * interpret the same core quantities with the same normalization rather than maintaining parallel
 * approximations of "activity", "prediction", "novelty", etc.
 */
data class UnifiedPresentationState(
    val activity: Double,
    val expectation: Double,
    val relevance: Double,
    val relationEnergy: Double,
    val signedExchange: Double,
    val prediction: Double,
    val novelty: Double,
    val crossmodal: Double,
    val tierDepth: Double,
    val retrievalShare: Double,
    val directActivation: Double,
    val density: Double,
    val memoryEnergy: Double
) {
    companion object {
        val EMPTY = UnifiedPresentationState(
            activity = 0.0,
            expectation = 0.0,
            relevance = 0.0,
            relationEnergy = 0.0,
            signedExchange = 0.0,
            prediction = 0.0,
            novelty = 0.0,
            crossmodal = 0.0,
            tierDepth = 0.0,
            retrievalShare = 0.0,
            directActivation = 0.0,
            density = 0.0,
            memoryEnergy = 0.0
        )

        fun from(working: WorkingSetSnapshot?): UnifiedPresentationState {
            if (working == null) return EMPTY
            val nodes = working.nodes
            val exchanges = working.exchanges
            val predictions = working.predictions

            val activity = if (nodes.isEmpty()) 0.0 else
                nodes.sumOf { it.activation.coerceIn(0.0, 1.0) } / nodes.size
            val expectation = if (nodes.isEmpty()) 0.0 else
                nodes.sumOf { it.expectation.coerceIn(0.0, 1.0) } / nodes.size
            val relevance = if (nodes.isEmpty()) 0.0 else
                nodes.sumOf { it.structuralRelevance.coerceIn(0.0, 1.0) } / nodes.size
            val relationEnergy = if (exchanges.isEmpty()) 0.0 else
                (exchanges.sumOf { abs(it.value).coerceIn(0.0, 2.0) } / exchanges.size).coerceIn(0.0, 1.0)
            val signedExchange = if (exchanges.isEmpty()) 0.0 else
                (exchanges.sumOf { it.value.coerceIn(-2.0, 2.0) } / exchanges.size).coerceIn(-1.0, 1.0)
            val prediction = if (predictions.isEmpty()) 0.0 else
                predictions.sumOf { it.confidence.coerceIn(0.0, 1.0) } / predictions.size
            val directNodes = nodes.filter { !it.retrieved }
            val directActivation = if (directNodes.isEmpty()) 0.0 else
                directNodes.sumOf { it.activation.coerceIn(0.0, 1.0) } / directNodes.size

            // Presentation-only novelty proxy. It is deliberately not a learned novelty variable.
            val novelty = (
                directActivation * (1.0 - (0.68 * expectation + 0.32 * prediction))
                ).coerceIn(0.0, 1.0)

            val crossmodal = if (working.crossModalLinks.isEmpty()) 0.0 else
                working.crossModalLinks.sumOf { it.confidence.coerceIn(0.0, 1.0) } / working.crossModalLinks.size
            val weightedTierDen = nodes.sumOf { max(it.activation, it.expectation).coerceIn(0.0, 1.0) }
            val tierDepth = if (weightedTierDen <= 1e-9) 0.0 else
                (nodes.sumOf {
                    it.abstractionTierIndex.coerceAtLeast(0) * max(it.activation, it.expectation).coerceIn(0.0, 1.0)
                } / weightedTierDen / 10.0).coerceIn(0.0, 1.0)
            val retrievalShare = if (nodes.isEmpty()) 0.0 else
                nodes.count { it.retrieved }.toDouble() / nodes.size.toDouble()
            // Bounded working-set occupancy. 12 is an output reference window, not a memory ceiling.
            val density = (nodes.size / 12.0).coerceIn(0.0, 1.0)
            val memoryEnergy = (
                0.26 * activity +
                    0.20 * expectation +
                    0.24 * relevance +
                    0.20 * relationEnergy +
                    0.10 * prediction
                ).coerceIn(0.0, 1.0)

            return UnifiedPresentationState(
                activity = activity,
                expectation = expectation,
                relevance = relevance,
                relationEnergy = relationEnergy,
                signedExchange = signedExchange,
                prediction = prediction,
                novelty = novelty,
                crossmodal = crossmodal,
                tierDepth = tierDepth,
                retrievalShare = retrievalShare,
                directActivation = directActivation,
                density = density,
                memoryEnergy = memoryEnergy
            )
        }
    }
}
