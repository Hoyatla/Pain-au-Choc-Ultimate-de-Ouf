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
	private static long entityIdCacheHits;
	private static long entityIdCacheMisses;
	private static long blockEntityIdCacheHits;
	private static long blockEntityIdCacheMisses;
	private static volatile boolean villagePressureActive;
	private static int villagePressureTicks;

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
		entityIdCacheHits = 0L;
		entityIdCacheMisses = 0L;
		blockEntityIdCacheHits = 0L;
		blockEntityIdCacheMisses = 0L;
		villagePressureActive = false;
		villagePressureTicks = 0;
		diagnosticsEnabled = true;
	}

	public static void onClientTick(Minecraft minecraft) {
		diagnosticsEnabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
		if (!diagnosticsEnabled || minecraft == null || minecraft.level == null) {
			villagePressureActive = false;
			villagePressureTicks = 0;
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
		updateVillagePressure(totalEntities, villageEntities);
	}

	public static void recordEntityRender(Entity entity) {
		if (!diagnosticsEnabled || entity == null) {
			return;
		}

		renderedEntities++;
		if (isVillageEntityType(entity.getType())) {
			renderedVillageEntities++;
		}
	}

	public static void recordBlockEntityRender(BlockEntity blockEntity) {
		if (!diagnosticsEnabled || blockEntity == null) {
			return;
		}

		renderedBlockEntities++;
		if (isVillageBlockEntityType(blockEntity.getType())) {
			renderedVillageBlockEntities++;
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

	public static String describeState() {
		return "villagePerf[clientEntities="
			+ lastClientEntityCount
			+ ", villageEntities="
			+ lastClientVillageEntityCount
			+ ", pressure="
			+ (isVillagePressureActive() ? "on" : "off")
			+ ", renderedEntities="
			+ renderedEntities
			+ "/village="
			+ renderedVillageEntities
			+ ", renderedBlockEntities="
			+ renderedBlockEntities
			+ "/village="
			+ renderedVillageBlockEntities
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

	private static void updateVillagePressure(int totalEntities, int villageEntities) {
		if (!Boolean.parseBoolean(System.getProperty(VILLAGE_PRESSURE_ENABLED_PROPERTY, "true"))) {
			villagePressureActive = false;
			villagePressureTicks = 0;
			return;
		}

		int villageThreshold = readInt(VILLAGE_ENTITY_THRESHOLD_PROPERTY, 8, 1, 256);
		int totalThreshold = readInt(TOTAL_ENTITY_THRESHOLD_PROPERTY, 96, 8, 512);
		boolean pressureCandidate = villageEntities >= villageThreshold
			|| (villageEntities > 0 && totalEntities >= totalThreshold);

		if (pressureCandidate) {
			villagePressureTicks = Math.min(120, villagePressureTicks + SAMPLE_INTERVAL_TICKS);
		} else {
			villagePressureTicks = Math.max(0, villagePressureTicks - SAMPLE_INTERVAL_TICKS);
		}
		villagePressureActive = villagePressureTicks >= SAMPLE_INTERVAL_TICKS;
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
			|| path.equals("sign")
			|| path.equals("hanging_sign")
			|| path.equals("chest")
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
}
