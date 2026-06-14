package fr.hoyatla.pauc.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

public final class PauCVillagePerformanceDiagnostics {
	private static final String ENABLED_PROPERTY = "pauc.lod.villageDiagnostics";
	private static final String VILLAGE_PRESSURE_ENABLED_PROPERTY = "pauc.lod.villagePressureBudget";
	private static final String VILLAGE_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.villagePressureEntityThreshold";
	private static final String TOTAL_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.villagePressureTotalEntityThreshold";
	private static final String RENDERED_VILLAGE_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.villagePressureRenderedEntityThreshold";
	private static final String RENDERED_VILLAGE_BLOCK_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.villagePressureRenderedBlockEntityThreshold";
	private static final String TOTAL_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.villagePressureTotalRenderedBlockEntityThreshold";
	private static final String TOTAL_RENDERED_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.villagePressureTotalRenderedEntityThreshold";
	private static final String VILLAGE_PRESSURE_HOLD_TICKS_PROPERTY = "pauc.lod.villagePressureHoldTicks";
	private static final String VILLAGE_PRESSURE_MIN_RENDER_SHARE_PROPERTY = "pauc.lod.villagePressureMinRenderShare";
	private static final String VILLAGE_PRESSURE_MIN_RENDER_SIGNALS_PROPERTY = "pauc.lod.villagePressureMinRenderSignals";
	private static final String HORDE_PRESSURE_ENABLED_PROPERTY = "pauc.lod.hordePressureBudget";
	private static final String HORDE_TOTAL_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.hordePressureTotalEntityThreshold";
	private static final String HORDE_RENDERED_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.hordePressureRenderedEntityThreshold";
	private static final String HORDE_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.hordePressureRenderedBlockEntityThreshold";
	private static final String HORDE_PRESSURE_HOLD_TICKS_PROPERTY = "pauc.lod.hordePressureHoldTicks";
	private static final String HORDE_ANIMATION_LOD_ENABLED_PROPERTY = "pauc.lod.hordeAnimationLod";
	private static final String HORDE_BLOCK_ENTITY_STEADY_BUDGET_PROPERTY = "pauc.lod.hordeBlockEntityBudgetSteady";
	private static final String HORDE_BLOCK_ENTITY_SEVERE_BUDGET_PROPERTY = "pauc.lod.hordeBlockEntityBudgetSevere";
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	private static final Map<EntityType<?>, Boolean> VILLAGE_ENTITY_TYPES = new IdentityHashMap<>();
	private static final Map<BlockEntityType<?>, Boolean> VILLAGE_BLOCK_ENTITY_TYPES = new IdentityHashMap<>();
	private static volatile boolean diagnosticsEnabled = true;
	private static int sampleTicks;
	private static int lastClientEntityCount;
	private static int lastClientVillageEntityCount;
	private static int lastEntitySortCount;
	private static int lastEntitySortGroups;
	private static long lastEntitySortNanos;
	private static long maxEntitySortNanos;
	private static long entitySortCalls;
	private static long entitySortNanos;
	private static long renderedEntities;
	private static long renderedVillageEntities;
	private static long renderedBlockEntities;
	private static long renderedVillageBlockEntities;
	private static long culledEntities;
	private static long culledVillageEntities;
	private static long culledBlockEntities;
	private static long culledVillageBlockEntities;
	private static long renderedEntitiesWindow;
	private static long renderedVillageEntitiesWindow;
	private static long renderedBlockEntitiesWindow;
	private static long renderedVillageBlockEntitiesWindow;
	private static long culledEntitiesWindow;
	private static long culledVillageEntitiesWindow;
	private static long culledBlockEntitiesWindow;
	private static long culledVillageBlockEntitiesWindow;
	private static long lastRenderedEntitiesWindow;
	private static long lastRenderedVillageEntitiesWindow;
	private static long lastRenderedBlockEntitiesWindow;
	private static long lastRenderedVillageBlockEntitiesWindow;
	private static long lastCulledEntitiesWindow;
	private static long lastCulledVillageEntitiesWindow;
	private static long lastCulledBlockEntitiesWindow;
	private static long lastCulledVillageBlockEntitiesWindow;
	private static long maxRenderedEntitiesWindow;
	private static long maxRenderedVillageEntitiesWindow;
	private static long maxRenderedBlockEntitiesWindow;
	private static long maxRenderedVillageBlockEntitiesWindow;
	private static long maxCulledEntitiesWindow;
	private static long maxCulledVillageEntitiesWindow;
	private static long maxCulledBlockEntitiesWindow;
	private static long maxCulledVillageBlockEntitiesWindow;
	private static long entityIdCacheHits;
	private static long entityIdCacheMisses;
	private static long blockEntityIdCacheHits;
	private static long blockEntityIdCacheMisses;
	private static volatile boolean villagePressureActive;
	private static int villagePressureTicks;
	private static long villagePressureActiveTicks;
	private static long villagePressureActivations;
	private static volatile boolean hordePressureActive;
	private static int hordePressureTicks;
	private static long hordePressureActiveTicks;
	private static long hordePressureActivations;
	private static int lastAnimationLodTier;
	private static int lastBlockEntityFrameBudget;

