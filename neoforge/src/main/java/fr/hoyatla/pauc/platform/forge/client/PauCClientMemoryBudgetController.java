package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodGameplayProfile;
import fr.hoyatla.pauc.lod.PauCTerrainGeneratorDetector;

public final class PauCClientMemoryBudgetController {
	private static final long MIN_RAM_BUDGET_BYTES = 256L * 1024L * 1024L;
	private static final long MAX_RAM_BUDGET_BYTES = 4L * 1024L * 1024L * 1024L;
	private static final long BASE_CHUNK_RAM_BYTES = 16L * 1024L;
	private static final long PER_SECTION_RAM_BYTES = 24L * 1024L;
	private static final int MIN_TRACKED_CHUNKS = 96;
	private static final int MAX_TRACKED_CHUNKS = 262144;
	private static final int MIN_RETAINED_CHUNKS = 24;
	private static final int MAX_RETAINED_CHUNKS = 8192;
	private static final int MIN_PENDING_PLANS = 16;
	private static final int MAX_PENDING_PLANS = 2048;
	private static final int MIN_QUEUED_MESH_SECTIONS = 32;
	private static final int MAX_QUEUED_MESH_SECTIONS = 4096;
	private static final int MIN_HOT_MESH_SECTIONS = 96;
	private static final int MAX_HOT_MESH_SECTIONS = 12288;
	private static final int MIN_VRAM_MESH_SECTIONS = 128;
	private static final int MAX_VRAM_MESH_SECTIONS = 24576;

	private PauCClientMemoryBudgetController() {
	}

