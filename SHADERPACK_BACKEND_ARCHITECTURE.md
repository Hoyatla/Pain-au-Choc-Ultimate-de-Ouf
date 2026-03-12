# Shaderpack Backend PauC - Architecture Et Limites

Ce document decrit le backend shaderpack actuellement implemente par PauC.

## Positionnement

Le backend shaderpack PauC n'est pas `Oculus`.

Il s'agit d'un systeme de `post-process multi-pass` pilote par PauC:

- PauC reste l'autorite
- les shaderpacks externes sont des stacks de passes ecran
- ils ne prennent pas le controle du rendu monde complet

## Etat courant

Release de reference: `1.4.1-ultimate`

Le backend actuel sait:

- scanner des shaderpacks externes dans `pauc_ultimate_de_ouf_shaders/packs/`
- scanner des packs en dossier et des packs `.zip`
- lire un manifeste `pauc_shaderpack.json`
- charger plusieurs passes par pack
- executer ces passes en ping-pong sur des render targets temporaires
- selectionner le pack actif depuis PauC
- persister le `shaderKey` actif dans la config PauC

## Fichiers clefs

- `src/main/java/pauc/pain_au_choc/PauCShaderManager.java`
  - point d'entree principal
  - arbitre entre shader interne, shader externe single-pass et shaderpack multi-pass
- `src/main/java/pauc/pain_au_choc/PauCShaderPackManager.java`
  - scan du dossier `packs/`
  - chargement des manifests
  - creation des passes
  - execution du chainage multi-pass
  - gestion des render targets temporaires
- `src/main/java/pauc/pain_au_choc/PauCClient.java`
  - persistance du shader actif
- `src/main/java/pauc/pain_au_choc/PauCConfigScreen.java`
  - cycle du shader actif
  - reload shaderpacks
  - ouverture du dossier shader
  - affichage du nombre de packs et du shader actif

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

Le backend actuel ne fait pas encore:

- ombres monde reelles type shaderpack complet
- lumiere volumetrique monde
- eau/shader sky/shadow map completes
- compatibilite Iris/Oculus pack universelle

Donc:

- c'est deja un vrai backend externe multi-pass PauC
- ce n'est pas encore un clone d'Oculus

## But technique de cette etape

Cette etape sert a:

- eliminer la dependance conceptuelle a un seul shader simple
- permettre AA/FXAA + lumiere/ombres ecran en couches
- garder un format externe que PauC controle

## Reprise

Si ce chantier est interrompu, repartir de:

- `README.md`
- `CHANGELOG.md`
- `PROXY_TERRAIN_ARCHITECTURE.md`
- `SHADERPACK_BACKEND_ARCHITECTURE.md`
- `PauCShaderManager.java`
- `PauCShaderPackManager.java`

## Prochaine etape logique

Si on continue le backend shaderpack PauC, les priorites sont:

- schema de manifeste plus riche et plus strict
- uniforms plus riches et plus stables par passe
- integration depth/color plus ambitieuse
- passes additionnelles utiles au combat
- meilleure integration DRS / AA / sharpen
- eventual shadow/light backend plus ambitieux
