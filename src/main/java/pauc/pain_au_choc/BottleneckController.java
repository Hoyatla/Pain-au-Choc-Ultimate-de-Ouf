package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;

public final class BottleneckController {
    private static final int UNLIMITED_SENTINEL = 260;
    private static final int DEFAULT_TARGET_FPS = 120;
    private static final int UPDATE_INTERVAL_TICKS = 4;
    private static final float FPS_SMOOTHING = 0.18F;
    private static final float FRAME_MS_SMOOTHING = 0.20F;

    private static float smoothedFps;
    private static float smoothedFrameMillis;
    private static float estimatedGpuFrameMillis;
    private static float estimatedCpuFrameMillis;
    private static float targetFrameMillis;
    private static BottleneckState state = BottleneckState.BALANCED;
    private static int tickCounter;

    private BottleneckController() {
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PauCClient.isGpuBottleneckDetectorActive() || minecraft.options == null) {
            reset();
            return;
        }

        tickCounter++;
        int fps = minecraft.getFps();
        if (fps > 0) {
            if (smoothedFps <= 0.0F) {
                smoothedFps = fps;
            } else {
                smoothedFps += (fps - smoothedFps) * FPS_SMOOTHING;
            }

            float frameMillis = 1000.0F / Math.max(1.0F, fps);
            if (smoothedFrameMillis <= 0.0F) {
                smoothedFrameMillis = frameMillis;
            } else {
                smoothedFrameMillis += (frameMillis - smoothedFrameMillis) * FRAME_MS_SMOOTHING;
            }
        }

        if (tickCounter % UPDATE_INTERVAL_TICKS != 0 || smoothedFps <= 0.0F || smoothedFrameMillis <= 0.0F) {
            return;
        }

        float targetFps = resolveTargetFps(minecraft);
        targetFrameMillis = 1000.0F / Math.max(1.0F, targetFps);

        float fpsPressure = targetFps / Math.max(1.0F, smoothedFps);
        float scalePressure = (float) Math.pow(Math.max(0.50D, DynamicResolutionController.getCurrentScale()), 0.70D);
        float gpuLoadWeight = GpuHeadroomController.getTargetGpuLoad() * fpsPressure * scalePressure;

        float jitterWeight = 1.0F + Math.min(0.65F, LatencyController.getFrameTimeJitterMillis() / Math.max(1.0F, targetFrameMillis));
        float cpuLoadWeight = 0.85F + LatencyController.getPressureLevel() * 0.35F + (jitterWeight - 1.0F) * 0.75F;

        float weightSum = Math.max(0.001F, gpuLoadWeight + cpuLoadWeight);
        float gpuRatio = gpuLoadWeight / weightSum;
        float cpuRatio = cpuLoadWeight / weightSum;

        estimatedGpuFrameMillis = smoothedFrameMillis * gpuRatio;
        estimatedCpuFrameMillis = smoothedFrameMillis * cpuRatio;

        float overloadedThreshold = targetFrameMillis * 1.03F;
        if (smoothedFrameMillis <= overloadedThreshold) {
            state = BottleneckState.BALANCED;
            return;
        }

        if (estimatedGpuFrameMillis > estimatedCpuFrameMillis * 1.18F) {
            state = BottleneckState.GPU_BOUND;
            return;
        }

        if (estimatedCpuFrameMillis > estimatedGpuFrameMillis * 1.12F) {
            state = BottleneckState.CPU_BOUND;
            return;
        }

        state = gpuLoadWeight >= cpuLoadWeight ? BottleneckState.GPU_BOUND : BottleneckState.CPU_BOUND;
    }

    public static void reset() {
        smoothedFps = 0.0F;
        smoothedFrameMillis = 0.0F;
        estimatedGpuFrameMillis = 0.0F;
        estimatedCpuFrameMillis = 0.0F;
        targetFrameMillis = 0.0F;
        state = BottleneckState.BALANCED;
        tickCounter = 0;
    }

    public static boolean isGpuBound() {
        return state == BottleneckState.GPU_BOUND;
    }

    public static boolean isCpuBound() {
        return state == BottleneckState.CPU_BOUND;
    }

    public static BottleneckState getState() {
        return state;
    }

    public static float getEstimatedGpuFrameMillis() {
        return estimatedGpuFrameMillis;
    }

    public static float getEstimatedCpuFrameMillis() {
        return estimatedCpuFrameMillis;
    }

    public static float getTargetFrameMillis() {
        return targetFrameMillis;
    }

    private static float resolveTargetFps(Minecraft minecraft) {
        int configuredCap = minecraft.options.framerateLimit().get();
        if (configuredCap > 0 && configuredCap < UNLIMITED_SENTINEL) {
            return Math.max(30, AdaptiveFrameCapController.getLatencyReferenceCap(configuredCap));
        }
        return DEFAULT_TARGET_FPS;
    }

    public enum BottleneckState {
        BALANCED,
        GPU_BOUND,
        CPU_BOUND
    }
}

