# Plan de Test A/B - Pain au Choc (PauC)

Date: 2026-03-06
Scope valide: points `1,2,3,4,6,7,8,9,10` (point `5` exclu).

## Objectif

Mesurer le gain reel vs risque sur modpack lourd, avec priorite au frametime stable.

## Regles de test

- Meme seed / meme monde / meme trajet.
- Meme modlist / memes packs / meme version GPU driver.
- Vsync OFF.
- Capture identique:
  - FPS moyen
  - 1% low
  - stutters visibles (micro-freezes)
  - stabilite visuelle (popping, artefacts)
  - crash / warning logs

## Profils a comparer

- A: PauC OFF (baseline)
- B1: PauC ON "stable" (qualite 7, CPU 2, RCAS 0.35)
- B2: PauC ON "agressif" (qualite 5, CPU 3, RCAS 0.45)

Sweep compat shaderpack (Phase 3, optionnel mais recommande):

- C1: deferred mode `strict`
- C2: deferred mode `balanced` (reference)
- C3: deferred mode `fast`
- Verification statique pre-run:
  - `.\tools\check_shaderpack_compat.ps1 -ShaderpacksDir .\shaderpacks`

Application auto profils (PowerShell, depuis la racine projet):

- `.\tools\apply_pauc_profile.ps1 -Profile baseline_off`
- `.\tools\apply_pauc_profile.ps1 -Profile stable`
- `.\tools\apply_pauc_profile.ps1 -Profile aggressive`
- `.\tools\apply_pauc_profile.ps1 -Profile safe|balanced|competitive240|cinematic`

## Scenes (3 min chacune)

1. Village dense (entites + block entities)
2. Deplacement rapide (elytra/cheval/chunk loading)
3. Combat/particules (explosions, armes, effets)
4. Base moddee lourde (machines/decos/transparence)

Ordre recommande: `A -> B1 -> A -> B2` pour reduire biais thermique.

## Matrice des points (hors 5)

### Phase immediate (deja dans PauC)

- Point 1: DRS + gouverneur frametime
- Point 4: Entity LOD
- Point 8: Gouverneur unifie (partiel)

Critere garder:

- `+15%` ou plus sur 1% low, OU
- `-30%` de saccades percues, sans artefact majeur.

### Phase incrementale (si phase immediate validee)

- Point 3: clustered visibility (version CPU monde)
- Point 6: invalidation/rebuild plus fine

Critere garder:

- gain net stable sur 3 scenes sur 4,
- pas de regression critique visuelle.

### Phase R&D (haut risque)

- Point 2: Hi-Z occlusion
- Point 7: upload GPU async/ring buffers
- Point 9: VRAM virtualization
- Point 10: reecriture transparence/particules/lumiere

Critere garder:

- gain fort et constant,
- aucun crash,
- fallback auto fiable valide.

## Garde-fous obligatoires

- Feature flag par point.
- Fallback instantane.
- Cooldown/hysteresis sur boucles adaptatives.
- Rollback possible en 1 commit.

## Journal de decision

Apres chaque point:

1. Gain constate (% FPS moyen, % 1% low, ressenti stutter)
2. Risques observes
3. Decision: `garder`, `garder sous flag`, `rollback`

Voir le fichier `RESULTATS_TESTS_AB_PAUC.csv`.

Automatisation saisie resultat:

- `.\tools\append_ab_result_from_metrics.ps1 -Scene scene_1_village -Profile A_baseline`
- aliases acceptes:
  - scene: `village|fast_move|combat_particles|modded_base`
  - profile: `baseline|stable|aggressive|safe|balanced|competitive240|cinematic`
- fenetre de capture recommandee par scene:
  - `.\tools\append_ab_result_from_metrics.ps1 -Scene village -Profile stable -LastSeconds 180`
- mode ecriture:
  - par defaut `upsert` (met a jour la ligne existante scene+profile)
  - `-ForceAppend` pour ajouter une nouvelle ligne
- workflow robuste recommande (segment exact sans calcul manuel de fenetre):
  - `.\tools\ab_mark_start.ps1 -Scene village -Profile stable`
  - jouer la scene
  - `.\tools\ab_mark_finish.ps1`
- workflow "non-aleatoire" (timer + protocole scene + validation min rows):
  - `.\tools\run_guided_ab_capture.ps1 -Scene village -Profile stable -DurationSeconds 180 -InstanceName test -OverwriteCapture`
  - (option) `-SkipAutoFinish` pour separer capture et validation manuelle
- orchestration next-step (prochaine case manquante + option profile/capture):
  - `.\tools\ab_campaign_next.ps1`
  - `.\tools\ab_campaign_next.ps1 -InstanceName "1.20.1(1)"`
  - `.\tools\ab_campaign_next.ps1 -ApplyProfile -StartCapture`
- enchainement auto apres fin de scene:
  - `.\tools\ab_mark_finish.ps1 -AutoPrepareNext`
  - `.\tools\ab_mark_finish.ps1 -AutoPrepareNext -ApplyProfileForNext`
