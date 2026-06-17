package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import fr.hoyatla.pauc.platform.PauCPlatformServices;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Locale;

public final class PauCClientFluidityState {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.fluidity.enabled";
	private static final String HEAVY_MOD_COUNT_PROPERTY = "pauc.fluidity.heavyModCount";
	private static final String HUGE_MOD_COUNT_PROPERTY = "pauc.fluidity.hugeModCount";
	private static final String CONSTRAINED_HEAP_MIB_PROPERTY = "pauc.fluidity.constrainedHeapMiB";
	private static final String TIGHT_HEAP_MIB_PROPERTY = "pauc.fluidity.tightHeapMiB";
	private static final String SHADER_TRANSITION_HOLD_TICKS_PROPERTY = "pauc.fluidity.shaderTransitionHoldTicks";
	private static final String MIN_GENERATION_SCALE_PROPERTY = "pauc.fluidity.minGenerationScale";
	private static final String MIN_WARMUP_SCALE_PROPERTY = "pauc.fluidity.minWarmupScale";
	private static final String MIN_MESH_SCALE_PROPERTY = "pauc.fluidity.minMeshScale";
	private static final String RECOVERY_BACKLOG_CHUNKS_PROPERTY = "pauc.fluidity.recoveryBacklogChunks";
	private static final String RECOVERY_AVG_CHUNK_MS_PROPERTY = "pauc.fluidity.recoveryAvgChunkMs";
	private static final String RECOVERY_MAX_HEAP_RATIO_PROPERTY = "pauc.fluidity.recoveryMaxHeapRatio";
	private static final String RECOVERY_MIN_FPS_RATIO_PROPERTY = "pauc.fluidity.recoveryMinFpsRatio";
	private static final String RECOVERY_GENERATION_SCALE_PROPERTY = "pauc.fluidity.recoveryGenerationScale";
	private static final String RECOVERY_WARMUP_SCALE_PROPERTY = "pauc.fluidity.recoveryWarmupScale";
	private static final String RECOVERY_MESH_SCALE_PROPERTY = "pauc.fluidity.recoveryMeshScale";
	private static final String BAND_STABLE_TICKS_PROPERTY = "pauc.fluidity.bandStableTicks";
	private static final String BAND_HOLD_MS_PROPERTY = "pauc.fluidity.bandHoldMs";
	private static final int CONFIG_RELOAD_TICKS = 100;
	private static final int LOG_THROTTLE_TICKS = 200;
	private static volatile Config config = Config.read();
	private static volatile Snapshot lastSnapshot = Snapshot.neutral();
	private static String lastRuntimeSignature = "";
	private static String lastLoggedBand = "";
	private static Band pendingBand = Band.HEADROOM;
	private static int pendingBandTicks;
	private static long pendingBandSinceMillis;
	private static int cachedModCount = Integer.MIN_VALUE;
	private static int configReloadTicks;
	private static int modCountRetryTicks;
	private static int shaderTransitionTicks;
	private static int logTicks;

	private PauCClientFluidityState() {
	}

