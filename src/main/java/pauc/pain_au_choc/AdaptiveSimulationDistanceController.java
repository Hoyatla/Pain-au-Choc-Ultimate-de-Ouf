package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.util.Locale;

public final class AdaptiveSimulationDistanceController {
    private static final int SAMPLE_INTERVAL_TICKS = 40;
    private static final int DOWN_ADJUSTMENT_COOLDOWN_TICKS = 320;
    private static final int UP_ADJUSTMENT_COOLDOWN_TICKS = 1200;
    private static final int REQUIRED_HIGH_TPS_SAMPLES = 14;
    private static final double LOW_TPS_THRESHOLD = 18.2D;
    private static final double HIGH_TPS_THRESHOLD = 19.8D;
    private static final int MIN_SIMULATION_DISTANCE = 5;
    private static final int MIN_SIMULATION_DISTANCE_EMERGENCY = 3;
    private static final int ABSOLUTE_MIN_SIMULATION_DISTANCE = MIN_SIMULATION_DISTANCE_EMERGENCY;
    private static final int MAX_SIMULATION_DISTANCE = 32;
    private static final float MAX_BACKPRESSURE_FOR_RECOVERY = 0.20F;

    private static int tickCounter;
    private static int cooldownTicks;
    private static int stableHighTpsSamples;
    private static int appliedSimulationDistance = -1;
    private static int lastBaseSimulationDistance = -1;
    private static int lastMinimumSimulationDistance = -1;
    private static double lastSampledTps = 20.0D;
    private static int adjustmentCount;

    private AdaptiveSimulationDistanceController() {
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PauCClient.isAdaptiveSimulationDistanceActive() || minecraft.options == null) {
            resetState();
            return;
        }

        int baseSimulationDistance = resolveBaseSimulationDistance();
        if (appliedSimulationDistance < 0 || baseSimulationDistance != lastBaseSimulationDistance) {
            appliedSimulationDistance = baseSimulationDistance;
            lastBaseSimulationDistance = baseSimulationDistance;
            lastMinimumSimulationDistance = Math.min(baseSimulationDistance, MIN_SIMULATION_DISTANCE);
            applySimulationDistance(minecraft, appliedSimulationDistance);
            stableHighTpsSamples = 0;
            cooldownTicks = 0;
        }

        IntegratedServer integratedServer = minecraft.getSingleplayerServer();
        if (integratedServer == null) {
            if (appliedSimulationDistance != baseSimulationDistance) {
                appliedSimulationDistance = baseSimulationDistance;
                applySimulationDistance(minecraft, appliedSimulationDistance);
            }
            stableHighTpsSamples = 0;
            cooldownTicks = 0;
            lastSampledTps = 20.0D;
            lastMinimumSimulationDistance = baseSimulationDistance;
            return;
        }

        tickCounter++;
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        if (tickCounter % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }

        float averageTickTimeMs = integratedServer.getAverageTickTime();
        if (averageTickTimeMs <= 0.0F) {
            return;
        }

        double tps = Math.min(20.0D, 1000.0D / averageTickTimeMs);
        lastSampledTps = tps;
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        int minimumSimulationDistance = Math.min(
                baseSimulationDistance,
                mitigationTier >= 3 ? MIN_SIMULATION_DISTANCE_EMERGENCY : MIN_SIMULATION_DISTANCE
        );
        lastMinimumSimulationDistance = minimumSimulationDistance;
        double lowTpsThreshold = LOW_TPS_THRESHOLD + (mitigationTier >= 2 ? 0.35D : 0.0D);
        double highTpsThreshold = HIGH_TPS_THRESHOLD + (mitigationTier >= 1 ? 0.15D : 0.0D);

        if (mitigationTier >= 3 && cooldownTicks == 0 && appliedSimulationDistance > minimumSimulationDistance) {
            applyAdaptiveDistance(minecraft, Math.max(minimumSimulationDistance, appliedSimulationDistance - 1));
            cooldownTicks = Math.max(120, DOWN_ADJUSTMENT_COOLDOWN_TICKS / 2);
            stableHighTpsSamples = 0;
            return;
        }

        if (tps < lowTpsThreshold) {
            stableHighTpsSamples = 0;
            if (cooldownTicks == 0 && appliedSimulationDistance > minimumSimulationDistance) {
                applyAdaptiveDistance(minecraft, appliedSimulationDistance - 1);
                cooldownTicks = DOWN_ADJUSTMENT_COOLDOWN_TICKS;
            }
            return;
        }

