# Roadmap Gouverneur Ultimate 2026-2027

Version document: `2026-03-14`
Projet: `Pain_au_Choc_ultimate_de_Ouf`
Scope: `Forge 1.20.1`, client + serveur integre

Ce document est la reference de pilotage pour le chantier:

- governor autonome generaliste
- compatibilite OptiFine la plus elevee possible
- rayon gere jusqu'a `256` chunks (architecture multi-anneaux)
- stabilite frametime et objectif mode competitif `240 FPS` (materiel compatible)
- interface in-game complete et exploitable par des utilisateurs non techniques

## 1) Objectifs produit

1. Gouvernance unifiee runtime de tous les budgets critiques:
- GPU render cost
- CPU client
- CPU serveur integre (`MSPT`)
- RAM/VRAM
- streaming/chunks/shaders

2. Compatibilite shaderpacks OptiFine tres elevee:
- chargement `dossier` et `.zip`
- chaines de passes conformes
- options/profils/macros/includes
- etats GL et uniforms stabilises

3. Experience in-game robuste:
- interface complete (presets, diagnostics, fallback)
- mode "safe recovery"
- lisibilite et actionabilite pour l'utilisateur final

## 2) Criteres de succes (KPI)

Ces cibles servent de guide produit. Elles peuvent etre re-calibrees selon matrice hardware.

- Frametime:
  - `p95 <= 6.0 ms`
  - `p99 <= 8.0 ms`
  - mode competitif vise `~4.16 ms` sur hardware haut de gamme (240 FPS)
- FPS:
  - stabilite prioritaire sur moyenne brute
  - degradation controlee par paliers, sans oscillations violentes
- Serveur integre:
  - `MSPT` sous controle en charge
  - reduction des pics critiques via gouvernance IA/simulation/chunk
- Rayon:
  - rayon gere jusqu'a `256` chunks via anneaux (pas full detail uniforme partout)
- Compatibilite OptiFine:
  - taux de reussite eleve sur matrice de packs reference
  - fallback gracefull quand un pack utilise des cas limites

## 3) Planning cible (scenario solo senior)

Date de depart de la roadmap: `2026-03-16`
Date cible RC (solo): `2027-01-24`

| Phase | Periode | Duree | Objectif principal |
|---|---|---:|---|
| 0 | 2026-03-16 -> 2026-03-29 | 2 semaines | Cadrage, instrumentation, banc de mesure |
| 1 | 2026-03-30 -> 2026-04-26 | 4 semaines | Noyau governor autonome |
| 2 | 2026-04-27 -> 2026-06-07 | 6 semaines | Chunks/streaming/proxy pour rayon gere 256 |
| 3 | 2026-06-08 -> 2026-08-16 | 10 semaines | Compatibilite OptiFine stricte |
| 4 | 2026-08-17 -> 2026-09-27 | 6 semaines | Gouvernance serveur integre |
| 5 | 2026-09-28 -> 2026-11-01 | 5 semaines | UI in-game complete |
| 6 | 2026-11-02 -> 2026-12-27 | 8 semaines | QA hard, tuning, parity optimisations |
| 7 | 2026-12-28 -> 2027-01-24 | 4 semaines | Stabilisation RC et documentation release |

## 4) Detail des phases

## Phase 0 - Cadrage et mesure

Livrables:

- definition precise des KPI
- scenes benchmark reproductibles
- protocoles A/B pour fps/frametime/mspt/memoire
- baseline project actuel chiffre

Criteres d'acceptation:

- un run benchmark complet est reproductible
- tous les indicateurs sont historises
- baseline officielle disponible

## Phase 1 - Noyau governor autonome

Livrables:

- boucle de decision unifiee par budgets
- separation boucle rapide (frame) / boucle lente (5-10 Hz)
- hysteresis anti-oscillation
- strategie de degradation ordonnee (priorites visuelles)

Criteres d'acceptation:

- disparition des oscillations brutales sur scene stable
- degradation progressive et explicable dans les logs
- recovery automatique quand la pression retombe

## Phase 2 - Rayon gere 256 (chunks/proxy/stream)

Livrables:

- architecture multi-anneaux:
  - full detail proche
  - detail reduit intermediaire
  - proxy/heightfield lointain
  - impostor/representation ultra-loin
- capture predictive orientee mouvement/camera
- upload GPU budgete et stable memoire

Criteres d'acceptation:

- rayon gere jusqu'a `256` chunks sans instabilite critique
- cout memoire sous controle (pas de croissance non bornee)
- absence de regressions majeures de streaming

## Phase 3 - Compatibilite OptiFine stricte

Livrables:

