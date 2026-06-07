package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodClientSettings;

public final class PauCClientMemoryBudgetController {
	private static final long MIN_RAM_BUDGET_BYTES = 96L * 1024L * 1024L;
	private static final long MAX_RAM_BUDGET_BYTES = 768L * 1024L * 1024L;
	private static final long BASE_CHUNK_RAM_BYTES = 16L * 1024L;
	private static final long PER_SECTION_RAM_BYTES = 24L * 1024L;
	private static final int MIN_TRACKED_CHUNKS = 96;
	private static final int MAX_TRACKED_CHUNKS = 65536;
	private static final int MIN_RETAINED_CHUNKS = 24;
	private static final int MAX_RETAINED_CHUNKS = 2048;
	private static final int MIN_PENDING_PLANS = 12;
	private static final int MAX_PENDING_PLANS = 512;
	private static final int MIN_QUEUED_MESH_SECTIONS = 24;
	private static final int MAX_QUEUED_MESH_SECTIONS = 1024;
	private static final int MIN_HOT_MESH_SECTIONS = 64;
	private static final int MAX_HOT_MESH_SECTIONS = 3072;
	private static final int MIN_VRAM_MESH_SECTIONS = 96;
	private static final int MAX_VRAM_MESH_SECTIONS = 6144;

	private PauCClientMemoryBudgetController() {
	}

	public static BudgetSnapshot capture(int renderDistanceChunks, int warmMarginChunks) {
		long runtimeMaxMemory = Runtime.getRuntime().maxMemory();
		long configuredBudgetBytes = (long) PauCLodClientSettings.memoryBudgetMb() * 1024L * 1024L;
		double pressureScale = PauCClientFpsGovernor.meshBudgetScale();
		long ramBudgetBytes = readLong("pauc.client.cache.ramBudgetBytes", clamp(Math.min(runtimeMaxMemory / 8L, configuredBudgetBytes), MIN_RAM_BUDGET_BYTES, MAX_RAM_BUDGET_BYTES));
		int trackedChunks = readInt(
			"pauc.client.cache.maxTrackedChunks",
			clamp(((renderDistanceChunks + warmMarginChunks + 2) * (renderDistanceChunks + warmMarginChunks + 2)) * 3, MIN_TRACKED_CHUNKS, MAX_TRACKED_CHUNKS)
		);
		int retainedChunks = readInt(
			"pauc.client.cache.maxRetainedChunks",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 12, MIN_RETAINED_CHUNKS, MAX_RETAINED_CHUNKS), pressureScale, MIN_RETAINED_CHUNKS)
		);
		int pendingPlans = readInt(
			"pauc.client.cache.maxPendingPlans",
			scaleBudget(clamp(renderDistanceChunks * 3, MIN_PENDING_PLANS, MAX_PENDING_PLANS), pressureScale, MIN_PENDING_PLANS)
		);
		int queuedMeshSections = readInt(
			"pauc.client.cache.maxQueuedMeshSections",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 10, MIN_QUEUED_MESH_SECTIONS, MAX_QUEUED_MESH_SECTIONS), pressureScale, MIN_QUEUED_MESH_SECTIONS)
		);
		int hotMeshSections = readInt(
			"pauc.client.cache.maxHotMeshSections",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 18, MIN_HOT_MESH_SECTIONS, MAX_HOT_MESH_SECTIONS), pressureScale, MIN_HOT_MESH_SECTIONS)
		);
		int vramMeshSections = readInt(
			"pauc.client.cache.maxVramMeshSections",
			scaleBudget(clamp((renderDistanceChunks + warmMarginChunks) * 24, MIN_VRAM_MESH_SECTIONS, MAX_VRAM_MESH_SECTIONS), pressureScale, MIN_VRAM_MESH_SECTIONS)
		);
		return new BudgetSnapshot(
			ramBudgetBytes,
			trackedChunks,
			retainedChunks,
			pendingPlans,
			queuedMeshSections,
			hotMeshSections,
			vramMeshSections,
			pressureScale
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
		return Math.max(minimum, (int) Math.floor(value * Math.max(0.20D, Math.min(1.0D, scale))));
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
