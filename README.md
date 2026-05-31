# PauC_Ultimate_de_Ouf

PauC_Ultimate_de_Ouf is a Forge-only Minecraft 1.20.1 rendering stack.

The project now targets a single distribution path:

- Forge 47.4.x on Minecraft 1.20.1
- Java 17
- Mod id: `paucultimate`
- Internal feature ids: `pauc_core`, `pauc_shader`

The active build is intentionally not a Fabric project and does not provide or claim the public ids of Sodium, Indium, Iris, Oculus, or Distant Horizons.

## Development Notes

- `common` is a shared source holder used by the Forge build.
- `neoforge` produces the distributable jar.
- `PROPRIETARY_FORGE_MIGRATION_PLAN.md` tracks the cleanup/replacement path for inherited renderer and LOD code.
- `vendor` contains temporary reference dependencies used while PauC-owned replacements are being built.

## Test Instance

The current local test instance is:

`C:\Users\charl\AppData\Roaming\PrismLauncher\instances\1.20.1 road beta\minecraft`
