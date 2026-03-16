# Pain au Choc ultimate de Ouf (PauC UO) - Forge 1.20.1

Version courante: `2.0.0-ultimate`

Pain au Choc ultimate de Ouf est un mod all-in-one de performance Minecraft Forge 1.20.1. Il integre un renderer de chunks optimise (Embeddium-like), un pipeline de shaders deferes (Oculus-like), un gouverneur global client + serveur integre, des budgets de rendu, un arbitrage runtime autoritaire, de la lisibilite camera et de la stabilite sous charge.

## Passation

Pour transfert a un nouveau mainteneur, commencer par:

- `TRANSFERT_PROJET.md`

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
- Rendu GBuffer (`colortex0-7`, `depthtex0-2`).
- Shadow mapping avec distance adaptative par mode gouverneur.
- Passes deferred + composite + final.
- Systeme d'uniforms (camera, celestial, temps, brouillard, uniforms PauC exclusifs).
- Suivi des phases de rendu (`WorldRenderingPhase`) pour les programmes `gbuffers_*`.
- UI de selection de shaderpack dans l'ecran F10 (cycle, reload, dossier).
- Persistance du shaderpack selectionne dans la config.
- Activation automatique du shaderpack sauvegarde au demarrage.

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
- Proxy terrain lointain PauC au-dela du rayon vanilla, avec cache ephemere, relief plus dense et rendu adaptatif par distance.
- Latency controller + frame time stabilizer.
- Detecteur bottleneck GPU/CPU.
- Adaptive frame cap.
- Dynamic Resolution Scaling.
- Adaptive Simulation Distance.
- Particle Budget System dynamique (`200..3000`).
- Upscale shader pipeline interne/externe.
- Shaderpacks externes PauC multi-pass.
- Mode `Sharp` interne base sur RCAS avec intensite reglable.

### Debug overlay F3
- Affichage dans l'ecran F3 de l'etat PauC: qualite, budget, mode gouverneur, pression, autorite, chunks visible/total, shader actif, pipeline deferred (pack + etat).

## Ce que la variante 2.0 ajoute

- Renderer de chunks Embeddium-like integre nativement dans PauC.
- Pipeline de shaders deferes Oculus-like integre nativement dans PauC.
- Support des shaderpacks OptiFine standard depuis `shaderpacks/` (meme emplacement qu'Iris/Oculus).
- Shadow mapping avec distance adaptative par mode gouverneur (skip en CRISIS haute pression).
- Hooks de phase entites et block-entities pour les programmes `gbuffers_*`.
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

- `F8`: activer/desactiver Pain au Choc.
- `F9`: cycle du niveau qualite.
- `F10`: ouvrir la configuration.

## Parametres F10

- Toggle global mod.
- Slider qualite `1..10`.
- Slider implication CPU `1..3`.
- Toggle frame time stabilizer.
- Toggle detecteur bottleneck GPU/CPU.
- Toggle RCAS.
- Slider intensite RCAS.
- Statut d'autorite runtime et resume des domaines contestes.
- Resume du rayon gere PauC.
- Statut du terrain proxy (cache et rendu).
- Raison explicite si le proxy est coupe.
- Cycle shader actif.
- Reload shaders externes.
- Ouverture du dossier shader.
- Cycle shaderpack deferred (OptiFine).
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
- `cpuInvolvementLevel=1..3`
- `frameTimeStabilizerEnabled=true|false`
- `gpuBottleneckDetectorEnabled=true|false`
- `advancedSharpeningEnabled=true|false`
- `advancedSharpeningStrength=0.0..1.0`
- `activeShaderKey=<shader actif>`
- `deferredShaderPack=<nom du shaderpack OptiFine ou (off)>`

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
- `PROXY_TERRAIN_ARCHITECTURE.md`: architecture, logique predictive, limites et checklist de reprise du proxy terrain.
- `SHADERPACK_BACKEND_ARCHITECTURE.md`: format, limites et reprise du backend shaderpack PauC.
- `CHANGELOG.md`: historique des releases.
- `TRANSFERT_PROJET.md`: etat de passation, blocages ouverts, plan de reprise mainteneur.
- `ETUDE_10_OPTIMISATIONS_HARDCORE.md`: analyse approfondie des pistes hardcore.
- `PLAN_TESTS_AB_PAUC.md`: protocole A/B officiel.
- `RESULTATS_TESTS_AB_PAUC.csv`: grille de saisie des mesures.

## Outils de test A/B

Profils preconfigures dans `tools/`:

- `pauc_profile_baseline_off.properties`
- `pauc_profile_stable.properties`
- `pauc_profile_aggressive.properties`

Application auto vers PrismLauncher:

```powershell
.\tools\apply_pauc_profile.ps1 -Profile baseline_off
.\tools\apply_pauc_profile.ps1 -Profile stable
.\tools\apply_pauc_profile.ps1 -Profile aggressive
```
