# Equinox v0.6 — Step 25.1 Semantic Cleanup

This step keeps Step 25 behaviour and removes the last semantic mix-up between abstraction depth and maturity/importance.

## What changed

- RPG tier is now *only* abstraction depth everywhere: `Q -> F -> E -> D -> C -> B -> A -> S -> SS -> SSS -> Ω -> Ω+n`.
- The stats line now computes the highest active tier by actual macro depth, not by whichever node has the highest maturity score.
- Maturity score no longer gets a bonus merely for being on a deeper tier.
- Memory competition no longer protects `S+` nodes just because of tier and no longer gives utility simply for depth. Protection comes from active context, parent reuse, template support, relevance and persistence.
- Output node size no longer double-counts abstraction depth through both level and the legacy rank index.
- The live footer reports the deepest active tier consistently.
- The generative orchestra still uses tier to choose orchestral role/timbre, but tier no longer directly increases loudness/presence.

## Compatibility

The indexed long-term store remains compatible. No learned memory format was changed. Legacy field names such as `structuralRank` remain internally for source compatibility, but their visible meaning is now strictly tier/depth.

## Next planned step

Step 26: minimal two-screen UI (`CANVAS` / `LIVE`) with settings overlay, LAB behind settings, and decoupled UI/core/render update rates to reduce stutter.
