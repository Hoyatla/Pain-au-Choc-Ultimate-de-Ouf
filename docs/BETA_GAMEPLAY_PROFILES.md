# PauC Beta Gameplay Profiles

The beta runtime defaults to `pauc.client.gameplayProfile=auto`.

## Auto

- No shader pack active: uses the `competitive` path.
- Shader pack active: uses the `cinematic` path.

## Competitive

Use for fast first-person gameplay without shader packs.

- Default LOD target: 56 chunks.
- Recommended vanilla render distance: 10 chunks.
- Does not change Minecraft video settings unless explicitly enabled.
- Lets the internal fluidity governor reduce PauC's own LOD target under pressure.
- Allows generation throttling when FPS pressure is detected.
- Uses a clipped vanilla-to-LOD boundary without shaders to keep moving terrain cleaner.
- Minimum LOD generation request rate: 144 requests/s.

Override:

```properties
pauc.client.gameplayProfile=competitive
```

## Cinematic

Use for shader-pack gameplay where frame pacing and visual stability matter more than maximum distance.

- Default LOD target: 48 chunks.
- Recommended vanilla render distance: 7 chunks.
- Does not change Minecraft video settings unless explicitly enabled.
- Allows dynamic LOD distance reduction under pressure.
- Allows dynamic generation throttling under pressure.
- Minimum LOD generation request rate: 80 requests/s.
- If the shader pack has no native DH terrain shader, PauC uses the late fallback LOD renderer.
- CUDA remains active for terrain preparation/cache, but the flat CUDA proxy terrain is not drawn by default.
- Photon and Solas use the native-synthetic DH terrain path when the shader pack has no explicit DH terrain program.
- Solas disables synthetic DH shadow fallback by default to avoid mismatched cloud-shadow artifacts and to reduce GPU pressure.
- Solas native-synthetic presentation is capped to `mid` by default for beta stability and FPS consistency.

Override:

```properties
pauc.client.gameplayProfile=cinematic
```

## Player Video Settings

Profiles only tune PauC/Distant Horizons defaults. They do not modify the player's Minecraft video settings by default.

Opt-in only:

```properties
pauc.lod.autoReduceVanillaDistance=true
```

## Fluidity Governor

PauC now treats fluidity as an internal budget problem, not as a fixed FPS lock.
The governor observes mod count, heap pressure, LOD queue pressure, shader
runtime transitions, and measured FPS. It can then scale PauC's own work:

- LOD target distance ceiling,
- generation request rate,
- warmup aggression,
- mesh memory pressure,
- retention margin,
- visible-fill floor during coverage recovery.

This does not edit Minecraft video settings or force a render-distance change.
It only changes PauC runtime budgets and can be disabled with:

```properties
pauc.fluidity.enabled=false
```

Debug-only shader proxy terrain:

```properties
pauc.lod.cuda.proxyTerrainShaderFallback=true
pauc.lod.shaderFallbackProxyPrepass=true
```

Solas beta overrides, if an older visual path is needed for diagnosis:

```properties
pauc.lod.shaderRuntime.solasSyntheticShadowFallback=true
pauc.lod.solasSyntheticHeadroomMaxQualityTier=far
```

## Beta Smoke Test

1. Start without shaders, sprint and rotate quickly in an open area.
2. Confirm logs mention `gameplayProfile[id=competitive`.
3. Enable a heavy shader pack and reload the world.
4. Confirm logs mention `gameplayProfile[id=cinematic`.
5. Watch `logs/latest.log` for LOD fallback, shader transform, or DH bridge errors.
6. Compare `minecraft/pauc_diagnostics/performance-*.json` after each session.
