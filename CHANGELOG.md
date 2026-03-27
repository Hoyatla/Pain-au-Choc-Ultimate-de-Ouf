# Changelog

## 2.0.6-ultimate - 2026-03-26

- Couverture de non-regression ajoutee pour le pipeline capture auto:
  - nouveau script `tools/test_run_capture_pipeline_auto_passthru.ps1`.
  - valide le contrat `-PassThru` de `tools/run_capture_pipeline_auto.ps1` (sortie pipeline unique, objet attendu, aucune fuite `Format*`, round-trip JSON, mode smoke sans etapes lourdes).
  - couvre aussi le deploiement jar en layout Prism `.minecraft` (non-regression pathing).
- Robustesse de deploiement Prism renforcee:
  - `tools/run_capture_pipeline_auto.ps1` resout maintenant automatiquement le dossier instance Minecraft (`.minecraft` ou `minecraft`) avant copie du jar vers `mods/`.
  - `tools/run_roadmap_autopilot.ps1` applique le meme fallback `.minecraft`/`minecraft` pour les sync jars startup et post-build.
  - `tools/run_error_sorting_pass.ps1` applique le meme fallback pour la resolution des logs (`.minecraft/logs` ou `minecraft/logs`) quand `LogPaths` n'est pas explicite.
  - `tools/triage_modpack_errors.ps1` et `tools/quarantine_modpack_data_errors.ps1` appliquent le meme fallback pour la resolution des logs Prism.
  - `tools/apply_pauc_profile.ps1` resout automatiquement `config` sur layout `.minecraft` ou `minecraft`.
  - `tools/validate_v3_hardware_drivers.ps1` resout automatiquement le dossier logs Prism (selection du layout le plus recent).
- Documentation outillage enrichie:
  - `README.md` documente maintenant le workflow `run_capture_pipeline_auto.ps1` (mode complet + mode smoke) et la commande de self-test associee.
- Hygiene `-PassThru` renforcee sur les scripts d'analyse:
  - `tools/triage_modpack_errors.ps1`, `tools/quarantine_modpack_data_errors.ps1`, `tools/run_error_sorting_pass.ps1` et `tools/validate_v3_hardware_drivers.ps1` n'injectent plus de sorties `Format-List` dans le pipeline quand `-PassThru` est active.
  - `tools/ab_campaign_status.ps1`, `tools/ab_campaign_next.ps1` et `tools/assess_beta_readiness.ps1` appliquent la meme regle pour conserver une sortie `-PassThru` proprement machine-readable.
- One-shot autopilot plus actionnable sans reset d'etat:
  - `tools/run_roadmap_autopilot.ps1` ajoute `-AllowOneShotMetricsSignatureReplay` pour autoriser un run strict en `-OneShot` sur la signature telemetry deja traitee (`waiting_candidate_metrics_new` contourne explicitement).
  - le resume JSON expose `allow_one_shot_metrics_signature_replay` et `metrics_signature_replay_used` pour audit CI.
  - `tools/run_capture_pipeline_auto.ps1` expose maintenant `-AutopilotAllowOneShotMetricsSignatureReplay` pour propager ce mode vers l'etape autopilot.
- Harmonisation des seuils stricts via le wrapper capture auto:
  - `tools/run_capture_pipeline_auto.ps1` expose `-FrameMsP95Max`, `-FrameMsP99Max`, `-MsptP95Max`, `-ErrorSortingNoiseWarnHitsTotal` et `-ErrorSortingNoiseFailHitsTotal`.
  - ajout de `-MinMetricsDurationSecondsForCandidatePreflight` pour piloter le gate amont `waiting_candidate_metrics` en one-shot strict.
  - `tools/run_capture_pipeline_auto.ps1` expose aussi `-CandidateMinSoakDurationSeconds` pour aligner le gate soak strict sur les scenarios de capture.
  - ces seuils sont propages aux etapes preflight/candidate/autopilot pour conserver une seule commande de pilotage CI.
  - le contrat `-PassThru` exporte maintenant explicitement ces valeurs pour audit machine-readable.
  - ajout de `-AutopilotScriptPath` pour permettre un runner autopilot injectable (tests CI deterministes).
