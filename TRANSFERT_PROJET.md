# Transfert Projet - PauC Ultimate De Ouf

Date de transfert: `2026-03-12`

Ce document donne un etat de passation exploitable immediatement pour un nouveau mainteneur.

## Scope transfere

- Projet: `Pain_au_Choc_ultimate_de_Ouf`
- Mod id: `pauc`
- Version code actuelle: `2.0.0-ultimate`
- Artefact: `build/libs/pauc-ultimate-de-ouf-2.0.0-ultimate.jar`

## Ce qui est valide

- Le jar `1.4.1-ultimate` est bien charge en jeu.
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

2. Bruit important de warnings shader uniforms.
- Les packs PauC chargent, mais plusieurs uniforms absents sont logues.
- Ce bruit ne bloque pas le chargement, mais complique le diagnostic.

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
2. puis re-activer DRS
3. puis ajuster mode qualite

## Priorites techniques pour le prochain mainteneur

1. Mettre un garde-fou dur sur DRS quand `capture_pipeline` est conteste.
2. Ajouter un fallback anti-ecran-noir (restauration explicite main target si blit echoue).
3. Reduire le bruit uniforms: setter seulement les uniforms presentes.
4. Ajouter des logs runtime explicites:
- `proxy on/off + reason`
- `drs on/off + reason`
- `shaderpack actif + mode natif/drs`

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

### Mixins
- `src/main/java/pauc/pain_au_choc/mixin/LevelRendererMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/GameRendererMixin.java`
- `src/main/java/pauc/pain_au_choc/mixin/DebugScreenOverlayMixin.java`

## Build / verification rapide

```bash
./gradlew.bat compileJava -x test
./gradlew.bat jar
```

Verifier ensuite:

- version jar: `2.0.0-ultimate`
- logs de chargement shaderpacks
- absence d'ecran noir en entree monde sur profil safe debug
