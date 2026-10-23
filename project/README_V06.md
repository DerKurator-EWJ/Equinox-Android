# Equinox v0.6 — Step 05: Live / Memory / Lab

Basis: v0.6 Step 04.

## New in Step 05

- Three separated app pages:
  - **LIVE** — microphone/camera input → Equinox core → output canvas.
  - **MEMORY** — no sensor input; already learned macro structures activate each other through the stored relation index.
  - **LAB** — graph/debug view, display settings, save slots, diagnostics, consolidation and manual sonification.
- Memory playback is deliberately **non-learning**:
  - no new observations,
  - no new candidates/macros,
  - no macro-weight increments,
  - no new persistent macro relations,
  - no persistent overexchange update.
- Memory playback keeps only a small temporary working set and follows indexed learned neighbors. The full memory is not recalculated.
- Output canvas now has an explicit LIVE/MEMORY label.
- Optional **Memory Orchestra** can synthesize the currently materialized memory state with oscillators only; no sample/sound library is used.
- Event-local overexchange is now separated from the long-term EMA memory so the canvas displays the current exchange rather than a stale global value.
- v0.6 save slots and long-term diagnostics now correctly use `equinox_v06_memory.json` instead of the legacy Step03 filename.

## Memory playback path

`stored macro -> indexed learned neighbors -> temporary working set -> transient overexchange -> canvas/orchestra`

The traversal is an output/read path. It does not feed itself back into the learner.

## Build

Open exactly `Equinox_v06_Step05_LiveMemoryModes` as the Android Studio project and use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05` so it updates the same installed app when signed with the same key/debug key.

Version: `0.6-step05-live-memory-modes` (versionCode 19).
