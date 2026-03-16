# Shaderpack Backend PauC - Architecture Et Limites

Ce document decrit le backend shaderpack actuellement implemente par PauC.

## Positionnement

PauC possede maintenant deux backends shader:

### 1. Pipeline deferred Oculus-like (nouveau, `2.0.0`)

Pipeline de shaders deferes complet, compatible avec les shaderpacks OptiFine standard:

- PauC controle le pipeline de rendu monde complet
- GBuffers, shadow mapping, deferred/composite/final passes
- Les shaderpacks OptiFine prennent le controle du rendu via programmes GLSL
- Charge depuis `shaderpacks/` (meme emplacement qu'Iris/Oculus)
- Integre avec le gouverneur PauC (distance ombres adaptative, skip shadow en CRISIS)

### 2. Backend multi-pass PauC (existant)

Systeme de `post-process multi-pass` pilote par PauC:

- PauC reste l'autorite
- les shaderpacks PauC sont des stacks de passes ecran
- ils ne prennent pas le controle du rendu monde complet
- Charge depuis `pauc_ultimate_de_ouf_shaders/packs/`

## Etat courant

Release de reference: `2.0.0-ultimate`

Le pipeline deferred sait:

- charger des shaderpacks OptiFine standard depuis `shaderpacks/` (ZIP ou dossier)
- parser `shaders.properties`, resoudre les `#include`, injecter les macros
- rendre dans des GBuffers (`colortex0-7`, `depthtex0-2`)
- executer le shadow mapping avec distance adaptative
- enchainer les passes deferred, composite et final
- fournir des uniforms riches (camera, celestial, temps, brouillard, PauC exclusifs)
- tracker les phases de rendu pour les programmes `gbuffers_*`
- persister le pack selectionne dans la config PauC
- s'activer automatiquement au demarrage apres GL context ready

Le backend multi-pass PauC sait toujours:

- scanner des shaderpacks PauC dans `pauc_ultimate_de_ouf_shaders/packs/`
- scanner des packs en dossier et des packs `.zip`
- lire un manifeste `pauc_shaderpack.json`
- charger plusieurs passes par pack
- executer ces passes en ping-pong sur des render targets temporaires

## Fichiers clefs

### Pipeline deferred (Oculus-like)
- `src/main/java/pauc/pain_au_choc/render/shader/DeferredWorldRenderingPipeline.java`
  - pipeline principal: GBuffers, deferred, composite, final passes
  - gestion des render targets, phases, uniforms
- `src/main/java/pauc/pain_au_choc/render/shader/PauCDeferredShaderController.java`
  - lifecycle de gestion des shaderpacks OptiFine
  - scan `shaderpacks/`, selection, activation, cycle, reload
  - persistance config (`deferredShaderPack`)
- `src/main/java/pauc/pain_au_choc/render/shader/ShadowRenderer.java`
  - rendu de la shadow map avec distance adaptative par gouverneur
- `src/main/java/pauc/pain_au_choc/render/shader/ShaderPackLoader.java`
  - chargement des shaderpacks OptiFine (ZIP/dossier, `#include`, macros)
- `src/main/java/pauc/pain_au_choc/render/shader/PauCShaderProgram.java`
  - compilation et gestion des programmes GLSL
- `src/main/java/pauc/pain_au_choc/render/shader/PauCRenderTargets.java`
  - GBuffers et render targets du pipeline
- `src/main/java/pauc/pain_au_choc/render/shader/WorldRenderingPhase.java`
  - enum des phases de rendu (SKY, TERRAIN_*, ENTITIES, etc.)

### Backend multi-pass PauC
- `src/main/java/pauc/pain_au_choc/PauCShaderManager.java`
  - point d'entree principal
  - arbitre entre shader interne, shader externe single-pass et shaderpack multi-pass
- `src/main/java/pauc/pain_au_choc/PauCShaderPackManager.java`
  - scan du dossier `packs/`
  - chargement des manifests
  - creation des passes
  - execution du chainage multi-pass
  - gestion des render targets temporaires

### Commun
- `src/main/java/pauc/pain_au_choc/PauCClient.java`
  - persistance du shader actif et du shaderpack deferred
- `src/main/java/pauc/pain_au_choc/PauCConfigScreen.java`
  - cycle shader actif + cycle shaderpack deferred
  - reload shaderpacks (PauC + OptiFine)
  - ouverture des dossiers shader
- `src/main/java/pauc/pain_au_choc/mixin/LevelRendererMixin.java`
  - hooks deferred pipeline (begin/end, terrain/entity/sky phases)
  - activation du shaderpack sauvegarde dans `allChanged`
- `src/main/java/pauc/pain_au_choc/mixin/DebugScreenOverlayMixin.java`
  - affichage deferred pipeline dans F3

## Format d'un shaderpack

Emplacement:

- `pauc_ultimate_de_ouf_shaders/packs/<nom_du_pack>/`
- `pauc_ultimate_de_ouf_shaders/packs/<nom_du_pack>.zip`

Exemples directement chargeables generes par PauC:

- `pauc_ultimate_de_ouf_shaders/packs/competitive_fxaa/`
- `pauc_ultimate_de_ouf_shaders/packs/cinematic_light/`

Fichier obligatoire:

- `pauc_shaderpack.json`

Exemple minimal:

```json
{
  "label": "Competitive FXAA Stack",
  "nativePass": true,
  "passes": [
    { "builtin": "fxaa_photon", "label": "FXAA" },
    { "builtin": "shadow_lift", "label": "Shadow Lift" },
    { "builtin": "light_clarity", "label": "Light Clarity" }
  ]
}
```

Une passe peut etre:

- `builtin`
- `file`

Exemple avec fichier custom:

```json
{
  "label": "Cinematic Light Stack",
  "nativePass": true,
  "passes": [
    { "builtin": "fxaa_elite", "label": "FXAA Elite" },
    { "builtin": "warm_tonemap", "label": "Warm Tonemap" },
    { "file": "passes/custom_glow.fsh", "label": "Custom Glow" }
  ]
}
```

## Passes built-in actuellement exposees

- `fxaa_photon`
- `fxaa_elite`
- `shadow_lift`
- `light_clarity`
- `warm_tonemap`

## Ce que signifie `nativePass`

`nativePass=true` indique a PauC que le pack prefere travailler a l'echelle native plutot qu'en plein DRS agressif.

Ce flag reste subordonne a PauC:

- PauC garde le droit de reprendre la main
- le runtime autoritaire reste la source de verite

## Limites actuelles

Le pipeline deferred Oculus-like ne fait pas encore:

- PBR (normal maps, specular maps) — prevu Phase 3.3
- optimisations avancees de rendu de blocs (feuilles, modeles bakes) — prevu Phase 3.1
- optimisations avancees de rendu d'entites (fast model rendering) — prevu Phase 3.2
- compatibilite universelle avec tous les shaderpacks OptiFine (certains uniforms/features manquants)

Le backend multi-pass PauC ne fait toujours pas:

- lumiere volumetrique monde
- eau/shader sky/shadow map completes

## But technique de la 2.0

Cette version sert a:

- posseder nativement le pipeline de rendu terrain et shader sans dependance externe
- eliminer le besoin d'Embeddium et Oculus comme mods separees
- integrer profondement le pipeline shader avec le gouverneur PauC (distance ombres adaptative, skip shadow en CRISIS)
- permettre le chargement direct de shaderpacks OptiFine standard

## Reprise

Si ce chantier est interrompu, repartir de:

- `README.md`
- `CHANGELOG.md`
- `PROXY_TERRAIN_ARCHITECTURE.md`
- `SHADERPACK_BACKEND_ARCHITECTURE.md`
- `PauCShaderManager.java`
- `PauCShaderPackManager.java`

## Prochaine etape logique

Phases restantes du plan d'integration:

- **Phase 3.1**: optimisations de rendu de blocs (feuilles, modeles bakes, palette, sprites)
- **Phase 3.2**: optimisations de rendu d'entites (fast model rendering, particles)
- **Phase 3.3**: support PBR (normal maps, specular maps, detection auto)
- **Phase 3.4**: mise a jour des policies AuthoritativeRuntime (detection si Embeddium/Oculus installes en plus)

Pour le backend multi-pass PauC:

- schema de manifeste plus riche et plus strict
- uniforms plus riches et plus stables par passe
- meilleure integration DRS / AA / sharpen
