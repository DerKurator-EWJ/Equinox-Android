# Equinox v0.5 — Step 12

Step 12 is the long-term/performance hardening step for the v0.5 prototype.

## New
- Manual diagnostic samples.
- Rolling diagnostic history capped at 240 samples.
- Tracks:
  - total observations
  - persistent micro codes
  - macro codes
  - sequence codes
  - total relations
  - cross-modal relations
  - live memory JSON size
  - Java heap usage
- Derived long-term metrics:
  - new micro codes per 1000 observations
  - new sequence codes per 1000 observations
  - growth since first retained diagnostic sample
- Sequence-capacity percentage is shown in the main memory status.
- Internal CSV diagnostic export.
- Diagnostic reset.

## Hard-test criterion
In a familiar environment, observations should keep increasing while the rate of
new stable codes should trend downward.

That would support the hypothesis that Equinox is reusing/compressing known
structure rather than merely generating endless new structure.

## What this does NOT prove
A declining novelty rate would not prove semantic understanding, intelligence,
or a physical theory. It would only show measurable reuse/compression behavior
inside this implementation.

## Preserved
- Step 11 save slots and display lab
- Step 10 2D/3D + geometry ground memory
- Step 09 camera + multimodal relations
- Step 08 sequence compression
- Step 07 recursive abstraction
- sonification
- consolidation
- existing memory migration

## Suggested test
1. Run Equinox in the same room/environment for a long period.
2. Capture diagnostic samples periodically.
3. Compare micro/1000 observations and sequence/1000 observations.
4. Watch memory-file size and Java heap.
5. If novelty rate stays flat or rises indefinitely, compression/hierarchy rules
   need tightening before adding more features.

## Install
Open as a fresh Android Studio project, build the APK, and install it over the
same `de.equinox.v05` application.
