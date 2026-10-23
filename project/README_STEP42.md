# Equinox v0.6 — Step 42: Learn / Play / Dream Separation

Step 42 makes runtime intent explicit and moves sensor learning behind the Settings layer. The persistent memory format is unchanged.

## 1. Three cognitive modes

`CognitiveMode` is runtime-only:

- **LEARN** — external audio/camera input may update codebooks, candidates, macros, relations, prediction evidence and cross-modal evidence.
- **PLAY** — external input is allowed, but it may only recognize and activate already learned identities/macros. Persistent knowledge is read-only.
- **DREAM** — external observations are ignored by `MemoryModel`; activation comes only from the autonomous internal memory traversal.

Changing mode clears transient working-state/cursors so one mode cannot leak live context into another mode's learning semantics.

## 2. PLAY is real recognition, not "sensors off"

Step 42 adds a separate read-only hierarchy stream. A known sensor token can:

`known quant -> known macro -> known higher macro -> bounded recall/prediction -> Unified State`

but PLAY does **not** increment:

- observation counters,
- quant weights,
- candidate hits,
- macro weights,
- transition relations,
- cross-modal evidence,
- persistent overexchange memory.

The hierarchy router and signature index are reused to address existing macros. Unknown input simply remains unknown to memory.

## 3. Adaptive codebooks are read-only in PLAY

`AdaptiveAudioQuantizer.quantize(..., learn = false)` and
`AdaptiveVisualQuantizer.quantize(..., learn = false)` classify only against existing prototypes.

In PLAY they do not:

- create prototypes,
- move prototype centres,
- change prototype counts/radii/colours,
- increment persisted quantizer learning statistics,
- save a newly learned identity.

A sufficiently novel feature vector returns `recognized = false` instead of being forced into the nearest known prototype.

For camera input, only recognized visual observations enter `SpatialVisualCoupler` and then the read-only memory activation path. Camera pigment remains available for display even when no learned visual identity matches.

## 4. DREAM boundary

`autonomousDreamStep()` now runs only while `CognitiveMode.DREAM` is active.

DREAM still traverses existing:

- prediction paths,
- ordinary relations,
- substitution alternatives,
- cross-modal bridges.

External `observe*()` calls are ignored in DREAM. DREAM cannot manufacture new sensor evidence.

## 5. PLAY / MEMORY / HYBRID

The previous MEMORY and HYBRID traversals remain useful internal playback tools, but they now belong to PLAY semantics and run only in `CognitiveMode.PLAY`.

This means the top-level contract is no longer confused with the older screen names:

- LEARN = permission to write experience,
- PLAY = read-only reaction/playback using learned memory,
- DREAM = read-only internally generated activation.

## 6. Product/UI change

The unified product canvas remains the only main surface.

The bottom product bar now exposes the current cognitive state (`PLAY`, `DREAM`, or `LEARN`). The main mode action toggles the consumer surface between PLAY and DREAM. LEARN is selected deliberately under **Settings -> INPUT / LEARN** together with the microphone/camera controls.

Sensor controls therefore remain available for development/training, but learning is no longer presented as the primary product surface.

## 7. Persistent-write guards

`MemoryModel.consolidate()` and manual memory competition are no-ops outside LEARN. Explicit maintenance actions such as profile import/export or destructive reset remain user actions rather than implicit learning.

The mode itself is not stored in `.eqx` profiles. Loading/restarting does not alter the learned identity format.

## 8. Compatibility

No indexed-store schema, macro field, relation format or adaptive-codebook file format was changed.

Step 37–41 profiles remain compatible. Voice signatures from Step 41 are still reconstructed read-only from existing macro composition.

## 9. Verification performed

Kotlin/JVM stub checks in the generation environment:

- modified `MemoryModel` compiles against the existing Step-41 dependencies,
- modified adaptive audio and visual quantizers compile,
- modified `VisualGridAnalyzer` + `SpatialVisualCoupler` compile against CameraX stubs,
- source parse check of the modified `MainActivity` reports no Kotlin parse errors.

Synthetic mode test:

1. LEARN trained a repeating audio stream and produced 36 macros from 540 observations.
2. PLAY replayed the known stream and activated 3 learned macros.
3. During PLAY, observation count, macro count and relation count remained unchanged.
4. DREAM ignored external observations and still produced an internal autonomous activation.

Adaptive-codebook test:

- a learned audio prototype was recognized in PLAY without changing quantizer learning counters,
- a learned visual prototype was recognized in PLAY without changing quantizer learning counters,
- a novel visual vector returned `recognized = false` and did not enlarge the codebook.

A full Android APK build still requires the local Android SDK/Android Studio.

## Next planned step

Step 43: **Hypno Mode** — make the unified audiovisual state the primary consumer presentation while retaining the Step-42 LEARN/PLAY/DREAM boundaries underneath it.
