# Equinox v0.6 — Step 32: Tier-aware Visual Hierarchy

Step 32 changes the unified OpenGL canvas from a point-centric graph aesthetic into a hierarchy-aware tissue renderer.

## What changed

The renderer now uses the RPG abstraction tier as *visual organization depth*, never as importance:

- **Q** remains the encoder alphabet and is not rendered as a macro tier.
- **F / E / D** are expressed as fine local filaments and small sparks.
- **C / B / A / S / SS** form soft membranes and local tissue densifications.
- **SSS** and nearby deep structures become broad regional density fields.
- **Ω / Ω+n** become quiet domain-scale organizers instead of giant circles.

Relevance, activity and expectation still determine how visible a structure is. Tier only determines *how* that structure occupies the renderer.

## Important implementation detail

The renderer reserves a few GPU field slots for shallow, middle and deep abstraction groups. This prevents a large number of active F/E nodes from visually hiding the deeper hierarchy. This sampling changes display only; it does not alter the working set, memory, learning, relevance or prediction.

The same unified Reality <-> Memory surface remains in place. Camera pigment and memory tissue still transition continuously, but Memory should now read more like fibers -> tissue -> fields -> domains and less like a star diagram.

There is still no physical x/y position in the Equinox core. All placement remains renderer-only and deterministic.