	private PauCVillagePerformanceDiagnostics() {
	}

	public static void reset() {
		sampleTicks = 0;
		lastClientEntityCount = 0;
		lastClientVillageEntityCount = 0;
		lastEntitySortCount = 0;
		lastEntitySortGroups = 0;
		lastEntitySortNanos = 0L;
		maxEntitySortNanos = 0L;
		entitySortCalls = 0L;
		entitySortNanos = 0L;
		renderedEntities = 0L;
		renderedVillageEntities = 0L;
		renderedBlockEntities = 0L;
		renderedVillageBlockEntities = 0L;
		culledEntities = 0L;
		culledVillageEntities = 0L;
		culledBlockEntities = 0L;
		culledVillageBlockEntities = 0L;
		renderedEntitiesWindow = 0L;
		renderedVillageEntitiesWindow = 0L;
		renderedBlockEntitiesWindow = 0L;
		renderedVillageBlockEntitiesWindow = 0L;
		culledEntitiesWindow = 0L;
		culledVillageEntitiesWindow = 0L;
		culledBlockEntitiesWindow = 0L;
		culledVillageBlockEntitiesWindow = 0L;
		lastRenderedEntitiesWindow = 0L;
		lastRenderedVillageEntitiesWindow = 0L;
		lastRenderedBlockEntitiesWindow = 0L;
		lastRenderedVillageBlockEntitiesWindow = 0L;
		lastCulledEntitiesWindow = 0L;
		lastCulledVillageEntitiesWindow = 0L;
		lastCulledBlockEntitiesWindow = 0L;
		lastCulledVillageBlockEntitiesWindow = 0L;
		maxRenderedEntitiesWindow = 0L;
		maxRenderedVillageEntitiesWindow = 0L;
		maxRenderedBlockEntitiesWindow = 0L;
		maxRenderedVillageBlockEntitiesWindow = 0L;
		maxCulledEntitiesWindow = 0L;
		maxCulledVillageEntitiesWindow = 0L;
		maxCulledBlockEntitiesWindow = 0L;
		maxCulledVillageBlockEntitiesWindow = 0L;
		entityIdCacheHits = 0L;
		entityIdCacheMisses = 0L;
		blockEntityIdCacheHits = 0L;
		blockEntityIdCacheMisses = 0L;
		villagePressureActive = false;
		villagePressureTicks = 0;
		villagePressureActiveTicks = 0L;
		villagePressureActivations = 0L;
		hordePressureActive = false;
		hordePressureTicks = 0;
		hordePressureActiveTicks = 0L;
		hordePressureActivations = 0L;
		lastAnimationLodTier = 0;
		lastBlockEntityFrameBudget = 0;
		diagnosticsEnabled = true;
	}

