# Equinox v0.5 — Step 06: Memory Sonification

Step 06 extends the working Step 05 project without changing the application ID (`de.equinox.v05`) or the persistent memory file.

## New in Step 06
- `GEDÄCHTNIS HÖRBAR MACHEN` outputs a read-only snapshot of learned memory through the phone speaker.
- Strong microcodes are rendered as short tones.
- Macrocodes are rendered as chords built from their member micro frequencies.
- Learned representative frequencies are octave-folded into 110–1760 Hz for practical phone-speaker playback while preserving octave relationships.
- Weight affects tone duration.
- `KLANG STOPP` stops playback.
- The microphone is deliberately stopped before sonification so the system does not immediately learn its own speaker output.
- No sonification event is written back to MemoryModel.

## Build
Open this folder as a new Android Studio project, wait for Gradle sync, then generate/build the debug APK. No emulator or target device is required for APK generation.

The application ID remains `de.equinox.v05`, so installation updates the existing Equinox app and retains the Step 03/04/05 memory.
