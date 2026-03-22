package pauc.pain_au_choc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
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
    private static final double CPU_LOW_FPS_THRESHOLD_RATIO = 0.90D;
    private static final double CPU_HIGH_FPS_THRESHOLD_RATIO = 1.04D;
    private static final int GL_READ_FRAMEBUFFER = 36008;
    private static final int GL_DRAW_FRAMEBUFFER = 36009;
    private static final int GL_COLOR_BUFFER_BIT = 16384;
    private static final int GL_LINEAR = 9729;
    private static final int COPY_FAILURE_NATIVE_FALLBACK_TICKS = 160;
    private static final boolean EXPERIMENTAL_SHADER_UPSCALER = Boolean.getBoolean("pauc.experimentalUpscaleShader");
    private static final boolean STICKY_INTERNAL_TARGET = true;

    private static double currentScale = 1.0D;
    private static float smoothedFps;
    private static int tickCounter;

    private static TextureTarget internalTarget;
    private static RenderTarget previousMainTarget;
    private static boolean swappedMainTarget;
    private static int internalWidth = -1;
    private static int internalHeight = -1;
    private static boolean loggedCapturePipelineBlock;
    private static boolean loggedDeferredPipelineBlock;
    private static boolean loggedCopyFallback;
    private static boolean loggedStableUpscalePath;
    private static int forceNativeFramesRemaining;
    private static int consecutiveCopyFailures;

    private DynamicResolutionController() {
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (forceNativeFramesRemaining > 0) {
            forceNativeFramesRemaining--;
        }

        boolean capturePipelineBlocked = AuthoritativeRuntimeController.shouldForceDisableDynamicResolution();
        boolean deferredPipelineBlocked = AuthoritativeRuntimeController.shouldForceDisableDynamicResolutionForDeferredPipeline();
        if (capturePipelineBlocked) {
            if (!loggedCapturePipelineBlock) {
                loggedCapturePipelineBlock = true;
                Pain_au_Choc.LOGGER.warn("PauC DRS forced off: capture pipeline contested.");
            }
        } else {
            loggedCapturePipelineBlock = false;
        }
        if (deferredPipelineBlocked && !capturePipelineBlocked) {
            if (!loggedDeferredPipelineBlock) {
                loggedDeferredPipelineBlock = true;
                Pain_au_Choc.LOGGER.warn("PauC DRS forced off: deferred pipeline safety guard active.");
            }
        } else {
            loggedDeferredPipelineBlock = false;
        }

        if (!isDynamicResolutionRuntimeActive(minecraft)) {
            resetScaleState();
            resetCopyFailureState();
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
            boolean severeStress = GlobalPerformanceGovernor.getGlobalPressure() >= 3
                    || LatencyController.getPressureLevel() >= 2;
            double downMultiplier = severeStress ? 1.60D : 1.20D;
            if (smoothedFps < targetFps * 1.02D) {
                currentScale = clampScale(currentScale - SCALE_DOWN_STEP * downMultiplier);
            } else if (smoothedFps > targetFps * 1.12D) {
                currentScale = clampScale(currentScale + SCALE_UP_STEP * 0.35D);
            }
            return;
        }

        if (BottleneckController.isCpuBound()) {
            if (smoothedFps < targetFps * CPU_LOW_FPS_THRESHOLD_RATIO) {
                // Even under CPU pressure, moderate downscale can relieve mixed bottlenecks.
                currentScale = clampScale(currentScale - SCALE_DOWN_STEP * 0.60D);
            } else if (smoothedFps > targetFps * CPU_HIGH_FPS_THRESHOLD_RATIO) {
                currentScale = clampScale(currentScale + SCALE_UP_STEP * 0.60D);
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
            boolean copied;
            if (EXPERIMENTAL_SHADER_UPSCALER) {
                PauCShaderManager.copyColor(internalTarget, nativeTarget);
                copied = true;
            } else {
                if (!loggedStableUpscalePath) {
                    loggedStableUpscalePath = true;
                    Pain_au_Choc.LOGGER.info(
                            "PauC DRS stable path active: using framebuffer blit upscaler. Set -Dpauc.experimentalUpscaleShader=true to restore shader upscaler."
                    );
                }
                copied = blitColorFallback(internalTarget, nativeTarget);
            }

            if (!copied) {
                registerCopyFailure("upscale copy returned false", internalTarget, nativeTarget);
                resetScaleState();
                releaseInternalTarget();
            } else {
                clearCopyFailureStateAfterRecovery();
            }
        } catch (Exception exception) {
            if (!loggedCopyFallback) {
                loggedCopyFallback = true;
                Pain_au_Choc.LOGGER.error("PauC DRS upscale failed, switching to framebuffer blit fallback.", exception);
            }

            if (!blitColorFallback(internalTarget, nativeTarget)) {
                registerCopyFailure("framebuffer blit fallback failed after exception", internalTarget, nativeTarget);
                resetScaleState();
                releaseInternalTarget();
            } else {
                clearCopyFailureStateAfterRecovery();
            }
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
        resetCopyFailureState();
    }

    public static double getCurrentScale() {
        return currentScale;
    }

    public static int getNativeFallbackFramesRemaining() {
        return forceNativeFramesRemaining;
    }

    public static int getConsecutiveCopyFailures() {
        return consecutiveCopyFailures;
    }

    public static boolean isStabilityGuardActive() {
        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        if (mode != GlobalPerformanceMode.CRISIS) {
            return false;
        }
        return IntegratedServerLoadController.getPressureLevel() >= 2
                || IntegratedServerLoadController.isEmergencyMitigationActive();
    }

    public static String getUpscalePathLabel() {
        if (isStabilityGuardActive()) {
            return "native_guard";
        }
        return EXPERIMENTAL_SHADER_UPSCALER ? "shader" : "blit";
    }

    private static boolean isDynamicResolutionRuntimeActive(Minecraft minecraft) {
        return PauCClient.isDynamicResolutionActive()
                && minecraft != null
                && minecraft.level != null
                && minecraft.options != null;
    }

    private static boolean shouldUseInternalTarget(Minecraft minecraft) {
        if (!isDynamicResolutionRuntimeActive(minecraft)) {
            return false;
        }
        if (isStabilityGuardActive()) {
            return false;
        }
        if (forceNativeFramesRemaining > 0) {
            return false;
        }
        return STICKY_INTERNAL_TARGET || currentScale < 0.999D;
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
        if (minScale < 0.35D) {
            minScale = 0.35D;
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

    private static void resetCopyFailureState() {
        forceNativeFramesRemaining = 0;
        consecutiveCopyFailures = 0;
    }

    private static void clearCopyFailureStateAfterRecovery() {
        if (consecutiveCopyFailures <= 0 && forceNativeFramesRemaining <= 0) {
            return;
        }

        Pain_au_Choc.LOGGER.info("PauC DRS copy path recovered after {} failure(s).", consecutiveCopyFailures);
        resetCopyFailureState();
    }

    private static void registerCopyFailure(String reason, RenderTarget source, RenderTarget target) {
        consecutiveCopyFailures = Math.min(9999, consecutiveCopyFailures + 1);
        forceNativeFramesRemaining = Math.max(forceNativeFramesRemaining, COPY_FAILURE_NATIVE_FALLBACK_TICKS);

        if (consecutiveCopyFailures == 1 || consecutiveCopyFailures % 20 == 0) {
            int sourceFbo = source == null ? -1 : source.frameBufferId;
            int targetFbo = target == null ? -1 : target.frameBufferId;
            int sourceWidth = source == null ? -1 : source.viewWidth;
            int sourceHeight = source == null ? -1 : source.viewHeight;
            int targetWidth = target == null ? -1 : target.viewWidth;
            int targetHeight = target == null ? -1 : target.viewHeight;
            Pain_au_Choc.LOGGER.warn(
                    "PauC DRS copy failure #{}: {} (srcFbo={}, dstFbo={}, src={}x{}, dst={}x{}). Native fallback for {} ticks.",
                    consecutiveCopyFailures,
                    reason,
                    sourceFbo,
                    targetFbo,
                    sourceWidth,
                    sourceHeight,
                    targetWidth,
                    targetHeight,
                    forceNativeFramesRemaining
            );
        }
    }

    private static boolean blitColorFallback(RenderTarget source, RenderTarget target) {
        if (source == null || target == null) {
            return false;
        }

        if (source.frameBufferId <= 0 || target.frameBufferId <= 0) {
            return false;
        }

        try {
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, source.frameBufferId);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._glBlitFrameBuffer(
                    0,
                    0,
                    source.viewWidth,
                    source.viewHeight,
                    0,
                    0,
                    target.viewWidth,
                    target.viewHeight,
                    GL_COLOR_BUFFER_BIT,
                    GL_LINEAR
            );
            return true;
        } catch (Exception exception) {
            Pain_au_Choc.LOGGER.error("PauC DRS framebuffer blit fallback failed.", exception);
            return false;
        }
    }
}

