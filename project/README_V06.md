# Equinox v0.6 — Step 13: Adaptive Hierarchy / Compression

Step 13 keeps the complete Step-12 system and changes how recurring structures become and feed higher abstraction levels.

## 1. Promotion is now compression-driven

A temporary candidate of length `L` observed `H` times is evaluated approximately as:

`rawCost = H * L`

`compressedCost = H + dictionaryCost`

`gain = (rawCost - compressedCost) / rawCost`

A candidate can promote itself when it has enough support and the estimated gain exceeds the level-specific threshold. Higher levels require more evidence. This is an engineering compression criterion, not a semantic claim.

The policy lives in `AdaptiveHierarchyPolicy.kt`.

## 2. Live self-promotion is bounded

Only the best currently eligible candidate may auto-promote on one incoming token. Auto-promotion also has a per-stream cooldown:

- L0: 48 source tokens
- L1: 24 source tokens
- L2+: at least 12/8 source tokens

This prevents a repetitive signal from instantly generating every overlapping combination as a permanent macro. Manual/timed consolidation remains available and is also ranked by the same adaptive compression score. Consolidation is capped at 8 new macros per level per pass.

## 3. Non-overlapping upward emission

A learned macro still stores its constituent token IDs, but Step 13 no longer recursively emits every overlapping match. For each modality/level stream, an accepted macro span must start after the previously emitted span.

Example: if `[A B C]` is a learned macro, repeated `A B C A B C` can emit approximately:

`M_ABC  M_ABC`

rather than repeatedly activating overlapping tails. The next abstraction level therefore receives a substantially shorter stream as the system learns stable patterns.

This higher stream is an abstraction stream, not a lossless raw-audio archive. The original level-0 identities remain in the base memory/counters, and every macro keeps references to its constituents.

## 4. New diagnostics

The normal memory statistics now show:

- L0 input token count
- number of accepted base macro emissions
- estimated tokens saved by replacing non-overlapping recognized spans with one macro token
- estimated compression gain
- mean accepted macro span
- number of live auto-promoted macros in this session

This makes the central hard test visible: with repeated familiar input, the higher-level stream should need fewer tokens to represent recognized structure.

## 5. Existing systems remain

Step 13 retains:

- FFT frequency IDs
- fixed DIRECT IDs
- learned ADAPTIVE IDs
- lazy working set / retrieval
- overexchange
- recursive macros through L4
- templates and slots
- substitution classes
- multidimensional relevance
- F → SSS → Ω+n structural ranks
- LIVE / MEMORY / LAB
- visual canvas and orchestra
- encoder convergence lab

Existing `equinox_v06_memory.json` and `equinox_v06_adaptive_quantizer.json` remain load-compatible. Newly auto-promoted macros use the same persistent macro format as older ones.

## Validation performed

The modified `MemoryModel`, adaptive/direct quantizers, relevance/rank engines and new hierarchy policy were compiled together with Kotlin stubs. `MainActivity` was separately compiled against Android/AndroidX stubs.

A synthetic stream repeating a three-token pattern 40 times (120 base tokens) produced a bounded hierarchy with 6 auto-promoted macros, reached L3, emitted 37 non-overlapping base macros and reported an estimated base-token reduction of about 61.7% for that artificial case. This is only a mechanics test; it is not evidence of semantic learning or real-world compression quality.

A full Android APK build still has to be run in Android Studio because the generation environment does not contain the Android SDK.

## Build

Application ID: `de.equinox.v05`

Version: `0.6-step13-adaptive-hierarchy` (versionCode 27)
