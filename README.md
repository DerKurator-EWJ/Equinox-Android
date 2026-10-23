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
| `android-eq35-productui` | `Eq35_ProductUI.zip` | 53 | 0 | `6408ca8a3d04dc3bd0e4125141bff697720030ba1ecd08f60d60e92f49d08bb1` |
| `android-eq36-record` | `Eq36_Record.zip` | 56 | 0 | `71e81c53fdbbee10b78969ce12b5254f7a2ab8787d0a06b8dcd536d4134c276d` |
| `android-eq37-profiles` | `Eq37_Profiles.zip` | 58 | 0 | `1ca92e7c111969f60b5273316593f18cabd8a31e268a80835f0b800d1e592acb` |
| `android-eq38-spatialcoupler` | `Eq38_SpatialCoupler.zip` | 60 | 0 | `7d9adbc991d8de4548fb88c0a5e8b7d8daaf2fa963279923aeb71acaefd6b720` |
| `android-v05-step01` | `Equinox_v05_Step01.zip` | 8 | 0 | `a5f900f4c233e8a38ea5fd8de557b76f7349feb76baf782a91b9904a645bc91e` |
| `android-v05-step02` | `Equinox_v05_Step02.zip` | 9 | 0 | `6e062406f2261c062b1ba93697926eb0c6255fec861d2bd885fa3d46649af9d7` |
| `android-v05-step03` | `Equinox_v05_Step03.zip` | 10 | 0 | `d52f21fd153610ba13566e31c492ec5a8584e6ed89d2580bb89d7ae40848be82` |
| `android-v05-step04` | `Equinox_v05_Step04.zip` | 11 | 0 | `86165fd0535ab4ee82c12a319f464af826ed1ab8c076d93747c2baa0d047ccb1` |
| `android-v05-step05` | `Equinox_v05_Step05.zip` | 11 | 0 | `e72140232577474238b966f61b7d40fff99c733849d0333170de27670fd1bc22` |
| `android-v05-step06` | `Equinox_v05_Step06.zip` | 12 | 0 | `8768d4bfcec0018673a539d364485dcda14c25e1a72b1de25c31358780401cad` |
| `android-v05-step06-fixed` | `Equinox_v05_Step06_fixed.zip` | 12 | 0 | `7bf3eda6e42697f95747f46d8dcab1e514d8e55c70abb5555bd275e682df7c8e` |
| `android-v05-step07` | `Equinox_v05_Step07.zip` | 12 | 0 | `50f392572a27265abb57d55d8c143765b4b07e630a3c3c05b4d80197adecb10e` |
| `android-v05-step08` | `Equinox_v05_Step08.zip` | 12 | 0 | `839aa0c06feaff805f6121fbfb1a83a31ffaee006b9138069a5537c9f4ef0ab6` |
| `android-v05-step09` | `Equinox_v05_Step09.zip` | 13 | 0 | `de04f231a6d8618e01dd6b0932d6c587cfcabb44a163adac51eee49a3b521919` |
| `android-v05-step10` | `Equinox_v05_Step10.zip` | 14 | 0 | `4c929801af25109bf263873938629a2a4e9fe9682d1717cf1722cc4ddb51a1a6` |

## Security, licensing, and limitations

Automatic filtering and secret-pattern scanning are incomplete. Review all commits and tags
before public release for privacy, credentials, embedded metadata, and third-party licensing.
Run instructions and system requirements may differ across historic snapshots.
This archive is not a claim that all versions have been independently validated.
No open-source license is granted merely by publishing code.
