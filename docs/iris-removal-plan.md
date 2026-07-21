# Plan de retrait d'Iris embarqué (compat Iris/Oculus externe)

Décision : PauC retire le fork Iris vendored (bloqueur CurseForge, cf. dé-embed DH) tout en restant
compatible avec Iris officiel installé en externe (= **Oculus** sur Forge 1.20.1). Les ombres shaderless
sont déjà assurées par le shadow map PauC (`fr.hoyatla.pauc.shadow`, zéro dépendance Iris).

## Inventaire (2026-07-11)

- 571 classes vendored `net.irisshaders.*` (relocalisées `net.paucshaders` au build).
- 42 fichiers de l'arbre iris contiennent du code PauC (mixins `pauc$`, hooks).
- 18 classes `fr.hoyatla.*` référencent des internals Iris, en 4 catégories :
  1. `ShadowRenderingState.areShadowsCurrentlyBeingRendered()` — 6 classes (culling/budget entités,
     frame metrics) : « suis-je dans la passe shadow du shader ? »
  2. `IrisApi.isShaderPackInUse()` — 3 sites (+ event bridge).
  3. Glue de cycle de vie pipeline (Iris.getPipelineManager, requestPipelineShutdownForClientLogout,
     BuildConfig, ShaderPackScreen, DepthTexture/TextureInfoCache, PauCShaderPackProgramPatches) —
     n'existe QUE parce que le pipeline est embarqué ; meurt avec lui.
  4. `SpriteContentsAccessor` (color cache LOD) — accessor trivial à déménager.

## Phases

- **P1 (fait)** : façade `fr.hoyatla.pauc.shadercompat.PauCShaderCompat` — les deux requêtes chaudes
  par RÉFLEXION avec handles cachés (l'API officielle `net.irisshaders.iris.api.v0.IrisApi` +
  `ShadowRenderingState`). Les chaînes de classe sont réécrites par le relocateur du build → la façade
  résout le vendored AUJOURD'HUI et résoudra l'externe APRÈS retrait, sans changement de code.
  Swap des call sites des catégories 1 et 2. Loi classload : jamais de référence directe.
