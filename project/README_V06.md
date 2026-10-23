# Equinox v0.6 — Step 09: Multidimensional Relevance

Basis: v0.6 Step 08 Substitution Classes.

## New in Step 09

Step 09 separates structural importance into independent read-only dimensions instead of treating one raw weight as "importance".

For every macro Equinox now derives:

- **Frequency** — normalized recurrence / occurrence.
- **Reuse** — how often higher macros reuse this macro as a component.
- **Relation support** — how much learned macro-relation evidence exists.
- **Context entropy** — how broadly relation evidence is distributed over contexts.
- **Specificity** — how selective/context-focused the learned relation support is.
- **Persistence** — whether the macro remained active across a longer observation span.
- **Generality** — broad distributed contextual use.
- **Structural relevance** — a bounded composite 0..1 used by output and diagnostics.

These are not semantic labels. Equinox still does not know that a token is a noun, object, word, fruit, etc.

## Frequency is not importance

The relevance composite intentionally gives raw frequency only a small share of the score.

A very frequent macro with broad/high-entropy context can therefore be less relevant than a less frequent macro that is:

- reused by higher structures,
- strongly supported by relations,
- selective in context,
- persistent across time.

A synthetic verification test used:

- a very frequent, broad-context pattern: relevance ≈ 0.426
- a less frequent, selective/reused pattern: relevance ≈ 0.868

The second pattern therefore outranked the first even though it occurred far less often.

## Rank vs relevance

The RPG rank remains:

`F → E → D → C → B → A → S → SS → SSS → Ω → Ω+1 → ...`

But Step 09 explicitly separates:

- **rank** = accumulated structural maturity / abstraction,
- **relevance** = bounded multidimensional read-only structural importance estimate.

Neither value directly rewrites learning.

## Temporal persistence

Macro memory now stores both:

- `firstActivatedObservation`
- `lastActivatedObservation`

This allows persistence to be estimated from the span across which the structure continued to recur.

Older Step 05–08 memory files remain readable. When `firstActivatedObservation` is missing, Step 09 falls back conservatively to the previous last-activation value.

## Output changes

The LIVE/MEMORY canvas now uses relevance as a secondary presence signal:

- relevant structures gain slightly more visual presence,
- general/context-broad structures can receive a wider diffuse halo,
- the footer reports mean active relevance.

The library-free orchestra also uses relevance as one input to voice presence. Generality slightly changes harmonic complexity. No sample library is added.

The renderer and orchestra remain read-only consumers of the core.

## UI / diagnostics

Memory statistics now additionally show:

- mean active relevance,
- highest active relevance,
- mean active context entropy,
- highest active generality.

This is intended for comparing patterns that are merely common with patterns that are structurally useful.

## Efficiency

Step 09 does not introduce a new persistent graph.

Relevance is calculated lazily from data that already exists:

- macro occurrence count,
- parent reuse index,
- sparse relation weights,
- first/last activation observations.

The cost is local to the macro being projected or inspected. There is no all-to-all relevance calculation.

## Persistence

The same `equinox_v06_memory.json` is used.

The save format now writes `formatVersion = 6009` and the first activation observation for each macro. Existing Step 05–08 files can still be loaded.

## Verification performed here

The Step 09 core (`MemoryModel.kt`, `StructuralRank.kt`, `StructuralRelevance.kt`) compiles successfully against JVM Android/JSON stubs.

The output layer (`EngineOutputState.kt`, `EquinoxOutputView.kt`, `OrchestraSonifier.kt`) also compiles successfully against Android graphics/media stubs.

A direct relevance test confirmed that a less frequent but selective/reused pattern can score higher than a massively frequent broad-context pattern.

A complete Android APK build is still not possible in this environment because the Android SDK/build tools are not installed.

## Build

Open exactly `Equinox_v06_Step09_MultidimensionalRelevance` as the Android Studio project and use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05`, so it updates the same installed app when signed with the same key/debug key.

Version: `0.6-step09-multidimensional-relevance` (versionCode 23).