	public static void onClientTick(Minecraft minecraft) {
		diagnosticsEnabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
		if (!diagnosticsEnabled || minecraft == null || minecraft.level == null) {
			villagePressureActive = false;
			villagePressureTicks = 0;
			hordePressureActive = false;
			hordePressureTicks = 0;
			lastAnimationLodTier = 0;
			lastBlockEntityFrameBudget = 0;
			resetRenderWindow();
			return;
		}

		if (++sampleTicks < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		sampleTicks = 0;

		int totalEntities = 0;
		int villageEntities = 0;
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (entity == null) {
				continue;
			}
			totalEntities++;
			if (isVillageEntityType(entity.getType())) {
				villageEntities++;
			}
		}
		lastClientEntityCount = totalEntities;
		lastClientVillageEntityCount = villageEntities;
		long windowEntities = renderedEntitiesWindow;
		long windowVillageEntities = renderedVillageEntitiesWindow;
		long windowBlockEntities = renderedBlockEntitiesWindow;
		long windowVillageBlockEntities = renderedVillageBlockEntitiesWindow;
		long windowCulledEntities = culledEntitiesWindow;
		long windowCulledVillageEntities = culledVillageEntitiesWindow;
		long windowCulledBlockEntities = culledBlockEntitiesWindow;
		long windowCulledVillageBlockEntities = culledVillageBlockEntitiesWindow;
		lastRenderedEntitiesWindow = windowEntities;
		lastRenderedVillageEntitiesWindow = windowVillageEntities;
		lastRenderedBlockEntitiesWindow = windowBlockEntities;
		lastRenderedVillageBlockEntitiesWindow = windowVillageBlockEntities;
		lastCulledEntitiesWindow = windowCulledEntities;
		lastCulledVillageEntitiesWindow = windowCulledVillageEntities;
		lastCulledBlockEntitiesWindow = windowCulledBlockEntities;
		lastCulledVillageBlockEntitiesWindow = windowCulledVillageBlockEntities;
		maxRenderedEntitiesWindow = Math.max(maxRenderedEntitiesWindow, windowEntities);
		maxRenderedVillageEntitiesWindow = Math.max(maxRenderedVillageEntitiesWindow, windowVillageEntities);
		maxRenderedBlockEntitiesWindow = Math.max(maxRenderedBlockEntitiesWindow, windowBlockEntities);
		maxRenderedVillageBlockEntitiesWindow = Math.max(maxRenderedVillageBlockEntitiesWindow, windowVillageBlockEntities);
		maxCulledEntitiesWindow = Math.max(maxCulledEntitiesWindow, windowCulledEntities);
		maxCulledVillageEntitiesWindow = Math.max(maxCulledVillageEntitiesWindow, windowCulledVillageEntities);
		maxCulledBlockEntitiesWindow = Math.max(maxCulledBlockEntitiesWindow, windowCulledBlockEntities);
		maxCulledVillageBlockEntitiesWindow = Math.max(maxCulledVillageBlockEntitiesWindow, windowCulledVillageBlockEntities);
		resetRenderWindow();
		updateVillagePressure(totalEntities, villageEntities, windowEntities, windowVillageEntities, windowBlockEntities, windowVillageBlockEntities);
		updateHordePressure(totalEntities, windowEntities, windowBlockEntities);
	}

	public static void recordEntityRender(Entity entity) {
		if (!diagnosticsEnabled || entity == null) {
			return;
		}

		renderedEntities++;
		renderedEntitiesWindow++;
		if (isVillageEntityType(entity.getType())) {
			renderedVillageEntities++;
			renderedVillageEntitiesWindow++;
		}
	}

	public static void recordBlockEntityRender(BlockEntity blockEntity) {
		if (!diagnosticsEnabled || blockEntity == null) {
			return;
		}

		renderedBlockEntities++;
		renderedBlockEntitiesWindow++;
		if (isVillageBlockEntityType(blockEntity.getType())) {
			renderedVillageBlockEntities++;
			renderedVillageBlockEntitiesWindow++;
		}
	}

	public static void recordEntityCull(Entity entity) {
		if (!diagnosticsEnabled || entity == null) {
			return;
		}

		culledEntities++;
		culledEntitiesWindow++;
		if (isVillageEntityType(entity.getType())) {
			culledVillageEntities++;
			culledVillageEntitiesWindow++;
		}
	}

	public static void recordBlockEntityCull(BlockEntity blockEntity) {
		if (!diagnosticsEnabled || blockEntity == null) {
			return;
		}

		culledBlockEntities++;
		culledBlockEntitiesWindow++;
		if (isVillageBlockEntityType(blockEntity.getType())) {
			culledVillageBlockEntities++;
			culledVillageBlockEntitiesWindow++;
		}
	}

	public static void recordEntityIdCache(boolean hit) {
		if (!diagnosticsEnabled) {
			return;
		}
		if (hit) {
			entityIdCacheHits++;
		} else {
			entityIdCacheMisses++;
		}
	}

	public static void recordBlockEntityIdCache(boolean hit) {
		if (!diagnosticsEnabled) {
			return;
		}
		if (hit) {
			blockEntityIdCacheHits++;
		} else {
			blockEntityIdCacheMisses++;
		}
	}

	public static void recordEntitySort(int entityCount, int groupCount, long elapsedNanos) {
		if (!diagnosticsEnabled) {
			return;
		}

		lastEntitySortCount = entityCount;
		lastEntitySortGroups = groupCount;
		lastEntitySortNanos = Math.max(0L, elapsedNanos);
		maxEntitySortNanos = Math.max(maxEntitySortNanos, lastEntitySortNanos);
		entitySortCalls++;
		entitySortNanos += lastEntitySortNanos;
	}

