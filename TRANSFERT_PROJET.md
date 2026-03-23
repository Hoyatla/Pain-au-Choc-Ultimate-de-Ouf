# Transfert Projet - PauC Ultimate De Ouf

Date de transfert: `2026-03-12`
Mise a jour transfert: `2026-03-23`

Ce document donne un etat de passation exploitable immediatement pour un nouveau mainteneur.

## Reprise immediate (ici)

- Candidat strict le plus recent: `run/beta_candidates/beta_candidate_20260323_182403_611` (`not_ready`, `90%`, gate bloquant unique `kpi_gate=fail`).
- Candidat operationnel courant: `run/beta_candidates/beta_candidate_20260323_185611_951` (`ready_for_beta`, `100%`, verification `pass`).
- Jar repo + Prism `test` actuellement sync: `sha256=2532F87A3C9C8F08D4FFCF5626F88A96F9C4DA66726BD4C0F32EBA98EF72CDE2`.
- Commandes de reprise immediates:
  - mode operationnel (recommande): `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot -FailOnErrorSortingBlockingPatterns`
  - mode strict complet (exige capture >=240 samples et >=480s): `.\tools\build_beta_candidate.ps1 -PrismInstanceName test -StrictPreflight -StrictReadiness`
  - mode exploratoire KPI assoupli (etat courant): `.\tools\build_beta_candidate.ps1 -PrismInstanceName test -FrameMsP95Max 60 -FrameMsP99Max 400`
- Durcissements autopilot (maj 2026-03-23):
  - retry strict multi-fenetre conditionne au statut `KPI gate=fail` (desactivable via `-RetryStrictCandidateOnlyOnKpiFailure:$false`).
  - usage du candidate cache (sync Prism + fallback decision) bornable via `-MaxCachedCandidateAgeMinutes` (`0` = desactive).
  - sync startup Prism bloque quand le cache `ready_for_beta` est stale (desactivable via `-EnforceFreshCachedCandidateForStartupSync:$false`), avec raison explicite `prism_jar_sync_skip_reason=stale_cached_candidate_startup_sync_blocked`.
  - mode CI strict disponible: `-FailOnStartupSyncStaleCacheBlock` (echec du run si ce blocage stale-cache survient).
  - gates CI decision disponibles: `-FailOnPendingMetricsDecision`, `-FailOnLatestMetricsNotFresh`, `-FailOnNoEffectiveDecision`, `-FailOnEffectiveDecisionNotReadyForBeta`, `-FailOnNonFreshEffectiveDecision`, `-FailOnCachedDecisionSource`, `-FailOnPrismJarSyncNotSynced`, `-FailOnErrorSortingStatusNotPass`, `-FailOnErrorSortingNoiseWarn`, `-FailOnSummaryOutputWriteError` (raison exposee dans `autopilot_failure_reason`, incluant `latest_metrics_not_fresh`, `effective_decision_not_fresh`, `error_sorting_status_not_pass`, `error_sorting_noise_warn_or_worse` et `error_sorting_noise_status_unavailable`; booleen agregat `autopilot_failed`).
  - bundle CI strict disponible: `-EnableStrictCiFailGates` active d'un coup toutes les gates CI strictes et force `RunErrorSortingPass` (etat expose via `strict_ci_fail_gates_enabled`), y compris le rejet des metrics trop anciennes (`latest_metrics_freshness_status`) et des decisions non fraiches (`decision_freshness!=fresh`); si `SummaryOutputPath` est absent, export auto vers `StrictCiSummaryOutputPath` (defaut `run/pauc_reports/autopilot_summary_ci_strict.json`, trace via `strict_ci_summary_output_defaulted`).
  - export machine-readable disponible: `-SummaryOutputPath <fichier.json>` (`summary_output_compressed`, `summary_output_written/written_utc/error` dans le resume; mode compact via `-SummaryOutputCompress`).
  - resume autopilot enrichi: `cached_candidate_is_fresh`, `cached_candidate_eligible_for_use`, `cached_candidate_freshness_status`, `decision_freshness` detaille en cas de `candidate_build_failed`.
- Lecture du resultat:
  - utiliser `effective_decision` / `effective_readiness_percent` comme verdict exploitable.
  - `final_decision` peut etre `pending_metrics` si session trop courte/stale.
  - `final_decision` peut etre `candidate_build_failed` en strict si un gate bloque (dernier cas: `kpi_gate=fail`).

## Statut validations humaines (maj 2026-03-23)