- Coherence du summary autopilot persiste:
  - `tools/run_roadmap_autopilot.ps1` stabilise maintenant `summary_output_size_bytes` pour correspondre a la taille reelle du JSON persiste.
  - le champ `summary_output_sha256` est calcule sur un payload canonicalise (`summary_output_sha256_scope=payload_without_summary_output_sha256`) pour eviter l'auto-reference impossible d'un hash de fichier se contenant lui-meme.
  - ajout du hook de test `PAUC_AUTOPILOT_TEST_FORCE_SUMMARY_SHA256_MISMATCH=1` pour forcer un mismatch SHA et valider la voie de fail-gate `summary_integrity_missing` de facon deterministe.
  - le gate `FailOnSummaryIntegrityMissing` valide maintenant aussi la coherence du hash payload (`summary_output_sha256` vs recalcul scope-aware) avant de considerer le summary intègre.
  - `tools/test_autopilot_fail_gates.ps1` verifie maintenant la presence metadata (`size/sha`) et la coherence taille declaree vs taille reelle du fichier summary.
  - le self-test verifie aussi la validite du hash declare en le recalculant depuis le payload canonicalise selon `summary_output_sha256_scope`.
  - quand un fail-gate summary ajuste le verdict apres la premiere sauvegarde, `tools/run_roadmap_autopilot.ps1` reecrit maintenant automatiquement le summary final (reason + triggered gates + metadata/hash a jour) pour eviter un JSON partiellement stale.
  - le self-test couvre explicitement le cas `summary_integrity_gate_detects_sha_mismatch` (summary corrompu volontairement) avec fallback reason/triggered sur exception quand le JSON summary est incomplet.
- Validation:
  - `.\tools\test_run_capture_pipeline_auto_passthru.ps1` -> `status: pass` (rapport `run/pauc_reports/capture_pipeline_auto_passthru_selftest_*/capture_pipeline_auto_passthru_selftest_summary.json`).
  - `.\tools\test_autopilot_fail_gates.ps1` -> `46/46` pass (incluant `prism_sync_gate_pass_with_dot_minecraft_layout`).
  - probe runtime reelle:
    - sans replay: `.\tools\run_roadmap_autopilot.ps1 -OneShot -EnableStrictCiFailGates ...` -> `final_decision=pending_metrics`, `allow_one_shot_metrics_signature_replay=false`, `metrics_signature_replay_used=false`,
    - avec replay: `.\tools\run_roadmap_autopilot.ps1 -OneShot -AllowOneShotMetricsSignatureReplay -EnableStrictCiFailGates ...` -> `final_decision=ready_for_beta`, `allow_one_shot_metrics_signature_replay=true`, `metrics_signature_replay_used=true`.
  - `.\tools\test_run_capture_pipeline_auto_passthru.ps1` -> `status: pass` avec contrat `-PassThru` (champ replay + seuils stricts, y compris verification des overrides).
  - `.\tools\test_run_capture_pipeline_auto_passthru.ps1` couvre aussi la propagation effective des arguments autopilot via stub (`AutopilotScriptPath`), y compris `MinMetricsDurationSecondsForCandidatePreflight` et `CandidateMinSoakDurationSeconds`.
  - smoke tests layout `.minecraft` executes sur `apply_pauc_profile`, `triage_modpack_errors`, `quarantine_modpack_data_errors`, `run_error_sorting_pass`, `validate_v3_hardware_drivers`.
  - smoke `-PassThru` (`triage`, `quarantine -DryRun`, `run_error_sorting_pass -RunQuarantine:$false`, `validate_v3_hardware_drivers`) -> aucune fuite de types `Microsoft.PowerShell.Commands.Internal.Format*`.
  - smoke `-PassThru` (`ab_campaign_status`, `ab_campaign_next`, `assess_beta_readiness`) -> aucune fuite de types `Microsoft.PowerShell.Commands.Internal.Format*`.
  - `.\tools\test_build_beta_candidate_verification_contract.ps1` -> `4/4` pass.

