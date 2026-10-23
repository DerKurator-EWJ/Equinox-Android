# Step 30 — Unified Canvas

Step 30 removes the last hard split between CANVAS and LIVE.

## One physical OpenGL surface

The normal app now owns one persistent `GpuMemoryVideoView` only. Camera pigment and the current read-only Equinox working set are rendered by the same fragment shader.

The bottom control is continuous:

- `0% MEMORY` = Reality dominated
- `50% MEMORY` = Hybrid
- `100% MEMORY` = naked memory tissue on black

There is no view destruction, no CANVAS/LIVE swap and no second hot renderer.

## Same field, two manifestations

The strongest currently materialized memory nodes create renderer-only fields. Those exact fields:

1. locally deform the camera pigment in Reality/Hybrid,
2. become glowing tissue nodes and links toward Memory.

At intermediate blend values, camera edges are allowed to feed a subtle bridge glow, so the transition can visually grow out of the observed scene instead of being a plain alpha fade.

## Tissue, not full graph

The shader never traverses the whole long-term graph. It receives only a tiny projection of the current working set (strong fields + a few active exchange/prediction/crossmodal links). Long-term memory stays lazy and coordinate-free.

## Orchestra

The generative orchestra continues to read the same `WorkingSetSnapshot`; the renderer and orchestra therefore remain two output projections of the same active state. Neither writes back into learning.

## Safety / performance

- no time-driven wobble
- OpenGL ES 2.0 conservative uniform budget
- bounded field/link projection to GPU
- low-latency camera ringbuffer from Step 27 retained
- Lab renderer remains throttled and hidden behind settings

This build is still a source project. Build the APK in Android Studio.
