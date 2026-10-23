# Equinox v0.6 — Step 11: Encoder Convergence Lab

Basis: v0.6 Step 10 Dual Audio Encoder.

## Purpose

Step 11 turns the two audio encoders into a fair, live A/B hard test.
The goal is not to decide by intuition whether FFT or DIRECT-ID is "better", but to
measure how each discrete representation behaves when both receive the exact same
2048-sample PCM frames.

The benchmark is deliberately separate from the Equinox learning rules. It only
observes the two identity streams and reports diagnostics. It does not change IDs,
macro promotion, relations, overexchange, templates, substitution classes, ranks or
relevance.

## Parallel hard test

Open **LAB -> PARALLELER KONVERGENZTEST** and enable:

`Beide Encoder parallel lernen + messen`

While enabled, every microphone frame is processed by both encoders:

`same PCM frame -> FFT-ID`

`same PCM frame -> DIRECT 16-bit ID`

Both identities are then learned by the existing Equinox core in their already
separate modalities:

- FFT: `audio`
- Direct: `audio_direct`

Because the modalities remain separated, their sequence candidates, macro signatures
and persistent macro relations do not merge. The non-selected stream is fed first
and the currently selected LIVE encoder is fed last, so the momentary output working
set remains centered on the encoder the user chose to display.

This diagnostic mode intentionally does approximately twice the encoder work and can
also create roughly two audio observations per PCM frame. It is therefore a hard-test
mode, not the normal low-power operating mode.

## Metrics

The benchmark reports separately for FFT and DIRECT:

- accepted frames / common input frames
- number of unique discrete IDs
- new IDs per 1000 accepted frames
- ID novelty in the latest 256 accepted events
- transition novelty in the latest 256 transitions
- revisit rate and immediate-repeat behaviour internally
- Shannon entropy of the session ID distribution
- quant-code delta since test start
- macro delta since test start
- macro formation per 1000 accepted frames internally

DIRECT has a silence gate, so its accepted-frame count may be lower than FFT. This is
shown explicitly rather than hidden.

## What “convergence” means here

The app gives only a narrow provisional hint based on recent identity novelty and
recent transition novelty.

A lower novelty rate means that an encoder is reusing its existing vocabulary more
consistently in the current environment. This does **not** prove that it preserves
more acoustic information, recognizes meaning better, or is universally superior.
A very coarse encoder can converge quickly simply because it collapses many different
sounds into the same ID.

For that reason the LAB text explicitly labels the result as an ID-convergence hint,
not a quality verdict.

## Test workflow

1. Tap **TEST NEU** to reset the benchmark baseline and enable the parallel test.
2. Start audio on LIVE.
3. Let the same acoustic environment run for a meaningful period.
4. Repeat familiar sounds as well as varied sounds.
5. Return to LAB and compare the live metrics.
6. Tap **CSV** to write `equinox_v06_encoder_benchmark.csv` into the app's internal files.
7. Use **STOP** to end the parallel benchmark; normal single-encoder operation remains available.

The benchmark session itself is runtime-only in this step. The CSV export persists the
sampled comparison. Existing Equinox memory continues to use `equinox_v06_memory.json`.

## Existing architecture retained

After each encoder produces an ID, the same v0.6 core remains in place:

`Quant ID -> temporary pattern evidence -> stable macro -> lazy active frontier -> sparse relations -> overexchange -> templates -> substitution classes -> rank/relevance -> output`

No level-0 relation graph has been reintroduced.

## Verification performed here

- `MemoryModel.kt`, rank/relevance support, `DirectAudioQuantizer.kt` and the new
  `EncoderBenchmarkManager.kt` compile successfully against JVM Android/JSON stubs.
- A synthetic 512-frame benchmark produced stable reuse after the initial vocabulary
  had been seen and correctly tracked independent FFT/DIRECT unique-ID and macro deltas.
- The modified `MainActivity.kt` compiles against a dedicated Android/AndroidX/app API
  stub set, including the new parallel-test UI and dual-learning audio branch.

A complete Android APK build is still not possible in this environment because the
Android SDK/build tools are not installed.

## Build

Open exactly `Equinox_v06_Step11_EncoderConvergenceLab` as the Android Studio project
and use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05`, so it updates the same installed app when
signed with the same key/debug key.

Version: `0.6-step11-encoder-convergence-lab` (versionCode 25).
