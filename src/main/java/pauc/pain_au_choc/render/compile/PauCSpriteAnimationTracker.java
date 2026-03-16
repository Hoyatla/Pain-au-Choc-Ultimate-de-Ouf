package pauc.pain_au_choc.render.compile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.GlobalPerformanceMode;

/**
 * Tracks and throttles animated sprite updates to reduce CPU overhead.
 *
 * Adapted from Embeddium's sprite animation optimization.
 * In low-quality or high-pressure modes, animations are throttled
 * to update less frequently, saving significant CPU time.
 */
public final class PauCSpriteAnimationTracker {

    /** Minimum frames between animation ticks in throttled mode. */
    private static final int THROTTLE_INTERVAL_LOW = 2;
    private static final int THROTTLE_INTERVAL_MEDIUM = 4;
    private static final int THROTTLE_INTERVAL_HIGH = 8;

    private static int frameCounter;
    private static int activeAnimationCount;
    private static int skippedAnimationCount;

    private PauCSpriteAnimationTracker() {
    }

    /**
     * Called once per frame to advance the counter.
     */
    public static void tick() {
        frameCounter++;
        if (frameCounter > 100_000) {
            frameCounter = 0;
        }
    }

    /**
     * Determines if a sprite animation should be updated this frame.
     * When PAUC is under pressure, non-critical animations are throttled.
     *
     * @param sprite the texture sprite to check
     * @return true if the sprite should animate this frame
     */
    public static boolean shouldAnimateThisFrame(TextureAtlasSprite sprite) {
        if (!PauCClient.isBudgetActive()) {
            return true;
        }

        int interval = getThrottleInterval();
        if (interval <= 1) {
            activeAnimationCount++;
            return true;
        }

        // Use sprite hash to stagger animations across frames
        int hash = System.identityHashCode(sprite);
        boolean shouldAnimate = ((frameCounter + (hash & 0xFF)) % interval) == 0;

        if (shouldAnimate) {
            activeAnimationCount++;
        } else {
            skippedAnimationCount++;
        }

        return shouldAnimate;
    }

    /**
     * Returns the current throttle interval based on governor pressure.
     */
    private static int getThrottleInterval() {
        int quality = PauCClient.getQualityLevel();
        if (quality >= 8) {
            return 1; // No throttling at high quality
        }

        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        return switch (mode) {
            case CRISIS -> THROTTLE_INTERVAL_HIGH;
            case COMBAT, TRANSIT -> THROTTLE_INTERVAL_MEDIUM;
            default -> quality <= 3 ? THROTTLE_INTERVAL_MEDIUM : THROTTLE_INTERVAL_LOW;
        };
    }

    /**
     * Returns count of animations that ran this frame (for debug).
     */
    public static int getActiveAnimationCount() {
        return activeAnimationCount;
    }

    /**
     * Returns count of animations skipped this frame (for debug).
     */
    public static int getSkippedAnimationCount() {
        return skippedAnimationCount;
    }

    /**
     * Reset per-frame counters.
     */
    public static void resetFrameCounters() {
        activeAnimationCount = 0;
        skippedAnimationCount = 0;
    }
}
