# Equinox v0.6 — Step 19: Crossmodal Macros

Step 19 joins the previously separate learned audio and visual hierarchies **above** their sensor alphabets. Raw FFT/DIRECT/ADAPTIVE audio quants and adaptive visual quants are still learned independently. A cross-modal identity can appear only after already-learned audio and visual **macros** repeatedly occur close together in live sensor time.

## New in Step 19

- Runtime-only coincidence window: approximately 900 ms. The clock is used only to align asynchronous microphone/camera events; timestamps are not stored in nodes.
- Only real sensor macro activations provide cross-modal evidence. Memory recall, predictions and retrieved context cannot manufacture cross-modal memories.
- Repeated pair evidence creates a sparse candidate `(audioMacro, visualMacro)`. Four separated confirmations are required before promotion.
- Repeated evidence from the same burst is rate-limited, preventing a fast audio loop from counting one camera frame hundreds of times.
- A promoted pair becomes one persistent `crossmodal` macro whose children are the learned audio macro and visual macro. Raw quants are never directly cross-linked.
- Joint macros enter the bounded working set and can themselves form a temporal cross-modal sequence stream.
- The normal memory-competition system can later forget weak/stale joint macros; their child macros are protected while the joint macro depends on them.
- LIVE/HYBRID output draws an active joint as a gold node with a cyan arm to audio and magenta arm to vision.
- Memory stats report learned cross-modal macros, temporary candidates, activations and strongest unpromoted support.

## Why this is still sparse

There is no `audioCount × visualCount` matrix. A pair is considered only when two already-learned sensor macros are both observed within the transient coincidence window. The learned memory therefore scales with **experienced joint events**, not every mathematically possible audio/visual combination.

## Data / compatibility

- Main memory remains `equinox_v06_memory.json`.
- Existing Step-18 memories load normally.
- New cross-modal macros are stored in the existing macro array using modality `crossmodal`; no migration of old audio/vision identities is required.
- Adaptive audio and adaptive visual codebooks stay separate and unchanged.
- Application ID remains `de.equinox.v05`.
- Version: `0.6-step19-crossmodal-macros`, versionCode 33.

## Suggested phone test

1. Let audio and camera each learn enough structure that both have macros.
2. Run microphone + camera together on one repeatable event (for example the same visible object while making the same sound).
3. Repeat it several times, separated by a short pause.
4. Watch `Crossmodal`: candidates should first gain support; after four separated confirmations a joint macro can appear.
5. Change either the sound or visual scene. The previous joint should not automatically absorb every new combination.
6. Repeat the original pairing and check that the existing joint macro is reused instead of creating another one.

## Build note

The assistant environment still has no Android SDK, so the full Gradle APK build cannot be performed here. Core/UI sources are compile-checked separately with JVM/API-shaped stubs before packaging.
