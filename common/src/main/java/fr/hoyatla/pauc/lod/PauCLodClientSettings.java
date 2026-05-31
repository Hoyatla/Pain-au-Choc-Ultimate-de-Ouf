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
	private static final String ENABLED_KEY = "lodsEnabled";
	private static final String LOD_CLOUDS_KEY = "lodCloudsEnabled";
	private static final String TARGET_DISTANCE_KEY = "lodTargetDistanceChunks";
	private static final String MEMORY_BUDGET_KEY = "lodMemoryBudgetMb";
	private static final String RETENTION_MARGIN_KEY = "lodRetentionMarginChunks";
	private static final String GENERATION_REQUEST_RATE_LIMIT_KEY = "lodGenerationRequestRateLimit";
	private static final String ENABLE_N_SIZE_GENERATION_KEY = "lodEnableNSizeGeneration";
	private static final String FILL_HOLES_KEY = "lodFillHoles";
	private static final String DIAGNOSTICS_KEY = "lodDiagnostics";
	private static final String ENABLED_PROPERTY = "pauc.lod.enabled";
	private static final String LOD_CLOUDS_PROPERTY = "pauc.lod.clouds";
	private static final String TARGET_DISTANCE_PROPERTY = "pauc.lod.targetDistance";
	private static final String DYNAMIC_TARGET_DISTANCE_PROPERTY = "pauc.lod.dynamicTargetDistance";
	private static final String MEMORY_BUDGET_PROPERTY = "pauc.lod.memoryBudgetMb";
	private static final String RETENTION_MARGIN_PROPERTY = "pauc.lod.retentionMarginChunks";
	private static final String DYNAMIC_RETENTION_MARGIN_PROPERTY = "pauc.lod.dynamicRetentionMarginChunks";
	private static final String GENERATION_REQUEST_RATE_LIMIT_PROPERTY = "pauc.lod.generationRequestRateLimit";
	private static final String DYNAMIC_GENERATION_REQUEST_RATE_LIMIT_PROPERTY = "pauc.lod.dynamicGenerationRequestRateLimit";
	private static final String RECOMMENDED_VANILLA_DISTANCE_PROPERTY = "pauc.lod.recommendedVanillaDistance";
	private static final String AUTO_REDUCE_VANILLA_DISTANCE_PROPERTY = "pauc.lod.autoReduceVanillaDistance";
	private static final String ENABLE_N_SIZE_GENERATION_PROPERTY = "pauc.lod.enableNSizeGeneration";
	private static final String FILL_HOLES_PROPERTY = "pauc.lod.fillHoles";
	private static final String DIAGNOSTICS_PROPERTY = "pauc.lod.diagnostics";
	private static volatile boolean loaded;
	private static boolean lodsEnabled = true;
	private static boolean lodCloudsEnabled = true;
	private static int targetDistanceChunks = PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS;
	private static int memoryBudgetMb = defaultMemoryBudgetMb();
	private static int retentionMarginChunks = 12;
	private static int generationRequestRateLimit = defaultGenerationRequestRateLimit();
	private static boolean enableNSizeGeneration = true;
	private static boolean fillHoles = true;
	private static boolean diagnostics = true;

	private PauCLodClientSettings() {
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

	public static synchronized void reloadForClientSession() {
		resetDefaults();
		loaded = false;
		ensureLoaded();
		syncRuntimeProperties();
	}

	public static int targetDistanceChunks() {
		ensureLoaded();
		int configuredDistance = configuredTargetDistanceChunks();
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
		targetDistanceChunks = sanitizeTargetDistanceChunks(targetDistance);
		System.setProperty(TARGET_DISTANCE_PROPERTY, Integer.toString(targetDistanceChunks));
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
			return configuredMargin;
		}
		return sanitizeRetentionMarginChunks(readInt(dynamicMargin, configuredMargin));
	}

	public static int generationRequestRateLimit() {
		ensureLoaded();
		int configuredRate = sanitizeGenerationRequestRateLimit(readIntProperty(GENERATION_REQUEST_RATE_LIMIT_PROPERTY, generationRequestRateLimit));
		String dynamicRate = System.getProperty(DYNAMIC_GENERATION_REQUEST_RATE_LIMIT_PROPERTY);
		if (dynamicRate != null) {
			return sanitizeGenerationRequestRateLimit(readInt(dynamicRate, configuredRate));
		}
		if (!PauCLodShaderContext.isShaderPackInUse()) {
			return Math.max(112, configuredRate);
		}

		return configuredRate;
	}

	public static int recommendedVanillaDistanceChunks() {
		ensureLoaded();
		int fallback = PauCLodShaderContext.isShaderPackInUse() ? 8 : 12;
		return sanitizeRecommendedVanillaDistanceChunks(readIntProperty(RECOMMENDED_VANILLA_DISTANCE_PROPERTY, fallback));
	}

	public static boolean autoReduceVanillaDistance() {
		ensureLoaded();
		return readBooleanProperty(AUTO_REDUCE_VANILLA_DISTANCE_PROPERTY, false);
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

	public static String describePerformancePolicy() {
		return "lodPolicy[target="
			+ targetDistanceChunks()
			+ ", memory="
			+ memoryBudgetMb()
			+ "MiB, retainMargin="
			+ effectiveRetentionMarginChunks()
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
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
			lodsEnabled = Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, Boolean.toString(lodsEnabled)));
			lodCloudsEnabled = Boolean.parseBoolean(properties.getProperty(LOD_CLOUDS_KEY, Boolean.toString(lodCloudsEnabled)));
			targetDistanceChunks = sanitizeTargetDistanceChunks(readInt(properties.getProperty(TARGET_DISTANCE_KEY), targetDistanceChunks));
			memoryBudgetMb = sanitizeMemoryBudgetMb(readInt(properties.getProperty(MEMORY_BUDGET_KEY), memoryBudgetMb));
			retentionMarginChunks = sanitizeRetentionMarginChunks(readInt(properties.getProperty(RETENTION_MARGIN_KEY), retentionMarginChunks));
			generationRequestRateLimit = sanitizeGenerationRequestRateLimit(readInt(properties.getProperty(GENERATION_REQUEST_RATE_LIMIT_KEY), generationRequestRateLimit));
			enableNSizeGeneration = Boolean.parseBoolean(properties.getProperty(ENABLE_N_SIZE_GENERATION_KEY, Boolean.toString(enableNSizeGeneration)));
			fillHoles = Boolean.parseBoolean(properties.getProperty(FILL_HOLES_KEY, Boolean.toString(fillHoles)));
			diagnostics = Boolean.parseBoolean(properties.getProperty(DIAGNOSTICS_KEY, Boolean.toString(diagnostics)));
		} catch (IOException exception) {
			LOGGER.warn("PauC could not read LOD client settings from {}.", path, exception);
		}
		syncRuntimeProperties();
	}

	private static synchronized void save() {
		Path path = configPath();
		Properties properties = new Properties();
		properties.setProperty(ENABLED_KEY, Boolean.toString(lodsEnabled));
		properties.setProperty(LOD_CLOUDS_KEY, Boolean.toString(lodCloudsEnabled));
		properties.setProperty(TARGET_DISTANCE_KEY, Integer.toString(targetDistanceChunks));
		properties.setProperty(MEMORY_BUDGET_KEY, Integer.toString(memoryBudgetMb));
		properties.setProperty(RETENTION_MARGIN_KEY, Integer.toString(retentionMarginChunks));
		properties.setProperty(GENERATION_REQUEST_RATE_LIMIT_KEY, Integer.toString(generationRequestRateLimit));
		properties.setProperty(ENABLE_N_SIZE_GENERATION_KEY, Boolean.toString(enableNSizeGeneration));
		properties.setProperty(FILL_HOLES_KEY, Boolean.toString(fillHoles));
		properties.setProperty(DIAGNOSTICS_KEY, Boolean.toString(diagnostics));

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
		targetDistanceChunks = PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS;
		memoryBudgetMb = defaultMemoryBudgetMb();
		retentionMarginChunks = 12;
		generationRequestRateLimit = defaultGenerationRequestRateLimit();
		enableNSizeGeneration = true;
		fillHoles = true;
		diagnostics = true;
	}

	private static void syncRuntimeProperties() {
		System.setProperty(ENABLED_PROPERTY, Boolean.toString(lodsEnabled));
		System.setProperty(LOD_CLOUDS_PROPERTY, Boolean.toString(lodCloudsEnabled));
		System.setProperty(TARGET_DISTANCE_PROPERTY, Integer.toString(targetDistanceChunks));
	}

	private static int readIntProperty(String key, int fallback) {
		return readInt(System.getProperty(key), fallback);
	}

	private static boolean readBooleanProperty(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
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
		return Math.max(256, Math.min(768, value));
	}

	private static int sanitizeRetentionMarginChunks(int value) {
		return Math.max(0, Math.min(16, value));
	}

	private static int effectiveRetentionMarginChunks() {
		int configuredMargin = retentionMarginChunks();
		if (!PauCLodShaderContext.isShaderPackInUse()) {
			return Math.max(8, configuredMargin);
		}

		return configuredMargin;
	}

	private static int sanitizeGenerationRequestRateLimit(int value) {
		return Math.max(20, Math.min(128, value));
	}

	private static int sanitizeRecommendedVanillaDistanceChunks(int value) {
		return Math.max(4, Math.min(24, value));
	}

	private static int defaultMemoryBudgetMb() {
		long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
		return sanitizeMemoryBudgetMb((int) Math.max(256L, maxMemoryMb / 8L));
	}

	private static int defaultGenerationRequestRateLimit() {
		int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
		int memoryBonus = defaultMemoryBudgetMb() >= 640 ? 8 : 0;
		return sanitizeGenerationRequestRateLimit(48 + processors * 3 + memoryBonus);
	}
}
