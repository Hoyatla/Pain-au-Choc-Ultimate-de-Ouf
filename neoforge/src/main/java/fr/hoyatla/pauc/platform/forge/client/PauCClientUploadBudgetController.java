package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import net.minecraft.client.Minecraft;

public final class PauCClientUploadBudgetController {
	private static double tokens;
	private static int lastFps = -1;
	private static double lastFrameTimeMs = -1.0D;
	private static int lastGrantedBudget;
	private static boolean snapMode;

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
		int baseRefill = Math.max(4, budgetSnapshot.maxQueuedMeshSections() / 8);
		int backlogPenalty = Math.max(0, rendererStats.scheduledJobs() - Math.max(2, rendererStats.totalThreads() * 2));
		double fpsPenalty = fps > 0 ? clamp01((targetFps - fps) / (double) targetFps) : 0.35D;
		double refillScale = (aggressiveUpload ? 1.15D : 0.9D) * PauCLodShaderRuntime.uploadBudgetScale();
		double refill = Math.max(1.0D, (baseRefill - (backlogPenalty * 1.5D)) * (1.0D - (fpsPenalty * 0.85D)) * refillScale);
		if (fps > 0 && fps < targetFps * 0.65D) {
			refill = Math.min(refill, 2.0D);
		}
		double maxTokens = Math.max(8.0D, budgetSnapshot.maxQueuedMeshSections() * (aggressiveUpload ? 0.85D : 0.65D));
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
		double multiplier = (snapUploadMode ? 1.15D : 1.0D) * PauCLodShaderRuntime.uploadBudgetScale();
		if (lastFps > 0 && lastFps < targetFps * 0.8D) {
			multiplier *= lastFps < targetFps * 0.65D ? 0.55D : 0.8D;
		}
		int granted = Math.min(requestedSections, Math.max(0, (int) Math.floor(tokens * multiplier)));
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
}