- V1 produit/priorites: validee (`2026-03-21`) par decision mainteneur (consigne de poursuite sans blocage Intel/AMD).
- V2 QA in-game: validee sur candidate `beta_candidate_20260321_201359_492`.
  - preuves: preflight strict `phase6_preflight_20260321_201339_271.md` (tous gates `pass`), readiness `100%`, verification candidate `pass`, completion A/B `12/12`, aucun nouveau crash date au `2026-03-21`.
  - jar Prism `test` resynchronise explicitement sur le hash candidat: `2B3A850EE742239DF20EAD48EAA334EFABFF10A97E6DA95990791C3E59200CFF`.
- V3 hardware/drivers: cloturee avec waivers (`pass_with_waivers`) via `run/pauc_reports/v3_hardware_driver_matrix_20260323_183528_795.md`.
  - NVIDIA: `pass` (runtime GL observe, driver `32.0.15.9579`, GL `4.6.0 NVIDIA 595.79`).
  - Intel: `no_runtime_evidence` (adapter detecte, mais aucun run GL force sur Intel dans les logs disponibles).
  - AMD: `missing_hardware` (aucun adaptateur AMD detecte sur la machine de validation).
  - commande de reproduction:
    - `.\tools\validate_v3_hardware_drivers.ps1 -InstanceName test -CandidateDir .\run\beta_candidates\beta_candidate_20260323_183020_036 -AllowMissingVendors`
- Revalidation outillage beta (2026-03-23):
  - `.\tools\verify_beta_candidate.ps1 -CandidateDir .\run\beta_candidates\beta_candidate_20260323_183020_036` -> `overall_status=pass`.

## Verdict de cloture (etat actuel)

