# Equinox v0.5 — Step 10

Step 10 adds a switchable 2D/3D renderer and a small formula-only geometric ground memory.

## New
- 2D / 3D switch directly below the graph.
- The relational MemoryModel remains unchanged with respect to space:
  - no persistent x
  - no persistent y
  - no persistent z
- 3D depth is generated only by RelationGraphView from graph metadata and a deterministic renderer seed.
- Same graph, same codes, same relations in both modes.
- Perspective projection, depth ordering and depth-scaled node/edge rendering in 3D.
- GeometryGroundMemory with reusable template definitions.

### 2D templates
- line
- arc
- triangle
- quadrilateral
- pentagon
- hexagon
- circle
- ellipse

### 3D templates
- tetrahedron
- cube
- octahedron
- prism
- pyramid
- sphere
- cylinder
- cone
- torus

Higher abstraction nodes receive deterministic display suggestions from the geometry grammar based on member count and whether the node came from a sequence.

## Architectural rule
The geometry library is NOT a hidden reintroduction of fundamental Euclidean space.
It is a renderer / pattern-language layer.

Core:
audio + vision -> symbols -> relations -> sequences -> recursive abstraction

Display:
relational snapshot -> layout -> optional 2D/3D projection -> geometric glyph

## Preserved
- Step 09 camera learning
- audio/vision cross-modal relations
- Step 08 sequence compression
- Step 07 recursive abstraction
- sonification
- consolidation
- existing persistent memory migration

## Install
Open as a fresh Android Studio project, build the APK and install it over the same
`de.equinox.v05` application.
