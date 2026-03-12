package pauc.pain_au_choc;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class PauCClient {
    private static final int DEFAULT_QUALITY_LEVEL = 7;
    private static final int MIN_QUALITY_LEVEL = 1;
    private static final int MAX_QUALITY_LEVEL = 10;
    private static final int QUALITY_CYCLE_COOLDOWN_TICKS = 8;
    private static final int MIN_CPU_INVOLVEMENT_LEVEL = 1;
    private static final int MAX_CPU_INVOLVEMENT_LEVEL = 3;
    private static final boolean DEFAULT_DYNAMIC_RESOLUTION_ENABLED = true;
    private static final double DEFAULT_DYNAMIC_RESOLUTION_MIN_SCALE = 0.70D;
    private static final boolean DEFAULT_ADAPTIVE_SIMULATION_DISTANCE_ENABLED = true;
    private static final int DEFAULT_CPU_INVOLVEMENT_LEVEL = 3;
    private static final boolean DEFAULT_FRAME_TIME_STABILIZER_ENABLED = true;
    private static final boolean DEFAULT_GPU_BOTTLENECK_DETECTOR_ENABLED = true;
    private static final boolean DEFAULT_ADVANCED_SHARPENING_ENABLED = true;
    private static final double DEFAULT_ADVANCED_SHARPENING_STRENGTH = 0.40D;
    private static final boolean DEFAULT_AUTHORITATIVE_RUNTIME_ENABLED = true;
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("pauc_ultimate_de_ouf.properties");

    private static boolean enabled = true;
    private static int qualityLevel = DEFAULT_QUALITY_LEVEL;
    private static boolean dynamicResolutionEnabled = DEFAULT_DYNAMIC_RESOLUTION_ENABLED;
    private static double dynamicResolutionMinScale = DEFAULT_DYNAMIC_RESOLUTION_MIN_SCALE;
    private static boolean adaptiveSimulationDistanceEnabled = DEFAULT_ADAPTIVE_SIMULATION_DISTANCE_ENABLED;
    private static int cpuInvolvementLevel = DEFAULT_CPU_INVOLVEMENT_LEVEL;
    private static boolean frameTimeStabilizerEnabled = DEFAULT_FRAME_TIME_STABILIZER_ENABLED;
    private static boolean gpuBottleneckDetectorEnabled = DEFAULT_GPU_BOTTLENECK_DETECTOR_ENABLED;
    private static boolean advancedSharpeningEnabled = DEFAULT_ADVANCED_SHARPENING_ENABLED;
    private static double advancedSharpeningStrength = DEFAULT_ADVANCED_SHARPENING_STRENGTH;
    private static boolean authoritativeRuntimeEnabled = DEFAULT_AUTHORITATIVE_RUNTIME_ENABLED;
    private static QualityBudgetProfile activeProfile = QualityBudgetProfile.forLevel(DEFAULT_QUALITY_LEVEL);
    private static boolean budgetActive;
    private static boolean initialized;
    private static boolean runtimePoliciesDirty = true;
    private static int qualityCycleCooldownTicks;

    private static KeyMapping toggleKey;
    private static KeyMapping cycleQualityKey;
    private static KeyMapping openMenuKey;

    private PauCClient() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        AuthoritativeRuntimeController.initialize();
        CompatibilityGuards.logDetectedStack();
        loadConfig();
        refreshBudgetState();
        PauCShaderManager.initializeShaderFolder();
        runtimePoliciesDirty = true;
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        toggleKey = new KeyMapping("key.pauc.toggle", GLFW.GLFW_KEY_F8, "key.categories.pauc");
        cycleQualityKey = new KeyMapping("key.pauc.cycle_quality", GLFW.GLFW_KEY_F9, "key.categories.pauc");
        openMenuKey = new KeyMapping("key.pauc.open_menu", GLFW.GLFW_KEY_F10, "key.categories.pauc");
        event.register(toggleKey);
        event.register(cycleQualityKey);
        event.register(openMenuKey);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (qualityCycleCooldownTicks > 0) {
            qualityCycleCooldownTicks--;
        }

        if (runtimePoliciesDirty) {
            VideoSettingsController.syncWithBudget(enabled, qualityLevel);
            runtimePoliciesDirty = false;
        }

        LatencyController.tick();
        BottleneckController.onClientTick();
        AuthoritativeRuntimeController.onClientTick();
        GlobalPerformanceGovernor.onClientTick();
        ShadowDistanceGovernor.onClientTick();
        DynamicResolutionController.onClientTick();
        ParticleBudgetController.onClientTick();
        AdaptiveSimulationDistanceController.onClientTick();
        StructureStreamingController.tick();
        TerrainProxyController.tick();
        EntitySpatialIndex.tick();
        EntityLodController.onClientTick();
        ChunkBuildQueueController.onClientTick();

        if (isSimplificationActive()) {
            ClientWorldCleanupController.tick();
        }

        if (toggleKey != null && toggleKey.consumeClick()) {
            setEnabled(!enabled);
            saveConfig();
            showStatusMessage(enabled ? "Pain au Choc ultimate de Ouf actif" : "Pain au Choc ultimate de Ouf inactif");
        }

        if (cycleQualityKey != null && cycleQualityKey.consumeClick()) {
            if (qualityCycleCooldownTicks == 0) {
                cycleQualityLevel();
                saveConfig();
                qualityCycleCooldownTicks = QUALITY_CYCLE_COOLDOWN_TICKS;
            }
        }

        if (openMenuKey != null && openMenuKey.consumeClick()) {
            openConfigScreen();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isAuthoritativeRuntimeEnabled() {
        return authoritativeRuntimeEnabled;
    }

    public static boolean isBudgetActive() {
        return budgetActive;
    }

    public static boolean isSimplificationActive() {
        return enabled && qualityLevel < MAX_QUALITY_LEVEL;
    }

    public static void setEnabled(boolean enabledIn) {
        enabled = enabledIn;
        refreshBudgetState();
        if (!enabled) {
            PauCPipeline.dispose();
            DynamicResolutionController.reset();
            ParticleBudgetController.reset();
            AdaptiveSimulationDistanceController.reset();
            ShadowDistanceGovernor.reset();
            TerrainProxyController.reset();
        }
        qualityCycleCooldownTicks = 0;
        AdaptiveFrameCapController.reset();
        runtimePoliciesDirty = true;
        Pain_au_Choc.LOGGER.info("PauC enabled={}", enabled);
    }

    public static int getQualityLevel() {
        return qualityLevel;
    }

    public static QualityBudgetProfile getActiveProfile() {
        return activeProfile;
    }

    public static void setQualityLevel(int level) {
        int clampedLevel = clampQualityLevel(level);
        if (qualityLevel == clampedLevel) {
            return;
        }

        qualityLevel = clampedLevel;
        refreshBudgetState();
        PauCPipeline.dispose();
        DynamicResolutionController.reset();
        ParticleBudgetController.reset();
        AdaptiveSimulationDistanceController.reset();
        ShadowDistanceGovernor.reset();
        TerrainProxyController.reset();
        qualityCycleCooldownTicks = 0;
        AdaptiveFrameCapController.reset();
        runtimePoliciesDirty = true;
        Pain_au_Choc.LOGGER.info("PauC qualityLevel={}", qualityLevel);
    }

    public static int getMinQualityLevel() {
        return MIN_QUALITY_LEVEL;
    }

    public static int getMaxQualityLevel() {
        return MAX_QUALITY_LEVEL;
    }

    public static String getQualityLabel() {
        if (qualityLevel <= MIN_QUALITY_LEVEL) {
            return "1 Pizza Ananas pourrie";
        }

        if (qualityLevel >= MAX_QUALITY_LEVEL) {
            return "10 Frite parfaite";
        }

        return qualityLevel + "/" + MAX_QUALITY_LEVEL;
    }

    public static boolean isDynamicResolutionActive() {
        return enabled
                && budgetActive
                && dynamicResolutionEnabled
                && !CompatibilityGuards.shouldDisableDynamicResolution();
    }

    public static double getDynamicResolutionMinScale() {
        return GlobalPerformanceGovernor.getEffectiveDynamicResolutionMinScale(dynamicResolutionMinScale);
    }

    public static boolean isAdaptiveSimulationDistanceActive() {
        return enabled && budgetActive && adaptiveSimulationDistanceEnabled;
    }

    public static int getCpuInvolvementLevel() {
        return cpuInvolvementLevel;
    }

    public static void setCpuInvolvementLevel(int level) {
        cpuInvolvementLevel = clampCpuInvolvementLevel(level);
    }

    public static int getMinCpuInvolvementLevel() {
        return MIN_CPU_INVOLVEMENT_LEVEL;
    }

    public static int getMaxCpuInvolvementLevel() {
        return MAX_CPU_INVOLVEMENT_LEVEL;
    }

    public static boolean isFrameTimeStabilizerActive() {
        return enabled && budgetActive && frameTimeStabilizerEnabled;
    }

    public static boolean isFrameTimeStabilizerEnabled() {
        return frameTimeStabilizerEnabled;
    }

    public static void setFrameTimeStabilizerEnabled(boolean enabledIn) {
        frameTimeStabilizerEnabled = enabledIn;
        LatencyController.reset();
    }

    public static boolean isGpuBottleneckDetectorActive() {
        return enabled && budgetActive && gpuBottleneckDetectorEnabled;
    }

    public static boolean isGpuBottleneckDetectorEnabled() {
        return gpuBottleneckDetectorEnabled;
    }

    public static void setGpuBottleneckDetectorEnabled(boolean enabledIn) {
        gpuBottleneckDetectorEnabled = enabledIn;
        BottleneckController.reset();
    }

    public static boolean isAdvancedSharpeningActive() {
        return enabled
                && budgetActive
                && advancedSharpeningEnabled
                && !CompatibilityGuards.shouldDisableAdvancedSharpening();
    }

    public static boolean isAdvancedSharpeningEnabled() {
        return advancedSharpeningEnabled;
    }

    public static void setAdvancedSharpeningEnabled(boolean enabledIn) {
        advancedSharpeningEnabled = enabledIn;
    }

    public static double getAdvancedSharpeningStrength() {
        return advancedSharpeningStrength;
    }

    public static void setAdvancedSharpeningStrength(double strength) {
        advancedSharpeningStrength = clampAdvancedSharpeningStrength(strength);
    }

    public static void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("qualityLevel", Integer.toString(qualityLevel));
        properties.setProperty("dynamicResolutionEnabled", Boolean.toString(dynamicResolutionEnabled));
        properties.setProperty("dynamicResolutionMinScale", Double.toString(dynamicResolutionMinScale));
        properties.setProperty("adaptiveSimulationDistanceEnabled", Boolean.toString(adaptiveSimulationDistanceEnabled));
        properties.setProperty("cpuInvolvementLevel", Integer.toString(cpuInvolvementLevel));
        properties.setProperty("frameTimeStabilizerEnabled", Boolean.toString(frameTimeStabilizerEnabled));
        properties.setProperty("gpuBottleneckDetectorEnabled", Boolean.toString(gpuBottleneckDetectorEnabled));
        properties.setProperty("advancedSharpeningEnabled", Boolean.toString(advancedSharpeningEnabled));
        properties.setProperty("advancedSharpeningStrength", Double.toString(advancedSharpeningStrength));
        properties.setProperty("authoritativeRuntimeEnabled", Boolean.toString(authoritativeRuntimeEnabled));
        properties.setProperty("activeShaderKey", PauCShaderManager.getActiveShaderKey());

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (OutputStream outputStream = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(outputStream, "Pain au Choc ultimate de Ouf client settings");
            }
        } catch (IOException exception) {
            Pain_au_Choc.LOGGER.warn("Failed to save PauC config {}", CONFIG_PATH, exception);
        }
    }

    private static void loadConfig() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(CONFIG_PATH)) {
            properties.load(inputStream);
            enabled = Boolean.parseBoolean(properties.getProperty("enabled", Boolean.toString(enabled)));
            qualityLevel = clampQualityLevel(parseQualityLevel(properties));
            dynamicResolutionEnabled = parseDynamicResolutionEnabled(properties);
            dynamicResolutionMinScale = parseDynamicResolutionMinScale(properties);
            adaptiveSimulationDistanceEnabled = parseAdaptiveSimulationDistanceEnabled(properties);
            cpuInvolvementLevel = parseCpuInvolvementLevel(properties);
            frameTimeStabilizerEnabled = parseFrameTimeStabilizerEnabled(properties);
            gpuBottleneckDetectorEnabled = parseGpuBottleneckDetectorEnabled(properties);
            advancedSharpeningEnabled = parseAdvancedSharpeningEnabled(properties);
            advancedSharpeningStrength = parseAdvancedSharpeningStrength(properties);
            authoritativeRuntimeEnabled = parseAuthoritativeRuntimeEnabled(properties);
            PauCShaderManager.setActiveShaderKey(properties.getProperty("activeShaderKey", PauCShaderManager.getDefaultShaderKey()));
        } catch (IOException exception) {
            Pain_au_Choc.LOGGER.warn("Failed to load PauC config {}", CONFIG_PATH, exception);
        }
    }

    private static void cycleQualityLevel() {
        int nextLevel = qualityLevel <= MIN_QUALITY_LEVEL ? MAX_QUALITY_LEVEL : qualityLevel - 1;
        setQualityLevel(nextLevel);
        showStatusMessage("Pain au Choc ultimate de Ouf qualite : " + getQualityLabel());
    }

    private static int clampQualityLevel(int level) {
        return Math.max(MIN_QUALITY_LEVEL, Math.min(MAX_QUALITY_LEVEL, level));
    }

    private static int parseQualityLevel(Properties properties) {
        String qualityValue = properties.getProperty("qualityLevel");
        if (qualityValue != null && !qualityValue.isBlank()) {
            try {
                return Integer.parseInt(qualityValue);
            } catch (NumberFormatException ignored) {
            }
        }

        String legacyRenderScale = properties.getProperty("renderScale");
        if (legacyRenderScale != null && !legacyRenderScale.isBlank()) {
            try {
                float scale = Float.parseFloat(legacyRenderScale);
                return clampQualityLevel(Math.round(scale * 10.0F));
            } catch (NumberFormatException ignored) {
            }
        }

        return qualityLevel;
    }

    private static boolean parseDynamicResolutionEnabled(Properties properties) {
        String value = properties.getProperty("dynamicResolutionEnabled");
        if (value == null || value.isBlank()) {
            return dynamicResolutionEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static double parseDynamicResolutionMinScale(Properties properties) {
        String value = properties.getProperty("dynamicResolutionMinScale");
        if (value == null || value.isBlank()) {
            return dynamicResolutionMinScale;
        }

        try {
            double parsed = Double.parseDouble(value);
            return clampDynamicResolutionScale(parsed);
        } catch (NumberFormatException ignored) {
            return dynamicResolutionMinScale;
        }
    }

    private static double clampDynamicResolutionScale(double value) {
        return Math.max(0.50D, Math.min(1.00D, value));
    }

    private static boolean parseAdaptiveSimulationDistanceEnabled(Properties properties) {
        String value = properties.getProperty("adaptiveSimulationDistanceEnabled");
        if (value == null || value.isBlank()) {
            return adaptiveSimulationDistanceEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static int clampCpuInvolvementLevel(int level) {
        return Math.max(MIN_CPU_INVOLVEMENT_LEVEL, Math.min(MAX_CPU_INVOLVEMENT_LEVEL, level));
    }

    private static int parseCpuInvolvementLevel(Properties properties) {
        String value = properties.getProperty("cpuInvolvementLevel");
        if (value == null || value.isBlank()) {
            return cpuInvolvementLevel;
        }

        try {
            return clampCpuInvolvementLevel(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return cpuInvolvementLevel;
        }
    }

    private static boolean parseFrameTimeStabilizerEnabled(Properties properties) {
        String value = properties.getProperty("frameTimeStabilizerEnabled");
        if (value == null || value.isBlank()) {
            return frameTimeStabilizerEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean parseGpuBottleneckDetectorEnabled(Properties properties) {
        String value = properties.getProperty("gpuBottleneckDetectorEnabled");
        if (value == null || value.isBlank()) {
            return gpuBottleneckDetectorEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean parseAdvancedSharpeningEnabled(Properties properties) {
        String value = properties.getProperty("advancedSharpeningEnabled");
        if (value == null || value.isBlank()) {
            return advancedSharpeningEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static double parseAdvancedSharpeningStrength(Properties properties) {
        String value = properties.getProperty("advancedSharpeningStrength");
        if (value == null || value.isBlank()) {
            return advancedSharpeningStrength;
        }

        try {
            return clampAdvancedSharpeningStrength(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return advancedSharpeningStrength;
        }
    }

    private static boolean parseAuthoritativeRuntimeEnabled(Properties properties) {
        String value = properties.getProperty("authoritativeRuntimeEnabled");
        if (value == null || value.isBlank()) {
            return authoritativeRuntimeEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static double clampAdvancedSharpeningStrength(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public static void onClientEntityJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() || !isSimplificationActive()) {
            return;
        }

        if (!RenderBudgetManager.shouldAcceptClientSpawn(event.getEntity())) {
            if (Pain_au_Choc.LOGGER.isDebugEnabled()) {
                Pain_au_Choc.LOGGER.debug("PauC pruned client entity spawn: {}", event.getEntity().getType());
            }
            event.setCanceled(true);
        }
    }

    private static void openConfigScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PauCConfigScreen configScreen) {
            configScreen.onClose();
            return;
        }

        Screen parent = minecraft.screen;
        minecraft.setScreen(new PauCConfigScreen(parent));
    }

    private static void showStatusMessage(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static void refreshBudgetState() {
        activeProfile = QualityBudgetProfile.forLevel(qualityLevel);
        budgetActive = enabled;
    }
}


