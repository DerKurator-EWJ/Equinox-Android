# Equinox v0.6 Step 32

Open this folder directly in Android Studio. Step 32 adds tier-aware visual hierarchy to the single unified OpenGL canvas.

See `README_STEP32.md` for the change summary and `README_V06.md` for the accumulated v0.6 notes.

## Step 33 — Fluid Hierarchy Transitions
The unified GPU hierarchy now interpolates field strength, tier, relations and global memory state over time. Structures fade/grow and morph across RPG-tier visual regimes instead of popping between discrete render states. Rendering stays on-demand: extra frames are scheduled only during an active transition.
