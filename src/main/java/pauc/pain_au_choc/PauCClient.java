package pauc.pain_au_choc;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

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
    private static final int RUNTIME_POLICY_SYNC_INTERVAL_TICKS = 20;
    private static final int MIN_CPU_INVOLVEMENT_LEVEL = 1;
    private static final int MAX_CPU_INVOLVEMENT_LEVEL = 3;
    private static final boolean DEFAULT_DYNAMIC_RESOLUTION_ENABLED = true;
    private static final double DEFAULT_DYNAMIC_RESOLUTION_MIN_SCALE = 0.70D;
    private static final boolean DEFAULT_ADAPTIVE_SIMULATION_DISTANCE_ENABLED = true;
    private static final boolean DEFAULT_ADAPTIVE_QUALITY_ENABLED = true;
    private static final int DEFAULT_CPU_INVOLVEMENT_LEVEL = 3;
    private static final boolean DEFAULT_FRAME_TIME_STABILIZER_ENABLED = true;
    private static final boolean DEFAULT_GPU_BOTTLENECK_DETECTOR_ENABLED = true;
    private static final boolean DEFAULT_ADVANCED_SHARPENING_ENABLED = true;
    private static final double DEFAULT_ADVANCED_SHARPENING_STRENGTH = 0.40D;
    private static final boolean DEFAULT_AUTHORITATIVE_RUNTIME_ENABLED = true;
    private static final PauCUserPreset DEFAULT_USER_PRESET = PauCUserPreset.BALANCED;
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("pauc_ultimate_de_ouf.properties");

    private static boolean enabled = true;
    private static int qualityLevel = DEFAULT_QUALITY_LEVEL;
    private static boolean dynamicResolutionEnabled = DEFAULT_DYNAMIC_RESOLUTION_ENABLED;
    private static double dynamicResolutionMinScale = DEFAULT_DYNAMIC_RESOLUTION_MIN_SCALE;
    private static boolean adaptiveSimulationDistanceEnabled = DEFAULT_ADAPTIVE_SIMULATION_DISTANCE_ENABLED;
    private static boolean adaptiveQualityEnabled = DEFAULT_ADAPTIVE_QUALITY_ENABLED;
    private static int cpuInvolvementLevel = DEFAULT_CPU_INVOLVEMENT_LEVEL;
    private static boolean frameTimeStabilizerEnabled = DEFAULT_FRAME_TIME_STABILIZER_ENABLED;
    private static boolean gpuBottleneckDetectorEnabled = DEFAULT_GPU_BOTTLENECK_DETECTOR_ENABLED;
    private static boolean advancedSharpeningEnabled = DEFAULT_ADVANCED_SHARPENING_ENABLED;
    private static double advancedSharpeningStrength = DEFAULT_ADVANCED_SHARPENING_STRENGTH;
    private static boolean authoritativeRuntimeEnabled = DEFAULT_AUTHORITATIVE_RUNTIME_ENABLED;
    private static PauCUserPreset selectedPreset = DEFAULT_USER_PRESET;
    private static QualityBudgetProfile activeProfile = QualityBudgetProfile.forLevel(DEFAULT_QUALITY_LEVEL);
    private static boolean budgetActive;
    private static int adaptiveQualityTargetLevel = DEFAULT_QUALITY_LEVEL;
    private static boolean initialized;
    private static boolean runtimePoliciesDirty = true;
    private static int runtimePolicySyncTicks;
    private static int qualityCycleCooldownTicks;

    private static KeyMapping toggleKey;
    private static KeyMapping cyclePresetKey;
    private static KeyMapping recoveryKey;
    private static KeyMapping cycleQualityKey;
    private static KeyMapping openMenuKey;

    private PauCClient() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        RuntimeStateLogger.reset();
        AuthoritativeRuntimeController.initialize();
        CompatibilityGuards.logDetectedStack();
        loadConfig();
        refreshBudgetState();
        PauCShaderManager.initializeShaderFolder();
        runtimePoliciesDirty = true;
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        cyclePresetKey = new KeyMapping("key.pauc.cycle_preset", GLFW.GLFW_KEY_F6, "key.categories.pauc");
        recoveryKey = new KeyMapping("key.pauc.recovery", GLFW.GLFW_KEY_F7, "key.categories.pauc");
        toggleKey = new KeyMapping("key.pauc.toggle", GLFW.GLFW_KEY_F8, "key.categories.pauc");
        cycleQualityKey = new KeyMapping("key.pauc.cycle_quality", GLFW.GLFW_KEY_F9, "key.categories.pauc");
        openMenuKey = new KeyMapping("key.pauc.open_menu", GLFW.GLFW_KEY_F10, "key.categories.pauc");
        event.register(cyclePresetKey);
        event.register(recoveryKey);
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

        runtimePolicySyncTicks = Math.min(10_000, runtimePolicySyncTicks + 1);

        LatencyController.tick();
        BottleneckController.onClientTick();
        AuthoritativeRuntimeController.onClientTick();
        GlobalPerformanceGovernor.onClientTick();
        AdaptiveQualityController.onClientTick();

        if (runtimePoliciesDirty || runtimePolicySyncTicks >= RUNTIME_POLICY_SYNC_INTERVAL_TICKS) {
            VideoSettingsController.syncWithBudget(enabled, qualityLevel);
            runtimePoliciesDirty = false;
            runtimePolicySyncTicks = 0;
        }

        ShadowDistanceGovernor.onClientTick();
        DynamicResolutionController.onClientTick();
        ParticleBudgetController.onClientTick();
        AdaptiveSimulationDistanceController.onClientTick();
        StructureStreamingController.tick();
        TerrainProxyController.tick();
        EntitySpatialIndex.tick();
        EntityLodController.onClientTick();
        ChunkBuildQueueController.onClientTick();
        PerformanceTelemetryRecorder.onClientTick();
        if (Minecraft.getInstance().level != null) {
            PauCDeferredShaderController.ensureSelectedPackActivated();
            PauCDeferredShaderController.onClientTick();
        }
        RuntimeStateLogger.onClientTick();

        if (isSimplificationActive()) {
            ClientWorldCleanupController.tick();
        }

        if (toggleKey != null && toggleKey.consumeClick()) {
            setEnabled(!enabled);
            saveConfig();
            showStatusMessage(enabled ? "Pain au Choc ultimate de Ouf actif" : "Pain au Choc ultimate de Ouf inactif");
        }

        if (cyclePresetKey != null && cyclePresetKey.consumeClick()) {
            cycleUserPreset();
            saveConfig();
            showStatusMessage("Preset selectionne: " + getSelectedPreset().getDisplayLabel());
        }

        if (recoveryKey != null && recoveryKey.consumeClick()) {
            activateRecoveryMode();
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

    public static void setAuthoritativeRuntimeEnabled(boolean enabledIn) {
        if (authoritativeRuntimeEnabled == enabledIn) {
            return;
        }

        authoritativeRuntimeEnabled = enabledIn;
        AuthoritativeRuntimeController.resetRuntimeState();
        DynamicResolutionController.reset();
        AdaptiveSimulationDistanceController.reset();
        TerrainProxyController.reset();
        ManagedChunkRadiusController.reset();
        StructureStreamingController.reset();
        ChunkBuildQueueController.reset();
        RuntimeStateLogger.reset();
        PerformanceTelemetryRecorder.flushNow();
        runtimePoliciesDirty = true;
        runtimePolicySyncTicks = RUNTIME_POLICY_SYNC_INTERVAL_TICKS;
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
            DynamicResolutionController.reset();
            PauCPipeline.dispose();
            ParticleBudgetController.reset();
            AdaptiveQualityController.reset();
            AdaptiveSimulationDistanceController.reset();
            ServerMobCadenceController.reset();
            ShadowDistanceGovernor.reset();
            TerrainProxyController.reset();
            ManagedChunkRadiusController.reset();
            PerformanceTelemetryRecorder.flushNow();
        }
        RuntimeStateLogger.reset();
        qualityCycleCooldownTicks = 0;
        runtimePolicySyncTicks = RUNTIME_POLICY_SYNC_INTERVAL_TICKS;
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
        applyQualityLevel(level, true, "manual");
    }

    static void setQualityLevelFromAdaptiveController(int level, String reason) {
        applyQualityLevel(level, false, reason == null ? "adaptive" : reason);
    }

    public static int getAdaptiveQualityTargetLevel() {
        return adaptiveQualityTargetLevel;
    }

    private static void applyQualityLevel(int level, boolean updateAdaptiveTarget, String source) {
        int clampedLevel = clampQualityLevel(level);
        if (qualityLevel == clampedLevel) {
            if (updateAdaptiveTarget) {
                adaptiveQualityTargetLevel = clampedLevel;
            }
            return;
        }

        qualityLevel = clampedLevel;
        if (updateAdaptiveTarget) {
            adaptiveQualityTargetLevel = clampedLevel;
        }

        refreshBudgetState();

        DynamicResolutionController.reset();
        ParticleBudgetController.reset();
        ShadowDistanceGovernor.reset();
        AdaptiveFrameCapController.reset();

        if (updateAdaptiveTarget) {
            PauCPipeline.resetForHotSettingsChange();
            AdaptiveQualityController.reset();
            AdaptiveSimulationDistanceController.reset();
            ServerMobCadenceController.reset();
            TerrainProxyController.reset();
            RuntimeStateLogger.reset();
        }

        PerformanceTelemetryRecorder.flushNow();
        qualityCycleCooldownTicks = 0;
        runtimePolicySyncTicks = RUNTIME_POLICY_SYNC_INTERVAL_TICKS;
        runtimePoliciesDirty = true;

        if (updateAdaptiveTarget) {
            Pain_au_Choc.LOGGER.info("PauC qualityLevel={} (source={})", qualityLevel, source);
        } else {
            Pain_au_Choc.LOGGER.info(
                    "PauC adaptive qualityLevel={} (reason={}, target={})",
                    qualityLevel,
                    source,
                    adaptiveQualityTargetLevel
            );
        }
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

    public static PauCUserPreset getSelectedPreset() {
        return selectedPreset;
    }

    public static void cycleUserPreset() {
        selectedPreset = selectedPreset.next();
    }

    public static void setSelectedPreset(PauCUserPreset preset) {
        selectedPreset = preset == null ? DEFAULT_USER_PRESET : preset;
    }

    public static void applySelectedPreset() {
        applyPreset(selectedPreset);
    }

    public static void applyPreset(PauCUserPreset preset) {
        applyPresetInternal(preset, false, false);
    }

    public static void activateRecoveryMode() {
        applyPresetInternal(PauCUserPreset.SAFE, true, true);
    }

    public static boolean isDynamicResolutionActive() {
        return enabled
                && budgetActive
                && dynamicResolutionEnabled
                && !CompatibilityGuards.shouldDisableDynamicResolution();
    }

    public static boolean isDynamicResolutionSettingEnabled() {
        return dynamicResolutionEnabled;
    }

    public static void setDynamicResolutionEnabled(boolean enabledIn) {
        if (dynamicResolutionEnabled == enabledIn) {
            return;
        }

        dynamicResolutionEnabled = enabledIn;
        DynamicResolutionController.reset();
        RuntimeStateLogger.reset();
    }

    public static String getDynamicResolutionRuntimeReason() {
        if (!enabled) {
            return "PauC off";
        }
        if (!budgetActive) {
            return "runtime off";
        }
        if (!dynamicResolutionEnabled) {
            return "setting off";
        }
        if (AuthoritativeRuntimeController.shouldForceDisableDynamicResolution()) {
            return "capture pipeline contested";
        }
        if (AuthoritativeRuntimeController.shouldForceDisableDynamicResolutionForDeferredPipeline()) {
            return "deferred pipeline safety";
        }
        if (AuthoritativeRuntimeController.shouldYieldDynamicResolutionToExternalPipeline()) {
            return "external shader pipeline";
        }
        return "ready";
    }

    public static double getDynamicResolutionMinScale() {
        return GlobalPerformanceGovernor.getEffectiveDynamicResolutionMinScale(dynamicResolutionMinScale);
    }

    public static double getConfiguredDynamicResolutionMinScale() {
        return dynamicResolutionMinScale;
    }

    public static void setDynamicResolutionMinScale(double minScale) {
        double clampedScale = clampDynamicResolutionScale(minScale);
        if (Math.abs(dynamicResolutionMinScale - clampedScale) < 0.0001D) {
            return;
        }

        dynamicResolutionMinScale = clampedScale;
        DynamicResolutionController.reset();
        RuntimeStateLogger.reset();
    }

    public static boolean isAdaptiveSimulationDistanceActive() {
        return enabled && budgetActive && adaptiveSimulationDistanceEnabled;
    }

    public static boolean isAdaptiveSimulationDistanceSettingEnabled() {
        return adaptiveSimulationDistanceEnabled;
    }

    public static void setAdaptiveSimulationDistanceEnabled(boolean enabledIn) {
        if (adaptiveSimulationDistanceEnabled == enabledIn) {
            return;
        }

        adaptiveSimulationDistanceEnabled = enabledIn;
        AdaptiveSimulationDistanceController.reset();
        RuntimeStateLogger.reset();
    }

    public static boolean isAdaptiveQualityEnabled() {
        return adaptiveQualityEnabled;
    }

    public static void setAdaptiveQualityEnabled(boolean enabledIn) {
        if (adaptiveQualityEnabled == enabledIn) {
            return;
        }

        adaptiveQualityEnabled = enabledIn;
        adaptiveQualityTargetLevel = qualityLevel;
        AdaptiveQualityController.reset();
    }

    public static boolean isAdaptiveQualityActive() {
        return enabled && budgetActive && adaptiveQualityEnabled;
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
        properties.setProperty("adaptiveQualityEnabled", Boolean.toString(adaptiveQualityEnabled));
        properties.setProperty("cpuInvolvementLevel", Integer.toString(cpuInvolvementLevel));
        properties.setProperty("frameTimeStabilizerEnabled", Boolean.toString(frameTimeStabilizerEnabled));
        properties.setProperty("gpuBottleneckDetectorEnabled", Boolean.toString(gpuBottleneckDetectorEnabled));
        properties.setProperty("advancedSharpeningEnabled", Boolean.toString(advancedSharpeningEnabled));
        properties.setProperty("advancedSharpeningStrength", Double.toString(advancedSharpeningStrength));
        properties.setProperty("authoritativeRuntimeEnabled", Boolean.toString(authoritativeRuntimeEnabled));
        properties.setProperty("activePreset", selectedPreset.getConfigKey());
        properties.setProperty("activeShaderKey", PauCShaderManager.getActiveShaderKey());
        properties.setProperty("deferredShaderPack", PauCDeferredShaderController.getConfigKey());
        properties.setProperty("deferredCompatibilityMode", PauCDeferredShaderController.getCompatibilityConfigKey());

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
            adaptiveQualityTargetLevel = qualityLevel;
            dynamicResolutionEnabled = parseDynamicResolutionEnabled(properties);
            dynamicResolutionMinScale = parseDynamicResolutionMinScale(properties);
            adaptiveSimulationDistanceEnabled = parseAdaptiveSimulationDistanceEnabled(properties);
            adaptiveQualityEnabled = parseAdaptiveQualityEnabled(properties);
            cpuInvolvementLevel = parseCpuInvolvementLevel(properties);
            frameTimeStabilizerEnabled = parseFrameTimeStabilizerEnabled(properties);
            gpuBottleneckDetectorEnabled = parseGpuBottleneckDetectorEnabled(properties);
            advancedSharpeningEnabled = parseAdvancedSharpeningEnabled(properties);
            advancedSharpeningStrength = parseAdvancedSharpeningStrength(properties);
            authoritativeRuntimeEnabled = parseAuthoritativeRuntimeEnabled(properties);
            selectedPreset = PauCUserPreset.fromConfigKey(properties.getProperty("activePreset"), DEFAULT_USER_PRESET);
            PauCShaderManager.setActiveShaderKey(properties.getProperty("activeShaderKey", PauCShaderManager.getDefaultShaderKey()));
            PauCDeferredShaderController.setCompatibilityConfigKey(
                    properties.getProperty("deferredCompatibilityMode", "balanced")
            );
            PauCDeferredShaderController.setConfigKey(properties.getProperty("deferredShaderPack", PauCDeferredShaderController.NONE_KEY));
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
        return Math.max(0.35D, Math.min(1.00D, value));
    }

    private static boolean parseAdaptiveSimulationDistanceEnabled(Properties properties) {
        String value = properties.getProperty("adaptiveSimulationDistanceEnabled");
        if (value == null || value.isBlank()) {
            return adaptiveSimulationDistanceEnabled;
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean parseAdaptiveQualityEnabled(Properties properties) {
        String value = properties.getProperty("adaptiveQualityEnabled");
        if (value == null || value.isBlank()) {
            return adaptiveQualityEnabled;
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
        adaptiveQualityTargetLevel = clampQualityLevel(adaptiveQualityTargetLevel);
    }

    private static void applyPresetInternal(PauCUserPreset preset, boolean forceDisableDeferredPipeline, boolean recoveryMode) {
        PauCUserPreset resolvedPreset = preset == null ? DEFAULT_USER_PRESET : preset;
        selectedPreset = resolvedPreset;

        enabled = true;
        qualityLevel = clampQualityLevel(resolvedPreset.getQualityLevel());
        adaptiveQualityTargetLevel = qualityLevel;
        dynamicResolutionEnabled = resolvedPreset.isDynamicResolutionEnabled();
        dynamicResolutionMinScale = clampDynamicResolutionScale(resolvedPreset.getDynamicResolutionMinScale());
        adaptiveSimulationDistanceEnabled = resolvedPreset.isAdaptiveSimulationDistanceEnabled();
        cpuInvolvementLevel = clampCpuInvolvementLevel(resolvedPreset.getCpuInvolvementLevel());
        frameTimeStabilizerEnabled = resolvedPreset.isFrameTimeStabilizerEnabled();
        gpuBottleneckDetectorEnabled = resolvedPreset.isGpuBottleneckEnabled();
        advancedSharpeningEnabled = resolvedPreset.isAdvancedSharpeningEnabled();
        advancedSharpeningStrength = clampAdvancedSharpeningStrength(resolvedPreset.getAdvancedSharpeningStrength());
        authoritativeRuntimeEnabled = true;

        PauCDeferredShaderController.setCompatibilityMode(resolvedPreset.getDeferredMode());
        if (forceDisableDeferredPipeline || resolvedPreset.isDisableDeferredPipeline()) {
            PauCDeferredShaderController.setSelectedPack(PauCDeferredShaderController.NONE_KEY);
        }

        refreshBudgetState();
        resetRuntimeSystemsForSettingsChange();
        runtimePoliciesDirty = true;
        saveConfig();

        String statusMessage = recoveryMode
                ? "PauC recovery mode applique"
                : "PauC preset applique: " + resolvedPreset.getDisplayLabel();
        showStatusMessage(statusMessage);
        Pain_au_Choc.LOGGER.info(
                "PauC user preset applied: key={} quality={} mode={} recovery={}",
                resolvedPreset.getConfigKey(),
                qualityLevel,
                resolvedPreset.getDeferredMode().getConfigKey(),
                recoveryMode
        );
    }

    private static void resetRuntimeSystemsForSettingsChange() {
        DynamicResolutionController.reset();
        PauCPipeline.resetForHotSettingsChange();
        ParticleBudgetController.reset();
        AdaptiveQualityController.reset();
        AdaptiveSimulationDistanceController.reset();
        ShadowDistanceGovernor.reset();
        TerrainProxyController.reset();
        ManagedChunkRadiusController.reset();
        StructureStreamingController.reset();
        ChunkBuildQueueController.reset();
        LatencyController.reset();
        BottleneckController.reset();
        GlobalPerformanceGovernor.reset();
        IntegratedServerLoadController.reset();
        ServerMobCadenceController.reset();
        PerformanceTelemetryRecorder.flushNow();
        RuntimeStateLogger.reset();
        qualityCycleCooldownTicks = 0;
        runtimePolicySyncTicks = RUNTIME_POLICY_SYNC_INTERVAL_TICKS;
        AdaptiveFrameCapController.reset();
    }
}
