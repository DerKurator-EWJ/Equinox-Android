# Equinox v0.6 — Step 43: Hypno Mode

Step 43 makes **HYPNO** the primary consumer presentation while keeping the Step-42 LEARN / PLAY / DREAM semantics unchanged.

## Product architecture

There is still exactly one persistent OpenGL surface and one read-only output path:

`MemoryModel -> WorkingSetSnapshot -> GpuMemoryVideoView`

HYPNO is a presentation of that state, not a second memory system, a second renderer, or another cognitive mode.

The technical Step-32/33 hierarchy/tissue projection remains available as **STRUCTURE** under **Settings -> LAB -> PRODUCT RENDERER** for comparison and diagnostics. Every app launch starts in HYPNO.

## What drives HYPNO

HYPNO reads the same bounded GPU field projection already used by the unified canvas and turns it into overlapping continuous auras / interference regions instead of explicit nodes and relation lines.

The visual controls are read-only projections of current state:

- **activity / relevance** -> visible field presence
- **prediction** -> visual coherence and smoother field organization
- **signed overexchange** -> flow polarity / tint direction
- **abstraction tier** -> spatial scale / breadth, never importance
- **crossmodal confidence** -> stronger fusion of visual color families
- **retrieval share** -> calmer broad memory depth
- **novelty proxy** -> sharper interference accents

### Novelty boundary

The novelty value in HYPNO is deliberately only a **renderer-side proxy**:

`direct live activation * (1 - explained expectation/prediction)`

It is not persisted, not exposed as a learned truth, and never feeds back into `MemoryModel`. This avoids silently inventing a new cognitive variable merely for visual effect.

## No artificial motion

HYPNO does **not** introduce a time uniform, global sine wobble, or autonomous visual animation.

The interference function depends on renderer position plus current Equinox state. Motion occurs only when the underlying active state changes or while Step-33 temporal interpolation is settling between two states. `RENDERMODE_WHEN_DIRTY` remains in use.

## Reality <-> Memory continuum

The existing continuous `memoryBlend` remains intact for now:

- 0% = live camera pigment dominates
- intermediate = live pigment increasingly shaped by the active memory state
- 100% = pure internal Hypno field on the dark memory background

Step 43 changes *how* that unified state is presented. A later step can change the source control into the planned Dream <-> Live activation mixer without replacing the renderer.

## Compatibility

- no persistent memory schema change
- no profile migration
- no adaptive codebook migration
- no change to macro IDs, relations, routing, recall or prediction
- LEARN / PLAY / DREAM write/read boundaries from Step 42 remain unchanged
- Unified Audio remains the same Step-41 audio engine
- recording still captures the same product surface and Unified Audio

## Verification

The Step-43 `GpuMemoryVideoView.kt` compiles against Kotlin Android/OpenGL stubs after the renderer changes.

Static architecture checks verify:

- default presentation is HYPNO
- STRUCTURE remains selectable only from LAB
- no time uniform was introduced
- one `GpuMemoryVideoView` remains the product surface
- HYPNO has no reference to persistence/write APIs

A full APK build and real OpenGL shader compilation should still be performed in Android Studio/device because this environment does not provide the Android SDK/GLES driver toolchain.

## Version

`0.6-step43-hypno-mode` — versionCode 58
