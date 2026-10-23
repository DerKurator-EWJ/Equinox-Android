package de.equinox.v05

/**
 * Formula-only geometric ground memory.
 *
 * These templates are renderer/recognition aids. They are NOT coordinates stored
 * in the Equinox relational core and they are NOT assumed to be fundamental
 * physical objects.
 */
enum class GeometryTemplate(
    val label: String,
    val dimension: Int,
    val anchorCount: Int,
    val closed: Boolean
) {
    LINE("Linie", 2, 2, false),
    ARC("Bogen", 2, 3, false),
    TRIANGLE("Dreieck", 2, 3, true),
    QUAD("Viereck", 2, 4, true),
    PENTAGON("Fünfeck", 2, 5, true),
    HEXAGON("Sechseck", 2, 6, true),
    CIRCLE("Kreis", 2, 0, true),
    ELLIPSE("Ellipse", 2, 0, true),

    TETRAHEDRON("Tetraeder", 3, 4, true),
    CUBE("Würfel", 3, 8, true),
    OCTAHEDRON("Oktaeder", 3, 6, true),
    PRISM("Prisma", 3, 6, true),
    PYRAMID("Pyramide", 3, 5, true),
    SPHERE("Kugel", 3, 0, true),
    CYLINDER("Zylinder", 3, 0, true),
    CONE("Kegel", 3, 0, true),
    TORUS("Torus", 3, 0, true)
}

data class GeometryGroundMemory(
    val templates2D: List<GeometryTemplate>,
    val templates3D: List<GeometryTemplate>
) {
    val totalTemplates: Int get() = templates2D.size + templates3D.size

    companion object {
        fun standard(): GeometryGroundMemory = GeometryGroundMemory(
            templates2D = GeometryTemplate.values().filter { it.dimension == 2 },
            templates3D = GeometryTemplate.values().filter { it.dimension == 3 }
        )
    }

    /**
     * Deterministic, lightweight template suggestion from already-existing
     * relational metadata. It is a display tag only.
     */
    fun suggest2D(memberCount: Int, sequence: Boolean): GeometryTemplate {
        if (sequence) return GeometryTemplate.ARC
        return when {
            memberCount <= 2 -> GeometryTemplate.LINE
            memberCount == 3 -> GeometryTemplate.TRIANGLE
            memberCount == 4 -> GeometryTemplate.QUAD
            memberCount == 5 -> GeometryTemplate.PENTAGON
            memberCount == 6 -> GeometryTemplate.HEXAGON
            memberCount in 7..10 -> GeometryTemplate.ELLIPSE
            else -> GeometryTemplate.CIRCLE
        }
    }

    fun suggest3D(memberCount: Int, sequence: Boolean): GeometryTemplate {
        if (sequence) return GeometryTemplate.TORUS
        return when {
            memberCount <= 3 -> GeometryTemplate.CONE
            memberCount == 4 -> GeometryTemplate.TETRAHEDRON
            memberCount == 5 -> GeometryTemplate.PYRAMID
            memberCount == 6 -> GeometryTemplate.PRISM
            memberCount in 7..8 -> GeometryTemplate.CUBE
            memberCount in 9..12 -> GeometryTemplate.OCTAHEDRON
            else -> GeometryTemplate.SPHERE
        }
    }
}
