package pauc.pain_au_choc.render.shader;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import pauc.pain_au_choc.BottleneckController;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.GlobalPerformanceMode;
import pauc.pain_au_choc.LatencyController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the OptiFine-compatible deferred shader pipeline.
 * Manages shaderpack discovery, selection, activation, and lifecycle.
 *
 * Shaderpacks are read from the standard {@code shaderpacks/} directory
 * in the Minecraft game directory (same location as Iris/Oculus).
 * This allows users to reuse existing OptiFine/Iris shaderpacks.
 *
 * The pipeline is fully integrated with PAUC's performance governor:
 * shadow distance, pass skipping, and DRS all adapt automatically.
 */
public final class PauCDeferredShaderController {

    /** Standard shaderpacks directory (same as Iris/Oculus). */
    private static final Path SHADERPACKS_DIR = FMLPaths.GAMEDIR.get().resolve("shaderpacks");

    /** Special key meaning no shaderpack (vanilla rendering). */
    public static final String NONE_KEY = "(off)";

    /** Currently selected shaderpack name (or NONE_KEY). */
    private static String selectedPack = NONE_KEY;
    /** Deferred compatibility mode (strict/balanced/fast). */
    private static DeferredCompatibilityMode compatibilityMode = DeferredCompatibilityMode.BALANCED;
    /** Effective mode currently used by the active pipeline. */
    private static DeferredCompatibilityMode effectiveCompatibilityMode = DeferredCompatibilityMode.BALANCED;

    /** Cached list of available shaderpacks. */
    private static List<String> availablePacks = new ArrayList<>();

    /** Whether the packs list has been scanned. */
    private static boolean scanned = false;
    private static final long ENSURE_RETRY_INTERVAL_NANOS = 2_000_000_000L;
    private static long lastEnsureAttemptNanos;
    private static final int ADAPTIVE_MODE_TICK_INTERVAL = 20;
    private static final int ADAPTIVE_MODE_SWITCH_COOLDOWN_TICKS = 160;
    private static final int ADAPTIVE_STRESS_CONFIRM_TICKS = 40;
    private static final int ADAPTIVE_RECOVERY_CONFIRM_TICKS = 220;
    private static int adaptiveModeTickCounter;
    private static int adaptiveModeSwitchCooldownTicks;
    private static int adaptiveStressTicks;
    private static int adaptiveRecoveryTicks;

    private PauCDeferredShaderController() {}

    // ================================================================
    // Discovery
    // ================================================================

    /**
     * Ensure the shaderpacks directory exists.
     */
    public static void initializePackFolder() {
        try {
            Files.createDirectories(SHADERPACKS_DIR);
        } catch (Exception e) {
            System.err.println("[PAUC Deferred] Failed to create shaderpacks directory: " + e.getMessage());
        }
    }

    /**
     * Scan the shaderpacks directory for available packs.
     * A valid pack is either a directory containing {@code shaders/} or a .zip file.
     */
    public static void refreshAvailablePacks() {
        initializePackFolder();
        availablePacks = ShaderPackLoader.listAvailable(SHADERPACKS_DIR);
        scanned = true;

        // Validate current selection still exists
        if (!NONE_KEY.equals(selectedPack) && !availablePacks.contains(selectedPack)) {
            System.out.println("[PAUC Deferred] Previously selected pack '"
                    + selectedPack + "' no longer available, reverting to off");
            setSelectedPack(NONE_KEY);
        }
    }

    /**
     * Get all available shaderpack names. First entry is always NONE_KEY.
     */
    public static List<String> getAvailablePackNames() {
        if (!scanned) {
            refreshAvailablePacks();
        }
        List<String> result = new ArrayList<>();
        result.add(NONE_KEY);
        result.addAll(availablePacks);
        return result;
    }

    /**
     * Get the number of detected shaderpacks (excluding "(off)").
     */
    public static int getPackCount() {
        if (!scanned) {
            refreshAvailablePacks();
        }
        return availablePacks.size();
    }

    // ================================================================
    // Selection & Activation
    // ================================================================

