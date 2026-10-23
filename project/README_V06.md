# Equinox v0.6 — Step 10: Dual Audio Encoder

Basis: v0.6 Step 09 Multidimensional Relevance.

## Purpose

Step 10 performs the audio-encoder hard test discussed during the v0.6 redesign.
The Equinox memory core should not depend on FFT as a fundamental representation.
Therefore the app now has two selectable sensor encoders that feed the same macro,
lazy-retrieval, overexchange, template, substitution and relevance architecture.

The two encoders are deliberately kept as separate alphabets so their memories do
not contaminate each other.

## Encoder A — FFT / frequency identity

The existing path remains available and is the default for backward compatibility:

`PCM -> FFT -> dominant frequency -> logarithmic frequency ID -> Equinox core`

It continues to use the existing positive frequency IDs and modality `audio`.
Existing v0.6 memories remain readable.

## Encoder B — DIRECT / fixed 16-bit acoustic identity

The new path does **not** call `Fft.magnitudes()`.
A 2048-sample PCM frame is quantized directly in the time domain using only five
coarse behaviour groups:

- zero-crossing rate: 4 bits
- normalized waveform roughness: 4 bits
- strongest tested autocorrelation lag: 3 bits
- autocorrelation strength: 3 bits
- coarse envelope trend: 2 bits

These fields form one 16-bit identity. Therefore the theoretical direct alphabet is
bounded to exactly **65,536 possible states**. It does not grow just because the app
runs longer.

Direct identities use negative IDs `-1 .. -65536` and modality `audio_direct`, so
there is no collision with legacy frequency IDs or the positive macro-ID space.
Silence below a small RMS threshold is displayed but is not learned.

This is intentionally a simple experimental encoder. It is not a claim that these
five features are a complete representation of sound.

## Same memory, different front end

After quantization both paths use the same pipeline:

`Quant ID -> temporary sequence evidence -> stable macro -> lazy active set -> sparse macro relations -> overexchange -> templates -> substitution classes -> relevance/rank -> output`

No new level-0 relation graph was added.

The core therefore remains independent of whether the sensor identity came from an
FFT frequency bin or a direct time-domain pattern.

## Switching encoders

Open **LAB -> AUDIO-ENCODER HARD TEST** and choose:

- `FFT`
- `DIRECT-ID`

Changing the encoder while audio is running first stops/consolidates the current
audio stream, preventing a rolling sequence from crossing between two alphabets.
The selected mode is saved in SharedPreferences.

The LIVE page shows the currently selected encoder.

FFT mode reports dominant Hz, frequency ID and FFT magnitude.
DIRECT mode reports pattern ID, RMS activity and the quantized time-domain feature
bins.

## Memory / diagnostics

The memory statistics now separately report:

- FFT quant codes / FFT macros
- Direct-ID quant codes / Direct-ID macros

This makes it possible to run the same environment through both encoders and compare
how quickly each representation stabilizes and compresses.

The same persistent file `equinox_v06_memory.json` is used. The save format is now
`formatVersion = 6010`. Step 05–09 data remains loadable; new direct IDs are simply
additional entries with modality `audio_direct`.

## Orchestra

Direct-ID codes do not store fake measured frequencies. For output only, the
orchestra deterministically projects a pitch from the direct ID / macro composition.
That pitch is a human-audible visualization and is not fed back into memory as a
physical property of the code.

## Verification performed here

- `MemoryModel.kt`, `StructuralRank.kt`, `StructuralRelevance.kt` and
  `DirectAudioQuantizer.kt` compile successfully against JVM Android/JSON stubs.
- A direct-quantizer test confirmed that a 440 Hz sine at two substantially different
  amplitudes maps to the same direct identity, while deterministic broadband noise
  maps to a different identity.
- The same test produced a different identity for an 880 Hz sine in the tested frame.

A complete Android APK build is still not possible in this environment because the
Android SDK/build tools are not installed.

## Build

Open exactly `Equinox_v06_Step10_DualAudioEncoder` as the Android Studio project and
use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05`, so it updates the same installed app when
signed with the same key/debug key.

Version: `0.6-step10-dual-audio-encoder` (versionCode 24).
