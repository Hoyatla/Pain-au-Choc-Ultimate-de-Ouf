package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

public final class AdaptiveSimulationDistanceController {
    private static final int SAMPLE_INTERVAL_TICKS = 40;
    private static final int ADJUSTMENT_COOLDOWN_TICKS = 120;
    private static final int REQUIRED_HIGH_TPS_SAMPLES = 3;
    private static final double LOW_TPS_THRESHOLD = 18.0D;
    private static final double HIGH_TPS_THRESHOLD = 19.5D;
    private static final int MIN_SIMULATION_DISTANCE = 5;

    private static int tickCounter;
    private static int cooldownTicks;
    private static int stableHighTpsSamples;
    private static int appliedSimulationDistance = -1;
    private static int lastBaseSimulationDistance = -1;

    private AdaptiveSimulationDistanceController() {
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PauCClient.isAdaptiveSimulationDistanceActive() || minecraft.options == null) {
            resetState();
            return;
        }

        int baseSimulationDistance = Math.max(2, PauCClient.getActiveProfile().simulationDistance());
        if (appliedSimulationDistance < 0 || baseSimulationDistance != lastBaseSimulationDistance) {
            appliedSimulationDistance = baseSimulationDistance;
            lastBaseSimulationDistance = baseSimulationDistance;
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
        int minimumSimulationDistance = Math.min(baseSimulationDistance, MIN_SIMULATION_DISTANCE);

        if (tps < LOW_TPS_THRESHOLD) {
            stableHighTpsSamples = 0;
            if (cooldownTicks == 0 && appliedSimulationDistance > minimumSimulationDistance) {
                appliedSimulationDistance--;
                applySimulationDistance(minecraft, appliedSimulationDistance);
                cooldownTicks = ADJUSTMENT_COOLDOWN_TICKS;
            }
            return;
        }

        if (tps > HIGH_TPS_THRESHOLD) {
            if (appliedSimulationDistance >= baseSimulationDistance) {
                stableHighTpsSamples = 0;
                return;
            }

            stableHighTpsSamples++;
            if (cooldownTicks == 0 && stableHighTpsSamples >= REQUIRED_HIGH_TPS_SAMPLES) {
                appliedSimulationDistance = Math.min(baseSimulationDistance, appliedSimulationDistance + 1);
                applySimulationDistance(minecraft, appliedSimulationDistance);
                cooldownTicks = ADJUSTMENT_COOLDOWN_TICKS;
                stableHighTpsSamples = 0;
            }
            return;
        }

        stableHighTpsSamples = 0;
    }

    public static void reset() {
        resetState();
    }

    private static void applySimulationDistance(Minecraft minecraft, int simulationDistance) {
        if (minecraft.options.simulationDistance().get() == simulationDistance) {
            return;
        }

        minecraft.options.simulationDistance().set(simulationDistance);
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
    }

    private static void resetState() {
        tickCounter = 0;
        cooldownTicks = 0;
        stableHighTpsSamples = 0;
        appliedSimulationDistance = -1;
        lastBaseSimulationDistance = -1;
    }
}

