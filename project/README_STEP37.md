# Step 37 — Memory Profiles / Portable Identities

Step 37 turns a learned Equinox history into a first-class portable profile.

## What a profile contains

- `equinox_v06_store/` indexed long-term memory
- `equinox_v06_adaptive_quantizer.json` adaptive audio codebook
- `equinox_v06_adaptive_visual_quantizer.json` adaptive visual codebook

Display/UI preferences remain global and are intentionally not part of the learned identity.

## New actions

Open **Settings → MEMORY → MEMORY PROFILES**.

- **NEW** — saves the old profile, creates a genuinely fresh memory and switches to it.
- **SAVE** — snapshots the currently active learned identity.
- **LOAD** — switches to the selected profile. Audio/camera/Dream are stopped first and the memory + both codebooks are reloaded.
- **DUP** — duplicates a profile as a separate learning history.
- **EXPORT** — writes the selected profile as a portable `.eqx` ZIP through Android's Storage Access Framework.
- **IMPORT** — imports a `.eqx`/ZIP as a new profile without overwriting existing profiles.
- **PROFIL LÖSCHEN** — removes a profile. At least one profile must remain.

## Important semantics

Profiles are experience histories, not UI themes. Switching a profile changes what Equinox has learned, while the unified canvas, shader, Product UI, governor and display preferences stay the same.

A profile switch never merges memories. Import creates a separate profile. This makes controlled A/B experiments possible: e.g. one profile learns only one room, another learns a different environment, and both can later be shown the same standardized input.