## 2.0.5-ultimate - 2026-03-20

- Autopilot decision projection ajoutee pour automation:
  - `tools/run_roadmap_autopilot.ps1` exporte maintenant:
    - `decision_source` (`fresh_candidate|cached_candidate|none`)
    - `effective_decision`
    - `effective_readiness_percent`
    - `decision_freshness`
  - but: conserver `final_decision=pending_metrics` quand les metrics sont stale, tout en exposant une decision exploitable (`effective_*`) basee sur le dernier candidat cache.
- Validation:
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot` -> `final_decision=pending_metrics`, `effective_decision=ready_for_beta`, `effective_readiness_percent=100`, `decision_source=cached_candidate`.

## 2.0.4-ultimate - 2026-03-20

- Autopilot stale-state clarifie:
  - `tools/run_roadmap_autopilot.ps1` conserve et expose un cache du dernier candidat valide (`cached_candidate_*`) dans le resume final.
  - en cas de `waiting_candidate_metrics_new`, le script affiche explicitement le dernier candidat `ready_for_beta` au lieu d'un statut ambigu sans contexte.
- Compatibilite de schema etat autopilot:
  - lecture `roadmap_autopilot_state.json` rendue backward-compatible quand les nouvelles cles de cache candidat sont absentes (ancien schema).
- Validation:
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot` -> `last_action=waiting_candidate_metrics_new`, `final_decision=pending_metrics`, avec `cached_candidate_decision=ready_for_beta` et `cached_candidate_readiness_percent=100`.

## 2.0.3-ultimate - 2026-03-20

- Stabilisation A/B contre les echantillons telemetry invalides:
  - `tools/append_ab_result_from_metrics.ps1` calcule maintenant un FPS effectif avec fallback `1000/frame_ms` quand `fps_raw<=0` ou invalide.
  - objectif: eviter les faux `1% low` dus a des lignes incoherentes (`fps_raw=0` alors que `frame_ms` est nominal).
- Recalcul des lignes `scene_1_village` a partir des segments bruts:
  - `A_baseline` conserve (`fps_avg=71.289`, `fps_1pct_low=20`).
  - `B1_stable` corrige (`fps_avg=80.074`, `fps_1pct_low=16.01`, avant `0.79`).
  - `B2_aggressive` conserve (`fps_avg=80.189`, `fps_1pct_low=20`).
- Validation pipeline strict:
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot` -> `final_decision=ready_for_beta`, `final_readiness_percent=100`.
  - candidate autopilot: `run/beta_candidates/beta_candidate_20260320_175838_523`.
  - revalidation stricte post-correction A/B: `run/beta_candidates/beta_candidate_20260320_180506_057`.

## 2.0.2-ultimate - 2026-03-20

- Gate `chunk_compile_health` rendu context-aware:
  - `tools/evaluate_chunk_compile_health.ps1` ne penalise plus un `budget_preview_avg` bas quand aucune pression compile n'est detectee (`compile_backpressure`, `builder_backpressure`, `builder_pending`).
  - nouveau signal exporte: `compile_pressure_detected`.
- Pipeline strict candidate plus robuste aux phases de chauffe:
  - `tools/build_beta_candidate.ps1`: en `-StrictPreflight`, si aucune fenetre n'est fournie, applique automatiquement `MetricsTailSeconds=600` (10 min recentes).
  - objectif: eviter qu'un debut de session non representatif domine la decision.
- Validation:
  - `.\tools\build_beta_candidate.ps1 -PrismInstanceName test -StrictPreflight -StrictReadiness` -> `ready_for_beta (95%)`.
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot` -> `final_decision=ready_for_beta`, `final_readiness_percent=95`.
  - candidate valide: `run/beta_candidates/beta_candidate_20260320_131029_157`.

