package de.equinox.v05

/**
 * Step 45: provenance is independent from abstraction depth.
 *
 * BASE     = immutable knowledge shipped/imported as a global base pack.
 * PERSONAL = knowledge learned by this local profile.
 * DERIVED  = a local macro whose composition bridges BASE with profile-owned knowledge.
 */
enum class KnowledgeOrigin(val code: Int) {
    BASE(0),
    PERSONAL(1),
    DERIVED(2);

    companion object {
        fun fromCode(code: Int): KnowledgeOrigin = entries.firstOrNull { it.code == code } ?: PERSONAL
    }
}

data class KnowledgeProvenance(
    val origin: KnowledgeOrigin,
    val sourceMacroIds: List<Int> = emptyList()
)

/** Reserved ID space keeps future immutable base packs disjoint from local profile IDs. */
object KnowledgeIdSpace {
    const val PERSONAL_MACRO_MIN = 1_000_000
    const val BASE_MACRO_MIN = 1_500_000_000
    const val BASE_MACRO_MAX = Int.MAX_VALUE - 1024

    fun isBaseMacroId(id: Int): Boolean = id in BASE_MACRO_MIN..BASE_MACRO_MAX
}
