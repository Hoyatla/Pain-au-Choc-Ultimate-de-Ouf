# NeoForge Latest Port Track

Date: 2026-06-07

This track keeps the validated Forge 1.20.1 beta intact and prepares a separate
NeoForge/latest Minecraft port.

## Current Verified Targets

- Stable beta branch: Forge 47.4.20 on Minecraft 1.20.1, Java 17.
- Latest Minecraft release observed from Mojang manifest: `26.1.2`.
- Latest NeoForge release observed from NeoForged Maven metadata: `26.1.2.74`.

Version sources:

- `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
- `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml`

## Branch Strategy

- Keep `master` as the beta-safe Forge 1.20.1 baseline.
- Use `port/neoforge-latest-foundation` for the first architecture pass.
- Do not replace the beta runtime dependency directly on `master`.

## First Foundation Pass

The first pass introduces `PauCPlatformServices` as a PauC-owned loader service.
Forge-specific mod detection, environment paths, and version lookup now have a
single replacement point for future NeoForge support.

Initial users moved behind the service:

- optional compat module detection,
- early compatibility guards,
- Distant Horizons gating in client chunk retention.

This does not change rendering, LOD policy, shader profiles, player video
settings, or beta gameplay behavior.

## Porting Order

1. Compile the current Forge 1.20.1 branch after every foundation pass.
2. Move loader-specific APIs behind PauC-owned services.
3. Split the current `neoforge` module into explicit loader modules:
   - `forge-1.20.1`,
   - `neoforge-latest`.
4. Bring up NeoForge/latest with no custom LOD renderer.
5. Re-enable shader-off LOD.
6. Re-enable shader runtime paths pack by pack, starting with Photon and Solas.
7. Replace or rebuild the embedded `PaucUltimateLOD-3.0.3-b-1.20.1-forge.jar`
   dependency for the latest Minecraft target.

## Risk Register

- The current `neoforge` module name is inaccurate: it still uses ForgeGradle
  and `net.minecraftforge:forge`.
- The embedded LOD runtime is Forge 1.20.1-specific and is the largest technical
  blocker for latest Minecraft.
- Shader compatibility code has many mixins and direct Iris/DH integration
  points, so it must be reintroduced incrementally.
- Deprecated Forge APIs and old `ResourceLocation` constructors are already
  visible during compilation and will likely become hard failures on latest.

## Performance Direction

Keep the two product paths explicit:

- `competitive`: shader-off, high FPS, stable first-person readability.
- `cinematic`: shader-pack path, stable transitions, controlled GPU pressure.

Future optimization work should keep visual quality meaningful for gameplay and
avoid solving FPS by removing cover, trees, structures, clouds, or player video
settings control.

## Modular Fluidity Direction

PauC should not hard-code one FPS target for all players. The runtime should aim
for the highest stable fluidity possible by observing the current environment and
scaling PauC-owned modules only.

Current first pass:

- loader service exposes loaded mod count,
- fluidity state observes modpack weight, heap pressure, LOD queue pressure,
  measured FPS, and shader runtime transitions,
- FPS governor uses that state to scale PauC LOD budgets without changing
  Minecraft video settings.

This is the model to keep for Forge maintenance and future NeoForge ports.
