# PauCUltimate Forge Migration Plan

Licensing update: as of 2026-06-07, the active project license is the GNU Lesser General Public License v3.0. The former proprietary migration objective is superseded; this document now tracks Forge packaging, namespace isolation, and third-party attribution cleanup.

Reference copy: `D:\Dev\Pain_au_Choc_Ultimate_de_Ouf - Road Beta\PauCUltimate_REFERENCE_BEFORE_PROPRIETARY_FORGE`

## Objectives

- Make the project Forge-only: no Fabric module, no Fabric metadata, no Fabric loader/runtime requirement.
- Stop claiming ownership of external mod ids: no `sodium`, `indium`, `iris`, or `oculus` aliases in Forge metadata.
- Keep only minimal Iris-style shader-pack compatibility where it is still needed for shader-pack parsing/rendering.
- Isolate embedded LOD code so it cannot collide with a real Distant Horizons installation.
- Keep inherited Iris, Sodium, Indium, and Distant Horizons implementation pieces compatible with the active LGPL v3.0 distribution path, while replacing or isolating code where needed for maintainability and attribution clarity.

## Immediate Changes

1. Remove the `fabric` module directory and Fabric Gradle plugin usage from the root build.
2. Convert `common` away from Fabric Loom so the shared source holder is no longer a Fabric project.
3. Remove Fabric repositories/properties that are no longer required by the active build.
4. Update Forge `mods.toml` files:
   - license becomes GNU Lesser General Public License v3.0,
   - `provides` keeps only PauC ids,
   - external renderer ids are not exposed,
   - Fabric renderer metadata is removed.
5. Remove generated/runtime config names that imply Sodium/Indium/Distant Horizons where PauC controls the name.

## Distant Horizons Isolation Track

The current jar embeds Distant Horizons classes under `com.seibel.distanthorizons`. That is not safe if the real Distant Horizons mod is also present, because class names, config names, loggers, data names, shader asset paths, and thread names still overlap.

Required end state:

- Relocate embedded classes from `com.seibel.distanthorizons` to a PauC namespace such as `fr.hoyatla.pauc.embedded.lod`.
- Rewrite embedded resources from `assets/distanthorizons` to `assets/paucultimate_lod`.
- Rename config/data outputs:
  - `DistantHorizons.toml` -> `paucultimate-lod.toml`,
  - world saved data beginning with `DistantHorizons` -> PauC-owned names,
  - `DH-*` thread names -> `PauC-LOD-*`,
  - `DistantHorizons-*` loggers -> `PauC-LOD-*`.
- Change all direct `com.seibel.distanthorizons` references in PauC mixins/bridges to the relocated namespace.
- Keep external Distant Horizons compatibility optional and disabled by default when PauC embedded LOD is active.

## Third-Party Code Replacement Track

PauCUltimate currently contains inherited Iris-style shader code and vendored Distant Horizons/Sodium-derived code. Renaming is not enough to change the licensing obligations of that code.

Required end state:

- Keep the reference copy untouched as the rollback/source-history base.
- Identify third-party implementation blocks by package/source set:
  - `net.irisshaders.*`,
  - `net.caffeinemc.*`,
  - `com.seibel.distanthorizons.*`,
  - `common/src/vendored`.
- Replace these blocks incrementally with PauC-owned implementations where useful, or keep them clearly attributed under their compatible third-party licenses.
- Remove inherited README/docs/license claims from release artifacts once replacement is complete.
- Keep legal attribution files only for any third-party code that remains.

## Verification

- Build Forge jar.
- Inspect jar contents for forbidden public ids and packages:
  - `fabric.mod.json`, `quilt.mod.json`,
  - `sodium`, `indium`, `iris`, `oculus` in metadata,
  - `com/seibel/distanthorizons` in relocated final artifacts,
  - `assets/distanthorizons`,
  - `DistantHorizons.toml` strings.
- Test in the Prism instance:
  `C:\Users\charl\AppData\Roaming\PrismLauncher\instances\1.20.1 road beta\minecraft`
- Confirm logs no longer create Distant Horizons config/data/log/thread names when only PauC is installed.

## Execution Log - 2026-05-24

- Removed the Fabric module from the active build and converted `common` to a plain Java source holder.
- Updated Forge metadata to PauC-owned ids and LGPL v3.0 licensing.
- Removed Indigo/Indium compatibility resources from the active source tree.
- Added generated-jar sanitization for legacy public ids and embedded LOD names.
- Fixed the sanitizer after the Prism session showed a GLSL parser/ANTLR initialization error: jar entry names are still renamed, but arbitrary third-party binary class bodies are no longer rewritten.
- Renamed the active compatibility source set from `sodiumCompatibility` to `paucorCompatibility`.
- Replaced inherited Fabric/Iris user docs in the active source tree with a short PauC Forge note; the full legacy docs remain in the reference copy.
- Removed the unused `vendor/sodium-neoforge` source vendor and renamed the embedded LOD runtime jar path to a PauC-owned vendor path.
- Removed the stale Forge runtime check for `fabric-resource-loader-v0` from the GUI texture registration mixin.
- Removed copied Distant Horizons API header stubs from the active source compile path; the Forge build now compiles against the vendored PauC LOD runtime jar instead.
- Rebuilt `:neoforge:build` successfully and verified the GLSL parser classes load without the previous ATN failure.
- Scanned `Pain_au_Choc_Ultimate_de_Ouf-0.9a.jar`; no forbidden legacy fragments were found by the raw artifact scan.
- Replaced the test-instance jar in:
  `C:\Users\charl\AppData\Roaming\PrismLauncher\instances\1.20.1 road beta\minecraft\mods`
- Moved stale active instance config/shader outputs using old Distant Horizons/Sodium-style names into:
  `C:\Users\charl\AppData\Roaming\PrismLauncher\instances\1.20.1 road beta\minecraft\pauc_migration_backup_20260524-221923`
- Fixed the 2026-05-24 23:30 Prism startup crash by sanitizing `META-INF/services/*` contents too. The final jar now exposes:
  `META-INF/services/net.paucshaders.pauc.platform.PauCPlatformHelpers`
  -> `net.paucshaders.pauc.platform.PauCForgeHelpers`.

Remaining third-party cleanup work:

- Replace inherited `net.irisshaders.*`, `net.caffeinemc.*`, and embedded LOD implementation code with PauC-owned implementations where maintenance or packaging isolation requires it.
- Keep the binary sanitizer until source-level package relocation is complete, especially for embedded LOD classes.
