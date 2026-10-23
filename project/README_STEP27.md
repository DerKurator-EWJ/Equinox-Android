# Equinox v0.6 — Step 27: Low-Latency Live Pipeline

Step 27 keeps the Step-26 CANVAS/LIVE UI, but replaces the direct camera→View update path with a bounded synchronized presentation pipeline.

## New

- Frame ring buffer with intentional frame dropping instead of UI backlog.
- Presentation delay presets: AUTO, 0, 33, 66, 100, 200, 500 ms.
- AUTO delay uses rolling analyzer + renderer cost and stays between 33–250 ms.
- Camera video sampling dynamically approaches 30 fps on fast devices and backs off when RGB extraction is expensive.
- Frame + WorkingSet are delivered to `MemoryVideoView` as one render packet.
- The LIVE hot path uses `memory.workingSetSnapshot()` only. It no longer materializes the full graph/sonification snapshot merely to render video.
- A single coalesced UI render job is allowed at a time. If new frames arrive while rendering is busy, stale frames are discarded.
- LIVE footer and INPUT settings show effective delay, actual frame age and dropped-frame count.

## Important

The delay is output-only. It does not change sensor timestamps, learning, macro creation or long-term memory.

`AUTO` is recommended first. A fixed 66–100 ms mode can look smoother because the corresponding memory state has time to become available before the camera pigment is displayed.

## Build

Open this folder itself in Android Studio. Application ID remains `de.equinox.v05`, so it updates the existing debug installation when signed with the same debug key.
