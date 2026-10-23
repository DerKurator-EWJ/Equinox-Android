# Equinox v0.6 — Step 08: Substitution Classes

Basis: v0.6 Step 07 Template / Slot Abstraction.

## New in Step 08

Step 08 turns repeated slot substitution into a higher structural layer.

Step 07 could infer a template such as:

- `[A, B, C, D]`
- `[A, B, C, E]`
- -> `[A, B, C, □]`

Step 08 now asks a stricter question:

> Do the same macro tokens repeatedly replace each other in more than one independent learned role?

If macro tokens X and Y can substitute for each other in at least **two distinct learned template contexts**, Equinox records sparse structural-role evidence between them. Connected tokens with sufficient evidence form a derived **substitution class**.

This does **not** mean that Equinox knows a noun, fruit, object, word, meaning, or semantic label. It only means that the learned structures repeatedly treated those macro tokens as structurally interchangeable.

## Important boundary

Raw quant/frequency IDs do not enter substitution classes.

Only **macro tokens** can become class members. This keeps the fixed quant alphabet as a low-level encoder and prevents broad frequency-bin combinations from flooding the structural class layer.

## Efficiency

There is still no global all-to-all comparison.

When a new stable macro joins an existing one-slot template bucket, Step 08 compares its slot variant only with the variants already present in that same local bucket.

Limits:

- a pair needs at least 2 independent template-role matches,
- at most 24 existing variants per template contribute new pair evidence,
- the sparse pair-evidence index is bounded to 24,000 pairs,
- classes are derived/cached and are not a second persistent graph,
- class rebuilding happens only when template-role evidence changes.

This keeps the expensive work tied to actually observed structural roles rather than total memory size.

## Memory mode

Ordinary learned macro relations remain the first path for MEMORY traversal.

If a memory path reaches a relation dead end, Step 08 may use a member of the same learned substitution class as a **read-only structural alternative** before falling back to an unrelated strong macro.

This does not create new observations, new relations, or new class evidence.

## Persistence

Substitution classes are derived from stable macro/template memory and are not separately saved.

`equinox_v06_memory.json` therefore remains compatible with Step 05–07. On load, the template and substitution indexes are reconstructed from the existing macros.

## UI / diagnostics

Memory statistics now show:

- substitution class count,
- currently active substitution classes,
- number of unique class members,
- strongest observed shared-role support.

Consolidation also reports newly formed substitution classes.

## Verification performed here

The Step 08 `MemoryModel.kt` and `StructuralRank.kt` compile successfully against JVM stubs for Android/JSON dependencies.

A synthetic multi-round training test produced higher-level macros, learned templates and then a substitution class only after two independent shared structural roles were available. The test reached:

- 287 macro nodes,
- 43 learned templates,
- 1 substitution class,
- pair support 2.

A complete Android APK build is not possible in this environment because the Android SDK/build tools are not installed.

## Build

Open exactly `Equinox_v06_Step08_SubstitutionClasses` as the Android Studio project and use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05`, so it updates the same installed app when signed with the same key/debug key.

Version: `0.6-step08-substitution-classes` (versionCode 22).
