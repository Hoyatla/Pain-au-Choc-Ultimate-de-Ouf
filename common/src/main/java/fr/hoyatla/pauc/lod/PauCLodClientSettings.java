package fr.hoyatla.pauc.lod;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class PauCLodClientSettings {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String CONFIG_FILE_NAME = "pauc_lods.properties";
	private static final String PROFILE_DEFAULTS_VERSION_KEY = "profileDefaultsVersion";
	private static final String ENABLED_KEY = "lodsEnabled";
	private static final String LOD_CLOUDS_KEY = "lodCloudsEnabled";
	private static final String VANILLA_FOG_KEY = "vanillaFogEnabled";
	private static final String TARGET_DISTANCE_KEY = "lodTargetDistanceChunks";
	private static final String MEMORY_BUDGET_KEY = "lodMemoryBudgetMb";
	private static final String RETENTION_MARGIN_KEY = "lodRetentionMarginChunks";
	private static final String GENERATION_REQUEST_RATE_LIMIT_KEY = "lodGenerationRequestRateLimit";
	private static final String ENABLE_N_SIZE_GENERATION_KEY = "lodEnableNSizeGeneration";
	private static final String FILL_HOLES_KEY = "lodFillHoles";
	private static final String DIAGNOSTICS_KEY = "lodDiagnostics";
	private static final String NVIDIA_ACCELERATION_KEY = "nvidiaAccelerationEnabled";
	private static final String DIRECT_GPU_UPLOAD_KEY = "directGpuUploadEnabled";
	private static final String TERRAIN_MORPHING_KEY = "terrainMorphingEnabled";
	private static final String DYNAMIC_RESOLUTION_MODE_KEY = "dynamicResolutionMode";
	private static final String SHADOW_MODE_KEY = "shadowMode";
	private static final String ENABLED_PROPERTY = "pauc.lod.enabled";
	private static final String LOD_CLOUDS_PROPERTY = "pauc.lod.clouds";
	private static final String VANILLA_FOG_PROPERTY = "pauc.lod.vanillaFog";
	private static final String TARGET_DISTANCE_PROPERTY = "pauc.lod.targetDistance";
	private static final String DYNAMIC_TARGET_DISTANCE_PROPERTY = "pauc.lod.dynamicTargetDistance";
	private static final String ALLOW_DISTANCE_REDUCTION_PROPERTY = "pauc.client.allowFpsGovernorDistanceReduction";
	private static final String ALLOW_GENERATION_REDUCTION_PROPERTY = "pauc.client.allowFpsGovernorGenerationReduction";
	private static final String MEMORY_BUDGET_PROPERTY = "pauc.lod.memoryBudgetMb";
	private static final String RETENTION_MARGIN_PROPERTY = "pauc.lod.retentionMarginChunks";
	private static final String DYNAMIC_RETENTION_MARGIN_PROPERTY = "pauc.lod.dynamicRetentionMarginChunks";
	private static final String GENERATION_REQUEST_RATE_LIMIT_PROPERTY = "pauc.lod.generationRequestRateLimit";
	private static final String DYNAMIC_GENERATION_REQUEST_RATE_LIMIT_PROPERTY = "pauc.lod.dynamicGenerationRequestRateLimit";
	private static final String MAX_GENERATION_REQUEST_RATE_LIMIT_PROPERTY = "pauc.lod.maxGenerationRequestRateLimit";
	private static final String RECOMMENDED_VANILLA_DISTANCE_PROPERTY = "pauc.lod.recommendedVanillaDistance";
	private static final String AUTO_REDUCE_VANILLA_DISTANCE_PROPERTY = "pauc.lod.autoReduceVanillaDistance";
	private static final String ENABLE_N_SIZE_GENERATION_PROPERTY = "pauc.lod.enableNSizeGeneration";
	private static final String FILL_HOLES_PROPERTY = "pauc.lod.fillHoles";
	private static final String DIAGNOSTICS_PROPERTY = "pauc.lod.diagnostics";
	private static final String NVIDIA_ACCELERATION_PROPERTY = "pauc.client.cuda.enabled";
	private static final String NVIDIA_ACCELERATION_AVAILABLE_PROPERTY = "pauc.client.cuda.available";
	private static final String NVIDIA_DRIVER_AVAILABLE_PROPERTY = "pauc.client.cuda.driverAvailable";
	private static final String NVIDIA_ACCELERATION_STATUS_PROPERTY = "pauc.client.cuda.status";
	private static final String DIRECT_GPU_UPLOAD_PROPERTY = "pauc.lod.directGpuOpenGlRenderer";
	private static final String TERRAIN_MORPHING_PROPERTY = "pauc.lod.shaderOffSeamMorph";
	private static final String DYNAMIC_RESOLUTION_PROPERTY = "pauc.client.dynamicResolution";
	private static final String DYNAMIC_RESOLUTION_MODE_PROPERTY = "pauc.client.dynamicResolutionMode";
	private static final String SHADOW_MODE_PROPERTY = "pauc.shadow.mode";
	private static final String DYNAMIC_RESOLUTION_MIN_SCALE_PROPERTY = "pauc.client.dynamicResolutionMinScale";
	private static final String DYNAMIC_RESOLUTION_DOWN_RATE_PROPERTY = "pauc.client.dynamicResolutionDownRatePerSecond";
	private static final String DYNAMIC_RESOLUTION_UP_RATE_PROPERTY = "pauc.client.dynamicResolutionUpRatePerSecond";
	private static final String ADAPTIVE_RETENTION_MARGIN_PROPERTY = "pauc.lod.adaptiveRetentionMargin";
	private static final int PROFILE_DEFAULTS_VERSION = 4;
	private static volatile boolean loaded;
	private static boolean lodsEnabled = true;
	private static boolean lodCloudsEnabled = true;
	private static boolean vanillaFogEnabled = true;
	private static int targetDistanceChunks = PauCLodGameplayProfile.defaultTargetDistanceChunks();
	private static int memoryBudgetMb = defaultMemoryBudgetMb();
	private static int retentionMarginChunks = 12;
	private static int generationRequestRateLimit = PauCLodGameplayProfile.defaultGenerationRequestRateLimit(defaultGenerationRequestRateLimit());
	private static boolean enableNSizeGeneration = true;
	private static boolean fillHoles = true;
	private static boolean diagnostics = true;
	private static boolean nvidiaAccelerationEnabled = true;
	private static boolean directGpuUploadEnabled = true;
	private static boolean terrainMorphingEnabled = true;
	private static PauCDynamicResolutionMode dynamicResolutionMode = PauCDynamicResolutionMode.OFF;
	// BASIC by user decision (07-19, after a profiler-measured session showed the pass at ~0.01ms
	// render-thread with the current implementation): its real cost is displayed on the
	// "PauC render profiler" log line ("shadow avg=..."), so the gauge is an informed choice.
	private static fr.hoyatla.pauc.shadow.PauCShadowMode shadowMode = fr.hoyatla.pauc.shadow.PauCShadowMode.BASIC;

	private PauCLodClientSettings() {
	}

	public static fr.hoyatla.pauc.shadow.PauCShadowMode shadowMode() {
		ensureLoaded();
		String override = System.getProperty(SHADOW_MODE_PROPERTY);
		return override == null ? shadowMode : fr.hoyatla.pauc.shadow.PauCShadowMode.byId(override);
	}

	public static void setShadowMode(fr.hoyatla.pauc.shadow.PauCShadowMode mode) {
		ensureLoaded();
		shadowMode = mode == null ? fr.hoyatla.pauc.shadow.PauCShadowMode.OFF : mode;
		System.setProperty(SHADOW_MODE_PROPERTY, shadowMode.id());
		save();
	}

	public static boolean isLodsEnabled() {
		ensureLoaded();
		String override = System.getProperty(ENABLED_PROPERTY);
		return override == null ? lodsEnabled : Boolean.parseBoolean(override);
	}

	public static void setLodsEnabled(boolean enabled) {
		ensureLoaded();
		lodsEnabled = enabled;
		System.setProperty(ENABLED_PROPERTY, Boolean.toString(enabled));
		save();
	}

	public static boolean isLodCloudsEnabled() {
		ensureLoaded();
		String override = System.getProperty(LOD_CLOUDS_PROPERTY);
		return override == null ? lodCloudsEnabled : Boolean.parseBoolean(override);
	}

	public static void setLodCloudsEnabled(boolean enabled) {
		ensureLoaded();
		lodCloudsEnabled = enabled;
		System.setProperty(LOD_CLOUDS_PROPERTY, Boolean.toString(enabled));
		save();
	}

	public static boolean isVanillaFogEnabled() {
		ensureLoaded();
		String override = System.getProperty(VANILLA_FOG_PROPERTY);
		return override == null ? vanillaFogEnabled : Boolean.parseBoolean(override);
	}

	public static void setVanillaFogEnabled(boolean enabled) {
		ensureLoaded();
		vanillaFogEnabled = enabled;
		System.setProperty(VANILLA_FOG_PROPERTY, Boolean.toString(enabled));
		save();
	}

	public static synchronized void reloadForClientSession() {
		resetDefaults();
		loaded = false;
		ensureLoaded();
		syncRuntimeProperties();
	}

	public static int targetDistanceChunks() {
		ensureLoaded();
		int configuredDistance = configuredTargetDistanceChunks();
		if (!isDynamicTargetDistanceReductionAllowed()) {
			return configuredDistance;
		}
		String dynamicDistance = System.getProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY);
		if (dynamicDistance == null) {
			return configuredDistance;
		}
		return Math.min(configuredDistance, sanitizeTargetDistanceChunks(readInt(dynamicDistance, configuredDistance)));
	}

	public static int configuredTargetDistanceChunks() {
		ensureLoaded();
		return sanitizeTargetDistanceChunks(readIntProperty(TARGET_DISTANCE_PROPERTY, targetDistanceChunks));
	}

	public static void setTargetDistanceChunks(int targetDistance) {
		ensureLoaded();
		int sanitizedTarget = sanitizeTargetDistanceChunks(targetDistance);
		targetDistanceChunks = sanitizedTarget;
		System.setProperty(TARGET_DISTANCE_PROPERTY, Integer.toString(targetDistanceChunks));
		System.clearProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY);
		save();
	}

	public static int sanitizeTargetDistanceChunks(int targetDistance) {
		return Math.max(PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, Math.min(PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS, targetDistance));
	}

	public static int memoryBudgetMb() {
		ensureLoaded();
		return sanitizeMemoryBudgetMb(readIntProperty(MEMORY_BUDGET_PROPERTY, memoryBudgetMb));
	}

	public static int retentionMarginChunks() {
		ensureLoaded();
		int configuredMargin = sanitizeRetentionMarginChunks(readIntProperty(RETENTION_MARGIN_PROPERTY, retentionMarginChunks));
		String dynamicMargin = System.getProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY);
		if (dynamicMargin == null) {
			return effectiveRetentionMarginChunks(configuredMargin);
		}
		return effectiveRetentionMarginChunks(readInt(dynamicMargin, configuredMargin));
	}

	public static int generationRequestRateLimit() {
		ensureLoaded();
		int configuredRate = configuredGenerationRequestRateLimit();
		String dynamicRate = System.getProperty(DYNAMIC_GENERATION_REQUEST_RATE_LIMIT_PROPERTY);
		if (dynamicRate != null) {
			int sanitizedDynamicRate = sanitizeGenerationRequestRateLimit(readInt(dynamicRate, configuredRate));
			if (!isDynamicGenerationReductionAllowed()) {
				return Math.max(configuredRate, sanitizedDynamicRate);
			}
			return sanitizedDynamicRate;
		}

		return Math.max(PauCLodGameplayProfile.minimumGenerationRequestRate(), configuredRate);
	}

	public static int configuredGenerationRequestRateLimit() {
		ensureLoaded();
		return sanitizeGenerationRequestRateLimit(readIntProperty(GENERATION_REQUEST_RATE_LIMIT_PROPERTY, generationRequestRateLimit));
	}

	public static int maxGenerationRequestRateLimit() {
		return Math.max(512, Math.min(2048, readInt(System.getProperty(MAX_GENERATION_REQUEST_RATE_LIMIT_PROPERTY), 1024)));
	}

	public static int recommendedVanillaDistanceChunks() {
		ensureLoaded();
		int fallback = PauCLodGameplayProfile.recommendedVanillaDistanceChunks();
		return sanitizeRecommendedVanillaDistanceChunks(readIntProperty(RECOMMENDED_VANILLA_DISTANCE_PROPERTY, fallback));
	}

	public static boolean autoReduceVanillaDistance() {
		ensureLoaded();
		return readBooleanProperty(AUTO_REDUCE_VANILLA_DISTANCE_PROPERTY, PauCLodGameplayProfile.autoReduceVanillaDistance());
	}

	public static boolean enableNSizeGeneration() {
		ensureLoaded();
		return readBooleanProperty(ENABLE_N_SIZE_GENERATION_PROPERTY, enableNSizeGeneration);
	}

	public static boolean fillLodHoles() {
		ensureLoaded();
		return readBooleanProperty(FILL_HOLES_PROPERTY, fillHoles);
	}

	public static boolean diagnosticsEnabled() {
		ensureLoaded();
		return readBooleanProperty(DIAGNOSTICS_PROPERTY, diagnostics);
	}

	public static boolean isNvidiaAccelerationEnabled() {
		ensureLoaded();
		return readBooleanProperty(NVIDIA_ACCELERATION_PROPERTY, nvidiaAccelerationEnabled);
	}

	public static boolean isDirectGpuUploadEnabled() {
		ensureLoaded();
		return readBooleanProperty(DIRECT_GPU_UPLOAD_PROPERTY, directGpuUploadEnabled);
	}

	public static boolean isTerrainMorphingEnabled() {
		ensureLoaded();
		return readEnabledStrengthProperty(TERRAIN_MORPHING_PROPERTY, terrainMorphingEnabled);
	}

	public static PauCDynamicResolutionMode dynamicResolutionMode() {
		ensureLoaded();
		return PauCDynamicResolutionMode.byId(System.getProperty(DYNAMIC_RESOLUTION_MODE_PROPERTY, dynamicResolutionMode.id()));
	}

	public static void setNvidiaAccelerationEnabled(boolean enabled) {
		ensureLoaded();
		nvidiaAccelerationEnabled = enabled;
		System.setProperty(NVIDIA_ACCELERATION_PROPERTY, Boolean.toString(enabled));
		save();
	}

	public static void setTerrainMorphingEnabled(boolean enabled) {
		ensureLoaded();
		terrainMorphingEnabled = enabled;
		System.setProperty(TERRAIN_MORPHING_PROPERTY, enabled ? "1.0" : "0.0");
		save();
	}

	public static void setDynamicResolutionMode(PauCDynamicResolutionMode mode) {
		ensureLoaded();
		dynamicResolutionMode = mode == null ? PauCDynamicResolutionMode.OFF : mode;
		syncDynamicResolutionProperties();
		save();
	}

	public static boolean isNvidiaAccelerationReady() {
		return isNvidiaAccelerationEnabled()
			&& Boolean.parseBoolean(System.getProperty(NVIDIA_ACCELERATION_AVAILABLE_PROPERTY, "false"));
	}

	public static boolean isNvidiaCudaDriverAvailable() {
		return Boolean.parseBoolean(System.getProperty(NVIDIA_DRIVER_AVAILABLE_PROPERTY, "false"));
	}

	public static String nvidiaAccelerationStatus() {
		String status = System.getProperty(NVIDIA_ACCELERATION_STATUS_PROPERTY);
		return status == null || status.isBlank() ? "not-detected" : status;
	}

	public static String describePerformancePolicy() {
		return "lodPolicy[target="
			+ targetDistanceChunks()
			+ ", "
			+ PauCLodGameplayProfile.describe()
			+ ", "
			+ PauCTerrainGeneratorDetector.describeCurrentClientContext()
			+ ", memory="
			+ memoryBudgetMb()
			+ "MiB, retainMargin="
			+ retentionMarginChunks()
			+ ", requestRate="
			+ generationRequestRateLimit()
			+ "/s, nSized="
			+ enableNSizeGeneration()
			+ ", vanillaRecommended<="
			+ recommendedVanillaDistanceChunks()
			+ ", autoVanilla="
			+ autoReduceVanillaDistance()
			+ ", fillHoles="
			+ fillLodHoles()
			+ ", lodClouds="
			+ isLodCloudsEnabled()
			+ ", nvidiaAccel="
			+ isNvidiaAccelerationEnabled()
			+ "/"
			+ nvidiaAccelerationStatus()
			+ ", directGpu="
			+ isDirectGpuUploadEnabled()
			+ ", terrainMorph="
			+ isTerrainMorphingEnabled()
			+ ", drs="
			+ dynamicResolutionMode().id()
			+ "]";
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}

		loaded = true;
		Path path = configPath();
		if (!Files.isRegularFile(path)) {
			syncRuntimeProperties();
			return;
		}

		Properties properties = new Properties();
		boolean migratedProfileDefaults = false;
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
			int profileDefaultsVersion = readInt(properties.getProperty(PROFILE_DEFAULTS_VERSION_KEY), 0);
			lodsEnabled = Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, Boolean.toString(lodsEnabled)));
			lodCloudsEnabled = Boolean.parseBoolean(properties.getProperty(LOD_CLOUDS_KEY, Boolean.toString(lodCloudsEnabled)));
			vanillaFogEnabled = Boolean.parseBoolean(properties.getProperty(VANILLA_FOG_KEY, Boolean.toString(vanillaFogEnabled)));
			targetDistanceChunks = sanitizeTargetDistanceChunks(readInt(properties.getProperty(TARGET_DISTANCE_KEY), targetDistanceChunks));
			memoryBudgetMb = sanitizeMemoryBudgetMb(readInt(properties.getProperty(MEMORY_BUDGET_KEY), memoryBudgetMb));
			retentionMarginChunks = sanitizeRetentionMarginChunks(readInt(properties.getProperty(RETENTION_MARGIN_KEY), retentionMarginChunks));
			generationRequestRateLimit = sanitizeGenerationRequestRateLimit(readInt(properties.getProperty(GENERATION_REQUEST_RATE_LIMIT_KEY), generationRequestRateLimit));
			enableNSizeGeneration = Boolean.parseBoolean(properties.getProperty(ENABLE_N_SIZE_GENERATION_KEY, Boolean.toString(enableNSizeGeneration)));
			fillHoles = Boolean.parseBoolean(properties.getProperty(FILL_HOLES_KEY, Boolean.toString(fillHoles)));
			diagnostics = Boolean.parseBoolean(properties.getProperty(DIAGNOSTICS_KEY, Boolean.toString(diagnostics)));
			nvidiaAccelerationEnabled = Boolean.parseBoolean(properties.getProperty(NVIDIA_ACCELERATION_KEY, Boolean.toString(nvidiaAccelerationEnabled)));
			directGpuUploadEnabled = Boolean.parseBoolean(properties.getProperty(DIRECT_GPU_UPLOAD_KEY, Boolean.toString(directGpuUploadEnabled)));
			terrainMorphingEnabled = Boolean.parseBoolean(properties.getProperty(TERRAIN_MORPHING_KEY, Boolean.toString(terrainMorphingEnabled)));
			dynamicResolutionMode = PauCDynamicResolutionMode.byId(properties.getProperty(DYNAMIC_RESOLUTION_MODE_KEY, dynamicResolutionMode.id()));
			shadowMode = fr.hoyatla.pauc.shadow.PauCShadowMode.byId(properties.getProperty(SHADOW_MODE_KEY, shadowMode.id()));
			migratedProfileDefaults = migrateProfileDefaults(profileDefaultsVersion);
		} catch (IOException exception) {
			LOGGER.warn("PauC could not read LOD client settings from {}.", path, exception);
		}
		syncRuntimeProperties();
		if (migratedProfileDefaults) {
			save();
		}
	}

	private static synchronized void save() {
		Path path = configPath();
		Properties properties = new Properties();
		properties.setProperty(PROFILE_DEFAULTS_VERSION_KEY, Integer.toString(PROFILE_DEFAULTS_VERSION));
		properties.setProperty(ENABLED_KEY, Boolean.toString(lodsEnabled));
		properties.setProperty(LOD_CLOUDS_KEY, Boolean.toString(lodCloudsEnabled));
		properties.setProperty(VANILLA_FOG_KEY, Boolean.toString(vanillaFogEnabled));
		properties.setProperty(TARGET_DISTANCE_KEY, Integer.toString(targetDistanceChunks));
		properties.setProperty(MEMORY_BUDGET_KEY, Integer.toString(memoryBudgetMb));
		properties.setProperty(RETENTION_MARGIN_KEY, Integer.toString(retentionMarginChunks));
		properties.setProperty(GENERATION_REQUEST_RATE_LIMIT_KEY, Integer.toString(generationRequestRateLimit));
		properties.setProperty(ENABLE_N_SIZE_GENERATION_KEY, Boolean.toString(enableNSizeGeneration));
		properties.setProperty(FILL_HOLES_KEY, Boolean.toString(fillHoles));
		properties.setProperty(DIAGNOSTICS_KEY, Boolean.toString(diagnostics));
		properties.setProperty(NVIDIA_ACCELERATION_KEY, Boolean.toString(nvidiaAccelerationEnabled));
		properties.setProperty(DIRECT_GPU_UPLOAD_KEY, Boolean.toString(directGpuUploadEnabled));
		properties.setProperty(TERRAIN_MORPHING_KEY, Boolean.toString(terrainMorphingEnabled));
		properties.setProperty(DYNAMIC_RESOLUTION_MODE_KEY, dynamicResolutionMode.id());
		properties.setProperty(SHADOW_MODE_KEY, shadowMode.id());

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				properties.store(writer, "PauC LOD client settings");
			}
		} catch (IOException exception) {
			LOGGER.warn("PauC could not save LOD client settings to {}.", path, exception);
		}
	}

	private static Path configPath() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.gameDirectory != null) {
			return minecraft.gameDirectory.toPath().resolve("config").resolve(CONFIG_FILE_NAME);
		}

		return Paths.get("config", CONFIG_FILE_NAME);
	}

	private static void resetDefaults() {
		lodsEnabled = true;
		lodCloudsEnabled = true;
		targetDistanceChunks = PauCLodGameplayProfile.defaultTargetDistanceChunks();
		memoryBudgetMb = defaultMemoryBudgetMb();
		retentionMarginChunks = 12;
		generationRequestRateLimit = PauCLodGameplayProfile.defaultGenerationRequestRateLimit(defaultGenerationRequestRateLimit());
		enableNSizeGeneration = true;
		fillHoles = true;
		diagnostics = true;
		nvidiaAccelerationEnabled = true;
		directGpuUploadEnabled = true;
		terrainMorphingEnabled = true;
		dynamicResolutionMode = PauCDynamicResolutionMode.OFF;
		shadowMode = fr.hoyatla.pauc.shadow.PauCShadowMode.BASIC;
	}

	private static void syncRuntimeProperties() {
		System.setProperty(SHADOW_MODE_PROPERTY, shadowMode.id());
		System.setProperty(ENABLED_PROPERTY, Boolean.toString(lodsEnabled));
		System.setProperty(LOD_CLOUDS_PROPERTY, Boolean.toString(lodCloudsEnabled));
		System.setProperty(TARGET_DISTANCE_PROPERTY, Integer.toString(targetDistanceChunks));
		System.clearProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY);
		System.setProperty(NVIDIA_ACCELERATION_PROPERTY, Boolean.toString(nvidiaAccelerationEnabled));
		System.setProperty(DIRECT_GPU_UPLOAD_PROPERTY, Boolean.toString(directGpuUploadEnabled));
		System.setProperty(TERRAIN_MORPHING_PROPERTY, terrainMorphingEnabled ? "1.0" : "0.0");
		syncDynamicResolutionProperties();
	}

	private static void syncDynamicResolutionProperties() {
		System.setProperty(DYNAMIC_RESOLUTION_MODE_PROPERTY, dynamicResolutionMode.id());
		System.setProperty(DYNAMIC_RESOLUTION_PROPERTY, Boolean.toString(dynamicResolutionMode != PauCDynamicResolutionMode.OFF));
		System.setProperty(DYNAMIC_RESOLUTION_MIN_SCALE_PROPERTY, Double.toString(dynamicResolutionMode.minScale()));
		System.setProperty(DYNAMIC_RESOLUTION_DOWN_RATE_PROPERTY, Double.toString(dynamicResolutionMode.downRatePerSecond()));
		System.setProperty(DYNAMIC_RESOLUTION_UP_RATE_PROPERTY, Double.toString(dynamicResolutionMode.upRatePerSecond()));
	}

	private static boolean isDynamicTargetDistanceReductionAllowed() {
		return readBooleanProperty(ALLOW_DISTANCE_REDUCTION_PROPERTY, PauCLodGameplayProfile.allowDynamicTargetDistanceReduction());
	}

	private static boolean isDynamicGenerationReductionAllowed() {
		return readBooleanProperty(ALLOW_GENERATION_REDUCTION_PROPERTY, PauCLodGameplayProfile.allowDynamicGenerationReduction());
	}

	private static boolean migrateProfileDefaults(int profileDefaultsVersion) {
		if (profileDefaultsVersion >= PROFILE_DEFAULTS_VERSION) {
			return false;
		}

		boolean changed = false;
		if (targetDistanceChunks <= PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS) {
			targetDistanceChunks = PauCLodGameplayProfile.defaultTargetDistanceChunks();
			changed = true;
		}
		if (profileDefaultsVersion < 3) {
			PauCLodGameplayProfile.Profile profile = PauCLodGameplayProfile.current();
			if (profile == PauCLodGameplayProfile.Profile.COMPETITIVE && targetDistanceChunks == 64) {
				targetDistanceChunks = PauCLodGameplayProfile.defaultTargetDistanceChunks();
				changed = true;
			} else if (profile == PauCLodGameplayProfile.Profile.BALANCED && targetDistanceChunks == 56) {
				targetDistanceChunks = PauCLodGameplayProfile.defaultTargetDistanceChunks();
				changed = true;
			}
		}
		if (profileDefaultsVersion < 4) {
			PauCLodGameplayProfile.Profile profile = PauCLodGameplayProfile.current();
			if (profile == PauCLodGameplayProfile.Profile.SHOOTER && (targetDistanceChunks == 56 || targetDistanceChunks == 60 || targetDistanceChunks == 64)) {
				targetDistanceChunks = PauCLodGameplayProfile.defaultTargetDistanceChunks();
				changed = true;
			}
		}

		int profileGenerationDefault = PauCLodGameplayProfile.defaultGenerationRequestRateLimit(defaultGenerationRequestRateLimit());
		if (generationRequestRateLimit < profileGenerationDefault) {
			generationRequestRateLimit = profileGenerationDefault;
			changed = true;
		}

		return changed;
	}

	/**
	 * "Vanilla fidelity ring" (perceived-vanilla horizon project): at high LOD gauges the nearest LOD ring
	 * should render at BLOCK resolution (geometrically identical to vanilla terrain). DH-free predicate so
	 * the always-on FPS governor can consult it; the governor owns WHEN to apply it (health-latched).
	 */
	public static boolean isVanillaFidelityRingActive() {
		return readBooleanProperty("pauc.lod.vanillaFidelityRing", true)
			&& targetDistanceChunks() >= Math.max(8, Math.min(96, readIntProperty("pauc.lod.vanillaFidelityMinTarget", 48)));
	}

	private static int readIntProperty(String key, int fallback) {
		return readInt(System.getProperty(key), fallback);
	}

	private static boolean readBooleanProperty(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static boolean readEnabledStrengthProperty(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return fallback;
		}

		String normalized = rawValue.trim();
		if (normalized.equalsIgnoreCase("true")) {
			return true;
		}
		if (normalized.equalsIgnoreCase("false")) {
			return false;
		}
		try {
			return Float.parseFloat(normalized) > 0.0F;
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int readInt(String rawValue, int fallback) {
		if (rawValue == null) {
			return fallback;
		}

		try {
			return Integer.parseInt(rawValue);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int sanitizeMemoryBudgetMb(int value) {
		return Math.max(256, Math.min(4096, value));
	}

	private static int sanitizeRetentionMarginChunks(int value) {
		return Math.max(0, Math.min(24, value));
	}

	private static int effectiveRetentionMarginChunks(int configuredMargin) {
		if (readBooleanProperty(ADAPTIVE_RETENTION_MARGIN_PROPERTY, true)) {
			PauCTerrainGeneratorDetector.GeneratorKind terrain = PauCTerrainGeneratorDetector.currentClientKind();
			PauCTerrainGeneratorDetector.ModpackClass modpack = PauCTerrainGeneratorDetector.currentModpackClass();
			configuredMargin += terrain.retentionMarginBoost() + modpack.retentionMarginBoost();
		}
		if (!PauCLodShaderContext.isShaderPackInUse()) {
			return sanitizeRetentionMarginChunks(Math.max(8, configuredMargin));
		}

		return sanitizeRetentionMarginChunks(configuredMargin);
	}

	private static int sanitizeGenerationRequestRateLimit(int value) {
		return Math.max(20, Math.min(maxGenerationRequestRateLimit(), value));
	}

	private static int sanitizeRecommendedVanillaDistanceChunks(int value) {
		return Math.max(4, Math.min(24, value));
	}

	private static int defaultMemoryBudgetMb() {
		long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
		PauCTerrainGeneratorDetector.ModpackClass modpack = PauCTerrainGeneratorDetector.currentModpackClass();
		long heapShareBudgetMb = Math.round(maxMemoryMb * modpack.heapBudgetShare());
		return sanitizeMemoryBudgetMb((int) Math.max(256L, heapShareBudgetMb + modpack.memoryBoostMb()));
	}

	private static int defaultGenerationRequestRateLimit() {
		int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
		int memoryBonus = defaultMemoryBudgetMb() >= 640 ? 8 : 0;
		PauCTerrainGeneratorDetector.GeneratorKind terrain = PauCTerrainGeneratorDetector.currentClientKind();
		PauCTerrainGeneratorDetector.ModpackClass modpack = PauCTerrainGeneratorDetector.currentModpackClass();
		return sanitizeGenerationRequestRateLimit(48 + processors * 3 + memoryBonus + terrain.generationRateBoost() + modpack.generationRateBoost());
	}
}
