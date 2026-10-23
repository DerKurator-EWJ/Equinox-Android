# Equinox v0.6 — Step 41: Unified Audio + Audio Macro Voices

Step 41 removes the remaining split audio-output architecture and makes learned macro composition itself the source of acoustic identity.

## 1. One hardware audio engine

`UnifiedAudioEngine` is now the only class in the project that owns an Android `AudioTrack`.

It serves all output cases:

- continuous product audio,
- MEMORY preview,
- LAB preview,
- PCM tapping for MP4 recording,
- feedback-suppression frequency reporting.

The former independent `Sonifier`, `OrchestraSonifier` and `ContinuousOrchestraEngine` hardware paths are removed. This prevents two output engines from competing for AudioTrack state or producing different interpretations of the same memory.

The main product bar now has one `♪` audio control. Settings/LAB preview actions route through the same engine rather than starting their own player.

## 2. Acoustic identity comes from learned composition

Every audio macro receives a read-only `AudioMacroVoiceSignature` when a sonification snapshot is built:

- `rootHz` — geometric acoustic centre of the child voices,
- `partialHz` — strongest recursively inherited partials,
- `partialWeights` — normalized support of those partials,
- `contourRatios` — ordered child-root ratios relative to the parent root,
- `spectralSpread` — dispersion of child voices,
- `compositionHash` — deterministic identity seed derived from the actual child token sequence.

For a leaf/quant the voice is the leaf itself. For a higher macro:

`Voice(parent) = F(Voice(child1), Voice(child2), ...)`

The reconstruction is recursive, so a deep macro is ultimately grounded in its actual learned descendants rather than an arbitrary tier-to-note table.

## 3. Tier no longer shifts pitch

Before Step 41, the orchestra applied a level-dependent frequency scale. A deeper macro could therefore sound lower simply because it was deeper.

Step 41 removes that mapping. `baseHz` is taken from the reconstructed macro voice. Abstraction depth may still choose an output *role/timbre/envelope* (pulse, motif, harmony, drone), but it does not manufacture a new note.

Motif movement follows `contourRatios`; chord energy follows the learned partial weights. The composition hash seeds deterministic phase/modulation details, so the same learned macro has a stable acoustic identity across playback sessions.

## 4. Persistence and profiles

No new learned field is persisted. Voice signatures are output projections rebuilt from the existing quant/macro composition after load.

Therefore Step 37/38/39/40 profiles and the indexed long-term store remain format-compatible. Import/export needs no migration.

## 5. Read-only boundary

Audio still never writes observations, relations, predictions or macros back into `MemoryModel`.

`MemoryModel -> SonificationSnapshot/WorkingSet -> UnifiedAudioEngine -> PCM -> AudioTrack`

Recording taps the same PCM before AudioTrack and remains read-only.

## 6. Verification

Performed in the generation environment:

- Kotlin compile check of the modified `MemoryModel` against Android/JSON stubs: passed.
- Kotlin compile check of `GenerativeOrchestraSynth` + `UnifiedAudioEngine` against audio stubs: passed.
- Source ownership check: exactly one `AudioTrack` import/builder remains, in `UnifiedAudioEngine.kt`.
- Synthetic repeated-audio training produced multi-level audio macros and recursively reconstructed voice signatures through at least levels 1–4.
- Scene generation from those signatures produced valid bounded output frequencies and non-empty composition contours.

A full Android APK build still requires Android Studio / the Android SDK on the target development machine.

## Next planned step

Step 42: **Learn / Play / Dream separation** — make learning a settings/lab concern while the main product surface becomes a clean playback/Hypno-oriented consumer of the same unified state.
