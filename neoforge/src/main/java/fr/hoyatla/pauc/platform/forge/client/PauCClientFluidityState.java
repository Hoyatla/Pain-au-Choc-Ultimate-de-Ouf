package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
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
	private static final int CONFIG_RELOAD_TICKS = 100;
	private static final int LOG_THROTTLE_TICKS = 200;
	private static volatile Config config = Config.read();
	private static volatile Snapshot lastSnapshot = Snapshot.neutral();
	private static String lastRuntimeSignature = "";
	private static String lastLoggedBand = "";
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
		PauCLodShaderContext.DhShaderMode dhMode
	) {
		Config currentConfig = currentConfig();
		if (!currentConfig.enabled) {
			lastSnapshot = Snapshot.neutral();
			return lastSnapshot;
		}

		int modCount = loadedModCount();
		long maxHeapMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
		boolean hugePack = modCount >= currentConfig.hugeModCount;
		boolean heavyPack = modCount >= currentConfig.heavyModCount;
		boolean mediumPack = modCount >= Math.max(40, currentConfig.heavyModCount / 2);
		boolean constrainedHeap = maxHeapMiB > 0L && maxHeapMiB <= currentConfig.constrainedHeapMiB;
		boolean tightHeap = maxHeapMiB > 0L && maxHeapMiB <= currentConfig.tightHeapMiB;

		String runtimeSignature = shaderActive + ":" + shaderFamily + ":" + dhMode.id();
		if (!runtimeSignature.equals(lastRuntimeSignature)) {
			lastRuntimeSignature = runtimeSignature;
			shaderTransitionTicks = currentConfig.shaderTransitionHoldTicks;
		} else if (shaderTransitionTicks > 0) {
			shaderTransitionTicks--;
		}

		double fpsPressure = deliveryRatio > 0.0D ? clamp01((0.98D - deliveryRatio) / 0.48D) : 0.30D;
		double rawFpsPressure = targetFps > 0 ? clamp01((targetFps - rawFps) / (double) Math.max(1, targetFps)) : 0.0D;
		double queueLoad = clamp01(queuePressure * 1.85D);
		double heapLoad = clamp01((heapPressure - 0.76D) / 0.22D);
		double packLoad = hugePack ? 0.38D : heavyPack ? 0.24D : mediumPack ? 0.10D : 0.0D;
		double heapClassLoad = tightHeap ? 0.22D : constrainedHeap ? 0.12D : 0.0D;
		double transitionLoad = shaderTransitionTicks > 0 ? 0.18D : 0.0D;
		double pressure = clamp01(Math.max(Math.max(fpsPressure, rawFpsPressure * 0.8D), Math.max(queueLoad, heapLoad)) + packLoad + heapClassLoad + transitionLoad);
		Band band = pressure >= 0.68D ? Band.RELIEF : pressure >= 0.32D ? Band.BALANCED : Band.HEADROOM;
		if (rawFps > 0 && targetFps > 0 && rawFps < Math.max(24, targetFps / 2)) {
			band = Band.RELIEF;
		}
		if (heapPressure > 0.92D || queuePressure > 0.32D) {
			band = Band.RELIEF;
		}

		double packScale = hugePack ? 0.82D : heavyPack ? 0.90D : 1.0D;
		double heapScale = heapPressure > 0.90D ? 0.76D : heapPressure > 0.84D ? 0.88D : 1.0D;
		double transitionScale = shaderTransitionTicks > 0 ? 0.88D : 1.0D;
		double generationScale = clamp(1.08D - pressure * 0.58D, currentConfig.minGenerationScale, 1.18D) * packScale * heapScale * transitionScale;
		double warmupScale = clamp(1.05D - pressure * 0.50D, currentConfig.minWarmupScale, 1.15D) * packScale * heapScale;
		double meshScale = clamp(1.03D - pressure * 0.42D, currentConfig.minMeshScale, 1.08D) * heapScale;

		int targetCeiling = targetDistanceCeiling(band, shaderActive, hugePack, heavyPack, constrainedHeap, heapPressure);
		int retentionCeiling = retentionCeiling(band, shaderActive, hugePack, heavyPack, heapPressure);
		int visibleFillFloorCeiling = visibleFillFloorCeiling(band, shaderActive, hugePack, heavyPack, heapPressure);
		int emergencyGenerationCap = emergencyGenerationCap(band, shaderActive, hugePack, heavyPack, heapPressure);
		String reason = reason(band, modCount, maxHeapMiB, heapPressure, queuePressure, deliveryRatio, shaderTransitionTicks);

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
		configReloadTicks = 0;
		shaderTransitionTicks = 0;
		logTicks = 0;
	}

	public static String describeState() {
		return lastSnapshot.describe();
	}

	public static int adjustTargetDistance(int configuredTargetDistance, int policyTargetDistance) {
		Snapshot snapshot = lastSnapshot;
		if (!snapshot.active || snapshot.targetDistanceCeiling <= 0) {
			return policyTargetDistance;
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
		if (snapshot.emergencyGenerationCap > 0 && !movementCatchup) {
			scaled = Math.min(scaled, snapshot.emergencyGenerationCap);
		}
		if (movementCatchup) {
			scaled = Math.max(scaled, Math.min(generationRequestRateLimit, snapshot.band == Band.RELIEF ? 96 : 128));
		}
		return Math.max(20, Math.min(384, scaled));
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
		return snapshot.active ? Math.max(0.15D, Math.min(1.25D, scale * snapshot.meshScale)) : scale;
	}

	private static int targetDistanceCeiling(Band band, boolean shaderActive, boolean hugePack, boolean heavyPack, boolean constrainedHeap, double heapPressure) {
		if (band == Band.HEADROOM && !hugePack && heapPressure < 0.82D) {
			return 0;
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

	private static int retentionCeiling(Band band, boolean shaderActive, boolean hugePack, boolean heavyPack, double heapPressure) {
		if (band == Band.RELIEF || heapPressure > 0.90D) {
			return shaderActive ? 8 : 10;
		}
		if (hugePack || heavyPack) {
			return shaderActive ? 10 : 12;
		}
		return 0;
	}

	private static int visibleFillFloorCeiling(Band band, boolean shaderActive, boolean hugePack, boolean heavyPack, double heapPressure) {
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

	private static int emergencyGenerationCap(Band band, boolean shaderActive, boolean hugePack, boolean heavyPack, double heapPressure) {
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
		double heapPressure,
		double queuePressure,
		double deliveryRatio,
		int shaderTransitionTicks
	) {
		return "fluidity[band="
			+ band.id
			+ ", mods="
			+ (modCount >= 0 ? modCount : "?")
			+ ", heap="
			+ maxHeapMiB
			+ "MiB/"
			+ round(heapPressure * 100.0D)
			+ "%, queue="
			+ round(queuePressure * 100.0D)
			+ "%, ratio="
			+ round(deliveryRatio)
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

	private static String round(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	public enum Band {
		RELIEF("relief"),
		BALANCED("balanced"),
		HEADROOM("headroom");

		private final String id;

		Band(String id) {
			this.id = id;
		}
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
				readInt(SHADER_TRANSITION_HOLD_TICKS_PROPERTY, 220, 0, 1200),
				readDouble(MIN_GENERATION_SCALE_PROPERTY, 0.42D, 0.20D, 1.0D),
				readDouble(MIN_WARMUP_SCALE_PROPERTY, 0.45D, 0.20D, 1.0D),
				readDouble(MIN_MESH_SCALE_PROPERTY, 0.55D, 0.20D, 1.0D)
			);
		}
	}
}
