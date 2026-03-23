# Pain au Choc ultimate de Ouf (PauC UO) - Forge 1.20.1

Version courante: `2.0.0-ultimate`

Pain au Choc ultimate de Ouf est un mod all-in-one de performance Minecraft Forge 1.20.1. Il integre un renderer de chunks optimise (Embeddium-like), un pipeline de shaders deferes (Oculus-like), un gouverneur global client + serveur integre, des budgets de rendu, un arbitrage runtime autoritaire, de la lisibilite camera et de la stabilite sous charge.

## Passation

Pour transfert a un nouveau mainteneur, commencer par:

- `TRANSFERT_PROJET.md`
- `ROADMAP_GOUVERNEUR_ULTIMATE_2026_2027.md`
- `SUIVI_SESSIONS_ROADMAP.md`

## Etat courant

- Mod `client-only`.
- Proxy terrain PauC distant actif, y compris a `qualityLevel=10` tant que PauC est active.
- Entity LOD local actif.
- Objectif principal: optimiser ensemble le client et le serveur integre.
- Priorites: stabilite, frametime, `MSPT`, lisibilite camera, puis FPS moyen.

## Fonctionnalites actives

### Pipeline de rendu de chunks (Embeddium-like)
- Format de vertex compact (20 octets/vertex au lieu de 32 vanilla).
- Compilation de mesh multi-thread avec back-pressure.
- Occlusion culler BFS avec matrice de visibilite 6x6 encodee en `long` 64-bit.
- Rendu terrain GPU par multidraw batching.
- Gestion de sections par region.
- Mixins remplacant le rendu de chunks vanilla.
- Integration complete avec le gouverneur, le budget et le proxy terrain PauC.

### Pipeline de shaders deferes (Oculus-like)
- Chargeur de shaderpacks OptiFine (ZIP + dossier, `#include`, macros).
- Resolution `#include` renforcee (relatif + absolu `/shaders/...`) avec limite de profondeur.
- Rendu GBuffer (`colortex0-7`, `depthtex0-2`).
- Shadow mapping avec distance adaptative par mode gouverneur.
- Passes deferred + composite + final.
- Systeme d'uniforms (camera, celestial, temps, brouillard, uniforms PauC exclusifs).
- Suivi des phases de rendu (`WorldRenderingPhase`) pour les programmes `gbuffers_*`.
- UI de selection de shaderpack dans l'ecran F10 (cycle, reload, dossier).
- Mode de compatibilite deferred `strict/balanced/fast` (cycle F10, persistance config).
- Fallback automatique de compatibilite deferred a l'activation (`strict -> balanced -> fast`).
- Defines GLSL injectees selon le mode deferred (`PAUC_DEFERRED_MODE_*`).
- Rapport de compatibilite pack (warnings) visible dans logs/pipeline debug.
- Persistance du shaderpack selectionne dans la config.
- Activation automatique du shaderpack sauvegarde au demarrage.
- Support PBR: detection automatique des textures normal (`_n`) et specular (`_s`), fallback 1x1 par defaut.

### Optimisations de rendu de blocs
- Culling intelligent des feuilles: faces internes entre blocs de meme type supprimees (`LeavesBlockMixin`).
- Detection de sections single-value pour skip de rendu.
- Throttling d'animations de sprites par mode gouverneur et niveau de qualite.
- Hook d'optimisation palette (`PalettedContainerMixin`).

### Optimisations de rendu d'entites
- LOD-aware model parts: skip des parties non essentielles a distance (`ModelPartMixin`).
- Multiplicateur de spawn de particules par mode gouverneur (CRISIS=0.15, BASE=1.0).
- Distance de rendu des particules adaptative par qualite via `ParticleEngineMixin`.
- Simplification des `ItemEntity` en billboard a distance.

