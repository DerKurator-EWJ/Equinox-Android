package de.equinox.v05

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Read-only projection of the memory for output devices.
 * Renderers and sonifiers consume this object; they do not mutate memory.
 * Nothing here is a physical coordinate or a fundamental property of a node.
 */
data class EngineOutputState(
    val graph: GraphSnapshot,
    val sound: SonificationSnapshot,
    val stats: MemoryStats,
    val working: WorkingSetSnapshot
)

class EngineOutputBridge(private val memory: MemoryModel) {
    /**
     * Step 25: output projection budgets follow the current active structure. They are not
     * memory/model ceilings; a quiet scene renders little, a complex active scene exposes more.
     */
    fun snapshot(): EngineOutputState {
        val stats = memory.stats()
        val working = memory.workingSetSnapshot()
        val active = working.nodes.size.coerceAtLeast(1)
        val depthKinds = stats.levelCounts.size.coerceAtLeast(1)

        val graphMicro = max(24, sqrt(stats.persistentCodes.coerceAtLeast(1).toDouble()).toInt() * 3)
        val graphMacrosPerTier = max(8, (active * 1.35 / depthKinds).toInt() + 4)
        val graphEdges = max(120, active * 6)
        val soundMicro = max(4, sqrt(stats.adaptiveAudioCodes.coerceAtLeast(1).toDouble()).toInt())
        val soundMacros = max(6, active)

        return EngineOutputState(
            graph = memory.graphSnapshot(
                maxMicroNodes = graphMicro,
                maxMacroNodesPerLevel = graphMacrosPerTier,
                maxEdges = graphEdges
            ),
            sound = memory.sonificationSnapshot(
                maxMicro = soundMicro,
                maxMacros = soundMacros
            ),
            stats = stats,
            working = working
        )
    }
}
