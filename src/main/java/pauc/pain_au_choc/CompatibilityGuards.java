package pauc.pain_au_choc;

import net.minecraftforge.fml.ModList;

public final class CompatibilityGuards {
    private static Boolean oculusLoaded;
    private static Boolean embeddiumLoaded;
    private static Boolean geckoLibLoaded;
    private static Boolean replayStackLoaded;
    private static boolean logged;

    private CompatibilityGuards() {
    }

    public static void logDetectedStack() {
        if (logged) {
            return;
        }

        logged = true;
        if (isOculusLoaded()) {
            Pain_au_Choc.LOGGER.warn("PauC compatibility mode: Oculus detected, disabling risky render-path overrides and advanced sharpening.");
        }
        if (isEmbeddiumLoaded()) {
            Pain_au_Choc.LOGGER.info("PauC compatibility: Embeddium detected.");
        }
        if (isGeckoLibLoaded()) {
            Pain_au_Choc.LOGGER.info("PauC compatibility: GeckoLib detected.");
        }
        if (isReplayStackLoaded()) {
            Pain_au_Choc.LOGGER.info("PauC compatibility: Replay stack detected.");
        }
    }

    public static boolean shouldDisableDynamicResolution() {
        return AuthoritativeRuntimeController.shouldYieldDynamicResolutionToExternalPipeline();
    }

    public static boolean shouldDisableAdaptiveFrameCap() {
        return AuthoritativeRuntimeController.shouldYieldAdaptiveFrameCapToExternalPipeline();
    }

    public static boolean shouldDisableEntityLodBillboards() {
        return AuthoritativeRuntimeController.shouldYieldEntityBillboardsToExternalPipeline();
    }

    public static boolean shouldDisableAdvancedSharpening() {
        return AuthoritativeRuntimeController.shouldYieldAdvancedSharpeningToExternalPipeline();
    }

    public static boolean isGeckoLibLoaded() {
        if (geckoLibLoaded == null) {
            geckoLibLoaded = isLoaded("geckolib");
        }
        return geckoLibLoaded;
    }

    private static boolean isOculusLoaded() {
        if (oculusLoaded == null) {
            oculusLoaded = isLoaded("oculus");
        }
        return oculusLoaded;
    }

    private static boolean isEmbeddiumLoaded() {
        if (embeddiumLoaded == null) {
            embeddiumLoaded = isLoaded("embeddium");
        }
        return embeddiumLoaded;
    }

    private static boolean isReplayStackLoaded() {
        if (replayStackLoaded == null) {
            replayStackLoaded = isLoaded("replaymod") || isLoaded("reforgedplaymod");
        }
        return replayStackLoaded;
    }

    private static boolean isLoaded(String modId) {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(modId);
    }
}


