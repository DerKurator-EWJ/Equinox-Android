# Equinox v0.6 — Step 18: Adaptive Visual Encoder

Step 18 brings the camera path onto the same architectural level as the adaptive audio path. The old fixed `(cell position × 64 RGB classes)` camera alphabet remains readable in existing memory, but new camera observations now use a persistent learned visual codebook.

## New in Step 18

- **AdaptiveVisualQuantizer** starts with an empty visual codebook and learns up to 1,024 stable appearance prototypes online.
- Each sampled camera cell is represented by seven normalized features: luminance, two opponent-chroma axes, saturation, local contrast, signed horizontal gradient and signed vertical gradient.
- Prototype IDs are negative and live in a separate range (`vision_adaptive`), so they cannot collide with FFT/DIRECT/ADAPTIVE audio IDs or the positive macro-ID range.
- A learned visual ID contains **no x/y coordinate**. The 8×6 grid is the sensor sampling order only; repeated appearance identities can therefore be reused at different positions.
- At the start of each image, the transient visual sequence buffers are cleared so a spatial macro candidate cannot accidentally bridge from the last cells of one frame into the first cells of the next. Persistent macros and temporal macro relations remain available.
- The same adaptive hierarchy, lazy working set, prediction, overexchange, ranking, relevance, templates and memory competition used by the rest of v0.6 now receive the `vision_adaptive` stream.
- Graph rendering treats legacy `vision` and new `vision_adaptive` nodes as visual nodes and keeps their learned representative color.
- The LIVE page reports visual-codebook size, recent novelty and mean prototype-match distance.
- Memory stats now separately show adaptive visual quants and adaptive visual macros.

## Small core hardening

Step 18 also removes an old numeric shortcut that treated every raw token ID above the macro base as if it were a macro. Macro composition / substitution indexing now checks whether an ID actually exists in the macro table. This fixes the legacy high positive camera-ID edge case without changing saved macro IDs.

## Data files

- Main relational memory: `equinox_v06_memory.json`
- Adaptive audio codebook: `equinox_v06_adaptive_quantizer.json`
- **Adaptive visual codebook:** `equinox_v06_adaptive_visual_quantizer.json`

The visual codebook and the `vision_adaptive` IDs stored in main memory belong together. Do not delete only the visual codebook if you intend to preserve those learned visual identities.

## Compatibility

- Application ID stays `de.equinox.v05`.
- Existing v0.6 main memory remains readable.
- Existing legacy `vision` quants/macros are not rewritten; new camera learning goes into the separate `vision_adaptive` modality.
- Version: `0.6-step18-adaptive-visual-encoder`, versionCode 32.

## Build verification done here

This environment still has no Android SDK, so a full Gradle APK build was not possible. The following were compile-checked separately with API-shaped stubs:

- `AdaptiveVisualQuantizer.kt`
- `VisualGridAnalyzer.kt`
- current `MemoryModel.kt` and its core dependencies
- modified `MainActivity.kt`

A small quantizer test also verified that two nearby visual feature vectors reuse one learned ID while a strongly different vector creates a different ID.

## Suggested phone test

1. Install/update and open LIVE.
2. Start camera learning and point the phone at one fairly static scene for 30–60 seconds.
3. Watch `Visual-Codebook`: after the first burst, reuse should increasingly dominate new IDs.
4. Move to a clearly different scene and confirm some new prototypes appear.
5. Return to the first scene and check whether novelty is lower than on the first exposure.
6. Let the camera run longer and inspect whether `vision_adaptive` macros begin to appear while the codebook growth rate slows.
