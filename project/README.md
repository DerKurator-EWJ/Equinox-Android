# Equinox v0.5 — Step 05

Step 05 adds the first abstraction layer to the working Step 04 audio-memory graph.

- Existing micro codes and directed relations remain intact.
- Consolidation promotes sufficiently repeated micro relations into first-level macro nodes.
- Macro nodes receive IDs starting at 1,000,000.
- Existing macros react when one of their members is observed and can form macro-to-macro relations.
- Macro creation can be frozen independently; existing macro weights/relations still update.
- Renderer distinguishes micro nodes (circles) and macro nodes (orange rounded rectangles).
- Coordinates remain display-only and are not persisted as part of the model.
- Uses the same memory file as Step 03/04, so prior learned micro memory can be loaded.

This is deliberately a first-level macro proof. Recursive macro-of-macro promotion is deferred until the first abstraction mechanism is stable on-device.
