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
| `android-v05-step11` | `Equinox_v05_Step11.zip` | 16 | 0 | `3501991b5a35bfdbc0b43360f0ebf4cf7e8e81a539480d8512f29701a147f4d8` |
| `android-v05-step12` | `Equinox_v05_Step12.zip` | 17 | 0 | `b81c3bf3dc560904c617a36a807e4b6abfca167d0fe318506fa5984dc0713611` |
| `android-v05-step13` | `Equinox_v05_Step13.zip` | 17 | 0 | `72f99d0807af251fd6f53654324f420b70e4ba747946daac523d035504e8c0ad` |
| `android-v05-step14` | `Equinox_v05_Step14.zip` | 18 | 0 | `c1ed092509cb3d77bd42d7804bdd4adabf7de697b756bff989e58e789bb0ca5a` |
| `android-v05-step065` | `Equinox_v05_Step065.zip` | 12 | 0 | `750497cc50246f653b319adf5fc8b6e33d545fa8d838fa6336f8a935221ee0df` |
| `android-v06-step01-coresplit` | `Equinox_v06_Step01_CoreSplit.zip` | 20 | 0 | `4240e5d6e7c15f3f414899632819da597a9d8159ad9af36ec1dd8ab045357f4c` |
| `android-v06-step02-quantmacrolazy` | `Equinox_v06_Step02_QuantMacroLazy.zip` | 20 | 0 | `00afb69f69675a3f4fcdbc5ae71c39a09638a2f7b04ee51e1c9bc64f42916a8b` |
| `android-v06-step02-quantmacrolazy-fixed` | `Equinox_v06_Step02_QuantMacroLazy_fixed.zip` | 20 | 0 | `235295f1b398ad824d1c5f1431fb0ed6e24fde3ca778ffc850e580a6f62255d7` |
| `android-v06-step03-activelazyexchange` | `Equinox_v06_Step03_ActiveLazyExchange.zip` | 20 | 0 | `0b92fc2644ee358ca57eade1674619b7bc2dea813fabdb9d2181c0853bea0f17` |
| `android-v06-step04-outputcanvasorchestra` | `Equinox_v06_Step04_OutputCanvasOrchestra.zip` | 22 | 0 | `b3314749046a3eed74d3af47160ee20ec0d0fa4dd281354795a1734af931a4cd` |
| `android-v06-step05-livememorymodes` | `Equinox_v06_Step05_LiveMemoryModes.zip` | 22 | 0 | `eca15b1d559ccd7084f733b1888ec721313a1889a4a10a4b8e61df337abeaa40` |
| `android-v06-step06-structuralranks` | `Equinox_v06_Step06_StructuralRanks.zip` | 23 | 0 | `2e732102372e8870307104ebb32ac3f9b1e2aaadd04ea370c3df26632d9c6fa2` |
| `android-v06-step07-templateslots` | `Equinox_v06_Step07_TemplateSlots.zip` | 23 | 0 | `728eb62189bf397bc3dcb058d94c99b663b30ec785d8cbe32263621eca924024` |
| `android-v06-step08-substitutionclasses` | `Equinox_v06_Step08_SubstitutionClasses.zip` | 23 | 0 | `339d6dc398627e9a8ed06883442090e59230908f36f7ed5e7210423b3bea6063` |
| `android-v06-step09-multidimensionalrelevance` | `Equinox_v06_Step09_MultidimensionalRelevance.zip` | 24 | 0 | `df9f0c6b5d2ce78c8b882809af901e3d41c482c866ad8fb4925445ea33d4d993` |
| `android-v06-step10-dualaudioencoder` | `Equinox_v06_Step10_DualAudioEncoder.zip` | 26 | 0 | `46a8111ced1691b98d72cdb9f120cf659f4ac4457fbbb98605f898bb376c0fcc` |
| `android-v06-step11-encoderconvergencelab` | `Equinox_v06_Step11_EncoderConvergenceLab.zip` | 27 | 0 | `46e892c45c6836d2136c3642f72109456b2a66995e6cc11408e60333a763e3c3` |
| `android-v06-step12-adaptivequantizer` | `Equinox_v06_Step12_AdaptiveQuantizer.zip` | 28 | 0 | `c34bd4289100a06f3329fab0aa0521b7fb6046ea7cfd093049d2fe209fb591d8` |
| `android-v06-step13-adaptivehierarchy` | `Equinox_v06_Step13_AdaptiveHierarchy.zip` | 29 | 0 | `d91f0a6f254ab25832678877d7c0c6f9f2b79cb4a034d2df06d9b4b2822eb02e` |
| `android-v06-step14-memorycompetition` | `Equinox_v06_Step14_MemoryCompetition.zip` | 30 | 0 | `e01c7c7c235f47d9f59a8e618753fabf645127cdbb9475957134427cb7a5328c` |
| `android-v06-step15-predictiverecall` | `Equinox_v06_Step15_PredictiveRecall.zip` | 30 | 0 | `3a17fb806ac42dfe7fd87bc8567a5df908a9a1b5e6cc277dc77ad5a0407b5bbe` |
| `android-v06-step16-hybridmode` | `Equinox_v06_Step16_HybridMode.zip` | 30 | 0 | `a6b45686903c25cb0e5a949a4ef4e9ee3172ca23a986993cb9261bde39eab784` |
| `android-v06-step17-continuousaudioio` | `Equinox_v06_Step17_ContinuousAudioIO.zip` | 32 | 0 | `a86886f104098c5d7ffd2561928e4215b3e4e07ba3921e31b8a25fe423ce90ba` |
| `android-v06-step18-adaptivevisualencoder` | `Equinox_v06_Step18_AdaptiveVisualEncoder.zip` | 33 | 0 | `89b922907b0bb1969f60746fc3a0641f9520b845172df248707a886696ce2932` |
| `android-v06-step19-crossmodalmacros` | `Equinox_v06_Step19_CrossmodalMacros.zip` | 33 | 0 | `5daa9868d180eaed5809e139ff45cce7a9ec61ec1d48bed44852640b1dd93de9` |
| `android-v06-step20-memoryvideoengine` | `Equinox_v06_Step20_MemoryVideoEngine.zip` | 34 | 0 | `a62f377519e67a97ddf8083b17620d804df4bfa47b23bf0e4221bcd8c0bbf62a` |
| `android-v06-step21-generativeorchestra` | `Equinox_v06_Step21_GenerativeOrchestra.zip` | 35 | 0 | `72fa83b05709a7821725060a29b7e4bb54e9c146f2f089b3674ca358d2b7e3c8` |
| `android-v06-step22-autonomousmemorydream` | `Equinox_v06_Step22_AutonomousMemoryDream.zip` | 35 | 0 | `a60951613890d0dd81f16c68b5dc924158183aa790cb0bb76a73d2b0702ddb18` |
| `android-v06-step23-indexedlongtermstorage` | `Equinox_v06_Step23_IndexedLongTermStorage.zip` | 36 | 0 | `792fb80781605b7e8b1cb5157ece5ba85b70ff48ddeaa3e037bd4f0d2b866639` |
| `android-v06-step24-longtermhardtest` | `Equinox_v06_Step24_LongTermHardTest.zip` | 37 | 0 | `296d08fc00bcc456d8c15c921d243f5ffe3c0f5240f1e2bd97dbcfce8c28fb0c` |
| `android-v06-step25-unboundedadaptiveresolution` | `Equinox_v06_Step25_UnboundedAdaptiveResolution.zip` | 39 | 0 | `9bb193b89e8420956315f1df2e918d3d587189608d29f17dc24bc86a90cbc92d` |
| `android-v06-step25-unboundedadaptiveresolution-fixed` | `Equinox_v06_Step25_UnboundedAdaptiveResolution_fixed.zip` | 39 | 0 | `068e23833f516d60f38f12a2452ca1ea4aa791312b42581691d58acb7920cc17` |
| `android-v06-step39-hierarchicalrouting` | `Equinox_v06_Step39_HierarchicalRouting.zip` | 62 | 0 | `909c87fd3cc5aead36b9192980137e1416806b7f997bd9467ffcf0ac5f4f467e` |
| `android-v06-step40-recallpredictionhardening` | `Equinox_v06_Step40_RecallPredictionHardening.zip` | 64 | 0 | `46cb78613f8f7dba28bbf79ce835ad0c293c065a92d786e30f0361ea851e4cfe` |
| `android-v06-step41-unifiedaudiomacrovoices` | `Equinox_v06_Step41_UnifiedAudioMacroVoices.zip` | 63 | 0 | `fdeff82e77885f8b7a94639dabcf919de6191ebaf7f5f18c5f5df6fd1695bdec` |
| `android-v06-step42-learnplaydream` | `Equinox_v06_Step42_LearnPlayDream.zip` | 65 | 0 | `f21c5702bc2b8de073d92ba016d6c036c75d0d809d7b1e6757cea9bbcc928678` |
| `android-v06-step43-hypnomode` | `Equinox_v06_Step43_HypnoMode.zip` | 66 | 0 | `34b544f5b79de5f4bf7d6e2cd40ff17af29951b340d7ee7c010cdcdd47833c89` |
| `android-v06-step44-hypnoavsync` | `Equinox_v06_Step44_HypnoAVSync.zip` | 68 | 0 | `f45d72dc705c7cb08c12277b9c41c52ed537cc54b23f3dfdf1382e2933f2cd2d` |
| `android-v06-step45-knowledgelayers` | `Equinox_v06_Step45_KnowledgeLayers.zip` | 72 | 0 | `0eae9a3275899616d45659dd9f54533a9148a9868d252099dba754773c0c5bb2` |
| `android-v06-step46-1-hypnoonlysurface` | `Equinox_v06_Step46_1_HypnoOnlySurface.zip` | 76 | 0 | `09912572fe864c19c3c2f6afdeee861f91bdccdc4a5dbb2b17a491ba4ab33b7a` |
| `android-v06-step46-2-archiveui-stability` | `Equinox_v06_Step46_2_ArchiveUI_Stability.zip` | 77 | 0 | `7d1c27f88e4411a2dbc618bf611833e41921c4c5b17f87beebd65495f0b1f8b6` |
| `android-v06-step46-externalknowledgeimport` | `Equinox_v06_Step46_ExternalKnowledgeImport.zip` | 75 | 0 | `bb5c412e92f58fe34fbadd9042b21bf786574cff57eeb6416ed6bfc0e924078c` |

## Security, licensing, and limitations

Automatic filtering and secret-pattern scanning are incomplete. Review all commits and tags
before public release for privacy, credentials, embedded metadata, and third-party licensing.
Run instructions and system requirements may differ across historic snapshots.
This archive is not a claim that all versions have been independently validated.
No open-source license is granted merely by publishing code.
