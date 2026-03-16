# Dossier Projet - Pain au Choc ultimate de Ouf (PauC UO)

Ce document est la base de contexte persistante pour reprise de session sans re-explication.

Document de reprise proxy a relire en priorite en cas de plantage:

- `PROXY_TERRAIN_ARCHITECTURE.md`
- `SHADERPACK_BACKEND_ARCHITECTURE.md`
- `TRANSFERT_PROJET.md`

## Chemin principal de travail

- `C:\Users\User\Desktop\sauv.minecraft\Modspack perso\Mods\Projets\Pain_au_Choc_ultimate_de_Ouf`

## Identite projet

- Nom visible: `Pain au Choc ultimate de Ouf`
- Identifiant technique principal: `pauc`
- Scope: optimisation client Minecraft 1.20.1 Forge
- Release courante: `2.0.0-ultimate`

## Objectif produit

Obtenir en solo un pilotage unifie client + serveur integre, en priorisant stabilite, frametime, `MSPT`, lisibilite camera et maintien d'un haut framerate sous charge.

## Contraintes de conception

- Pas de cache persistant proxy.
- Conserver les optimisations deja integrees.
- Mod strictement client-side.

## Systeme en production

### Pipeline de rendu (Embeddium-like, natif)
- Renderer de chunks optimise avec format de vertex compact (20 octets/vertex).
- Compilation de mesh multi-thread avec back-pressure.
- Occlusion culler BFS avec matrice de visibilite 6x6 en `long` 64-bit.
- Rendu terrain GPU par multidraw batching.
- Gestion de sections par region.

### Pipeline de shaders deferes (Oculus-like, natif)
- Chargeur de shaderpacks OptiFine (ZIP + dossier, `#include`, macros).
- Rendu GBuffer (`colortex0-7`, `depthtex0-2`).
- Shadow mapping avec distance adaptative par mode gouverneur.
- Passes deferred + composite + final.
- Systeme d'uniforms (camera, celestial, temps, brouillard, PauC exclusifs).
- Suivi des phases de rendu pour programmes `gbuffers_*`.
- UI de selection de shaderpack dans F10 + persistance config.

### Gouvernance et performance
- Gouverneur qualite `1..10`.
- Ajustements runtime options video.
- Entity LOD local multistade + bridge GeckoLib optionnel.
- Culling entites/block entities/ombres/particules.
- Particle budget adaptatif (`200..3000`).
- Streaming chunks prioritaires + index spatial.
- Queue compile chunks avec priorisation dynamique + back-pressure.
- Rayon gere distinct du `renderDistance` vanilla.
- Proxy terrain PauC distant avec cache ephemere.
- Capture proxy predictive avec biais de trajectoire.
- Backend shaderpack externe multi-pass pilote par PauC, en dossiers ou `.zip`.
- Latency controller + frame time stabilizer.
- Detecteur bottleneck GPU/CPU.
- Dynamic Resolution Scaling.
- Adaptive Simulation Distance.
- Adaptive frame cap.
- Upscale pipeline interne/externe avec RCAS.
- Gouverneur global runtime avec modes:
  - `exploration`
  - `combat`
  - `transit`
  - `base`
  - `crisis`
- Runtime autoritaire:
  - classification `delegated backend` / `passive` / `forbidden` / `high-risk`
  - suivi des domaines `render_backend`, `shader_pipeline`, `chunk_streaming`, `server_simulation`, `capture_pipeline`, `worldgen`, `entity_rendering`
  - statut runtime `sovereign` / `contested` / `degraded`
  - reconnaissance du pipeline deferred interne (pas de yield a soi-meme)

### Debug overlay F3
- Etat PauC, mode gouverneur, pression, autorite, chunks visible/total, shader, pipeline deferred.

## Compatibilite runtime

PauC possede nativement les domaines `render_backend` et `shader_pipeline`.

- Embeddium / Rubidium: domaine `render_backend` conteste (PauC natif).
- Oculus / Iris: domaine `shader_pipeline` conteste (PauC natif).
- ServerCore / VMP: modules passifs toleres.
- GeckoLib: rendu entites passif tolere.
- Stack replay: domaine `capture_pipeline` conteste.
- Distant Horizons: domaine `chunk_streaming` conteste.
- Flerovium: domaine `render_backend` conteste.
- ExpandedWorld: domaine `worldgen` marque `high-risk`.

