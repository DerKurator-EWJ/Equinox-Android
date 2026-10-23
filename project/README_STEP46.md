# Equinox v0.6 — Step 46: External Knowledge Import Interface

Step 46 adds the controlled bridge from external teacher/model data into the Step-45
`BASE / PERSONAL / DERIVED` architecture. It deliberately does **not** copy arbitrary neural
weights directly into Equinox memory.

## Pipeline

`External teacher / adapter -> canonical EQKI JSON -> dry-run -> validation -> normalized Equinox macros/relations -> staged BASE store -> atomic install`

The canonical input format is:

`equinox-external-knowledge-v1`

See `EXTERNAL_KNOWLEDGE_SCHEMA_EXAMPLE.json`.

## Grounding rule

External knowledge must be structurally grounded before it can become Equinox memory:

- Level-1 nodes use explicit existing Equinox sensor-token IDs in `quantTokens`.
- Level-2+ nodes use `members` that reference lower external nodes.
- A normal single-modality parent must reference the immediately lower tier and the same modality.
- `crossmodal` may bridge modalities explicitly.
- Ungrounded embedding-only nodes are rejected instead of being presented as learned sensor meaning.

This boundary is intentional. An embedding can say that two already-grounded structures are
similar; it cannot silently invent what a camera/audio token means.

## Stable BASE IDs

Every external node receives a deterministic ID in the reserved BASE range using:

`stableId = FNV1a64(packId | externalNodeId) -> BASE ID space`

Collisions are detected and make the dry-run fail. The same pack ID + external node ID therefore
keeps the same Equinox ID across compatible future versions.

## Relations / embeddings

Explicit relations are normalized to bounded Equinox relation weights.

Optional embedding conversion is sparse and bounded:

1. deterministic sign-sketch bucket,
2. same/one-bit-neighbor buckets only,
3. bounded candidate count,
4. cosine verification,
5. threshold + bounded outgoing neighbors.

There is no global all-to-all embedding matrix. Embeddings create relation proposals only; they do
not create macro identity or sensor grounding.

## Dry-run and installation

Settings -> MEMORY -> KNOWLEDGE BASE now includes **EXTERNAL KNOWLEDGE DRY-RUN / IMPORT**.

Dry-run checks:

- schema/version,
- duplicate external IDs,
- hierarchy integrity,
- modality integrity,
- grounding,
- relation references,
- stable-ID collisions,
- size ceilings,
- embedding constraints.

Nothing persistent changes during dry-run.

A valid document becomes a `CompiledBasePack`. Installation then:

1. writes a separate staging `IndexedLongTermStore`,
2. rereads headers/relations,
3. touches every stored token to detect truncation,
4. backs up the active BASE store,
5. atomically swaps staging -> active,
6. writes manifest v2,
7. restores the previous BASE on failure.

Same-pack downgrades are rejected.

## Profile safety

If the active profile already requires a BASE pack through DERIVED knowledge, the UI only permits
installation when the incoming pack has the same `packId` and at least the required version.
A different pack is blocked instead of silently quarantining the profile's DERIVED layer.

BASE remains global and outside memory profiles. PERSONAL/DERIVED profile storage is unchanged.
Step-37+ profiles therefore remain format-compatible.

## Manifest v2

The global BASE manifest now additionally records:

- source ID/version,
- macro count,
- relation count,
- import timestamp.

Manifest v1 remains readable.

## Verification performed

- `ExternalKnowledgeImport.kt`, `IndexedLongTermStore.kt`, `KnowledgeOrigin.kt` and
  `BaseKnowledgeRepository.kt` compile against JVM Android/JSON stubs.
- A synthetic staged install with two Level-1 macros, one Level-2 macro and one relation completed
  successfully and reread all three macros + the relation from the installed store.
- Sparse embedding routing linked two near vectors while excluding an unrelated vector in the
  synthetic test.
- Same-pack invalid/downgrade metadata is rejected before store activation.

A full Android APK build still requires Android Studio / Android SDK on the target machine.

Version: `0.6-step46-external-knowledge-import` (versionCode 61).
