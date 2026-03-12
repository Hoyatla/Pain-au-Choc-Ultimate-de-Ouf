# Pain au Choc ultimate de Ouf - Cadrage

Cette variante sert a construire un gouverneur global solo:

- client rendu
- client chunks
- client entites
- client particules
- serveur integre

## Objectif

Maximiser les performances percues de Minecraft en solo en pilotant ensemble:

- frametime client
- charge GPU
- charge CPU client
- `MSPT` du serveur integre
- lisibilite camera
- stabilite du pack

## Blocs ajoutes dans cette iteration

- `GlobalPerformanceMode`
- `GlobalPerformanceGovernor`
- `AuthoritativeRuntimeController`
- `ManagedChunkRadiusController`
- `TerrainProxyController`

## Modes runtime

- `EXPLORATION`
- `COMBAT`
- `TRANSIT`
- `BASE`
- `CRISIS`

## Integrations deja branchees

- DRS: plancher adapte par mode
- chunks: budget et priorite adaptes par mode
- entites: protection des cibles critiques en combat
- pruning client: limite quand la lisibilite prime
- cadence IA serveur: ajustee par mode
- runtime autoritaire: classification des mods et suivi des domaines contestes
- rayon detail/stream/proxy separes
- proxy terrain PauC alimente par les chunks deja charges
- capture proxy predictive vers l'avant du joueur
- shaderpacks externes multi-pass sous autorite PauC

## Runtime autoritaire

- `PauC` est la source de verite du pack.
- Les mods charges sont classes:
  - `delegated backend`
  - `passive`
  - `forbidden for authoritative profile`
  - `high-risk`
- Domains suivis:
  - `render_backend`
  - `shader_pipeline`
  - `chunk_streaming`
  - `server_simulation`
  - `capture_pipeline`
  - `worldgen`
- Statuts exposes:
  - `sovereign`
  - `contested`
  - `degraded`

## Etapes suivantes

- instrumentation plus fine par scene
- adaptateurs explicites par famille de mods
- budget lisibilite plus explicite
- pipeline visuel PauC souverain sans shaderpack
- backend shaderpack PauC limite et controle
- proxy terrain plus riche que le simple cache ephemere actuel
- proxy terrain avec materiaux plus riches et transitions plus propres
- manifests shaderpack plus riches, uniforms et passes plus ambitieux
- garder le runtime PauC actif meme au cran `10`
- garder le proxy terrain compatible avec un stack capture present mais inactif

## Reprise

Si la session est interrompue pendant le chantier proxy terrain, repartir de:

- `PROXY_TERRAIN_ARCHITECTURE.md`
- `SHADERPACK_BACKEND_ARCHITECTURE.md`
- `ManagedChunkRadiusController.java`
- `TerrainProxyController.java`
- `LevelRendererMixin.java`
