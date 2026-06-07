# PauC Ultimate 0.1.0 Beta Release Notes

Date: 2026-06-07

## Binary

- Release artifact: `dist/Pain_au_Choc_Ultimate_de_Ouf-0.1.0.jar`
- Build artifact: `neoforge/build/libs/Pain_au_Choc_Ultimate_de_Ouf-0.1.0.jar`
- Test instance artifact:
  `C:\Users\charl\AppData\Roaming\PrismLauncher\instances\1.20.1 road beta\minecraft\mods\Pain_au_Choc_Ultimate_de_Ouf-0.1.0.jar`
- Expected SHA-256 after the final beta build:
  `C2EAA515C94B9974DD9EC5C106CF23582A8DD2964A0830BEF0FCE3AE50E117B4`

## Scope

- Forge-only Minecraft 1.20.1 beta.
- Java 17 build/runtime target.
- LGPL-3.0-or-later project license.
- Player video settings remain user-controlled; PauC profiles tune PauC/DH runtime behavior only.

## Rendering Status

- Shader-off path keeps local terrain/features readable by clipping visible LOD overlap around near trees and structures.
- Photon is validated on the native-synthetic DH terrain path with stable synthetic shadow fallback.
- Solas uses the same native-synthetic terrain path, but disables synthetic DH shadow fallback by default when no real `dh_shadow` program is present.
- Solas presentation is capped to `mid` by default to keep FPS stable and avoid high-quality oscillation during shader setting changes.
- CUDA remains a preparation/cache acceleration path, not a visual proxy path.

## Useful Runtime Overrides

Restore Solas synthetic DH shadow fallback for diagnosis:

```properties
pauc.lod.shaderRuntime.solasSyntheticShadowFallback=true
```

Allow higher Solas presentation quality during headroom:

```properties
pauc.lod.solasSyntheticHeadroomMaxQualityTier=far
```

Restore the more aggressive local feature transition defaults:

```properties
pauc.lod.featureTransitionExitTicks=24
pauc.lod.featureTransitionMaskHoldMs=1500
```

Restore the earlier shader fallback screen-fog sampling frequency:

```properties
pauc.lod.screenFogCaptureIntervalFrames=2
```

## Validation

- Build command: `.\gradlew.bat :neoforge:build`
- Last local validation: build succeeded with existing Mixin/deprecation warnings only.
- Smoke-test focus:
  - no-shader movement under trees and near structures,
  - Photon shadow transition stability,
  - Solas without giant cloud-shadow mismatch,
  - FPS stability after toggling shader settings.
