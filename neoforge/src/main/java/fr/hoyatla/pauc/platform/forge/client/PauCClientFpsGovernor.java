package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Locale;

public final class PauCClientFpsGovernor {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.client.fpsGovernor";
	private static final String ALLOW_DISTANCE_REDUCTION_PROPERTY = "pauc.client.allowFpsGovernorDistanceReduction";
	private static final String DYNAMIC_TARGET_DISTANCE_PROPERTY = "pauc.lod.dynamicTargetDistance";
	private static final String DYNAMIC_RETENTION_MARGIN_PROPERTY = "pauc.lod.dynamicRetentionMarginChunks";
	private static final String DYNAMIC_GENERATION_RATE_PROPERTY = "pauc.lod.dynamicGenerationRequestRateLimit";
	private static final String DYNAMIC_MAX_RESOLUTION_PROPERTY = "pauc.lod.dynamicMaxHorizontalResolution";
	private static final String DYNAMIC_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.dynamicHorizontalQuality";
	private static final String DYNAMIC_VERTICAL_QUALITY_PROPERTY = "pauc.lod.dynamicVerticalQuality";
	private static final String VILLAGE_GENERATION_RATE_PROPERTY = "pauc.lod.villageGenerationRequestRateLimit";
	private static final String VILLAGE_WARMUP_SCALE_PROPERTY = "pauc.lod.villageWarmupScale";
	private static final String QUALITY_UPGRADE_STABLE_TICKS_PROPERTY = "pauc.lod.qualityUpgradeStableTicks";
	private static final String MAX_QUALITY_TIER_PROPERTY = "pauc.lod.maxAdaptiveQualityTier";
	private static final int LOG_THROTTLE_TICKS = 100;
	private static double smoothedFps = -1.0D;
	private static Policy lastPolicy = Policy.STARTUP;
	private static QualityTier vanillaAchievedQualityTier = QualityTier.NEAR;
	private static final EnumMap<PauCLodShaderProfiles.Family, QualityTier> shaderAchievedQualityTiers = new EnumMap<>(PauCLodShaderProfiles.Family.class);
	private static QualityTier lastAppliedQualityTier = QualityTier.NEAR;
	private static String lastQualityRuntime = "vanilla";
	private static int ticksUntilNextLog;
	private static int lowFpsStreak;
	private static int highFpsStreak;
	private static int qualityHeadroomStreak;
	private static boolean lastVillagePressure;