	public static boolean isVillagePressureActive() {
		return diagnosticsEnabled
			&& Boolean.parseBoolean(System.getProperty(VILLAGE_PRESSURE_ENABLED_PROPERTY, "true"))
			&& villagePressureActive;
	}

	public static boolean isHordePressureActive() {
		return diagnosticsEnabled
			&& Boolean.parseBoolean(System.getProperty(HORDE_PRESSURE_ENABLED_PROPERTY, "true"))
			&& hordePressureActive;
	}

	public static int animationLodTier() {
		if (!diagnosticsEnabled || !Boolean.parseBoolean(System.getProperty(HORDE_ANIMATION_LOD_ENABLED_PROPERTY, "true"))) {
			return 0;
		}
		return Math.max(0, Math.min(3, lastAnimationLodTier));
	}

	public static int blockEntityFrameBudget() {
		return Math.max(0, lastBlockEntityFrameBudget);
	}

	public static int lastClientEntityCount() {
		return lastClientEntityCount;
	}

	public static int lastClientVillageEntityCount() {
		return lastClientVillageEntityCount;
	}

	public static long lastRenderedEntitiesWindow() {
		return lastRenderedEntitiesWindow;
	}

	public static long lastRenderedVillageEntitiesWindow() {
		return lastRenderedVillageEntitiesWindow;
	}

	public static long lastRenderedBlockEntitiesWindow() {
		return lastRenderedBlockEntitiesWindow;
	}

	public static long lastRenderedVillageBlockEntitiesWindow() {
		return lastRenderedVillageBlockEntitiesWindow;
	}

	public static boolean isVillageEntity(Entity entity) {
		return entity != null && isVillageEntityType(entity.getType());
	}

	public static boolean isVillageBlockEntity(BlockEntity blockEntity) {
		return blockEntity != null && isVillageBlockEntityType(blockEntity.getType());
	}

	public static String describeState() {
		return "villagePerf[clientEntities="
			+ lastClientEntityCount
			+ ", villageEntities="
			+ lastClientVillageEntityCount
			+ ", pressure="
			+ (isVillagePressureActive() ? "on" : "off")
			+ ", horde="
			+ (isHordePressureActive() ? "on" : "off")
			+ "/tier="
			+ animationLodTier()
			+ "/beBudget="
			+ blockEntityFrameBudget()
			+ ", renderedEntities="
			+ renderedEntities
			+ "/village="
			+ renderedVillageEntities
			+ ", renderedBlockEntities="
			+ renderedBlockEntities
			+ "/village="
			+ renderedVillageBlockEntities
			+ ", culledEntities="
			+ culledEntities
			+ "/village="
			+ culledVillageEntities
			+ ", culledBlockEntities="
			+ culledBlockEntities
			+ "/village="
			+ culledVillageBlockEntities
			+ ", renderWindow="
			+ lastRenderedEntitiesWindow
			+ "/village="
			+ lastRenderedVillageEntitiesWindow
			+ ", blockWindow="
			+ lastRenderedBlockEntitiesWindow
			+ "/village="
			+ lastRenderedVillageBlockEntitiesWindow
			+ ", cullWindow="
			+ lastCulledEntitiesWindow
			+ "/village="
			+ lastCulledVillageEntitiesWindow
			+ ", blockCullWindow="
			+ lastCulledBlockEntitiesWindow
			+ "/village="
			+ lastCulledVillageBlockEntitiesWindow
			+ ", maxRenderWindow="
			+ maxRenderedEntitiesWindow
			+ "/village="
			+ maxRenderedVillageEntitiesWindow
			+ ", maxBlockWindow="
			+ maxRenderedBlockEntitiesWindow
			+ "/village="
			+ maxRenderedVillageBlockEntitiesWindow
			+ ", maxCullWindow="
			+ maxCulledEntitiesWindow
			+ "/village="
			+ maxCulledVillageEntitiesWindow
			+ ", maxBlockCullWindow="
			+ maxCulledBlockEntitiesWindow
			+ "/village="
			+ maxCulledVillageBlockEntitiesWindow
			+ ", pressureActive="
			+ villagePressureActiveTicks
			+ "t/"
			+ villagePressureActivations
			+ "x"
			+ ", sortLast="
			+ formatMs(lastEntitySortNanos)
			+ "ms/"
			+ lastEntitySortCount
			+ "e/"
			+ lastEntitySortGroups
			+ "g, sortAvg="
			+ formatMs(entitySortCalls <= 0L ? 0L : entitySortNanos / entitySortCalls)
			+ "ms, sortMax="
			+ formatMs(maxEntitySortNanos)
			+ "ms, entityIdCache="
			+ entityIdCacheHits
			+ "/"
			+ entityIdCacheMisses
			+ ", blockEntityIdCache="
			+ blockEntityIdCacheHits
			+ "/"
			+ blockEntityIdCacheMisses
			+ "]";
	}

