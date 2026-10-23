# Equinox v0.6 Step 12 — Adaptive Quantizer

Open this folder directly in Android Studio and build the APK from this project.

New in Step 12:
- third audio encoder: **ADAPTIVE-ID** (no FFT, no fixed 16-bit bins)
- persistent learned vector-quantizer codebook (`equinox_v06_adaptive_quantizer.json`)
- stable adaptive IDs in a separate `audio_adaptive` memory stream
- online prototype centroids with slowly learned local acceptance radii
- hard cap of 4096 prototypes
- three-way live benchmark: FFT vs fixed DIRECT vs ADAPTIVE on the exact same PCM frames
- adaptive codebook diagnostics in LAB
- existing v0.6 memory remains compatible; adaptive IDs start in a non-colliding negative range

Important: do not delete only the adaptive codebook while keeping adaptive memory, because stable IDs would be reused for different prototypes. Treat codebook + memory as one learned state.

This source tree was statically Kotlin-checked here, but a full Android APK build still requires Android Studio / Android SDK.
