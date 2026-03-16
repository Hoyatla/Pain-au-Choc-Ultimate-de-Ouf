# Changelog

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
