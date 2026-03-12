# Etude Approfondie - 10 Optimisations Hardcore (Pain au Choc / PauC)

Date: 2026-03-06
Contexte: Forge 1.20.1, architecture actuelle PauC (DRS, budgets, LOD entites, queue chunks, RCAS, detecteur bottleneck estime).

## Methode d'analyse

Chaque point est evalue selon:

* Gain potentiel réaliste dans un modpack lourd.
* Faisabilite technique dans Forge 1.20.1 sans réécrire tout le moteur.
* Risques (crash, artefacts, incompatibilites, regressions).
* Garde-fous de securisation (feature flags, fallback, telemetry, rollout progressif).

## 1\) DRS pilote par frametime GPU

Etat actuel:

* PauC fait deja du DRS.
* Le controle est principalement pilote par FPS lisse + detecteur bottleneck estime.

Faisabilite avancee:

* Faisable a court/moyen terme avec mesure GPU plus fiable (timer queries OpenGL) et boucle de controle en ms/frame.

Risques:

* Oscillation/pompage.
* Input lag si chute de scale trop agressive.
* Conflits avec shader mods (Oculus).

Securisation:

* Controle PID simplifie avec hystereses.
* Limiter delta scale par update.
* Cooldown de remontee.
* Fallback automatique sur mode actuel si mesure GPU invalide.
* Toggle F10 + kill switch config.

Priorite recommandee: Haute.

## 2\) Occlusion culling hierarchique profondeur (Hi-Z)

Etat actuel:

* Culling distance/frustum/octree, mais pas Hi-Z reel.

Faisabilite:

* Possible mais tres complexe en mod Forge vanilla renderer.
* Exige une passe depth pyramid et une logique de latence visibilite.

Risques:

* Popping.
* Cout CPU/GPU > gain si mal calibre.
* Fragilite avec pipelines shaders externes.

Securisation:

* Prototype uniquement sur block entities denses.
* Double-buffer visibilite (N/N+1) pour limiter popping.
* Timeout de revalidation forcant rendu temporaire.
* Activation uniquement en profil experimental.

Priorite recommandee: Moyenne/long terme.

## 3\) Clustered/Tiled visibility entites/block entities

Etat actuel:

* Traitement global avec index spatial.

Faisabilite:

* Bonne faisabilite incrementale via grille monde (pas ecran) cote CPU.

Risques:

* Complexite ordonnanceur.
* Cas limites (teleport, chunks en transition).

Securisation:

* Commencer par grille monde coarse 16x16/32x32.
* Plafonds par cluster avec minimum de rendu garantis.
* Telemetry: temps par passe + taux de rejet.

Priorite recommandee: Haute (apres instrumentation).

## 4\) LOD multi-niveaux entites

Etat actuel:

* Deja implemente (full/simplifie/static/billboard) + garde-fous GeckoLib.

Ameliorations possibles:

* LOD par famille d'entites configurable.
* Mise a jour animation par budget cluster.

Risques:

* Artefacts animation.
* Gameplay impacts (PVP, mobs critiques).

Securisation:

* Exclusions strictes (joueurs/projectiles/boss).
* Profils par categorie.
* Flags serveur local/test pour comparer.

Priorite recommandee: Continue (iteratif).

## 5\) LOD terrain reel (type DH) NON pas 5, à oublier)

Etat actuel:

* Hors scope actueI (decision produit: pas de LOD terrain distant).

Faisabilite:

* Techniquement tres lourde, proche d'un sous-moteur.

Risques:

* Dette architecturale majeure.
* Conflits rendu/shaders/memory.

Securisation:

* Ne lancer que si la decision produit change explicitement.
* Isoler dans module experimental separable.

Priorite recommandee: Non retenue dans le scope actuel.

## 6\) Rebuild mesh chunks par regions + cache incremental

Etat actuel:

* PauC agit sur file/priorisation/back-pressure, pas encore sur invalidation fine des meshes vanilla.

Faisabilite:

