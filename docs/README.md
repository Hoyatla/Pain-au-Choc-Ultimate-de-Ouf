# PauC Ultimate Forge Notes

This active source tree is the Forge-only PauC Ultimate branch.

## Current Runtime Shape

- Public Forge mod id: `paucultimate`.
- Release artifact: `dist/Pain_au_Choc_Ultimate_de_Ouf-0.1.0.jar`.
- Current beta release notes: [`BETA_RELEASE_0.1.0.md`](BETA_RELEASE_0.1.0.md).
- Embedded long-distance terrain runtime is published under PauC-owned jar names and runtime resource names.
- Legacy loader documentation and inherited user guides were removed from the active source tree; the preserved reference copy remains available at:
  `D:\Dev\Pain_au_Choc_Ultimate_de_Ouf - Road Beta\PauCUltimate_REFERENCE_BEFORE_PROPRIETARY_FORGE`

## Verification Checklist

- Run `:neoforge:build`.
- Confirm the generated jar scan does not report legacy public ids.
- Test in the Prism instance:
  `C:\Users\charl\AppData\Roaming\PrismLauncher\instances\1.20.1 road beta\minecraft`
- After a session, check `logs/latest.log` for shader-transform errors and old embedded LOD config names.
