package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

public final class AdaptiveQualityController {
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int DOWN_ADJUSTMENT_COOLDOWN_TICKS = 200;
    private static final int UP_ADJUSTMENT_COOLDOWN_TICKS = 360;
    private static final int REQUIRED_HIGH_PRESSURE_SAMPLES = 2;
    private static final int REQUIRED_STABLE_RECOVERY_SAMPLES = 10;
    private static final int HIGH_PRESSURE_SCORE = 8;
    private static final int CRITICAL_PRESSURE_SCORE = 12;
    private static final int RECOVERY_PRESSURE_SCORE_MAX = 3;
    private static final int DEFAULT_TARGET_FPS = 120;
    private static final int UNLIMITED_FRAMERATE = 260;
    private static final float HIGH_FRAME_RATIO = 1.25F;
    private static final float CRITICAL_FRAME_RATIO = 1.45F;
    private static final float JITTER_HIGH_THRESHOLD_MS = 3.0F;
    private static final float JITTER_CRITICAL_THRESHOLD_MS = 5.5F;

    private static int tickCounter;
    private static int cooldownTicks;
    private static int highPressureSamples;
    private static int stableRecoverySamples;
    private static int lastPressureScore;
    private static int adjustmentCount;
    private static String lastAdjustmentReason = "none";

    private AdaptiveQualityController() {
    }

    public static void onClientTick() {
        if (!PauCClient.isAdaptiveQualityActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options == null) {
            highPressureSamples = 0;
            stableRecoverySamples = 0;
            return;
        }

        if (PauCDeferredShaderController.isPipelineActive()) {
            onDeferredPipelineTick(minecraft);
            return;
        }

        tickCounter++;
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        if (tickCounter % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }

        int pressureScore = computePressureScore(minecraft);
        lastPressureScore = pressureScore;

        if (pressureScore >= HIGH_PRESSURE_SCORE) {
            highPressureSamples = Math.min(20, highPressureSamples + 1);
            stableRecoverySamples = 0;
        } else if (pressureScore <= RECOVERY_PRESSURE_SCORE_MAX) {
            stableRecoverySamples = Math.min(40, stableRecoverySamples + 1);
            highPressureSamples = 0;
        } else {
            highPressureSamples = 0;
            stableRecoverySamples = 0;
        }

        if (cooldownTicks > 0) {
            return;
        }

        int currentQuality = PauCClient.getQualityLevel();
        int minQuality = resolveAdaptiveMinQuality();
        if (pressureScore >= CRITICAL_PRESSURE_SCORE && currentQuality > minQuality) {
            int targetQuality = Math.max(minQuality, currentQuality - 2);
            applyAdaptiveQuality(targetQuality, "pressure_critical", currentQuality);
            return;
        }

        if (pressureScore >= HIGH_PRESSURE_SCORE
                && highPressureSamples >= REQUIRED_HIGH_PRESSURE_SAMPLES
                && currentQuality > minQuality) {
            int targetQuality = Math.max(minQuality, currentQuality - 1);
            applyAdaptiveQuality(targetQuality, "pressure_high", currentQuality);
            return;
        }

        int recoveryTarget = Math.max(minQuality, PauCClient.getAdaptiveQualityTargetLevel());
        if (currentQuality < recoveryTarget
                && pressureScore <= RECOVERY_PRESSURE_SCORE_MAX
                && stableRecoverySamples >= REQUIRED_STABLE_RECOVERY_SAMPLES) {
            int targetQuality = Math.min(recoveryTarget, currentQuality + 1);
            applyAdaptiveQuality(targetQuality, "recovery_stable", currentQuality);
        }
    }

    public static void reset() {
        tickCounter = 0;
        cooldownTicks = 0;
        highPressureSamples = 0;
        stableRecoverySamples = 0;
        lastPressureScore = 0;
        adjustmentCount = 0;
        lastAdjustmentReason = "none";
    }

    public static int getCooldownTicks() {
        return cooldownTicks;
    }

    public static int getLastPressureScore() {
        return lastPressureScore;
    }

    public static int getAdjustmentCount() {
        return adjustmentCount;
    }

    public static String getLastAdjustmentReason() {
        return lastAdjustmentReason;
    }

    private static void onDeferredPipelineTick(Minecraft minecraft) {
        tickCounter++;
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }

        if (tickCounter % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }

        int pressureScore = computePressureScore(minecraft);
        lastPressureScore = pressureScore;
        highPressureSamples = 0;
        stableRecoverySamples = 0;

        int currentQuality = PauCClient.getQualityLevel();
        int minQuality = resolveDeferredAdaptiveMinQuality(pressureScore);

        if (cooldownTicks > 0) {
            lastAdjustmentReason = "deferred_pressure_observe";
            return;
        }

