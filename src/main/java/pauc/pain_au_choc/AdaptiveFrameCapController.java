package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;

public final class AdaptiveFrameCapController {
    private static final int UNLIMITED_SENTINEL = 260;
    private static final double NANOS_TO_MILLIS = 1.0D / 1_000_000.0D;
    private static final double FAST_SMOOTHING = 0.45D;
    private static final double SLOW_SMOOTHING = 0.12D;

    private static long lastFrameNanos;
    private static double fastFrameMillis;
    private static double slowFrameMillis;
    private static int effectiveCap;
    private static int lastBaseCap;
    private static int stableFrames;

    private AdaptiveFrameCapController() {
    }

    public static void onFrameStart() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PauCClient.isEnabled() || minecraft.options == null || CompatibilityGuards.shouldDisableAdaptiveFrameCap()) {
            reset();
            return;
        }

        if (Boolean.TRUE.equals(minecraft.options.enableVsync().get())) {
            minecraft.options.enableVsync().set(false);
        }

        int baseCap = minecraft.options.framerateLimit().get();
        long now = System.nanoTime();
        if (!isAdaptiveCapEligible(baseCap)) {
            lastFrameNanos = now;
            effectiveCap = baseCap;
            lastBaseCap = baseCap;
            stableFrames = 0;
            fastFrameMillis = 0.0D;
            slowFrameMillis = 0.0D;
            return;
        }

        if (baseCap != lastBaseCap || effectiveCap <= 0 || effectiveCap > baseCap) {
            effectiveCap = baseCap;
            stableFrames = 0;
            fastFrameMillis = 0.0D;
            slowFrameMillis = 0.0D;
        }

        if (lastFrameNanos != 0L) {
            double frameMillis = (now - lastFrameNanos) * NANOS_TO_MILLIS;
            if (frameMillis > 0.1D && frameMillis < 250.0D) {
                if (fastFrameMillis <= 0.0D) {
                    fastFrameMillis = frameMillis;
                    slowFrameMillis = frameMillis;
                } else {
                    fastFrameMillis += (frameMillis - fastFrameMillis) * FAST_SMOOTHING;
                    slowFrameMillis += (frameMillis - slowFrameMillis) * SLOW_SMOOTHING;
                }

                adaptTowardStableCap(baseCap);
            }
        }

        lastFrameNanos = now;
        lastBaseCap = baseCap;
    }

    public static void reset() {
        lastFrameNanos = 0L;
        fastFrameMillis = 0.0D;
        slowFrameMillis = 0.0D;
        effectiveCap = 0;
        lastBaseCap = 0;
        stableFrames = 0;
    }

    public static int clampVanillaFrameLimit(int vanillaLimit, int configuredCap) {
        if (!PauCClient.isEnabled() || CompatibilityGuards.shouldDisableAdaptiveFrameCap() || !isAdaptiveCapEligible(configuredCap)) {
            return vanillaLimit;
        }

        int runtimeCap = getRuntimeCap(configuredCap);
        if (runtimeCap <= 0) {
            return vanillaLimit;
        }

        return Math.min(vanillaLimit, runtimeCap);
    }

    public static int getLatencyReferenceCap(int configuredCap) {
        if (!PauCClient.isEnabled() || CompatibilityGuards.shouldDisableAdaptiveFrameCap() || !isAdaptiveCapEligible(configuredCap)) {
            return configuredCap;
        }

        return getRuntimeCap(configuredCap);
    }

    public static boolean isAdaptiveCapEligible(int configuredCap) {
        return configuredCap > 0 && configuredCap < UNLIMITED_SENTINEL;
    }

    private static int getRuntimeCap(int configuredCap) {
        if (!isAdaptiveCapEligible(configuredCap)) {
            return configuredCap;
        }

        if (effectiveCap <= 0) {
            effectiveCap = configuredCap;
        }

        if (effectiveCap > configuredCap) {
            effectiveCap = configuredCap;
        }

        return effectiveCap;
    }

    private static void adaptTowardStableCap(int baseCap) {
        int currentCap = getRuntimeCap(baseCap);
        double targetFrameMillis = 1000.0D / Math.max(1, currentCap);
        double hardUpperBound = targetFrameMillis * 1.08D;
        double softUpperBound = targetFrameMillis * 1.03D;
        double lowerBound = targetFrameMillis * 0.94D;
        int floorCap = getMinimumAdaptiveCap(baseCap);

        if (fastFrameMillis > hardUpperBound || slowFrameMillis > softUpperBound) {
            stableFrames = 0;
            double stress = Math.max(fastFrameMillis / targetFrameMillis, slowFrameMillis / targetFrameMillis);
            int drop = stress >= 1.50D ? 10 : stress >= 1.30D ? 6 : stress >= 1.15D ? 4 : 2;
            effectiveCap = Math.max(floorCap, currentCap - drop);
            return;
        }

        if (fastFrameMillis < lowerBound && slowFrameMillis < targetFrameMillis * 0.98D) {
            stableFrames++;
            if (stableFrames >= 10 && currentCap < baseCap) {
                effectiveCap = Math.min(baseCap, currentCap + 1);
                stableFrames = 0;
            }
            return;
        }

        if (stableFrames > 0) {
            stableFrames--;
        }
    }

    private static int getMinimumAdaptiveCap(int baseCap) {
        int ratioFloor = (int) Math.floor(baseCap * 0.55D);
        return Math.min(baseCap, Math.max(30, ratioFloor));
    }
}

