# Proxy Terrain PauC - Architecture Et Reprise

Ce document sert de reference de reprise si une session plante ou s'interrompt pendant le chantier proxy terrain.

## Objectif

Le proxy terrain PauC existe pour etendre la portee visuelle utile au-dela du `renderDistance` vanilla sans payer le prix d'un rendu chunk complet.

Le systeme vise 4 anneaux distincts:

- `full detail`: chunks vanilla complets, gameplay prioritaire
- `stream`: chunks geres et priorises par PauC, encore relies au monde reel
- `proxy`: terrain lointain simplifie rendu par PauC
- `managed`: enveloppe maximale geree par PauC, meme si tout n'est pas rendu

## Etat courant

Release de reference: `1.4.1-ultimate`

Le proxy terrain actuel est un `far-field cache` ephemere:

- il n'ecrit rien sur disque
- il se nourrit des chunks client deja charges
- il extrait un resume tres simple du relief et de la couleur dominante
- il rend des prismes simplifies au-dela du rayon detail
- il peut deplacer son centre de capture vers l'avant du joueur
- il echantillonne maintenant `4x4` cellules par chunk proxy
- il regroupe ces cellules a grande distance pour garder un cout stable

Ce n'est pas encore:

- un remplaçant complet de `Distant Horizons`
- un streamer autonome de chunks distants
- un cache persistant
- un mesh terrain detaille

## Fichiers clefs

- `src/main/java/pauc/pain_au_choc/ManagedChunkRadiusController.java`
  - source de verite des rayons `full`, `stream`, `proxy`, `managed`
  - plafond actuel du rayon proxy: `256` chunks geres
- `src/main/java/pauc/pain_au_choc/TerrainProxyController.java`
  - capture des resumes de chunks
  - cache ephemere en memoire
  - selection de rendu
  - emission des quads/prismes simplifies
- `src/main/java/pauc/pain_au_choc/DistanceBudgetController.java`
  - les distances detail/PauC lisent maintenant le rayon `full` via `ManagedChunkRadiusController`
- `src/main/java/pauc/pain_au_choc/StructureStreamingController.java`
  - reste sur la zone detail pour la priorite de chunks reelle
- `src/main/java/pauc/pain_au_choc/PauCClient.java`
  - tick du proxy
  - reset du proxy quand PauC se coupe ou change de qualite
- `src/main/java/pauc/pain_au_choc/PauCPipeline.java`
  - reset global du proxy
- `src/main/java/pauc/pain_au_choc/mixin/LevelRendererMixin.java`
  - point d'injection rendu du proxy avant `setupRender(...)`
- `src/main/java/pauc/pain_au_choc/AuthoritativeRuntimeController.java`
  - le proxy se coupe si PauC ne possede pas le domaine

## Contrat d'autorite

Le proxy terrain n'est autorise que si:

- PauC est actif
- le runtime autoritaire est actif
- aucun backend concurrent n'a la main sur le domaine critique

Le proxy se desactive si un des domaines suivants est conteste:

- `chunk_streaming`
- `shader_pipeline`
- `capture_pipeline`

Donc:

- `Distant Horizons` conteste le proxy
- `Oculus` peut aussi le bloquer tant que PauC ne possede pas encore tout le pipeline visuel

## Modele de donnees actuel

Chaque chunk proxy en memoire contient:

- sa position chunk `x/z`
- `4` cellules internes (`2x2`)
- pour chaque cellule:
  - une hauteur surface
  - une couleur ARGB simplifiee
- un `minHeight`
- un `maxHeight`
- une `averageHeight`
- une generation de derniere observation

Ce choix est volontairement simple pour garder:

- peu de CPU
- peu de memoire
- peu de risque de stutter

## Flux runtime actuel

### Tick

`TerrainProxyController.tick()`:

- verifie si le proxy est autorise
- detecte le changement de monde
- recale le centre de capture sur le joueur
- scanne progressivement une zone de capture
- n'echantillonne que les chunks deja charges
- extrait `Heightmap WORLD_SURFACE` + couleur dominante
- met a jour le cache
- elague les entrees trop vieilles ou trop lointaines

### Render

`TerrainProxyController.render(...)`:

- s'execute avant le terrain vanilla complet
- filtre les chunks proxies selon:
  - distance
  - cone de visibilite
  - stride de simplification
- dessine des volumes simples avec `POSITION_COLOR`
- applique une attenuation alpha avec la distance

### Reset

Le proxy est remis a zero si:

- le monde client change
- PauC est desactive
- le pipeline PauC est jete
- la qualite change et force un reset global

## Rayons et logique actuelle

Le systeme ne se contente plus du `renderDistance` vanilla.

Rayons actuels:

- `full detail`
  - equivalent au rayon vanilla reel
- `stream`
  - rayon de gestion PauC pour les decisions runtime
- `proxy start`
  - `full detail + 4`
- `proxy radius`
  - rayon lointain dynamique, borne jusqu'a `256`

Capture predictive:

- une ancre de capture peut etre decalee vers l'avant du joueur
- le decalage depend du mode runtime, de l'implication CPU et de la pression
- en transit, le cache se concentre davantage sur la zone utile devant la camera

Le rayon varie selon:

- qualite PauC
- implication CPU
- mode runtime global
- pression client
- pression serveur integre
- statut autoritaire

## Limites connues

- le proxy n'invente pas encore de terrain non vu
- pas de cache disque
- pas de materiaux riches par biome
- pas encore de transition geometrique fine entre terrain reel et proxy
- pas encore de prediction forte basee sur la direction/mouvement
- pas encore de water bodies et silhouettes complexes a longue distance

## Direction prevue

Le prochain objectif est un proxy `plus riche`:

- retention preferentielle des proxies utiles en transit
- meilleure lecture des masses d'eau et silhouettes
- selection de rendu plus intelligente en mouvement
- ensuite seulement: mesh plus riche, transitions plus propres, eventuel prefetch plus agressif

## Invariants a respecter

Ne pas casser ces regles:

- le proxy ne doit jamais devenir un deuxieme gouverneur autonome
- le proxy ne doit pas forcer le chargement disque/monde a lui seul
- le proxy doit rester cheap par frame
- le proxy doit s'effacer proprement si le runtime devient `contested` ou `degraded`
- aucune ecriture persistante tant que le format de cache n'est pas stable

## Checklist De Reprise

Si une session plante, repartir de cette liste:

1. Verifier que la release de travail est bien `Pain_au_Choc_ultimate_de_Ouf`.
2. Relire:
   - `DOSSIER_PROJET.md`
   - `ULTIMATE_DE_OUF_PLAN.md`
   - `PROXY_TERRAIN_ARCHITECTURE.md`
3. Revalider les fichiers clefs:
   - `ManagedChunkRadiusController.java`
   - `TerrainProxyController.java`
   - `LevelRendererMixin.java`
4. Verifier que le proxy reste subordonne a `AuthoritativeRuntimeController`.
5. Recompiler avec Java 17:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileJava -x test
.\gradlew.bat jar
```

6. Reprendre le chantier dans cet ordre:
   - doc
   - comportement runtime
   - rendu
   - build

## Jar De Reference

Artefact de reference actuel:

- `build/libs/pauc-ultimate-de-ouf-1.4.1-ultimate.jar`
