package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCFrameSpikeAbsorber;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import net.minecraft.client.Minecraft;

public final class PauCClientUploadBudgetController {
	private static double tokens;
	private static int lastFps = -1;
	private static double lastFrameTimeMs = -1.0D;
	private static int lastGrantedBudget;
	private static boolean snapMode;
	private static long perFrameSeq = -1L;
	private static int perFrameGranted;
	private static double cachedDirectGpuNormalScale = 1.0D;
	private static double cachedDirectGpuAggressiveScale = 1.0D;
	private static double cachedHighTargetUploadScale = 1.0D;
	private static double cachedHighTargetBurstNormalScale = 1.0D;
	private static double cachedHighTargetBurstSnapScale = 1.0D;
	private static int lastTargetFps = -1;
	private static double lastRefill;
	private static double lastRefillScale = 1.0D;
	private static double lastMaxTokens;
	private static int lastRequestedBudget;
	private static double lastAcquireMultiplier = 1.0D;
	private static int lastPerFrameCeiling = -1;

	private PauCClientUploadBudgetController() {
	}

	public static void onClientTick(
		Minecraft minecraft,
		PauCorRendererBridge.RendererStats rendererStats,
		PauCClientMemoryBudgetController.BudgetSnapshot budgetSnapshot,
		boolean aggressiveUpload
		) {
		int fps = queryFps(minecraft);
		int targetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(minecraft);
		if (playerVideo.fpsUnlimited() && fps > 0) {
			targetFps = Math.max(30, fps);
		}
		lastTargetFps = targetFps;
		double frameTimeMs = fps > 0 ? (1000.0D / fps) : -1.0D;
		boolean fpsFirstVanilla = PauCClientChunkPriorityScorer.isFpsFirstVanillaMode(targetFps);
		boolean backlogResolved = PauCClientFpsGovernor.isBacklogResolved();
		boolean recoveryBand = PauCClientFluidityState.lastSnapshot().band() == PauCClientFluidityState.Band.RECOVERY;
		boolean nearCoverageDebt = PauCClientFrontierWarmupManager.hasNearCoverageDebt();
		boolean directFill = PauCClientFrontierWarmupManager.isDirectHorizonFillActive();
		int baseRefill = Math.max(5, budgetSnapshot.maxQueuedMeshSections() / 6);
		int backlogPenalty = Math.max(0, rendererStats.scheduledJobs() - Math.max(2, rendererStats.totalThreads() * 2));
		double fpsPenalty = fps > 0 ? clamp01((targetFps - fps) / (double) targetFps) : 0.35D;
		refreshCachedScales(fpsFirstVanilla, aggressiveUpload);
		double meshScale = Math.max(0.60D, Math.min(1.35D, PauCClientFpsGovernor.meshBudgetScale()));
		double directGpuScale = aggressiveUpload ? cachedDirectGpuAggressiveScale : cachedDirectGpuNormalScale;
		double spikeUploadScale = spikeUploadScale(aggressiveUpload);
		double refillScale = (aggressiveUpload ? 1.35D : 1.0D)
			* PauCLodShaderRuntime.uploadBudgetScale()
			* directGpuScale
			* meshScale
			* cachedHighTargetUploadScale
			* spikeUploadScale;
		if (backlogResolved) {
			refillScale *= readDouble("pauc.lod.uploadBudgetResolvedRefillScale", aggressiveUpload ? 1.20D : 1.12D, 1.0D, 1.60D);
		}
		if (recoveryBand) {
			refillScale *= readDouble("pauc.lod.uploadBudgetRecoveryRefillScale", aggressiveUpload ? 1.42D : 1.30D, 1.0D, 2.25D);
			backlogPenalty = Math.max(0, backlogPenalty / 2);
			fpsPenalty = Math.min(fpsPenalty, readDouble("pauc.lod.uploadBudgetRecoveryMaxFpsPenalty", 0.35D, 0.0D, 0.90D));
		}
		if (nearCoverageDebt) {
			refillScale *= readDouble("pauc.lod.nearCoverageUploadRefillScale", aggressiveUpload ? 1.28D : 1.18D, 1.0D, 2.25D);
			backlogPenalty = Math.max(0, backlogPenalty / 2);
			fpsPenalty = Math.min(fpsPenalty, readDouble("pauc.lod.nearCoverageUploadMaxFpsPenalty", 0.32D, 0.0D, 0.90D));
		}
		if (directFill) {
			refillScale *= readDouble("pauc.lod.directHorizonUploadRefillScale", aggressiveUpload ? 1.35D : 1.20D, 1.0D, 2.25D);
		}
		double refill = Math.max(1.0D, (baseRefill - backlogPenalty) * (1.0D - (fpsPenalty * 0.58D)) * refillScale);
		if (nearCoverageDebt) {
			refill = Math.max(refill, aggressiveUpload ? 12.0D : 8.0D);
		} else if (recoveryBand) {
			refill = Math.max(refill, aggressiveUpload ? 10.0D : 7.0D);
		} else if (backlogResolved && rendererStats.scheduledJobs() <= Math.max(2, rendererStats.totalThreads())) {
			refill = Math.max(refill, aggressiveUpload ? 6.0D : 4.0D);
		}
		if (directFill) {
			refill = Math.max(refill, aggressiveUpload ? 12.0D : 8.0D);
		}
		if (!recoveryBand && fps > 0 && fps < targetFps * 0.65D) {
			refill = Math.min(refill, aggressiveUpload ? 4.0D : 3.0D);
		}
		double maxTokens = Math.max(
			16.0D,
			budgetSnapshot.maxQueuedMeshSections()
				* (aggressiveUpload ? 1.05D : 0.85D)
				* (fpsFirstVanilla ? 0.72D : 1.0D)
				* (backlogResolved ? readDouble("pauc.lod.uploadBudgetResolvedTokenScale", 1.10D, 1.0D, 1.40D) : 1.0D)
				* (recoveryBand ? readDouble("pauc.lod.uploadBudgetRecoveryTokenScale", 1.30D, 1.0D, 2.0D) : 1.0D)
				* (nearCoverageDebt ? readDouble("pauc.lod.nearCoverageUploadTokenScale", 1.28D, 1.0D, 2.0D) : 1.0D)
		);
		if (directFill) {
			maxTokens *= readDouble("pauc.lod.directHorizonUploadTokenScale", 1.30D, 1.0D, 2.0D);
		}
		tokens = Math.min(maxTokens, tokens + refill);
		lastRefill = refill;
		lastRefillScale = refillScale;
		lastMaxTokens = maxTokens;
		lastFps = fps;
		lastFrameTimeMs = frameTimeMs;
	}

