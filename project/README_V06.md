# Equinox v0.6 — Step 07: Template / Slot Abstraction

Basis: v0.6 Step 06 Structural Ranks.

## New in Step 07

Step 07 adds the first structural generalization layer above exact macro sequences.

If stable macros exist such as:

- `[A, B, C, D]`
- `[A, B, C, E]`
- `[A, B, C, F]`

Equinox can now infer a compact structural template:

- `[A, B, C, □]`

The slot does **not** mean a noun, object, fruit, word or semantic category. It only means:

> multiple already-learned stable macro patterns share the same fixed structure and differ at this position.

That keeps the rule purely structural and data-driven.

## Efficiency

There is no pairwise all-to-all macro comparison.

Each newly promoted stable macro contributes a bounded number of masked signatures, one per possible slot position. Example:

`[A,B,C,D] -> [□,B,C,D], [A,□,C,D], [A,B,□,D], [A,B,C,□]`

Those signatures are indexed directly. A later macro with the same masked signature lands in the same bucket in approximately O(pattern length) work rather than O(number of macros²).

Limits:

- template input patterns must contain at least 3 tokens,
- at least 2 different observed slot variants are required,
- the template index is bounded to 12,000 buckets,
- templates do not create a second relation graph.

## Persistence

Templates are deliberately **derived from the stable macro memory** rather than saved as a second authoritative database.

`equinox_v06_memory.json` remains compatible with Step 05/06. On load, the template index is rebuilt from the existing macro structures. This avoids duplicating the same structural information and keeps migration simple.

## UI / diagnostics

LIVE / MEMORY / LAB memory statistics now include:

- learned template count,
- currently active templates,
- total observed slot-variant links,
- widest template slot (number of alternatives).

Consolidation reports how many new templates became valid after newly promoted macros were indexed.

## What is intentionally NOT done yet

- Templates do not carry semantic labels.
- A slot does not yet become its own learned category.
- Templates do not yet replace their matching macro sequence as a higher token.
- No grammar is hard-coded.
- Templates do not alter structural ranks yet.

This step only establishes the sparse, testable mechanism for discovering substitutable positions.

## Verification performed here

The modified Kotlin core was compiled against JVM stubs for Android/JSON dependencies. A synthetic sequence test using repeated `[10,20,30,40]` and `[10,20,30,50]` patterns produced stable macros and inferred the expected `[10,20,30,□]` template family.

A complete Android APK build is not possible in this environment because the Android SDK/build tools are not installed.

## Build

Open exactly `Equinox_v06_Step07_TemplateSlots` as the Android Studio project and use **Build > Build APK(s)**.

Application ID remains `de.equinox.v05`, so it updates the same installed app when signed with the same key/debug key.

Version: `0.6-step07-template-slots` (versionCode 21).