	public static BudgetSnapshot capture(int renderDistanceChunks, int warmMarginChunks) {
		long runtimeMaxMemory = Runtime.getRuntime().maxMemory();
		long configuredBudgetBytes = (long) PauCLodClientSettings.memoryBudgetMb() * 1024L * 1024L;
		double pressureScale = PauCClientFpsGovernor.meshBudgetScale();
		if (PauCClientFpsGovernor.isBacklogResolved()) {
			pressureScale = Math.max(
				pressureScale,
				Math.min(1.10D, pressureScale * readDouble("pauc.client.cache.meshBudgetResolvedScale", 1.08D, 1.0D, 1.30D))
			);
		}
		double gameplayScale = Math.max(0.70D, Math.min(1.10D, PauCLodGameplayProfile.qualityScale()));
		double effectiveScale = pressureScale * gameplayScale;
		PauCTerrainGeneratorDetector.ModpackClass modpackClass = PauCTerrainGeneratorDetector.currentModpackClass();
		boolean heavyModpack = modpackClass == PauCTerrainGeneratorDetector.ModpackClass.HEAVY
			|| modpackClass == PauCTerrainGeneratorDetector.ModpackClass.EXTREME;
		double heapBudgetRatio = readDouble("pauc.client.cache.heapBudgetRatio", heavyModpack ? modpackClass.heapBudgetShare() : 0.17D, 0.05D, 0.40D);
		double heapBudgetCeilingRatio = readDouble("pauc.client.cache.heapBudgetCeilingRatio", 0.38D, heapBudgetRatio, 0.75D);
		long heapShareBudgetBytes = Math.round(runtimeMaxMemory * heapBudgetRatio);
		long heapShareCeilingBytes = Math.round(runtimeMaxMemory * heapBudgetCeilingRatio);
		long modpackBoostBytes = (long) modpackClass.memoryBoostMb() * 1024L * 1024L;
		long baselineBudgetBytes = Math.max(configuredBudgetBytes, heapShareBudgetBytes) + modpackBoostBytes;
		long effectiveCeilingBytes = Math.min(MAX_RAM_BUDGET_BYTES, Math.max(MIN_RAM_BUDGET_BYTES, heapShareCeilingBytes + modpackBoostBytes));
		long clampedBudgetBytes = clamp(baselineBudgetBytes, MIN_RAM_BUDGET_BYTES, effectiveCeilingBytes);
		long ramBudgetBytes = readLong("pauc.client.cache.ramBudgetBytes", clampedBudgetBytes);
		int trackedChunks = readInt(
			"pauc.client.cache.maxTrackedChunks",
			clamp(((renderDistanceChunks + warmMarginChunks + 2) * (renderDistanceChunks + warmMarginChunks + 2)) * 3, MIN_TRACKED_CHUNKS, MAX_TRACKED_CHUNKS)
		);
		int retainedChunks = readInt(
			"pauc.client.cache.maxRetainedChunks",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 12, MIN_RETAINED_CHUNKS, MAX_RETAINED_CHUNKS), effectiveScale, MIN_RETAINED_CHUNKS)
		);
		int pendingPlans = readInt(
			"pauc.client.cache.maxPendingPlans",
			scaleBudget(clamp(renderDistanceChunks * 3, MIN_PENDING_PLANS, MAX_PENDING_PLANS), effectiveScale, MIN_PENDING_PLANS)
		);
		int queuedMeshSections = readInt(
			"pauc.client.cache.maxQueuedMeshSections",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 10, MIN_QUEUED_MESH_SECTIONS, MAX_QUEUED_MESH_SECTIONS), effectiveScale, MIN_QUEUED_MESH_SECTIONS)
		);
		int hotMeshSections = readInt(
			"pauc.client.cache.maxHotMeshSections",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 18, MIN_HOT_MESH_SECTIONS, MAX_HOT_MESH_SECTIONS), effectiveScale, MIN_HOT_MESH_SECTIONS)
		);
		int vramMeshSections = readInt(
			"pauc.client.cache.maxVramMeshSections",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 24, MIN_VRAM_MESH_SECTIONS, MAX_VRAM_MESH_SECTIONS), effectiveScale, MIN_VRAM_MESH_SECTIONS)
		);
		return new BudgetSnapshot(
			ramBudgetBytes,
			trackedChunks,
			retainedChunks,
			pendingPlans,
			queuedMeshSections,
			hotMeshSections,
			vramMeshSections,
			effectiveScale
		);
	}

	public static long estimateRamBytes(int nonEmptySectionCount) {
		return BASE_CHUNK_RAM_BYTES + (long) Math.max(0, nonEmptySectionCount) * PER_SECTION_RAM_BYTES;
	}

	public static int estimateGpuSectionCost(int nonEmptySectionCount) {
		return Math.max(1, nonEmptySectionCount);
	}

	private static int readInt(String key, int fallback) {
		String value = System.getProperty(key);
		if (value == null) {
			return fallback;
		}

		try {
			return Math.max(1, Integer.parseInt(value));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static long readLong(String key, long fallback) {
		String value = System.getProperty(key);
		if (value == null) {
			return fallback;
		}

		try {
			return Math.max(1L, Long.parseLong(value));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int scaleBudget(int value, double scale, int minimum) {
		return Math.max(minimum, (int) Math.floor(value * Math.max(0.30D, Math.min(1.60D, scale))));
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String value = System.getProperty(key);
		if (value == null) {
			return Math.max(min, Math.min(max, fallback));
		}

		try {
			return Math.max(min, Math.min(max, Double.parseDouble(value)));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	public record BudgetSnapshot(
		long ramBudgetBytes,
		int maxTrackedChunks,
		int maxRetainedChunks,
		int maxPendingPlans,
		int maxQueuedMeshSections,
		int maxHotMeshSections,
		int maxVramMeshSections,
		double pressureScale
	) {
		public String describe() {
			return "budget[ram="
				+ (ramBudgetBytes / (1024L * 1024L))
				+ "MiB, tracked="
				+ maxTrackedChunks
				+ ", retained="
				+ maxRetainedChunks
				+ ", pending="
				+ maxPendingPlans
				+ ", queuedMesh="
				+ maxQueuedMeshSections
				+ ", hotMesh="
				+ maxHotMeshSections
				+ ", vramMesh="
				+ maxVramMeshSections
				+ ", scale="
				+ String.format(java.util.Locale.ROOT, "%.2f", pressureScale)
				+ "]";
		}
	}
}
