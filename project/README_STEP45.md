# Equinox v0.6 — Step 45: Base / Personal / Derived Knowledge Architecture

Step 45 separates **where knowledge comes from** from **how abstract it is**. RPG tier remains abstraction depth only. Knowledge provenance is now a separate axis:

- `BASE` — immutable app/global knowledge from an installed base pack.
- `PERSONAL` — knowledge learned by the active on-device profile.
- `DERIVED` — profile-owned knowledge whose actual composition bridges `BASE` and profile-owned knowledge.

## Physical layer separation

The runtime can mount two indexed stores:

- `equinox_v06_base/store/` — global immutable BASE store; never archived inside a memory profile.
- `equinox_v06_store/` — existing profile-owned PERSONAL/DERIVED store.
- `equinox_v06_knowledge_layers.bin` — compact profile sidecar with provenance and the required base-pack identity/version.
- `equinox_profiles/` — portable profile archives from Step 37.

Old Step 23–44 stores remain readable. If a profile has no provenance sidecar, all of its existing macros are interpreted as `PERSONAL`.

## Stable identity ranges

Step 45 reserves the upper positive Int range for BASE macro identities:

- PERSONAL/DERIVED: starts at `1_000_000` and must stay below `1_500_000_000`.
- BASE: `1_500_000_000 .. Int.MAX_VALUE-1024`.

This gives future converted base packs a stable ID namespace without colliding with normal on-device learning.

## BASE is immutable at runtime

A mounted BASE macro can activate, participate in recall/prediction, and seed PERSONAL/DERIVED learning, but Equinox does not mutate its identity:

- activation does not increment BASE macro weight or timestamps;
- Memory Competition cannot prune BASE macros;
- BASE relations are mounted as immutable priors;
- if local experience reinforces a BASE relation, only the positive PERSONAL delta is saved in the profile store;
- saving a profile never copies BASE macros into that profile.

## DERIVED provenance

When local learning creates a macro, its child composition is inspected:

- only local/profile children -> `PERSONAL`;
- only BASE children -> still `PERSONAL` because the newly observed composition is a local experience;
- BASE + PERSONAL/DERIVED children -> `DERIVED`.

The provenance sidecar retains the source macro IDs. If a profile depends on BASE, it also records the required base pack ID/version.

## Base updates and compatibility

A base manifest contains `packId`, `version`, and a `stableIds` guarantee. A profile that references BASE can mount when:

1. the required pack ID matches;
2. installed version is at least the required version;
3. the pack guarantees stable IDs.

If that contract is not satisfied, base-dependent profile macros are quarantined from the live graph. The profile is not destructively rewritten while incompatible, so its knowledge can recover when the correct BASE returns.

## Genuine blank mode

Settings -> MEMORY now exposes **BASE KNOWLEDGE aktiv**.

- BASE ON: global base is mounted alongside the active personal layer.
- BASE OFF: the app runs without the global base.
- `BASE OFF + NEW` gives a genuine zero-knowledge personal start.

Disabling BASE on a profile that already depends on it makes that profile read-only/incompatible until BASE is restored. This prevents learning against missing dependencies and avoids silently corrupting derived knowledge.

## Profiles

Step 37 profile semantics are retained:

- PERSONAL/DERIVED store + adaptive codebooks + provenance sidecar belong to the profile.
- BASE remains global and is never duplicated into `.eqx` profile archives.
- changing/updating BASE therefore does not overwrite personal learning.

## Scaling boundary

A large mounted BASE must not inflate the on-device learner's temporary candidate horizon merely because many immutable macros exist. Step 45 therefore bases hot candidate/horizon pressure on the profile-owned macro count; BASE knowledge is expected to be reached through routing rather than by widening brute-force local search. BASE macros also do not count toward PERSONAL memory-competition pressure.

## Diagnostics

Memory diagnostics now report:

- BASE / PERSONAL / DERIVED macro counts;
- installed base pack ID/version;
- BASE ON vs `AUS/BLANK`;
- base compatibility status;
- combined layered store bytes/token reads.

## Verification

The changed core compiles against JVM Android/JSON stubs. Synthetic layer tests verified:

- one BASE + one PERSONAL + one DERIVED macro mount with correct provenance;
- `saveNow()` persists only PERSONAL/DERIVED and does not leak BASE into the profile store;
- personal reset leaves BASE installed;
- BASE OFF + reset produces a truly empty runtime, and BASE can be remounted;
- an incompatible base pack quarantines dependent knowledge and `saveNow()` does not destructively rewrite the profile.

Step 46 can now build the external knowledge-conversion/import interface on top of this stable layer boundary without changing the existing profile format again.
