# Step 46.1 — Hypno-only Product Surface

## Why

The Step-30 Reality <-> Memory slider made sense while the product still exposed camera reality, hybrid and memory as visible presentation states. By Step 43–46, HYPNO had become the intended consumer renderer, while Audio and REC controls competed for the same narrow bottom bar. Keeping the legacy slider therefore made the startup UI both cramped and conceptually misleading.

## Product surface

There is now exactly one consumer renderer: **HYPNO**.

- Removed `REALITY` label.
- Removed `MEMORY xx%` label.
- Removed the Reality <-> Memory `SeekBar`.
- Removed user-facing visual blend control.
- The GPU surface starts with visual memory/generative blend `1.0`.
- Camera frames may still enter the sensing/presentation pipeline, but HYPNO shows their effect through the Unified State instead of presenting the raw camera image as a product mode.

The bottom bar is now a centered wrap-content dock with only:

`LIVE/DREAM | Audio | REC | Settings`

`LIVE` is the existing read-only PLAY cognitive mode with live sensor activation. `DREAM` is internal read-only activation. `LEARN` still lives under Settings/Input. None of these changes the renderer: the renderer remains HYPNO.

## LAB boundary

`STRUCTURE` is retained only for technical diagnosis in LAB. If it is selected, closing Settings forcibly restores `HYPNO`, so a diagnostic renderer can never leak into the normal product surface.

## Audio

The old visual slider no longer controls audio. Unified Audio continues to consume the same synchronized `WorkingSetSnapshot` / `UnifiedPresentationState`; its internal context weighting remains implementation detail rather than a product presentation mode.

## Persistence / learning

No memory, profile, BASE pack, provenance or external knowledge format changes. Step 46 imports and Step 45 knowledge layers remain fully compatible.

## Version

- `versionCode 62`
- `versionName 0.6-step46.1-hypno-only-surface`
