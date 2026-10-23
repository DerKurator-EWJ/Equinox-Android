# Equinox v0.5 — Step 14

Step 14 replaces the old graph-style renderer with the first dedicated Equinox visual engine.

## Goal
The core still stores relations, not physical x/y/z positions.

The renderer now builds a separate visual scene from the relational memory.

## New visual architecture
MemoryModel
→ GraphSnapshot
→ VisualGraphModel
→ layout relaxation / hierarchy
→ fiber generation
→ RelationGraphView scene renderer

## Major changes
- stable display positions retained between graph updates
- abstraction-aware preferred distances
- visible-node cap and visible-edge cap for level-of-detail
- relation fibers use cubic Bezier paths
- related fibers receive deterministic bundle bias
- stronger relations become thicker / more luminous
- macro abstraction becomes organic glyphs rather than labeled boxes
- vision remains square-like
- audio remains circular
- strong nodes and fibers can glow
- 2D and pseudo-3D use the same scene
- pinch zoom
- drag/pan
- renderer-only depth
- no x/y/z written into MemoryModel

## What this is
This is the first actual visualization-engine layer for Equinox.

## What this is not yet
It is not OpenGL/Vulkan, GPU particle rendering, real volumetric 3D, or a physics engine.
The renderer is still Android Canvas based.

The next visual iteration can focus on animation, smoothing between states,
more sophisticated fiber bundling, level-of-detail transitions and eventual
GPU rendering if performance requires it.

## Preserved
- Step 13 User/Lab split + fullscreen
- Step 12 diagnostics
- Step 11 save slots/settings
- Step 10 geometry ground memory
- Step 09 camera multimodality
- Step 08 sequence compression
- Step 07 recursive abstraction
- sonification and consolidation

## Install
Build in Android Studio and install over the existing `de.equinox.v05` app.
