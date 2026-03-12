package pauc.pain_au_choc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import pauc.pain_au_choc.mixin.MinecraftMainRenderTargetAccessor;
import net.minecraft.client.Minecraft;

public final class DynamicResolutionController {
    private static final int UNLIMITED_FRAMERATE = 260;
    private static final int DEFAULT_TARGET_FPS = 120;
    private static final int SCALE_UPDATE_INTERVAL_TICKS = 8;
    private static final float FPS_SMOOTHING = 0.20F;
    private static final double SCALE_DOWN_STEP = 0.05D;
    private static final double SCALE_UP_STEP = 0.02D;
    private static final double LOW_FPS_THRESHOLD_RATIO = 0.98D;
    private static final double HIGH_FPS_THRESHOLD_RATIO = 1.03D;

    private static double currentScale = 1.0D;
    private static float smoothedFps;
    private static int tickCounter;

    private static TextureTarget internalTarget;
    private static RenderTarget previousMainTarget;
    private static boolean swappedMainTarget;
    private static int internalWidth = -1;
    private static int internalHeight = -1;

    private DynamicResolutionController() {
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isDynamicResolutionRuntimeActive(minecraft)) {
            resetScaleState();
            releaseInternalTarget();
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
        }

        if (tickCounter % SCALE_UPDATE_INTERVAL_TICKS != 0 || smoothedFps <= 0.0F) {
            return;
        }

        double targetFps = resolveTargetFps(minecraft);
        if (BottleneckController.isGpuBound()) {
            if (smoothedFps < targetFps * 1.01D) {
                currentScale = clampScale(currentScale - SCALE_DOWN_STEP * 1.15D);
            }
            return;
        }

        if (BottleneckController.isCpuBound()) {
            if (smoothedFps < targetFps * 0.75D) {
                currentScale = clampScale(currentScale - SCALE_DOWN_STEP * 0.35D);
            } else {
                currentScale = clampScale(currentScale + SCALE_UP_STEP * 0.75D);
            }
            return;
        }

        if (smoothedFps < targetFps * LOW_FPS_THRESHOLD_RATIO) {
            currentScale = clampScale(currentScale - SCALE_DOWN_STEP);
        } else if (smoothedFps > targetFps * HIGH_FPS_THRESHOLD_RATIO) {
            currentScale = clampScale(currentScale + SCALE_UP_STEP);
        }
    }

    public static void beginWorldRenderPass() {
        Minecraft minecraft = Minecraft.getInstance();
        restoreMainTargetIfNeeded(minecraft);

        if (!shouldUseInternalTarget(minecraft)) {
            releaseInternalTarget();
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null) {
            return;
        }

        int targetWidth = getScaledDimension(mainTarget.viewWidth);
        int targetHeight = getScaledDimension(mainTarget.viewHeight);
        ensureInternalTarget(targetWidth, targetHeight);
        if (internalTarget == null) {
            return;
        }

        previousMainTarget = mainTarget;
        ((MinecraftMainRenderTargetAccessor) minecraft).pauc$setMainRenderTarget(internalTarget);
        swappedMainTarget = true;
        internalTarget.bindWrite(true);
        internalTarget.clear(Minecraft.ON_OSX);
    }

    public static void endWorldRenderPass(RenderTarget expectedTarget, boolean setViewport) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!swappedMainTarget || previousMainTarget == null || internalTarget == null) {
            if (expectedTarget != null) {
                expectedTarget.bindWrite(setViewport);
            }
            return;
        }

        RenderTarget nativeTarget = previousMainTarget;
        try {
            PauCShaderManager.copyColor(internalTarget, nativeTarget);
        } finally {
            restoreMainTargetIfNeeded(minecraft);
        }
        nativeTarget.bindWrite(setViewport);
    }

    public static void failSafeRestore() {
        restoreMainTargetIfNeeded(Minecraft.getInstance());
    }

    public static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        restoreMainTargetIfNeeded(minecraft);
        releaseInternalTarget();
        resetScaleState();
    }

    public static double getCurrentScale() {
        return currentScale;
    }

    private static boolean isDynamicResolutionRuntimeActive(Minecraft minecraft) {
        return PauCClient.isDynamicResolutionActive()
                && minecraft != null
                && minecraft.level != null
                && minecraft.options != null;
    }

    private static boolean shouldUseInternalTarget(Minecraft minecraft) {
        return isDynamicResolutionRuntimeActive(minecraft) && currentScale < 0.999D;
    }

    private static double resolveTargetFps(Minecraft minecraft) {
        int configuredCap = minecraft.options.framerateLimit().get();
        if (configuredCap > 0 && configuredCap < UNLIMITED_FRAMERATE) {
            return Math.max(30, AdaptiveFrameCapController.getLatencyReferenceCap(configuredCap));
        }
        return DEFAULT_TARGET_FPS;
    }

    private static double clampScale(double value) {
        double minScale = PauCClient.getDynamicResolutionMinScale();
        if (minScale < 0.50D) {
            minScale = 0.50D;
        } else if (minScale > 1.0D) {
            minScale = 1.0D;
        }
        return Math.max(minScale, Math.min(1.0D, value));
    }

    private static int getScaledDimension(int nativeDimension) {
        int clampedNative = Math.max(1, nativeDimension);
        int scaled = (int) Math.round(clampedNative * currentScale);
        return Math.max(1, Math.min(clampedNative, scaled));
    }

    private static void ensureInternalTarget(int width, int height) {
        if (internalTarget == null) {
            internalTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            internalTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            internalWidth = width;
            internalHeight = height;
            return;
        }

        if (width != internalWidth || height != internalHeight) {
            internalTarget.resize(width, height, Minecraft.ON_OSX);
            internalWidth = width;
            internalHeight = height;
        }
    }

    private static void restoreMainTargetIfNeeded(Minecraft minecraft) {
        if (!swappedMainTarget) {
            return;
        }

        if (minecraft != null && previousMainTarget != null) {
            ((MinecraftMainRenderTargetAccessor) minecraft).pauc$setMainRenderTarget(previousMainTarget);
        }

        swappedMainTarget = false;
        previousMainTarget = null;
    }

    private static void releaseInternalTarget() {
        if (internalTarget != null) {
            internalTarget.destroyBuffers();
            internalTarget = null;
        }
        internalWidth = -1;
        internalHeight = -1;
    }

    private static void resetScaleState() {
        currentScale = 1.0D;
        smoothedFps = 0.0F;
        tickCounter = 0;
    }
}

