# Equinox v0.6 — Step 13: Adaptive Hierarchy

Open this exact folder as a new Android Studio project and build the APK from it.

This step keeps Step 12 and adds:

- adaptive macro promotion based on estimated compression gain rather than one fixed hit threshold;
- bounded live auto-promotion with a per-level cooldown;
- non-overlapping macro emission into the next abstraction level;
- live hierarchy/compression diagnostics in the app;
- the same FFT, DIRECT and ADAPTIVE encoders from Step 12.

Application ID: `de.equinox.v05`
Version: `0.6-step13-adaptive-hierarchy` / versionCode 27

The full Android APK was not built in the generation environment because no Android SDK is installed there. The changed core and MainActivity were compiled against Kotlin/Android stubs and the hierarchy logic was exercised with a synthetic repeated-token test.