### Gouvernance et performance
- Pilotage dynamique de plusieurs options video selon un niveau de qualite `1..10`.
- Gouverneur global runtime avec modes `exploration`, `combat`, `transit`, `base`, `crisis`.
- Runtime autoritaire `AuthoritativeRuntimeController`.
- Culling selectif entites, ombres, particules et block entities.
- Entity LOD local:
  - `<20` blocs: rendu normal
  - `20-40`: animation simplifiee
  - `40-80`: pose quasi statique + decimation de frames
  - `>80`: impostor billboard
- Bridge GeckoLib optionnel.
- Streaming de chunks prioritaires autour du joueur.
- Priorisation dynamique de file de compilation chunks (`NONE`/`NEARBY`/`PLAYER_AFFECTED`) + back-pressure.
- Rayon de chunks gere distinct du `renderDistance` vanilla.
- Proxy terrain lointain PauC au-dela du rayon vanilla, avec cache ephemere, relief plus dense, rendu adaptatif par distance et palier ultra-loin (impostor simplifie).
- Latency controller + frame time stabilizer.
- Detecteur bottleneck GPU/CPU.
- Adaptive frame cap.
- Overrides video runtime par tier de stress (graphics/clouds/particles/shadows/render distance/simulation distance).
- Dynamic Resolution Scaling.
- Garde-fou stabilite DRS: DRS force OFF automatiquement quand le pipeline deferred interne est actif (securite anti-black-frame).
- Adaptive Simulation Distance.
- Adaptive Quality Controller (baisse auto sous pression, recovery progressif).
- Anti-pics MSPT serveur integre (tier de mitigation + fenetre d'urgence).
- Couplage anti-pics MSPT -> chunks: rayon streaming/proxy, cadence scan streaming et budget compile chunks durcis automatiquement selon `mitigation tier` et `emergency hold`.
- Presets utilisateurs F10 (`safe`, `balanced`, `competitive 240`, `cinematic`) + recovery one-click.
- Particle Budget System dynamique (`200..3000`).
- Logs runtime explicites des transitions critiques: `DRS on/off + reason`, `proxy on/off + reason`, `shader upscaler route native/drs + deferred mode`.
- Upscale shader pipeline interne/externe.
- Shaderpacks externes PauC multi-pass.
- Mode `Sharp` interne base sur RCAS avec intensite reglable.

### Debug overlay F3
- Affichage dans l'ecran F3 de l'etat PauC: qualite, budget, mode gouverneur, pression, autorite, statut serveur (`MSPT/tier/emergency`), simulation distance adaptive, cadence IA serveur, chunks visible/total, anneaux visibles (`full/stream/deferred/culled`), compteurs block entities (`visible_culled/global`), backlog/budget upload GPU, shader actif, pipeline deferred (pack + etat).

## Ce que la variante 2.0 ajoute

- Renderer de chunks Embeddium-like integre nativement dans PauC.
- Pipeline de shaders deferes Oculus-like integre nativement dans PauC.
- Support des shaderpacks OptiFine standard depuis `shaderpacks/` (meme emplacement qu'Iris/Oculus).
- Shadow mapping avec distance adaptative par mode gouverneur (skip en CRISIS haute pression).
- Hooks de phase entites et block-entities pour les programmes `gbuffers_*`.
- Optimisations de rendu de blocs: culling feuilles, throttle animations sprites, hook palette.
- Optimisations de rendu d'entites: LOD model parts, multiplicateur particules, distance billboard adaptive.
- Support PBR: normal maps (`_n`), specular maps (`_s`), detection auto, fallback 1x1.
- Le runtime autoritaire reconnait le pipeline deferred interne (pas de yield a soi-meme).
- Debug overlay F3 avec l'etat complet du pipeline PauC.
- Classification de la stack en `delegated backend`, `passive`, `forbidden`, `high-risk`.
- Statut d'autorite runtime `sovereign`, `contested`, `degraded`.
- Pression pack injectee dans le gouverneur global.
- Penalite compile chunks et throttle chunk streaming quand un domaine est conteste ou qu'un risque worldgen est detecte.
- Decouplage entre rayon detail vanilla, rayon de streaming PauC et rayon proxy jusqu'a `256` chunks geres.
- Cache ephemere de proxies terrain alimentes par les chunks charges.
- Capture proxy predictive avec biais vers l'avant du joueur selon le mouvement et le mode runtime.
- Backend shaderpack PauC multi-pass en dossier ou `.zip` (en plus des packs OptiFine).
- Passes built-in PauC: `fxaa_photon`, `fxaa_elite`, `shadow_lift`, `light_clarity`, `warm_tonemap`.
- Controle shader depuis l'ecran `F10` avec cycle, reload et ouverture du dossier.
- Niveau `10` conserve maintenant le runtime PauC actif au lieu de couper tout le budget.

## Valeurs par defaut de la variante

- Qualite par defaut: `7`
- Implication CPU par defaut: `3`
- DRS min par defaut: `0.70`
- Sharpening RCAS par defaut: `0.40`
- Runtime autoritaire actif par defaut: `true`
- Configuration separee: `config/pauc_ultimate_de_ouf.properties`
- Dossier shaders internes/externes separe: `pauc_ultimate_de_ouf_shaders/`

## Compatibilite runtime

PauC integre maintenant nativement le rendu de chunks et le pipeline shader. Les mods externes sur ces domaines sont contestes:

- `embeddium` / `rubidium`: domaine `render_backend` conteste (PauC possede nativement ce domaine).
- `oculus` / `iris`: domaine `shader_pipeline` conteste (PauC possede nativement ce domaine).
- `servercore` / `vmp`: modules passifs toleres.
- `geckolib`: rendu entites passif tolere.
- stack replay: domaine `capture_pipeline` conteste.
- `distanthorizons`: domaine `chunk_streaming` conteste.
- `flerovium`: domaine `render_backend` conteste.
- `expandedworld`: domaine `worldgen` marque `high-risk`.

Les shaderpacks OptiFine standard sont chargeables nativement par PauC depuis `shaderpacks/`, sans besoin d'Oculus/Iris. Les shaderpacks PauC multi-pass restent aussi supportes depuis `pauc_ultimate_de_ouf_shaders/packs/`.

## Raccourcis

- `F6`: cycle preset utilisateur (`safe`/`balanced`/`competitive240`/`cinematic`).
- `F7`: recovery one-click (applique profil safe + deferred OFF).
- `F8`: activer/desactiver Pain au Choc.
- `F9`: cycle du niveau qualite.
- `F10`: ouvrir la configuration.

## Parametres F10

- Toggle global mod.
- Slider qualite `1..10`.
- Slider implication CPU `1..3`.
- Toggle adaptive quality.
- Toggle frame time stabilizer.
- Toggle detecteur bottleneck GPU/CPU.
- Cycle preset utilisateur.
- Application preset selectionne.
- Recovery one-click (profil safe + pipeline deferred coupe).
- Toggle RCAS.
- Slider intensite RCAS.
- Statut d'autorite runtime et resume des domaines contestes.
- Resume du rayon gere PauC.
- Statut streaming (known/active/deferred + rayons full/stream + throttle).
- Statut du terrain proxy (cache et rendu).
- Raison explicite si le proxy est coupe.
- Ligne diagnostic actionnable (pression/tier serveur + action recommandee).
- Cycle shader actif.
- Reload shaders externes.
- Ouverture du dossier shader.
- Cycle shaderpack deferred (OptiFine).
- Cycle mode deferred (`strict/balanced/fast`).
- Reload shaderpack deferred.
- Ouverture du dossier `shaderpacks/`.

## Configuration disque

Fichier: `config/pauc_ultimate_de_ouf.properties`

Cles principales:

- `enabled=true|false`
- `authoritativeRuntimeEnabled=true|false`
- `qualityLevel=1..10`
- `qualityLevel=10` garde le runtime PauC actif avec simplifications minimales.
- `dynamicResolutionEnabled=true|false`
- `dynamicResolutionMinScale=0.50..1.00`
- `adaptiveSimulationDistanceEnabled=true|false`
- `adaptiveQualityEnabled=true|false`
- `cpuInvolvementLevel=1..3`
- `frameTimeStabilizerEnabled=true|false`
- `gpuBottleneckDetectorEnabled=true|false`
- `advancedSharpeningEnabled=true|false`
- `advancedSharpeningStrength=0.0..1.0`
- `activePreset=safe|balanced|competitive240|cinematic`
- `activeShaderKey=<shader actif>`
- `deferredShaderPack=<nom du shaderpack OptiFine ou (off)>`
- `deferredCompatibilityMode=strict|balanced|fast`

## Build

Pre-requis:

- Java 17
- Gradle Wrapper

Commandes:

```bash
./gradlew.bat compileJava -x test
./gradlew.bat jar
```

Sortie:

- `build/libs/pauc-ultimate-de-ouf-2.0.0-ultimate.jar`

## Dossier de contexte

- `DOSSIER_PROJET.md`: contexte persistant + decisions.
- `ULTIMATE_DE_OUF_PLAN.md`: cadrage de la variante et du runtime autoritaire.
- `ROADMAP_GOUVERNEUR_ULTIMATE_2026_2027.md`: roadmap complete (phases, dates, objectifs, criteres d'acceptation, risques).
- `SUIVI_SESSIONS_ROADMAP.md`: journal obligatoire de fin de session pour le suivi de roadmap.
- `PROXY_TERRAIN_ARCHITECTURE.md`: architecture, logique predictive, limites et checklist de reprise du proxy terrain.
- `SHADERPACK_BACKEND_ARCHITECTURE.md`: format, limites et reprise du backend shaderpack PauC.
- `CHANGELOG.md`: historique des releases.
- `TRANSFERT_PROJET.md`: etat de passation, blocages ouverts, plan de reprise mainteneur.
- `ETUDE_10_OPTIMISATIONS_HARDCORE.md`: analyse approfondie des pistes hardcore.
- `PLAN_TESTS_AB_PAUC.md`: protocole A/B officiel.
- `RESULTATS_TESTS_AB_PAUC.csv`: grille de saisie des mesures.

## Discipline documentaire

En plus du code, la documentation de pilotage est obligatoire:

- Ordre de maintenance: au minimum toutes les 1 heure, mettre a jour la documentation de suivi en cours de session pour ne pas perdre le contexte en cas de crash, plantage, redemarrage force ou autre interruption.
- Outil checkpoint manuel: `.\tools\append_doc_checkpoint.ps1 -Message "..."`.
- Outil heartbeat (periodique): `.\tools\run_doc_heartbeat.ps1 -Iterations 6 -IntervalMinutes 60`.
- Verification de fraicheur doc: `.\tools\verify_doc_freshness.ps1 -MaxAgeMinutes 60`.
- A chaque fin de session, mise a jour de `SUIVI_SESSIONS_ROADMAP.md`.
- Si objectifs, priorites, risques ou planning changent: mise a jour de `ROADMAP_GOUVERNEUR_ULTIMATE_2026_2027.md`.
- Si blocages de reprise changent: mise a jour de `TRANSFERT_PROJET.md`.
- Mode execution actif: automatisation `Codex-first` avec uniquement 3 validations humaines obligatoires (produit, QA in-game, hardware/drivers).

## Outils de test A/B

Profils preconfigures dans `tools/`:

- `pauc_profile_baseline_off.properties`
- `pauc_profile_stable.properties`
- `pauc_profile_aggressive.properties`
- `pauc_profile_safe.properties`
- `pauc_profile_balanced.properties`
- `pauc_profile_competitive240.properties`
- `pauc_profile_cinematic.properties`

Application auto vers PrismLauncher:

```powershell
.\tools\apply_pauc_profile.ps1 -Profile baseline_off
.\tools\apply_pauc_profile.ps1 -Profile stable
.\tools\apply_pauc_profile.ps1 -Profile aggressive
.\tools\apply_pauc_profile.ps1 -Profile safe
.\tools\apply_pauc_profile.ps1 -Profile balanced
.\tools\apply_pauc_profile.ps1 -Profile competitive240
.\tools\apply_pauc_profile.ps1 -Profile cinematic
```

Si PrismLauncher n'est pas detecte, `apply_pauc_profile.ps1` bascule automatiquement sur `.\config\pauc_ultimate_de_ouf.properties` (run local dev/IDE).

Telemetrie runtime (Phase 0):

- Export CSV automatique in-game: `run/pauc_telemetry/runtime_metrics.csv`
- Resolution auto du chemin metrics (repo + PrismLauncher): `.\tools\resolve_pauc_metrics_path.ps1`
- Synchronisation locale des metrics externes (optionnel): `.\tools\sync_pauc_telemetry.ps1`
- Colonnes etendues: stream/proxy/upload + compteurs visibles par anneaux + compteurs block entities (`visible_block_entities/global_block_entities`) + metriques compile chunks (`chunk_compile_budget_preview/chunk_compile_backpressure/chunk_builder_backpressure/chunk_builder_pending`) + statut deferred (mode/pack/warnings) + mitigation serveur (`tier/emergency`) + statut simulation distance adaptive (applique/base/min/cooldown/TPS/adjustments) + cadence IA serveur (cadence selector/navigation + ratios run lisses) + etat runtime `drs/proxy` (avec raison) + route upscaler (`native|drs`).
- Resume KPI:

```powershell
.\tools\summarize_pauc_metrics.ps1
```

Injection directe d'un run telemetrie dans la matrice A/B:

```powershell
.\tools\append_ab_result_from_metrics.ps1 -Scene scene_1_village -Profile B1_stable
.\tools\append_ab_result_from_metrics.ps1 -Scene village -Profile stable -LastSeconds 180
```

Notes A/B:

- aliases acceptes: scenes (`village|fast_move|combat_particles|modded_base`) et profils (`baseline|stable|aggressive|safe|balanced|competitive240|cinematic`).
- ecriture par defaut en mode `upsert` (met a jour la ligne existante scene+profile de la matrice au lieu d'ajouter un doublon).
- utiliser `-ForceAppend` pour forcer un append brut.
- filtres de fenetre disponibles: `-LastSamples`, `-LastSeconds`, `-FromTimestamp`, `-ToTimestamp`.

Workflow capture segmentee start/finish (recommande en campagne):

```powershell
.\tools\ab_mark_start.ps1 -Scene village -Profile stable
# ... jouer la scene ...
.\tools\ab_mark_finish.ps1
```

Ce flux extrait uniquement les nouvelles lignes metrics depuis `ab_mark_start`, puis alimente la matrice via `append_ab_result_from_metrics.ps1`.
Les scripts `ab_mark_start`, `ab_mark_finish` et `ab_campaign_next` tentent automatiquement de resoudre `runtime_metrics.csv` depuis le repo ou PrismLauncher (instance specifique possible via `-PrismInstanceName` / `-InstanceName`).

Workflow capture guidee (anti-aleatoire):

```powershell
.\tools\run_guided_ab_capture.ps1 -Scene village -Profile stable -DurationSeconds 180 -InstanceName test -OverwriteCapture
```

Ce script impose un timer fixe, affiche un protocole scene, puis lance `ab_mark_finish` avec un seuil minimal de nouvelles lignes pour eviter les captures trop courtes ou bruites.

Orchestration "next step" (prochaine case manquante):

```powershell
.\tools\ab_campaign_next.ps1
.\tools\ab_campaign_next.ps1 -InstanceName "1.20.1(1)"
.\tools\ab_campaign_next.ps1 -ApplyProfile -StartCapture
```

Enchainement automatique apres une fin de capture:

```powershell
.\tools\ab_mark_finish.ps1 -AutoPrepareNext
.\tools\ab_mark_finish.ps1 -AutoPrepareNext -ApplyProfileForNext
```

Verification statique compat shaderpacks (`strict/balanced/fast`):

```powershell
.\tools\check_shaderpack_compat.ps1 -ShaderpacksDir .\shaderpacks
```

Preflight QA Phase 6 (compile + compat packs + resume metrics + rapport):

```powershell
.\tools\run_phase6_preflight.ps1
.\tools\run_phase6_preflight.ps1 -PrismInstanceName "1.20.1(1)"
.\tools\run_phase6_preflight.ps1 -PrismInstanceName "1.20.1(1)" -SyncTelemetryToRepo
```

Notes fenetre metrics preflight:

- par defaut, les gates metrics utilisent la derniere session contigue detectee (anti-pollution par historique ancien).
- pour revenir a l'historique complet: `-UseFullMetricsHistory`.
- pour limiter encore la fenetre: `-MetricsTailSeconds <n>` et/ou `-MetricsTailSamples <n>`.

Preflight avec checkpoint documentaire automatique:

```powershell
.\tools\run_phase6_preflight.ps1 -WriteDocCheckpoint
```

Preflight avec verification de fraicheur documentaire:

```powershell
.\tools\run_phase6_preflight.ps1 -CheckDocFreshness -DocFreshnessMaxAgeMinutes 60
```

Audit matrice A/B:

```powershell
.\tools\audit_ab_results.ps1 -ResultsPath .\RESULTATS_TESTS_AB_PAUC.csv
.\tools\ab_campaign_status.ps1 -ResultsPath .\RESULTATS_TESTS_AB_PAUC.csv
```

Preflight avec audit A/B (mode strict optionnel):

```powershell
.\tools\run_phase6_preflight.ps1 -CheckAbMatrix
.\tools\run_phase6_preflight.ps1 -CheckAbMatrix -CheckAbProgress
.\tools\run_phase6_preflight.ps1 -CheckAbMatrix -StrictAbMatrix
```

Estimation readiness beta a partir du dernier rapport preflight:

```powershell
.\tools\assess_beta_readiness.ps1
.\tools\assess_beta_readiness.ps1 -FailBelowThreshold
```

Notes readiness:

- Gates bloquants actifs par defaut: `compile`, `kpi_gate`, `ab_audit`, `ab_progress`.
- La decision est `not_ready` si un gate bloquant est en echec, meme si le score global depasse le seuil.
- Override possible pour analyse exploratoire: `-DisableBlockingGates` ou personnalisation via `-BlockingGateKeys`.

Creation d'un dossier "beta candidate" (preflight + readiness + jar + manifeste):

```powershell
.\tools\build_beta_candidate.ps1
.\tools\build_beta_candidate.ps1 -PrismInstanceName "1.20.1(1)"
.\tools\build_beta_candidate.ps1 -PrismInstanceName "1.20.1(1)" -SyncTelemetryToRepo
.\tools\build_beta_candidate.ps1 -StrictPreflight -StrictReadiness
.\tools\build_beta_candidate.ps1 -StrictPreflight -StrictReadiness -StrictMetricsFreshness -MaxMetricsAgeMinutes 240
.\tools\build_beta_candidate.ps1 -MetricsTailSeconds 180
.\tools\build_beta_candidate.ps1 -UseFullMetricsHistory
.\tools\build_beta_candidate.ps1 -FrameMsP95Max 8 -FrameMsP99Max 10
```

Notes candidate build:

- En echec `-StrictReadiness`, le dossier candidat partiel est nettoye automatiquement.
- Pour conserver un dossier en echec (debug), ajouter `-KeepFailedCandidate`.
- Les seuils KPI (`FrameMsP95Max`, `FrameMsP99Max`, `MsptP95Max`) sont exposes et transmis au preflight.
- Gate fraicheur metrics disponible (`-StrictMetricsFreshness`) avec verif age + derive code; en `-StrictPreflight`, une fenetre max `240 min` est appliquee par defaut si non specifiee.

Le dossier candidat inclut aussi:

- `ab_campaign_status.json` (etat campagne A/B au moment du packaging)
- `BETA_ACTIONS.md` (plan d'actions concret: statuts des gates non pass + actions pour lever les blocages vers la beta stricte)
- `candidate_manifest.json` (metadonnees candidate + git + hash jar)
- `SHA256SUMS.txt` (checksums SHA256 des artefacts)
- `profiles/` (copie des presets `pauc_profile_*.properties`)

Verification d'un dossier candidate:

```powershell
.\tools\verify_beta_candidate.ps1 -CandidateDir .\run\beta_candidates\beta_candidate_YYYYMMDD_HHMMSS_fff
```

Gate KPI roadmap (frametime/MSPT):

```powershell
.\tools\evaluate_pauc_kpi_gate.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv
```

Gate sante gouvernance serveur (sim distance + cadence IA):

```powershell
.\tools\evaluate_server_governor_health.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv
```

Parametre utile:

- `-MinPressureSamplesForEvaluation` (defaut `10`) pour eviter les faux warns quand la fenetre contient trop peu d'echantillons sous pression.

Gate sante compile chunks (budget/backpressure/pending):

```powershell
.\tools\evaluate_chunk_compile_health.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv
```

Gate hygiene compile (warnings Java/Mixin):

```powershell
.\tools\evaluate_compile_warnings.ps1 -CompileLogPath .\run\pauc_reports\preflight_compile_YYYYMMDD_HHMMSS_fff.log
```

Gate securite DRS/deferred (coherence runtime anti-black-frame):

```powershell
.\tools\evaluate_drs_deferred_safety.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv
```

Preflight avec gate KPI strict (echec si seuils depasses):

```powershell
.\tools\run_phase6_preflight.ps1 -StrictKpiGate
```

Preflight avec gate gouvernance serveur strict:

```powershell
.\tools\run_phase6_preflight.ps1 -StrictServerGovernor
```

Preflight avec gate compile chunks strict:

```powershell
.\tools\run_phase6_preflight.ps1 -StrictChunkCompile
```

Preflight avec gate DRS/deferred strict:

```powershell
.\tools\run_phase6_preflight.ps1 -StrictDrsDeferredSafety
```

Preflight avec gate compile warnings strict:

```powershell
.\tools\run_phase6_preflight.ps1 -StrictCompileWarnings
```

Preflight avec gate fraicheur metrics strict:

```powershell
.\tools\run_phase6_preflight.ps1 -StrictMetricsFreshness -MaxMetricsAgeMinutes 240
```

Autopilot roadmap (sans confirmations manuelles entre captures/candidate):

```powershell
.\tools\run_roadmap_autopilot.ps1
.\tools\run_roadmap_autopilot.ps1 -OneShot
.\tools\run_roadmap_autopilot.ps1 -OneShot -RunErrorSortingPass:$true -FailOnErrorSortingBlockingPatterns
.\tools\run_roadmap_autopilot.ps1 -OneShot -RunErrorSortingPass:$true -ErrorSortingNoiseWarnHitsTotal 500 -ErrorSortingNoiseFailHitsTotal 2000
.\tools\run_roadmap_autopilot.ps1 -OneShot -RunErrorSortingPass:$true -ErrorSortingNoiseWarnHitsTotal 100 -ErrorSortingNoiseFailHitsTotal 300 -FailOnErrorSortingNoiseFail
.\tools\run_roadmap_autopilot.ps1 -OneShot -CandidateMetricsTailSeconds 300 -CandidateMetricsWarmupTrimSeconds 30
.\tools\run_roadmap_autopilot.ps1 -OneShot -CandidateUseFullMetricsHistory
.\tools\run_roadmap_autopilot.ps1 -OneShot -PreferCachedDecisionOnBuildFailure:$false
.\tools\run_roadmap_autopilot.ps1 -OneShot -EnableStrictCandidateWindowRetry:$false
.\tools\run_roadmap_autopilot.ps1 -OneShot -StrictCandidateRetryTailSeconds 900
.\tools\run_roadmap_autopilot.ps1 -OneShot -RetryStrictCandidateOnlyOnKpiFailure:$false
.\tools\run_roadmap_autopilot.ps1 -OneShot -MaxCachedCandidateAgeMinutes 180
.\tools\run_roadmap_autopilot.ps1 -OneShot -MaxCachedCandidateAgeMinutes 180 -EnforceFreshCachedCandidateForStartupSync:$false
.\tools\run_roadmap_autopilot.ps1 -OneShot -MaxCachedCandidateAgeMinutes 180 -FailOnStartupSyncStaleCacheBlock
.\tools\run_roadmap_autopilot.ps1 -OneShot -FailOnPendingMetricsDecision
.\tools\run_roadmap_autopilot.ps1 -OneShot -MaxCachedCandidateAgeMinutes 180 -FailOnNoEffectiveDecision
.\tools\run_roadmap_autopilot.ps1 -OneShot -FailOnEffectiveDecisionNotReadyForBeta
```

Notes autopilot cache/retry:

- Le retry strict multi-fenetre peut etre force meme hors echec KPI via `-RetryStrictCandidateOnlyOnKpiFailure:$false`.
- Le fallback/usage du candidate cache peut etre borne dans le temps via `-MaxCachedCandidateAgeMinutes <n>` (`0` = desactive, valeur par defaut).
- Quand ce cache est stale et que `-EnforceFreshCachedCandidateForStartupSync` est actif (defaut), le sync startup Prism est bloque (`prism_jar_sync_status=skipped`, `prism_jar_sync_skip_reason=stale_cached_candidate_startup_sync_blocked`) au lieu de retomber sur `build_libs`.
- Pour CI stricte, `-FailOnStartupSyncStaleCacheBlock` force un `exit` en erreur quand ce blocage stale-cache se produit.
- Pour CI stricte, `-FailOnPendingMetricsDecision` force un `exit` en erreur si `final_decision=pending_metrics`.
- Pour CI stricte, `-FailOnNoEffectiveDecision` force un `exit` en erreur si `effective_decision` est vide.
- Pour CI stricte, `-FailOnEffectiveDecisionNotReadyForBeta` force un `exit` en erreur si `effective_decision` est renseignee mais differente de `ready_for_beta`.
- `prism_jar_sync_skip_reason` permet de distinguer les cas de skip (`stale_cached_candidate_startup_sync_blocked`, `startup_sync_not_synced`, `post_build_sync_not_synced`, `post_build_sync_error`).
- `prism_startup_sync_blocked_by_stale_cache` indique explicitement si le blocage stale-cache a ete active au demarrage.
- Le resume autopilot expose l'etat du cache: `cached_candidate_is_fresh`, `cached_candidate_eligible_for_use`, `cached_candidate_freshness_status`.
- `autopilot_failure_reason` expose la cause de fail gate (`pending_metrics_decision`, `missing_effective_decision`, `effective_decision_not_ready_for_beta`, `startup_sync_stale_cache_blocked`).
- `autopilot_failed` passe a `true` quand un fail gate autopilot est declenche.
- En cas de retry strict pilote par KPI, le resume expose le probe utilise: `strict_candidate_retry_kpi_evaluated`, `strict_candidate_retry_kpi_status`, `strict_candidate_retry_kpi_report_path`.
- Valeurs utiles de `decision_freshness`: `fresh`, `stale_metrics_cached_candidate`, `fresh_failure_cached_fallback`, `fresh_failure_cached_candidate_stale`, `fresh_failure_no_cached_candidate`, `fresh_failure_no_fallback`, `stale_candidate_ignored`.

Passe auto de tri des erreurs (triage + signatures bloquantes + quarantine):

```powershell
.\tools\run_error_sorting_pass.ps1 -InstanceName test -IncludeWarnings
.\tools\run_error_sorting_pass.ps1 -InstanceName test -IncludeWarnings -FailOnBlocking
.\tools\run_error_sorting_pass.ps1 -InstanceName test -IncludeWarnings -KnownNoiseWarnHitsTotal 500 -KnownNoiseFailHitsTotal 2000
.\tools\run_error_sorting_pass.ps1 -InstanceName test -IncludeWarnings -FailOnNoiseFail
```
