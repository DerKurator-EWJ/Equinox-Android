# Step 46.2 — Archive Vocabulary / Stable Live Diagnostics

## Goal

This is a product/UI pass only. It does not change MemoryModel semantics, persistence, knowledge layers, routing, recall, prediction, learning or the HYPNO renderer.

## Archive / bureau vocabulary

Selected user-facing labels now use a playful retro-bureaucratic temporal-agency vocabulary while retaining technical meaning:

- Settings -> **ARCHIVE CONTROL**
- Input -> **INTAKE**
- Memory -> **ARCHIVE**
- Lab -> **CONTROL**
- Knowledge Base -> **CENTRAL ARCHIVE**
- External Knowledge Import -> **ARCHIVE INTAKE**
- Memory Profiles -> **CASE FILES**
- Long-Term Hard Test -> **CONTINUITY AUDIT**
- Memory Competition -> **PRUNING PROTOCOL**
- Hierarchical Routing diagnostics -> **ROUTING DESK**
- Predictive Recall diagnostics -> **BRANCH FORECAST**
- Recall Hardening -> **RETRIEVAL CONTROL**
- Long-Term Store -> **DEEP ARCHIVE**
- novelty in the continuity display -> **VARIANCE**

These names are presentation aliases only. Internal classes, persisted field names and file formats are unchanged.

## Settings jitter fix

The live INPUT/INTAKE page used normal wrap-content TextViews for rapidly changing audio/video diagnostics. Long frame counters, IDs, percentages and timing strings could therefore wrap to a different number of lines from update to update, forcing the ScrollView to re-layout and making the menu visibly jump.

Step 46.2 introduces fixed-height `stableDiagnosticLabel(...)` slots:

- audio state, frequency/pattern ID, strength and frame counter: **1 fixed line each**
- camera frame/coupler diagnostics: **2 fixed lines**
- visual codebook diagnostics: **2 fixed lines**
- temporal sync/governor diagnostics: **2 fixed lines**
- live memory statistics: **22 fixed lines**

The slots use monospace text and end-ellipsizing. Values can change continuously without changing the vertical geometry of the settings page.

## Compatibility

No memory/profile/BASE/provenance/import format changes. Step 46.1 product behavior remains HYPNO-only.

## Version

- `versionCode 63`
- `versionName 0.6-step46.2-archive-ui-stability`