- resolution explicite du chemin telemetrie (repo + Prism):
  - `.\tools\resolve_pauc_metrics_path.ps1`
  - `.\tools\resolve_pauc_metrics_path.ps1 -InstanceName "1.20.1(1)" -PassThru`
- sync locale telemetrie externe (Prism -> repo):
  - `.\tools\sync_pauc_telemetry.ps1`
  - `.\tools\sync_pauc_telemetry.ps1 -InstanceName "1.20.1(1)"`

Resume KPI rapide depuis telemetrie:

- `.\tools\summarize_pauc_metrics.ps1`
- inclut aussi (si colonnes presentes): backlog upload (`avg/p95`), ratio de sections visibles cullees (`avg/p95`), compteurs block entities visibles/globales (`avg/p95`), metriques compile chunks (`budget/backpressure/pending` en `avg/p95`), statut simulation distance adaptive (`avg/min/cooldown/tps`) et cadence IA serveur (`cadence avg + run ratios`).

Preflight recommande avant campagne (Phase 6):

- `.\tools\run_phase6_preflight.ps1`
- `.\tools\run_phase6_preflight.ps1 -PrismInstanceName "1.20.1(1)"`
- `.\tools\run_phase6_preflight.ps1 -PrismInstanceName "1.20.1(1)" -SyncTelemetryToRepo`
- fenetre metrics par defaut: derniere session contigue detectee (anti-pollution historique)
- override historique complet: `.\tools\run_phase6_preflight.ps1 -UseFullMetricsHistory`
- fenetrage supplementaire: `.\tools\run_phase6_preflight.ps1 -MetricsTailSeconds <n>` / `-MetricsTailSamples <n>`
- avec checkpoint doc automatique: `.\tools\run_phase6_preflight.ps1 -WriteDocCheckpoint`
- avec check fraicheur doc: `.\tools\run_phase6_preflight.ps1 -CheckDocFreshness -DocFreshnessMaxAgeMinutes 60`
- avec audit A/B: `.\tools\run_phase6_preflight.ps1 -CheckAbMatrix`
- avec progression A/B detaillee: `.\tools\run_phase6_preflight.ps1 -CheckAbMatrix -CheckAbProgress`
- avec gate DRS/deferred strict: `.\tools\run_phase6_preflight.ps1 -StrictDrsDeferredSafety`
- avec gate compile warnings strict: `.\tools\run_phase6_preflight.ps1 -StrictCompileWarnings`
- avec gate fraicheur metrics (age + derive code): `.\tools\run_phase6_preflight.ps1 -StrictMetricsFreshness -MaxMetricsAgeMinutes 240`
- audit A/B direct: `.\tools\audit_ab_results.ps1 -ResultsPath .\RESULTATS_TESTS_AB_PAUC.csv`
- statut campagne A/B (cases remplies + prochaine case): `.\tools\ab_campaign_status.ps1 -ResultsPath .\RESULTATS_TESTS_AB_PAUC.csv`
- readiness beta synthese: `.\tools\assess_beta_readiness.ps1`
- readiness applique des gates bloquants par defaut (`compile`, `kpi_gate`, `ab_audit`, `ab_progress`) meme si le score global est >= seuil
- generation candidate beta: `.\tools\build_beta_candidate.ps1`
- generation candidate beta avec sync telemetry: `.\tools\build_beta_candidate.ps1 -PrismInstanceName "1.20.1(1)" -SyncTelemetryToRepo`
- generation candidate beta avec seuils KPI explicites: `.\tools\build_beta_candidate.ps1 -FrameMsP95Max 8 -FrameMsP99Max 10`
- generation candidate beta strict avec fraicheur metrics: `.\tools\build_beta_candidate.ps1 -StrictPreflight -StrictReadiness -StrictMetricsFreshness -MaxMetricsAgeMinutes 240`
- orchestration autonome roadmap (capture -> finish -> candidate strict): `.\tools\run_roadmap_autopilot.ps1`
- orchestration autonome en mode ponctuel: `.\tools\run_roadmap_autopilot.ps1 -OneShot`
- le dossier beta candidat inclut `ab_campaign_status.json` + `BETA_ACTIONS.md` pour piloter les prochaines actions
- verification candidate: `.\tools\verify_beta_candidate.ps1 -CandidateDir <dossier_candidate>`
- Gate KPI explicite (frametime/MSPT): `.\tools\evaluate_pauc_kpi_gate.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv`
- Gate gouvernance serveur (sim distance + cadence IA): `.\tools\evaluate_server_governor_health.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv`
- Gate gouvernance serveur: `-MinPressureSamplesForEvaluation` (defaut `10`) pour eviter les faux warns si trop peu d'echantillons sous pression
- Gate compile chunks (budget/backpressure/pending): `.\tools\evaluate_chunk_compile_health.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv`
- Gate compile warnings (hygiene build): `.\tools\evaluate_compile_warnings.ps1 -CompileLogPath .\run\pauc_reports\preflight_compile_YYYYMMDD_HHMMSS_fff.log`
- Gate securite DRS/deferred (coherence runtime): `.\tools\evaluate_drs_deferred_safety.ps1 -MetricsPath .\run\pauc_telemetry\runtime_metrics.csv`

