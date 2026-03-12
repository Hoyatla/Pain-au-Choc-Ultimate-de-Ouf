package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;

public final class LatencyController {
    private static final float SMOOTHING_FACTOR = 0.20F;
    private static final float FAST_FRAME_SMOOTHING = 0.45F;
    private static final float SLOW_FRAME_SMOOTHING = 0.14F;
    private static final float JITTER_SMOOTHING = 0.20F;

    private static float smoothedFps;
    private static float fastFrameMillis;
    private static float slowFrameMillis;
    private static float frameTimeJitterMillis;
    private static int pressureLevel;
    private static int stableTicks;
    private static int tickCounter;

    private LatencyController() {
    }

    public static void tick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        tickCounter++;

        Minecraft minecraft = Minecraft.getInstance();
        int currentFps = minecraft.getFps();
        if (currentFps <= 0) {
            return;
        }

        if (smoothedFps <= 0.0F) {
            smoothedFps = currentFps;
        } else {
            smoothedFps += (currentFps - smoothedFps) * SMOOTHING_FACTOR;
        }

        float frameMillis = 1000.0F / Math.max(1.0F, currentFps);
        if (fastFrameMillis <= 0.0F) {
            fastFrameMillis = frameMillis;
            slowFrameMillis = frameMillis;
            frameTimeJitterMillis = 0.0F;
        } else if (PauCClient.isFrameTimeStabilizerActive()) {
            fastFrameMillis += (frameMillis - fastFrameMillis) * FAST_FRAME_SMOOTHING;
            slowFrameMillis += (frameMillis - slowFrameMillis) * SLOW_FRAME_SMOOTHING;
            float instantaneousJitter = Math.abs(fastFrameMillis - slowFrameMillis);
            frameTimeJitterMillis += (instantaneousJitter - frameTimeJitterMillis) * JITTER_SMOOTHING;
        } else {
            fastFrameMillis = frameMillis;
            slowFrameMillis = frameMillis;
            frameTimeJitterMillis = 0.0F;
        }

        float targetFloor = getTargetFpsFloor();
        float stabilizedFps = getStabilizedFps();
        float jitterThreshold = getTargetJitterMillis();
        if (stabilizedFps < targetFloor || frameTimeJitterMillis > jitterThreshold) {
            stableTicks = 0;
            if (pressureLevel < 3) {
                pressureLevel++;
            }
        } else {
            stableTicks++;
            if (stableTicks >= 10 && pressureLevel > 0) {
                pressureLevel--;
                stableTicks = 0;
            }
        }
    }

    public static void reset() {
        smoothedFps = 0.0F;
        fastFrameMillis = 0.0F;
        slowFrameMillis = 0.0F;
        frameTimeJitterMillis = 0.0F;
        pressureLevel = 0;
        stableTicks = 0;
        tickCounter = 0;
    }

    public static int getAdaptiveInterval(int baseInterval) {
        if (!PauCClient.isBudgetActive()) {
            return Math.max(1, baseInterval);
        }

        int cpuPenalty = Math.max(0, 3 - PauCClient.getCpuInvolvementLevel());
        int multiplier = 1 + pressureLevel + cpuPenalty;
        if (BottleneckController.isCpuBound()) {
            multiplier++;
        }
        return Math.max(1, baseInterval * multiplier);
    }

    public static boolean shouldRunBackgroundWork(int workPhase) {
        if (!PauCClient.isBudgetActive()) {
            return false;
        }

        if (pressureLevel <= 0) {
            return PauCClient.getCpuInvolvementLevel() >= 2 || (tickCounter + Math.max(0, workPhase)) % 2 == 0;
        }

        int cpuPenalty = Math.max(0, 3 - PauCClient.getCpuInvolvementLevel());
        int cadence = 1 + pressureLevel + cpuPenalty + (BottleneckController.isCpuBound() ? 1 : 0);
        int offset = Math.max(0, workPhase);
        return (tickCounter + offset) % cadence == 0;
    }

    public static int getPressureLevel() {
        return pressureLevel;
    }

    public static float getSmoothedFps() {
        return smoothedFps;
    }

    public static float getStabilizedFrameMillis() {
        if (slowFrameMillis > 0.0F) {
            return slowFrameMillis;
        }
        return smoothedFps > 0.0F ? 1000.0F / smoothedFps : 0.0F;
    }

    public static float getFrameTimeJitterMillis() {
        return frameTimeJitterMillis;
    }

    private static float getTargetFpsFloor() {
        Minecraft minecraft = Minecraft.getInstance();
        int configuredCap = minecraft.options == null ? 120 : minecraft.options.framerateLimit().get();
        int referenceCap;
        if (configuredCap > 0 && configuredCap < 260) {
            referenceCap = AdaptiveFrameCapController.getLatencyReferenceCap(configuredCap);
        } else {
            referenceCap = 120;
        }

        float headroomFactor = 1.0F - GpuHeadroomController.getHeadroomFraction();
        return Math.max(45.0F, referenceCap * Math.max(0.55F, headroomFactor));
    }

    private static float getStabilizedFps() {
        if (!PauCClient.isFrameTimeStabilizerActive()) {
            return smoothedFps;
        }

        float stabilizedFrameMs = getStabilizedFrameMillis();
        return stabilizedFrameMs > 0.0F ? 1000.0F / stabilizedFrameMs : smoothedFps;
    }

    private static float getTargetJitterMillis() {
        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        float baseJitter = 2.2F;
        if (cpuLevel == 1) {
            return baseJitter + 0.8F;
        }
        if (cpuLevel == 3) {
            return baseJitter;
        }
        return baseJitter + 0.4F;
    }
}

