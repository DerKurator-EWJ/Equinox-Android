# Equinox v0.6 — Step 14: Memory Competition / Forgetting

Step 14 keeps the complete Step-13 architecture and adds the first deliberate forgetting mechanism. The goal is not to delete old information arbitrarily; it is to stop the learned macro dictionary from growing monotonically forever.

## Core rule

Only **leaf macros** are eligible for automatic removal. If a macro is used as a constituent of a higher macro, it is structurally protected. This preserves the recursive recipes needed to reconstruct learned abstractions.

Competition is local to a `(modality, abstraction-level)` stream. A stream has:

- soft target: 320 macros
- hard pressure threshold: 384 macros
- at most 128 removals in one maintenance pass

When a stream is under pressure, the lowest-utility eligible leaf macros compete first. Manual LAB competition can also remove a very small number of extremely stale/low-utility leaf macros before hard pressure occurs.

## Utility is multidimensional

A macro is not judged by occurrence count alone. `MemoryCompetitionPolicy.kt` combines:

- recurrence,
- structural relevance,
- relation mass and relation breadth,
- template-role reuse,
- recency,
- abstraction level,
- structural maturity/rank.

The score is only a storage-management heuristic. It is not treated as semantic meaning or truth.

## Hard protection

Automatic competition cannot remove a macro when it is:

- a child/component of a higher macro,
- currently active,
- the current previous-context token for a live stream,
- structurally mature at S rank or above,
- reused in many template roles.

This means pruning is conservative by design.

## Dependency cleanup

When a leaf macro is removed, Step 14 also removes:

- persistent macro relations touching that ID,
- overexchange memory touching that ID,
- temporary pattern candidates that contain that ID,
- momentary working-state references,
- stale rolling-buffer references.

Then composition, template, substitution and relation indexes are rebuilt from surviving macros. This work happens at maintenance/consolidation time, never on each audio frame.

A forgotten macro is not permanently banned. If the same structure reappears strongly enough, the normal candidate/compression pipeline can learn a new macro identity for it.

## UI / diagnostics

LAB now has `MEMORY COMPETITION JETZT` and the memory statistics show:

- competition runs,
- total macros/relations/candidates removed,
- removals in the most recent run,
- protected macros,
- number of pressured modality/level groups.

Timed/manual consolidation also runs the conservative automatic competition pass.

## Existing systems retained

Step 14 retains FFT, fixed DIRECT and learned ADAPTIVE audio encoders, adaptive compression hierarchy, lazy active working set, overexchange, recursive L1-L4 macros, templates/slots, substitution classes, multidimensional relevance, F→SSS→Ω+n ranks, LIVE/MEMORY/LAB, visual canvas, orchestra and encoder convergence testing.

Existing `equinox_v06_memory.json` data remains structurally compatible; Step 14 changes maintenance policy rather than the persisted macro schema.

## Build

Application ID: `de.equinox.v05`

Version: `0.6-step14-memory-competition` (versionCode 28)
