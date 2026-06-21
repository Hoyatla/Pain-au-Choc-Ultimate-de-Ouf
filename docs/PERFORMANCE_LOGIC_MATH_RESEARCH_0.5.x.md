# Performance Logic / Math Research 0.5.x

## Target

Keep PauC fluid in heavy scenes without forcing the player's settings:

- terrain must keep loading
- FPS drops from mobs / villages / particles / animated textures must be cut earlier
- close-range gameplay visibility must stay intact
- decisions must react before the 20-tick diagnostic window fully closes

## Core finding

Several PauC decisions were still based on completed windows only.
That means some actuators reacted after the expensive scene had already happened.

The useful mathematical fix is:

- observe the current open render window
- project it to a full 20-tick equivalent
- trigger non-essential budgets from the projected load, not only from the last closed sample

Projection used in this pass:

- `projectedWindow = max(currentWindow, round(currentWindow * SAMPLE_INTERVAL_TICKS / max(sampleTicks, minProjectionTicks)))`

This keeps the signal stable in the first ticks of a new window while still reacting much earlier than the old closed-window path.

## Implemented in this pass

### 1. Predictive heavy-scene pressure

`PauCVillagePerformanceDiagnostics` now exposes projected pressure signals:

- projected scene tier
- projected scene scale
- projected horde / animation tier
- projected block-entity budget
- projected rendered entity and block-entity windows

Result:

- heavy villages, mob bursts and ground-motion scenes are classified earlier
- downstream budgets can react before the frame pacing is already damaged

### 2. Runtime profile reacts to projected load

`PauCClientFluidityState` now classifies runtime weight from projected entity / block-entity windows and projected scene tier.

Result:

- pack/runtime classification is less late under sudden scene changes

### 3. Passive mob LOD under pressure

`PauCLodRenderCulling` now applies a stronger distance horizon to passive/ambient/water mobs under projected scene pressure.

Preserved on purpose:

- player
- camera entity
- vehicles / passengers
- named entities
- village-critical entities
- hostile gameplay visibility

Goal:

- reduce wasted cost from passive crowd rendering first
- avoid degrading combat readability

### 4. Earlier particle pressure

`PauCParticleBudget` and particle-distance reduction now use projected scene pressure instead of waiting for the old latched state.

Result:

- explosion / VFX floods are cut earlier
- terrain and core scene budgets keep more headroom

### 5. Conservative animation dephasing for far passive mobs

`PauCEntityRenderBudget` was reshaped:

- enabled by default
- only engages on projected heavy tiers or real spike-absorber pressure
- only applies to far passive/ambient/water mobs
- does not touch close gameplay-critical entities

Goal:

- cut animation/render churn where it is least visible

### 6. Conservative far block-entity dephasing

`PauCBlockEntityRenderBudget` now only wakes up in severe projected pressure and only in the far band.

Goal:

- keep decorative BE storms from stealing the frame when the scene is already overloaded

### 7. Animated texture cadence under pressure

Animated textures now pass through a synchronized PauC ticker budget.

Important design choice:

- cadence reduction is global per frame
- not per texture hash

Reason:

- base atlases and PBR atlases stay in sync
- avoids desynchronizing vanilla color animation from normal/specular animation

Goal:

- shave animated-texture upload/tick cost during shader-heavy scenes

## What this does not change yet

### Terrain upload / generation throughput

This pass protects terrain indirectly by cutting non-terrain cost earlier.
It does not yet rewrite the upload/generation controller math.

Next useful terrain pass:

- feed projected scene pressure into upload/mesh token ceilings
- keep a minimum protected terrain budget even in village/horde spikes
- reserve recovery bursts when terrain coverage is incomplete

### GPU / CUDA throughput

This pass does not alter CUDA routing thresholds.
The next GPU-focused pass should target:

- true async overlap
- small-batch threshold adaptation
- queue occupancy feedback into CPU/CUDA routing

## Expected user-visible effect

- earlier stabilization in villages and mob-rich travel
- fewer late FPS collapses after the scene is already dense
- cheaper passive-mob scenes
- lower animated-texture churn with shaders
- terrain loading should keep relatively more headroom because secondary costs are cut sooner

## Validation focus

Best manual checks after this pass:

- sprint on ground through biome changes with passive mobs nearby
- end-of-village entry with many block entities and villagers
- explosion / particle stress
- shader-on scene with many animated textures
- compare raw FPS stability and 1%-low behavior, not only average FPS
