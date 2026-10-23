# Equinox v0.6 Step 11 — Encoder Convergence Lab

Open this folder directly in Android Studio and build the APK with **Build > Build APK(s)**.

New in Step 11: LAB can run FFT and DIRECT-ID on the exact same live PCM frames and
measure their discrete-vocabulary convergence separately. The test reports recent ID
novelty, transition novelty, entropy and macro growth, and can export a CSV from the
app's internal storage.

Use **TEST NEU** before a comparison session. The parallel test intentionally does
roughly twice the audio encoder work, so disable it after the hard test.

See `README_V06.md` for the architecture and metric definitions.