	private static void updateVillagePressure(
		int totalEntities,
		int villageEntities,
		long windowEntities,
		long windowVillageEntities,
		long windowBlockEntities,
		long windowVillageBlockEntities
	) {
		if (!Boolean.parseBoolean(System.getProperty(VILLAGE_PRESSURE_ENABLED_PROPERTY, "true"))) {
			villagePressureActive = false;
			villagePressureTicks = 0;
			return;
		}

		int villageThreshold = readInt(VILLAGE_ENTITY_THRESHOLD_PROPERTY, 8, 1, 256);
		int totalThreshold = readInt(TOTAL_ENTITY_THRESHOLD_PROPERTY, 96, 8, 512);
		int renderedVillageEntityThreshold = readInt(RENDERED_VILLAGE_ENTITY_THRESHOLD_PROPERTY, 96, 1, 4096);
		int renderedVillageBlockEntityThreshold = readInt(RENDERED_VILLAGE_BLOCK_ENTITY_THRESHOLD_PROPERTY, 96, 1, 4096);
		int totalRenderedBlockEntityThreshold = readInt(TOTAL_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY, 512, 16, 8192);
		int totalRenderedEntityThreshold = readInt(TOTAL_RENDERED_ENTITY_THRESHOLD_PROPERTY, totalThreshold * 4, 16, 8192);
		int pressureHoldTicks = readInt(VILLAGE_PRESSURE_HOLD_TICKS_PROPERTY, 100, SAMPLE_INTERVAL_TICKS, 600);
		double minRenderShare = readDouble(VILLAGE_PRESSURE_MIN_RENDER_SHARE_PROPERTY, 0.12D, 0.01D, 1.0D);
		long minRenderSignals = readInt(VILLAGE_PRESSURE_MIN_RENDER_SIGNALS_PROPERTY, 24, 1, 4096);
		long totalRenderSignals = Math.max(0L, windowEntities + windowBlockEntities);
		long villageRenderSignals = Math.max(0L, windowVillageEntities + windowVillageBlockEntities);
		double villageRenderShare = totalRenderSignals > 0L ? villageRenderSignals / (double) totalRenderSignals : 0.0D;
		boolean directVillageLoad = villageEntities >= villageThreshold
			|| windowVillageEntities >= renderedVillageEntityThreshold
			|| windowVillageBlockEntities >= renderedVillageBlockEntityThreshold;
		boolean mixedVillageEntityLoad = villageEntities > 0
			&& totalEntities >= totalThreshold
			&& windowEntities >= totalRenderedEntityThreshold
			&& villageRenderSignals >= minRenderSignals;
		boolean mixedVillageBlockEntityLoad = windowVillageBlockEntities >= Math.max(8L, minRenderSignals / 2L)
			&& windowBlockEntities >= totalRenderedBlockEntityThreshold
			&& villageRenderShare >= minRenderShare;
		boolean mixedVillageEntityWindowLoad = windowVillageEntities >= Math.max(8L, minRenderSignals / 3L)
			&& windowEntities >= totalRenderedEntityThreshold
			&& villageRenderShare >= Math.max(0.05D, minRenderShare * 0.5D);
		boolean pressureCandidate = directVillageLoad
			|| mixedVillageEntityLoad
			|| mixedVillageBlockEntityLoad
			|| mixedVillageEntityWindowLoad;

		boolean wasActive = villagePressureActive;
		if (pressureCandidate) {
			villagePressureTicks = Math.max(villagePressureTicks, pressureHoldTicks);
		} else {
			villagePressureTicks = Math.max(0, villagePressureTicks - SAMPLE_INTERVAL_TICKS);
		}
		villagePressureActive = villagePressureTicks > 0;
		if (villagePressureActive) {
			villagePressureActiveTicks += SAMPLE_INTERVAL_TICKS;
			if (!wasActive) {
				villagePressureActivations++;
			}
		}
	}

