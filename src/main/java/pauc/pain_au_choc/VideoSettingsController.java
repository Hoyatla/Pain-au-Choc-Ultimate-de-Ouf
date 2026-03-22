package pauc.pain_au_choc;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;

import java.util.Objects;

public final class VideoSettingsController {
    private static GraphicsStatus baselineGraphics;
    private static CloudStatus baselineClouds;
    private static ParticleStatus baselineParticles;
    private static Object baselineSmoothLighting;
    private static Integer baselineRenderDistance;
    private static Integer baselineSimulationDistance;
    private static Object baselineEntityDistanceScaling;
    private static Integer baselineMipmapLevels;
    private static Boolean baselineEntityShadows;
    private static boolean managingSettings;

    private VideoSettingsController() {
    }

    public static void syncWithBudget(boolean enabled, int qualityLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) {
            return;
        }

        if (!enabled) {
            restoreBaseline(minecraft);
            return;
        }

        if (!managingSettings) {
            captureBaseline(minecraft);
        }

        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            return;
        }

        QualityBudgetProfile profile = QualityBudgetProfile.forLevel(qualityLevel);
        int stressTier = resolveRuntimeStressTier();

        GraphicsStatus graphicsTarget = resolveGraphicsTarget(profile.graphicsStatus(), stressTier);
        CloudStatus cloudsTarget = resolveCloudTarget(profile.cloudStatus(), stressTier);
        ParticleStatus particlesTarget = resolveParticlesTarget(profile.particleStatus(), stressTier);
        boolean smoothLightingTarget = resolveSmoothLightingTarget(profile.smoothLighting(), stressTier);
        double entityDistanceScalingTarget = resolveEntityDistanceScalingTarget(profile.entityDistanceScaling(), stressTier);
        int mipmapLevelsTarget = resolveMipmapLevelsTarget(profile.mipmapLevels(), stressTier);
        boolean entityShadowsTarget = resolveEntityShadowsTarget(profile.entityShadows(), stressTier);

        boolean changed = false;

        changed |= applyGraphics(minecraft, graphicsTarget);
        changed |= applyClouds(minecraft, cloudsTarget);
        changed |= applyParticles(minecraft, particlesTarget);
        changed |= applySmoothLighting(minecraft, smoothLightingTarget);
        // Avoid runtime chunk reload loops: render/simulation distance are not
        // auto-mutated here anymore. Simulation distance remains governed by
        // AdaptiveSimulationDistanceController and render distance stays user-driven.
        changed |= applyEntityDistanceScaling(minecraft, entityDistanceScalingTarget);
        changed |= applyMipmapLevels(minecraft, mipmapLevelsTarget);
        changed |= applyEntityShadows(minecraft, entityShadowsTarget);

        if (changed && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }

        managingSettings = true;
    }

    private static void captureBaseline(Minecraft minecraft) {
        baselineGraphics = minecraft.options.graphicsMode().get();
        baselineClouds = minecraft.options.cloudStatus().get();
        baselineParticles = minecraft.options.particles().get();
        baselineSmoothLighting = minecraft.options.ambientOcclusion().get();
        baselineRenderDistance = minecraft.options.renderDistance().get();
        baselineSimulationDistance = minecraft.options.simulationDistance().get();
        baselineEntityDistanceScaling = minecraft.options.entityDistanceScaling().get();
        baselineMipmapLevels = minecraft.options.mipmapLevels().get();
        baselineEntityShadows = minecraft.options.entityShadows().get();
        managingSettings = true;
    }

    private static void restoreBaseline(Minecraft minecraft) {
        if (!managingSettings) {
            return;
        }

        boolean changed = false;
        if (baselineGraphics != null) {
            changed |= applyGraphics(minecraft, baselineGraphics);
        }
        if (baselineClouds != null) {
            changed |= applyClouds(minecraft, baselineClouds);
        }
        if (baselineParticles != null) {
            changed |= applyParticles(minecraft, baselineParticles);
        }
        if (baselineSmoothLighting != null) {
            changed |= applyOptionRaw(minecraft.options.ambientOcclusion(), baselineSmoothLighting);
        }
        if (baselineRenderDistance != null) {
            changed |= applyRenderDistance(minecraft, baselineRenderDistance);
        }
        if (baselineSimulationDistance != null) {
            changed |= applySimulationDistance(minecraft, baselineSimulationDistance);
        }
        if (baselineEntityDistanceScaling != null) {
            changed |= applyOptionRaw(minecraft.options.entityDistanceScaling(), baselineEntityDistanceScaling);
        }
        if (baselineMipmapLevels != null) {
            changed |= applyMipmapLevels(minecraft, baselineMipmapLevels);
        }
        if (baselineEntityShadows != null) {
            changed |= applyEntityShadows(minecraft, baselineEntityShadows);
        }

        if (changed && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }

        baselineGraphics = null;
        baselineClouds = null;
        baselineParticles = null;
        baselineSmoothLighting = null;
        baselineRenderDistance = null;
        baselineSimulationDistance = null;
        baselineEntityDistanceScaling = null;
        baselineMipmapLevels = null;
        baselineEntityShadows = null;
        managingSettings = false;
    }

    private static boolean applyGraphics(Minecraft minecraft, GraphicsStatus target) {
        if (minecraft.options.graphicsMode().get() == target) {
            return false;
        }
        minecraft.options.graphicsMode().set(target);
        return true;
    }

    private static boolean applyClouds(Minecraft minecraft, CloudStatus target) {
        if (minecraft.options.cloudStatus().get() == target) {
            return false;
        }
        minecraft.options.cloudStatus().set(target);
        return true;
    }

    private static boolean applyParticles(Minecraft minecraft, ParticleStatus target) {
        if (minecraft.options.particles().get() == target) {
            return false;
        }
        minecraft.options.particles().set(target);
        return true;
    }

    private static boolean applySmoothLighting(Minecraft minecraft, boolean target) {
        Object current = minecraft.options.ambientOcclusion().get();
        Object resolvedTarget = resolveSmoothLightingValue(current, target);
        return applyOptionRaw(minecraft.options.ambientOcclusion(), resolvedTarget);
    }

    private static boolean applyRenderDistance(Minecraft minecraft, int target) {
        int clampedTarget = Math.max(2, Math.min(32, target));
        if (minecraft.options.renderDistance().get() == clampedTarget) {
            return false;
        }
        minecraft.options.renderDistance().set(clampedTarget);
        return true;
    }

    private static boolean applySimulationDistance(Minecraft minecraft, int target) {
        int clampedTarget = Math.max(5, Math.min(32, target));
        if (minecraft.options.simulationDistance().get() == clampedTarget) {
            return false;
        }
        minecraft.options.simulationDistance().set(clampedTarget);
        return true;
    }

    private static int resolveTargetRenderDistance(int qualityLevel, int stressTier) {
        int qualityTarget = switch (Math.max(1, Math.min(10, qualityLevel))) {
            case 1, 2 -> 7;
            case 3 -> 9;
            case 4 -> 11;
            case 5 -> 13;
            case 6 -> 15;
            case 7 -> 16;
            case 8 -> 18;
            case 9 -> 20;
            case 10 -> 22;
            default -> 16;
        };

        int pressurePenalty = LatencyController.getPressureLevel() * 3
                + IntegratedServerLoadController.getPressureLevel() * 3
                + GlobalPerformanceGovernor.getGlobalPressure() * 2;
        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        if (mode == GlobalPerformanceMode.CRISIS) {
            pressurePenalty += 6;
        } else if (mode == GlobalPerformanceMode.COMBAT) {
            pressurePenalty += 2;
        } else if (mode == GlobalPerformanceMode.TRANSIT) {
            pressurePenalty += 1;
        }

        int resolved = qualityTarget - pressurePenalty;
        if (baselineRenderDistance != null) {
            resolved = Math.min(baselineRenderDistance, resolved);
        }

        int minRenderDistance = stressTier >= 3 ? 4 : 6;
        return Math.max(minRenderDistance, Math.min(32, resolved));
    }

    private static int resolveRuntimeStressTier() {
        int maxPressure = Math.max(
                GlobalPerformanceGovernor.getGlobalPressure(),
                Math.max(LatencyController.getPressureLevel(), IntegratedServerLoadController.getPressureLevel())
        );
        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        if (mode == GlobalPerformanceMode.CRISIS || maxPressure >= 3) {
            return 3;
        }
        if (mode == GlobalPerformanceMode.COMBAT || maxPressure >= 2) {
            return 2;
        }
        if (mode == GlobalPerformanceMode.BASE || mode == GlobalPerformanceMode.TRANSIT || maxPressure >= 1) {
            return 1;
        }
        return 0;
    }

    private static GraphicsStatus resolveGraphicsTarget(GraphicsStatus base, int stressTier) {
        if (stressTier >= 3) {
            return GraphicsStatus.FAST;
        }
        if (stressTier == 2 && base == GraphicsStatus.FABULOUS) {
            return GraphicsStatus.FANCY;
        }
        return base;
    }

    private static CloudStatus resolveCloudTarget(CloudStatus base, int stressTier) {
        if (stressTier >= 3) {
            return CloudStatus.OFF;
        }
        if (stressTier == 2 && base == CloudStatus.FANCY) {
            return CloudStatus.FAST;
        }
        return base;
    }

    private static ParticleStatus resolveParticlesTarget(ParticleStatus base, int stressTier) {
        if (stressTier >= 3) {
            return ParticleStatus.MINIMAL;
        }
        if (stressTier == 2 && base == ParticleStatus.ALL) {
            return ParticleStatus.DECREASED;
        }
        return base;
    }

    private static boolean resolveSmoothLightingTarget(boolean base, int stressTier) {
        if (stressTier >= 3) {
            return false;
        }
        return base;
    }

    private static int resolveSimulationDistanceTarget(int base, int stressTier) {
        if (stressTier >= 3) {
            return Math.min(base, 7);
        }
        if (stressTier == 2) {
            return Math.min(base, 9);
        }
        return base;
    }

    private static double resolveEntityDistanceScalingTarget(double base, int stressTier) {
        if (stressTier >= 3) {
            return Math.min(base, 0.80D);
        }
        if (stressTier == 2) {
            return Math.min(base, 0.90D);
        }
        return base;
    }

    private static int resolveMipmapLevelsTarget(int base, int stressTier) {
        if (stressTier >= 3) {
            return Math.min(base, 2);
        }
        if (stressTier == 2) {
            return Math.min(base, 3);
        }
        return base;
    }

    private static boolean resolveEntityShadowsTarget(boolean base, int stressTier) {
        if (stressTier >= 2) {
            return false;
        }
        return base;
    }

    private static boolean applyEntityDistanceScaling(Minecraft minecraft, double target) {
        Object current = minecraft.options.entityDistanceScaling().get();
        Object resolvedTarget = coerceNumericValue(current, target);
        return applyOptionRaw(minecraft.options.entityDistanceScaling(), resolvedTarget);
    }

    private static boolean applyMipmapLevels(Minecraft minecraft, int target) {
        Integer current = minecraft.options.mipmapLevels().get();
        if (current != null && current == target) {
            return false;
        }
        minecraft.options.mipmapLevels().set(target);
        minecraft.updateMaxMipLevel(target);
        return true;
    }

    private static boolean applyEntityShadows(Minecraft minecraft, boolean target) {
        if (minecraft.options.entityShadows().get() == target) {
            return false;
        }
        minecraft.options.entityShadows().set(target);
        return true;
    }

    private static Object resolveSmoothLightingValue(Object current, boolean enabled) {
        if (current instanceof Boolean) {
            return enabled;
        }

        if (current instanceof Enum<?> currentEnum) {
            Object[] values = currentEnum.getDeclaringClass().getEnumConstants();
            if (values == null || values.length == 0) {
                return current;
            }
            return enabled ? values[values.length - 1] : values[0];
        }

        return current;
    }

    private static Object coerceNumericValue(Object current, double target) {
        if (current instanceof Integer) {
            return (int) Math.round(target);
        }
        if (current instanceof Float) {
            return (float) target;
        }
        if (current instanceof Double) {
            return target;
        }
        if (current instanceof Long) {
            return (long) Math.round(target);
        }
        return target;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean applyOptionRaw(net.minecraft.client.OptionInstance option, Object target) {
        Object current = option.get();
        if (Objects.equals(current, target)) {
            return false;
        }
        option.set(target);
        return true;
    }
}

