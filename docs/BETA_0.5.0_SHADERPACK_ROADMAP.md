# PauC Ultimate 0.5.0 Shaderpack Roadmap

Date: 2026-06-18

Note: the standalone `PauCShaderpack` source/archive was removed from the active
tree. This document remains as historical architecture intent only.

## Intent

Version `0.5.0` is the shader-focused cycle.

The project will start with `PauCShaderpack` first, but without letting the
shaderpack silently reshape the whole runtime. The shaderpack must define the
visual contract. The runtime must then follow that contract in a controlled
way.

This is the opposite of ad-hoc shader compatibility patches. The pack becomes
the reference surface, while PauC runtime changes remain isolated, measurable,
and reversible.

## Core Rule

`PauCShaderpack` comes first.

But:

- the shaderpack must not force Minecraft video settings,
- the shaderpack must not become a hidden workaround layer for broken runtime
  logic,
- the runtime must not regress `shader-off`, `Photon`, or `Solas` just to make
  `PauCShaderpack` look correct,
- every shader-facing runtime rule must stay behind an explicit PauC-owned
  compatibility layer.

## Why Start With PauCShaderpack

Starting with the shaderpack first is valid if the pack is treated as a stable
target, not as a moving experiment.

The pack should answer these questions before broad runtime work begins:

- what fog behavior is expected at the vanilla-to-LOD transition,
- what terrain shadow behavior is expected in the vanilla zone,
- what can be deferred to LOD terrain and what must stay vanilla-only,
- what should happen when the player changes render distance live,
- what is acceptable in heavy modpacks versus visual showcase sessions,
- what data the runtime must expose once per frame to keep the shader state
  coherent.

If this contract is not written first, runtime work turns into guesswork and
regressions accumulate.

## 0.5.0 Scope

The target of `0.5.0` is not "all shader features".

The target is:

- a defined `PauCShaderpack` baseline,
- a PauC-owned shader runtime bridge,
- stable fog, terrain visibility, and shadow state handoff,
- no forced FPS cap,
- no forced player video-setting override,
- no regression of pack-off gameplay.

## Deliverables

`0.5.0` should ship only when the following are true:

1. `PauCShaderpack` loads as the primary PauC reference pack.
2. `shader-off` remains functional and performant.
3. `Photon` and `Solas` remain supported as compatibility profiles.
4. Changing vanilla render distance live updates the effective terrain/fog
   behavior without requiring a restart.
5. Terrain shadows no longer depend on camera pitch side effects.
6. The vanilla zone, transition zone, and LOD zone each have explicit rules.
7. PauC diagnostics can explain which shader profile/path is active.

## Architecture Direction

The `0.5.0` shader work should be split into four layers.

### 1. PauCShaderpack

This is the source of truth for intended visual output.

Responsibilities:

- define the expected fog shape,
- define the expected shadow intent,
- define the intended transition behavior,
- define feature tiers for light, medium, and heavy workloads,
- remain usable as a standalone visual profile instead of a debugging artifact.

Constraints:

- keep it modular,
- keep its defaults conservative,
- keep pack options explicit,
- do not bury runtime assumptions into unexplained shader macros.

### 2. PauC Shader Runtime Bridge

This layer should be the only runtime entry point that answers:

- is a shader pack active,
- which profile is active,
- what fog model is required,
- what shadow mode is required,
- what terrain transition mode is required,
- what sync values must be published this frame.

This bridge must be a single source of truth. No scattered reads from
independent systems.

### 3. Compatibility Profiles

Profiles should stay explicit:

- `shader-off`
- `Photon`
- `Solas`
- `PauCShaderpack`

Each profile should define:

- fog behavior,
- shadow expectations,
- vanilla-to-LOD overlap rules,
- fallback rules,
- allowed performance degradations,
- debug telemetry labels.

### 4. Diagnostics And Guardrails

The shader path must remain diagnosable in live sessions.

Required diagnostics:

- active shader profile,
- active terrain path,
- active shadow path,
- active fog sync state,
- last runtime fallback cause,
- current performance tier,
- whether PauC is CPU-bound, GPU-bound, queue-bound, or external-bound.

## Development Order

Because this cycle starts with `PauCShaderpack`, the order should be:

1. Define `PauCShaderpack` visual contract.
2. Build the minimal pack with conservative defaults.
3. Freeze the first pack feature set.
4. Add the PauC shader runtime bridge around that fixed target.
5. Add compatibility profiles for `shader-off`, `Photon`, and `Solas`.
6. Only then adjust fog, shadows, transition coverage, and LOD-specific sync.
7. Only after the above is stable, add richer visual features.

This prevents runtime logic from being constantly rewritten around an unstable
pack.

## What Must Not Happen

The following patterns are explicitly rejected for `0.5.0`:

- forcing player render settings,
- forcing an FPS lock in the name of "stability",
- hard-coding behavior from one shader pack into global runtime logic,
- using camera-angle side effects as part of shadow correctness,
- mixing shaderpack feature work with emergency LOD bug fixes in the same pass,
- adding invisible heuristics that cannot be diagnosed in logs or F3.

## Recommended Work Tranches

### Tranche A: PauCShaderpack Baseline

Goal:

- get a clean, minimal PauC-owned shaderpack baseline,
- establish fog/shadow/terrain intent,
- keep the pack visually coherent without advanced runtime coupling.

Exit criteria:

- the pack loads,
- the pack is readable in gameplay,
- the pack can be tested without patching core runtime behavior.

### Tranche B: Runtime Bridge

Goal:

- create one stable shader-state publication path,
- eliminate scattered per-system interpretation,
- expose stable per-frame state to the render path.

Exit criteria:

- active profile is explicit,
- fog/shadow/transition state is coherent,
- fallback reasons are logged.

### Tranche C: Transition Stability

Goal:

- stabilize the vanilla zone,
- stabilize the LOD zone,
- make the junction predictable even if it stays visually simple.

Exit criteria:

- no disappearing terrain caused by transient policy flips,
- no shader-only mismatch when render distance changes live,
- no camera-pitch-driven terrain shadow loss.

### Tranche D: Performance Hardening

Goal:

- keep the shader path viable on real gameplay loads,
- preserve FPS under movement, mobs, villages, and long sessions.

Exit criteria:

- no hidden 60 FPS cap,
- no runaway GPU path during shader use,
- no uncontrolled queue buildup blamed on shader state,
- stable frame behavior in light and heavy sessions.

## Test Matrix

Every shader tranche should be checked against:

- `shader-off`, open terrain,
- `shader-off`, near forests,
- `shader-off`, village,
- `PauCShaderpack`, open terrain,
- `PauCShaderpack`, fast ground movement,
- `PauCShaderpack`, village,
- `Photon`, open terrain,
- `Photon`, village,
- `Solas`, open terrain,
- `Solas`, village,
- live render-distance changes,
- long-session stability,
- dimension change,
- sunrise/sunset,
- rain/fog variation,
- heavy mob activity.

## Release Rule

`0.5.0` should be considered successful only if shader infrastructure becomes
more predictable than before.

The release is not validated by "more effects". It is validated by:

- less ambiguity,
- fewer silent fallbacks,
- stable terrain visibility,
- stable shadow behavior,
- preserved player control,
- controlled performance cost.
