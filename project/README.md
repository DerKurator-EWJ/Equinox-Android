# Equinox v0.6 — Step 14: Memory Competition

Open this exact folder as a new Android Studio project and build the APK from it.

This step keeps Step 13 and adds bounded, dependency-safe memory competition:

- stale leaf macros compete only inside the same modality and abstraction level;
- macros used by higher macros are protected;
- active/current-context macros and mature S+ structures are protected;
- stream capacity has a soft target and a hard pressure threshold;
- removed structures can be relearned later if enough evidence returns;
- relations/candidates that depend on a removed macro are cleaned safely;
- competition runs automatically during consolidation and can also be triggered manually in LAB;
- the UI reports protected/pruned macros, relations, candidates and pressured groups.

Application ID: `de.equinox.v05`
Version: `0.6-step14-memory-competition` / versionCode 28

The full Android APK was not built in the generation environment because no Android SDK is installed there. The new competition policy is pure Kotlin and the project source is intended to remain load-compatible with the existing `equinox_v06_memory.json` format.