        if (pressureScore >= CRITICAL_PRESSURE_SCORE && currentQuality > minQuality) {
            int dropStep = pressureScore >= CRITICAL_PRESSURE_SCORE + 4 ? 2 : 1;
            int targetQuality = Math.max(minQuality, currentQuality - dropStep);
            applyAdaptiveQuality(targetQuality, "deferred_pressure_critical", currentQuality);
            return;
        }

        if (pressureScore >= HIGH_PRESSURE_SCORE + 2 && currentQuality > minQuality) {
            int targetQuality = Math.max(minQuality, currentQuality - 1);
            applyAdaptiveQuality(targetQuality, "deferred_pressure_high", currentQuality);
            return;
        }

        if (currentQuality < PauCClient.getAdaptiveQualityTargetLevel()) {
            lastAdjustmentReason = "deferred_hold_low_quality";
        } else {
            lastAdjustmentReason = "deferred_pipeline_control";
        }
    }

    private static void applyAdaptiveQuality(int targetQuality, String reason, int previousQuality) {
        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            lastAdjustmentReason = "deferred_priority";
            cooldownTicks = Math.max(cooldownTicks, DOWN_ADJUSTMENT_COOLDOWN_TICKS);
            return;
        }

        int clampedQuality = Math.max(PauCClient.getMinQualityLevel(), Math.min(PauCClient.getMaxQualityLevel(), targetQuality));
        if (clampedQuality == previousQuality) {
            return;
        }

        PauCClient.setQualityLevelFromAdaptiveController(clampedQuality, reason);
        adjustmentCount++;
        lastAdjustmentReason = reason;
        highPressureSamples = 0;
        stableRecoverySamples = 0;
        cooldownTicks = clampedQuality < previousQuality
                ? DOWN_ADJUSTMENT_COOLDOWN_TICKS
                : UP_ADJUSTMENT_COOLDOWN_TICKS;
    }

    private static int computePressureScore(Minecraft minecraft) {
        int score = 0;
        score += LatencyController.getPressureLevel() * 2;
        score += IntegratedServerLoadController.getPressureLevel() * 2;
        score += AuthoritativeRuntimeController.getRuntimePressureBias();

        if (BottleneckController.isGpuBound()) {
            score += 2;
        }
        if (BottleneckController.isCpuBound()) {
            score += 2;
        }

        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        if (mode == GlobalPerformanceMode.CRISIS) {
            score += 3;
        } else if (mode == GlobalPerformanceMode.COMBAT) {
            score += 1;
        }

        float frameMillis = LatencyController.getStabilizedFrameMillis();
        float targetFrameMillis = resolveTargetFrameMillis(minecraft);
        if (frameMillis > 0.0F && targetFrameMillis > 0.0F) {
            if (frameMillis >= targetFrameMillis * CRITICAL_FRAME_RATIO) {
                score += 4;
            } else if (frameMillis >= targetFrameMillis * HIGH_FRAME_RATIO) {
                score += 2;
            } else if (frameMillis >= targetFrameMillis * 1.10F) {
                score += 1;
            }
        }

        float jitterMillis = LatencyController.getFrameTimeJitterMillis();
        if (jitterMillis >= JITTER_CRITICAL_THRESHOLD_MS) {
            score += 2;
        } else if (jitterMillis >= JITTER_HIGH_THRESHOLD_MS) {
            score += 1;
        }

        return score;
    }

    private static float resolveTargetFrameMillis(Minecraft minecraft) {
        float targetFromBottleneck = BottleneckController.getTargetFrameMillis();
        if (targetFromBottleneck > 0.0F) {
            return targetFromBottleneck;
        }

        int configuredCap = minecraft.options.framerateLimit().get();
        int targetFps;
        if (configuredCap > 0 && configuredCap < UNLIMITED_FRAMERATE) {
            targetFps = Math.max(30, AdaptiveFrameCapController.getLatencyReferenceCap(configuredCap));
        } else {
            targetFps = DEFAULT_TARGET_FPS;
        }
        return 1000.0F / Math.max(1.0F, targetFps);
    }

    private static int resolveAdaptiveMinQuality() {
        int minQuality = 3;
        if (IntegratedServerLoadController.getPressureLevel() >= 2 || GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS) {
            minQuality = 2;
        }
        return Math.max(PauCClient.getMinQualityLevel(), minQuality);
    }

    private static int resolveDeferredAdaptiveMinQuality(int pressureScore) {
        int minQuality = 3;
        int serverPressure = IntegratedServerLoadController.getPressureLevel();
        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        if (pressureScore >= HIGH_PRESSURE_SCORE + 2 || serverPressure >= 1 || mode == GlobalPerformanceMode.COMBAT) {
            minQuality = 2;
        }
        if (pressureScore >= CRITICAL_PRESSURE_SCORE + 2 || serverPressure >= 2 || mode == GlobalPerformanceMode.CRISIS) {
            minQuality = 1;
        }
        return Math.max(PauCClient.getMinQualityLevel(), minQuality);
    }
}