	public static Snapshot update(
		Minecraft minecraft,
		int rawFps,
		int targetFps,
		double steadyFps,
		double deliveryRatio,
		double queuePressure,
		double heapPressure,
		boolean shaderActive,
		PauCLodShaderProfiles.Family shaderFamily,
		PauCLodShaderContext.DhShaderMode dhMode,
		PauCWorkloadState.Snapshot workloadSnapshot
	) {
		Config currentConfig = currentConfig();
		if (!currentConfig.enabled) {
			lastSnapshot = Snapshot.neutral();
			return lastSnapshot;
		}

		int modCount = loadedModCount();
		long maxHeapMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
		boolean constrainedHeap = maxHeapMiB > 0L && maxHeapMiB <= currentConfig.constrainedHeapMiB;
		boolean tightHeap = maxHeapMiB > 0L && maxHeapMiB <= currentConfig.tightHeapMiB;

		String runtimeSignature = shaderActive + ":" + shaderFamily + ":" + dhMode.id();
		if (!runtimeSignature.equals(lastRuntimeSignature)) {
			lastRuntimeSignature = runtimeSignature;
			shaderTransitionTicks = currentConfig.shaderTransitionHoldTicks;
			clearPendingBand();
		} else if (shaderTransitionTicks > 0) {
			shaderTransitionTicks--;
		}

		int pendingChunks = workloadSnapshot.pendingChunks();
		int backlogTasks = workloadSnapshot.backlogTasks();
		double avgChunkMs = workloadSnapshot.averageChunkMs();
		boolean frameWatchdogSpike = workloadSnapshot.frameWatchdogSpike();
		boolean queueDrained = workloadSnapshot.queueDrained();
		boolean queueNearlyDrained = workloadSnapshot.queueNearlyDrained();
		boolean queueFullyDrained = workloadSnapshot.queueFullyDrained();
		boolean backlogResolved = workloadSnapshot.backlogResolved();
		boolean workloadRecovered = workloadSnapshot.workloadRecovered();
		RuntimeProfile runtimeProfile = classifyRuntimeProfile(currentConfig, modCount, maxHeapMiB, shaderActive, heapPressure);
		boolean hugePack = runtimeProfile.tier() == PackTier.HUGE;
		boolean heavyPack = runtimeProfile.tier().atLeast(PackTier.HEAVY);
		boolean mediumPack = runtimeProfile.tier().atLeast(PackTier.MEDIUM);
		boolean fpsFirstVanilla = !shaderActive && PauCClientChunkPriorityScorer.isFpsFirstVanillaMode(targetFps);
		boolean coverageDebt = PauCEmbeddedLodRuntimeDiagnostics.hasCoverageDebt();
		boolean paucResolved = workloadSnapshot.paucResolved();
		boolean externalFpsDip = workloadSnapshot.externalFpsDip();
		double fpsPressure = deliveryRatio > 0.0D ? clamp01((0.98D - deliveryRatio) / 0.48D) : 0.30D;
		double rawFpsPressure = targetFps > 0 ? clamp01((targetFps - rawFps) / (double) Math.max(1, targetFps)) : 0.0D;
		double queueLoad = clamp01(queuePressure * 1.85D);
		double heapLoad = clamp01((heapPressure - 0.76D) / 0.22D);
		double packLoad = switch (runtimeProfile.tier()) {
			case HUGE -> 0.38D;
			case HEAVY -> 0.24D;
			case MEDIUM -> 0.10D;
			case LIGHT -> 0.0D;
		};
		double heapClassLoad = tightHeap ? 0.22D : constrainedHeap ? 0.12D : 0.0D;
		double transitionLoad = shaderTransitionTicks > 0 ? 0.18D : 0.0D;
		double fpsPressureScale = paucResolved ? 0.10D : workloadRecovered ? 0.14D : backlogResolved ? 0.18D : queueDrained ? 0.38D : queueNearlyDrained ? 0.72D : 1.0D;
		fpsPressure *= fpsPressureScale;
		rawFpsPressure *= paucResolved ? 0.08D : workloadRecovered ? 0.12D : backlogResolved ? 0.16D : queueDrained ? 0.34D : queueNearlyDrained ? 0.74D : 1.0D;
		double pressure = clamp01(Math.max(Math.max(fpsPressure, rawFpsPressure * 0.8D), Math.max(queueLoad, heapLoad)) + packLoad + heapClassLoad + transitionLoad);
		boolean recoveryCandidate = targetFps > 0
			&& steadyFps >= targetFps * readDouble(RECOVERY_MIN_FPS_RATIO_PROPERTY, 0.76D, 0.40D, 1.50D)
			&& heapPressure <= readDouble(RECOVERY_MAX_HEAP_RATIO_PROPERTY, 0.72D, 0.20D, 0.95D)
			&& pendingChunks >= readInt(RECOVERY_BACKLOG_CHUNKS_PROPERTY, 768, 128, 32768)
			&& avgChunkMs >= readDouble(RECOVERY_AVG_CHUNK_MS_PROPERTY, 120.0D, 20.0D, 2000.0D);
		Band band = pressure >= 0.68D ? Band.RELIEF : pressure >= 0.32D ? Band.BALANCED : Band.HEADROOM;
		if (recoveryCandidate) {
			band = Band.RECOVERY;
		}
		if (rawFps > 0 && targetFps > 0 && rawFps < Math.max(24, targetFps / 2) && !workloadRecovered) {
			band = Band.RELIEF;
		}
		if (heapPressure > 0.92D || queuePressure > 0.32D) {
			band = Band.RELIEF;
		}
		if (externalFpsDip && band == Band.RELIEF) {
			band = Band.BALANCED;
		}
		if (paucResolved && band == Band.RELIEF && heapPressure < 0.90D && queuePressure < 0.18D) {
			band = Band.BALANCED;
		}
		band = stabilizeBand(
			band,
			pressure,
			frameWatchdogSpike,
			queuePressure,
			queueDrained,
			queueFullyDrained,
			backlogResolved,
			workloadRecovered
		);

		double packScale = switch (runtimeProfile.tier()) {
			case HUGE -> 0.82D;
			case HEAVY -> 0.90D;
			case MEDIUM, LIGHT -> 1.0D;
		};
		double heapScale = heapPressure > 0.90D ? 0.76D : heapPressure > 0.84D ? 0.88D : 1.0D;
		double transitionScale = shaderTransitionTicks > 0 ? 0.88D : 1.0D;
		double generationScale = clamp(1.08D - pressure * 0.58D, currentConfig.minGenerationScale, 1.18D) * packScale * heapScale * transitionScale;
		double warmupScale = clamp(1.05D - pressure * 0.50D, currentConfig.minWarmupScale, 1.15D) * packScale * heapScale;
		double meshScale = clamp(1.03D - pressure * 0.42D, currentConfig.minMeshScale, 1.08D) * heapScale;
		if (band == Band.RECOVERY) {
			generationScale = Math.max(generationScale, readDouble(RECOVERY_GENERATION_SCALE_PROPERTY, 1.24D, 1.0D, 2.0D));
			warmupScale = Math.max(warmupScale, readDouble(RECOVERY_WARMUP_SCALE_PROPERTY, 1.18D, 1.0D, 2.0D));
			meshScale = Math.max(meshScale, readDouble(RECOVERY_MESH_SCALE_PROPERTY, 1.16D, 1.0D, 2.0D));
		}

		int targetCeiling = targetDistanceCeiling(band, shaderActive, hugePack, heavyPack, constrainedHeap, heapPressure, fpsFirstVanilla);
		int retentionCeiling = retentionCeiling(band, shaderActive, hugePack, heavyPack, heapPressure);
		int visibleFillFloorCeiling = visibleFillFloorCeiling(band, shaderActive, hugePack, heavyPack, heapPressure, fpsFirstVanilla, coverageDebt);
		int emergencyGenerationCap = emergencyGenerationCap(band, shaderActive, hugePack, heavyPack, heapPressure, fpsFirstVanilla, coverageDebt);
		String reason = reason(
			band,
			modCount,
			maxHeapMiB,
			runtimeProfile,
			heapPressure,
			queuePressure,
			deliveryRatio,
			shaderTransitionTicks,
			pendingChunks,
			backlogTasks,
			queueDrained,
			backlogResolved,
			externalFpsDip,
			avgChunkMs
		);

		Snapshot snapshot = new Snapshot(
			true,
			band,
			reason,
			modCount,
			maxHeapMiB,
			pressure,
			generationScale,
			warmupScale,
			meshScale,
			targetCeiling,
			retentionCeiling,
			visibleFillFloorCeiling,
			emergencyGenerationCap,
			shaderTransitionTicks
		);
		lastSnapshot = snapshot;
		logIfChanged(snapshot, rawFps, steadyFps, targetFps);
		return snapshot;
	}