## 2.0.1-ultimate - 2026-03-20

- Stabilisation `stream_radius`:
  - verrou de croissance apres pic de pression pour casser l'oscillation 14<->20 en soak.
  - resultat telemetry observe: `stream_radius_transitions_per_min = 0` sur session longue.
- Fiabilite pipeline candidate strict:
  - `tools/build_beta_candidate.ps1`: `-StrictPreflight` aligne sur les gates bloquantes (les checks advisory restent informatifs).
  - `tools/run_phase6_preflight.ps1`: drift de freshness metrics base sur sources runtime (pas sur `tools/`) pour eviter les faux stale.
- Validation fin de session:
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot` -> `final_decision=ready_for_beta`, `final_readiness_percent=92`.
  - candidate genere et verifie: `run/beta_candidates/beta_candidate_20260320_020037_882`.

## 2.0.0-ultimate - 2026-03-20 (stream radius stability + preflight robustness)

- **Stabilisation du rayon streaming/proxy face aux spikes de pression:**
  - `ManagedChunkRadiusController` utilise maintenant une pression client/serveur amortie (attack/release) au lieu du signal instantane brut.
  - echantillonnage borne a 1 fois par tick de jeu pour eviter les reactions multiples intra-tick.
  - application de cette pression amortie aux calculs de rayon `streaming/proxy`, du `predictive bias` et du rayon de capture proxy.
- **Fiabilite preflight compile:**
  - `tools/run_phase6_preflight.ps1` n'echoue plus a tort sur des `Note:` Gradle emis sur stderr (`NativeCommandError`), tout en conservant le controle de `LASTEXITCODE`.
- **Etat QA apres patch:**
  - build `compileJava` OK.
  - preflight non strict OK (outillage valide), mais KPI et soak restent inchanges tant qu'aucune nouvelle capture gameplay n'est produite.
  - autopilot `-OneShot` retourne `pending_metrics` (`waiting_candidate_metrics_new`) en attendant une telemetrie plus recente.

## 2.0.0-ultimate - 2026-03-19 (adaptive quality deferred)

- **Adaptive quality active aussi en pipeline deferred (mode urgence):**
  - `AdaptiveQualityController` ne bloque plus completement en `deferred`.
  - en pression haute/critique, baisse automatique du `qualityLevel` avec cooldown conserve.
  - nouvelles raisons telemetrie:
    - `deferred_pressure_high`
    - `deferred_pressure_critical`
    - `deferred_hold_low_quality`
    - `deferred_pressure_observe`
- **Objectif du patch:**
  - eviter les sessions bloquees a `qualityLevel=10` sous `CPU_BOUND` quand un shaderpack deferred est actif.
  - preparer une nouvelle capture in-game representative avant re-evaluation KPI.

## 2.0.0-ultimate - 2026-03-18 (roadmap ops)

- **DRS floor harmonise a `0.35`** pour les cas GPU severes:
  - `DynamicResolutionController.clampScale`
  - `PauCClient.clampDynamicResolutionScale`
  - `BottleneckController` (scale pressure floor)
- **Autopilot roadmap durci contre la telemetrie stale**:
  - etat persistant: `run/pauc_telemetry/roadmap_autopilot_state.json`
  - signature de session metrics pour eviter les faux re-runs
  - statut explicite `waiting_candidate_metrics_new`
  - resume enrichi avec `latest_metrics_timestamp_utc`
- **Handoff session renforce**:
  - documentation de reprise immediatement exploitable apres redemarrage de session

## 2.0.0-ultimate - 2026-03-13

- **Pipeline de rendu de chunks Embeddium-like integre nativement dans PauC:**
  - format de vertex compact (20 octets/vertex)
  - compilation de mesh multi-thread avec back-pressure
  - occlusion culler BFS avec matrice de visibilite 6x6
  - rendu terrain GPU par multidraw batching
  - gestion de sections par region
  - mixins remplacant le rendu de chunks vanilla
  - integration complete avec gouverneur, budget et proxy terrain
- **Pipeline de shaders deferes Oculus-like integre nativement dans PauC:**
  - chargeur de shaderpacks OptiFine (ZIP + dossier, `#include`, macros)
  - rendu GBuffer (`colortex0-7`, `depthtex0-2`)
  - shadow mapping avec distance adaptative par mode gouverneur
  - passes deferred + composite + final
  - systeme d'uniforms (camera, celestial, temps, brouillard, PauC exclusifs)
  - suivi des phases de rendu pour les programmes `gbuffers_*`
