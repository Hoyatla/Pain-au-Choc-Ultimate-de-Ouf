package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodDiagnostics;
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
	private static final String PRESERVE_CONFIGURED_TARGET_DISTANCE_VANILLA_PROPERTY = "pauc.lod.preserveConfiguredTargetDistanceInVanilla";
	// Honor the player's configured LOD distance identically with shaders ON or OFF, so toggling a shader never changes
	// the view distance. Defaults true (falls back to the legacy vanilla-only flag if set). Full-quality principle:
	// never shrink the configured LOD distance for fps.
	private static final String PRESERVE_CONFIGURED_TARGET_DISTANCE_PROPERTY = "pauc.lod.preserveConfiguredTargetDistance";
	private static final String DYNAMIC_TARGET_DISTANCE_PROPERTY = "pauc.lod.dynamicTargetDistance";
	private static final String DYNAMIC_RETENTION_MARGIN_PROPERTY = "pauc.lod.dynamicRetentionMarginChunks";
	private static final String DYNAMIC_GENERATION_RATE_PROPERTY = "pauc.lod.dynamicGenerationRequestRateLimit";
	private static final String DYNAMIC_MAX_RESOLUTION_PROPERTY = "pauc.lod.dynamicMaxHorizontalResolution";
	private static final String DYNAMIC_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.dynamicHorizontalQuality";
	private static final String DYNAMIC_VERTICAL_QUALITY_PROPERTY = "pauc.lod.dynamicVerticalQuality";
	private static final String RUNTIME_HUD_RAW_FPS_PROPERTY = PauCLodDiagnostics.HUD_RAW_FPS_PROPERTY;
	private static final String RUNTIME_HUD_AVERAGE_FPS_PROPERTY = PauCLodDiagnostics.HUD_AVERAGE_FPS_PROPERTY;
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
	private static final String QUALITY_UPGRADE_CONFIRMATIONS_PROPERTY = "pauc.lod.qualityUpgradeConfirmations";
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
	private static final String PRESENTATION_QUALITY_DROP_STABLE_TICKS_PROPERTY = "pauc.lod.presentationQualityDropStableTicks";
	private static final String PRESENTATION_QUALITY_DROP_HOLD_MS_PROPERTY = "pauc.lod.presentationQualityDropHoldMs";
	private static final String EMERGENCY_GENERATION_CAP_PROPERTY = "pauc.lod.emergencyGenerationRequestCap";
	private static final String SHADER_EMERGENCY_GENERATION_CAP_PROPERTY = "pauc.lod.shaderEmergencyGenerationRequestCap";
	private static final String RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY = "pauc.runtime.frameWatchdogSpike";
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
	private static int qualityUpgradeConfirmations;
	private static boolean lastVillagePressure;
	private static boolean lastVillageSeverePressure;
	private static boolean villageSeverePressure;
	private static int villageSevereCandidateTicks;
	private static int villageSevereRecoveryTicks;
	private static int villageSevereHoldTicks;
	private static boolean idleQueueResolvedState;
	private static boolean lastMovementCatchup;
	private static boolean backlogResolved;
	private static boolean lastWorkloadRecovered;
	private static int appliedTargetDistance = -1;
	private static int lastCommandedTargetDistance = -1;
	private static int lastCommandedGenerationRate = -1;
	private static int lastCommandedRetentionMargin = -1;
	private static int lastVisibleFillFloor = -1;
	private static boolean lastNearCoverageDebt;
	private static String lastPolicyReason = "-";
	private static int pendingTargetDistance = -1;
	private static int pendingTargetDistanceTicks;
	private static long pendingTargetDistanceSinceMillis;
	private static long lastTargetDistanceDecreaseAtMillis;
	private static int lastConfiguredTargetDistance = -1;
	private static int lastPresentationQualityTargetDistance = -1;
	private static QualityTier pendingPresentationQualityTier;
	private static int pendingPresentationQualityTicks;
	private static long pendingPresentationQualitySinceMillis;

	private PauCClientFpsGovernor() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			clearRuntimeHudFps();
			resetTargetDistanceStabilizer();
			clearPresentationQualityStabilizer();
			clearDynamicOverrides();
			return;
		}
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			suspendOutsideWorld();
			return;
		}
		synchronizeConfiguredTargetDistance();

		int fps = queryFps(minecraft);
		if (fps <= 0) {
			clearRuntimeHudFps();
			applyPolicy(Policy.STARTUP, "fps-unavailable");
			return;
		}

		double queuePressure = PauCEmbeddedLodRuntimeDiagnostics.backlogPressure();
		double previousSmoothedFps = smoothedFps;
		double smoothingAlpha = fpsSmoothingAlpha(previousSmoothedFps, fps, queuePressure, false);
		smoothedFps = previousSmoothedFps < 0.0D ? fps : (previousSmoothedFps * (1.0D - smoothingAlpha)) + (fps * smoothingAlpha);
		double previousSlowSmoothedFps = slowSmoothedFps;
		double slowSmoothingAlpha = fpsSmoothingAlpha(previousSlowSmoothedFps, fps, queuePressure, true);
		slowSmoothedFps = previousSlowSmoothedFps < 0.0D ? fps : (previousSlowSmoothedFps * (1.0D - slowSmoothingAlpha)) + (fps * slowSmoothingAlpha);
		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(minecraft);
		int reportedTargetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		int targetFps = reportedTargetFps;
		double steadyFps = conservativeSteadyFps(smoothedFps, slowSmoothedFps, fps, targetFps, queuePressure);
		double ratio = steadyFps / targetFps;
		double rawRatio = fps / (double) targetFps;
		double deliveryRatio = ratio * (1.0D - (queuePressure * 0.38D));
		double heapPressure = heapPressure();
		PauCWorkloadState.Snapshot workloadSnapshot = PauCWorkloadState.update(queuePressure, heapPressure, deliveryRatio, villageSeverePressure);
		boolean queueDrained = workloadSnapshot.queueDrained();
		boolean queueFullyDrained = workloadSnapshot.queueFullyDrained();
		lastConservativeFps = steadyFps;
		updateRuntimeHudFps(fps, smoothedFps);
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
		PauCClientFluidityState.Snapshot fluiditySnapshot = PauCClientFluidityState.update(
			minecraft,
			fps,
			targetFps,
			steadyFps,
			deliveryRatio,
			queuePressure,
			heapPressure,
			shaderActive,
			shaderFamily,
			dhMode,
			workloadSnapshot
		);

		// Shader GPU floor actuator: pull shadow render distance in when the measured (steady) fps is below the pack's
		// floor, restore when it clears. Driven by real fps, ramps slowly (no strobe). See PauCShaderShadowBudget.
		fr.hoyatla.pauc.lod.PauCShaderShadowBudget.update(shaderActive, shaderFamily.name(), (int) Math.round(steadyFps));

		if (!currentRuntime.equals(lastQualityRuntime)) {
			lowFpsStreak = 0;
			highFpsStreak = 0;
			qualityHeadroomStreak = 0;
			qualityUpgradeConfirmations = 0;
			lastQualityRuntime = currentRuntime;
			clearPresentationQualityStabilizer();
			PauCEmbeddedDhBridge.resetPresentationStability("runtime-switch:" + currentRuntime);
			LOGGER.info("PauC reset LOD quality headroom tracking after runtime switch to {}.", currentRuntime);
		}

		boolean frameWatchdogSpike = workloadSnapshot.frameWatchdogSpike();
		boolean idleQueueResolved = workloadSnapshot.idleQueueResolved();
		idleQueueResolvedState = idleQueueResolved;
		backlogResolved = workloadSnapshot.backlogResolved();
		boolean workloadRecovered = workloadSnapshot.workloadRecovered();
		lastWorkloadRecovered = workloadRecovered;
		boolean paucResolved = workloadSnapshot.paucResolved();
		boolean externalFpsDip = workloadSnapshot.externalFpsDip();
		boolean resolvedFpsDip = externalFpsDip
			|| (paucResolved
				&& queueFullyDrained
				&& !frameWatchdogSpike
				&& heapPressure < 0.88D
				&& queuePressure < 0.08D);
		boolean reliefEligibleFromFps = !externalFpsDip && !paucResolved && (rawRatio < 0.66D || deliveryRatio < 0.68D);
		if (frameWatchdogSpike || reliefEligibleFromFps || heapPressure > 0.90D || queuePressure > 0.28D) {
			lowFpsStreak = rawRatio < 0.66D ? Math.max(lowFpsStreak + 1, 3) : lowFpsStreak + 1;
			highFpsStreak = 0;
			qualityHeadroomStreak = 0;
			qualityUpgradeConfirmations = 0;
		} else if (resolvedFpsDip) {
			lowFpsStreak = Math.max(0, lowFpsStreak - 3);
			highFpsStreak = Math.max(0, highFpsStreak - 1);
			if (deliveryRatio >= 0.97D && heapPressure < 0.84D && queuePressure < 0.10D) {
				qualityHeadroomStreak++;
			} else if (deliveryRatio < 0.92D || heapPressure > 0.86D || queuePressure > 0.12D) {
				qualityHeadroomStreak = 0;
				qualityUpgradeConfirmations = 0;
			}
		} else if (workloadRecovered) {
			lowFpsStreak = Math.max(0, lowFpsStreak - 2);
			if (deliveryRatio >= 0.99D && heapPressure < 0.82D && queuePressure < 0.12D) {
				qualityHeadroomStreak++;
			} else if (deliveryRatio < 0.94D || heapPressure > 0.86D || queuePressure > 0.12D) {
				qualityHeadroomStreak = 0;
				qualityUpgradeConfirmations = 0;
			}
		} else if (rawRatio > 1.03D && deliveryRatio > 1.0D && heapPressure < 0.80D && queuePressure < 0.10D) {
			highFpsStreak++;
			lowFpsStreak = 0;
			qualityHeadroomStreak += rawRatio > 1.10D && queuePressure < 0.05D ? 2 : 1;
		} else {
			lowFpsStreak = Math.max(0, lowFpsStreak - 1);
			highFpsStreak = Math.max(0, highFpsStreak - 1);
			if (deliveryRatio >= 0.99D && heapPressure < 0.82D && queuePressure < 0.12D) {
				qualityHeadroomStreak++;
			} else if (deliveryRatio < 0.96D || heapPressure > 0.86D || queuePressure > 0.12D) {
				qualityHeadroomStreak = 0;
				qualityUpgradeConfirmations = 0;
			}
		}
		updateVillageSeverePressure(deliveryRatio, workloadRecovered, queueDrained, queueFullyDrained, idleQueueResolved);

		Policy policy;
		boolean reliefPolicyFromFps = !resolvedFpsDip && !paucResolved && (rawRatio < 0.66D || deliveryRatio < 0.72D);
		if (frameWatchdogSpike || reliefPolicyFromFps || queuePressure > 0.30D || lowFpsStreak >= 3) {
			policy = shaderActive ? Policy.SHADER_RELIEF : Policy.VANILLA_RELIEF;
		} else if (deliveryRatio < 0.95D || heapPressure > 0.82D || queuePressure > 0.12D) {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		} else if (highFpsStreak >= 2) {
			policy = shaderActive ? Policy.SHADER_HEADROOM : Policy.VANILLA_HEADROOM;
		} else {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		}
		if (fluiditySnapshot.band() == PauCClientFluidityState.Band.RELIEF && !backlogResolved && !paucResolved) {
			policy = shaderActive ? Policy.SHADER_RELIEF : Policy.VANILLA_RELIEF;
		} else if (fluiditySnapshot.band() == PauCClientFluidityState.Band.RECOVERY) {
			policy = shaderActive ? Policy.SHADER_RECOVERY : Policy.VANILLA_RECOVERY;
		} else if (fluiditySnapshot.band() == PauCClientFluidityState.Band.BALANCED
			&& (policy == Policy.SHADER_HEADROOM || policy == Policy.VANILLA_HEADROOM)) {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		}
		if (resolvedFpsDip && (policy == Policy.SHADER_RELIEF || policy == Policy.VANILLA_RELIEF)) {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		}
		if (paucResolved
			&& (policy == Policy.SHADER_RELIEF || policy == Policy.VANILLA_RELIEF)
			&& heapPressure < 0.90D
			&& queuePressure < 0.18D) {
			policy = shaderActive ? Policy.SHADER_BALANCED : Policy.VANILLA_BALANCED;
		}
		if (shaderActive && dhMode == PauCLodShaderContext.DhShaderMode.SYNTHETIC_NATIVE) {
			int stableHeadroomTicks = readInt(
				SYNTHETIC_DH_HEADROOM_STABLE_TICKS_PROPERTY,
				shaderFamily == PauCLodShaderProfiles.Family.SOLAS ? 90 : 48,
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
			"fps=" + round(steadyFps)
				+ ", referenceFps=" + targetFps
				+ ", referenceMode=" + PauCClientTargetFps.referenceMode(minecraft)
				+ ", raw=" + fps
				+ ", playerFpsLimit=" + playerVideo.fpsLimitLabel()
				+ ", paucFpsCap=none, pacing=off, governorOutput=budgets-only"
				+ ", queue=" + round(queuePressure * 100.0D) + "%"
				+ ", heap=" + round(heapPressure * 100.0D) + "%"
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
		qualityUpgradeConfirmations = 0;
		lastVillagePressure = false;
		lastVillageSeverePressure = false;
		lastMovementCatchup = false;
		villageSeverePressure = false;
		villageSevereCandidateTicks = 0;
		villageSevereRecoveryTicks = 0;
		villageSevereHoldTicks = 0;
		idleQueueResolvedState = false;
		backlogResolved = false;
		lastWorkloadRecovered = false;
		lastConfiguredTargetDistance = -1;
		resetTargetDistanceStabilizer();
		clearPresentationQualityStabilizer();
		System.clearProperty(RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY);
		System.clearProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY);
		PauCLodShaderRuntime.updatePerformance(false, PauCLodShaderProfiles.Family.GENERIC, -1, 0, 0.0D, false, false, false);
		PauCClientFluidityState.reset();
		PauCWorkloadState.reset();
		fr.hoyatla.pauc.lod.PauCShaderShadowBudget.reset();
		clearRuntimeHudFps();
		clearDynamicOverrides();
	}

	public static String describeState() {
		return "fpsGovernor[policy="
			+ lastPolicy.id
			+ ", fps="
			+ (smoothedFps >= 0.0D ? round(smoothedFps) : "-")
			+ ", steadyFps="
			+ (lastConservativeFps >= 0.0D ? round(lastConservativeFps) : "-")
			+ ", referenceFps="
			+ PauCClientTargetFps.effectiveTargetFps()
			+ ", referenceMode="
			+ PauCClientTargetFps.referenceMode(Minecraft.getInstance())
			+ ", player="
			+ PauCPlayerVideoSettings.capture(Minecraft.getInstance()).describe()
			+ ", paucFpsCap=none"
			+ ", pacing=off"
			+ ", queuePressure="
			+ round(lastQueuePressure * 100.0D)
			+ "%"
			+ ", queueResolved="
			+ (backlogResolved ? "on" : "off")
			+ ", quality="
			+ lastAppliedQualityTier.id
			+ "@"
			+ lastQualityRuntime
			+ ", qualityStreak="
			+ qualityHeadroomStreak
			+ ", qualityConf="
			+ qualityUpgradeConfirmations
			+ ", targetDistance="
			+ describeTargetDistance(appliedTargetDistance < 0 ? PauCLodClientSettings.targetDistanceChunks() : appliedTargetDistance)
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
			+ ", "
			+ PauCClientFluidityState.describeState()
			+ "]";
	}

	public static String telemetryStateKey() {
		return "policy="
			+ lastPolicy.id
			+ ",quality="
			+ lastAppliedQualityTier.id
			+ "@"
			+ lastQualityRuntime
			+ ",band="
			+ PauCClientFluidityState.lastSnapshot().band().name().toLowerCase(Locale.ROOT)
			+ ",targetDistance="
			+ (appliedTargetDistance < 0 ? "-" : Integer.toString(appliedTargetDistance))
			+ ",generation="
			+ System.getProperty(DYNAMIC_GENERATION_RATE_PROPERTY, "-")
			+ ",retention="
			+ System.getProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY, "-")
			+ ",queueResolved="
			+ (backlogResolved ? "on" : "off")
			+ ",villagePressure="
			+ (PauCVillagePerformanceDiagnostics.isVillagePressureActive() ? "on" : "off")
			+ ",villageSevere="
			+ (villageSeverePressure ? "on" : "off")
			+ ",movementCatchup="
			+ (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? "on" : "off");
	}

	public static String describeActuationState() {
		return "governorAct[policy="
			+ lastPolicy.id
			+ ", reason="
			+ lastPolicyReason
			+ ", cmdTarget="
			+ (lastCommandedTargetDistance >= 0 ? lastCommandedTargetDistance : -1)
			+ ", cmdGeneration="
			+ (lastCommandedGenerationRate >= 0 ? lastCommandedGenerationRate : -1)
			+ ", cmdRetention="
			+ (lastCommandedRetentionMargin >= 0 ? lastCommandedRetentionMargin : -1)
			+ ", visibleFillFloor="
			+ (lastVisibleFillFloor >= 0 ? lastVisibleFillFloor : -1)
			+ ", nearDebt="
			+ lastNearCoverageDebt
			+ ", queueResolved="
			+ backlogResolved
			+ ", movementCatchup="
			+ PauCClientChunkPriorityScorer.isMovementCatchupActive()
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

	public static boolean isBacklogResolved() {
		return backlogResolved;
	}

	public static double warmupAggressionScale() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return 1.0D;
		}

		double scale = switch (lastPolicy) {
			case STARTUP -> 0.70D;
			case SHADER_RELIEF -> 0.55D;
			case SHADER_RECOVERY -> 1.15D;
			case VANILLA_RELIEF -> 0.70D;
			case SHADER_BALANCED -> 0.85D;
			case VANILLA_BALANCED -> 1.00D;
			case SHADER_HEADROOM -> 1.05D;
			case VANILLA_RECOVERY -> 1.25D;
			case VANILLA_HEADROOM -> 1.25D;
		};
		if (shouldApplyVillagePressureRelief()) {
			scale *= readDouble(VILLAGE_WARMUP_SCALE_PROPERTY, 0.65D, 0.25D, 1.0D);
		}
		if (shouldApplyVillageSevereRelief()) {
			scale *= readDouble(VILLAGE_SEVERE_WARMUP_SCALE_PROPERTY, 0.35D, 0.10D, 1.0D);
			scale = Math.max(scale, readDouble(VILLAGE_SEVERE_WARMUP_FLOOR_PROPERTY, 0.35D, 0.10D, 1.0D));
		}
		if (PauCClientChunkPriorityScorer.isMovementCatchupActive() && !villageSeverePressure) {
			scale = Math.max(scale, readDouble(MOVEMENT_CATCHUP_WARMUP_SCALE_PROPERTY, 1.08D, 0.50D, 1.50D));
		}
		if (highTargetVanillaMode()) {
			scale *= readDouble("pauc.lod.vanillaHighTargetWarmupScale", 0.82D, 0.35D, 1.0D);
			if (PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
				scale = Math.max(scale, 0.92D);
			}
		}
		if (PauCClientFrontierWarmupManager.hasNearCoverageDebt()) {
			scale = Math.max(
				scale,
				readDouble(
					"pauc.lod.nearCoverageWarmupScale",
					highTargetVanillaMode() ? 1.10D : 1.00D,
					0.50D,
					1.60D
				)
			);
		}
		return PauCClientFluidityState.adjustWarmupScale(scale);
	}

	public static double meshBudgetScale() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return 1.0D;
		}
		boolean highTargetVanilla = highTargetVanillaMode();
		double scale;
		if (shouldApplyVillageSevereRelief()) {
			scale = readDouble("pauc.lod.villageSevereMeshBudgetScale", 0.55D, 0.20D, 1.0D);
		} else if (lastPolicy == Policy.SHADER_RECOVERY || lastPolicy == Policy.VANILLA_RECOVERY) {
			scale = highTargetVanilla
				? readDouble("pauc.lod.vanillaRecoveryMeshBudgetScale", 1.12D, 0.50D, 1.60D)
				: readDouble("pauc.lod.recoveryMeshBudgetScale", 1.22D, 0.50D, 1.60D);
		} else if (PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
			scale = highTargetVanilla
				? readDouble("pauc.lod.vanillaHighTargetCatchupMeshBudgetScale", 0.96D, 0.35D, 1.0D)
				: 1.0D;
		} else if (isUnderPressure()) {
			double pressureScale = readDouble("pauc.lod.pressureMeshBudgetScale", 0.82D, 0.35D, 1.0D);
			if (highTargetVanilla) {
				pressureScale = Math.min(
					pressureScale,
					readDouble("pauc.lod.vanillaHighTargetPressureMeshBudgetScale", 0.74D, 0.20D, 1.0D)
				);
			}
			scale = pressureScale;
		} else {
			scale = highTargetVanilla
				? readDouble("pauc.lod.vanillaHighTargetMeshBudgetScale", 0.84D, 0.35D, 1.0D)
				: 1.0D;
		}
		if (PauCClientFrontierWarmupManager.hasNearCoverageDebt()) {
			scale = Math.max(
				scale,
				readDouble(
					"pauc.lod.nearCoverageMeshBudgetScale",
					highTargetVanilla ? 0.96D : 1.0D,
					0.35D,
					1.60D
				)
			);
		}
		return PauCClientFluidityState.adjustMeshBudgetScale(scale);
	}

	private static void updateVillageSeverePressure(
		double ratio,
		boolean workloadRecovered,
		boolean queueDrained,
		boolean queueFullyDrained,
		boolean idleQueueResolved
	) {
		boolean villagePressure = PauCVillagePerformanceDiagnostics.isVillagePressureActive();
		boolean recentVillageLoad = PauCVillagePerformanceDiagnostics.lastClientVillageEntityCount() > 0
			|| PauCVillagePerformanceDiagnostics.lastRenderedVillageEntitiesWindow() >= readInt("pauc.lod.villageSevereRecentEntityWindow", 8, 0, 4096)
			|| PauCVillagePerformanceDiagnostics.lastRenderedVillageBlockEntitiesWindow() >= readInt("pauc.lod.villageSevereRecentBlockEntityWindow", 24, 0, 4096);
		boolean villageLoadGone = !villagePressure || !recentVillageLoad;
		double enterRatio = readDouble(VILLAGE_SEVERE_RATIO_PROPERTY, 0.83D, 0.40D, 1.20D);
		double recoveryRatio = readDouble(VILLAGE_SEVERE_RECOVERY_RATIO_PROPERTY, 0.88D, enterRatio, 1.30D);
		boolean severeCandidate = villagePressure
			&& recentVillageLoad
			&& ratio < enterRatio
			&& lowFpsStreak >= 3
			&& !idleQueueResolved
			&& !backlogResolved
			&& !workloadRecovered;
		int enterTicks = readInt(VILLAGE_SEVERE_ENTER_TICKS_PROPERTY, 6, 1, 200);
		int exitTicks = readInt(VILLAGE_SEVERE_EXIT_TICKS_PROPERTY, 48, 1, 600);
		int minHoldTicks = readInt(VILLAGE_SEVERE_MIN_HOLD_TICKS_PROPERTY, 36, 1, 600);
		boolean resolved = idleQueueResolved || backlogResolved || workloadRecovered;
		int releaseStep = villageLoadGone && queueFullyDrained
			? Math.max(10, Math.max(1, minHoldTicks / 2))
			: !villagePressure && idleQueueResolved
			? Math.max(6, Math.max(1, minHoldTicks / 2))
			: !villagePressure && workloadRecovered
			? Math.max(4, Math.max(1, minHoldTicks / 3))
			: !villagePressure && resolved
			? Math.max(3, Math.max(1, minHoldTicks / 4))
			: !villagePressure && (queueFullyDrained || queueDrained)
				? Math.max(2, Math.max(1, minHoldTicks / 6))
				: 1;
		int effectiveExitTicks = villageLoadGone && (idleQueueResolved || queueFullyDrained)
			? Math.max(1, readInt("pauc.lod.villageSevereGoneExitTicks", Math.max(1, exitTicks / 12), 1, 600))
			: !villagePressure && idleQueueResolved
			? Math.max(2, readInt("pauc.lod.villageSevereIdleResolvedExitTicks", Math.max(2, exitTicks / 10), 1, 600))
			: !villagePressure && workloadRecovered
			? Math.max(3, readInt("pauc.lod.villageSevereCoverageExitTicks", Math.max(3, exitTicks / 8), 1, 600))
			: !villagePressure && resolved
			? Math.max(4, readInt("pauc.lod.villageSevereResolvedExitTicks", Math.max(4, exitTicks / 6), 1, 600))
			: !villagePressure && (queueFullyDrained || queueDrained)
			? Math.max(6, readInt("pauc.lod.villageSevereIdleExitTicks", Math.max(6, exitTicks / 4), 1, 600))
			: exitTicks;
		boolean fastRecovery = villageLoadGone && (idleQueueResolved || queueFullyDrained || workloadRecovered);
		if (!fastRecovery) {
			fastRecovery = !villagePressure && idleQueueResolved;
		}
		if (!fastRecovery) {
			fastRecovery = !villagePressure
				&& workloadRecovered
				&& ratio >= Math.max(0.40D, recoveryRatio - readDouble("pauc.lod.villageSevereCoverageRecoveryMargin", 0.18D, 0.0D, 0.40D));
		}
		if (!fastRecovery) {
			fastRecovery = !villagePressure
				&& resolved
				&& ratio >= Math.max(0.40D, recoveryRatio - readDouble("pauc.lod.villageSevereResolvedRecoveryMargin", 0.14D, 0.0D, 0.40D));
		}
		if (!fastRecovery) {
			fastRecovery = !villagePressure
			&& (queueFullyDrained || queueDrained)
			&& ratio >= Math.max(0.40D, recoveryRatio - readDouble("pauc.lod.villageSevereIdleRecoveryMargin", 0.10D, 0.0D, 0.40D));
		}
		if (!fastRecovery) {
			fastRecovery = idleQueueResolved
				|| backlogResolved
				|| workloadRecovered
				|| (queueDrained && queueFullyDrained);
		}

		if (severeCandidate) {
			villageSevereCandidateTicks = Math.min(enterTicks, villageSevereCandidateTicks + 1);
			villageSevereRecoveryTicks = 0;
			if (!villageSeverePressure && villageSevereCandidateTicks >= enterTicks) {
				villageSeverePressure = true;
				villageSevereHoldTicks = minHoldTicks;
			} else if (villageSeverePressure) {
				villageSevereHoldTicks = Math.max(villageSevereHoldTicks, Math.max(1, minHoldTicks / 3));
			}
		} else {
			villageSevereCandidateTicks = 0;
			if (villageSeverePressure) {
				if (villageLoadGone && (queueFullyDrained || resolved) && villageSevereHoldTicks <= Math.max(1, minHoldTicks / 3)) {
					villageSevereHoldTicks = 0;
				}
				if (villageSevereHoldTicks > 0) {
					villageSevereHoldTicks = Math.max(0, villageSevereHoldTicks - releaseStep);
					if (!fastRecovery) {
						villageSevereRecoveryTicks = 0;
					}
				}
				if (villageSevereHoldTicks <= 0 && (!villagePressure || ratio >= recoveryRatio || fastRecovery)) {
					villageSevereRecoveryTicks += fastRecovery ? 2 : 1;
					if (villageSevereRecoveryTicks >= effectiveExitTicks) {
						villageSeverePressure = false;
						villageSevereRecoveryTicks = 0;
					}
				} else if (!fastRecovery) {
					villageSevereRecoveryTicks = 0;
				}
			}
		}

		System.setProperty(RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY, Boolean.toString(villageSeverePressure));
	}

	private static void applyPolicy(Policy policy, String reason) {
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		int configuredTarget = PauCLodClientSettings.configuredTargetDistanceChunks();
		boolean dynamicDistanceAllowed = readBoolean(ALLOW_DISTANCE_REDUCTION_PROPERTY, PauCLodGameplayProfile.allowDynamicTargetDistanceReduction());
		boolean preserveConfiguredTarget = readBoolean(PRESERVE_CONFIGURED_TARGET_DISTANCE_PROPERTY,
			readBoolean(PRESERVE_CONFIGURED_TARGET_DISTANCE_VANILLA_PROPERTY, true));
		int targetDistance = configuredTarget;
		if (preserveConfiguredTarget) {
			targetDistance = configuredTarget;
		} else if (dynamicDistanceAllowed) {
			int policyTargetDistance = Math.min(configuredTarget, policy.targetDistanceChunks);
			targetDistance = PauCClientFluidityState.adjustTargetDistance(configuredTarget, policyTargetDistance);
		}
		PauCLodShaderProfiles.Family shaderFamily = PauCLodShaderProfiles.currentFamily();
		boolean villagePressure = PauCVillagePerformanceDiagnostics.isVillagePressureActive();
		boolean movementCatchup = PauCClientChunkPriorityScorer.isMovementCatchupActive();
		boolean stabilizeLodPresentation = PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation();
		boolean nearCoverageDebt = PauCClientFrontierWarmupManager.hasNearCoverageDebt();
		boolean shortTargetDistance = configuredTarget <= readInt("pauc.lod.shortTargetDistanceChunks", 16, 2, 32);
		if (shortTargetDistance && !shaderActive) {
			targetDistance = configuredTarget;
		}
		QualityTier achievedQualityTier = currentQualityTier(shaderActive, shaderFamily);
		QualityTier qualityTier = presentationQualityTier(achievedQualityTier, stabilizeLodPresentation);
		qualityTier = vanillaHighTargetPresentationTier(policy, qualityTier);
		qualityTier = stabilizePresentationQualityTier(qualityTier, policy, targetDistance, stabilizeLodPresentation, movementCatchup);
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
		visibleFillFloor = PauCClientFluidityState.adjustVisibleFillFloor(visibleFillFloor);
		if (!villageSeverePressure || stabilizeLodPresentation) {
			generationRequestRateLimit = Math.max(generationRequestRateLimit, visibleFillFloor);
		}
		if (shouldApplyVillagePressureRelief() && !stabilizeLodPresentation) {
			generationRequestRateLimit = Math.min(generationRequestRateLimit, readInt(VILLAGE_GENERATION_RATE_PROPERTY, 32, 4, 128));
		}
		if (shouldApplyVillageSevereRelief() && !stabilizeLodPresentation) {
			generationRequestRateLimit = Math.min(generationRequestRateLimit, readInt(VILLAGE_SEVERE_GENERATION_RATE_PROPERTY, 8, 2, 64));
			generationRequestRateLimit = Math.max(generationRequestRateLimit, readInt(VILLAGE_SEVERE_GENERATION_FLOOR_PROPERTY, 32, 8, 128));
		}
		if (movementCatchup) {
			generationRequestRateLimit = Math.max(
				generationRequestRateLimit,
				villageSeverePressure
					? readInt(MOVEMENT_CATCHUP_SEVERE_GENERATION_RATE_PROPERTY, shaderActive ? 112 : 80, 20, 256)
					: readInt(MOVEMENT_CATCHUP_GENERATION_RATE_PROPERTY, shaderActive ? 224 : 192, 20, 384)
			);
		}
		if (nearCoverageDebt) {
			int nearFloor = readInt(
				"pauc.lod.nearCoverageDebtGenerationFloor",
				!shaderActive && shortTargetDistance ? 320 : shaderActive ? 224 : 256,
				64,
				512
			);
			generationRequestRateLimit = Math.max(generationRequestRateLimit, nearFloor);
		}
		generationRequestRateLimit = PauCClientFluidityState.adjustGenerationRate(generationRequestRateLimit, movementCatchup);
		if (!readBoolean(ALLOW_GENERATION_REDUCTION_PROPERTY, PauCLodGameplayProfile.allowDynamicGenerationReduction())) {
			generationRequestRateLimit = Math.max(generationRequestRateLimit, PauCLodClientSettings.configuredGenerationRequestRateLimit());
		}
		boolean emergencyRelief = policy == Policy.SHADER_RELIEF || policy == Policy.VANILLA_RELIEF;
		if (emergencyRelief && !movementCatchup) {
			boolean highFpsCoverageDebt = highTargetVanillaMode() && (PauCEmbeddedLodRuntimeDiagnostics.hasCoverageDebt() || nearCoverageDebt);
			int emergencyCap = shaderActive
				? readInt(SHADER_EMERGENCY_GENERATION_CAP_PROPERTY, 256, 32, 768)
				: readInt(
					EMERGENCY_GENERATION_CAP_PROPERTY,
					nearCoverageDebt && shortTargetDistance ? 384 : highFpsCoverageDebt ? 224 : 160,
					32,
					512
				);
			if (highFpsCoverageDebt) {
				int fillFloor = readInt(
					nearCoverageDebt ? "pauc.lod.nearCoverageDebtGenerationFloor" : "pauc.lod.vanillaHighFpsCoverageGenerationFloor",
					nearCoverageDebt && shortTargetDistance ? 320 : 160,
					64,
					512
				);
				emergencyCap = Math.max(emergencyCap, fillFloor);
			}
			generationRequestRateLimit = Math.min(generationRequestRateLimit, emergencyCap);
		}
		int retentionMarginChunks = policy.retentionMarginChunks;
		if (shouldApplyVillageSevereRelief()) {
			retentionMarginChunks = Math.min(retentionMarginChunks, readInt(VILLAGE_SEVERE_RETENTION_MARGIN_PROPERTY, 12, 3, 12));
		}
		retentionMarginChunks = PauCClientFluidityState.adjustRetentionMargin(retentionMarginChunks);
		targetDistance = stabilizeTargetDistance(
			targetDistance,
			configuredTarget,
			policy,
			backlogResolved,
			lastQueuePressure,
			movementCatchup
		);
		lastCommandedTargetDistance = targetDistance;
		lastCommandedGenerationRate = generationRequestRateLimit;
		lastCommandedRetentionMargin = retentionMarginChunks;
		lastVisibleFillFloor = visibleFillFloor;
		lastNearCoverageDebt = nearCoverageDebt;
		lastPolicyReason = reason;
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
			LOGGER.info("PauC FPS governor selected {} ({}, quality={}, targetDistance={}, generation={} /s, retention={}, resolution={}, horizontal={}, vertical={}, villagePressure={}, villageSevere={}, movementCatchup={}, {}).",
				policy.id,
				reason + ", " + PauCLodGameplayProfile.describe(),
				qualityLabel,
				describeTargetDistance(targetDistance),
				generationRequestRateLimit,
				retentionMarginChunks,
				qualityTier.maxHorizontalResolution,
				qualityTier.horizontalQuality,
				verticalQuality,
				villagePressure ? "on" : "off",
				villageSeverePressure ? "on" : "off",
				movementCatchup ? "on" : "off",
				PauCClientFluidityState.describeState()
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
			qualityUpgradeConfirmations = 0;
			return;
		}
		boolean shaderFallback = shaderActive && PauCLodShaderContext.isFallbackActive();
		if (shaderFallback && PauCLodShaderRuntime.pressure() != PauCLodShaderRuntime.Pressure.HEADROOM) {
			qualityUpgradeConfirmations = 0;
			return;
		}
		if (shaderFallback && ratio < readDouble(SHADER_FALLBACK_QUALITY_UPGRADE_MIN_RATIO_PROPERTY, 1.18D, 1.0D, 2.0D)) {
			qualityUpgradeConfirmations = 0;
			return;
		}
		if (Boolean.parseBoolean(System.getProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY, "false"))) {
			qualityUpgradeConfirmations = 0;
			return;
		}
		if (ratio < 1.0D || heapPressure > 0.82D) {
			qualityUpgradeConfirmations = 0;
			return;
		}
		int stableTicks = shaderFallback
			? readInt(SHADER_FALLBACK_QUALITY_UPGRADE_STABLE_TICKS_PROPERTY, 220, 20, 2400)
			: readInt(QUALITY_UPGRADE_STABLE_TICKS_PROPERTY, 180, 20, 2400);
		if (qualityHeadroomStreak < stableTicks) {
			return;
		}
		int requiredConfirmations = readInt(QUALITY_UPGRADE_CONFIRMATIONS_PROPERTY, 2, 1, 4);
		qualityHeadroomStreak = 0;
		qualityUpgradeConfirmations = Math.min(requiredConfirmations, qualityUpgradeConfirmations + 1);
		if (qualityUpgradeConfirmations < requiredConfirmations) {
			return;
		}

		QualityTier previous = currentTier;
		QualityTier next = currentTier.next(maxTier);
		setCurrentQualityTier(shaderActive, shaderFamily, next);
		qualityUpgradeConfirmations = 0;
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
			case STARTUP, VANILLA_RELIEF, VANILLA_BALANCED, VANILLA_RECOVERY -> QualityTier.MID;
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
		return PauCClientChunkPriorityScorer.isFpsFirstVanillaMode();
	}

	private static int stabilizeTargetDistance(
		int requestedTargetDistance,
		int configuredTargetDistance,
		Policy policy,
		boolean queueResolved,
		double queuePressure,
		boolean movementCatchup
	) {
		if (readBoolean(PRESERVE_CONFIGURED_TARGET_DISTANCE_PROPERTY,
				readBoolean(PRESERVE_CONFIGURED_TARGET_DISTANCE_VANILLA_PROPERTY, true))) {
			appliedTargetDistance = configuredTargetDistance;
			clearPendingTargetDistance();
			return configuredTargetDistance;
		}
		int requested = PauCLodClientSettings.sanitizeTargetDistanceChunks(Math.min(configuredTargetDistance, requestedTargetDistance));
		if (!PauCLodShaderContext.isShaderPackInUse()
			&& configuredTargetDistance <= readInt("pauc.lod.noClampTargetDistanceChunks", 16, 2, 32)) {
			appliedTargetDistance = configuredTargetDistance;
			clearPendingTargetDistance();
			return configuredTargetDistance;
		}
		if (appliedTargetDistance < PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS) {
			appliedTargetDistance = requested;
			clearPendingTargetDistance();
			return requested;
		}
		if (requested == appliedTargetDistance) {
			clearPendingTargetDistance();
			return appliedTargetDistance;
		}

		long now = System.currentTimeMillis();
		if (requested < appliedTargetDistance) {
			int previous = appliedTargetDistance;
			appliedTargetDistance = requested;
			lastTargetDistanceDecreaseAtMillis = now;
			clearPendingTargetDistance();
			LOGGER.info(
				"PauC target distance stabilizer reduced LOD range from {} to {} (requested={}, policy={}, queueResolved={}, queue={}%, movementCatchup={}).",
				previous,
				appliedTargetDistance,
				requested,
				policy.id,
				queueResolved ? "on" : "off",
				round(queuePressure * 100.0D),
				movementCatchup ? "on" : "off"
			);
			return appliedTargetDistance;
		}

		boolean highTarget = configuredTargetDistance >= 48;
		long recoveryCooldownMs = readInt(
			"pauc.lod.targetDistanceRecoveryCooldownMs",
			highTarget ? 2_500 : 1_500,
			0,
			15_000
		);
		if (movementCatchup || now - lastTargetDistanceDecreaseAtMillis < recoveryCooldownMs) {
			clearPendingTargetDistance();
			return appliedTargetDistance;
		}
		if (!PauCClientFrontierWarmupManager.isTargetDistanceUpgradeReady(appliedTargetDistance, configuredTargetDistance)) {
			clearPendingTargetDistance();
			return appliedTargetDistance;
		}

		double maxQueuePressure = readDouble(
			"pauc.lod.targetDistanceUpgradeMaxQueuePressure",
			queueResolved ? 0.24D : 0.12D,
			0.0D,
			1.0D
		);
		if (!queueResolved && queuePressure > maxQueuePressure) {
			clearPendingTargetDistance();
			return appliedTargetDistance;
		}

		if (requested != pendingTargetDistance) {
			pendingTargetDistance = requested;
			pendingTargetDistanceTicks = 1;
			pendingTargetDistanceSinceMillis = now;
			return appliedTargetDistance;
		}

		pendingTargetDistanceTicks++;
		int requiredTicks = readInt("pauc.lod.targetDistanceUpgradeStableTicks", highTarget ? 32 : 22, 1, 400)
			+ (queueResolved ? 0 : highTarget ? 14 : 8);
		long requiredHoldMs = readInt("pauc.lod.targetDistanceUpgradeHoldMs", highTarget ? 1_600 : 1_000, 0, 15_000)
			+ (queueResolved ? 0L : highTarget ? 600L : 300L);
		if (pendingTargetDistanceTicks < requiredTicks && now - pendingTargetDistanceSinceMillis < requiredHoldMs) {
			return appliedTargetDistance;
		}

		int baseStep = readInt("pauc.lod.targetDistanceUpgradeStep", highTarget ? 8 : configuredTargetDistance >= 32 ? 6 : 4, 1, 24);
		int adaptiveStep = queueResolved
			? baseStep + (configuredTargetDistance >= 64 ? 4 : configuredTargetDistance >= 48 ? 2 : 0)
			: baseStep;
		int nextTargetDistance = Math.min(requested, appliedTargetDistance + adaptiveStep);
		if (nextTargetDistance != appliedTargetDistance) {
			int previous = appliedTargetDistance;
			appliedTargetDistance = nextTargetDistance;
			clearPendingTargetDistance();
			LOGGER.info(
				"PauC target distance stabilizer raised LOD range from {} to {} (requested={}, step={}, policy={}, queueResolved={}, queue={}%).",
				previous,
				appliedTargetDistance,
				requested,
				adaptiveStep,
				policy.id,
				queueResolved ? "on" : "off",
				round(queuePressure * 100.0D)
			);
		}
		return appliedTargetDistance;
	}

	private static void clearPendingTargetDistance() {
		pendingTargetDistance = -1;
		pendingTargetDistanceTicks = 0;
		pendingTargetDistanceSinceMillis = 0L;
	}

	private static QualityTier stabilizePresentationQualityTier(
		QualityTier candidate,
		Policy policy,
		int targetDistance,
		boolean stabilizeLodPresentation,
		boolean movementCatchup
	) {
		if (lastPresentationQualityTargetDistance < 0) {
			lastPresentationQualityTargetDistance = targetDistance;
			clearPendingPresentationQuality();
			return candidate;
		}
		if (candidate.ordinal() >= lastAppliedQualityTier.ordinal()) {
			lastPresentationQualityTargetDistance = targetDistance;
			clearPendingPresentationQuality();
			return candidate;
		}

		boolean urgentDrop = policy == Policy.STARTUP
			|| policy == Policy.SHADER_RELIEF
			|| policy == Policy.VANILLA_RELIEF
			|| movementCatchup
			|| stabilizeLodPresentation
			|| !backlogResolved
			|| !lastWorkloadRecovered
			|| targetDistance < lastPresentationQualityTargetDistance
			|| lastQueuePressure > readDouble("pauc.lod.presentationQualityDropMaxQueuePressure", 0.06D, 0.0D, 1.0D)
			|| PauCClientSurfaceLodMode.prefersAccurateFeatureLods();
		if (urgentDrop) {
			lastPresentationQualityTargetDistance = targetDistance;
			clearPendingPresentationQuality();
			return candidate;
		}

		long now = System.currentTimeMillis();
		if (candidate == pendingPresentationQualityTier) {
			pendingPresentationQualityTicks++;
		} else {
			pendingPresentationQualityTier = candidate;
			pendingPresentationQualityTicks = 1;
			pendingPresentationQualitySinceMillis = now;
		}

		boolean highTarget = PauCLodClientSettings.configuredTargetDistanceChunks() >= 48;
		int requiredTicks = readInt(
			PRESENTATION_QUALITY_DROP_STABLE_TICKS_PROPERTY,
			highTarget ? 30 : 18,
			1,
			240
		) + (PauCLodShaderContext.isShaderPackInUse() ? 4 : 0);
		long requiredHoldMs = readInt(
			PRESENTATION_QUALITY_DROP_HOLD_MS_PROPERTY,
			highTarget ? 1_400 : 900,
			0,
			10_000
		);
		if (pendingPresentationQualityTicks >= requiredTicks || now - pendingPresentationQualitySinceMillis >= requiredHoldMs) {
			lastPresentationQualityTargetDistance = targetDistance;
			clearPendingPresentationQuality();
			return candidate;
		}
		return lastAppliedQualityTier;
	}

	private static void resetTargetDistanceStabilizer() {
		appliedTargetDistance = -1;
		lastTargetDistanceDecreaseAtMillis = 0L;
		clearPendingTargetDistance();
	}

	private static void clearPendingPresentationQuality() {
		pendingPresentationQualityTier = null;
		pendingPresentationQualityTicks = 0;
		pendingPresentationQualitySinceMillis = 0L;
	}

	private static void clearPresentationQualityStabilizer() {
		lastAppliedQualityTier = QualityTier.NEAR;
		lastPresentationQualityTargetDistance = -1;
		clearPendingPresentationQuality();
	}

	private static String describeTargetDistance(int appliedDistance) {
		if (pendingTargetDistance > appliedDistance) {
			return appliedDistance + " (requested=" + pendingTargetDistance + ", pending=" + pendingTargetDistanceTicks + "t)";
		}
		return Integer.toString(appliedDistance);
	}

	private static void synchronizeConfiguredTargetDistance() {
		int configuredTargetDistance = PauCLodClientSettings.configuredTargetDistanceChunks();
		if (configuredTargetDistance == lastConfiguredTargetDistance) {
			return;
		}

		boolean initialized = lastConfiguredTargetDistance >= PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS;
		lastConfiguredTargetDistance = configuredTargetDistance;
		if (!initialized) {
			lastPresentationQualityTargetDistance = configuredTargetDistance;
			return;
		}

		resetTargetDistanceStabilizer();
		clearPresentationQualityStabilizer();
		lastPresentationQualityTargetDistance = configuredTargetDistance;
		PauCClientFrontierWarmupManager.onConfiguredTargetDistanceChanged(configuredTargetDistance);
		PauCClientSurfaceLodMode.onConfiguredTargetDistanceChanged(configuredTargetDistance);
		PauCEmbeddedDhBridge.resetPresentationStability("target-distance:" + configuredTargetDistance);
		LOGGER.info("PauC synchronized manual LOD distance change to {} chunks and cleared stale presentation state.", configuredTargetDistance);
	}

	private static void clearDynamicOverrides() {
		System.clearProperty(DYNAMIC_TARGET_DISTANCE_PROPERTY);
		System.clearProperty(DYNAMIC_RETENTION_MARGIN_PROPERTY);
		System.clearProperty(DYNAMIC_GENERATION_RATE_PROPERTY);
		System.clearProperty(DYNAMIC_MAX_RESOLUTION_PROPERTY);
		System.clearProperty(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY);
		System.clearProperty(DYNAMIC_VERTICAL_QUALITY_PROPERTY);
		lastCommandedTargetDistance = -1;
		lastCommandedGenerationRate = -1;
		lastCommandedRetentionMargin = -1;
		lastVisibleFillFloor = -1;
		lastNearCoverageDebt = false;
		lastPolicyReason = "-";
	}

	private static void setSystemPropertyIfChanged(String key, String value) {
		if (!value.equals(System.getProperty(key))) {
			System.setProperty(key, value);
		}
	}

	private static void updateRuntimeHudFps(int rawFps, double averageFps) {
		if (rawFps > 0) {
			setSystemPropertyIfChanged(RUNTIME_HUD_RAW_FPS_PROPERTY, Integer.toString(rawFps));
		} else {
			System.clearProperty(RUNTIME_HUD_RAW_FPS_PROPERTY);
		}
		if (averageFps >= 0.0D) {
			setSystemPropertyIfChanged(RUNTIME_HUD_AVERAGE_FPS_PROPERTY, round(averageFps));
		} else {
			System.clearProperty(RUNTIME_HUD_AVERAGE_FPS_PROPERTY);
		}
	}

	private static void clearRuntimeHudFps() {
		System.clearProperty(RUNTIME_HUD_RAW_FPS_PROPERTY);
		System.clearProperty(RUNTIME_HUD_AVERAGE_FPS_PROPERTY);
	}

	private static void suspendOutsideWorld() {
		smoothedFps = -1.0D;
		slowSmoothedFps = -1.0D;
		lastConservativeFps = -1.0D;
		lastQueuePressure = 0.0D;
		lowFpsStreak = 0;
		highFpsStreak = 0;
		qualityHeadroomStreak = 0;
		qualityUpgradeConfirmations = 0;
		villageSeverePressure = false;
		villageSevereCandidateTicks = 0;
		villageSevereRecoveryTicks = 0;
		villageSevereHoldTicks = 0;
		lastVillagePressure = false;
		lastVillageSeverePressure = false;
		lastMovementCatchup = false;
		idleQueueResolvedState = false;
		backlogResolved = false;
		lastWorkloadRecovered = false;
		lastConfiguredTargetDistance = -1;
		resetTargetDistanceStabilizer();
		clearPresentationQualityStabilizer();
		lastPolicy = Policy.STARTUP;
		System.clearProperty(RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY);
		System.clearProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY);
		PauCClientFluidityState.reset();
		PauCWorkloadState.reset();
		clearRuntimeHudFps();
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

	private static double fpsSmoothingAlpha(double previousFps, int fps, double queuePressure, boolean slowCurve) {
		if (previousFps < 0.0D) {
			return 1.0D;
		}
		if (fps < previousFps) {
			return slowCurve ? 0.20D : 0.45D;
		}
		double queueRelief = 1.0D - clamp01(queuePressure * 1.25D);
		double recoveryBoost = fps >= previousFps * 1.08D ? 0.08D : 0.0D;
		double base = slowCurve ? 0.14D : 0.38D;
		double ceiling = slowCurve ? 0.24D : 0.52D;
		return Math.min(ceiling, base + (queueRelief * (slowCurve ? 0.06D : 0.10D)) + recoveryBoost);
	}

	private static double conservativeSteadyFps(double fastSmoothedFps, double slowSmoothedFps, int fps, int targetFps, double queuePressure) {
		double steadyFps = Math.min(fastSmoothedFps, slowSmoothedFps);
		if (fps <= 0 || targetFps <= 0 || fastSmoothedFps <= slowSmoothedFps) {
			return steadyFps;
		}
		double headroom = clamp01((fps - slowSmoothedFps) / Math.max(1.0D, targetFps * 0.28D));
		double queueRelief = 1.0D - clamp01(queuePressure * 1.4D);
		double blend = 0.18D + (headroom * queueRelief * 0.32D);
		return slowSmoothedFps + ((fastSmoothedFps - slowSmoothedFps) * blend);
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static boolean shouldApplyVillagePressureRelief() {
		return PauCVillagePerformanceDiagnostics.isVillagePressureActive()
			&& !idleQueueResolvedState
			&& !backlogResolved
			&& !lastWorkloadRecovered;
	}

	private static boolean shouldApplyVillageSevereRelief() {
		return villageSeverePressure
			&& !idleQueueResolvedState
			&& !backlogResolved
			&& !lastWorkloadRecovered;
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
		SHADER_RECOVERY("shader-recovery", 56, 12, 224),
		SHADER_HEADROOM("shader-headroom", 80, 12, 160),
		VANILLA_RELIEF("vanilla-relief", 64, 10, 112),
		VANILLA_BALANCED("vanilla-balanced", 96, 12, 160),
		VANILLA_RECOVERY("vanilla-recovery", 96, 14, 256),
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
						case SHOOTER -> FAR;
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
