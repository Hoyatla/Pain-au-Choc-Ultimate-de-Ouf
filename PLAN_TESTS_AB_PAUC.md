# Plan de Test A/B - Pain au Choc (PauC)

Date: 2026-03-06
Scope valide: points `1,2,3,4,6,7,8,9,10` (point `5` exclu).

## Objectif

Mesurer le gain reel vs risque sur modpack lourd, avec priorite au frametime stable.

## Regles de test

- Meme seed / meme monde / meme trajet.
- Meme modlist / memes packs / meme version GPU driver.
- Vsync OFF.
- Capture identique:
  - FPS moyen
  - 1% low
  - stutters visibles (micro-freezes)
  - stabilite visuelle (popping, artefacts)
  - crash / warning logs

## Profils a comparer

- A: PauC OFF (baseline)
- B1: PauC ON "stable" (qualite 7, CPU 2, RCAS 0.35)
- B2: PauC ON "agressif" (qualite 5, CPU 3, RCAS 0.45)

Application auto profils (PowerShell, depuis la racine projet):

- `.\tools\apply_pauc_profile.ps1 -Profile baseline_off`
- `.\tools\apply_pauc_profile.ps1 -Profile stable`
- `.\tools\apply_pauc_profile.ps1 -Profile aggressive`

## Scenes (3 min chacune)

1. Village dense (entites + block entities)
2. Deplacement rapide (elytra/cheval/chunk loading)
3. Combat/particules (explosions, armes, effets)
4. Base moddee lourde (machines/decos/transparence)

Ordre recommande: `A -> B1 -> A -> B2` pour reduire biais thermique.

## Matrice des points (hors 5)

### Phase immediate (deja dans PauC)

- Point 1: DRS + gouverneur frametime
- Point 4: Entity LOD
- Point 8: Gouverneur unifie (partiel)

Critere garder:

- `+15%` ou plus sur 1% low, OU
- `-30%` de saccades percues, sans artefact majeur.

### Phase incrementale (si phase immediate validee)

- Point 3: clustered visibility (version CPU monde)
- Point 6: invalidation/rebuild plus fine

Critere garder:

- gain net stable sur 3 scenes sur 4,
- pas de regression critique visuelle.

### Phase R&D (haut risque)

- Point 2: Hi-Z occlusion
- Point 7: upload GPU async/ring buffers
- Point 9: VRAM virtualization
- Point 10: reecriture transparence/particules/lumiere

Critere garder:

- gain fort et constant,
- aucun crash,
- fallback auto fiable valide.

## Garde-fous obligatoires

- Feature flag par point.
- Fallback instantane.
- Cooldown/hysteresis sur boucles adaptatives.
- Rollback possible en 1 commit.

## Journal de decision

Apres chaque point:

1. Gain constate (% FPS moyen, % 1% low, ressenti stutter)
2. Risques observes
3. Decision: `garder`, `garder sous flag`, `rollback`

Voir le fichier `RESULTATS_TESTS_AB_PAUC.csv`.