* Partiellement faisable en optimisant ordonnanceur et granularite de travail.
* Cache geometrique profond plus complexe sans forker pipeline chunk renderer.

Risques:

* Corruption visuelle si invalidation imparfaite.
* Bugs difficiles a reproduire.

Securisation:

* D'abord metriques: taux rebuild complet vs partiel.
* Activer invalidation fine uniquement sur cas simples.
* Hash de verification debug pour detecter mismatches.

Priorite recommandee: Moyenne.

## 7\) Upload GPU async + ring buffers persistants

Etat actuel:

* Pas de couche dediee persistently-mapped.

Faisabilite:

* Faisable mais bas niveau OpenGL, sensible au driver.

Risques:

* Stalls ou corruption buffer selon plateforme.
* Maintenance elevee.

Securisation:

* Feature detect OpenGL capabilities.
* Fallback immediat path vanilla.
* Canary mode: activer sur sous-ensemble de buffers.
* Telemetry frametime p95/p99 et stalls.

Priorite recommandee: Moyenne/long terme.

## 8\) Gouverneur unifie client + serveur integre

Etat actuel:

* Base deja en place (qualite, latence, particules, DRS, sim distance, queue chunk).

Faisabilite:

* Tres bonne, c'est le meilleur axe differenciant PauC.

Risques:

* Instabilite si boucles de controle couplent trop fort.
* Effets de bord entre systemes (DRS vs simulation vs chunks).

Securisation:

* Arbitre central avec priorites claires.
* Une seule variable de "pression globale" publiee en lecture.
* Cooldowns distincts par sous-systeme.
* Tests replay de scenarios lourds.

Priorite recommandee: Tres haute.

## 9\) Virtualisation VRAM / texture streaming

Etat actuel:

* Non implemente.

Faisabilite:

* Difficile dans vanilla/Forge (atlas, lifecycle textures, packs externes).

Risques:

* Hitches I/O, pop textures, crash GPU.

Securisation:

* Commencer par budget + monitoring VRAM approximatif.
* Degrader en mip bias global avant streaming intelligent.
* Blacklist packs/mods incompatibles.

Priorite recommandee: Basse a moyenne (R\&D).

## 10\) Reecriture ciblee lumiere/transparence/particules

Etat actuel:

* Particules deja budgetees.
* Pas de reecriture profonde lumiere/transparence.

Faisabilite:

* Tres couteuse mais potentiellement payante sur frametime.

Risques:

* Incompatibilites graphiques larges.
* Charge de maintenance tres elevee.

Securisation:

* Traiter sous-systemes un par un.
* Commencer par transparence/particules avant lumiere.
* Basculer automatiquement vers mode vanilla en cas d'erreur render.

Priorite recommandee: Long terme.

## Roadmap realiste et securisee

Phase 1 (court terme, ROI fort):

* 8 gouverneur unifie (consolidation)
* 1 DRS frametime plus strict (mesures stables)
* 3 clustered visibility CPU monde
* amelioration 4 LOD entites

Phase 2 (moyen terme):

* 6 invalidation/rebuild plus fine
* 7 uploads GPU optimises

Phase 3 (R\&D):

* 2 Hi-Z occlusion
* 9 VRAM virtualization
* 10 reecritures profondes

## Garde-fous obligatoires (tous chantiers)

* Chaque feature derriere flag config.
* Fallback instantane vers comportement stable.
* Journalisation concise des raisons de fallback.
* Metriques p50/p95/p99 frametime CPU/GPU (si dispo).
* Scenarios de regression reproductibles:

  * deplacement rapide Elytra
  * village dense
  * combat particules massif
  * base moddee avec block entities
  * solo modpack lourd avec shader mod present/absent

## Decision de cadrage

Dans l'etat actuel de PauC, l'axe le plus rentable est:

* renforcer le gouverneur unifie,
* fiabiliser la mesure frametime,
* etendre la priorisation locale (clusters),

avant toute reecriture lourde du pipeline graphique bas niveau.

