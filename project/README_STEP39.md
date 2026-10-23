# Equinox v0.6 — Step 39: Hierarchical Routing

Step 39 turns the learned macro hierarchy into an address/routing structure for familiar input. It does **not** add a second semantic graph and it does not change the persistent memory format.

## Why

Through Step 38, `feedToken()` still materialized a complete signature for every possible tail length inside the adaptive pattern horizon. Direct hash lookup avoided a global macro scan, but a larger learned horizon could still mean more tail work even for familiar input.

Step 39 changes the familiar path:

`input -> stream/tier -> two-token anchor -> learned spans -> exact macro signature -> macro activation`

Unknown structure still uses the ordinary candidate learner.

## HierarchicalRouteIndex

A new runtime-only `HierarchicalRouteIndex` is rebuilt from learned macro identities.

For every `(modality, source level)` stream it stores sparse two-token tail anchors. Under each anchor it records only the spans/signatures that actually exist in learned memory.

The router therefore never searches all macros and never walks the full long-term graph.

Higher abstraction streams use the same mechanism automatically because emitted macro IDs become the tokens of the next stream. Tier remains abstraction depth only; routing never makes a deep macro more important merely because it is deep.

## Familiar vs novel path

For each input token:

1. Probe only learned spans reachable from the current stream + two-token tail anchor.
2. If a known non-overlapping macro tail matches, activate the longest match.
3. Do not re-run candidate work for shorter tails already contained in that familiar macro.
4. Continue exploring all *longer* tails so novel context around familiar structure can still be learned.
5. If routing misses, fall back to the normal open candidate path.

This means learning is not frozen by familiarity:

`known core -> routed/compressed`

`new surrounding context -> still analyzed`

## Diagnostics

Settings/LAB memory diagnostics now expose:

- routing hits / queries and hit rate;
- mean matched familiar span;
- actual tail-signature checks;
- Step-38-equivalent tail checks avoided;
- route-index stream / anchor / macro-signature counts.

The intended long-term trend is:

`more reusable learned structure -> more routed hits -> fewer redundant tail checks for familiar input`

Novel input should show fewer routing hits and retain candidate analysis.

## Persistence / profiles

No new persistent file is introduced.

The router is an address index derived from the existing macro signatures. On indexed-store load it is rebuilt from macro headers without loading complete disk-backed token payloads. Newly promoted macros are added incrementally. After memory competition/pruning it is rebuilt from the surviving identities.

Therefore Step 37/38 `.eqx` profiles and the indexed long-term store remain format-compatible.

## Verification performed

- `HierarchicalRouteIndex.kt` compiled independently with Kotlin/JVM.
- Synthetic router test verified longest exact matches, anchor separation, non-match behavior and overlap/span limits.
- `MemoryModel.kt` plus its core dependencies compiled against lightweight Android/JSON JVM stubs.
- Synthetic repeated-input integration test produced 13 learned macros, then reached 45 routing hits and 45 avoided Step-38 tail checks while continuing to accept input/candidate processing.

A full Android APK build still requires the Android SDK/Gradle environment in Android Studio.

## Build

Open `Eq39_HierarchicalRouting_Project` directly in Android Studio and build/install as before. Application ID stays `de.equinox.v05`, so it updates the existing debug installation when signed with the same debug key. Version is `0.6-step39-hierarchical-routing` (versionCode 54).
