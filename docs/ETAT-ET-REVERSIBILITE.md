# État du build déployé (jar 17:20, 19-07-2026) et réversibilité complète

Tout ce qui a été modifié les 17–19 juillet est réversible **sans moi**, par drapeau JVM ou config.
Aucune recompilation nécessaire.

## Retrouver les FPS

| Levier | État actuel | Pour revenir en arrière |
|---|---|---|
| Passe d'ombre heightfield (~8 ms/frame) | **OFF** (config `pauc_lods.properties` → `shadowMode=off`) | Jauge « ombres » dans les options vidéo, ou `shadowMode=basic` dans le fichier |
| Passe near-vanilla Z1 (AO sommets, couleurs pures, fusion greedy, relief ombré, teinte d'eau 0.80) | ON (coût : build de mesh asynchrone uniquement, ~0 sur la frame) | `-Dpauc.lodengine.nearVanillaPass=false` → rendu exact d'avant le 19-07, un seul drapeau |
| Réparateur de trous (régions absentes, remplissage step 16) | ON (threads de fond) | pas de drapeau dédié ; coût render-thread nul, corrige les trous ciel prouvés |
| Imposteurs (dégradé vertical, silhouettes chêne/sapin/acacia, troncs bruns) | ON (coût nul, même nombre de sommets) | cosmétique pur ; `pauc.lodengine.treeImposters=false` coupe tout imposteur |
| Structures immergées (épaves/ruines/monuments) | plus dessinées (fidèle vanilla) | rien à faire, aucun coût |

## Mesurer au lieu de croire

La ligne de log `PauC render profiler` couvre désormais **tout** le per-frame du mod :
`terrain`, `imposters`, `structures`, `shadow`, `clouds` — en ms CPU render-thread, toutes les 3 s.
Si les fps ne sont pas là, cette ligne désigne le coupable. Plus jamais de débat sans elle.

## Drapeaux individuels de la passe near-vanilla (si le maître est ON)

- `pauc.lodengine.nearVertexAo` (défaut true)
- `pauc.lodengine.nearPureColors` (défaut true)
- `pauc.lodengine.nearGreedyMerge` (défaut true)
- `pauc.client.terrainShading` (toggle vidéo « Relief ombré »)
- `pauc.lodengine.waterSeamShade` (défaut 0.80)
- `pauc.lodengine.biomeBlend` (défaut false, jamais activé)

## Ce qui est acquis (vérifié sur données, pas sur promesse)

- Architecture cible intacte : base triangle partout, raffinement blocky Z1, imposteurs.
- Store de surfaces 100 % dense (les trous ciel des vols rapides sont réparés et le réparateur
  suit le joueur) — vérifié en parsant les fichiers `.pauclod` après session.
- Rendu terrain LOD : 6-7 ms → 1-2,5 ms sur le thread de rendu (ligne profiler).