	public static Snapshot lastSnapshot() {
		return lastSnapshot;
	}

	public static void reset() {
		lastSnapshot = Snapshot.neutral();
		lastRuntimeSignature = "";
		lastLoggedBand = "";
		clearPendingBand();
		configReloadTicks = 0;
		shaderTransitionTicks = 0;
		logTicks = 0;
	}

	public static String describeState() {
		return lastSnapshot.describe();
	}

	public static int adjustTargetDistance(int configuredTargetDistance, int policyTargetDistance) {
		int noClampDistance = readInt(
			"pauc.lod.noClampTargetDistanceChunks",
			16,
			PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS,
			PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS
		);
		if (configuredTargetDistance <= noClampDistance) {
			return configuredTargetDistance;
		}
		Snapshot snapshot = lastSnapshot;
		if (!snapshot.active || snapshot.targetDistanceCeiling <= 0) {
			return policyTargetDistance;
		}
		if (!shouldClampTargetDistance(snapshot)) {
			return configuredTargetDistance;
		}
		int ceiling = Math.min(configuredTargetDistance, snapshot.targetDistanceCeiling);
		return Math.max(PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, Math.min(policyTargetDistance, ceiling));
	}

	public static int adjustVisibleFillFloor(int visibleFillFloor) {
		Snapshot snapshot = lastSnapshot;
		if (!snapshot.active || snapshot.visibleFillFloorCeiling <= 0) {
			return visibleFillFloor;
		}
		return Math.max(48, Math.min(visibleFillFloor, snapshot.visibleFillFloorCeiling));
	}