    /**
     * Get the currently selected shaderpack name.
     */
    public static String getSelectedPack() {
        return selectedPack;
    }

    /**
     * Set the selected shaderpack and activate/deactivate the deferred pipeline.
     *
     * @param packName The shaderpack directory name, or NONE_KEY to disable
     */
    public static void setSelectedPack(String packName) {
        if (packName == null || packName.isBlank()) {
            packName = NONE_KEY;
        }

        String previousPack = selectedPack;
        DeferredWorldRenderingPipeline current = DeferredWorldRenderingPipeline.getActivePipeline();
        if (!NONE_KEY.equals(packName)
                && packName.equals(previousPack)
                && current != null
                && current.isInitialized()) {
            // Avoid hot-reloading the same pack on every allChanged() call.
            return;
        }

        selectedPack = packName;
        resetAdaptiveModeState();

        // Deactivate current pipeline
        if (current != null) {
            current.close();
        }

        // Activate new pipeline if a pack is selected
        if (!NONE_KEY.equals(packName)) {
            Path packPath = SHADERPACKS_DIR.resolve(packName);
            ActivationResult result = activateWithFallback(packPath, packName);
            if (result.activated) {
                if (result.warnings > 0) {
                    showToast("Pack warnings: " + result.warnings);
                }
                if (result.modeUsed != compatibilityMode) {
                    showToast("Deferred fallback: "
                            + compatibilityMode.getConfigKey()
                            + " -> " + result.modeUsed.getConfigKey());
                }
            } else {
                selectedPack = NONE_KEY;
                effectiveCompatibilityMode = compatibilityMode;
                showToast("Shaderpack failed: " + packName);
            }
        } else {
            effectiveCompatibilityMode = compatibilityMode;
            if (!NONE_KEY.equals(previousPack)) {
                showToast("Shaderpack: OFF");
            }
        }
    }

    /**
     * Cycle to the next available shaderpack.
     * Order: (off) → pack1 → pack2 → ... → (off)
     */
    public static void cycleShaderPack() {
        List<String> packs = getAvailablePackNames();
        if (packs.size() <= 1) {
            // Only "(off)" available, nothing to cycle
            if (!NONE_KEY.equals(selectedPack)) {
                setSelectedPack(NONE_KEY);
            }
            return;
        }

        int currentIndex = packs.indexOf(selectedPack);
        if (currentIndex < 0) currentIndex = 0;
        int nextIndex = (currentIndex + 1) % packs.size();
        setSelectedPack(packs.get(nextIndex));
    }

    /**
     * Reload the currently active shaderpack (recompile all programs).
     */
    public static void reloadCurrentPack() {
        if (NONE_KEY.equals(selectedPack)) return;

        String current = selectedPack;
        setSelectedPack(NONE_KEY); // Deactivate
        refreshAvailablePacks();    // Re-scan
        setSelectedPack(current);   // Reactivate
    }

    /**
     * Open the shaderpacks folder in the system file browser.
     */
    public static void openShaderPackFolder() {
        initializePackFolder();
        Util.getPlatform().openFile(SHADERPACKS_DIR.toFile());
    }

    // ================================================================
    // Status
    // ================================================================

    /**
     * Whether the deferred shader pipeline is currently active.
     */
    public static boolean isPipelineActive() {
        return DeferredWorldRenderingPipeline.isShaderActive();
    }

    /**
     * Ensure the selected deferred pack is actually activated when rendering starts.
     * This is a safety net in case initial config activation happened before the
     * render pipeline was fully ready.
     */
    public static void ensureSelectedPackActivated() {
        if (NONE_KEY.equals(selectedPack)) {
            return;
        }

        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            return;
        }

