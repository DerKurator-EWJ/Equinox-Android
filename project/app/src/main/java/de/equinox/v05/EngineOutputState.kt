package de.equinox.v05

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
    /** Lazy: snapshots are created only when an output asks for them. */
    fun snapshot(
        maxGraphMicro: Int = 34,
        maxGraphMacrosPerLevel: Int = 12,
        maxGraphEdges: Int = 360,
        maxSoundMicro: Int = 8,
        maxSoundMacros: Int = 8
    ): EngineOutputState = EngineOutputState(
        graph = memory.graphSnapshot(
            maxMicroNodes = maxGraphMicro,
            maxMacroNodesPerLevel = maxGraphMacrosPerLevel,
            maxEdges = maxGraphEdges
        ),
        sound = memory.sonificationSnapshot(
            maxMicro = maxSoundMicro,
            maxMacros = maxSoundMacros
        ),
        stats = memory.stats(),
        working = memory.workingSetSnapshot()
    )
}
