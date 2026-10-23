# Equinox v0.5 — Step 13

Step 13 is the UI restructuring pass.

## Main goals
- modernized UI
- clean separation between User view and Lab view
- immersive fullscreen
- fewer debug-style labels in the normal user experience

## User view
The default screen now focuses on:
- live system status
- memory summary
- graph
- 2D/3D switch
- audio controls
- camera controls
- sonification
- consolidation interval
- manual consolidation

## Lab view
Technical and development controls are moved out of the normal interface:
- diagnostic samples
- CSV diagnostics
- save slots
- display HEX settings
- glow toggle
- geometry summary

Switch between both modes from the header.

## Fullscreen
Step 13 uses immersive sticky fullscreen:
- status bar hidden
- navigation bar hidden
- system bars can still be shown temporarily by swipe

## Preserved
- Step 12 diagnostics
- Step 11 save slots / settings
- Step 10 2D/3D + geometry
- Step 09 camera + multimodal relations
- Step 08 sequence compression
- Step 07 recursive abstraction
- sonification
- persistent memory migration

## Important
This is still the v0.5 prototype family. The UI is cleaner, but it is not yet a
polished production design system or signed release package.

## Install
Open as a fresh Android Studio project, build the APK, and install over the same
`de.equinox.v05` application.