- **Shadow renderer avec integration gouverneur:**
  - multiplicateur de distance d'ombres par mode (CRISIS=0.5, COMBAT=0.75, BASE=0.9)
  - skip du shadow pass en CRISIS haute pression
- **Hooks entites et block-entities dans LevelRenderer** pour phases `gbuffers_entities` et `gbuffers_block`.
- **Runtime autoritaire mis a jour:**
  - PauC reconnait son propre pipeline deferred interne (pas de yield a soi-meme)
  - Embeddium/Rubidium passes de `DELEGATED_BACKEND` a `FORBIDDEN` (domaine possede nativement)
- **UI shaderpack deferred** dans ecran F10: cycle pack, reload, dossier.
- **PauCDeferredShaderController**: lifecycle complet de gestion des shaderpacks OptiFine.
- **Config persistence** du shaderpack deferred selectionne.
- **Activation automatique** du shaderpack sauvegarde au demarrage (apres GL context ready).
- **Debug overlay F3**: etat PauC, mode gouverneur, pression, autorite, chunks visible/total, shader actif, pipeline deferred.
- **Optimisations de rendu de blocs (Phase 3.1):**
  - culling intelligent des feuilles: skip des faces internes entre blocs de meme type (`LeavesBlockMixin`)
  - detection de sections single-value pour skip de rendu
  - throttling d'animations de sprites par mode gouverneur et qualite (`PauCSpriteAnimationTracker`)
  - hook d'optimisation palette (`PalettedContainerMixin`)
- **Optimisations de rendu d'entites (Phase 3.2):**
  - LOD-aware model parts: skip des parties non essentielles a distance (`ModelPartMixin`)
  - multiplicateur de spawn de particules par mode gouverneur (CRISIS=0.15, BASE=1.0)
  - distance de rendu billboard adaptative par qualite (`BillboardParticleMixin`)
  - simplification `ItemEntity` en billboard a distance
- **Support PBR (Phase 3.3):**
  - detection automatique des textures normal (`_n`) et specular (`_s`)
  - bind sur les texture units GL_TEXTURE4 (normals) et GL_TEXTURE5 (specular)
  - fallback 1x1 par defaut (flat normal + zero specular)
- **Corrections de compilation:**
  - `FrustumAccessor`: champ corrige de `frustumIntersection` a `intersection`
  - `PauCChunkRenderer`: `pushMatrix()`/`popMatrix()` corriges en `pushPose()`/`popPose()`
  - `PauCGlBuffer`: `GL30.GL_UNIFORM_BUFFER` corrige en `GL31.GL_UNIFORM_BUFFER`
  - `PauCChunkBuildContext`: acces champ prive `animatedTexture` remplace
  - `PauCWorldRenderer`: appel `PauCClient.getGovernor()` inexistant retire

## 1.4.1-ultimate - 2026-03-12

