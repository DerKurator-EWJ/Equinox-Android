# Equinox v0.5 — Step 08

Step 08 adds sequence memory and sequence compression.

## New
- Interned sequence memory (trie/DAG-like parent + next-token representation).
- Repeated chains do not get stored as raw copies; one sequence code increases its weight.
- Sequence depth is capped at 6 for real-time stability.
- Sequence memory exists independently on each abstraction stream.
- Stable sequences are promoted into macro tokens of the next abstraction level.
- Promoted sequence macros can themselves participate in higher-level sequences.
- Existing Step 07 memory is migrated automatically.
- The UI shows:
  - number of sequence codes
  - promoted/compressed sequences
  - longest represented sequence
  - strongest sequence weight
- Sequence-derived macro nodes get a distinct spindle/chain glyph.
- Sequence-node count is capped at 12,000 to prevent runaway RAM/storage growth.

## Concept
A recurring chain such as X → C → E → Y is represented by a short internal node chain:
(parentSequenceId, nextTokenId, weight)

Repeating the same chain increases weight instead of duplicating the raw chain.

## Important
This is an online memory/compression experiment. A high sequence weight means recurrence, not meaning, truth, or semantic importance.

## Install
Open this folder as a new Android Studio project, generate the APK and install it over the existing `de.equinox.v05` app.
