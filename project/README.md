# Equinox v0.5 — Step 07

Step 07 adds true recursive abstraction on top of the working Step 06.5 build.

## New in Step 07
- Existing Step 03–06.5 memory is migrated automatically.
- Level 1 nodes are created from stable micro-to-micro relations.
- Level 2 nodes can be created from stable Level-1-to-Level-1 relations.
- Level 3 and Level 4 follow the same rule recursively.
- Existing higher nodes keep reacting even when creation is frozen.
- Macro freeze now freezes creation across all abstraction levels.
- The Lab view shows counts per abstraction level.
- Rendering gives each abstraction level its own glyph/color:
  - Micro: circles
  - L1: orange organic glyph
  - L2: pink glyph
  - L3: violet glyph
  - L4: magenta glyph
- Sonification can recursively resolve higher nodes down to their leaf micro frequencies.

## Important
The abstraction levels are algorithmic memory/compression layers. They are not claimed to be physical dimensions or laws of nature.

## Install
Open this folder as a new Android Studio project, build an APK, and install it over the existing `de.equinox.v05` app.