	public static int adjustGenerationRate(int generationRequestRateLimit, boolean movementCatchup) {
		Snapshot snapshot = lastSnapshot;
		if (!snapshot.active) {
			return generationRequestRateLimit;
		}
		int scaled = (int) Math.round(generationRequestRateLimit * snapshot.generationScale);
		boolean coverageDebt = PauCEmbeddedLodRuntimeDiagnostics.hasCoverageDebt();
		boolean nearCoverageDebt = PauCClientFrontierWarmupManager.hasNearCoverageDebt();
		boolean directFill = PauCClientFrontierWarmupManager.isDirectHorizonFillActive(PauCLodClientSettings.targetDistanceChunks());
		// Resilient sustainable-rate cap (measured, no per-pack constant): while a real backlog exists, never
		// request meaningfully more chunks/s than the embedded LOD runtime is actually COMPLETING on this
		// modpack + hardware. Heavy worldgen (slow per-chunk gen, e.g. Terralith) self-limits instead of
		// piling an unbounded backlog - the root cause of map-load stutter and 1% low dips - while light packs
		// stay uncapped and push far. movementCatchup is exempt so the player can briefly burst to fill ahead.
		if (!directFill && !movementCatchup && !coverageDebt) {
			double throughput = PauCEmbeddedLodRuntimeDiagnostics.completionsPerSecond();
			int backlog = PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() + PauCEmbeddedLodRuntimeDiagnostics.pendingTasks();
			int backlogThreshold = readInt("pauc.lod.sustainableRateBacklogTasks", 96, 8, 8192);
			if (throughput > 0.5D && backlog > backlogThreshold) {
				double headroom = readDouble("pauc.lod.sustainableRateHeadroom", 1.5D, 1.0D, 4.0D);
				int floor = readInt("pauc.lod.sustainableRateFloor", 24, 8, 128);
				int sustainable = (int) Math.ceil(throughput * headroom);
				scaled = Math.min(scaled, Math.max(floor, sustainable));
			}
		}
		if (coverageDebt || nearCoverageDebt) {
			boolean fpsFirstVanilla = PauCClientChunkPriorityScorer.isFpsFirstVanillaMode();
			int configuredTarget = PauCLodClientSettings.targetDistanceChunks();
			boolean shortTarget = configuredTarget <= readInt("pauc.lod.shortTargetDistanceChunks", 16, 2, 32);
			int coverageFloor = readInt(
				nearCoverageDebt ? "pauc.lod.nearCoverageDebtGenerationFloor" : "pauc.lod.coverageDebtGenerationFloor",
				nearCoverageDebt && fpsFirstVanilla && shortTarget
					? 320
					: nearCoverageDebt && fpsFirstVanilla
						? 256
						: fpsFirstVanilla
					? (snapshot.band == Band.RELIEF ? 160 : 192)
					: (snapshot.band == Band.RELIEF ? 112 : 144),
				32,
				512
			);
			int cap = nearCoverageDebt && shortTarget
				? readInt("pauc.lod.nearCoverageDebtGenerationBurstCap", 512, 64, 768)
				: generationRequestRateLimit;
			scaled = Math.max(scaled, Math.min(cap, coverageFloor));
		}
		if (snapshot.emergencyGenerationCap > 0 && !movementCatchup && !coverageDebt) {
			scaled = Math.min(scaled, snapshot.emergencyGenerationCap);
		}
		if (movementCatchup) {
			int recoveryFloor = switch (snapshot.band) {
				case RELIEF -> 144;
				case RECOVERY -> 320;
				case BALANCED, HEADROOM -> 192;
			};
			scaled = Math.max(scaled, Math.min(generationRequestRateLimit, recoveryFloor));
		}
		if (directFill) {
			int directFillFloor = readInt(
				"pauc.lod.directHorizonGenerationFloor",
				nearCoverageDebt ? 768 : (movementCatchup ? 896 : 640),
				64,
				PauCLodClientSettings.maxGenerationRequestRateLimit()
			);
			scaled = Math.max(scaled, directFillFloor);
		}
		return Math.max(20, Math.min(PauCLodClientSettings.maxGenerationRequestRateLimit(), scaled));
	}

