# Equinox v0.6 — Step 16: Hybrid Mode

Step 16 closes the first loop between **live perception** and **learned memory** without turning recalled material into fake experience.

## New in Step 16

- New fourth main page: **HYBRID** next to LIVE, MEMORY and LAB.
- Microphone/camera remain real sensor input and are the only source of live **A-state**.
- Every real macro activation re-anchors a runtime Hybrid cursor in the present.
- Between sensor events, Hybrid mode may follow one already learned prediction/relation hop.
- Recalled/predicted macros enter only as expectation / **B-state**.
- Hybrid propagation is read-only: it does **not** increment observations, macro weights, candidate hits or transition counts.
- Hybrid overexchange is transient and is not written into persistent exchange memory.
- The bounded Working Set and existing local relation index are reused; no full-memory scan is added per Hybrid tick.
- HYBRID has its own output canvas and direct sensor start/stop controls.
- The orchestra can render the current Hybrid state, but Step 16 still stops microphone input before loudspeaker synthesis to avoid acoustic self-learning. Simultaneous audio I/O is reserved for Step 17.

## Runtime flow

`live sensor -> quant/macro -> A-state -> memory lookup/prediction -> B-state -> transient overexchange -> canvas/orchestra`

A new live macro always resets the Hybrid traversal anchor, so internal memory remains coupled to the current world instead of drifting indefinitely on its own.

## Memory compatibility

The existing `equinox_v06_memory.json` remains compatible. Hybrid state is runtime-only. The format marker is updated to `60_16`; no destructive migration is required.

## Build

Open `Equinox_v06_Step16_HybridMode` directly in Android Studio and build the APK as usual.

A full Android Gradle build cannot be run in the generation environment because the Android SDK is unavailable. The changed Kotlin core and activity are checked separately with JVM/Android stubs before packaging.
