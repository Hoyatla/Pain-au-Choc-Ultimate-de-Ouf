package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;

public final class ShadowDistanceGovernor {
    private static final int UPDATE_INTERVAL_TICKS = 4;
    private static final int FAST_RECOVERY_STABLE_TICKS = 30;
    private static final double MIN_DISTANCE_SCALE = 0.22D;
    private static final double BASE_REDUCE_STEP = 0.05D;
    private static final double HARD_REDUCE_STEP = 0.09D;
    private static final double BASE_RECOVER_STEP = 0.02D;
    private static final double FAST_RECOVER_STEP = 0.035D;

    private static double shadowDistanceScale = 1.0D;
    private static int tickCounter;
    private static int stableTicks;
    private static int trackedLevelIdentity;

    private ShadowDistanceGovernor() {
    }

    public static void onClientTick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            reset();
            return;
        }

        int levelIdentity = System.identityHashCode(minecraft.level);
        if (trackedLevelIdentity != levelIdentity) {
            reset();
            trackedLevelIdentity = levelIdentity;
        }

        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        boolean gpuBound = BottleneckController.isGpuBound();
        int pressure = LatencyController.getPressureLevel();
        double currentDynamicScale = DynamicResolutionController.getCurrentScale();
        if (gpuBound) {
            stableTicks = 0;
            double reduceStep = BASE_REDUCE_STEP;
            if (pressure >= 2 || currentDynamicScale <= 0.86D) {
                reduceStep = HARD_REDUCE_STEP;
            } else if (pressure >= 1 || currentDynamicScale <= 0.92D) {
                reduceStep = 0.07D;
            }

            shadowDistanceScale = clampScale(shadowDistanceScale - reduceStep);
            return;
        }

        stableTicks++;
        double recoverStep = stableTicks >= FAST_RECOVERY_STABLE_TICKS && pressure == 0
                ? FAST_RECOVER_STEP
                : BASE_RECOVER_STEP;
        shadowDistanceScale = clampScale(shadowDistanceScale + recoverStep);
    }

    public static double getShadowDistanceScale() {
        if (!PauCClient.isBudgetActive()) {
            return 1.0D;
        }
        return shadowDistanceScale;
    }

    public static void reset() {
        shadowDistanceScale = 1.0D;
        tickCounter = 0;
        stableTicks = 0;
        trackedLevelIdentity = 0;
    }

    private static double clampScale(double value) {
        return Math.max(MIN_DISTANCE_SCALE, Math.min(1.0D, value));
    }
}
