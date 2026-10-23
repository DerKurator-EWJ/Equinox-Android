# Equinox v0.6 — Step 38

Current milestone: **Spatial Coupler**.

Build the Android project in Android Studio and install it on device. Camera learning now keeps the adaptive
visual codebook, but compresses connected near-identical patches into frame-local regions before they enter
the memory hierarchy. Familiar flat areas also stop refining just because they are familiar.

In **Settings -> camera diagnostics**, compare `Roh -> gekoppelt` to see how much visual core work is removed
for the current scene.

See `README_STEP38.md` for implementation notes. Step 37 profiles remain format-compatible.
