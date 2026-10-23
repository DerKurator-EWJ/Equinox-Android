# Equinox v0.6 — Step 45

Current milestone: **Base / Personal / Derived Knowledge Architecture**.

The learned hierarchy now has an independent provenance axis: immutable global `BASE`, profile-owned `PERSONAL`, and mixed `DERIVED` knowledge. BASE lives outside memory profiles, so future base-pack updates cannot overwrite personal histories. Existing Step 23–44 profiles remain readable and default to PERSONAL when no provenance sidecar exists.

Settings -> MEMORY also has a BASE toggle. `BASE AUS + NEW` gives a genuine blank start.

See `README_STEP45.md` for the architecture, compatibility rules and verification notes.
