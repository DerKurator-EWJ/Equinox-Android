# Equinox v0.6 Step 41 — Unified Audio + Audio Macro Voices

Current project state through Step 41.

Step 38 spatial compression, Step 39 hierarchical routing and Step 40 recall/prediction hardening remain intact. Step 41 collapses all hardware audio output into one `UnifiedAudioEngine` and reconstructs each audio macro's acoustic voice recursively from its actual learned constituents instead of using abstraction tier as a pitch rule.

The product surface now exposes one primary audio control. Memory/LAB previews and recording use the same engine and synthesis model.

See `README_STEP38.md` through `README_STEP41.md` for the implementation history.
