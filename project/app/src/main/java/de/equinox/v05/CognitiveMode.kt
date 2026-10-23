package de.equinox.v05

/**
 * Step 42 runtime contract.
 *
 * LEARN: external sensor events may update persistent codebooks/memory.
 * PLAY: external sensor events may only activate already learned identities/macros.
 * DREAM: only internal memory traversal may activate state; external events are ignored by MemoryModel.
 *
 * This mode is runtime state, not part of a learned profile.
 */
enum class CognitiveMode {
    LEARN,
    PLAY,
    DREAM;

    val writesPersistentKnowledge: Boolean get() = this == LEARN
    val acceptsExternalActivation: Boolean get() = this != DREAM
    val label: String get() = when (this) {
        LEARN -> "LEARN"
        PLAY -> "PLAY"
        DREAM -> "DREAM"
    }
}