- `qualityLevel=10` ne coupe plus le runtime PauC.
- Les simplifications agressives restent bornees aux niveaux `<10`, mais DRS, proxy terrain, telemetry GPU/CPU et gouvernance runtime restent actives.
- Le proxy terrain n'est plus desactive par la seule presence du stack replay/capture.
- La ligne d'etat proxy affiche maintenant une raison explicite quand le proxy est coupe.
- Les exemples shaderpacks generes sont maintenant directement chargeables sous `pauc_ultimate_de_ouf_shaders/packs/`.
- Le README du dossier `packs/` explique maintenant clairement le format chargeable et l'usage des `.zip`.
- Mise a jour passation:
  - ajout de `TRANSFERT_PROJET.md`
  - etat du dernier run documente
  - blocages ouverts de reprise documentes (ecran noir intermittent, bruit worldgen/heightmap)

## 1.4.0-ultimate - 2026-03-12

- Proxy terrain enrichi:
  - echantillonnage `4x4` par chunk proxy
  - rendu adaptatif par distance avec regroupement de cellules
  - relief et materiaux plus lisibles en far-field
- Ajout de `PauCShaderPackManager`.
- Ajout d'un backend shaderpack externe multi-pass pilote par PauC.
- Support de manifests `pauc_shaderpack.json`, de packs en dossiers ou `.zip`, et de passes `builtin` ou `file`.
- Passes built-in exposees: `fxaa_photon`, `fxaa_elite`, `shadow_lift`, `light_clarity`, `warm_tonemap`.
- Persistance du shader actif dans la config PauC.
- Controle des shaders depuis `F10`: cycle, reload, ouverture du dossier.
- Ajout de `SHADERPACK_BACKEND_ARCHITECTURE.md`.

## 1.3.1-ultimate - 2026-03-12

- Ajout d'une capture proxy predictive avec ancrage vers l'avant du joueur.
- Elargissement controle du rayon de capture proxy selon le mode runtime et la pression.
- Retention du cache proxy recalee sur l'ancre predictive plutot que sur la seule position instantanee du joueur.
- Ajout de `PROXY_TERRAIN_ARCHITECTURE.md` pour documenter architecture, reprise et prochaines etapes du chantier proxy.

## 1.3.0-ultimate - 2026-03-11

- Ajout de `ManagedChunkRadiusController` pour separer rayon vanilla, rayon streaming PauC et rayon proxy.
- Ajout de `TerrainProxyController` avec cache ephemere des chunks charges et rendu terrain simplifie au-dela du rayon vanilla.
- Injection du rendu proxy terrain dans `LevelRendererMixin` avant le setup terrain vanilla.
- Affichage du resume `managed radius` et de l'etat du terrain proxy dans l'ecran `F10`.
- Variante `ultimate` portee en `1.3.0-ultimate`.

## 1.2.1-ultimate - 2026-03-11

- Ajout du runtime autoritaire `AuthoritativeRuntimeController`.
- Classification de la stack en `delegated backend`, `passive`, `forbidden for authoritative profile`, `high-risk`.
- Ajout des statuts runtime `sovereign`, `contested`, `degraded`.
- Injection de la pression pack dans le gouverneur global pour reagir plus tot aux conflits de domaines.
- Penalite compile chunks et throttle streaming quand les domaines `chunk_streaming` ou `worldgen` menacent la stabilite.
- Affichage du statut d'autorite dans l'ecran `F10`.

## 1.2-ultimate - 2026-03-11

- Creation de la variante `Pain au Choc ultimate de Ouf`.
- Ajout d'un gouverneur global runtime client + serveur integre.
- Ajout des modes `exploration`, `combat`, `transit`, `base`, `crisis`.
- DRS, chunks, pruning client et cadence IA serveur branches sur le gouverneur global.
- Identite, configuration et dossier shaders isoles de la base stable.

## 1.2 - 2026-03-11

- Versionnement du projet aligne sur `1.2`.
- Documentation de release et de build mise a jour.
- Ajout de ce changelog pour suivre les evolutions du projet.
- Base stabilisee avant duplication vers une variante plus experimentale.

## 1.1

- Migration nomenclature vers `Pain au Choc` / `pauc`.
- Ajout Entity LOD local, DRS, RCAS, frame time stabilizer, detecteur de bottleneck, queue chunks priorisee, gouverneurs runtime.
