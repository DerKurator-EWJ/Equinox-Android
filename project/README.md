# Equinox v0.6 — Step 26: Minimal UI / Render Isolation

Step 26 does not change the learning model. It separates the hot render path from debug/settings UI.

## Normal UI
- CANVAS: only the Equinox generative state canvas.
- LIVE: only the memory-driven live camera surface.
- ⚙: opens settings. INPUT, MEMORY and LAB live behind this overlay.

## Performance changes
- Exactly one main render surface receives continuous EngineOutputState updates.
- Legacy embedded preview canvases are hidden and no longer updated.
- Large memory statistics are not rebuilt while settings are closed.
- Audio/camera labels are not updated while settings are closed.
- LAB graph rendering is capped to ~4 Hz and only runs while LAB is visible.
- Camera still uses KEEP_ONLY_LATEST; the live surface receives the newest frame instead of building a UI queue.

## Engine
All Step 25.1 semantics remain unchanged: open adaptive alphabets, coarse-to-fine vision, dynamic working set, and Q→F→…→Ω+n abstraction tiers.

Next planned step: low-latency live frame ring buffer with selectable/AUTO delay and explicit frame dropping/synchronization.