	private PauCClientFpsGovernor() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			clearDynamicOverrides();
			return;
		}

		int fps = queryFps(minecraft);
		if (fps <= 0) {
			applyPolicy(Policy.STARTUP, "fps-unavailable");
			return;
		}

		smoothedFps = smoothedFps < 0.0D ? fps : (smoothedFps * 0.88D) + (fps * 0.12D);
		int targetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		double ratio = smoothedFps / targetFps;
		double heapPressure = heapPressure();
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		PauCLodShaderProfiles.Family shaderFamily = PauCLodShaderProfiles.currentFamily();
		PauCClientGpuPathController.GpuSnapshot gpuSnapshot = PauCClientGpuPathController.getLastSnapshot();
		PauCLodShaderRuntime.updatePerformance(
			shaderActive,
			shaderFamily,
			fps,
			targetFps,
			heapPressure,
			PauCorRendererBridge.isMeshAccelerationActive(gpuSnapshot),
			gpuSnapshot.multiDrawIndirect(),
			gpuSnapshot.bindlessIndirect()
		);

		if (ratio < 0.68D || heapPressure > 0.90D) {
			lowFpsStreak++;
			highFpsStreak = 0;
			qualityHeadroomStreak = 0;
		} else if (ratio > 1.08D && heapPressure < 0.78D) {
			highFpsStreak++;
			lowFpsStreak = 0;
			qualityHeadroomStreak++;
		} else {
			lowFpsStreak = Math.max(0, lowFpsStreak - 1);
			highFpsStreak = Math.max(0, highFpsStreak - 1);
			if (ratio >= 1.0D && heapPressure < 0.82D) {
				qualityHeadroomStreak++;
			} else if (ratio < 0.96D || heapPressure > 0.86D) {
				qualityHeadroomStreak = 0;
			}
		}

		Policy policy;
		if (lowFpsStreak >= 3) {
			policy = shaderActive ? Policy.SHADER_RELIEF : Policy.VANILLA_RELIEF;
		} else if (ratio < 0.90D || heapPressure > 0.82D) {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		} else if (highFpsStreak >= 5) {
			policy = shaderActive ? Policy.SHADER_HEADROOM : Policy.VANILLA_HEADROOM;
		} else {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		}

		maybeUpgradeQualityTier(ratio, heapPressure, targetFps, shaderActive, shaderFamily);
		applyPolicy(policy, "fps=" + round(smoothedFps) + "/" + targetFps + ", heap=" + round(heapPressure * 100.0D) + "%");
	}

	public static void reset() {
		smoothedFps = -1.0D;
		lastPolicy = Policy.STARTUP;
		vanillaAchievedQualityTier = QualityTier.NEAR;
		shaderAchievedQualityTiers.clear();
		lastAppliedQualityTier = QualityTier.NEAR;
		lastQualityRuntime = "vanilla";
		ticksUntilNextLog = 0;
		lowFpsStreak = 0;
		highFpsStreak = 0;
		qualityHeadroomStreak = 0;
		lastVillagePressure = false;
		PauCLodShaderRuntime.updatePerformance(false, PauCLodShaderProfiles.Family.GENERIC, -1, 200, 0.0D, false, false, false);
		clearDynamicOverrides();
	}

	public static String describeState() {
		return "fpsGovernor[policy="
			+ lastPolicy.id
			+ ", fps="
			+ (smoothedFps >= 0.0D ? round(smoothedFps) : "-")
			+ ", target="
			+ PauCClientTargetFps.effectiveTargetFps()
			+ ", quality="
			+ lastAppliedQualityTier.id
			+ "@"
			+ lastQualityRuntime
			+ ", qualityStreak="
			+ qualityHeadroomStreak
			+ ", targetDistance="
			+ System.getProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY, "-")
			+ ", generation="
			+ System.getProperty(DYNAMIC_GENERATION_RATE_PROPERTY, "-")
			+ ", retention="
			+ System.getProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY, "-")
			+ ", villagePressure="
			+ (PauCVillagePerformanceDiagnostics.isVillagePressureActive() ? "on" : "off")
			+ ", warmupScale="
			+ round(warmupAggressionScale())
			+ ", "
			+ PauCLodShaderRuntime.describe()
			+ ", "
			+ PauCClientSurfaceLodMode.describeState()
			+ "]";
	}

	public static boolean isUnderPressure() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return false;
		}

		return lastPolicy == Policy.STARTUP || lastPolicy == Policy.SHADER_RELIEF || lastPolicy == Policy.VANILLA_RELIEF;
	}

	public static double warmupAggressionScale() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return 1.0D;
		}

		double scale = switch (lastPolicy) {
			case STARTUP -> 0.55D;
			case SHADER_RELIEF -> 0.35D;
			case VANILLA_RELIEF -> 0.45D;
			case SHADER_BALANCED -> 0.70D;
			case VANILLA_BALANCED -> 0.90D;
			case SHADER_HEADROOM -> 1.05D;
			case VANILLA_HEADROOM -> 1.25D;
		};
		if (PauCVillagePerformanceDiagnostics.isVillagePressureActive()) {
			scale *= readDouble(VILLAGE_WARMUP_SCALE_PROPERTY, 0.65D, 0.25D, 1.0D);
		}
		return scale;
	}

	private static void applyPolicy(Policy policy, String reason) {
		int configuredTarget = PauCLodClientSettings.configuredTargetDistanceChunks();
		int targetDistance = Boolean.parseBoolean(System.getProperty(ALLOW_DISTANCE_REDUCTION_PROPERTY, "false"))
			? Math.min(configuredTarget, policy.targetDistanceChunks)
			: configuredTarget;
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		PauCLodShaderProfiles.Family shaderFamily = PauCLodShaderProfiles.currentFamily();
		QualityTier qualityTier = currentQualityTier(shaderActive, shaderFamily);
		lastAppliedQualityTier = qualityTier;
		lastQualityRuntime = runtimeId(shaderActive, shaderFamily);
		boolean villagePressure = PauCVillagePerformanceDiagnostics.isVillagePressureActive();
		int generationRequestRateLimit = policy.generationRequestRateLimit;
		if (shaderActive) {
			generationRequestRateLimit = PauCLodShaderRuntime.shaderGenerationRateLimit(generationRequestRateLimit);
		}
		if (villagePressure) {
			generationRequestRateLimit = Math.min(generationRequestRateLimit, readInt(VILLAGE_GENERATION_RATE_PROPERTY, 18, 4, 128));
		}
		System.setProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY, Integer.toString(targetDistance));
		System.setProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY, Integer.toString(policy.retentionMarginChunks));
		System.setProperty(DYNAMIC_GENERATION_RATE_PROPERTY, Integer.toString(generationRequestRateLimit));
		System.setProperty(DYNAMIC_MAX_RESOLUTION_PROPERTY, qualityTier.maxHorizontalResolution);
		System.setProperty(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY, qualityTier.horizontalQuality);
		String verticalQuality = PauCClientSurfaceLodMode.adjustVerticalQuality(qualityTier.verticalQuality);
		System.setProperty(DYNAMIC_VERTICAL_QUALITY_PROPERTY, verticalQuality);

		if (policy != lastPolicy || villagePressure != lastVillagePressure || ticksUntilNextLog-- <= 0) {
			ticksUntilNextLog = LOG_THROTTLE_TICKS;
			lastPolicy = policy;
			lastVillagePressure = villagePressure;
			LOGGER.info("PauC FPS governor selected {} ({}, quality={}, targetDistance={}, generation={} /s, retention={}, resolution={}, horizontal={}, vertical={}, villagePressure={}).",
				policy.id,
				reason,
				qualityTier.id,
				targetDistance,
				generationRequestRateLimit,
				policy.retentionMarginChunks,
				qualityTier.maxHorizontalResolution,
				qualityTier.horizontalQuality,
				verticalQuality,
				villagePressure ? "on" : "off"
			);
		}
	}

	private static void maybeUpgradeQualityTier(
		double ratio,
		double heapPressure,
		int targetFps,
		boolean shaderActive,
		PauCLodShaderProfiles.Family shaderFamily
	) {
		QualityTier maxTier = QualityTier.maxAllowed();
		QualityTier currentTier = currentQualityTier(shaderActive, shaderFamily);
		if (currentTier.ordinal() >= maxTier.ordinal()) {
			return;
		}
		if (ratio < 1.0D || heapPressure > 0.82D) {
			return;
		}
		int stableTicks = readInt(QUALITY_UPGRADE_STABLE_TICKS_PROPERTY, 60, 10, 600);
		if (qualityHeadroomStreak < stableTicks) {
			return;
		}

		QualityTier previous = currentTier;
		QualityTier next = currentTier.next(maxTier);
		setCurrentQualityTier(shaderActive, shaderFamily, next);
		qualityHeadroomStreak = 0;
		LOGGER.info("PauC raised {} LOD quality from {} to {} after holding the {} FPS target; existing upgraded LODs will not be downgraded in that runtime.",
			runtimeId(shaderActive, shaderFamily),
			previous.id,
			next.id,
			targetFps
		);
	}

	private static QualityTier currentQualityTier(boolean shaderActive, PauCLodShaderProfiles.Family shaderFamily) {
		if (!shaderActive) {
			return vanillaAchievedQualityTier;
		}
		return shaderAchievedQualityTiers.getOrDefault(shaderFamily, QualityTier.NEAR);
	}

	private static void setCurrentQualityTier(boolean shaderActive, PauCLodShaderProfiles.Family shaderFamily, QualityTier tier) {
		if (!shaderActive) {
			vanillaAchievedQualityTier = tier;
			return;
		}
		shaderAchievedQualityTiers.put(shaderFamily, tier);
	}

	private static String runtimeId(boolean shaderActive, PauCLodShaderProfiles.Family shaderFamily) {
		return shaderActive ? shaderFamily.name().toLowerCase(Locale.ROOT) : "vanilla";
	}

	private static void clearDynamicOverrides() {
		System.clearProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY);
		System.clearProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY);
		System.clearProperty(DYNAMIC_GENERATION_RATE_PROPERTY);
		System.clearProperty(DYNAMIC_MAX_RESOLUTION_PROPERTY);
		System.clearProperty(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY);
		System.clearProperty(DYNAMIC_VERTICAL_QUALITY_PROPERTY);
	}

	private static int queryFps(Minecraft minecraft) {
		return PauCClientFrameMetrics.queryFps(minecraft);
	}

	private static double heapPressure() {
		Runtime runtime = Runtime.getRuntime();
		long max = runtime.maxMemory();
		if (max <= 0L) {
			return 0.0D;
		}

		long used = runtime.totalMemory() - runtime.freeMemory();
		return Math.max(0.0D, Math.min(1.0D, used / (double) max));
	}

	private static String round(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private enum Policy {
		STARTUP("startup", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 20),
		SHADER_RELIEF("shader-relief", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 20),
		SHADER_BALANCED("shader-balanced", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 32),
		SHADER_HEADROOM("shader-headroom", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 56),
		VANILLA_RELIEF("vanilla-relief", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 20),
		VANILLA_BALANCED("vanilla-balanced", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 40),
		VANILLA_HEADROOM("vanilla-headroom", PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS, 12, 80);

		private final String id;
		private final int targetDistanceChunks;
		private final int retentionMarginChunks;
		private final int generationRequestRateLimit;

		Policy(
			String id,
			int targetDistanceChunks,
			int retentionMarginChunks,
			int generationRequestRateLimit
		) {
			this.id = id;
			this.targetDistanceChunks = targetDistanceChunks;
			this.retentionMarginChunks = retentionMarginChunks;
			this.generationRequestRateLimit = generationRequestRateLimit;
		}
	}

	private enum QualityTier {
		NEAR("near", "TWO_BLOCKS", "LOW", "MEDIUM"),
		MID("mid", "TWO_BLOCKS", "MEDIUM", "MEDIUM"),
		FAR("far", "BLOCK", "MEDIUM", "MEDIUM"),
		POLISH("polish", "BLOCK", "HIGH", "HIGH");

		private final String id;
		private final String maxHorizontalResolution;
		private final String horizontalQuality;
		private final String verticalQuality;

		QualityTier(String id, String maxHorizontalResolution, String horizontalQuality, String verticalQuality) {
			this.id = id;
			this.maxHorizontalResolution = maxHorizontalResolution;
			this.horizontalQuality = horizontalQuality;
			this.verticalQuality = verticalQuality;
		}

		private QualityTier next(QualityTier maxTier) {
			QualityTier[] tiers = values();
			return tiers[Math.min(maxTier.ordinal(), ordinal() + 1)];
		}

		private static QualityTier maxAllowed() {
			String rawValue = System.getProperty(MAX_QUALITY_TIER_PROPERTY);
			if (rawValue == null || rawValue.isBlank()) {
				return POLISH;
			}
			String normalized = rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT);
			for (QualityTier tier : values()) {
				if (tier.name().equals(normalized) || tier.id.toUpperCase(Locale.ROOT).equals(normalized)) {
					return tier;
				}
			}
			return POLISH;
		}
	}
}
