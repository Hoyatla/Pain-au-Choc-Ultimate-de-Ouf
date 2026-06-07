package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodGameplayProfile;
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
	private static final String ALLOW_GENERATION_REDUCTION_PROPERTY = "pauc.client.allowFpsGovernorGenerationReduction";
	private static final String DYNAMIC_TARGET_DISTANCE_PROPERTY = "pauc.lod.dynamicTargetDistance";
	private static final String DYNAMIC_RETENTION_MARGIN_PROPERTY = "pauc.lod.dynamicRetentionMarginChunks";
	private static final String DYNAMIC_GENERATION_RATE_PROPERTY = "pauc.lod.dynamicGenerationRequestRateLimit";
	private static final String DYNAMIC_MAX_RESOLUTION_PROPERTY = "pauc.lod.dynamicMaxHorizontalResolution";
	private static final String DYNAMIC_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.dynamicHorizontalQuality";
	private static final String DYNAMIC_VERTICAL_QUALITY_PROPERTY = "pauc.lod.dynamicVerticalQuality";
	private static final String VILLAGE_GENERATION_RATE_PROPERTY = "pauc.lod.villageGenerationRequestRateLimit";
	private static final String VILLAGE_WARMUP_SCALE_PROPERTY = "pauc.lod.villageWarmupScale";
	private static final String VILLAGE_SEVERE_RATIO_PROPERTY = "pauc.lod.villageSevereFpsRatio";
	private static final String VILLAGE_SEVERE_GENERATION_RATE_PROPERTY = "pauc.lod.villageSevereGenerationRequestRateLimit";
	private static final String VILLAGE_SEVERE_GENERATION_FLOOR_PROPERTY = "pauc.lod.villageSevereGenerationFloor";
	private static final String VILLAGE_SEVERE_RETENTION_MARGIN_PROPERTY = "pauc.lod.villageSevereRetentionMarginChunks";
	private static final String VILLAGE_SEVERE_WARMUP_SCALE_PROPERTY = "pauc.lod.villageSevereWarmupScale";
	private static final String VILLAGE_SEVERE_WARMUP_FLOOR_PROPERTY = "pauc.lod.villageSevereWarmupFloor";
	private static final String VILLAGE_SEVERE_ENTER_TICKS_PROPERTY = "pauc.lod.villageSevereEnterTicks";
	private static final String VILLAGE_SEVERE_EXIT_TICKS_PROPERTY = "pauc.lod.villageSevereExitTicks";
	private static final String VILLAGE_SEVERE_MIN_HOLD_TICKS_PROPERTY = "pauc.lod.villageSevereMinHoldTicks";
	private static final String VILLAGE_SEVERE_RECOVERY_RATIO_PROPERTY = "pauc.lod.villageSevereRecoveryRatio";
	private static final String RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY = "pauc.runtime.villageSeverePressure";
	private static final String MOVEMENT_CATCHUP_GENERATION_RATE_PROPERTY = "pauc.lod.movementCatchupGenerationRequestRateLimit";
	private static final String MOVEMENT_CATCHUP_SEVERE_GENERATION_RATE_PROPERTY = "pauc.lod.movementCatchupSevereGenerationRequestRateLimit";
	private static final String MOVEMENT_CATCHUP_WARMUP_SCALE_PROPERTY = "pauc.lod.movementCatchupWarmupScale";
	private static final String SHADER_VISIBLE_FILL_GENERATION_FLOOR_PROPERTY = "pauc.lod.shaderVisibleFillGenerationFloor";
	private static final String VANILLA_VISIBLE_FILL_GENERATION_FLOOR_PROPERTY = "pauc.lod.vanillaVisibleFillGenerationFloor";
	private static final String QUALITY_UPGRADE_STABLE_TICKS_PROPERTY = "pauc.lod.qualityUpgradeStableTicks";
	private static final String DEFER_QUALITY_UPGRADE_DURING_FILL_PROPERTY = "pauc.lod.deferQualityUpgradeDuringFill";
	private static final String COARSE_PRESENTATION_FILL_PROPERTY = "pauc.lod.coarsePresentationFill";
	private static final String MAX_QUALITY_TIER_PROPERTY = "pauc.lod.maxAdaptiveQualityTier";
	private static final String SHADER_FALLBACK_ADAPTIVE_PRESENTATION_PROPERTY = "pauc.lod.shaderFallbackAdaptivePresentationQuality";
	private static final String SHADER_FALLBACK_RELIEF_MAX_QUALITY_PROPERTY = "pauc.lod.shaderFallbackReliefMaxQualityTier";
	private static final String SHADER_FALLBACK_BALANCED_MAX_QUALITY_PROPERTY = "pauc.lod.shaderFallbackBalancedMaxQualityTier";
	private static final String SHADER_FALLBACK_HEADROOM_MAX_QUALITY_PROPERTY = "pauc.lod.shaderFallbackHeadroomMaxQualityTier";
	private static final String SHADER_FALLBACK_QUALITY_UPGRADE_STABLE_TICKS_PROPERTY = "pauc.lod.shaderFallbackQualityUpgradeStableTicks";
	private static final String SHADER_FALLBACK_QUALITY_UPGRADE_MIN_RATIO_PROPERTY = "pauc.lod.shaderFallbackQualityUpgradeMinFpsRatio";
	private static final String SYNTHETIC_DH_POLISH_STABLE_TICKS_PROPERTY = "pauc.lod.syntheticDhPolishStableTicks";
	private static final String SYNTHETIC_DH_HEADROOM_STABLE_TICKS_PROPERTY = "pauc.lod.syntheticDhHeadroomStableTicks";
	private static final String SOLAS_SYNTHETIC_BALANCED_MAX_QUALITY_PROPERTY = "pauc.lod.solasSyntheticBalancedMaxQualityTier";
	private static final String SOLAS_SYNTHETIC_HEADROOM_MAX_QUALITY_PROPERTY = "pauc.lod.solasSyntheticHeadroomMaxQualityTier";
	private static final String EMERGENCY_GENERATION_CAP_PROPERTY = "pauc.lod.emergencyGenerationRequestCap";
	private static final String SHADER_EMERGENCY_GENERATION_CAP_PROPERTY = "pauc.lod.shaderEmergencyGenerationRequestCap";
	private static final int LOG_THROTTLE_TICKS = 100;
	private static double smoothedFps = -1.0D;
	private static double slowSmoothedFps = -1.0D;
	private static double lastConservativeFps = -1.0D;
	private static double lastQueuePressure;
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
	private static boolean lastVillageSeverePressure;
	private static boolean villageSeverePressure;
	private static int villageSevereCandidateTicks;
	private static int villageSevereRecoveryTicks;
	private static int villageSevereHoldTicks;
	private static boolean lastMovementCatchup;

	private PauCClientFpsGovernor() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			clearDynamicOverrides();
			return;
		}
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			suspendOutsideWorld();
			return;
		}

		int fps = queryFps(minecraft);
		if (fps <= 0) {
			applyPolicy(Policy.STARTUP, "fps-unavailable");
			return;
		}

		double previousSmoothedFps = smoothedFps;
		double smoothingAlpha = previousSmoothedFps < 0.0D
			? 1.0D
			: fps < previousSmoothedFps ? 0.45D : 0.30D;
		smoothedFps = previousSmoothedFps < 0.0D ? fps : (previousSmoothedFps * (1.0D - smoothingAlpha)) + (fps * smoothingAlpha);
		double previousSlowSmoothedFps = slowSmoothedFps;
		double slowSmoothingAlpha = previousSlowSmoothedFps < 0.0D
			? 1.0D
			: fps < previousSlowSmoothedFps ? 0.18D : 0.08D;
		slowSmoothedFps = previousSlowSmoothedFps < 0.0D ? fps : (previousSlowSmoothedFps * (1.0D - slowSmoothingAlpha)) + (fps * slowSmoothingAlpha);
		int targetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		double steadyFps = Math.min(smoothedFps, slowSmoothedFps);
		double ratio = steadyFps / targetFps;
		double rawRatio = fps / (double) targetFps;
		double queuePressure = PauCEmbeddedLodRuntimeDiagnostics.backlogPressure();
		double deliveryRatio = ratio * (1.0D - (queuePressure * 0.45D));
		double heapPressure = heapPressure();
		lastConservativeFps = steadyFps;
		lastQueuePressure = queuePressure;
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		PauCLodShaderProfiles.Family shaderFamily = PauCLodShaderProfiles.currentFamily();
		PauCLodShaderContext.DhShaderMode dhMode = PauCLodShaderContext.effectiveDhMode();
		String currentRuntime = runtimeId(shaderActive, shaderFamily);
		PauCClientGpuPathController.GpuSnapshot gpuSnapshot = PauCClientGpuPathController.getLastSnapshot();
		boolean directGpuUpload = PauCEmbeddedDhBridge.isDirectGpuUploadActive();
		int runtimeFps = fps > 0
			? Math.max(1, Math.min(fps, (int) Math.round(targetFps * deliveryRatio)))
			: fps;
		PauCLodShaderRuntime.updatePerformance(
			shaderActive,
			shaderFamily,
			runtimeFps,
			targetFps,
			heapPressure,
			PauCorRendererBridge.isMeshAccelerationActive(gpuSnapshot) || directGpuUpload,
			gpuSnapshot.multiDrawIndirect(),
			gpuSnapshot.bindlessIndirect()
		);

		if (!currentRuntime.equals(lastQualityRuntime)) {
			lowFpsStreak = 0;
			highFpsStreak = 0;
			qualityHeadroomStreak = 0;
			lastQualityRuntime = currentRuntime;
			LOGGER.info("PauC reset LOD quality headroom tracking after runtime switch to {}.", currentRuntime);
		}

		if (rawRatio < 0.66D || deliveryRatio < 0.68D || heapPressure > 0.90D || queuePressure > 0.28D) {
			lowFpsStreak = rawRatio < 0.66D ? Math.max(lowFpsStreak + 1, 3) : lowFpsStreak + 1;
			highFpsStreak = 0;
			qualityHeadroomStreak = 0;
		} else if (rawRatio > 1.05D && deliveryRatio > 1.02D && heapPressure < 0.78D && queuePressure < 0.08D) {
			highFpsStreak++;
			lowFpsStreak = 0;
			qualityHeadroomStreak++;
		} else {
			lowFpsStreak = Math.max(0, lowFpsStreak - 1);
			highFpsStreak = Math.max(0, highFpsStreak - 1);
			if (deliveryRatio >= 1.0D && heapPressure < 0.82D && queuePressure < 0.10D) {
				qualityHeadroomStreak++;
			} else if (deliveryRatio < 0.96D || heapPressure > 0.86D || queuePressure > 0.12D) {
				qualityHeadroomStreak = 0;
			}
		}
		updateVillageSeverePressure(deliveryRatio);

		Policy policy;
		if (rawRatio < 0.66D || deliveryRatio < 0.72D || queuePressure > 0.30D || lowFpsStreak >= 3) {
			policy = shaderActive ? Policy.SHADER_RELIEF : Policy.VANILLA_RELIEF;
		} else if (deliveryRatio < 0.95D || heapPressure > 0.82D || queuePressure > 0.12D) {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		} else if (highFpsStreak >= 3) {
			policy = shaderActive ? Policy.SHADER_HEADROOM : Policy.VANILLA_HEADROOM;
		} else {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		}
		if (shaderActive && dhMode == PauCLodShaderContext.DhShaderMode.SYNTHETIC_NATIVE) {
			int stableHeadroomTicks = readInt(
				SYNTHETIC_DH_HEADROOM_STABLE_TICKS_PROPERTY,
				shaderFamily == PauCLodShaderProfiles.Family.SOLAS ? 180 : 90,
				10,
				600
			);
			if (policy == Policy.SHADER_HEADROOM && (deliveryRatio < 1.08D || queuePressure > 0.04D || qualityHeadroomStreak < stableHeadroomTicks)) {
				policy = Policy.SHADER_BALANCED;
			}
		}

		maybeUpgradeQualityTier(deliveryRatio, heapPressure, targetFps, shaderActive, shaderFamily);
		applyPolicy(
			policy,
			"fps=" + round(steadyFps) + "/" + targetFps + ", raw=" + fps + ", queue=" + round(queuePressure * 100.0D) + "%, heap=" + round(heapPressure * 100.0D) + "%"
		);
	}

	public static void reset() {
		smoothedFps = -1.0D;
		slowSmoothedFps = -1.0D;
		lastConservativeFps = -1.0D;
		lastQueuePressure = 0.0D;
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
		lastVillageSeverePressure = false;
		lastMovementCatchup = false;
		villageSeverePressure = false;
		villageSevereCandidateTicks = 0;
		villageSevereRecoveryTicks = 0;
		villageSevereHoldTicks = 0;
		System.clearProperty(RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY);
		PauCLodShaderRuntime.updatePerformance(false, PauCLodShaderProfiles.Family.GENERIC, -1, 0, 0.0D, false, false, false);
		clearDynamicOverrides();
	}

	public static String describeState() {
		return "fpsGovernor[policy="
			+ lastPolicy.id
			+ ", fps="
			+ (smoothedFps >= 0.0D ? round(smoothedFps) : "-")
			+ ", steadyFps="
			+ (lastConservativeFps >= 0.0D ? round(lastConservativeFps) : "-")
			+ ", target="
			+ PauCClientTargetFps.effectiveTargetFps()
			+ ", queuePressure="
			+ round(lastQueuePressure * 100.0D)
			+ "%"
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
			+ ", villageSevere="
			+ (villageSeverePressure ? "on" : "off")
			+ ", movementCatchup="
			+ (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? "on" : "off")
			+ ", warmupScale="
			+ round(warmupAggressionScale())
			+ ", meshBudgetScale="
			+ round(meshBudgetScale())
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

		if (PauCClientChunkPriorityScorer.isMovementCatchupActive() && !villageSeverePressure) {
			return false;
		}

		return lastPolicy == Policy.STARTUP || lastPolicy == Policy.SHADER_RELIEF || lastPolicy == Policy.VANILLA_RELIEF;
	}

	public static double warmupAggressionScale() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return 1.0D;
		}

		double scale = switch (lastPolicy) {
			case STARTUP -> 0.70D;
			case SHADER_RELIEF -> 0.55D;
			case VANILLA_RELIEF -> 0.70D;
			case SHADER_BALANCED -> 0.85D;
			case VANILLA_BALANCED -> 1.00D;
			case SHADER_HEADROOM -> 1.05D;
			case VANILLA_HEADROOM -> 1.25D;
		};
		if (PauCVillagePerformanceDiagnostics.isVillagePressureActive()) {
			scale *= readDouble(VILLAGE_WARMUP_SCALE_PROPERTY, 0.65D, 0.25D, 1.0D);
		}
		if (villageSeverePressure) {
			scale *= readDouble(VILLAGE_SEVERE_WARMUP_SCALE_PROPERTY, 0.35D, 0.10D, 1.0D);
			scale = Math.max(scale, readDouble(VILLAGE_SEVERE_WARMUP_FLOOR_PROPERTY, 0.35D, 0.10D, 1.0D));
		}
		if (PauCClientChunkPriorityScorer.isMovementCatchupActive() && !villageSeverePressure) {
			scale = Math.max(scale, readDouble(MOVEMENT_CATCHUP_WARMUP_SCALE_PROPERTY, 1.20D, 0.50D, 1.50D));
		}
		if (highTargetVanillaMode()) {
			scale *= readDouble("pauc.lod.vanillaHighTargetWarmupScale", 0.82D, 0.35D, 1.0D);
			if (PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
				scale = Math.max(scale, 0.92D);
			}
		}
		return scale;
	}

	public static double meshBudgetScale() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return 1.0D;
		}
		boolean highTargetVanilla = highTargetVanillaMode();
		if (villageSeverePressure) {
			return readDouble("pauc.lod.villageSevereMeshBudgetScale", 0.45D, 0.20D, 1.0D);
		}
		if (PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
			return highTargetVanilla
				? readDouble("pauc.lod.vanillaHighTargetCatchupMeshBudgetScale", 0.92D, 0.35D, 1.0D)
				: 1.0D;
		}
		if (isUnderPressure()) {
			double pressureScale = readDouble("pauc.lod.pressureMeshBudgetScale", 0.75D, 0.35D, 1.0D);
			if (highTargetVanilla) {
				pressureScale = Math.min(
					pressureScale,
					readDouble("pauc.lod.vanillaHighTargetPressureMeshBudgetScale", 0.68D, 0.20D, 1.0D)
				);
			}
			return pressureScale;
		}
		return highTargetVanilla
			? readDouble("pauc.lod.vanillaHighTargetMeshBudgetScale", 0.78D, 0.35D, 1.0D)
			: 1.0D;
	}

	private static void updateVillageSeverePressure(double ratio) {
		boolean villagePressure = PauCVillagePerformanceDiagnostics.isVillagePressureActive();
		double enterRatio = readDouble(VILLAGE_SEVERE_RATIO_PROPERTY, 0.85D, 0.40D, 1.20D);
		double recoveryRatio = readDouble(VILLAGE_SEVERE_RECOVERY_RATIO_PROPERTY, 0.90D, enterRatio, 1.30D);
		boolean severeCandidate = villagePressure && ratio < enterRatio && lowFpsStreak >= 3;
		int enterTicks = readInt(VILLAGE_SEVERE_ENTER_TICKS_PROPERTY, 10, 1, 200);
		int exitTicks = readInt(VILLAGE_SEVERE_EXIT_TICKS_PROPERTY, 120, 1, 600);
		int minHoldTicks = readInt(VILLAGE_SEVERE_MIN_HOLD_TICKS_PROPERTY, 120, 1, 600);

		if (severeCandidate) {
			villageSevereCandidateTicks = Math.min(enterTicks, villageSevereCandidateTicks + 1);
			villageSevereRecoveryTicks = 0;
			if (!villageSeverePressure && villageSevereCandidateTicks >= enterTicks) {
				villageSeverePressure = true;
				villageSevereHoldTicks = minHoldTicks;
			} else if (villageSeverePressure) {
				villageSevereHoldTicks = Math.max(villageSevereHoldTicks, Math.max(1, minHoldTicks / 2));
			}
		} else {
			villageSevereCandidateTicks = 0;
			if (villageSeverePressure) {
				if (villageSevereHoldTicks > 0) {
					villageSevereHoldTicks--;
					villageSevereRecoveryTicks = 0;
				} else if (!villagePressure || ratio >= recoveryRatio) {
					villageSevereRecoveryTicks++;
					if (villageSevereRecoveryTicks >= exitTicks) {
						villageSeverePressure = false;
						villageSevereRecoveryTicks = 0;
					}
				} else {
					villageSevereRecoveryTicks = 0;
				}
			}
		}

		System.setProperty(RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY, Boolean.toString(villageSeverePressure));
	}

	private static void applyPolicy(Policy policy, String reason) {
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		int configuredTarget = PauCLodClientSettings.configuredTargetDistanceChunks();
		int targetDistance = readBoolean(ALLOW_DISTANCE_REDUCTION_PROPERTY, PauCLodGameplayProfile.allowDynamicTargetDistanceReduction())
			? Math.min(configuredTarget, policy.targetDistanceChunks)
			: configuredTarget;
		PauCLodShaderProfiles.Family shaderFamily = PauCLodShaderProfiles.currentFamily();
		boolean villagePressure = PauCVillagePerformanceDiagnostics.isVillagePressureActive();
		boolean movementCatchup = PauCClientChunkPriorityScorer.isMovementCatchupActive();
		boolean stabilizeLodPresentation = PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation();
		QualityTier achievedQualityTier = currentQualityTier(shaderActive, shaderFamily);
		QualityTier qualityTier = presentationQualityTier(achievedQualityTier, stabilizeLodPresentation);
		qualityTier = vanillaHighTargetPresentationTier(policy, qualityTier);
		String qualityLabel = qualityTier == achievedQualityTier ? qualityTier.id : qualityTier.id + "/achieved=" + achievedQualityTier.id;
		lastAppliedQualityTier = qualityTier;
		lastQualityRuntime = runtimeId(shaderActive, shaderFamily);
		int generationRequestRateLimit = policy.generationRequestRateLimit;
		if (shaderActive) {
			generationRequestRateLimit = PauCLodShaderRuntime.shaderGenerationRateLimit(generationRequestRateLimit);
		}
		int visibleFillFloor = shaderActive
			? readInt(SHADER_VISIBLE_FILL_GENERATION_FLOOR_PROPERTY, 256, 20, 512)
			: readInt(VANILLA_VISIBLE_FILL_GENERATION_FLOOR_PROPERTY, highTargetVanillaMode() ? 160 : 192, 20, 512);
		if (!villageSeverePressure || stabilizeLodPresentation) {
			generationRequestRateLimit = Math.max(generationRequestRateLimit, visibleFillFloor);
		}
		if (villagePressure && !stabilizeLodPresentation) {
			generationRequestRateLimit = Math.min(generationRequestRateLimit, readInt(VILLAGE_GENERATION_RATE_PROPERTY, 32, 4, 128));
		}
		if (villageSeverePressure && !stabilizeLodPresentation) {
			generationRequestRateLimit = Math.min(generationRequestRateLimit, readInt(VILLAGE_SEVERE_GENERATION_RATE_PROPERTY, 8, 2, 64));
			generationRequestRateLimit = Math.max(generationRequestRateLimit, readInt(VILLAGE_SEVERE_GENERATION_FLOOR_PROPERTY, 32, 8, 128));
		}
		if (movementCatchup) {
			generationRequestRateLimit = Math.max(
				generationRequestRateLimit,
				villageSeverePressure
					? readInt(MOVEMENT_CATCHUP_SEVERE_GENERATION_RATE_PROPERTY, shaderActive ? 128 : 96, 20, 256)
					: readInt(MOVEMENT_CATCHUP_GENERATION_RATE_PROPERTY, shaderActive ? 256 : 224, 20, 384)
			);
		}
		if (!readBoolean(ALLOW_GENERATION_REDUCTION_PROPERTY, PauCLodGameplayProfile.allowDynamicGenerationReduction())) {
			generationRequestRateLimit = Math.max(generationRequestRateLimit, PauCLodClientSettings.configuredGenerationRequestRateLimit());
		}
		boolean emergencyRelief = policy == Policy.SHADER_RELIEF || policy == Policy.VANILLA_RELIEF;
		if (emergencyRelief && !movementCatchup) {
			int emergencyCap = shaderActive
				? readInt(SHADER_EMERGENCY_GENERATION_CAP_PROPERTY, 256, 32, 768)
				: readInt(EMERGENCY_GENERATION_CAP_PROPERTY, 160, 32, 512);
			generationRequestRateLimit = Math.min(generationRequestRateLimit, emergencyCap);
		}
		int retentionMarginChunks = policy.retentionMarginChunks;
		if (villageSeverePressure) {
			retentionMarginChunks = Math.min(retentionMarginChunks, readInt(VILLAGE_SEVERE_RETENTION_MARGIN_PROPERTY, 12, 3, 12));
		}
		setSystemPropertyIfChanged(DYNAMIC_TARGET_DISTANCE_PROPERTY, Integer.toString(targetDistance));
		setSystemPropertyIfChanged(DYNAMIC_RETENTION_MARGIN_PROPERTY, Integer.toString(retentionMarginChunks));
		setSystemPropertyIfChanged(DYNAMIC_GENERATION_RATE_PROPERTY, Integer.toString(generationRequestRateLimit));
		setSystemPropertyIfChanged(DYNAMIC_MAX_RESOLUTION_PROPERTY, qualityTier.maxHorizontalResolution);
		setSystemPropertyIfChanged(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY, qualityTier.horizontalQuality);
		String verticalQuality = PauCClientSurfaceLodMode.adjustVerticalQuality(qualityTier.verticalQuality);
		setSystemPropertyIfChanged(DYNAMIC_VERTICAL_QUALITY_PROPERTY, verticalQuality);

		if (policy != lastPolicy
			|| villagePressure != lastVillagePressure
			|| villageSeverePressure != lastVillageSeverePressure
			|| movementCatchup != lastMovementCatchup
			|| ticksUntilNextLog-- <= 0) {
			ticksUntilNextLog = LOG_THROTTLE_TICKS;
			lastPolicy = policy;
			lastVillagePressure = villagePressure;
			lastVillageSeverePressure = villageSeverePressure;
			lastMovementCatchup = movementCatchup;
			LOGGER.info("PauC FPS governor selected {} ({}, quality={}, targetDistance={}, generation={} /s, retention={}, resolution={}, horizontal={}, vertical={}, villagePressure={}, villageSevere={}, movementCatchup={}).",
				policy.id,
				reason + ", " + PauCLodGameplayProfile.describe(),
				qualityLabel,
				targetDistance,
				generationRequestRateLimit,
				retentionMarginChunks,
				qualityTier.maxHorizontalResolution,
				qualityTier.horizontalQuality,
				verticalQuality,
				villagePressure ? "on" : "off",
				villageSeverePressure ? "on" : "off",
				movementCatchup ? "on" : "off"
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
		if (readBoolean(DEFER_QUALITY_UPGRADE_DURING_FILL_PROPERTY, false) && PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation()) {
			return;
		}
		boolean shaderFallback = shaderActive && PauCLodShaderContext.isFallbackActive();
		if (shaderFallback && PauCLodShaderRuntime.pressure() != PauCLodShaderRuntime.Pressure.HEADROOM) {
			return;
		}
		if (shaderFallback && ratio < readDouble(SHADER_FALLBACK_QUALITY_UPGRADE_MIN_RATIO_PROPERTY, 1.18D, 1.0D, 2.0D)) {
			return;
		}
		if (ratio < 1.0D || heapPressure > 0.82D) {
			return;
		}
		int stableTicks = shaderFallback
			? readInt(SHADER_FALLBACK_QUALITY_UPGRADE_STABLE_TICKS_PROPERTY, 160, 20, 1200)
			: readInt(QUALITY_UPGRADE_STABLE_TICKS_PROPERTY, 60, 10, 600);
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

	private static QualityTier presentationQualityTier(QualityTier achievedQualityTier, boolean stabilizeLodPresentation) {
		QualityTier qualityTier = achievedQualityTier;
		if (stabilizeLodPresentation && readBoolean(COARSE_PRESENTATION_FILL_PROPERTY, false)) {
			qualityTier = achievedQualityTier == QualityTier.NEAR ? QualityTier.FILL : achievedQualityTier;
		}
		qualityTier = shaderFallbackPresentationTier(qualityTier);
		return syntheticNativePresentationTier(qualityTier);
	}

	private static QualityTier vanillaHighTargetPresentationTier(Policy policy, QualityTier qualityTier) {
		if (!highTargetVanillaMode()) {
			return qualityTier;
		}
		QualityTier ceiling = switch (policy) {
			case STARTUP, VANILLA_RELIEF, VANILLA_BALANCED -> QualityTier.MID;
			case VANILLA_HEADROOM -> QualityTier.FAR;
			default -> qualityTier;
		};
		return qualityTier.atMost(ceiling);
	}

	private static QualityTier shaderFallbackPresentationTier(QualityTier qualityTier) {
		if (!readBoolean(SHADER_FALLBACK_ADAPTIVE_PRESENTATION_PROPERTY, true)) {
			return qualityTier;
		}
		if (!PauCLodShaderContext.isShaderPackInUse() || !PauCLodShaderContext.isFallbackActive()) {
			return qualityTier;
		}

		QualityTier maxTier = switch (PauCLodShaderRuntime.pressure()) {
			case RELIEF -> readQualityTier(SHADER_FALLBACK_RELIEF_MAX_QUALITY_PROPERTY, QualityTier.FAR);
			case BALANCED -> readQualityTier(SHADER_FALLBACK_BALANCED_MAX_QUALITY_PROPERTY, QualityTier.FAR);
			case HEADROOM -> readQualityTier(SHADER_FALLBACK_HEADROOM_MAX_QUALITY_PROPERTY, QualityTier.POLISH);
			default -> QualityTier.FAR;
		};
		return qualityTier.atMost(maxTier);
	}

	private static QualityTier syntheticNativePresentationTier(QualityTier qualityTier) {
		if (PauCLodShaderContext.effectiveDhMode() != PauCLodShaderContext.DhShaderMode.SYNTHETIC_NATIVE) {
			return qualityTier;
		}

		if (PauCLodShaderProfiles.currentFamily() == PauCLodShaderProfiles.Family.SOLAS) {
			QualityTier maxTier = switch (PauCLodShaderRuntime.pressure()) {
				case RELIEF -> QualityTier.NEAR;
				case BALANCED -> readQualityTier(SOLAS_SYNTHETIC_BALANCED_MAX_QUALITY_PROPERTY, QualityTier.MID);
				case HEADROOM -> readQualityTier(SOLAS_SYNTHETIC_HEADROOM_MAX_QUALITY_PROPERTY, QualityTier.MID);
				default -> QualityTier.MID;
			};
			return qualityTier.atMost(maxTier);
		}

		int polishStableTicks = readInt(SYNTHETIC_DH_POLISH_STABLE_TICKS_PROPERTY, 180, 20, 1200);
		QualityTier maxTier = switch (PauCLodShaderRuntime.pressure()) {
			case RELIEF -> QualityTier.NEAR;
			case BALANCED -> QualityTier.MID;
			case HEADROOM -> qualityHeadroomStreak >= polishStableTicks ? QualityTier.POLISH : QualityTier.FAR;
			default -> QualityTier.MID;
		};
		return qualityTier.atMost(maxTier);
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
		return shaderActive
			? shaderFamily.name().toLowerCase(Locale.ROOT) + ":" + PauCLodShaderContext.effectiveDhMode().id()
			: "vanilla";
	}

	private static boolean highTargetVanillaMode() {
		return !PauCLodShaderContext.isShaderPackInUse()
			&& PauCLodGameplayProfile.current() == PauCLodGameplayProfile.Profile.COMPETITIVE
			&& PauCClientTargetFps.effectiveTargetFps() >= readInt("pauc.lod.vanillaHighTargetFps", 132, 90, 240);
	}

	private static void clearDynamicOverrides() {
		System.clearProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY);
		System.clearProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY);
		System.clearProperty(DYNAMIC_GENERATION_RATE_PROPERTY);
		System.clearProperty(DYNAMIC_MAX_RESOLUTION_PROPERTY);
		System.clearProperty(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY);
		System.clearProperty(DYNAMIC_VERTICAL_QUALITY_PROPERTY);
	}

	private static void setSystemPropertyIfChanged(String key, String value) {
		if (!value.equals(System.getProperty(key))) {
			System.setProperty(key, value);
		}
	}

	private static void suspendOutsideWorld() {
		smoothedFps = -1.0D;
		slowSmoothedFps = -1.0D;
		lastConservativeFps = -1.0D;
		lastQueuePressure = 0.0D;
		lowFpsStreak = 0;
		highFpsStreak = 0;
		qualityHeadroomStreak = 0;
		villageSeverePressure = false;
		villageSevereCandidateTicks = 0;
		villageSevereRecoveryTicks = 0;
		villageSevereHoldTicks = 0;
		lastVillagePressure = false;
		lastVillageSeverePressure = false;
		lastMovementCatchup = false;
		lastPolicy = Policy.STARTUP;
		System.clearProperty(RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY);
		clearDynamicOverrides();
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

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	private static QualityTier readQualityTier(String key, QualityTier fallback) {
		String rawValue = System.getProperty(key);
		if (rawValue == null || rawValue.isBlank()) {
			return fallback;
		}
		String normalized = rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT);
		for (QualityTier tier : QualityTier.values()) {
			if (tier.name().equals(normalized) || tier.id.toUpperCase(Locale.ROOT).equals(normalized)) {
				return tier;
			}
		}
		return fallback;
	}

	private enum Policy {
		STARTUP("startup", 48, 12, 96),
		SHADER_RELIEF("shader-relief", 48, 10, 96),
		SHADER_BALANCED("shader-balanced", 56, 12, 128),
		SHADER_HEADROOM("shader-headroom", 80, 12, 160),
		VANILLA_RELIEF("vanilla-relief", 64, 10, 112),
		VANILLA_BALANCED("vanilla-balanced", 96, 12, 160),
		VANILLA_HEADROOM("vanilla-headroom", PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS, 14, 192);

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
		FILL("fill", "CHUNK", "LOW", "LOW"),
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

		private QualityTier atMost(QualityTier maxTier) {
			return ordinal() > maxTier.ordinal() ? maxTier : this;
		}

		private static QualityTier maxAllowed() {
			String rawValue = System.getProperty(MAX_QUALITY_TIER_PROPERTY);
			if (rawValue == null || rawValue.isBlank()) {
				if (!PauCLodShaderContext.isShaderPackInUse()) {
					return switch (PauCLodGameplayProfile.current()) {
						case COMPETITIVE, BALANCED -> FAR;
						case CINEMATIC -> POLISH;
					};
				}
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
