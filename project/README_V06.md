# Equinox v0.6 — Step 04: Output Canvas + Orchestra

This step is based on the working Step 03 Active/Lazy/Exchange project.

## New in Step 04

- `EquinoxOutputView.kt`: a read-only live-state canvas.
  - consumes `EngineOutputState`
  - visualizes only the currently active/retrieved working set
  - uses activation, expectation and overexchange as visual input
  - renderer coordinates are projection-only and are not written back into the core
- `OrchestraSonifier.kt`: library-free generative audio projection.
  - no samples or sound library
  - uses oscillator synthesis only
  - active macro state controls voice importance
  - abstraction level changes register/harmonic character
  - exchange bias changes modulation/harmonic balance
  - if no active audio macro exists it can fall back to stored audio macros
- `MainActivity` now shows:
  - a dedicated OUTPUT / LIVE STATE CANVAS
  - the old graph below it as LAB / DEBUG VIEW
  - an `AKTIVEN STATE ALS ORCHESTER SPIELEN` button
- Output remains read-only: neither canvas nor orchestra changes `MemoryModel`.

## Important audio note

The orchestra button stops microphone learning before playback. This is intentional in this step so the phone does not immediately learn its own loudspeaker output. True simultaneous microphone + speaker operation needs either headphone mode or deliberate echo/feedback handling and is reserved for a later step.

## Build

Open the folder `Equinox_v06_Step04_OutputCanvasOrchestra` directly in Android Studio and build the APK from that project.

Application ID remains `de.equinox.v05`.
Version: `0.6-step04-output-canvas-orchestra`.
