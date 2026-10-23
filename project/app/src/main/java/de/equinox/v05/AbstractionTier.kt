package de.equinox.v05

/**
 * v0.6 Step 25
 *
 * The abstraction depth itself is the RPG tier. Q is the encoder alphabet and therefore
 * not a macro tier. The first persistent composition is F, then E..SSS, Ω, Ω+1, ...
 * There is deliberately no maximum abstraction depth.
 */
object AbstractionTier {
    fun labelForMacroDepth(depth: Int): String {
        val d = depth.coerceAtLeast(1)
        return when (d) {
            1 -> "F"
            2 -> "E"
            3 -> "D"
            4 -> "C"
            5 -> "B"
            6 -> "A"
            7 -> "S"
            8 -> "SS"
            9 -> "SSS"
            10 -> "Ω"
            else -> "Ω+${d - 10}"
        }
    }

    /** Zero-based display index: F=0 ... Ω=9, Ω+n=9+n. */
    fun indexForMacroDepth(depth: Int): Int = (depth.coerceAtLeast(1) - 1)

    fun labelForIndex(index: Int): String = labelForMacroDepth(index.coerceAtLeast(0) + 1)
}