	public static int adjustRetentionMargin(int retentionMarginChunks) {
		Snapshot snapshot = lastSnapshot;
		if (!snapshot.active || snapshot.retentionCeiling <= 0) {
			return retentionMarginChunks;
		}
		return Math.max(4, Math.min(retentionMarginChunks, snapshot.retentionCeiling));
	}

	public static double adjustWarmupScale(double scale) {
		Snapshot snapshot = lastSnapshot;
		return snapshot.active ? Math.max(0.15D, Math.min(1.50D, scale * snapshot.warmupScale)) : scale;
	}

	public static double adjustMeshBudgetScale(double scale) {
		Snapshot snapshot = lastSnapshot;
		return snapshot.active ? Math.max(0.15D, Math.min(1.60D, scale * snapshot.meshScale)) : scale;
	}

	private static int targetDistanceCeiling(
		Band band,
		boolean shaderActive,
		boolean hugePack,
		boolean heavyPack,
		boolean constrainedHeap,
		double heapPressure,
		boolean fpsFirstVanilla
	) {
		if (band == Band.RECOVERY) {
			return 0;
		}
		if (band == Band.HEADROOM && !hugePack && heapPressure < 0.82D) {
			return 0;
		}
		if (fpsFirstVanilla) {
			if (band == Band.RELIEF || heapPressure > 0.90D) {
				return readInt("pauc.lod.vanillaHighFpsReliefTargetDistance", 16, PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS);
			}
			if (band == Band.BALANCED) {
				return readInt("pauc.lod.vanillaHighFpsBalancedTargetDistance", 24, PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS);
			}
		}
		if (band == Band.RELIEF || heapPressure > 0.90D) {
			return shaderActive ? 32 : hugePack || constrainedHeap ? 32 : 40;
		}
		if (hugePack || constrainedHeap) {
			return shaderActive ? 40 : 48;
		}
		if (heavyPack) {
			return shaderActive ? 48 : 56;
		}
		return 0;
	}

	private static boolean shouldClampTargetDistance(Snapshot snapshot) {
		if (snapshot.band == Band.RECOVERY || snapshot.pressure >= 0.92D) {
			return true;
		}
		if (!PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()) {
			return snapshot.band == Band.RELIEF && snapshot.pressure >= 0.82D;
		}

		int pendingChunks = PauCEmbeddedLodRuntimeDiagnostics.pendingChunks();
		int backlogTasks = PauCEmbeddedLodRuntimeDiagnostics.backlogTasks();
		double backlogPressure = PauCEmbeddedLodRuntimeDiagnostics.backlogPressure();
		if (pendingChunks >= readInt("pauc.lod.distanceClampPendingChunks", 256, 0, 32768)) {
			return true;
		}
		if (backlogTasks >= readInt("pauc.lod.distanceClampBacklogTasks", 24, 0, 4096)) {
			return true;
		}
		if (backlogPressure >= readDouble("pauc.lod.distanceClampBacklogPressure", 0.12D, 0.0D, 1.0D)) {
			return true;
		}
		return snapshot.band == Band.RELIEF
			&& backlogPressure >= readDouble("pauc.lod.distanceClampReliefBacklogPressure", 0.05D, 0.0D, 1.0D);
	}

