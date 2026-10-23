# Equinox v0.6 — Step 28: GPU Live Renderer

Step 28 replaces the LIVE hot-path Android Canvas renderer with an OpenGL ES 2.0 texture renderer.

## What changed

- LIVE now uses `GpuMemoryVideoView` (`GLSurfaceView`, OpenGL ES 2.0).
- Camera pigment is uploaded as a GPU texture; the live deformation/color pass runs in a fragment shader.
- No per-cell `Canvas.drawRect()` loop remains on the visible LIVE surface.
- Equinox Working Set remains read-only output input: activation, expectation, prediction, relevance and overexchange become shader uniforms.
- Up to eight strongest active macro fields become local swirl/deformation fields in the shader.
- The Step 27 frame ring buffer, AUTO delay, stale-frame dropping and synchronized frame/WorkingSet packets stay intact.
- Renderer runs `RENDERMODE_WHEN_DIRTY`: no camera/state update means no continuous GPU redraw.
- Existing CANVAS and LAB renderers are unchanged.
- The camera pigment raster is now also device-budget adaptive: it starts above the old prototype density and grows/shrinks from measured YUV sampling cost, with the source frame itself as the natural ceiling.

## Important architecture boundary

This is a GPU renderer, not a zero-copy CameraX preview pipeline yet. `VisualGridAnalyzer` still samples CameraX YUV frames into `VideoFrameSnapshot`; Step 28 moves the expensive visual deformation/compositing off Android Canvas and onto the GPU. A later zero-copy camera `SurfaceTexture` path could remove the remaining CPU YUV-to-pigment conversion if measurements show it is still the bottleneck.

## Build

Open this folder itself in Android Studio and build the APK normally. Application id stays `de.equinox.v05` so it updates the existing debug installation when signed with the same key.
