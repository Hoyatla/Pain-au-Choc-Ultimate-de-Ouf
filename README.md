# Pain au Choc ultimate de Ouf (PauC UO) - Forge 1.20.1

Version courante: `1.4.1-ultimate`

Pain au Choc ultimate de Ouf est la variante experimentale orientee gouverneur global client + serveur integre: budgets de rendu, arbitrage runtime, autorite pack-wide, lisibilite camera et stabilite sous charge.

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

## Ce que la variante ajoute maintenant

- Classification de la stack en `delegated backend`, `passive`, `forbidden`, `high-risk`.
- Statut d'autorite runtime `sovereign`, `contested`, `degraded`.
- Pression pack injectee dans le gouverneur global.
- Penalite compile chunks et throttle chunk streaming quand un domaine est conteste ou qu'un risque worldgen est detecte.
- Affichage du statut d'autorite dans l'ecran `F10`.
- Decouplage entre rayon detail vanilla, rayon de streaming PauC et rayon proxy jusqu'a `256` chunks geres.
- Cache ephemere de proxies terrain alimentes par les chunks charges.
- Rendu terrain lointain simplifie injecte avant le setup terrain vanilla.
- Capture proxy predictive avec biais vers l'avant du joueur selon le mouvement et le mode runtime.
- Backend shaderpack externe multi-pass pilote par PauC, en dossier ou `.zip`.
- Passes built-in actuelles: `fxaa_photon`, `fxaa_elite`, `shadow_lift`, `light_clarity`, `warm_tonemap`.
- Controle shader depuis l'ecran `F10` avec cycle, reload et ouverture du dossier.
- Niveau `10` conserve maintenant le runtime PauC actif au lieu de couper tout le budget.
- Le proxy terrain n'est plus desactive par la seule presence du stack replay quand aucun vrai conflit shader/chunk n'est detecte.
- Les exemples shaderpacks generes sont maintenant directement chargeables sous `packs/competitive_fxaa/` et `packs/cinematic_light/`.

## Valeurs par defaut de la variante

- Qualite par defaut: `7`
- Implication CPU par defaut: `3`
- DRS min par defaut: `0.70`
- Sharpening RCAS par defaut: `0.40`
- Runtime autoritaire actif par defaut: `true`
- Configuration separee: `config/pauc_ultimate_de_ouf.properties`
- Dossier shaders internes/externes separe: `pauc_ultimate_de_ouf_shaders/`

## Compatibilite runtime

- `embeddium`: backend rendu tolere.
- `servercore` / `vmp`: modules passifs toleres.
- `oculus`: domaine `shader_pipeline` conteste.
- stack replay: domaine `capture_pipeline` conteste.
- `distanthorizons`: domaine `chunk_streaming` conteste.
- `flerovium`: domaine `render_backend` conteste.
- `expandedworld`: domaine `worldgen` marque `high-risk`.

Les shaderpacks externes PauC restent chargeables sans `Oculus`. Le backend actuel est un systeme multi-pass PauC controle, en dossiers ou `.zip`, pas encore une compatibilite universelle Iris/Oculus.

Note transfert:

- ecran noir intermittent observe en entree monde dans le contexte `capture_pipeline` conteste (stack replay presente)
- voir `TRANSFERT_PROJET.md` pour le profil de reprise recommande et les priorites de correction

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

- `build/libs/pauc-ultimate-de-ouf-1.4.1-ultimate.jar`

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