	public static int acquireSectionBudget(int requestedSections, boolean snapUploadMode) {
		snapMode = snapUploadMode;
		lastRequestedBudget = requestedSections;
		lastPerFrameCeiling = -1;
		if (requestedSections <= 0) {
			lastGrantedBudget = 0;
			return 0;
		}

		int targetFps = PauCClientTargetFps.effectiveTargetFps();
		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(Minecraft.getInstance());
		if (playerVideo.fpsUnlimited() && lastFps > 0) {
			targetFps = Math.max(30, lastFps);
		}
		boolean backlogResolved = PauCClientFpsGovernor.isBacklogResolved();
		boolean recoveryBand = PauCClientFluidityState.lastSnapshot().band() == PauCClientFluidityState.Band.RECOVERY;
		boolean nearCoverageDebt = PauCClientFrontierWarmupManager.hasNearCoverageDebt();
		boolean directFill = PauCClientFrontierWarmupManager.isDirectHorizonFillActive();
		double multiplier = (snapUploadMode ? 1.15D : 1.0D)
			* PauCLodShaderRuntime.uploadBudgetScale()
			* (snapUploadMode ? cachedDirectGpuAggressiveScale : cachedDirectGpuNormalScale)
			* (snapUploadMode ? cachedHighTargetBurstSnapScale : cachedHighTargetBurstNormalScale)
			* spikeUploadScale(snapUploadMode);
		if (backlogResolved) {
			multiplier *= readDouble("pauc.lod.uploadBudgetResolvedBurstScale", snapUploadMode ? 1.16D : 1.08D, 1.0D, 1.50D);
		}
		if (recoveryBand) {
			multiplier *= readDouble("pauc.lod.uploadBudgetRecoveryBurstScale", snapUploadMode ? 1.35D : 1.22D, 1.0D, 2.25D);
		}
		if (nearCoverageDebt) {
			multiplier *= readDouble("pauc.lod.nearCoverageUploadBurstScale", snapUploadMode ? 1.26D : 1.14D, 1.0D, 2.25D);
		}
		if (directFill) {
			multiplier *= readDouble("pauc.lod.directHorizonUploadBurstScale", snapUploadMode ? 1.32D : 1.18D, 1.0D, 2.25D);
		}
		if (!recoveryBand && lastFps > 0 && lastFps < targetFps * 0.8D) {
			double lowFpsScale = lastFps < targetFps * 0.65D ? 0.65D : 0.85D;
			if (nearCoverageDebt) {
				lowFpsScale = Math.max(
					lowFpsScale,
					readDouble("pauc.lod.nearCoverageLowFpsUploadScale", 0.78D, 0.35D, 1.0D)
				);
			}
			if (directFill) {
				lowFpsScale = Math.max(lowFpsScale, readDouble("pauc.lod.directHorizonLowFpsUploadScale", 0.88D, 0.35D, 1.0D));
			}
			multiplier *= lowFpsScale;
		}
		lastAcquireMultiplier = multiplier;
		int granted = Math.min(requestedSections, Math.max(0, (int) Math.floor(tokens * multiplier)));
		if (granted <= 0 && nearCoverageDebt && tokens >= 1.0D) {
			granted = Math.min(requestedSections, snapUploadMode ? 8 : 5);
		} else if (granted <= 0 && recoveryBand && tokens >= 1.0D) {
			granted = Math.min(requestedSections, snapUploadMode ? 8 : 5);
		} else if (granted <= 0 && backlogResolved && tokens >= 1.0D) {
			granted = Math.min(requestedSections, snapUploadMode ? 4 : 2);
		} else if (granted <= 0 && (snapUploadMode || lastFps <= 0)) {
			granted = Math.min(requestedSections, snapUploadMode ? 3 : 2);
		}
		if (granted <= 0 && directFill && tokens >= 1.0D) {
			granted = Math.min(requestedSections, snapUploadMode ? 8 : 6);
		}
		if (nearCoverageDebt && granted > 0) {
			int nearFloor = snapUploadMode ? 8 : (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? 7 : 5);
			granted = Math.min(requestedSections, Math.max(granted, nearFloor));
		} else if (recoveryBand && granted > 0) {
			int recoveryFloor = snapUploadMode ? 8 : (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? 6 : 5);
			granted = Math.min(requestedSections, Math.max(granted, recoveryFloor));
		} else if (backlogResolved && granted > 0) {
			int reboundFloor = snapUploadMode ? 4 : (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? 3 : 2);
			granted = Math.min(requestedSections, Math.max(granted, reboundFloor));
		}
		if (directFill && granted > 0) {
			int directFillFloor = snapUploadMode ? 12 : 8;
			granted = Math.min(requestedSections, Math.max(granted, directFillFloor));
		}
		// Per-frame burst ceiling: cap how many sections upload in a SINGLE frame so a large ready-burst (after a
		// teleport / chunk load) spreads across a few frames instead of stalling the render thread inside
		// VertexBuffer.upload. Full quality — the same sections upload a few frames later — and load stays fast
		// (default 48/frame ≈ 2880 sections/s at 60fps; higher while catching up). Kill-switch below.
		if (Boolean.parseBoolean(System.getProperty("pauc.lod.uploadPerFrameCap", "true"))) {
			long seq = PauCFrameSpikeAbsorber.frameSeq();
			if (seq != perFrameSeq) {
				perFrameSeq = seq;
				perFrameGranted = 0;
			}
			int ceiling = readInt("pauc.lod.uploadMaxSectionsPerFrame", 48, 4, 1024);
			if (recoveryBand) {
				ceiling = Math.max(ceiling, readInt("pauc.lod.uploadMaxSectionsPerFrameRecovery", 96, 4, 2048));
			} else if (nearCoverageDebt) {
				ceiling = Math.max(ceiling, readInt("pauc.lod.uploadMaxSectionsPerFrameNearCoverage", 96, 4, 2048));
			} else if (backlogResolved) {
				ceiling = Math.max(ceiling, readInt("pauc.lod.uploadMaxSectionsPerFrameRebound", 72, 4, 2048));
			}
			if (directFill) {
				ceiling = Math.max(ceiling, readInt("pauc.lod.uploadMaxSectionsPerFrameDirectFill", snapUploadMode ? 192 : 144, 4, 2048));
			}
			if (PauCFrameSpikeAbsorber.isAbsorbing() && !recoveryBand) {
				ceiling = Math.max(4, (int) Math.round(ceiling * spikeUploadScale(snapUploadMode)));
			}
			lastPerFrameCeiling = ceiling;
			granted = Math.min(granted, Math.max(0, ceiling - perFrameGranted));
			perFrameGranted += granted;
		}

		tokens = Math.max(0.0D, tokens - granted);
		lastGrantedBudget = granted;
		return granted;
	}

