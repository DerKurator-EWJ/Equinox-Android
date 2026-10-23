# Equinox v0.6 — Step 25.1: Semantic Cleanup

> Step 25.1 is a cleanup layer on top of Step 25. The adaptive/unbounded mechanics remain unchanged.
> The cleanup makes one rule consistent everywhere: **Tier = abstraction depth only**; maturity/relevance/stability are separate signals.


Step 25 removes the major **semantic hard ceilings** that were still left over from the prototype architecture. Physical phone resources remain finite, but the model no longer says that memory ends at 4096 audio IDs, 1024 visual IDs, 48 active macros, L4, 6000 candidates or 12000 template buckets.

## 1. Adaptive visual resolution instead of a fixed learning raster

The old 8×6 learning grid is now only the **entry lattice**. Each region can recursively split into 2×2 subregions.

Refinement is driven by two signals:

- **familiarity**: a repeatedly recognized region earns finer resolution;
- **surprise/novelty**: a new region receives temporary exploratory resolution, so an unknown object cannot remain unknown merely because it started coarse.

There is no configured maximum visual depth. Refinement stops naturally when the camera region is too small to split further or when the adaptive per-frame work budget is used. Faster hardware automatically earns a larger region budget.

The read-only video pigment grid also scales dynamically from the current familiarity/detail state instead of being fixed to 48×27.

## 2. Adaptive audio and visual codebooks have no identity-count ceiling

The former caps:

- audio: 4096 prototypes
- vision: 1024 prototypes

are removed.

A new ID is created when the current input is not represented closely enough by an existing local prototype. Familiar prototypes can gradually split more finely. This means resolution can increase with experience rather than stopping at an arbitrary codebook size.

Both adaptive quantizers now use a coarse local feature index, so lookup does not require scanning the entire learned codebook on every observation.

## 3. Dynamic working set

The former fixed 48-macro working set is removed.

The active frontier now keeps enough nodes to cover about 97.5% of the current activation/expectation/exchange mass, while genuinely live nodes are always retained. A simple context may therefore use only a small number of macros; a complex state may materialize many more.

Neighbor retrieval and prediction are likewise selected by cumulative relation/probability mass instead of fixed `6 neighbors` or `Top-4` rules.

## 4. RPG system is now the abstraction depth

The old L1–L4 ceiling is removed. Q is the encoder alphabet; persistent compositions are labeled directly by depth:

`Q → F → E → D → C → B → A → S → SS → SSS → Ω → Ω+1 → Ω+2 → ...`

The internal depth is an ordinary unbounded integer. There is no `maxLevel` in the learning core.

**Tier is no longer a quality score.** Tier means abstraction depth only. Relevance, stability/maturity and recurrence remain separate measurements.

Existing Step-24 macros remain compatible: old level 1 becomes F, level 2 becomes E, level 3 becomes D and level 4 becomes C. New learning may continue beyond that.

## 5. Sequence/template limits removed

The old fixed candidate/template capacities are gone:

- no 6000 temporary-pattern ceiling;
- no 12000 template-bucket ceiling;
- no fixed substitution-pair ceiling;
- no fixed crossmodal-candidate count ceiling.

Temporary evidence is instead pressure-managed relative to the amount of persistent structure already learned. Pattern horizon grows logarithmically with experience rather than being permanently fixed to length 6.

## 6. Memory competition and relation storage scale with learned structure

Macro competition no longer assumes fixed `320/384 macros per stream` or `128 deletions per run`. Stream capacity grows with accumulated evidence and abstraction depth. Relation/exchange cleanup uses soft targets derived from macro/working-set size rather than fixed 4096-entry cutoffs.

## 7. Geometry

The existing 17 geometry rules remain as a reusable **ground grammar**, not as an upper bound on learned identities or abstraction depth. They are output primitives; they do not constrain how many visual structures, macros or RPG tiers Equinox can learn.

## Compatibility

The Step-23 indexed long-term store remains the persistent backend. Step-24 memory can be loaded. Existing integer abstraction levels are reinterpreted through the new RPG tier labels without rewriting their identities.

## Verification done here

The Step-25 core (`MemoryModel`, open hierarchy, adaptive working set, adaptive quantizers, indexed storage, tier mapping) was compiled with Kotlin against API stubs. The adaptive visual analyzer was also compiled separately against CameraX stubs. The full Android Gradle/APK build still has to be done in Android Studio because this environment has no Android SDK.

## Core stress check

A synthetic repeated Direct-ID stream was run against the Step-25 core after compilation. It formed levels 1 through 6 (`F → E → D → C → B → A`), demonstrating that the old L4 stop is no longer present in the learning path. This is a mechanism check only, not evidence of semantic learning.

## Step 25 fixed build
- Fixed `MainActivity.memoryText()` to use the actual field name `adaptiveQuantizer` instead of the stale `adaptiveAudioQuantizer` reference.


## Step 25.1 semantic cleanup

- Highest active tier is computed by maximum active macro depth, not by maturity score.
- Maturity score no longer receives an abstraction-depth bonus.
- Memory competition no longer protects S/SS/SSS/Ω nodes merely because they are deep.
- Renderer node size no longer double-counts tier.
- Orchestra tier controls role/timbre, while presence is driven by activation/relevance/expectation.
- Runtime output DTOs now use explicit names: `abstractionTier`, `abstractionTierIndex`, `maturityScore`.
- Indexed memory format is unchanged.

## Step 28 — GPU LIVE renderer

The visible LIVE surface now uses OpenGL ES 2.0. Camera pigment is uploaded as a texture and Equinox state deforms/colors it in a fragment shader. The learning core remains independent of output coordinates and the renderer never writes back into memory. Step 27's delayed/latest-frame policy remains the synchronization layer.

## Step 30 — Unified Canvas
CANVAS and LIVE are no longer separate hot render surfaces. One persistent OpenGL canvas continuously blends Reality and Memory, while the generative orchestra reads the same active working state.


## Step 31 — Unified Orchestra
The Reality ↔ Memory blend is now shared by the single GPU canvas and the continuous generative orchestra. Audio is no longer conceptually a separate mode: the same live/recalled working state is rendered visually and acoustically with one continuous mix parameter.

---

## Step 32 — Tier-aware Visual Hierarchy

The unified OpenGL canvas no longer treats every active macro as the same point-like visual primitive. The RPG abstraction depth now selects the *kind* of spatial expression: F/E/D become fine fibers, middle tiers become local membranes/tissue, SSS-level structures become regional density, and Ω+n becomes a broad organizing domain. Relevance/activity remain separate from abstraction depth, so deeper does not automatically mean brighter or more important. The renderer also reserves a small number of field slots across shallow/mid/deep groups so deep structures are not hidden by a flood of low-tier activity.

### Step 33
Fluid renderer-side hierarchy transitions: persistent macro-ID visual states, eased Reality/Memory blend, fade-in/out for fields and links, continuous tier morphing, and on-demand animation frames only while a transition is active.

### Step 34 — Performance Governor
Transient GPU/camera/analysis budgets now adapt to measured render cost, analysis cost, dropped frames and Android thermal pressure. Persistent Equinox learning semantics remain unchanged.

## Step 36 — Unified Capture / Export
The Unified GPU surface can now be recorded to H.264 MP4. The Unified Orchestra exposes a read-only PCM tap so its generated audio can be encoded as AAC into the same export. Capture does not mutate memory or learning state.