        long now = System.nanoTime();
        if (lastEnsureAttemptNanos != 0L && now - lastEnsureAttemptNanos < ENSURE_RETRY_INTERVAL_NANOS) {
            return;
        }
        lastEnsureAttemptNanos = now;
        setSelectedPack(selectedPack);
    }

    public static void onClientTick() {
        if (adaptiveModeSwitchCooldownTicks > 0) {
            adaptiveModeSwitchCooldownTicks--;
        }

        adaptiveModeTickCounter++;
        if (adaptiveModeTickCounter < ADAPTIVE_MODE_TICK_INTERVAL) {
            return;
        }
        adaptiveModeTickCounter = 0;

        if (NONE_KEY.equals(selectedPack) || !isPipelineActive()) {
            resetAdaptiveModeState();
            return;
        }

        DeferredCompatibilityMode targetMode = resolveAdaptiveTargetMode();
        if (targetMode == effectiveCompatibilityMode) {
            adaptiveStressTicks = 0;
            adaptiveRecoveryTicks = Math.min(2000, adaptiveRecoveryTicks + ADAPTIVE_MODE_TICK_INTERVAL);
            return;
        }

        if (adaptiveModeSwitchCooldownTicks > 0) {
            return;
        }

        boolean downgrade = isLowerQualityThan(targetMode, effectiveCompatibilityMode);
        if (downgrade) {
            adaptiveStressTicks = Math.min(2000, adaptiveStressTicks + ADAPTIVE_MODE_TICK_INTERVAL);
            adaptiveRecoveryTicks = 0;
            if (adaptiveStressTicks < ADAPTIVE_STRESS_CONFIRM_TICKS) {
                return;
            }
        } else {
            adaptiveRecoveryTicks = Math.min(2000, adaptiveRecoveryTicks + ADAPTIVE_MODE_TICK_INTERVAL);
            adaptiveStressTicks = 0;
            if (adaptiveRecoveryTicks < ADAPTIVE_RECOVERY_CONFIRM_TICKS) {
                return;
            }
        }

        DeferredCompatibilityMode previousMode = effectiveCompatibilityMode;
        boolean switched = trySwitchToMode(targetMode);
        adaptiveModeSwitchCooldownTicks = ADAPTIVE_MODE_SWITCH_COOLDOWN_TICKS;
        adaptiveStressTicks = 0;
        adaptiveRecoveryTicks = 0;
        if (switched && previousMode != effectiveCompatibilityMode) {
            System.out.println("[PAUC Deferred] Adaptive mode "
                    + previousMode.getConfigKey() + " -> " + effectiveCompatibilityMode.getConfigKey()
                    + " (preferred=" + compatibilityMode.getConfigKey()
                    + ", governor=" + GlobalPerformanceGovernor.getMode()
                    + ", pressure=" + GlobalPerformanceGovernor.getGlobalPressure()
                    + ", latency=" + LatencyController.getPressureLevel()
                    + ", bottleneck=" + BottleneckController.getState() + ")");
        }
    }

    /**
     * Get a display label for the current state.
     */
    public static String getStatusLabel() {
        if (isPipelineActive()) {
            DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
            if (pipeline != null) {
                return selectedPack + " [" + compatibilityMode.getConfigKey() + "] (" + pipeline.getDebugString() + ")";
            }
            return selectedPack;
        }
        return NONE_KEY;
    }

    /**
     * Get a short label suitable for UI buttons.
     */
    public static String getShortLabel() {
        if (NONE_KEY.equals(selectedPack)) {
            return "OFF";
        }
        // Truncate long names for the button
        if (selectedPack.length() > 24) {
            return selectedPack.substring(0, 21) + "...";
        }
        return selectedPack;
    }

    public static DeferredCompatibilityMode getCompatibilityMode() {
        return compatibilityMode;
    }

    public static DeferredCompatibilityMode getEffectiveCompatibilityMode() {
        return effectiveCompatibilityMode;
    }

    public static String getCompatibilityLabel() {
        if (isPipelineActive() && compatibilityMode != effectiveCompatibilityMode) {
            return compatibilityMode.getConfigKey() + "->" + effectiveCompatibilityMode.getConfigKey();
        }
        return compatibilityMode.getConfigKey();
    }

    public static void cycleCompatibilityMode() {
        DeferredCompatibilityMode[] modes = DeferredCompatibilityMode.values();
        int nextIndex = (compatibilityMode.ordinal() + 1) % modes.length;
        setCompatibilityMode(modes[nextIndex]);
    }

    public static void setCompatibilityMode(DeferredCompatibilityMode mode) {
        DeferredCompatibilityMode resolvedMode = mode == null ? DeferredCompatibilityMode.BALANCED : mode;
        if (compatibilityMode == resolvedMode) {
            return;
        }

        compatibilityMode = resolvedMode;
        resetAdaptiveModeState();
        ShaderPackLoader.setCompatibilityMode(compatibilityMode);
        showToast("Deferred mode: " + compatibilityMode.getConfigKey());

        if (isPipelineActive() && !NONE_KEY.equals(selectedPack)) {
            reloadCurrentPack();
        }
    }

    // ================================================================
    // Config persistence
    // ================================================================

    /**
     * Get the key to save in config.
     */
    public static String getConfigKey() {
        return selectedPack;
    }

    public static String getCompatibilityConfigKey() {
        return compatibilityMode.getConfigKey();
    }

    /**
     * Restore from config on startup. Does NOT activate the pipeline yet
     * — call activateFromConfig() after GL context is ready.
     */
    public static void setConfigKey(String key) {
        if (key == null || key.isBlank()) {
            selectedPack = NONE_KEY;
        } else {
            selectedPack = key;
        }
    }

    public static void setCompatibilityConfigKey(String key) {
        compatibilityMode = DeferredCompatibilityMode.fromConfigKey(key, DeferredCompatibilityMode.BALANCED);
        effectiveCompatibilityMode = compatibilityMode;
        resetAdaptiveModeState();
        ShaderPackLoader.setCompatibilityMode(compatibilityMode);
    }

    /**
     * Activate the saved config shaderpack. Call after GL context is ready
     * and resource packs are loaded.
     */
    public static void activateFromConfig() {
        ShaderPackLoader.setCompatibilityMode(compatibilityMode);
        if (!NONE_KEY.equals(selectedPack)) {
            refreshAvailablePacks();
            if (availablePacks.contains(selectedPack)) {
                setSelectedPack(selectedPack);
            } else {
                System.out.println("[PAUC Deferred] Saved shaderpack '"
                        + selectedPack + "' not found, disabling");
                selectedPack = NONE_KEY;
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static void showToast(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[PauC] " + message), true);
        }
    }

    private static ActivationResult activateWithFallback(Path packPath, String packName) {
        Exception lastException = null;
        for (DeferredCompatibilityMode mode : buildModeAttempts()) {
            ShaderPackLoader.setCompatibilityMode(mode);
            try {
                DeferredWorldRenderingPipeline pipeline = new DeferredWorldRenderingPipeline(packPath);
                if (!pipeline.isInitialized()) {
                    pipeline.close();
                    continue;
                }

                pipeline.activate();
                effectiveCompatibilityMode = mode;
                System.out.println("[PAUC Deferred] Activated shaderpack: "
                        + packName + " (mode=" + mode.getConfigKey() + ")");
                showToast("Shaderpack: " + packName);
                ShaderPackLoader.ShaderPack loadedPack = pipeline.getShaderPack();
                int warnings = loadedPack == null ? 0 : loadedPack.warnings.size();
                return new ActivationResult(true, mode, warnings);
            } catch (Exception exception) {
                lastException = exception;
            }
        }

        if (lastException != null) {
            System.err.println("[PAUC Deferred] Error loading shaderpack '" + packName + "': " + lastException.getMessage());
        } else {
            System.err.println("[PAUC Deferred] Failed to initialize shaderpack: " + packName);
        }
        return new ActivationResult(false, compatibilityMode, 0);
    }

    private static DeferredCompatibilityMode[] buildModeAttempts() {
        return switch (compatibilityMode) {
            case STRICT -> new DeferredCompatibilityMode[]{
                    DeferredCompatibilityMode.STRICT,
                    DeferredCompatibilityMode.BALANCED,
                    DeferredCompatibilityMode.FAST
            };
            case BALANCED -> new DeferredCompatibilityMode[]{
                    DeferredCompatibilityMode.BALANCED,
                    DeferredCompatibilityMode.FAST
            };
            case FAST -> new DeferredCompatibilityMode[]{DeferredCompatibilityMode.FAST};
        };
    }

    private static DeferredCompatibilityMode resolveAdaptiveTargetMode() {
        DeferredCompatibilityMode preferredMode = compatibilityMode;
        if (preferredMode == DeferredCompatibilityMode.FAST) {
            return DeferredCompatibilityMode.FAST;
        }

        GlobalPerformanceMode governorMode = GlobalPerformanceGovernor.getMode();
        int pressure = GlobalPerformanceGovernor.getGlobalPressure();
        int latencyPressure = LatencyController.getPressureLevel();
        boolean gpuBound = BottleneckController.isGpuBound();
        boolean cpuBound = BottleneckController.isCpuBound();

        boolean severeStress = governorMode == GlobalPerformanceMode.CRISIS
                || pressure >= 3
                || latencyPressure >= 2
                || gpuBound;
        boolean combatCpuStress = governorMode == GlobalPerformanceMode.COMBAT
                && cpuBound
                && (pressure >= 1 || latencyPressure >= 1);
        boolean combatModerateStress = governorMode == GlobalPerformanceMode.COMBAT
                && (pressure >= 2 || latencyPressure >= 1);

        return switch (preferredMode) {
            case STRICT -> {
                if (severeStress || combatCpuStress) {
                    yield DeferredCompatibilityMode.FAST;
                }
                if (combatModerateStress || pressure >= 2) {
                    yield DeferredCompatibilityMode.BALANCED;
                }
                yield DeferredCompatibilityMode.STRICT;
            }
            case BALANCED -> {
                if (severeStress || combatCpuStress) {
                    yield DeferredCompatibilityMode.FAST;
                }
                yield DeferredCompatibilityMode.BALANCED;
            }
            case FAST -> DeferredCompatibilityMode.FAST;
        };
    }

    private static boolean trySwitchToMode(DeferredCompatibilityMode targetMode) {
        if (targetMode == null || targetMode == effectiveCompatibilityMode || NONE_KEY.equals(selectedPack)) {
            return true;
        }

        Path packPath = SHADERPACKS_DIR.resolve(selectedPack);
        if (!Files.exists(packPath)) {
            refreshAvailablePacks();
            packPath = SHADERPACKS_DIR.resolve(selectedPack);
            if (!Files.exists(packPath)) {
                return false;
            }
        }

        DeferredWorldRenderingPipeline current = DeferredWorldRenderingPipeline.getActivePipeline();
        if (current != null) {
            current.close();
        }

        ShaderPackLoader.setCompatibilityMode(targetMode);
        try {
            DeferredWorldRenderingPipeline pipeline = new DeferredWorldRenderingPipeline(packPath);
            if (!pipeline.isInitialized()) {
                pipeline.close();
                throw new IllegalStateException("pipeline not initialized");
            }
            pipeline.activate();
            effectiveCompatibilityMode = targetMode;
            return true;
        } catch (Exception switchException) {
            ShaderPackLoader.setCompatibilityMode(compatibilityMode);
            ActivationResult fallbackResult = activateWithFallback(packPath, selectedPack);
            if (!fallbackResult.activated) {
                System.err.println("[PAUC Deferred] Adaptive switch to "
                        + targetMode.getConfigKey()
                        + " failed for pack "
                        + selectedPack
                        + ": "
                        + switchException.getMessage());
                return false;
            }
            return true;
        }
    }

    private static boolean isLowerQualityThan(DeferredCompatibilityMode candidate, DeferredCompatibilityMode baseline) {
        return modeRank(candidate) < modeRank(baseline);
    }

    private static int modeRank(DeferredCompatibilityMode mode) {
        return switch (mode) {
            case FAST -> 1;
            case BALANCED -> 2;
            case STRICT -> 3;
        };
    }

    private static void resetAdaptiveModeState() {
        adaptiveModeTickCounter = 0;
        adaptiveModeSwitchCooldownTicks = 0;
        adaptiveStressTicks = 0;
        adaptiveRecoveryTicks = 0;
    }

    private record ActivationResult(boolean activated, DeferredCompatibilityMode modeUsed, int warnings) {
    }
}
