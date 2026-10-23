# Equinox v0.5 — Step 11

Step 11 adds save slots, a small Lab panel, and persistent display settings.

## New
- 3 local Equinox memory save slots.
- SAVE / LOAD / DELETE per slot.
- Loading a slot stops audio and camera first, then reloads the selected memory state.
- Save slots contain only the Equinox memory JSON state.
- Display settings are stored separately in SharedPreferences.

## Lab / display settings
Editable HEX colors for:
- background
- micro relation edges
- L1
- L2
- L3
- L4

Also:
- glow toggle

The graph updates immediately after saving display settings.

## Preserved
- Step 10 2D/3D renderer
- geometry ground memory
- Step 09 camera + multimodal relations
- Step 08 sequence compression
- Step 07 recursive abstraction
- sonification
- consolidation
- existing live memory migration

## Architecture
Core memory remains separate from display preferences.

Memory:
symbols -> relations -> sequences -> recursive abstractions

Lab:
renderer colors / glow only

Save slots:
copies of the live Equinox memory file

## Important
This step still uses internal app storage. External export/import and signed-release packaging remain separate finalization tasks.

## Install
Open as a fresh Android Studio project, build the APK and install it over the same
`de.equinox.v05` application.
