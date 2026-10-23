# Equinox v0.6 — Step 15: Predictive Recall

Step 15 builds on Step 14 Memory Competition and adds a sparse, directional prediction layer without turning the memory back into a full-graph simulation.

## New in Step 15

- **Directional next-macro prediction:** when a macro is active, Equinox looks only at its already learned outgoing macro transitions.
- **Top-k only:** at most 4 likely next macros are materialized into the bounded working set.
- **No full graph scan:** predictions are addressed through the existing local relation index.
- **Prediction becomes B-state expectation:** forecast macros enter the same expectation channel already used by overexchange.
- **No self-fulfilling learning:** a prediction is never written back as an observation or relation just because it was predicted.
- **Top-k hit diagnostics:** on the next real macro event, Equinox checks whether the actual macro was among the previous predictions.
- **Memory mode follows learned direction:** memory-only playback prefers the strongest predicted continuation before falling back to ordinary graph traversal.
- **Canvas output:** predicted transitions appear as thin warm expectation arcs; exchange remains a separate cyan/magenta layer.
- **Orchestra:** no new sound library is used. Predicted macros already affect sound through their expectation/B-state.

## Operational rule

For active macro `M_i`, only learned directed transitions `M_i -> M_j` are considered. If a transition has enough support, prediction confidence is derived from:

`relative outgoing share × support confidence`

This is a practical sequence predictor, not semantic understanding and not a claim about physical causality.

## Memory compatibility

The same `equinox_v06_memory.json` structure remains usable. Step 15 derives predictions from the directional transition counts already stored in that file. The save format marker is updated to `60_15`, but no destructive migration is required.

## Build

Open the inner folder `Equinox_v06_Step15_PredictiveRecall` directly in Android Studio and build the APK as usual.

The Android SDK is not installed in the generation environment, so a full Android Gradle/APK build was not performed here. The modified core was compiled with Kotlin/JVM stubs, the output canvas was compiled separately, and a synthetic repeated-sequence test produced stable predictions with a measured top-k hit rate.
