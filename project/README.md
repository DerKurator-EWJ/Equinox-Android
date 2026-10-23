# Equinox v0.5 — Step 09

Step 09 adds the first real visual input and multimodal binding.

## New
- Camera permission and CameraX image analysis.
- Rear camera is analysed directly; no video file is recorded.
- Coarse 8×6 visual raster.
- Each sampled cell is quantized to one of 64 coarse RGB color classes.
- A visual symbol is encoded from `(cell, color class)`.
- Visual symbols are stored in the same persistent memory graph as audio symbols.
- Visual microcodes render as colored squares; audio microcodes remain circles.
- Audio↔vision relations are strengthened when a visual observation occurs while an audio code is active.
- Visual continuity relations are also stored.
- Existing sequence compression and recursive abstraction remain active.
- Existing Step 08 memory is migrated automatically.
- UI counters show visual frames, visual observations, persistent visual codes and cross-modal relations.
- Camera analysis is throttled to roughly one frame every 700 ms to keep the phone responsive and to avoid flooding memory.

## Privacy / storage behavior
Step 09 does NOT save photographs or raw camera frames.
Only quantized visual symbols and their learned relation weights enter the Equinox memory file.

## Architecture
Camera -> 8x6 samples -> 64-class color quantization -> visual token IDs
Audio -> FFT -> frequency IDs

Both streams -> shared relational memory -> recursive macro abstraction

## Important
Cell position is part of the visual encoder's symbol identity; it is not treated as fundamental physical x/y inside the relational core.

## Install
Open the folder as a fresh Android Studio project, generate the APK and install it over the existing `de.equinox.v05` app.
