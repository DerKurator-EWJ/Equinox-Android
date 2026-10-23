# Equinox v0.5 — Step 04

Step 04 adds the first relational tissue visualization to the working Step 03 audio-memory baseline.

## New in Step 04
- persistent frequency codes are rendered as nodes
- directed remembered transitions are rendered as curved relations
- visual curvature rises with repeated interaction using kappa(w) = 1 - exp(-0.08 w)
- relation weight also affects line thickness/opacity
- node size reflects code recurrence weight
- node color is derived from representative frequency on a logarithmic scale
- lightweight force layout uses the relation graph to derive display coordinates
- the core still stores no x/y coordinates
- graph refreshes during audio and after consolidation
- Step 03 memory file is intentionally reused, so an update can retain learned codes/relations

## Still intentionally not included
- macro nodes / recursive promoted sequences
- camera / visual input
- 3D renderer
- geometry ground-memory matching
- save-slot manager

Package/application ID remains `de.equinox.v05`, so it installs as an update of previous Equinox v0.5 steps when signed by the same local debug key.
