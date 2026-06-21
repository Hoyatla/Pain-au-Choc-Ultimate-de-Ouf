package fr.hoyatla.pauc.lod;

import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Locale;

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
	private static final String SCENE_PRESSURE_ENABLED_PROPERTY = "pauc.lod.scenePressureBudget";
	private static final String SCENE_PRESSURE_RENDERED_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.scenePressureRenderedEntityThreshold";
	private static final String SCENE_PRESSURE_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.scenePressureRenderedBlockEntityThreshold";
	private static final String SCENE_PRESSURE_MOVING_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.scenePressureMovingEntityThreshold";
	private static final String SCENE_PRESSURE_MOVING_BLOCK_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.scenePressureMovingBlockEntityThreshold";
	private static final String SCENE_PRESSURE_SEVERE_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.scenePressureSevereRenderedEntityThreshold";
	private static final String SCENE_PRESSURE_SEVERE_BLOCK_ENTITY_THRESHOLD_PROPERTY = "pauc.lod.scenePressureSevereRenderedBlockEntityThreshold";
	private static final String SCENE_PRESSURE_SORT_MS_THRESHOLD_PROPERTY = "pauc.lod.scenePressureEntitySortMsThreshold";
	private static final String SCENE_PRESSURE_MOVEMENT_SPEED_THRESHOLD_PROPERTY = "pauc.lod.scenePressureMovementSpeedThreshold";
	private static final String SCENE_PRESSURE_HOLD_TICKS_PROPERTY = "pauc.lod.scenePressureHoldTicks";
	private static final String SCENE_PRESSURE_PROJECTION_MIN_TICKS_PROPERTY = "pauc.lod.scenePressureProjectionMinTicks";
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	private static final Reference2ByteOpenHashMap<EntityType<?>> VILLAGE_ENTITY_TYPES = new Reference2ByteOpenHashMap<>();
	private static final Reference2ByteOpenHashMap<BlockEntityType<?>> VILLAGE_BLOCK_ENTITY_TYPES = new Reference2ByteOpenHashMap<>();
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
	private static volatile boolean scenePressureActive;
	private static int scenePressureTicks;
	private static long scenePressureActiveTicks;
	private static long scenePressureActivations;
	private static int lastScenePressureTier;
	private static double lastPlayerHorizontalSpeed;
	private static boolean lastPlayerGrounded;
	private static String lastScenePressureReason = "off";
	private static int lastAnimationLodTier;
	private static int lastBlockEntityFrameBudget;

	static {
		VILLAGE_ENTITY_TYPES.defaultReturnValue((byte) -1);
		VILLAGE_BLOCK_ENTITY_TYPES.defaultReturnValue((byte) -1);
	}

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
		scenePressureActive = false;
		scenePressureTicks = 0;
		scenePressureActiveTicks = 0L;
		scenePressureActivations = 0L;
		lastScenePressureTier = 0;
		lastPlayerHorizontalSpeed = 0.0D;
		lastPlayerGrounded = false;
		lastScenePressureReason = "off";
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
			scenePressureActive = false;
			scenePressureTicks = 0;
			lastScenePressureTier = 0;
			lastPlayerHorizontalSpeed = 0.0D;
			lastPlayerGrounded = false;
			lastScenePressureReason = "off";
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
		updateScenePressure(minecraft, windowEntities, windowBlockEntities);
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

	public static boolean isScenePressureActive() {
		return diagnosticsEnabled
			&& Boolean.parseBoolean(System.getProperty(SCENE_PRESSURE_ENABLED_PROPERTY, "true"))
			&& scenePressureActive;
	}

	public static boolean isProjectedScenePressureActive() {
		return projectedScenePressureTier() > 0;
	}

	public static int scenePressureTier() {
		return Math.max(0, Math.min(3, lastScenePressureTier));
	}

	public static double scenePressureScale() {
		return scenePressureScaleForTier(scenePressureTier());
	}

	public static int projectedScenePressureTier() {
		if (!diagnosticsEnabled || !Boolean.parseBoolean(System.getProperty(SCENE_PRESSURE_ENABLED_PROPERTY, "true"))) {
			return 0;
		}

		ScenePressureState projectedState = evaluateScenePressure(
			Minecraft.getInstance(),
			projectedWindowValue(renderedEntitiesWindow),
			projectedWindowValue(renderedBlockEntitiesWindow)
		);
		int tier = projectedState.tier();
		if (scenePressureActive) {
			tier = Math.max(tier, lastScenePressureTier);
		}
		return Math.max(0, Math.min(3, tier));
	}

	public static double projectedScenePressureScale() {
		return scenePressureScaleForTier(projectedScenePressureTier());
	}

	public static String projectedScenePressureReason() {
		if (!diagnosticsEnabled || !Boolean.parseBoolean(System.getProperty(SCENE_PRESSURE_ENABLED_PROPERTY, "true"))) {
			return "off";
		}

		ScenePressureState projectedState = evaluateScenePressure(
			Minecraft.getInstance(),
			projectedWindowValue(renderedEntitiesWindow),
			projectedWindowValue(renderedBlockEntitiesWindow)
		);
		if (scenePressureActive && lastScenePressureTier >= projectedState.tier()) {
			return lastScenePressureReason;
		}
		return projectedState.reason();
	}

	public static double lastPlayerHorizontalSpeed() {
		return Math.max(0.0D, lastPlayerHorizontalSpeed);
	}

	public static boolean lastPlayerGrounded() {
		return lastPlayerGrounded;
	}

	public static int animationLodTier() {
		if (!diagnosticsEnabled || !Boolean.parseBoolean(System.getProperty(HORDE_ANIMATION_LOD_ENABLED_PROPERTY, "true"))) {
			return 0;
		}
		return Math.max(0, Math.min(3, lastAnimationLodTier));
	}

	public static int projectedAnimationLodTier() {
		if (!diagnosticsEnabled || !Boolean.parseBoolean(System.getProperty(HORDE_ANIMATION_LOD_ENABLED_PROPERTY, "true"))) {
			return 0;
		}

		HordePressureState projectedState = evaluateHordePressure(
			lastClientEntityCount,
			projectedWindowValue(renderedEntitiesWindow),
			projectedWindowValue(renderedBlockEntitiesWindow),
			projectedScenePressureTier()
		);
		int tier = projectedState.tier();
		if (hordePressureActive) {
			tier = Math.max(tier, lastAnimationLodTier);
		}
		return Math.max(0, Math.min(3, tier));
	}

	public static int blockEntityFrameBudget() {
		return Math.max(0, lastBlockEntityFrameBudget);
	}

	public static int projectedBlockEntityFrameBudget() {
		if (!diagnosticsEnabled) {
			return 0;
		}

		int steadyBudget = readInt(HORDE_BLOCK_ENTITY_STEADY_BUDGET_PROPERTY, 1500, 128, 20000);
		int severeBudget = readInt(HORDE_BLOCK_ENTITY_SEVERE_BUDGET_PROPERTY, 600, 64, 10000);
		int projectedTier = projectedAnimationLodTier();
		int projectedSceneTier = projectedScenePressureTier();
		if (projectedTier >= 2 || villagePressureActive || projectedSceneTier >= 2) {
			return severeBudget;
		}
		if (projectedTier >= 1 || projectedSceneTier >= 1) {
			return Math.max(severeBudget + 128, (int) Math.round(steadyBudget * 0.80D));
		}
		return steadyBudget;
	}

	public static long projectedRenderedEntitiesWindow() {
		return projectedWindowValue(renderedEntitiesWindow);
	}

	public static long projectedRenderedBlockEntitiesWindow() {
		return projectedWindowValue(renderedBlockEntitiesWindow);
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
			+ ", scene="
			+ (isScenePressureActive() ? "on" : "off")
			+ "/tier="
			+ scenePressureTier()
			+ "/"
			+ lastScenePressureReason
			+ ", sceneProjected="
			+ projectedScenePressureTier()
			+ "/"
			+ projectedScenePressureReason()
			+ "/speed="
			+ String.format(Locale.ROOT, "%.2f", lastPlayerHorizontalSpeed)
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
			+ "/proj="
			+ projectedRenderedEntitiesWindow()
			+ "/village="
			+ lastRenderedVillageEntitiesWindow
			+ ", blockWindow="
			+ lastRenderedBlockEntitiesWindow
			+ "/proj="
			+ projectedRenderedBlockEntitiesWindow()
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
			+ ", sceneActive="
			+ scenePressureActiveTicks
			+ "t/"
			+ scenePressureActivations
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

		int pressureHoldTicks = readInt(HORDE_PRESSURE_HOLD_TICKS_PROPERTY, 80, SAMPLE_INTERVAL_TICKS, 600);
		HordePressureState pressureState = evaluateHordePressure(totalEntities, windowEntities, windowBlockEntities, lastScenePressureTier);
		boolean pressureCandidate = pressureState.activeCandidate();

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
		lastAnimationLodTier = Boolean.parseBoolean(System.getProperty(HORDE_ANIMATION_LOD_ENABLED_PROPERTY, "true"))
			? pressureState.tier()
			: 0;
		lastBlockEntityFrameBudget = pressureState.blockEntityFrameBudget();
	}

	private static void updateScenePressure(Minecraft minecraft, long windowEntities, long windowBlockEntities) {
		if (!Boolean.parseBoolean(System.getProperty(SCENE_PRESSURE_ENABLED_PROPERTY, "true"))) {
			scenePressureActive = false;
			scenePressureTicks = 0;
			lastScenePressureTier = 0;
			lastScenePressureReason = "off";
			return;
		}

		ScenePressureState pressureState = evaluateScenePressure(minecraft, windowEntities, windowBlockEntities);
		int pressureHoldTicks = readInt(SCENE_PRESSURE_HOLD_TICKS_PROPERTY, 80, SAMPLE_INTERVAL_TICKS, 600);
		boolean pressureCandidate = pressureState.activeCandidate();
		lastPlayerHorizontalSpeed = pressureState.playerHorizontalSpeed();
		lastPlayerGrounded = pressureState.playerGrounded();

		boolean wasActive = scenePressureActive;
		if (pressureCandidate) {
			scenePressureTicks = Math.max(scenePressureTicks, pressureHoldTicks);
		} else {
			scenePressureTicks = Math.max(0, scenePressureTicks - SAMPLE_INTERVAL_TICKS);
		}
		scenePressureActive = scenePressureTicks > 0;
		if (scenePressureActive) {
			scenePressureActiveTicks += SAMPLE_INTERVAL_TICKS;
			if (!wasActive) {
				scenePressureActivations++;
			}
		}
		lastScenePressureTier = scenePressureActive ? pressureState.tier() : 0;
		lastScenePressureReason = scenePressureActive ? pressureState.reason() : "off";
	}

	private static double scenePressureScaleForTier(int tier) {
		return switch (Math.max(0, Math.min(3, tier))) {
			case 3 -> 0.58D;
			case 2 -> 0.72D;
			case 1 -> 0.86D;
			default -> 1.0D;
		};
	}

	private static long projectedWindowValue(long value) {
		if (value <= 0L) {
			return 0L;
		}

		int minTicks = readInt(SCENE_PRESSURE_PROJECTION_MIN_TICKS_PROPERTY, 5, 1, SAMPLE_INTERVAL_TICKS);
		int observedTicks = Math.max(minTicks, sampleTicks);
		double factor = SAMPLE_INTERVAL_TICKS / (double) observedTicks;
		return Math.max(value, Math.round(value * factor));
	}

	private static HordePressureState evaluateHordePressure(int totalEntities, long windowEntities, long windowBlockEntities, int sceneTier) {
		int totalThreshold = readInt(HORDE_TOTAL_ENTITY_THRESHOLD_PROPERTY, 192, 32, 4096);
		int renderedEntityThreshold = readInt(HORDE_RENDERED_ENTITY_THRESHOLD_PROPERTY, 4096, 256, 50000);
		int renderedBlockEntityThreshold = readInt(HORDE_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY, 1536, 128, 50000);
		boolean pressureCandidate = totalEntities >= totalThreshold
			|| windowEntities >= renderedEntityThreshold
			|| windowBlockEntities >= renderedBlockEntityThreshold;
		int tier = 0;
		if (pressureCandidate) {
			double entityLoad = Math.max(
				totalEntities / (double) Math.max(1, totalThreshold),
				windowEntities / (double) Math.max(1, renderedEntityThreshold)
			);
			double blockEntityLoad = windowBlockEntities / (double) Math.max(1, renderedBlockEntityThreshold);
			double load = Math.max(entityLoad, blockEntityLoad);
			if (sceneTier >= 3) {
				load = Math.max(load, 1.65D);
			} else if (sceneTier >= 2) {
				load = Math.max(load, 1.35D);
			}
			if (load >= 2.5D) {
				tier = 3;
			} else if (load >= 1.5D || villagePressureActive || sceneTier >= 2) {
				tier = 2;
			} else {
				tier = 1;
			}
		}

		int steadyBudget = readInt(HORDE_BLOCK_ENTITY_STEADY_BUDGET_PROPERTY, 1500, 128, 20000);
		int severeBudget = readInt(HORDE_BLOCK_ENTITY_SEVERE_BUDGET_PROPERTY, 600, 64, 10000);
		int budget = tier >= 2 || villagePressureActive || sceneTier >= 2
			? severeBudget
			: steadyBudget;
		if (tier == 1 || sceneTier == 1) {
			budget = Math.min(budget, Math.max(severeBudget + 128, (int) Math.round(steadyBudget * 0.80D)));
		}
		return new HordePressureState(pressureCandidate, tier, Math.max(0, budget));
	}

	private static ScenePressureState evaluateScenePressure(Minecraft minecraft, long windowEntities, long windowBlockEntities) {
		LocalPlayer player = minecraft != null ? minecraft.player : null;
		double playerHorizontalSpeed = player != null ? player.getDeltaMovement().horizontalDistance() : 0.0D;
		boolean playerGrounded = player != null && player.onGround();
		double sortMs = lastEntitySortNanos / 1_000_000.0D;
		int entityThreshold = readInt(SCENE_PRESSURE_RENDERED_ENTITY_THRESHOLD_PROPERTY, 160, 16, 8192);
		int blockEntityThreshold = readInt(SCENE_PRESSURE_RENDERED_BLOCK_ENTITY_THRESHOLD_PROPERTY, 256, 16, 8192);
		int severeEntityThreshold = readInt(SCENE_PRESSURE_SEVERE_ENTITY_THRESHOLD_PROPERTY, entityThreshold * 2, entityThreshold, 32768);
		int severeBlockEntityThreshold = readInt(SCENE_PRESSURE_SEVERE_BLOCK_ENTITY_THRESHOLD_PROPERTY, blockEntityThreshold * 2, blockEntityThreshold, 32768);
		int movingEntityThreshold = readInt(SCENE_PRESSURE_MOVING_ENTITY_THRESHOLD_PROPERTY, Math.max(48, entityThreshold / 2), 8, 8192);
		int movingBlockEntityThreshold = readInt(SCENE_PRESSURE_MOVING_BLOCK_ENTITY_THRESHOLD_PROPERTY, Math.max(64, blockEntityThreshold / 2), 8, 8192);
		double movementSpeedThreshold = readDouble(SCENE_PRESSURE_MOVEMENT_SPEED_THRESHOLD_PROPERTY, 0.10D, 0.01D, 2.0D);
		double sortThresholdMs = readDouble(SCENE_PRESSURE_SORT_MS_THRESHOLD_PROPERTY, 1.20D, 0.10D, 30.0D);
		boolean movingGrounded = playerGrounded && playerHorizontalSpeed >= movementSpeedThreshold;
		boolean pressureCandidate = windowEntities >= entityThreshold
			|| windowBlockEntities >= blockEntityThreshold
			|| sortMs >= sortThresholdMs
			|| (movingGrounded && (windowEntities >= movingEntityThreshold || windowBlockEntities >= movingBlockEntityThreshold));
		if (!pressureCandidate) {
			return new ScenePressureState(false, 0, "off", playerHorizontalSpeed, playerGrounded);
		}

		double entityLoad = windowEntities / (double) Math.max(1, entityThreshold);
		double blockEntityLoad = windowBlockEntities / (double) Math.max(1, blockEntityThreshold);
		double severeEntityLoad = windowEntities / (double) Math.max(1, severeEntityThreshold);
		double severeBlockEntityLoad = windowBlockEntities / (double) Math.max(1, severeBlockEntityThreshold);
		double sortLoad = sortMs / Math.max(0.10D, sortThresholdMs);
		double movementEntityLoad = movingGrounded ? windowEntities / (double) Math.max(1, movingEntityThreshold) : 0.0D;
		double movementBlockEntityLoad = movingGrounded ? windowBlockEntities / (double) Math.max(1, movingBlockEntityThreshold) : 0.0D;
		double load = Math.max(Math.max(entityLoad, blockEntityLoad), Math.max(sortLoad, Math.max(movementEntityLoad, movementBlockEntityLoad)));
		int tier;
		if (severeEntityLoad >= 1.0D || severeBlockEntityLoad >= 1.0D || load >= 2.25D) {
			tier = 3;
		} else if (load >= 1.40D) {
			tier = 2;
		} else {
			tier = 1;
		}

		String reason;
		if (sortLoad >= Math.max(Math.max(entityLoad, blockEntityLoad), 1.0D)) {
			reason = "entity-sort";
		} else if (movingGrounded && Math.max(movementEntityLoad, movementBlockEntityLoad) >= Math.max(entityLoad, blockEntityLoad)) {
			reason = "ground-motion";
		} else if (blockEntityLoad >= entityLoad) {
			reason = "block-entities";
		} else {
			reason = "entities";
		}
		return new ScenePressureState(true, tier, reason, playerHorizontalSpeed, playerGrounded);
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
		byte cached = VILLAGE_ENTITY_TYPES.getByte(type);
		if (cached >= 0) {
			return cached != 0;
		}

		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		String path = key != null ? key.getPath() : "";
		boolean village = path.contains("villager")
			|| path.contains("golem")
			|| path.equals("cat")
			|| path.equals("wandering_trader")
			|| path.equals("trader_llama");
		VILLAGE_ENTITY_TYPES.put(type, village ? (byte) 1 : (byte) 0);
		return village;
	}

	private static boolean isVillageBlockEntityType(BlockEntityType<?> type) {
		byte cached = VILLAGE_BLOCK_ENTITY_TYPES.getByte(type);
		if (cached >= 0) {
			return cached != 0;
		}

		ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
		String path = key != null ? key.getPath() : "";
		boolean village = path.equals("bell")
			|| path.equals("bed")
			|| path.equals("lectern");
		VILLAGE_BLOCK_ENTITY_TYPES.put(type, village ? (byte) 1 : (byte) 0);
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

	private record ScenePressureState(
		boolean activeCandidate,
		int tier,
		String reason,
		double playerHorizontalSpeed,
		boolean playerGrounded
	) {
	}

	private record HordePressureState(boolean activeCandidate, int tier, int blockEntityFrameBudget) {
	}
}
