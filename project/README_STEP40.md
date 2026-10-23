# Equinox v0.6 — Step 40: Recall / Prediction Hardening

Step 40 controls how much already learned structure is materialized around the current state. It does **not** change the persistent memory format and it does not make recall/prediction into learning events.

## 1. Information-aware bounded recall

Step 39 made familiar pattern recognition cheaper, but relation recall could still materialize a large fraction of a broad local neighborhood when relation mass was flat.

Step 40 keeps the same sparse `relationIndex`, then ranks only that source macro's local neighbors using four signals:

- conditional relation share;
- support confidence;
- structural relevance;
- positive local information gain.

The information-gain term compares the learned relation share with the target's recurrence prior inside the same local neighborhood. It therefore rewards an association that is unusually diagnostic for the current source without turning global popularity into relevance.

After ranking, a relative score gate removes the weak tail and a logarithmic transient budget limits materialization. The budget grows with local complexity, but sub-linearly, so a flat neighborhood cannot make live recall grow linearly with total learned relations.

Persistent relations are never deleted by this gate. Only the momentary B-state / Working Set is reduced.

## 2. Prediction candidate control

Directional prediction still uses only observed outgoing transitions from the exact source macro, abstraction level and modality.

Step 40 adds two controls above the existing minimum-support/minimum-confidence filter:

1. candidates too weak relative to the strongest current forecast are dropped;
2. selected prediction breadth grows logarithmically with the number of viable outgoing alternatives.

The remaining forecast is still probability-mass aware, but it can no longer satisfy the mass target by materializing an arbitrarily broad weak tail.

Diagnostics expose `raw -> selected` prediction candidates.

## 3. Prediction baseline and lift

The old metric counted whether the actual next macro appeared anywhere in the selected prediction set. That remains available as **coverage**, but a broad set can make that number look better than the real forecast quality.

Step 40 therefore adds Top-1 evaluation and a context-free baseline.

For each live forecast:

- **Model Top-1** = strongest directional prediction from the current source;
- **Baseline Top-1** = strongest recurrent macro in the same level/modality stream, without using the current source relation context.

The baseline keeps only two lightweight leaders per stream, so excluding the current source does not require a full-memory scan.

`Prediction Lift = model Top-1 hit rate - baseline Top-1 hit rate`

A positive lift means the learned directional context predicts the next state better than simply guessing the stream's generally common macro.

## 4. Weak temporary candidate expiry

Pattern candidates are temporary evidence, not permanent memory. Previously weak one-off candidates could remain until a consolidation decay or large candidate-pressure event.

Each candidate now records its first/last position in its **own stream event count**. Periodic lightweight sweeps remove candidates with at most three hits once they have been inactive beyond an adaptive idle window. More-supported candidates retain more time to recur; stronger evidence continues through the normal consolidation/pressure path. Pressure cleanup also starts at 1.5× the adaptive soft target instead of waiting for 2×.

This uses stream-event position, not wall-clock time, so audio/vision rates do not become semantic time.

## 5. Cross-modal live boundary

The existing cross-modal boundary remains intact: joint evidence is registered only from real sensor macro activation through `observeCrossModalEvidence()`. Retrieved/predicted B-state still cannot manufacture cross-modal coincidence evidence. Step 40 does not weaken that boundary.

## Diagnostics

Settings/LAB now report:

- local recall candidates -> materialized count;
- rejected recall frontier count;
- mean positive information gain of materialized recall;
- current `retrieved / direct-active` Recall Factor;
- number of weak temporary candidates expired;
- prediction `raw -> selected` breadth;
- prediction coverage hit rate;
- model Top-1 hit rate;
- baseline Top-1 hit rate;
- Prediction Lift in percentage points.

## Persistence / profiles

No new persistent file or field is introduced.

The recall gate, baseline leaders, prediction probes, candidate ages and diagnostics are runtime/derived state. Learned macros, relations, indexed-store layout, adaptive audio/visual codebooks and Step-37 `.eqx` profile format remain unchanged.

## Verification performed

- `RecallPredictionPolicy.kt` plus the modified `MemoryModel.kt` compiled successfully against the existing Step-39 core/JVM Android+JSON stubs.
- Synthetic repeated-input integration produced persistent macros and active Step-39 routing.
- The hardened model recorded 248/248 contextual Top-1 hits versus 124/248 baseline hits in that deterministic synthetic stream, corresponding to `+50.0 pp` prediction lift for the test.
- A long one-off novelty stream expired 1,964 weak temporary candidates while the earlier 1.5× soft-target pressure cleanup kept the remaining temporary pool bounded; persistent macros/relations were untouched.
- Invariants checked in the integration run: materialized recall never exceeded local recall candidates, and selected prediction breadth never exceeded raw prediction candidates.

These numbers are synthetic verification, not expected real-world accuracy. A full Android APK build was not run here because this execution environment has no Android SDK; the modified core was compile-checked with Kotlin/JVM stubs instead.

## Build

Open `Eq40_RecallPredictionHardening_Project` directly in Android Studio and build/install as before. Application ID remains `de.equinox.v05`, so it updates the existing debug installation when signed with the same debug key.

Version: `0.6-step40-recall-prediction-hardening` (`versionCode 55`).
