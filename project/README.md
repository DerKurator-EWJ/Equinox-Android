# Equinox v0.6 — Step 44

Current milestone: **Hypno Audio / Visual Synchronisation**.

HYPNO and Unified Audio now derive their behaviour from one shared, normalized, read-only `UnifiedPresentationState`. Activity, prediction, signed exchange, novelty, crossmodality, tier depth, retrieval share and relation energy therefore have one canonical output interpretation before being expressed visually or acoustically.

With live camera presentation, the same delayed `WorkingSetSnapshot` selected by the Step-27 render packet is sent to both GPU and audio, removing independent audiovisual state sampling.

LEARN / PLAY / DREAM semantics, the persistent memory format, Step-37 profiles and the Step-41 reconstructed macro voices remain compatible.

See `README_STEP44.md` for architecture and verification notes.
