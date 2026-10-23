# Equinox v0.6 — Step 44: Hypno Audio / Visual Synchronisation

Step 44 makes HYPNO and Unified Audio two synchronized projections of the **same normalized read-only presentation state**.

## Core boundary

Nothing in Step 44 changes learning or persistent memory:

`MemoryModel -> WorkingSetSnapshot -> UnifiedPresentationState -> { HYPNO, Unified Audio }`

`UnifiedPresentationState` is ephemeral. It is never saved and never writes back into `MemoryModel`.

## One normalized presentation vector

Before Step 44, renderer and orchestra derived several related output quantities independently. Step 44 centralizes those calculations in `UnifiedPresentationState.kt`.

The shared normalized vector contains:

- activity
- expectation
- structural relevance
- relation energy
- signed exchange / overexchange polarity
- prediction confidence
- presentation-only novelty proxy
- crossmodal confidence
- abstraction-tier depth
- retrieval share
- direct live activation
- bounded working-set density
- combined memory energy

This means a state transition is no longer interpreted by two subtly different formulas before reaching image and sound.

## Shared semantics, modality-specific expression

The same value is allowed to manifest differently in each output modality:

| Unified state | HYPNO | Unified Audio |
| --- | --- | --- |
| activity | field presence / energy | presence / tempo drive |
| prediction | coherence | anticipatory ghost / harmonic organization |
| signed exchange | flow/tint polarity | relation phase / tension polarity |
| novelty | interference rim | transient brightness / breath / accent |
| crossmodal | color-family fusion | spectral brightness / breath |
| tier depth | spatial breadth | deeper/sub-weighted timbre, not pitch importance |
| retrieval share | calm broad memory depth | slower / more sustained memory bed |
| relation energy | deformation / exchange intensity | tension / relation pulse |

Tier remains abstraction depth only. Step 44 does not reintroduce a tier=importance mapping.

## Live synchronization

When the camera pipeline is active, Step 44 no longer lets audio independently sample a newer/older working set while a delayed camera frame is being displayed.

The Step-27 low-latency pipeline already chooses a `RenderPacket` containing:

`camera frame + nearest WorkingSetSnapshot`

Step 44 now sends that **same packet WorkingSetSnapshot** to both:

`GpuMemoryVideoView`

and

`UnifiedAudioEngine`

The existing presentation delay therefore applies semantically to the audiovisual state as a pair. Audio-only operation still samples the current working state normally because no camera presentation packet exists.

DREAM / memory-only playback similarly sends the same output snapshot to GPU and audio in the same refresh path.

## Audio changes

The Step-41 macro voice identity remains unchanged: macro pitch identity is still reconstructed from learned child composition.

Step 44 only maps the shared presentation vector onto transient musical behavior:

- novelty increases brightness/noise/transient accent;
- crossmodal confidence increases spectral fusion;
- deeper tier state reinforces low/sub texture rather than shifting base pitch;
- retrieval share calms tempo and adds sustained weight;
- signed exchange biases relation pulse phase;
- relation energy drives tension;
- activity/prediction drive presence and temporal motion.

There is still exactly one `AudioTrack` owner: `UnifiedAudioEngine`.

## Compatibility

- no persistent store schema change
- no profile migration
- no adaptive codebook migration
- no macro/relation/routing format change
- Step-42 LEARN / PLAY / DREAM boundaries remain unchanged
- Step-43 HYPNO / STRUCTURE renderer choice remains unchanged
- recording still taps the same Unified Audio PCM and the same GPU product surface

## Verification

Performed in this environment:

1. `UnifiedPresentationState.kt + GpuMemoryVideoView.kt` compile against Kotlin Android/OpenGL stubs.
2. `UnifiedPresentationState.kt + GenerativeOrchestraSynth.kt` compile against the tested Step-41 memory model classes.
3. A deterministic synchronization test constructed one WorkingSet and verified that the orchestra receives exactly the same normalized activity, prediction, novelty, crossmodal, relation, signed-exchange, tier-depth and retrieval-share values produced by `UnifiedPresentationState`.
4. Static source check confirms there is still exactly one `AudioTrack.Builder()` in the project.
5. The old novelty formula exists only once, inside `UnifiedPresentationState`, instead of being duplicated across output systems.

Test output:

`STEP44_OK A=0.600 P=0.750 N=0.363 X=0.650 Tier=0.371 Recall=0.500`

A full Android APK build and device/GLES/audio-latency test should still be performed in Android Studio because the current environment does not include the Android SDK/device audio stack.

## Version

`0.6-step44-hypno-av-sync` — versionCode 59