        if (tps > highTpsThreshold) {
            if (appliedSimulationDistance >= baseSimulationDistance) {
                stableHighTpsSamples = 0;
                return;
            }

            if (!isRecoveryAllowed()) {
                stableHighTpsSamples = 0;
                return;
            }

            stableHighTpsSamples++;
            if (cooldownTicks == 0 && stableHighTpsSamples >= REQUIRED_HIGH_TPS_SAMPLES) {
                applyAdaptiveDistance(minecraft, Math.min(baseSimulationDistance, appliedSimulationDistance + 1));
                cooldownTicks = UP_ADJUSTMENT_COOLDOWN_TICKS;
                stableHighTpsSamples = 0;
            }
            return;
        }

        stableHighTpsSamples = 0;
    }

    public static void reset() {
        resetState();
    }

    public static int getAppliedSimulationDistance() {
        return appliedSimulationDistance > 0 ? appliedSimulationDistance : resolveBaseSimulationDistance();
    }

    public static int getBaseSimulationDistance() {
        return resolveBaseSimulationDistance();
    }

    public static int getLastMinimumSimulationDistance() {
        if (lastMinimumSimulationDistance > 0) {
            return lastMinimumSimulationDistance;
        }
        return resolveBaseSimulationDistance();
    }

    public static int getCooldownTicks() {
        return cooldownTicks;
    }

    public static int getStableHighTpsSamples() {
        return stableHighTpsSamples;
    }

    public static double getLastSampledTps() {
        return lastSampledTps;
    }

    public static int getAdjustmentCount() {
        return adjustmentCount;
    }

    public static String getStatusLine() {
        return "simDist="
                + getAppliedSimulationDistance()
                + "/"
                + getBaseSimulationDistance()
                + " min="
                + getLastMinimumSimulationDistance()
                + " tps="
                + String.format(Locale.ROOT, "%.2f", getLastSampledTps())
                + " cooldown="
                + cooldownTicks
                + " adjustments="
                + adjustmentCount;
    }

    private static void applySimulationDistance(Minecraft minecraft, int simulationDistance) {
        int clampedTarget = clampSimulationDistance(simulationDistance);
        if (minecraft.options.simulationDistance().get() == clampedTarget) {
            return;
        }

        minecraft.options.simulationDistance().set(clampedTarget);
    }

    private static void applyAdaptiveDistance(Minecraft minecraft, int newDistance) {
        int clamped = clampSimulationDistance(newDistance);
        if (clamped == appliedSimulationDistance) {
            return;
        }
        appliedSimulationDistance = clamped;
        applySimulationDistance(minecraft, appliedSimulationDistance);
        adjustmentCount++;
    }

    private static int resolveBaseSimulationDistance() {
        int qualityLevel = resolveTerrainQualityLevel();
        int profileDistance = clampSimulationDistance(QualityBudgetProfile.forLevel(qualityLevel).simulationDistance());
        return Math.max(MIN_SIMULATION_DISTANCE, profileDistance);
    }

    private static int clampSimulationDistance(int value) {
        return Math.max(ABSOLUTE_MIN_SIMULATION_DISTANCE, Math.min(MAX_SIMULATION_DISTANCE, value));
    }

    private static int resolveTerrainQualityLevel() {
        int level = PauCClient.isAdaptiveQualityActive()
                ? PauCClient.getAdaptiveQualityTargetLevel()
                : PauCClient.getQualityLevel();
        return Math.max(PauCClient.getMinQualityLevel(), Math.min(PauCClient.getMaxQualityLevel(), level));
    }

    private static boolean isRecoveryAllowed() {
        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            return false;
        }
        if (IntegratedServerLoadController.getPressureLevel() > 0) {
            return false;
        }
        if (IntegratedServerLoadController.getMitigationTier() > 0 || IntegratedServerLoadController.isEmergencyMitigationActive()) {
            return false;
        }
        if (LatencyController.getPressureLevel() > 0) {
            return false;
        }
        if (GlobalPerformanceGovernor.getGlobalPressure() > 1) {
            return false;
        }
        if (ChunkBuildQueueController.getBackPressureRatio() > MAX_BACKPRESSURE_FOR_RECOVERY) {
            return false;
        }
        return !BottleneckController.isCpuBound();
    }

    private static void resetState() {
        tickCounter = 0;
        cooldownTicks = 0;
        stableHighTpsSamples = 0;
        appliedSimulationDistance = -1;
        lastBaseSimulationDistance = -1;
        lastMinimumSimulationDistance = -1;
        lastSampledTps = 20.0D;
        adjustmentCount = 0;
    }
}

