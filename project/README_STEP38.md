# Equinox v0.6 — Step 38: Spatial Visual Coupler

Step 38 reduces redundant camera learning before observations enter the persistent macro stream.

## What changed

- Added `SpatialVisualCoupler.kt`.
- `VisualObservation` now carries normalized quadtree bounds (`x0/y0/x1/y1`).
- The coupler first removes a coarse parent only when all four direct children were actually sampled. This keeps full-frame coverage when the adaptive work budget stops midway through refinement.
- Spatially touching leaf regions are joined only when their learned visual identity / prototype colour is sufficiently compatible.
- Novel boundary regions are deliberately harder to merge than familiar flat regions.
- Camera learning now feeds the coupled regions into `MemoryModel` instead of every raw adaptive patch.
- The raw adaptive observations remain available for diagnostics and resolution control; this step changes the learning input, not the camera/video renderer.
- Settings diagnostics now report `Samples -> Leaves -> Coupled` and the frame-local reduction percentage.

## Intended behavior

A familiar, visually homogeneous wall can collapse from many neighboring adaptive patches to only a few contiguous observations before macro learning. Detailed / novel / edge-heavy areas remain split more finely.

This is deliberately a **sensor-side spatial compression step**, not semantic object recognition. A coupled region is still represented by the dominant learned visual identity. Shape-aware persistent region IDs / top-down hierarchical routing remain separate later steps.

## Safety / compatibility

- Existing indexed long-term stores and Step-37 memory profiles are unchanged.
- No memory migration is required.
- Coupling is deterministic and stateless; it does not write directly to `MemoryModel`.
- The existing adaptive visual codebook remains the source of visual identities.

## Test on device

In Settings with camera learning active, compare:

`Samples N · Leaves L -> gekoppelt C (-R%)`

Flat familiar surfaces should produce a substantial `L -> C` reduction. Complex edges and novel areas should reduce less.

A full Android Gradle/APK build was not executed in the generation environment because the Android SDK is not installed there.