	private static void updateHordePressure(int totalEntities, long windowEntities, long windowBlockEntities) {
		if (!Boolean.parseBoolean(System.getProperty(HORDE_PRESSURE_ENABLED_PROPERTY, "true"))) {
			hordePressureActive = false;
			hordePressureTicks = 0;
			lastAnimationLodTier = 0;
			lastBlockEntityFrameBudget = 0;
			return;
		}

		int totalThreshold = readInt(HORDE_TOTAL_ENTITY_THRESHOLD_PROPERTY, 192, 32, 4096);
		int renderedEntityThreshold = readInt(HORDE_RENDERED_ENTITY_THRESHOLD_PROPERTY, 4096, 256, 50000);
		int renderedBlockEntityThreshold = readInt(HORDE_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY, 1536, 128, 50000);
		int pressureHoldTicks = readInt(HORDE_PRESSURE_HOLD_TICKS_PROPERTY, 80, SAMPLE_INTERVAL_TICKS, 600);
		boolean pressureCandidate = totalEntities >= totalThreshold
			|| windowEntities >= renderedEntityThreshold
			|| windowBlockEntities >= renderedBlockEntityThreshold;

		boolean wasActive = hordePressureActive;
		if (pressureCandidate) {
			hordePressureTicks = Math.max(hordePressureTicks, pressureHoldTicks);
		} else {
			hordePressureTicks = Math.max(0, hordePressureTicks - SAMPLE_INTERVAL_TICKS);
		}
		hordePressureActive = hordePressureTicks > 0;
		if (hordePressureActive) {
			hordePressureActiveTicks += SAMPLE_INTERVAL_TICKS;
			if (!wasActive) {
				hordePressureActivations++;
			}
		}

		int tier = 0;
		if (hordePressureActive) {
			double entityLoad = Math.max(
				totalEntities / (double) Math.max(1, totalThreshold),
				windowEntities / (double) Math.max(1, renderedEntityThreshold)
			);
			double blockEntityLoad = windowBlockEntities / (double) Math.max(1, renderedBlockEntityThreshold);
			double load = Math.max(entityLoad, blockEntityLoad);
			if (load >= 2.5D) {
				tier = 3;
			} else if (load >= 1.5D || villagePressureActive) {
				tier = 2;
			} else {
				tier = 1;
			}
		}
		lastAnimationLodTier = Boolean.parseBoolean(System.getProperty(HORDE_ANIMATION_LOD_ENABLED_PROPERTY, "true")) ? tier : 0;
		int steadyBudget = readInt(HORDE_BLOCK_ENTITY_STEADY_BUDGET_PROPERTY, 1500, 128, 20000);
		int severeBudget = readInt(HORDE_BLOCK_ENTITY_SEVERE_BUDGET_PROPERTY, 600, 64, 10000);
		lastBlockEntityFrameBudget = tier >= 2 || villagePressureActive ? severeBudget : steadyBudget;
	}

	private static void resetRenderWindow() {
		renderedEntitiesWindow = 0L;
		renderedVillageEntitiesWindow = 0L;
		renderedBlockEntitiesWindow = 0L;
		renderedVillageBlockEntitiesWindow = 0L;
		culledEntitiesWindow = 0L;
		culledVillageEntitiesWindow = 0L;
		culledBlockEntitiesWindow = 0L;
		culledVillageBlockEntitiesWindow = 0L;
	}

	private static boolean isVillageEntityType(EntityType<?> type) {
		Boolean cached = VILLAGE_ENTITY_TYPES.get(type);
		if (cached != null) {
			return cached;
		}

		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		String path = key != null ? key.getPath() : "";
		boolean village = path.contains("villager")
			|| path.contains("golem")
			|| path.equals("cat")
			|| path.equals("wandering_trader")
			|| path.equals("trader_llama");
		VILLAGE_ENTITY_TYPES.put(type, village);
		return village;
	}

	private static boolean isVillageBlockEntityType(BlockEntityType<?> type) {
		Boolean cached = VILLAGE_BLOCK_ENTITY_TYPES.get(type);
		if (cached != null) {
			return cached;
		}

		ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
		String path = key != null ? key.getPath() : "";
		boolean village = path.equals("bell")
			|| path.equals("bed")
			|| path.equals("lectern");
		VILLAGE_BLOCK_ENTITY_TYPES.put(type, village);
		return village;
	}

	private static String formatMs(long nanos) {
		return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
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
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
