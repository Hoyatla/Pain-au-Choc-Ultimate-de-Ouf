package pauc.pain_au_choc.render.shader;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

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

    /** Cached list of available shaderpacks. */
    private static List<String> availablePacks = new ArrayList<>();

    /** Whether the packs list has been scanned. */
    private static boolean scanned = false;

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
        selectedPack = packName;

        // Deactivate current pipeline
        DeferredWorldRenderingPipeline current = DeferredWorldRenderingPipeline.getActivePipeline();
        if (current != null) {
            current.close();
        }

        // Activate new pipeline if a pack is selected
        if (!NONE_KEY.equals(packName)) {
            Path packPath = SHADERPACKS_DIR.resolve(packName);
            try {
                DeferredWorldRenderingPipeline pipeline = new DeferredWorldRenderingPipeline(packPath);
                if (pipeline.isInitialized()) {
                    pipeline.activate();
                    System.out.println("[PAUC Deferred] Activated shaderpack: " + packName);
                    showToast("Shaderpack: " + packName);
                } else {
                    System.err.println("[PAUC Deferred] Failed to initialize shaderpack: " + packName);
                    pipeline.close();
                    selectedPack = NONE_KEY;
                    showToast("Shaderpack failed: " + packName);
                }
            } catch (Exception e) {
                System.err.println("[PAUC Deferred] Error loading shaderpack '"
                        + packName + "': " + e.getMessage());
                selectedPack = NONE_KEY;
                showToast("Shaderpack error: " + packName);
            }
        } else {
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
     * Get a display label for the current state.
     */
    public static String getStatusLabel() {
        if (isPipelineActive()) {
            DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
            if (pipeline != null) {
                return selectedPack + " (" + pipeline.getDebugString() + ")";
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

    // ================================================================
    // Config persistence
    // ================================================================

    /**
     * Get the key to save in config.
     */
    public static String getConfigKey() {
        return selectedPack;
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

    /**
     * Activate the saved config shaderpack. Call after GL context is ready
     * and resource packs are loaded.
     */
    public static void activateFromConfig() {
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
}