	public static void reset() {
		tokens = 0.0D;
		lastFps = -1;
		lastFrameTimeMs = -1.0D;
		lastTargetFps = -1;
		lastRefill = 0.0D;
		lastRefillScale = 1.0D;
		lastMaxTokens = 0.0D;
		lastGrantedBudget = 0;
		lastRequestedBudget = 0;
		lastAcquireMultiplier = 1.0D;
		lastPerFrameCeiling = -1;
		snapMode = false;
		perFrameSeq = -1L;
		perFrameGranted = 0;
		cachedDirectGpuNormalScale = 1.0D;
		cachedDirectGpuAggressiveScale = 1.0D;
		cachedHighTargetUploadScale = 1.0D;
		cachedHighTargetBurstNormalScale = 1.0D;
		cachedHighTargetBurstSnapScale = 1.0D;
	}

	public static String describeState() {
		return "uploadBudget[tokens="
			+ (int) tokens
			+ ", granted="
			+ lastGrantedBudget
			+ ", requested="
			+ lastRequestedBudget
			+ ", fps="
			+ lastFps
			+ "/"
			+ lastTargetFps
			+ ", frame="
			+ (lastFrameTimeMs >= 0.0D ? String.format(java.util.Locale.ROOT, "%.2fms", lastFrameTimeMs) : "-")
			+ ", refill="
			+ String.format(java.util.Locale.ROOT, "%.1f", lastRefill)
			+ "@"
			+ String.format(java.util.Locale.ROOT, "%.2f", lastRefillScale)
			+ ", burst="
			+ String.format(java.util.Locale.ROOT, "%.2f", lastAcquireMultiplier)
			+ ", maxTokens="
			+ String.format(java.util.Locale.ROOT, "%.1f", lastMaxTokens)
			+ ", ceiling="
			+ lastPerFrameCeiling
			+ ", spikeScale="
			+ String.format(java.util.Locale.ROOT, "%.2f", spikeUploadScale(snapMode))
			+ ", snap="
			+ snapMode
			+ "]";
	}

