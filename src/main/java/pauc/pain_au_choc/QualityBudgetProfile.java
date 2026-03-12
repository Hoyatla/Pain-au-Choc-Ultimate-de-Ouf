package pauc.pain_au_choc;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.ParticleStatus;

public record QualityBudgetProfile(
        int qualityLevel,
        GraphicsStatus graphicsStatus,
        CloudStatus cloudStatus,
        ParticleStatus particleStatus,
        boolean smoothLighting,
        int simulationDistance,
        double entityDistanceScaling,
        int mipmapLevels,
        boolean entityShadows,
        double fullDetailRatio,
        double reducedDetailRatio,
        double aggressiveDetailRatio,
        double softUnloadRatio,
        double entityDistanceRatio,
        double blockEntityDistanceRatio,
        double shadowDistanceRatio,
        double coneMarginDegrees,
        double chunkRebuildBudgetRatio,
        float targetGpuLoad,
        float peakGpuLoad
) {
    private static final int MIN_QUALITY = 1;
    private static final int MAX_QUALITY = 10;
    private static final QualityBudgetProfile[] PROFILES = buildProfiles();

    public static QualityBudgetProfile forLevel(int qualityLevel) {
        int clampedLevel = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, qualityLevel));
        return PROFILES[clampedLevel - MIN_QUALITY];
    }

    private static QualityBudgetProfile[] buildProfiles() {
        return new QualityBudgetProfile[]{
                createProfile(1, GraphicsStatus.FAST, CloudStatus.OFF, ParticleStatus.MINIMAL, false, 8, 0.75D, 0, false, 4.0D, 0.15D, 0.82F, 0.95F),
                createProfile(2, GraphicsStatus.FAST, CloudStatus.OFF, ParticleStatus.MINIMAL, false, 8, 0.80D, 0, false, 6.0D, 0.20D, 0.83F, 0.95F),
                createProfile(3, GraphicsStatus.FAST, CloudStatus.OFF, ParticleStatus.MINIMAL, false, 9, 0.85D, 1, false, 8.0D, 0.28D, 0.84F, 0.95F),
                createProfile(4, GraphicsStatus.FAST, CloudStatus.FAST, ParticleStatus.MINIMAL, true, 10, 0.90D, 1, false, 10.0D, 0.36D, 0.85F, 0.95F),
                createProfile(5, GraphicsStatus.FAST, CloudStatus.FAST, ParticleStatus.MINIMAL, true, 10, 0.95D, 2, true, 12.0D, 0.45D, 0.86F, 0.95F),
                createProfile(6, GraphicsStatus.FANCY, CloudStatus.FAST, ParticleStatus.DECREASED, true, 11, 1.00D, 2, true, 14.0D, 0.55D, 0.87F, 0.95F),
                createProfile(7, GraphicsStatus.FANCY, CloudStatus.FANCY, ParticleStatus.DECREASED, true, 11, 1.00D, 3, true, 16.0D, 0.65D, 0.88F, 0.95F),
                createProfile(8, GraphicsStatus.FABULOUS, CloudStatus.FANCY, ParticleStatus.DECREASED, true, 12, 1.00D, 4, true, 18.0D, 0.78D, 0.89F, 0.95F),
                createProfile(9, GraphicsStatus.FABULOUS, CloudStatus.FANCY, ParticleStatus.ALL, true, 12, 1.00D, 4, true, 20.0D, 0.90D, 0.90F, 0.95F),
                createProfile(10, GraphicsStatus.FABULOUS, CloudStatus.FANCY, ParticleStatus.ALL, true, 12, 1.00D, 4, true, 24.0D, 1.00D, 0.90F, 0.95F)
        };
    }

    private static QualityBudgetProfile createProfile(
            int level,
            GraphicsStatus graphicsStatus,
            CloudStatus cloudStatus,
            ParticleStatus particleStatus,
            boolean smoothLighting,
            int simulationDistance,
            double entityDistanceScaling,
            int mipmapLevels,
            boolean entityShadows,
            double coneMarginDegrees,
            double chunkRebuildBudgetRatio,
            float targetGpuLoad,
            float peakGpuLoad
    ) {
        double fullDetailRatio = computeFullDetailRatio(level);
        double reducedDetailRatio = clampRatio(fullDetailRatio + (1.0D - fullDetailRatio) * interpolate(level, 0.18D, 0.72D));
        double aggressiveDetailRatio = clampRatio(fullDetailRatio + (1.0D - fullDetailRatio) * interpolate(level, 0.35D, 0.88D));
        double softUnloadRatio = clampRatio(fullDetailRatio + (1.0D - fullDetailRatio) * interpolate(level, 0.20D, 0.95D));
        double entityDistanceRatio = clampRatio(interpolate(level, 0.25D, 1.00D));
        double blockEntityDistanceRatio = clampRatio(interpolate(level, 0.18D, 1.00D));
        double shadowDistanceRatio = entityShadows ? clampRatio(interpolate(level, 0.15D, 0.75D)) : 0.0D;

        return new QualityBudgetProfile(
                level,
                graphicsStatus,
                cloudStatus,
                particleStatus,
                smoothLighting,
                simulationDistance,
                entityDistanceScaling,
                mipmapLevels,
                entityShadows,
                fullDetailRatio,
                reducedDetailRatio,
                aggressiveDetailRatio,
                softUnloadRatio,
                entityDistanceRatio,
                blockEntityDistanceRatio,
                shadowDistanceRatio,
                coneMarginDegrees,
                chunkRebuildBudgetRatio,
                targetGpuLoad,
                peakGpuLoad
        );
    }

    private static double computeFullDetailRatio(int level) {
        if (level >= 8) {
            return 1.0D;
        }

        double progress = (level - 1) / 7.0D;
        return clampRatio(0.005D * Math.pow(200.0D, progress));
    }

    private static double interpolate(int level, double min, double max) {
        double progress = (level - 1) / 9.0D;
        return min + (max - min) * progress;
    }

    private static double clampRatio(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public boolean isVanillaEquivalent() {
        return this.qualityLevel >= PauCClient.getMaxQualityLevel();
    }
}