- **P2 (fait, 2026-07-11)** : foyer mixin PauC créé — `fr.hoyatla.pauc.mixin` + `paucperf.mixins.json`
  (câblé build: mixin{config} + manifest MixinConfigs ; refmap partagée `paucultimate.refmap.json`,
  7 mappings vérifiés dans le jar). Déplacés/extraits : MixinPauCLodFarPlane, MixinPauCLodClouds
  (purs), MixinPauCFogExtension (split de MixinFogRenderer — tout le fog PauC), MixinPauCEntityCulling
  + MixinPauCBlockEntityCulling + MixinPauCParticleBudget (splits des dispatchers/particules),
  PauCSpriteContentsAccessor (le color cache n'a plus AUCUNE dépendance shader-mod).
  RESTE pour P4 : MixinDebugScreenOverlay (lignes HUD PauC), MixinVideoSettingsScreen (réécriture
  sans IrisVideoSettings/QuickAccess), MixinOptions_CloudsOverride, et les touches pauc$ dans les
  classes cœur iris (pipeline/uniforms/HorizonRenderer/DH-compat) qui meurent avec le vendored.
- **P3 (fait, 2026-07-19)** : toute la glue catégorie 3 passe désormais par la façade réflective
  `PauCShaderCompat` — 0 référence directe `net.irisshaders.*` restante dans les 6 classes PauC
  concernées (vérifié). Ajouts façade : `requestPipelineShutdown`, `currentPackName`,
  `currentPackPath`, `describeProgramPatches`, `pipelineSupportsSodiumShadowPass`,
  `createShaderPackScreen` — tous en `resolveStatic`/soft-fail. Swaps : `PauCCompatibility`
  (supportsSodiumShadowPass via façade), `PauCLodDiagnostics` (describeState → façade, segment vide
  après P4), `PauCCompatEventBridge` (requestPipelineShutdownForClientLogout ×2 → façade + import
  retiré), `PauCForgeBootstrap` (ShaderPackScreen → façade, retombe sur l'écran parent après P4).
  BuildConfig : nouvelle classe `fr.hoyatla.pauc.PauCBuildConfig` (buildConfig `forClass`) — PauCIdentity
  ne lit plus le BuildConfig de l'arbre iris. Détection EXTERNE branchée :
  `PauCLodShaderContext.pollExternalShaderState()` (client tick ~1s, no-op tant que le push vendored
  agit). VÉRIF bytecode : le relocateur réécrit les chaînes réflectives (`supportsSodiumShadowPass`
  → `supportsPauCorShadowPass`, `net.irisshaders.iris.*` → `net.paucshaders.pauc.*`) exactement comme
  les refs code → résout le vendored aujourd'hui, résoudra l'externe après retrait de la relocation (P4).
  `PauCLodLateDepthBuffer` (DepthTexture/TextureInfoCache) laissé tel quel : ses 2 seuls appelants
  vivent dans l'arbre iris vendored, meurent avec lui en P4.
- **P4** — décidé en 2 COUPES (2026-07-19, user). Précondition FAITE : dernière dépendance dure
  `fr.hoyatla → net.irisshaders` retirée (`PauCForgePlatformServices.getModVersion` retombe sur
  `PauCIdentity`, plus sur `Iris.getVersion`) ; seul `PauCLodLateDepthBuffer` référence encore iris et
  meurt avec l'arbre. Les 2 chaînes restantes (`PauCCompatibilityGuards` classPresent, `FarChunkPlacementSource`
  préfixe) sont des probes string bénignes, correctes post-P4, laissées.
  **CONSTAT CRITIQUE (blocage Coupe 1)** : les 7 configs mixins actives → SEUL `paucperf.mixins.json`
  (`fr.hoyatla.pauc.mixin`, 6 mixins purs) est hors arbre iris. Les 6 autres sont en package
  `net.irisshaders.*` MAIS `paucultimate-forge.mixins.json` (~30 mixins) et une partie de
  `paucultimate.mixins.json` (76 mixins) contiennent le CŒUR PauC : `MixinMobAiThrottler`,
  `MixinNaturalSpawnerThrottle`, `MixinPauCAsyncPathFinder`, `MixinPoiManagerQueryCache`,
  `MixinPathNavigationCircuitBreaker`, `MixinPauCVideoSettings` (écran d'options), worldgen, compat DH.
  → dé-enregistrer ces configs briquerait le perf-core + l'écran d'options + DH compat. La suppression
  de l'arbre est donc IMPOSSIBLE tant que ces mixins PauC ne sont pas EXTRAITS vers `fr.hoyatla.pauc.mixin.*`
  (chaque déplacement doit préserver target + refmap `paucultimate.refmap.json`).
  - **Coupe 1a (FAIT, jar 22:36, à LANCER-tester avec Oculus)** : 12 mixins perf-core serveur + worldgen
    (MobAiThrottler, NaturalSpawnerThrottle, PauCAsyncPathFinder, PathNavigationCircuitBreaker,
    PoiManagerQueryCache, ItemEntityMergeThrottle, MinecraftServerTickRuntime, Minecraft/ServerLevel
    ShutdownBarrier, ServerLevelStructureCircuitBreaker, HangingEntityInvalidPosition, WorldGenRegion) —
    tous CLEAN (0 import iris/seibel) — déplacés `net.irisshaders.iris.mixin.forge` →
    `fr.hoyatla.pauc.mixin.forge`, nouveau config `paucultimate-pauc-forge.mixins.json` (pas de plugin DH,
    defaultRequire 0), câblé au manifeste. VÉRIFIÉ jar : 12 .class présentes, config listé MixinConfigs,
    refmap régénéré vers fr/hoyatla, classes hors arbre relocalisé. Le refmap se régénère seul au build
    (annotation-processor) → déplacer sources + rebuild suffit.
  - **Coupe 1b (FAIT, jar déployé)** : 10 mixins CLEAN de plus déplacés vers `fr.hoyatla.pauc.mixin.forge`
    (MixinPauCVideoSettings=écran options, MixinInstancedIcosphere, + mod-compat @Pseudo : BeyondTheAbyss×2,
    CreateGoggle, Sandworm×2, Xaero, Voicechat, ClientChunkCacheRetention). Ajoutés au config PauC (bloc
    "client" créé). VÉRIF jar : 22 .class fr.hoyatla total (1a+1b), refmap régénéré, hors arbre relocalisé.
    CORRECTION méthodo : la 1re classification (grep sous-packages étroit) ratait `mixinterface`/`Iris` →
    MixinRenderFlame + MixinShaderInstance sont en fait STAY-iris. Grep LARGE (`^import net.(iris|pauc)shaders.`
    hors package mixin.forge) = la bonne méthode.
  - **Coupe 1c (FAIT, jar déployé) — COUPE 1 COMPLÈTE** : plugin DH migré
    `net.irisshaders.iris.compat.dh.DHMixinConfigPlugin` → `fr.hoyatla.pauc.compat.dh.PauCDhMixinConfigPlugin`
    (dépendance `IrisPlatformHelpers.isModLoaded` remplacée par `PauCEmbeddedDhRuntime.isDistantHorizonsPresent()`
    = probe réflectif `com.seibel...DhApi`, framework-indépendant ; gating par NOM `PauCDh`/`.compat.dh.` donc
    valide après déplacement). Ancien plugin SUPPRIMÉ. 12 mixins DH-compat (sans import iris, juste com.seibel)
    déplacés vers `fr.hoyatla.pauc.mixin.forge.compat` + ajoutés au config PauC (qui porte maintenant le plugin DH).
    Les 2 configs (`paucultimate-pauc-forge`, `paucultimate-compat-dh`) référencent le plugin PauC. VÉRIF jar :
    plugin PauC présent, ancien absent, 12 DH + 34 forge total dans fr.hoyatla, refmap OK.
    **BILAN Coupe 1 : TOUS les mixins PauC (perf-core, worldgen, UI video-settings, mod-compat, DH-compat) +
    le plugin DH sont HORS de l'arbre iris.** Ne restent dans l'arbre QUE du shader/DH-sous-shader qui MEURT
    avec lui en Coupe 2 : 7 dans `paucultimate-forge` (DhLevelRenderer, DhTerrainShaderProgram, ItemBlockRenderTypes,
    ShaderInstance, RenderFlame, ShadowRenderer, VBOIE), les 4 frustum de `paucultimate-compat-dh`, et les configs
    purs shader (paucultimate 76, vertexformat 11, batched-entity 11, fantastic 5). MixinServerLevelFarQueryGuard =
    fichier mort non-enregistré, meurt aussi. **Coupe 2 est débloquée.** À tester sans Oculus/sans DH : le plugin
    doit VÉTO-er les 12 DH (pas de CNFE com.seibel) → si ça charge, le plugin migré marche.
    Historique de la ligne d'origine, pour référence : 24 mixins compat étaient
    (a) client mod-compat SANS deps iris (BeyondTheAbyss, CreateGoggle, Sandworm×2, Xaero, Voicechat,
    InstancedIcosphere, RenderFlame, ClientChunkCacheRetention, MixinPauCVideoSettings=écran options) →
    déplaçables comme 1a ; (b) DH-compat `MixinPauCDh*` (ChunkMap, ServerPlayer, UtilBackgroundThread,
    WorldGenerationQueue, + les client cloud/render) → besoin du gating DH, MIGRER le
    `DHMixinConfigPlugin` (common/.../iris/compat/dh/) vers un namespace PauC d'abord ; (c) 6 mixins qui
    IMPORTENT des internals shader iris (MixinShadowRenderer, MixinVBOIE, MixinItemBlockRenderTypes=PBR,
    MixinRenderMekasuit, MixinPauCDhLevelRenderer, MixinPauCDhTerrainShaderProgram) → RESTENT, meurent en Coupe 2.
    Idem pour les configs vertexformat/batched-entity/fantastic (purs shader) + les 76 de paucultimate.mixins.json.
  - **Coupe 2 (après validation)** : supprimer l'arbre `net/irisshaders` + relocation + configs iris
    résiduels + `PauCLodLateDepthBuffer` + migration assets (lang options.pauc.*, clouds_26.png) + mods.toml
    (retirer `provides pauc_shader`). Features shaderless perdues (batched entity, PBR, mipmaps) : acter.
- **P5** : matrice de tests externe : sans shader mod / Oculus seul / Oculus+pack (Photon/Solas/Sildur).
  Décision : LODs sous shader externe (cachés tant que pas d'intégration gbuffer via l'API externe).
- **P6** : packaging CurseForge.

## BLOCAGE OCULUS DÉCOUVERT (2026-07-19, launch-test)

PauC + Oculus externe → **crash JPMS au boot** (avant chargement des mods, donc PAS lié aux mixins) :
`ResolutionException: Modules oculus and glsl.transformer.pre6 export package
io.github.douira.glsl_transformer.ast.node.external_declaration`. PauC embarque en jar-in-jar
`META-INF/jarjar/glsl-transformer-2.0.0-pre6.jar` (+ `jcpp-1.4.14.jar`), Oculus embarque la MÊME lib →
split-package. Le relocateur PauC réécrit `net.irisshaders`→`net.paucshaders` et `Sodium`→`PauCor`
MAIS PAS `io.github.douira.glsl_transformer` (ni dans le JiJ, ni dans les refs du code vendored).
**Conséquence : impossible de cohabiter avec Oculus tant que la lib glsl n'est pas soit relocalisée,
soit retirée (Coupe 2).** Deux voies : (A) STOPGAP — ajouter `io.github.douira`→`net.paucshaders.douira`
(ou `io.github.paucdouira`) à la relocation, Y COMPRIS l'intérieur du JiJ glsl + jcpp + les refs du
code Iris vendored (build surgery, à itérer) ; (B) attendre Coupe 2 qui supprime tout le pipeline
vendored + ses JiJ → Oculus devient l'unique fournisseur glsl. Pour valider Coupe 1a en attendant :
lancer PauC **sans** Oculus (le désactiver), PauC seul fournit glsl → pas de conflit.

## FIX CHIRURGICAL OCULUS (2026-07-20) — avant la Coupe 2 complète

Constat en lisant la relocation neoforge : PauC relocalise TOUT (`iris→pauc`, `sodium→paucor`,
`oculus→paucus`, `fabric→forgex`, `indium→paucin`) → le pipeline shader vendored est un UNIVERS
AUTO-CONTENU (`net.paucshaders`/`paucor`) qui ne cible jamais le vrai Sodium/vanilla d'Oculus. Donc le
risque n°1 « conflit mixins Oculus » est en réalité FAIBLE (les mixins PauC visent leurs propres classes
relocalisées). **Le SEUL blocage dur = le JiJ glsl-transformer** (non relocalisé en interne → split-package
JPMS avec le glsl d'Oculus). Fix appliqué : retiré les `jarJar(glsl-transformer)` + `jarJar(jcpp)` de
neoforge/build.gradle.kts (gardé `minecraftLibrary` compile-only). PauC ne fournit plus `io.github.douira`
→ Oculus seul fournisseur → plus de split. Le stack shader PauC reste DORMANT (shaders disabled), ne parse
pas de shader → pas besoin de glsl au runtime. VÉRIF jar : JiJ glsl+jcpp absents. À LANCER-tester : PauC +
Oculus + DH doivent tous charger (pas de crash JPMS, pas de CNFE glsl runtime). Réversible (ré-ajouter 6 lignes).
Après retrait glsl, un 2e crash JPMS est apparu (`kroppeb.stareval`), puis scan COMPLET des packages
partagés PauC∩Oculus = exactement 2 familles de libs tierces embarquées non-relocalisées : `kroppeb.stareval.*`
(source, common/src/main/java/kroppeb) + `de.odysseus.ithaka.digraph.*` (source, sourceset vendored). Fix :
ajout à `sameLengthJarReplacements` de `kroppeb`→`paukrob` + `odysseus`→`paucseus` (renomme entrées + contenu),
et `de/odysseus/` à `sanitizableJarClassPrefixes`. VÉRIF : intersection packages PauC∩Oculus (hors net/minecraft)
= VIDE. kroppeb→paukrob, de.odysseus→de.paucseus dans le jar ; Oculus garde les originaux → 0 split-package.
glsl reste RETIRÉ (compileOnly, stack dormant ne le touche pas) ; kroppeb/odysseus RELOCALISÉS (présents, renommés)
donc le code vendored les résout au runtime. À LANCER-tester : PauC+Oculus+DH chargent. Si CNFE glsl runtime →
le stack shader tente de parser (improbable, dormant) ; sinon OK.
Puis 3e crash (00:58, APRÈS les fixes module) : `InjectionError MixinGlStateManager` (paucultimate.mixins.json) —
`pauc$increaseMaximumAllowedTextureUnits` échoue car les mixins SHADER vendored visent des classes vanilla
partagées (GlStateManager/blaze3d) qu'Oculus transforme AUSSI. C'EST le vrai conflit mixin (le risque n°1 EXISTE
pour les mixins ciblant vanilla, pas les targets relocalisés). Fix : DÉ-ENREGISTRER les 6 configs shader
(paucultimate 76, compat-dh, vertexformat, batched-entity, fantastic, forge STAY) du `mixin{config(...)}` ET de
la chaîne `MixinConfigs`. Ne restent que `paucperf` + `paucultimate-pauc-forge` (les 2 configs 100% PauC). Le
stack shader vendored ne s'applique plus (classes mortes dans le jar). Features shaderless perdues (batched-entity,
mipmaps, PBR) = actées, Oculus les remplace. VÉRIF jar : MixinConfigs = 2 configs PauC. À LANCER-tester.
POINT DE VIGILANCE : si le boot « PauC Shader » (init pipeline vendored) tourne encore sans ses mixins et crashe,
il faudra le gater/désactiver. La Coupe 2 complète (retrait des 640 fichiers) reste le but P4 mais ces désactivations
suffisent à cohabiter avec Oculus. Étape suivante demandée par user : DH pour accélérer la génération LOD PauC.

## DÉCISION 2026-07-20 : Oculus ABANDONNÉ, PauC reste standalone (shaders intégrés)

Après la cascade de crashes, cause racine trouvée : **Oculus exige Sodium/Embeddium** (`net.caffeinemc.mods.sodium.*`,
ex. MemoryIntrinsics) — dépendance dure. Or PERSONNE ne le fournit : user refuse Embeddium ; **PauC n'embarque PAS
de Sodium** (vérifié : 0 package net.caffeinemc dans le jar ; ce que je croyais être « PauCor=Sodium » est en fait
la couche de COMPAT Iris↔Sodium = `net/paucshaders/pauc/compat/paucor/mixin/*`, des mixins qui CIBLENT Sodium, pas
Sodium) ; embeddium = dépendance OPTIONNELLE dans mods.toml, pas un provides. Donc Oculus ne peut PAS tourner ici,
aucune modif PauC ne peut fabriquer Sodium. User a choisi : **utiliser les shaders intégrés de PauC** (pas Oculus,
pas Embeddium) + DH pour les LODs. → TOUS les bricolages Oculus de cette session ANNULÉS (glsl+jcpp JiJ restaurés,
relocation kroppeb/odysseus retirée, 6 configs mixins shader re-enregistrées). PauC = état standalone fonctionnel.
CONSERVÉ (propre, sans risque standalone) : P1-P3 façade réflective, Coupe 1a/1b/1c (34 mixins PauC extraits vers
fr.hoyatla + plugin DH migré). P4 (retrait Iris) = PAUSÉ tant que user veut les shaders PauC. User doit
DÉSACTIVER Oculus dans le pack. Prochaine tâche : brancher DH pour accélérer/améliorer la génération LOD PauC.

## Risques majeurs

- Conflits de mixins avec Oculus externe sur les mêmes cibles Sodium/vanilla → P4 doit supprimer tout
  jeu de mixins « métier Iris » ; ne garder que les cibles PauC pures.
- Le mod ne doit plus fournir/被 confondre avec l'id « oculus »/« iris » (vérifier mods.toml, IMC).
- Features perdues shaderless après retrait : batched entity rendering, PBR, mipmaps — acter.
