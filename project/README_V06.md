# Equinox v0.6 — Step 12: Adaptive Quantizer

Step 12 keeps the complete Step-11 core and adds a learned, FFT-free quantizer beside the two existing encoders.

## Audio encoders

1. `FFT → Frequenz-ID` — legacy frequency path.
2. `DIRECT → 16-bit Pattern-ID` — fixed hand-designed time-domain bins.
3. `ADAPTIVE → gelernte Pattern-ID` — online vector quantization with a persistent learned codebook.

The adaptive encoder extracts six normalized time-domain properties from each 2048-sample PCM frame: a logarithmic zero-crossing pitch proxy, normalized roughness, autocorrelation-lag class, periodicity strength, envelope slope and pulse/unevenness. It does **not** call the FFT.

Each frame is compared with existing prototypes. If the nearest prototype lies inside its learned acceptance radius, that stable ID is reused and the centroid moves slightly toward the observation. Otherwise a new stable ID is created. IDs never get renumbered. The codebook is capped at 4096 prototypes and stored in `equinox_v06_adaptive_quantizer.json`.

Adaptive IDs occupy a separate negative range below `-1,000,000` and MemoryModel stores them under modality `audio_adaptive`, so they cannot collide with fixed DIRECT IDs or FFT IDs. Macro learning, lazy retrieval, overexchange, templates, substitution classes, ranks and relevance then operate through the same core as before.

## Three-way convergence lab

`Alle 3 Encoder parallel lernen + messen` feeds FFT, fixed DIRECT and ADAPTIVE the exact same live PCM frames. The lab reports vocabulary size, recent ID novelty, transition novelty, entropy and macro growth for all three. This is a convergence diagnostic, not a proof that the lowest-novelty encoder preserves the most useful acoustic information.

The adaptive codebook additionally reports prototype count, accepted frames, created/reused IDs, last-256 novelty, mean match distance and saturation state.

## State safety

The adaptive codebook and the `audio_adaptive` entries in `equinox_v06_memory.json` form one learned identity system. Do not reset/delete the codebook alone while retaining adaptive memory, because the stable numeric IDs could later refer to different learned prototypes.

## Build

Application ID remains `de.equinox.v05`.
Version: `0.6-step12-adaptive-quantizer` (versionCode 26).

The modified MainActivity was syntax-checked against Android stubs; MemoryModel, the adaptive quantizer and benchmark manager were compiled together with Kotlin stubs. A synthetic test kept repeated 440 Hz frames on one adaptive ID, 880 Hz on another, and noise on a third while remaining amplitude-insensitive in the tested sine case. A full Android build still has to be done in Android Studio because this environment has no Android SDK.
