package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import net.minecraft.client.Minecraft;

public final class PauCClientUploadBudgetController {
	private static double tokens;
	private static int lastFps = -1;
	private static double lastFrameTimeMs = -1.0D;
	private static int lastGrantedBudget;
	private static boolean snapMode;
	private static double cachedDirectGpuNormalScale = 1.0D;
	private static double cachedDirectGpuAggressiveScale = 1.0D;
	private static double cachedHighTargetUploadScale = 1.0D;
	private static double cachedHighTargetBurstNormalScale = 1.0D;
	private static double cachedHighTargetBurstSnapScale = 1.0D;

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
		double frameTimeMs = fps > 0 ? (1000.0D / fps) : -1.0D;
		boolean fpsFirstVanilla = PauCClientChunkPriorityScorer.isFpsFirstVanillaMode(targetFps);
		boolean backlogResolved = PauCClientFpsGovernor.isBacklogResolved();
		boolean recoveryBand = PauCClientFluidityState.lastSnapshot().band() == PauCClientFluidityState.Band.RECOVERY;
		int baseRefill = Math.max(5, budgetSnapshot.maxQueuedMeshSections() / 6);
		int backlogPenalty = Math.max(0, rendererStats.scheduledJobs() - Math.max(2, rendererStats.totalThreads() * 2));
		double fpsPenalty = fps > 0 ? clamp01((targetFps - fps) / (double) targetFps) : 0.35D;
		refreshCachedScales(fpsFirstVanilla, aggressiveUpload);
		double meshScale = Math.max(0.60D, Math.min(1.35D, PauCClientFpsGovernor.meshBudgetScale()));
		double directGpuScale = aggressiveUpload ? cachedDirectGpuAggressiveScale : cachedDirectGpuNormalScale;
		double refillScale = (aggressiveUpload ? 1.35D : 1.0D)
			* PauCLodShaderRuntime.uploadBudgetScale()
			* directGpuScale
			* meshScale
			* cachedHighTargetUploadScale;
		if (backlogResolved) {
			refillScale *= readDouble("pauc.lod.uploadBudgetResolvedRefillScale", aggressiveUpload ? 1.20D : 1.12D, 1.0D, 1.60D);
		}
		if (recoveryBand) {
			refillScale *= readDouble("pauc.lod.uploadBudgetRecoveryRefillScale", aggressiveUpload ? 1.42D : 1.30D, 1.0D, 2.25D);
			backlogPenalty = Math.max(0, backlogPenalty / 2);
			fpsPenalty = Math.min(fpsPenalty, readDouble("pauc.lod.uploadBudgetRecoveryMaxFpsPenalty", 0.35D, 0.0D, 0.90D));
		}
		double refill = Math.max(1.0D, (baseRefill - backlogPenalty) * (1.0D - (fpsPenalty * 0.58D)) * refillScale);
		if (recoveryBand) {
			refill = Math.max(refill, aggressiveUpload ? 10.0D : 7.0D);
		} else if (backlogResolved && rendererStats.scheduledJobs() <= Math.max(2, rendererStats.totalThreads())) {
			refill = Math.max(refill, aggressiveUpload ? 6.0D : 4.0D);
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
		);
		tokens = Math.min(maxTokens, tokens + refill);
		lastFps = fps;
		lastFrameTimeMs = frameTimeMs;
	}

	public static int acquireSectionBudget(int requestedSections, boolean snapUploadMode) {
		snapMode = snapUploadMode;
		if (requestedSections <= 0) {
			lastGrantedBudget = 0;
			return 0;
		}

		int targetFps = PauCClientTargetFps.effectiveTargetFps();
		boolean backlogResolved = PauCClientFpsGovernor.isBacklogResolved();
		boolean recoveryBand = PauCClientFluidityState.lastSnapshot().band() == PauCClientFluidityState.Band.RECOVERY;
		double multiplier = (snapUploadMode ? 1.15D : 1.0D)
			* PauCLodShaderRuntime.uploadBudgetScale()
			* (snapUploadMode ? cachedDirectGpuAggressiveScale : cachedDirectGpuNormalScale)
			* (snapUploadMode ? cachedHighTargetBurstSnapScale : cachedHighTargetBurstNormalScale);
		if (backlogResolved) {
			multiplier *= readDouble("pauc.lod.uploadBudgetResolvedBurstScale", snapUploadMode ? 1.16D : 1.08D, 1.0D, 1.50D);
		}
		if (recoveryBand) {
			multiplier *= readDouble("pauc.lod.uploadBudgetRecoveryBurstScale", snapUploadMode ? 1.35D : 1.22D, 1.0D, 2.25D);
		}
		if (!recoveryBand && lastFps > 0 && lastFps < targetFps * 0.8D) {
			multiplier *= lastFps < targetFps * 0.65D ? 0.65D : 0.85D;
		}
		int granted = Math.min(requestedSections, Math.max(0, (int) Math.floor(tokens * multiplier)));
		if (granted <= 0 && recoveryBand && tokens >= 1.0D) {
			granted = Math.min(requestedSections, snapUploadMode ? 8 : 5);
		} else if (granted <= 0 && backlogResolved && tokens >= 1.0D) {
			granted = Math.min(requestedSections, snapUploadMode ? 4 : 2);
		} else if (granted <= 0 && (snapUploadMode || lastFps <= 0)) {
			granted = Math.min(requestedSections, snapUploadMode ? 3 : 2);
		}
		if (recoveryBand && granted > 0) {
			int recoveryFloor = snapUploadMode ? 8 : (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? 6 : 5);
			granted = Math.min(requestedSections, Math.max(granted, recoveryFloor));
		} else if (backlogResolved && granted > 0) {
			int reboundFloor = snapUploadMode ? 4 : (PauCClientChunkPriorityScorer.isMovementCatchupActive() ? 3 : 2);
			granted = Math.min(requestedSections, Math.max(granted, reboundFloor));
		}
		tokens = Math.max(0.0D, tokens - granted);
		lastGrantedBudget = granted;
		return granted;
	}

	public static void reset() {
		tokens = 0.0D;
		lastFps = -1;
		lastFrameTimeMs = -1.0D;
		lastGrantedBudget = 0;
		snapMode = false;
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
			+ ", fps="
			+ lastFps
			+ ", frame="
			+ (lastFrameTimeMs >= 0.0D ? String.format(java.util.Locale.ROOT, "%.2fms", lastFrameTimeMs) : "-")
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
