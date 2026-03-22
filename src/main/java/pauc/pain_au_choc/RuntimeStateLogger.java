package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

import java.util.Objects;

/**
 * Logs runtime feature transitions with explicit ON/OFF reasons for support and QA triage.
 */
public final class RuntimeStateLogger {
    private static boolean initialized;
    private static boolean lastDynamicResolutionActive;
    private static String lastDynamicResolutionReason = "";
    private static boolean lastProxyActive;
    private static String lastProxyReason = "";
    private static String lastShaderSignature = "";

    private RuntimeStateLogger() {
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }

        boolean inWorld = minecraft.level != null;
        logDynamicResolutionState(inWorld);
        logProxyState(inWorld);
        logShaderState();
        initialized = true;
    }

    public static void reset() {
        initialized = false;
        lastDynamicResolutionActive = false;
        lastDynamicResolutionReason = "";
        lastProxyActive = false;
        lastProxyReason = "";
        lastShaderSignature = "";
    }

    private static void logDynamicResolutionState(boolean inWorld) {
        boolean active = inWorld && PauCClient.isDynamicResolutionActive();
        String reason = inWorld ? PauCClient.getDynamicResolutionRuntimeReason() : "no world";
        if (initialized
                && active == lastDynamicResolutionActive
                && Objects.equals(reason, lastDynamicResolutionReason)) {
            return;
        }

        lastDynamicResolutionActive = active;
        lastDynamicResolutionReason = reason;
        Pain_au_Choc.LOGGER.info(
                "PauC runtime drs={} reason={} minScale={}",
                active ? "on" : "off",
                reason,
                PauCClient.getDynamicResolutionMinScale()
        );
    }

    private static void logProxyState(boolean inWorld) {
        boolean active = inWorld && ManagedChunkRadiusController.shouldRenderProxyTerrain();
        String reason = inWorld ? ManagedChunkRadiusController.getProxyRuntimeReason() : "no world";
        if (initialized
                && active == lastProxyActive
                && Objects.equals(reason, lastProxyReason)) {
            return;
        }

        lastProxyActive = active;
        lastProxyReason = reason;
        Pain_au_Choc.LOGGER.info(
                "PauC runtime proxy={} reason={} radius={}",
                active ? "on" : "off",
                reason,
                ManagedChunkRadiusController.getRadiusSummary()
        );
    }

    private static void logShaderState() {
        String deferredPack = PauCDeferredShaderController.isPipelineActive()
                ? PauCDeferredShaderController.getSelectedPack()
                : "OFF";
        String deferredMode = PauCDeferredShaderController.getCompatibilityLabel();
        String shaderLabel = PauCShaderManager.getActiveShaderLabel();
        String upscaleRoute = PauCShaderManager.shouldProcessAtNativeScale() ? "native" : "drs";
        String signature = deferredPack + "|" + deferredMode + "|" + shaderLabel + "|" + upscaleRoute;

        if (initialized && signature.equals(lastShaderSignature)) {
            return;
        }

        lastShaderSignature = signature;
        Pain_au_Choc.LOGGER.info(
                "PauC runtime shader upscaler={} route={} deferred={} mode={}",
                shaderLabel,
                upscaleRoute,
                deferredPack,
                deferredMode
        );
    }
}