- loader robuste dossier + `.zip`
- traitement `#include`, macros, options, profils
- chaines de passes/fallback conformes
- uniform bank et alias historiques
- etats GL coherents par pass
- mode `strict`, `balanced`, `fast`

Criteres d'acceptation:

- matrice de packs reference majoritairement conforme
- differenciels visuels limites et traces
- fallback automatique en cas de pass non supporte

## Phase 4 - Gouvernance serveur integre

Livrables:

- pilotage cadence IA/navigation/selectors
- simulation distance adaptative
- policies anti-pics MSPT
- couplage propre client <-> serveur integre

Criteres d'acceptation:

- baisse des pics MSPT en charge
- pas de regressions gameplay critiques
- policies explicites et reversibles

## Phase 5 - UI in-game complete

Livrables:

- centre de controle governor
- centre shaderpack/compat/performance
- presets humains (`safe`, `balanced`, `competitive 240`, `cinematic`)
- diagnostics lisibles (causes d'etat et actions recommandees)
- mode recovery one-click

Criteres d'acceptation:

- utilisateur non technique peut diagnostiquer un etat degrade
- changement de preset sans instabilite
- rollback safe en cas d'echec de pipeline

## Phase 6 - QA hard et tuning global

Livrables:

- matrice GPU/driver:
  - NVIDIA
  - AMD
  - Intel
- campaigns longues (stabilite memoire/frametime)
- parity fonctionnelle avec familles d'optimisations connues (dans limites client + serveur integre)

Criteres d'acceptation:

- crash rate et regressions reduits
- gains mesurables sur scenes cibles
- compatibilite pack moderee a elevee selon niveau de risque

## Phase 7 - Stabilisation release candidate

Livrables:

- gel du scope
- corrections blocantes
- doc de release et reprise mainteneur a jour
- profils de prod recommandes

Criteres d'acceptation:

- build RC reproductible
- protocoles test valides
- documentation coherente et complete

## 5) Estimations multi-equipes

- Solo senior full-time: `~45 semaines` (10-11 mois)
- 2 developpeurs: `~26-32 semaines`
- 3-4 developpeurs: `~16-24 semaines`

Mode automatisation "Codex-first" avec validations humaines minimales (objectif retenu):

- Prototype fonctionnel: `4-6 semaines`
- Beta solide: `3-5 mois`
- Version mature: `6-9 mois`

Ce mode suppose que l'implementation est majoritairement automatisee et que seules 3 validations humaines sont maintenues (voir section 8).

## 6) Risques majeurs et mitigations

Risque:

- divergence de comportement entre packs OptiFine
Mitigation:
- mode strict + suite de conformite + fallback pass-level

Risque:

- instabilite DRS/pipeline capture/replay
Mitigation:
- guardrails runtime, restore target failsafe, logs causes explicites

Risque:

- pression memoire sous rayon large
Mitigation:
- budgets dynamiques, pooling, retention bornees, eviction policy stricte

Risque:

- oscillation governor
Mitigation:
- hysteresis, cooldowns, dual-loop control

## 7) Regle de maintenance documentaire

Obligations de session:

1. Mettre a jour `SUIVI_SESSIONS_ROADMAP.md` en fin de session.
2. Mettre a jour ce document si planning/scope/risques changent.
3. Mettre a jour `TRANSFERT_PROJET.md` si statut de blocage change.
4. En session longue, ecrire un checkpoint documentaire au moins toutes les 1 heure pour conserver une reprise fiable en cas de crash ou interruption.
5. Utiliser les scripts de checkpoint/fraicheur (`tools/append_doc_checkpoint.ps1`, `tools/run_doc_heartbeat.ps1`, `tools/verify_doc_freshness.ps1`) quand la session est longue ou exposee a des interruptions.

Non-respect de ces 5 points = session consideree non cloturee.

## 8) Mode execution automatise (actif)

Strategie retenue:

- Execution `Codex-first`: code, refactor, instrumentation, documentation, scripts de test et maintenance operationnelle automatises.
- Intervention humaine minimale: uniquement 3 validations obligatoires.

Les 3 validations humaines obligatoires:

1. Validation produit/priorites
- Moment: fin de chaque jalon majeur.
- Objet: priorites, arbitrages de scope, acceptation du jalon.

2. Validation QA en jeu reel
- Moment: builds candidates de phase et prerelease.
- Objet: scenarios in-game, comportement gameplay, regressions perceptibles.

3. Validation compatibilite hardware/drivers
- Moment: release candidates.
- Objet: matrice GPU/driver (NVIDIA/AMD/Intel) et stabilite runtime.

Tout le reste est automatise:

- generation/modification de code
- generation/mise a jour documentation
- instrumentation et rapports
- execution des suites de test automatisees
- preparation des correctifs et de la release candidate
