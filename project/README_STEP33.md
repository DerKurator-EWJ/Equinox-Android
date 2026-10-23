# Equinox v0.6 — Step 33: Fluid Hierarchy Transitions

Step 33 keeps the single unified OpenGL canvas from Steps 30–32, but removes hard visual popping between hierarchy states.

## New

- Renderer-side temporal interpolation for memory energy, expectation, prediction and signed overexchange.
- The Reality ↔ Memory blend is eased instead of jumping directly to the slider target.
- Active hierarchy fields are tracked by persistent macro ID.
- New fields grow in from zero strength; disappearing fields decay before removal.
- Field position, strength and RPG abstraction tier interpolate continuously.
- Because the Step-32 shader already uses smooth tier bands, interpolated tier values now let F/E/D fibers reorganize continuously into tissue, density fields and Ω domains rather than switching abruptly.
- Relation / prediction / crossmodal links fade in and out instead of appearing or disappearing in one frame.
- The GLSurfaceView remains `RENDERMODE_WHEN_DIRTY`: additional animation frames are requested only while a transition is still moving, so the renderer does not become an always-on 60/120 Hz loop when the scene is static.

## Unchanged

- One unified GPU canvas.
- Reality ↔ Memory continuum.
- No artificial time wobble.
- Renderer coordinates remain output-only and never enter MemoryModel.
- Tier remains abstraction depth only; relevance/stability/activity remain separate.
- Orchestra and learning core are unchanged.

## Build note

Open this folder directly in Android Studio. The renderer source was compile-checked against Android/OpenGL stubs, but a full Android Gradle/APK build still needs the local Android SDK.