	private static int retentionCeiling(Band band, boolean shaderActive, boolean hugePack, boolean heavyPack, double heapPressure) {
		if (band == Band.RECOVERY) {
			return 0;
		}
		if (band == Band.RELIEF || heapPressure > 0.90D) {
			return shaderActive ? 8 : 10;
		}
		if (hugePack || heavyPack) {
			return shaderActive ? 10 : 12;
		}
		return 0;
	}

	private static int visibleFillFloorCeiling(
		Band band,
		boolean shaderActive,
		boolean hugePack,
		boolean heavyPack,
		double heapPressure,
		boolean fpsFirstVanilla,
		boolean coverageDebt
	) {
		if (band == Band.RECOVERY) {
			return 0;
		}
		if (fpsFirstVanilla && coverageDebt) {
			return readInt("pauc.lod.vanillaHighFpsCoverageFillFloorCeiling", 192, 64, 384);
		}
		if (band == Band.RELIEF || heapPressure > 0.90D) {
			return shaderActive ? 96 : 112;
		}
		if (hugePack) {
			return shaderActive ? 112 : 128;
		}
		if (heavyPack) {
			return shaderActive ? 128 : 144;
		}
		return 0;
	}

	private static int emergencyGenerationCap(
		Band band,
		boolean shaderActive,
		boolean hugePack,
		boolean heavyPack,
		double heapPressure,
		boolean fpsFirstVanilla,
		boolean coverageDebt
	) {
		if (band == Band.RECOVERY) {
			return 0;
		}
		if (fpsFirstVanilla && coverageDebt) {
			return readInt("pauc.lod.vanillaHighFpsCoverageGenerationCap", 224, 64, 384);
		}
		if (band == Band.RELIEF || heapPressure > 0.90D) {
			return shaderActive ? 128 : hugePack ? 112 : 144;
		}
		if (hugePack || heavyPack) {
			return shaderActive ? 160 : 192;
		}
		return 0;
	}

	private static String reason(
		Band band,
		int modCount,
		long maxHeapMiB,
		RuntimeProfile runtimeProfile,
		double heapPressure,
		double queuePressure,
		double deliveryRatio,
		int shaderTransitionTicks,
		int pendingChunks,
		int backlogTasks,
		boolean queueDrained,
		boolean backlogResolved,
		boolean externalFpsDip,
		double avgChunkMs
	) {
		return "fluidity[band="
			+ band.id
			+ ", mods="
			+ (modCount >= 0 ? modCount : "?")
			+ ", runtime="
			+ runtimeProfile.tier().id
			+ "/"
			+ runtimeProfile.score()
			+ ", heap="
			+ maxHeapMiB
			+ "MiB/"
			+ round(heapPressure * 100.0D)
			+ "%, queue="
			+ round(queuePressure * 100.0D)
			+ "%, ratio="
			+ round(deliveryRatio)
			+ ", backlog="
			+ pendingChunks
			+ ", liveBacklog="
			+ backlogTasks
			+ ", queueDrained="
			+ queueDrained
			+ ", backlogResolved="
			+ backlogResolved
			+ ", externalDip="
			+ externalFpsDip
			+ ", avgChunkMs="
			+ (avgChunkMs >= 0.0D ? round(avgChunkMs) : "-")
			+ ", genTput="
			+ (PauCEmbeddedLodRuntimeDiagnostics.completionsPerSecond() >= 0.0D
				? round(PauCEmbeddedLodRuntimeDiagnostics.completionsPerSecond()) + "/s"
				: "-")
			+ ", shaderRecovery="
			+ shaderTransitionTicks
			+ "t]";
	}

	private static void logIfChanged(Snapshot snapshot, int rawFps, double steadyFps, int targetFps) {
		if (snapshot.band.id.equals(lastLoggedBand) && logTicks-- > 0) {
			return;
		}
		lastLoggedBand = snapshot.band.id;
		logTicks = LOG_THROTTLE_TICKS;
		LOGGER.info("PauC fluidity state: {} fps={}/{} steady={}.",
			snapshot.describe(),
			rawFps,
			targetFps,
			steadyFps >= 0.0D ? round(steadyFps) : "-"
		);
	}

