# Equinox v0.6 — Step 06: Structural Ranks

Basis: v0.6 Step 05.

## New in Step 06

- Read-only multidimensional structural profile for every macro.
- RPG-style rank ladder:
  - `F -> E -> D -> C -> B -> A -> S -> SS -> SSS -> Ω -> Ω+1 -> Ω+2 -> ...`
- Rank is **derived**, not a new learning rule. It does not create relations or alter macro promotion.
- Rank uses four separate evidence dimensions:
  - recurrence — how often the macro itself was activated,
  - reuse — how often higher macros reuse it as a component,
  - relation support — accumulated weight of its learned macro neighborhood,
  - specificity — how concentrated vs. generic its relation context is.
- Abstraction level also contributes to the slowly growing structural score.
- Raw counters are log-compressed in the score, so one huge occurrence counter cannot instantly dominate every other dimension.
- `Ω+n` has no hard upper rank; each further Omega step requires additional logarithmic structural evidence.
- Parent-use counts are held in a compact composition index; no new all-to-all graph is introduced.
- Rank calculation is lazy/on-demand for output nodes and the bounded active working set.
- LIVE/MEMORY canvas now shows A-rank and above as labels; S/SS/SSS/Omega nodes gain an extra rank halo.
- Node size gets a capped rank contribution.
- Orchestra uses rank only as a small output modulation of presence/harmonic richness.
- Memory/Lab text shows the highest currently active rank and score.

## Important interpretation

The rank means **structural maturity in Equinox**, not truth, semantic importance, intelligence, or physical significance.
A very common generic pattern can have high recurrence but low specificity; a reusable selective pattern can gain rank through a different evidence mix.

## Compatibility

The persistent memory file remains `equinox_v06_memory.json` and Step 05 memory loads directly.
Rank values are reconstructed from existing macro/relation data; they do not need a separate save format.

## Build

Open exactly `Equinox_v06_Step06_StructuralRanks` as the Android Studio project and use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05` so it updates the same installed app when signed with the same key/debug key.

Version: `0.6-step06-structural-ranks` (versionCode 20).
