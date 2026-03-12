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

        QualityBudgetProfile profile = QualityBudgetProfile.forLevel(qualityLevel);
        boolean changed = false;

        changed |= applyGraphics(minecraft, profile.graphicsStatus());
        changed |= applyClouds(minecraft, profile.cloudStatus());
        changed |= applyParticles(minecraft, profile.particleStatus());
        changed |= applySmoothLighting(minecraft, profile.smoothLighting());
        changed |= applySimulationDistance(minecraft, profile.simulationDistance());
        changed |= applyEntityDistanceScaling(minecraft, profile.entityDistanceScaling());
        changed |= applyMipmapLevels(minecraft, profile.mipmapLevels());
        changed |= applyEntityShadows(minecraft, profile.entityShadows());

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

    private static boolean applySimulationDistance(Minecraft minecraft, int target) {
        if (minecraft.options.simulationDistance().get() == target) {
            return false;
        }
        minecraft.options.simulationDistance().set(target);
        return true;
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