	private static int queryFps(Minecraft minecraft) {
		return PauCClientFrameMetrics.queryFps(minecraft);
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static double spikeUploadScale(boolean aggressiveUpload) {
		if (!PauCFrameSpikeAbsorber.isAbsorbing()) {
			return 1.0D;
		}
		double workScale = PauCFrameSpikeAbsorber.workScale();
		double minimum = aggressiveUpload ? 0.68D : 0.58D;
		return Math.max(minimum, Math.min(1.0D, 0.45D + (workScale * 0.55D)));
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static void refreshCachedScales(boolean fpsFirstVanilla, boolean aggressiveUpload) {
		boolean directGpuUpload = PauCEmbeddedDhBridge.isDirectGpuUploadActive();
		cachedDirectGpuNormalScale = directGpuUpload
			? readDouble("pauc.lod.directGpuUploadBudgetScale", 1.22D, 1.0D, 1.75D)
			: 1.0D;
		cachedDirectGpuAggressiveScale = directGpuUpload
			? readDouble("pauc.lod.directGpuUploadBudgetScale", 1.36D, 1.0D, 1.75D)
			: 1.0D;
		cachedHighTargetUploadScale = highTargetUploadScale(fpsFirstVanilla, aggressiveUpload);
		cachedHighTargetBurstNormalScale = highTargetBurstScale(fpsFirstVanilla, false);
		cachedHighTargetBurstSnapScale = highTargetBurstScale(fpsFirstVanilla, true);
	}

	private static double highTargetUploadScale(boolean fpsFirstVanilla, boolean aggressiveUpload) {
		if (!fpsFirstVanilla) {
			return 1.0D;
		}
		return readDouble(
			"pauc.lod.vanillaHighTargetUploadBudgetScale",
			aggressiveUpload ? 0.86D : 0.74D,
			0.25D,
			1.0D
		);
	}

	private static double highTargetBurstScale(boolean fpsFirstVanilla, boolean snapUploadMode) {
		if (!fpsFirstVanilla) {
			return 1.0D;
		}
		return readDouble(
			"pauc.lod.vanillaHighTargetUploadBurstScale",
			snapUploadMode ? 0.92D : 0.78D,
			0.25D,
			1.0D
		);
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
