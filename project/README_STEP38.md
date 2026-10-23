# Step 38 — Spatial Coupler

Step 38 reduces redundant camera-to-core work before the visual stream enters the macro hierarchy.

## Pipeline

`Camera -> adaptive coarse-to-fine sampling -> adaptive visual IDs -> SpatialVisualCoupler -> MemoryModel`

The visual quantizer still learns the same appearance IDs. The new coupler is frame-local and does **not**
invent a second semantic database. Neighboring regions are joined when they are spatially connected and
their seven-dimensional appearance signatures are sufficiently close.

## What changes

- Stable low-detail regions no longer refine merely because they are familiar.
- Novel/surprising regions can still receive exploratory refinement.
- Neighboring near-identical observations are grouped with a spatial hash + union-find.
- Strong local edges act as coupling barriers for different visual IDs.
- The memory core receives one event per coupled region instead of one event per sampled patch.
- `supportCount` preserves how much lower-level visual evidence was merged into the event.
- Region order stays spatial (`top -> bottom`, then `left -> right`) so the visual macro stream remains deterministic.
- Settings diagnostics now show `raw -> coupled` and the percentage of core events removed.

## Semantics

This is a **spatial compression/coupling layer**, not object recognition. A flat wall-like field can collapse
to a small number of region events, but the system does not claim that the region semantically means
"wall". Higher macro learning or a future imported base knowledge layer can attach that abstraction.

## Persistence / profiles

No new persistent codebook is introduced in Step 38. The coupler reuses existing adaptive visual IDs, so
Step 37 memory profiles remain compatible without a format migration.

## Expected effect

Known homogeneous surfaces should become cheaper twice:

1. fewer deep camera-analysis patches due detail-aware refinement;
2. fewer visual events entering candidate/macro/relation processing due spatial coupling.

The exact reduction depends on the scene. Highly textured scenes should couple much less than flat walls.
