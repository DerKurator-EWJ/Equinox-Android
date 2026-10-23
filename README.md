# Equinox Android — Historical Development Archive

This repository documents archived iterations of the early Equinox / Kira Android app.
This Android development started before Equinox Forge and continued partly in parallel.

**Retrospective import:** Git commit dates reflect publication/import, not original development dates.
Version ordering is based on the provided filename list, not verified historic timestamps.
In particular `Equinox_v05_Step065` and the `v06 Step46` variants should be reviewed.

The source of each snapshot is in [`project/`](project/). Switch Git tags to inspect earlier versions.
Each commit **replaces** the `project/` snapshot. The original ZIP archives remain unchanged locally.
Known generated/build files, APKs, machine configs and signing material are omitted deliberately.
Counts and SHA-256 hashes below refer to the original input ZIPs; they are not a guarantee of safety.

## Snapshot index

| Tag | Source ZIP | Included files | Omitted files | Input ZIP SHA-256 |
|---|---|---:|---:|---|
| `android-eq25-1-cleanup` | `Eq25_1_Cleanup.zip` | 40 | 0 | `ac072c22e2809d92741566ccd1f160f803379a90364bf651d81944b658163f97` |
| `android-eq26-ui` | `Eq26_UI.zip` | 41 | 0 | `f297dd254c11d97f76934f0eeb3bdd4751876666c24fd9a00ac80d3f1da26a1a` |
| `android-eq27-live` | `Eq27_Live.zip` | 43 | 0 | `993bb94ef358a0124ce38e2601a3d09c7e7bfa891de984d8bbb136eaf4e8fe8d` |
| `android-eq28-gpu` | `Eq28_GPU.zip` | 45 | 0 | `e3a95ccee3a6b254423d47a9bbb0d1ca1269bd4bfa946bbce858228ec46c936d` |
| `android-eq29-ux` | `Eq29_UX.zip` | 46 | 0 | `506ded303877300600c53b609fb774b934e6f3b2ea8d55ca87d2a26a6b4bbedb` |
| `android-eq30-unified` | `Eq30_Unified.zip` | 47 | 0 | `5b2f4841edca63cb72d3a8137c0ddb9b9ccf1ec4366a1967bef6ef2cd88b94df` |
| `android-eq31-unifiedorchestra` | `Eq31_UnifiedOrchestra.zip` | 48 | 0 | `c9f1c6e5b3fb44e5fe1d376de9b063ac0222a9f814a624adc916d11b7a3a6129` |
| `android-eq32-visualhierarchy` | `Eq32_VisualHierarchy.zip` | 49 | 0 | `e7e7e62cb20e7352872a7e5ef1e6d0f996ecb80bf9f255d2e7f38bb2bc6b4a12` |
| `android-eq33-fluidhierarchy` | `Eq33_FluidHierarchy.zip` | 50 | 0 | `18a041bd79e86b6dd59a579aefdc8278255c025fb3c889ceb864b071272f2790` |
| `android-eq34-governor` | `Eq34_Governor.zip` | 52 | 0 | `70ed0c1d79d8ae45de0064559695ed4fa3b87f43ac8441d68ce12e5701d75a3e` |

## Security, licensing, and limitations

Automatic filtering and secret-pattern scanning are incomplete. Review all commits and tags
before public release for privacy, credentials, embedded metadata, and third-party licensing.
Run instructions and system requirements may differ across historic snapshots.
This archive is not a claim that all versions have been independently validated.
No open-source license is granted merely by publishing code.