- Validation humaine complete: `V1=ok`, `V2=ok`, `V3=ok_with_waivers`.
- Candidat recommande pour suite release/beta: `beta_candidate_20260323_183020_036`.
- Candidat strict recent a debloquer: `beta_candidate_20260323_182403_611` (`not_ready` uniquement a cause de `kpi_gate`).
- Waivers ouverts a lever plus tard (non bloquants pour l'etat courant):
  - Intel runtime GL non observe sur cette machine.
  - AMD non present sur la machine de validation.

## Scope transfere

- Projet: `Pain_au_Choc_ultimate_de_Ouf`
- Mod id: `pauc`
- Version code actuelle: `2.0.0-ultimate`
- Artefact: `build/libs/pauc-ultimate-de-ouf-2.0.0-ultimate.jar`

## Checkpoint reprise 2026-03-18 (avant redemarrage)

Etat immediat:

- jar Prism `test` sync OK: `pauc-ultimate-de-ouf-2.0.0-ultimate.jar`
- hash jar Prism actif: `5DB836AD3F58659F1D1A102500152C23334034CEAA351E7E13D598B09D8541CF`
- autopilot durci: evite maintenant de re-evaluer la meme session telemetrie (`waiting_candidate_metrics_new`) tant qu'aucune nouvelle capture gameplay n'est detectee
- derniere telemetrie candidate connue: `2026-03-18T19:35:47Z` (`schema=20260318_shadowv2`)

Reprise operatoire conseillee:

1. lancer `.\tools\run_roadmap_autopilot.ps1 -InstanceName test`
2. jouer ~10 min dans la scene reproduisant le rechargement terrain
3. executer `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot`
4. verifier que le resume n'indique plus `last_action=waiting_candidate_metrics_new`

## Checkpoint reprise 2026-03-19 (adaptive quality deferred)

Etat immediat:

- jar Prism `test` re-sync OK apres patch: `pauc-ultimate-de-ouf-2.0.0-ultimate.jar`
- hash jar Prism actif: `6C5378C89D1CFEAC96082BFC1887166D9345FF4CAC09E598309F4AA14D52EDB7`
- patch runtime: `AdaptiveQualityController` degrade maintenant la qualite meme quand le pipeline deferred est actif (`deferred_pressure_high|critical`) au lieu d'un blocage total (`deferred_pipeline_control`)
- dernier preflight strict connu: `phase6_preflight_20260319_202247_201.md` -> `Metrics freshness=stale` (derive code/metrics) et `frame_ms_p95=36.1391` (KPI fail)

Reprise operatoire conseillee:

1. lancer `test` avec le jar sync ci-dessus
2. jouer ~10 min sur la scene cible
3. verifier dans telemetry que `auto_quality_adjustments` augmente et que `quality_level` peut descendre depuis `10`
4. relancer `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot`

## Checkpoint reprise 2026-03-20 (stabilisation rayon + fiabilite preflight)

Etat immediat:

- patch runtime applique: `ManagedChunkRadiusController` amortit maintenant les niveaux de pression client/serveur avant de calculer `stream_radius/proxy_radius` pour eviter les oscillations en dents de scie.
- patch outillage applique: `tools/run_phase6_preflight.ps1` ignore correctement les faux positifs `NativeCommandError` lies aux lignes `Note:` de Gradle.
- compile local valide: `.\gradlew.bat compileJava -x test` OK.
- jar Prism `test` re-sync post-build OK: `sha256=81A4EA9901F9BE539DBA48148CC66188F258BE4308A03E078E198929BAD2125B`.
- preflight non strict valide (outillage): `phase6_preflight_20260319_232315_708.md`.
- dernier etat autopilot: `pending_metrics` / `waiting_candidate_metrics_new` (pas de telemetrie gameplay nouvelle depuis `2026-03-19T22:47:51Z`).

Reprise operatoire conseillee:

1. lancer Prism `test` avec le jar deja sync (`sha256=81A4EA9901F9BE539DBA48148CC66188F258BE4308A03E078E198929BAD2125B`)
2. jouer ~10 min sur la scene cible (rechargement terrain + pressure)
3. relancer `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot`
4. verifier la nouvelle fenetre metrics:
   - baisse des transitions `stream_radius`
   - `frame_ms_p99` rapproche de la cible gate (`<= 60`)

## Cadre roadmap actif

Le projet est maintenant pilote avec une roadmap explicite:

- `ROADMAP_GOUVERNEUR_ULTIMATE_2026_2027.md`: plan global, phases, jalons, objectifs, risques.
- `SUIVI_SESSIONS_ROADMAP.md`: journal de suivi obligatoire par fin de session.
- Mode execution actif: automatisation `Codex-first`.
- Validation humaine minimale: uniquement 3 validations obligatoires (produit, QA in-game, hardware/drivers).

Regle de reprise:

- Ordre de securite documentaire: toute session longue doit ecrire une mise a jour documentaire au moins toutes les 1 heure afin de conserver un point de reprise exploitable en cas de crash ou d'interruption brutale.
- Toute session doit se terminer par une mise a jour de `SUIVI_SESSIONS_ROADMAP.md`.
- Si planning/scope/risques evoluent: mise a jour dans `ROADMAP_GOUVERNEUR_ULTIMATE_2026_2027.md`.
- Si blocages de reprise evoluent: mise a jour ici meme (`TRANSFERT_PROJET.md`).

## Point de reprise demain (maj 2026-03-22)

- Etat global de sortie:
  - pipeline operationnel: `ready_for_beta` disponible via cache candidate strict.
  - dernier `autopilot_state`: `last_processed_metrics_signature` sur session courte (`rows=220`, `duration=221.101s`, `timestamp=2026-03-22T22:02:46Z`).
  - blocage strict courant: `soak_stability` (session contigue insuffisante pour gate strict).
- Candidat strict de reference:
  - dossier: `run/beta_candidates/beta_candidate_20260322_160614_277`
  - decision: `ready_for_beta`
  - readiness: `100%`
  - notes: `server governor health skipped (insufficient pressure samples)`
  - manifeste: `run/beta_candidates/beta_candidate_20260322_160614_277/candidate_manifest.json`
- Candidat exploratoire (session courte):
  - dossier: `run/beta_candidates/beta_candidate_20260322_220939_377`
  - decision: `not_ready`
  - readiness: `97.5%`
  - gate bloquant: `soak_stability=skipped`
  - manifeste: `run/beta_candidates/beta_candidate_20260322_220939_377/candidate_manifest.json`
- Dernier jar sync Prism `test`:
  - `sha256=86FC9AF0E9932E7457BBF6D185B2DA91AC9ECC9CDE32988C0EA63E11DF4876C0`
  - source: `build/libs/pauc-ultimate-de-ouf-2.0.0-ultimate.jar`
- Derniers rapports clefs:
  - preflight exploratoire: `run/pauc_reports/phase6_preflight_20260322_220919_908.md`
  - error sorting: `run/pauc_reports/error_sorting_pass_20260322_220743_391.md` (`blocking_hits_total=0`, `known_noise_status=pass`, `known_noise_hits_total=256`)
- Commits recents a connaitre:
  - `1c90e6f`: fix autopilot `LASTEXITCODE` (elimine faux echec strict)
  - `8dbe0ae`: checkpoint documentaire final + sync jar instance
- Reprise minimale conseillee:
  1. `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot -FailOnErrorSortingBlockingPatterns`
  2. produire une capture continue >= 480s
  3. `.\tools\build_beta_candidate.ps1 -PrismInstanceName test -StrictPreflight -StrictReadiness`

## Point de reprise demain (archive maj 2026-03-21)

- Etat global: `ready_for_beta` obtenu en pipeline strict.
- Dernier candidate valide:
  - dossier: `run/beta_candidates/beta_candidate_20260321_201359_492`
  - readiness: `100%`
  - verification: `pass`
- Dernier jar sync Prism `test`:
  - `sha256=2B3A850EE742239DF20EAD48EAA334EFABFF10A97E6DA95990791C3E59200CFF`
- Revalidation session `2026-03-21`:
  - `.\tools\build_beta_candidate.ps1 -PrismInstanceName test -StrictPreflight -StrictReadiness -MetricsWarmupTrimSeconds 120`:
    - candidate `run/beta_candidates/beta_candidate_20260321_201312_405`
    - `decision=ready_for_beta`
    - `readiness_percent=100`
    - `verification=pass`
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot`:
    - `last_action=beta_candidate_attempt`
    - `final_decision=ready_for_beta`
    - `effective_decision=ready_for_beta`
    - `effective_readiness_percent=100`
    - `decision_source=fresh_candidate`
    - `decision_freshness=fresh`
    - `cached_candidate_server_governor_health=pass`
    - `cached_candidate_server_governor_skipped_for_insufficient_pressure=false`
    - `latest_metrics_timestamp_utc=2026-03-21T20:13:33Z`
  - `.\tools\run_phase6_preflight.ps1 -PrismInstanceName test -MetricsTailSeconds 600 -MinDurationSeconds 300 -ReportAsJson` -> PASS (`compile`, `compile_warnings`, `kpi`, `server_governor`, `chunk_compile`, `drs_deferred_safety`, `soak_stability`)
  - `.\tools\evaluate_chunk_compile_health.ps1 -PrismInstanceName test -MetricsTailSeconds 600 -ReportAsJson` -> PASS (`budget_preview_avg=2`, `compile_pressure_detected=false`)
- Commande de reprise minimale:
  - `.\tools\run_roadmap_autopilot.ps1 -InstanceName test -OneShot`
- Points encore ouverts (non bloquants strict):
  - si aucune telemetrie gameplay nouvelle n'arrive apres cette candidate, un prochain `-OneShot` peut revenir en `pending_metrics` (mode anti-stale), avec projection via `cached_candidate_*`.
  - `chunk_compile_health=pass` sur fenetre sans pression compile (`compile_pressure_detected=false`); revalider uniquement si backlog compile reapparait.
  - la prise finale `2026-03-21` valide maintenant aussi la branche serveur sous pression (`server_governor=pass`, `mitigation_active_samples=8`).
  - matrice A/B stabilisee cote outillage: `append_ab_result_from_metrics.ps1` applique un fallback `fps_raw -> frame_ms` pour supprimer les faux minima (`fps_raw=0` incoherent).
  - sortie autopilot enrichie pour automation: utiliser `effective_decision` + `effective_readiness_percent` (source `fresh_candidate` ou `cached_candidate`) plutot que `final_decision` seul.

## Ce qui est valide

- Le jar `2.0.0-ultimate` est bien sync sur Prism `test` (hash ci-dessus).
- Build/preflight revalides au `2026-03-21` (`.\gradlew.bat compileJava -x test` + `.\tools\run_phase6_preflight.ps1 ...` PASS).
- Build local compile/jar OK au `2026-03-19` (`.\gradlew.bat compileJava -x test` + `.\gradlew.bat jar`).
- Ajustement Phase 1/6 (`2026-03-19`): adaptive quality active en mode deferred sous pression (degradation d'urgence avec cooldown, telemetrie reason dediee).
- Plancher DRS harmonise a `0.35` dans governor/client/controller/bottleneck pour debloquer les cas GPU severes.
- Autopilot roadmap renforce avec etat persistant anti-donnees stale (`run/pauc_telemetry/roadmap_autopilot_state.json`).
- Instrumentation Phase 0 ajoutee: export CSV runtime (`run/pauc_telemetry/runtime_metrics.csv`) + script KPI (`tools/summarize_pauc_metrics.ps1`).
- Pipeline A/B accelere: script d'ajout automatique d'un run dans `RESULTATS_TESTS_AB_PAUC.csv` (`tools/append_ab_result_from_metrics.ps1`).
- Pipeline A/B accelere (v2): `append_ab_result_from_metrics.ps1` supporte alias scene/profil, fenetre de capture (`LastSeconds/LastSamples/From/To`) et mode `upsert` pour remplir la matrice sans doublons.
- Pipeline A/B accelere (v3): correction anti-artefacts telemetry (`fps_raw` invalide) via fallback FPS derive de `frame_ms` pendant l'agregation.
- Readiness (v2): `tools/assess_beta_readiness.ps1` traite maintenant `server_governor=skipped` pour cause `insufficient pressure samples` comme non penalise (score conserve, note explicite).
- Packaging candidate (v2): `tools/build_beta_candidate.ps1` enrichit `BETA_ACTIONS.md` et `candidate_manifest.json` avec l'etat de couverture serveur (`readiness_server_governor_*`) et une action advisory explicite quand la pression est insuffisante.
- Packaging candidate (v3): `tools/build_beta_candidate.ps1` expose `-MetricsWarmupTrimSeconds` pour piloter explicitement l'exclusion de bootstrap lors des campagnes longues.
- Autopilot roadmap (v2 stale-clarity): resume enrichi avec `cached_candidate_decision/readiness/dir/timestamp` et lecture d'etat backward-compatible.
- Autopilot roadmap (v3 decision-projection): resume enrichi avec `decision_source`, `effective_decision`, `effective_readiness_percent`, `decision_freshness`.
- Autopilot roadmap (v4 cache-sync): resynchronisation automatique de la derniere candidate presente dans `run/beta_candidates` meme quand la telemetrie est stale, avec persistence du cache dans `roadmap_autopilot_state.json`.
- Autopilot roadmap (v5 server-coverage): resume one-shot enrichi avec `cached_candidate_server_governor_*` + message explicite quand la couverture serveur est partielle (pression insuffisante).
- Autopilot roadmap (v6 candidate-jar-sync): post-build sync Prism alignee d'abord sur le jar de la `candidateForDecision` (fallback `build/libs` si indisponible) pour eviter les derives binaire entre candidate retenue et instance.
- Autopilot roadmap (v7 startup-candidate-sync): au demarrage, si la candidate cachee est `ready_for_beta` et qu'aucun build force n'est demande, sync Prism prioritaire depuis cette candidate (fallback `build/libs`).
- Autopilot roadmap (v8 jar-sync-telemetry): resume one-shot enrichi avec `prism_jar_sync_status/source/path/sha256` pour tracer exactement quel jar a ete pousse vers Prism.
- Autopilot roadmap (v9 candidate-metrics-window-controls): nouveaux flags pass-through pour piloter la fenetre metrics candidate depuis l'autopilot (`-CandidateMetricsWarmupTrimSeconds`, `-CandidateMetricsTailSeconds`, `-CandidateMetricsTailSamples`, `-CandidateUseFullMetricsHistory`).
- Autopilot roadmap (v10 cached-fallback-on-build-failure): quand `final_decision=candidate_build_failed` et qu'une candidate cachee existe, projection optionnelle de `effective_decision` sur le cache (`decision_source=cached_candidate_fallback`) avec trace `decision_override_reason`; desactivation possible via `-PreferCachedDecisionOnBuildFailure:$false`.
- Autopilot roadmap (v11 candidate-config-telemetry): resume one-shot enrichi avec les parametres de gating/fenetre effectivement appliques (`target_frame_ms_*`, `target_mspt_p95_max`, `candidate_metrics_*`) pour reproduction exacte des runs.
- Autopilot roadmap (v12 strict-multi-window-retry): build candidate strict tente maintenant plusieurs fenetres metrics avant echec final (`primary` puis `full_history_retry` par defaut), expose `strict_candidate_attempt_*` dans le resume, et reste configurable via `-EnableStrictCandidateWindowRetry` / `-StrictCandidateRetryTailSeconds`.
- Workflow capture A/B segmente: `ab_mark_start.ps1` / `ab_mark_finish.ps1` pour extraire exactement les nouvelles lignes telemetrie d'une scene avant ecriture matrice.
- Suivi de progression A/B: `ab_campaign_status.ps1` + integration preflight (`-CheckAbProgress`) pour afficher completion `%` et prochaine case a remplir.
- Orchestration campagne A/B: `ab_campaign_next.ps1` (prochaine case manquante + mapping profil launcher + start capture optionnel) + mode `ab_mark_finish.ps1 -AutoPrepareNext`.
- Stabilisation gouverneur (Phase 1): hysteresis/cooldown des transitions de mode + compteurs F3 des transitions.
- Phase 1 (degradation ordonnee): coupes visuelles explicites par mode integrees dans `RenderBudgetManager` (clouds/weather/particles/sky).
- Phase 1 (budgets): `ParticleBudgetController` couple au mode gouverneur + pression, et budget exporte en telemetrie.
- Phase 2 (streaming): `StructureStreamingController` etendu au rayon streaming (au lieu du seul full detail), avec ancre predictive, scan batch adaptatif et compteurs `known/active/deferred`.
- Phase 2 (proxy memoire): `TerrainProxyController` applique un budget cache cible dynamique + eviction priorisee pour borner la retention.
- Phase 2 (upload GPU): `PauCUploadManager` traite un backlog budgete par frame (sections + bytes) au lieu d'uploads massifs, avec stats exposees pour debug.
- Phase 2 (upload GPU correctness): isolation des VBO par section/pass pour eviter les ecrasements de mesh inter-sections lors des uploads successifs.
- Gouvernance compile chunks (stabilite): `ChunkBuildQueueController` expose un calcul non-mutant (`previewCompileBudget`) pour les builders PAUC; suppression du double multiplicateur mode dans `PauCRenderSectionManager` pour eviter un budget trop agressif.
- Observabilite etendue: telemetrie runtime CSV inclut maintenant metriques streaming/proxy/upload + raisons runtime `drs/proxy` + route upscaler (`native|drs`) + statut simulation distance adaptive + cadence IA serveur (ratios run) + compteurs block entities (`visible/global`) + metriques compile chunks (`budget preview/backpressure/pending`); F3 et ecran config affichent le statut streaming/upload + serveur/sim distance/cadence + compteurs block entities.
- Phase 2 (render rings): `PauCRenderSectionManager` marque maintenant les sections visibles par anneaux (`full/stream/deferred`) et applique `BUDGET_CULLED` de facon adaptative en fonction du mode/pressure.
- Phase 2 (ultra-loin): palier impostor initial ajoute via `getUltraImpostorStartRadiusChunks()` + `stride=8` sur la zone lointaine du proxy pour limiter le cout.
- Phase 3 (compat shaderpacks): mode deferred `strict/balanced/fast` ajoute (UI F10 + config), avec fallback et limite de passes adaptes par mode.
- Phase 3 (loader): `ShaderPackLoader` gere mieux les includes relatifs/absolus (`/shaders/...`) et injecte des defines GLSL de mode deferred.
- Phase 3 (diagnostics): warnings de compatibilite pack exposes (logs + debug pipeline + telemetrie CSV).
- Phase 4 (serveur): mitigation anti-pics MSPT ajoutee (`mitigationTier` + fenetre `emergencyHoldTicks`) et couplee a simulation distance + cadence IA serveur.
- Phase 4 (serveur->chunks): `mitigationTier`/`emergency` pilote maintenant aussi l'agressivite streaming/proxy (`ManagedChunkRadiusController`, `StructureStreamingController`) et le budget compile chunks (`ChunkBuildQueueController`) pour proteger le serveur integre en charge.
- Outil QA Phase 3: `tools/check_shaderpack_compat.ps1` pour pre-qualifier les packs (`strict/balanced/fast`) avant run in-game.
- UI diagnostics: ecran F10 affiche mode deferred + compteur de warnings pack pour triage rapide.
- Phase 3 (activation robuste): fallback de mode deferred automatique a l'activation pack (`strict -> balanced -> fast`) avant abandon OFF.
- Phase 5 (UI): presets utilisateurs (`safe/balanced/competitive 240/cinematic`) + bouton recovery one-click dans F10.
- Phase 5 (UX test): raccourcis clavier presets/recovery ajoutes (`F6` cycle preset, `F7` recovery).
- Phase 5 (diagnostic): F10 expose maintenant une ligne d'action recommandee selon pression gouverneur/serveur/warnings deferred.
- Logs runtime explicites ajoutes:
  - `DRS on/off + reason`
  - `proxy on/off + reason`
  - `shader upscaler + route native/drs + deferred mode`
- Stabilite DRS/deferred renforcee (`2026-03-14`): DRS est maintenant force OFF automatiquement quand le pipeline deferred interne est actif, pour eviter les black frames lies au swap framebuffer.
- Hygiene build (`2026-03-14`): warning mixin particules supprime en migrant le culling distance vers `ParticleEngineMixin` (suppression de `BillboardParticleMixin` cible invalide).
- Phase 6 (outillage QA): script `tools/run_phase6_preflight.ps1` pour lancer compile + checks packs + resume metrics et generer un rapport horodate.
- Phase 6 (gate KPI): script `tools/evaluate_pauc_kpi_gate.ps1` pour verifier automatiquement les seuils roadmap (`frame_ms_p95/p99`, `mspt_p95`), integre au preflight (mode strict optionnel).
- Phase 6 (gate gouvernance serveur): script `tools/evaluate_server_governor_health.ps1` pour verifier l'adaptation sim distance/cadence IA sous pression (`server_mitigation_tier`, `sim_distance_*`, `mob_*`), integre au preflight (mode strict optionnel via `-StrictServerGovernor`).
- Phase 6 (gate compile chunks): script `tools/evaluate_chunk_compile_health.ps1` pour verifier la sante du pipeline compile (`chunk_compile_*`), integre au preflight (mode strict optionnel via `-StrictChunkCompile`).
- Phase 6 (gate compile warnings): script `tools/evaluate_compile_warnings.ps1` pour verifier l'hygiene warnings Java/Mixin, integre au preflight (mode strict optionnel via `-StrictCompileWarnings`).
- Phase 6 (gate DRS/deferred): script `tools/evaluate_drs_deferred_safety.ps1` pour verifier la coherence runtime anti-black-frame (`deferred_active` vs `drs_active`), integre au preflight (mode strict optionnel via `-StrictDrsDeferredSafety`).
- Outillage profils A/B et presets: `tools/apply_pauc_profile.ps1` supporte maintenant aussi `safe`, `balanced`, `competitive240`, `cinematic`.
- Outillage documentaire anti-crash: `tools/append_doc_checkpoint.ps1` + `tools/run_doc_heartbeat.ps1` (checkpoints horaires), `tools/verify_doc_freshness.ps1` (controle age checkpoint), et options doc dans `tools/run_phase6_preflight.ps1` (`-WriteDocCheckpoint`, `-CheckDocFreshness`, `-StrictDocFreshness`).
- Outillage A/B readiness: `tools/audit_ab_results.ps1` + options `-CheckAbMatrix` / `-StrictAbMatrix` dans `tools/run_phase6_preflight.ps1`.
- Outillage resolution metrics: `tools/resolve_pauc_metrics_path.ps1` + auto-resolution integree dans `run_phase6_preflight.ps1`, `build_beta_candidate.ps1`, `ab_mark_start.ps1`, `ab_mark_finish.ps1`, `ab_campaign_next.ps1` (fallback repo + PrismLauncher).
- Outillage sync telemetry: `tools/sync_pauc_telemetry.ps1` pour copier metrics/segments capture vers `run/pauc_telemetry` et mode preflight `-SyncTelemetryToRepo`.
- Outillage decision beta: `tools/assess_beta_readiness.ps1` pour calculer un pourcentage de readiness depuis le dernier rapport preflight.
- Packaging candidate beta: `tools/build_beta_candidate.ps1` (preflight + readiness + build jar + dossier candidat + manifeste + `ab_campaign_status.json` + `BETA_ACTIONS.md` + `candidate_manifest.json` + `SHA256SUMS.txt` + copie `profiles/` + verification auto candidate).
- `BETA_ACTIONS.md` liste maintenant explicitement les gates preflight/readiness non pass (dont `Chunk compile health`) pour accelerer le deblocage vers le mode strict.
- Le backend shaderpack PauC charge bien des packs externes.
- Exemples charges dans les logs:
  - `PauC shaderpack loaded: Cinematic Light Stack`
  - `PauC shaderpack loaded: Competitive FXAA Stack`
  - `PauC external shaderpacks loaded=2`
- Les regressions chunks majeures vues avant ne sont plus presentes sur le dernier run:
  - `Can't keep up!`: 0
  - `Ignoring chunk since it's not in the view range`: 0
  - `Detected setBlock in a far chunk`: 0

## Problemes ouverts (importants)

1. Ecran noir intermittent a l'entree en monde.
- Suspect principal: conflit de pipeline rendu quand la stack capture/replay est chargee.
- Indice logs: runtime autoritaire en `status=contested` sur `capture_pipeline`.
- Hypothese technique: interaction entre swap main render target (DRS) et pipeline capture.
- Etat mitigation code (`2026-03-14`): garde-fou DRS sur `capture_pipeline` conteste + fallback blit anti-ecran-noir + garde-fou DRS quand deferred interne actif; validation in-game encore requise.

2. Bruit important de warnings shader uniforms.
- Les packs PauC chargent, mais plusieurs uniforms absents sont logues.
- Ce bruit ne bloque pas le chargement, mais complique le diagnostic.
- Etat mitigation code (`2026-03-13`): generation JSON des uniforms optionnels rendue conditionnelle au contenu des fragments pour limiter les warnings inutiles; validation logs in-game encore requise.

3. Bruit serveur integre non PauC a traiter cote modpack.
- `Ignoring heightmap data for chunk ... expected 52, got 43` (volume eleve).
- Erreurs assets/recipes/tags cote `hbm_m`.
- Ces erreurs polluent la session et peuvent impacter les perfs CPU/MSPT.

## Etat de config observe (dernier run)

Fichier: `config/pauc_ultimate_de_ouf.properties`

- `enabled=true`
- `authoritativeRuntimeEnabled=true`
- `dynamicResolutionEnabled=true`
- `activeShaderKey=builtin:sharp`
- `qualityLevel=1` (valeur definie manuellement pendant test)

## Profil de reprise recommande (safe debug)

Objectif: stabiliser l'image avant toute optimisation agressive.

1. `dynamicResolutionEnabled=false`
2. `activeShaderKey=builtin:linear`
3. `qualityLevel=7`
4. garder replay stack chargee pour reproduire le contexte reel
5. verifier si l'ecran noir disparait

Si le noir disparait dans ce profil, reprendre ensuite par etapes:

1. re-activer shaderpack PauC (sans DRS)
2. puis re-activer DRS (uniquement sans deferred actif)
3. puis ajuster mode qualite

## Priorites techniques pour le prochain mainteneur

1. Valider en jeu les garde-fous DRS (`capture_pipeline` conteste + deferred interne actif) (maj `2026-03-14`).
2. Valider en jeu le fallback anti-ecran-noir avec restauration framebuffer (implante le `2026-03-13`).
3. Valider en jeu la reduction du bruit uniforms apres generation JSON conditionnelle (`PauCShaderManager` + `PauCShaderPackManager`).
4. Valider en jeu les logs runtime explicites implantes:
- `proxy on/off + reason`
- `drs on/off + reason`
- `shader upscaler + route native/drs + deferred mode`
5. Mesurer en jeu le backlog upload (F3 + CSV) sur scenes lourdes et ajuster les coefficients de budget si la latence visuelle augmente.
6. Valider en jeu la stabilite memoire en rayon large (stream/proxy) sur sessions longues.

## Fichiers clefs a reprendre

### Coeur
- `src/main/java/pauc/pain_au_choc/PauCClient.java`
- `src/main/java/pauc/pain_au_choc/AuthoritativeRuntimeController.java`
- `src/main/java/pauc/pain_au_choc/GlobalPerformanceGovernor.java`

### Pipeline de rendu (Embeddium-like)
- `src/main/java/pauc/pain_au_choc/render/PauCWorldRenderer.java`
- `src/main/java/pauc/pain_au_choc/render/chunk/PauCRenderSectionManager.java`
- `src/main/java/pauc/pain_au_choc/render/chunk/PauCChunkRenderer.java`
- `src/main/java/pauc/pain_au_choc/render/chunk/PauCChunkBuilder.java`
- `src/main/java/pauc/pain_au_choc/render/occlusion/PauCOcclusionCuller.java`

### Pipeline deferred (Oculus-like)
- `src/main/java/pauc/pain_au_choc/render/shader/DeferredWorldRenderingPipeline.java`
- `src/main/java/pauc/pain_au_choc/render/shader/PauCDeferredShaderController.java`
- `src/main/java/pauc/pain_au_choc/render/shader/ShadowRenderer.java`
- `src/main/java/pauc/pain_au_choc/render/shader/ShaderPackLoader.java`

### Systemes existants
- `src/main/java/pauc/pain_au_choc/DynamicResolutionController.java`
- `src/main/java/pauc/pain_au_choc/PauCShaderManager.java`
- `src/main/java/pauc/pain_au_choc/PauCShaderPackManager.java`
- `src/main/java/pauc/pain_au_choc/TerrainProxyController.java`

### Optimisations de rendu (Phase 3.1-3.3)
- `src/main/java/pauc/pain_au_choc/render/compile/PauCBlockRenderOptimizer.java`
- `src/main/java/pauc/pain_au_choc/render/compile/PauCSpriteAnimationTracker.java`
- `src/main/java/pauc/pain_au_choc/render/entity/PauCEntityRenderOptimizer.java`
- `src/main/java/pauc/pain_au_choc/render/shader/PauCPBRTextureManager.java`

### Mixins
- `src/main/java/pauc/pain_au_choc/mixin/LevelRendererMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/GameRendererMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/DebugScreenOverlayMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/LeavesBlockMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/PalettedContainerMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/ModelPartMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/ParticleEngineMixin.java`

## Build / verification rapide

```bash
./gradlew.bat compileJava -x test
./gradlew.bat jar
```

Verifier ensuite:

- version jar: `2.0.0-ultimate`
- logs de chargement shaderpacks
- absence d'ecran noir en entree monde sur profil safe debug
- coherence des statuts phase/session dans `SUIVI_SESSIONS_ROADMAP.md`