## Interface utilisateur

- `F8`: toggle mod
- `F9`: cycle qualite
- `F10`: ecran config

Parametres exposes F10:

- toggle global
- qualite
- implication CPU
- frame time stabilizer
- detecteur bottleneck GPU/CPU
- RCAS on/off
- intensite RCAS
- statut d'autorite runtime
- resume du rayon gere PauC
- statut du terrain proxy
- cycle shader actif
- reload shaders externes
- ouverture dossier shader
- cycle shaderpack deferred (OptiFine)
- reload shaderpack deferred
- ouverture dossier `shaderpacks/`

## Configuration persistante

- fichier: `config/pauc_ultimate_de_ouf.properties`
- cles:
  - `enabled`
  - `authoritativeRuntimeEnabled`
  - `qualityLevel`
  - `dynamicResolutionEnabled`
  - `dynamicResolutionMinScale`
  - `adaptiveSimulationDistanceEnabled`
  - `cpuInvolvementLevel`
  - `frameTimeStabilizerEnabled`
  - `gpuBottleneckDetectorEnabled`
  - `advancedSharpeningEnabled`
  - `advancedSharpeningStrength`
  - `activeShaderKey`
  - `deferredShaderPack`

## Build

Validation:

```bash
./gradlew.bat compileJava -x test
```

Build:

```bash
./gradlew.bat jar
```

Artefact:

- `build/libs/pauc-ultimate-de-ouf-2.0.0-ultimate.jar`

## Historique recent

- **2.0.0**: Integration native du renderer Embeddium-like et du pipeline deferred Oculus-like.
  - Renderer de chunks optimise avec vertex compact, multidraw, occlusion culler BFS.
  - Pipeline deferred complet: GBuffers, shadow mapping, composite/final passes.
  - Support shaderpacks OptiFine natif depuis `shaderpacks/`.
  - UI shaderpack deferred dans F10 + persistance config.
  - Debug overlay F3.
  - Runtime autoritaire: PauC possede nativement `render_backend` et `shader_pipeline`.
- Suppression LOD terrain/proxy distant.
- Ajout Entity LOD local.
- Ajout queue compile chunks priorisee + back-pressure.
- Ajout frame time stabilizer + detecteur bottleneck.
- Passage sharpening interne vers RCAS.
- Migration nomenclature vers `Pain au Choc` / `pauc`.
- Creation de la variante `ultimate de Ouf` avec gouverneur global client + serveur integre.
- Ajout du runtime autoritaire et de la classification de la stack.
- Ajout du rayon gere PauC et du proxy terrain lointain.
- Ajout d'une premiere logique predictive au proxy terrain.
- Ajout du backend shaderpack externe multi-pass PauC.
- Correction du runtime pour garder PauC actif a `qualityLevel=10`.

## Etat transfert 2026-03-12

- Reprise mainteneur: `TRANSFERT_PROJET.md`
- Point critique restant: ecran noir intermittent a l'entree monde dans un contexte `capture_pipeline` conteste.
- Shaderpacks PauC: chargement confirme dans les logs (`loaded=2` sur dernier run).
- Regression chunks majeure absente sur dernier run:
  - `Can't keep up!`: 0
  - `Ignoring chunk since it's not in the view range`: 0
  - `Detected setBlock in a far chunk`: 0
- Bruit serveur integre encore present cote modpack:
  - `Ignoring heightmap data for chunk ... expected 52, got 43`

## Prochaine etude

Les 10 optimisations hardcore demandees sont detaillees dans:

- `ETUDE_10_OPTIMISATIONS_HARDCORE.md`
- `PLAN_TESTS_AB_PAUC.md`
- `RESULTATS_TESTS_AB_PAUC.csv`

## Outillage test

Dans `tools/`:

- `pauc_test_checklist.txt`
- `pauc_profile_baseline_off.properties`
- `pauc_profile_stable.properties`
- `pauc_profile_aggressive.properties`
- `apply_pauc_profile.ps1`
