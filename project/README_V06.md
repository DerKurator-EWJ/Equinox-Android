# Equinox v0.6 — Step 17: Continuous Audio I/O

Step 17 closes the audio loop: microphone capture may continue while Equinox simultaneously synthesizes its current learned state through a streaming orchestra.

## New in Step 17

- **ContinuousOrchestraEngine** uses `AudioTrack.MODE_STREAM` at 48 kHz. It is oscillator-only: no sound library, samples or media assets.
- LIVE and HYBRID now have a **CONTINUOUS ORCHESTRA START/STOP** control. Starting output no longer stops the microphone.
- The orchestra is updated from the read-only `SonificationSnapshot` + `WorkingSetSnapshot`, so output remains separate from learning.
- Up to six current audio macros become continuously synthesized voices. Activation, expectation, relevance, abstraction and overexchange affect amplitude, pitch drift and harmonic content.
- **Android AcousticEchoCanceler** is attached to the active `AudioRecord` session when the device exposes it and is enabled only while Continuous I/O + feedback protection are active.
- **AudioSelfOutputSuppressor** adds a second, Equinox-specific guard. Because the oscillator frequencies are known, it conservatively projects those current fundamentals/harmonics out of each microphone PCM frame before FFT, DIRECT or ADAPTIVE encoding.
- The same cleaned PCM frame is fed to all three encoders during the parallel convergence benchmark, preserving the fair-comparison rule.
- Feedback protection can be disabled from LIVE or HYBRID. Both controls are synchronized.
- The UI reports the active voice count, AEC state and the most recent self-output suppression amount.

## Important limitation

This is feedback **reduction**, not mathematically perfect source separation. Android AEC quality depends on the device/audio route, and the tonal self-filter can also attenuate an external sound that happens to occupy the same frequency as an Equinox voice. For clean learning while listening to the orchestra, wired/Bluetooth headphones remain the best practical test route.

## Data semantics

The audio output is still projection-only. Synthesized samples do not directly enter MemoryModel. Only microphone PCM is eligible for learning, after the optional feedback guard. No recalled/predicted macro becomes a real observation merely because the orchestra played it.

## Compatibility

- Application ID remains `de.equinox.v05` so this continues the same app line.
- Existing `equinox_v06_memory.json` and the adaptive quantizer file remain compatible.
- Version: `0.6-step17-continuous-audio-io`, versionCode 31.

## Build

Open **Equinox_v06_Step17_ContinuousAudioIO** itself in Android Studio and use **Build > Build APK(s)**.

This environment does not contain the Android SDK, so the full Android Gradle APK build was not executed here. The new continuous engine and feedback suppressor were Kotlin-compiled against API-shaped stubs, and MainActivity was separately compile-checked against the existing Android/AndroidX stub harness.

## Suggested phone test

1. Install/update the app.
2. Start AUDIO in LIVE and let a few audio macros activate.
3. Start CONTINUOUS ORCHESTRA while the microphone stays active.
4. Confirm `Audio: AKTIV` remains visible and the Continuous I/O status reports active voices.
5. With feedback protection ON, check whether `SelfGuard` reports removed components while external speech/noise still changes the learned state.
6. Repeat with headphones. That run is the cleaner reference for whether the orchestra is reacting to external input rather than recursively learning its own loudspeaker output.
