# BETA 0.5.1 Shader Fix Status

## Etat actuel

- Le projet compile et produit les jars NeoForge 0.5.1.
- Photon et Solas sont embarques comme ressources extraites dans `common/src/main/resources/pauc/shaderpacks/`.
- La passe courante renforce la jonction `vanilla/LOD` en deplacement rapide et en elytra.
- Le fog Photon utilise maintenant les distances d'ombre demandees et effectives pour mieux couvrir la zone non ombree.

## Fix a faire

- Verifier en session que les LODs restent visibles et stables avec shaders actifs.
- Finaliser la jonction visuelle terrain `vanilla/LOD` sans trous ni clignotements sur travel rapide.
- Stabiliser la couverture d'ombres terrain sur Photon et Solas autour de la zone de transition.
- Verifier que le fog Photon se recale immediatement quand la distance d'ombre change en jeu.