	private static Config currentConfig() {
		if (configReloadTicks-- <= 0) {
			config = Config.read();
			configReloadTicks = CONFIG_RELOAD_TICKS;
		}
		return config;
	}

	private static int loadedModCount() {
		if (cachedModCount >= 0) {
			return cachedModCount;
		}
		if (modCountRetryTicks-- > 0) {
			return cachedModCount == Integer.MIN_VALUE ? -1 : cachedModCount;
		}
		modCountRetryTicks = CONFIG_RELOAD_TICKS;
		cachedModCount = Math.max(-1, PauCPlatformServices.getInstance().loadedModCount());
		return cachedModCount;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
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

	private static double clamp01(double value) {
		return clamp(value, 0.0D, 1.0D);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static Band stabilizeBand(
		Band candidate,
		double pressure,
		boolean frameWatchdogSpike,
		double queuePressure,
		boolean queueDrained,
		boolean queueFullyDrained,
		boolean backlogResolved,
		boolean workloadRecovered
	) {
		Band current = lastSnapshot.active ? lastSnapshot.band : Band.HEADROOM;
		if (candidate == current) {
			clearPendingBand();
			return candidate;
		}
		boolean urgentRelief = candidate == Band.RELIEF
			&& (frameWatchdogSpike || pressure >= 0.84D || queuePressure >= 0.24D);
		if (urgentRelief) {
			clearPendingBand();
			return candidate;
		}
		boolean resolved = queueFullyDrained || backlogResolved || workloadRecovered;
		if (candidate != pendingBand) {
			pendingBand = candidate;
			pendingBandTicks = 1;
			pendingBandSinceMillis = System.currentTimeMillis();
			return current;
		}

		pendingBandTicks++;
		boolean reliefTransition = current == Band.RELIEF || candidate == Band.RELIEF;
		boolean recoveryTransition = current == Band.RECOVERY || candidate == Band.RECOVERY;
		int requiredTicks = readInt(BAND_STABLE_TICKS_PROPERTY, 10, 1, 240)
			+ (reliefTransition ? resolved ? 6 : 16 : 0)
			+ (recoveryTransition ? resolved ? 4 : 10 : 0);
		long requiredHoldMs = readInt(BAND_HOLD_MS_PROPERTY, 420, 0, 5_000)
			+ (reliefTransition ? resolved ? 180L : 520L : 0L)
			+ (recoveryTransition ? resolved ? 120L : 280L : 0L);
		long now = System.currentTimeMillis();
		if (pendingBandTicks >= requiredTicks || now - pendingBandSinceMillis >= requiredHoldMs) {
			clearPendingBand();
			return candidate;
		}
		return current;
	}

	private static void clearPendingBand() {
		pendingBand = Band.HEADROOM;
		pendingBandTicks = 0;
		pendingBandSinceMillis = 0L;
	}

	private static RuntimeProfile classifyRuntimeProfile(Config config, int modCount, long maxHeapMiB, boolean shaderActive, double heapPressure) {
		int mediumModCount = Math.max(40, config.heavyModCount / 2);
		int score = 0;
		if (modCount >= config.hugeModCount) {
			score += 6;
		} else if (modCount >= config.heavyModCount) {
			score += 4;
		} else if (modCount >= mediumModCount) {
			score += 2;
		} else if (modCount >= 16) {
			score += 1;
		}
		if (shaderActive) {
			score += 1;
		}
		if (maxHeapMiB > 0L && maxHeapMiB <= config.tightHeapMiB) {
			score += 2;
		} else if (maxHeapMiB > 0L && maxHeapMiB <= config.constrainedHeapMiB) {
			score += 1;
		}
		int clientEntities = PauCVillagePerformanceDiagnostics.lastClientEntityCount();
		long renderedEntitiesWindow = PauCVillagePerformanceDiagnostics.lastRenderedEntitiesWindow();
		long renderedBlockEntitiesWindow = PauCVillagePerformanceDiagnostics.lastRenderedBlockEntitiesWindow();
		if (clientEntities >= 128 || renderedEntitiesWindow >= 192L) {
			score += 2;
		} else if (clientEntities >= 64 || renderedEntitiesWindow >= 80L) {
			score += 1;
		}
		if (renderedBlockEntitiesWindow >= 1024L) {
			score += 3;
		} else if (renderedBlockEntitiesWindow >= 384L) {
			score += 2;
		} else if (renderedBlockEntitiesWindow >= 128L) {
			score += 1;
		}
		if (PauCVillagePerformanceDiagnostics.isVillagePressureActive()) {
			score += 1;
		}
		if (heapPressure >= 0.88D) {
			score += 1;
		}
		PackTier tier = score >= 8 ? PackTier.HUGE : score >= 5 ? PackTier.HEAVY : score >= 2 ? PackTier.MEDIUM : PackTier.LIGHT;
		return new RuntimeProfile(tier, score);
	}

	private static String round(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	public enum Band {
		RELIEF("relief"),
		RECOVERY("recovery"),
		BALANCED("balanced"),
		HEADROOM("headroom");

		private final String id;

		Band(String id) {
			this.id = id;
		}
	}

	private enum PackTier {
		LIGHT("light"),
		MEDIUM("medium"),
		HEAVY("heavy"),
		HUGE("huge");

		private final String id;

		PackTier(String id) {
			this.id = id;
		}

		private boolean atLeast(PackTier other) {
			return ordinal() >= other.ordinal();
		}
	}

	private record RuntimeProfile(PackTier tier, int score) {
	}

	public record Snapshot(
		boolean active,
		Band band,
		String reason,
		int modCount,
		long maxHeapMiB,
		double pressure,
		double generationScale,
		double warmupScale,
		double meshScale,
		int targetDistanceCeiling,
		int retentionCeiling,
		int visibleFillFloorCeiling,
		int emergencyGenerationCap,
		int shaderTransitionTicks
	) {
		private static Snapshot neutral() {
			return new Snapshot(false, Band.HEADROOM, "fluidity[disabled]", -1, -1L, 0.0D, 1.0D, 1.0D, 1.0D, 0, 0, 0, 0, 0);
		}

		public String describe() {
			return reason
				+ ", scale[generation="
				+ round(generationScale)
				+ ", warmup="
				+ round(warmupScale)
				+ ", mesh="
				+ round(meshScale)
				+ "], ceilings[target="
				+ (targetDistanceCeiling > 0 ? targetDistanceCeiling : "-")
				+ ", retention="
				+ (retentionCeiling > 0 ? retentionCeiling : "-")
				+ ", fillFloor="
				+ (visibleFillFloorCeiling > 0 ? visibleFillFloorCeiling : "-")
				+ ", generation="
				+ (emergencyGenerationCap > 0 ? emergencyGenerationCap : "-")
				+ "]";
		}
	}

	private record Config(
		boolean enabled,
		int heavyModCount,
		int hugeModCount,
		int constrainedHeapMiB,
		int tightHeapMiB,
		int shaderTransitionHoldTicks,
		double minGenerationScale,
		double minWarmupScale,
		double minMeshScale
	) {
		private static Config read() {
			int heavyModCount = readInt(HEAVY_MOD_COUNT_PROPERTY, 120, 40, 500);
			return new Config(
				readBoolean(ENABLED_PROPERTY, true),
				heavyModCount,
				readInt(HUGE_MOD_COUNT_PROPERTY, 200, heavyModCount, 800),
				readInt(CONSTRAINED_HEAP_MIB_PROPERTY, 6144, 2048, 32768),
				readInt(TIGHT_HEAP_MIB_PROPERTY, 4608, 2048, 32768),
				readInt(SHADER_TRANSITION_HOLD_TICKS_PROPERTY, 120, 0, 1200),
				readDouble(MIN_GENERATION_SCALE_PROPERTY, 0.42D, 0.20D, 1.0D),
				readDouble(MIN_WARMUP_SCALE_PROPERTY, 0.45D, 0.20D, 1.0D),
				readDouble(MIN_MESH_SCALE_PROPERTY, 0.55D, 0.20D, 1.0D)
			);
		}
	}
}
