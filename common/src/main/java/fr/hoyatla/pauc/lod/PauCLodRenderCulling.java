package fr.hoyatla.pauc.lod;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCLodRenderCulling {
	private static final String ENABLED_PROPERTY = "pauc.lod.renderCulling";
	private static final String VANILLA_TERRAIN_CULLING_PROPERTY = "pauc.lod.cull.vanillaTerrain";
	private static final String VANILLA_TERRAIN_MARGIN_CHUNKS_PROPERTY = "pauc.lod.cull.vanillaTerrainMarginChunks";
	private static final String VANILLA_FOLIAGE_CULLING_PROPERTY = "pauc.lod.cull.vanillaFoliage";
	private static final String VANILLA_FOLIAGE_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.vanillaFoliageDistanceChunks";
	private static final String LOD_GENERIC_UNDER_VANILLA_CULLING_PROPERTY = "pauc.lod.cull.genericUnderVanilla";
	private static final String LOD_GENERIC_UNDER_VANILLA_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.genericUnderVanillaExtraChunks";
	private static final String LOD_GENERIC_UNDER_VANILLA_RELEASE_HEIGHT_PROPERTY = "pauc.lod.cull.genericUnderVanillaReleaseHeightBlocks";
	private static final String LOD_GENERIC_UNDER_VANILLA_HOLD_MS_PROPERTY = "pauc.lod.cull.genericUnderVanillaHoldMs";
	private static final String LOD_GENERIC_UNDER_VANILLA_CONFIG_CACHE_MS_PROPERTY = "pauc.lod.cull.genericUnderVanillaConfigCacheMs";
	private static final String VANILLA_AQUATIC_PLANT_CULLING_PROPERTY = "pauc.lod.cull.aquaticPlants";
	private static final String VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.aquaticPlantDistanceChunks";
	private static final String VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.underwaterPlantDistanceChunks";
	private static final String ENTITY_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.entityExtraChunks";
	private static final String BLOCK_ENTITY_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.blockEntityExtraChunks";
	private static final String VILLAGE_PRESSURE_CULLING_PROPERTY = "pauc.lod.cull.villagePressure";
	private static final String VILLAGE_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY = "pauc.lod.cull.villageEntityPressureDistanceBlocks";
	private static final String VILLAGE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY = "pauc.lod.cull.villageBlockEntityPressureDistanceBlocks";
	private static final String PRESSURE_BLOCK_ENTITY_CULLING_PROPERTY = "pauc.lod.cull.pressureBlockEntities";
	private static final String PRESSURE_BLOCK_ENTITY_DISTANCE_BLOCKS_PROPERTY = "pauc.lod.cull.pressureBlockEntityDistanceBlocks";
	private static final String HORDE_PRESSURE_CULLING_PROPERTY = "pauc.lod.cull.hordePressure";
	private static final String HORDE_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY = "pauc.lod.cull.hordeEntityPressureDistanceBlocks";
	private static final String HORDE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY = "pauc.lod.cull.hordeBlockEntityPressureDistanceBlocks";
	private static final String SCENE_PRESSURE_ENTITY_DISTANCE_TIER1_PROPERTY = "pauc.lod.cull.scenePressureEntityDistanceTier1Blocks";
	private static final String SCENE_PRESSURE_ENTITY_DISTANCE_TIER2_PROPERTY = "pauc.lod.cull.scenePressureEntityDistanceTier2Blocks";
	private static final String SCENE_PRESSURE_ENTITY_DISTANCE_TIER3_PROPERTY = "pauc.lod.cull.scenePressureEntityDistanceTier3Blocks";
	private static final String SCENE_PRESSURE_BLOCK_ENTITY_DISTANCE_TIER1_PROPERTY = "pauc.lod.cull.scenePressureBlockEntityDistanceTier1Blocks";
	private static final String SCENE_PRESSURE_BLOCK_ENTITY_DISTANCE_TIER2_PROPERTY = "pauc.lod.cull.scenePressureBlockEntityDistanceTier2Blocks";
	private static final String SCENE_PRESSURE_BLOCK_ENTITY_DISTANCE_TIER3_PROPERTY = "pauc.lod.cull.scenePressureBlockEntityDistanceTier3Blocks";
	private static final String SCENE_PRESSURE_FOLIAGE_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.scenePressureFoliageDistanceChunks";
	private static final String RUNTIME_VILLAGE_SEVERE_PRESSURE_PROPERTY = "pauc.runtime.villageSeverePressure";
	private static final String PARTICLE_DISTANCE_BLOCKS_PROPERTY = "pauc.lod.cull.particleDistanceBlocks";
	private static final String LOD_CLOUD_CELL_WIDTH_BLOCKS_PROPERTY = "pauc.lod.cloudCellWidthBlocks";
	private static final String LOD_CLOUD_THICKNESS_BLOCKS_PROPERTY = "pauc.lod.cloudThicknessBlocks";
	private static final String LOD_CLOUD_INNER_CULL_INSTANCES_PROPERTY = "pauc.lod.cloudInnerCullInstances";
	private static final String LOD_CLOUD_OUTER_ACTIVE_INSTANCES_PROPERTY = "pauc.lod.cloudOuterActiveInstances";
	private static final String LOD_CLOUD_CULL_VANILLA_OVERLAP_PROPERTY = "pauc.lod.cloudCullVanillaOverlap";
	private static final String LOD_CLOUD_SAFE_MODE_PROPERTY = "pauc.lod.cloudSafeMode";
	private static final String LOD_CLOUD_FORCE_PROPERTY = "pauc.lod.forceDistantClouds";
	private static final String LOD_CLOUD_SPEED_BLOCKS_PER_SECOND_PROPERTY = "pauc.lod.cloudSpeedBlocksPerSecond";
	private static final String LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_PROPERTY = "pauc.lod.cloudHeightOffsetFromWorldTopBlocks";
	private static final String LOD_CLOUD_LOW_NEAR_CULL_HEIGHT_MARGIN_PROPERTY = "pauc.lod.cloudLowNearCullHeightMarginBlocks";
	private static final String LOD_CLOUD_LOW_NEAR_CULL_RING_PROPERTY = "pauc.lod.cloudLowNearCullRing";
	private static final int DEFAULT_VANILLA_TERRAIN_MARGIN_CHUNKS = 1;
	private static final int DEFAULT_VANILLA_FOLIAGE_DISTANCE_CHUNKS = 7;
	private static final int DEFAULT_LOD_GENERIC_UNDER_VANILLA_EXTRA_CHUNKS = 1;
	private static final int DEFAULT_LOD_GENERIC_UNDER_VANILLA_RELEASE_HEIGHT_BLOCKS = 36;
	private static final int DEFAULT_LOD_GENERIC_UNDER_VANILLA_HOLD_MS = 900;
	private static final int DEFAULT_VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS = 3;
	private static final int DEFAULT_VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS = 5;
	private static final int DEFAULT_ENTITY_EXTRA_CHUNKS = 0;
	private static final int DEFAULT_BLOCK_ENTITY_EXTRA_CHUNKS = 0;
	private static final int DEFAULT_VILLAGE_ENTITY_PRESSURE_DISTANCE_BLOCKS = 96;
	private static final int DEFAULT_VILLAGE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS = 48;
	private static final int DEFAULT_PRESSURE_BLOCK_ENTITY_DISTANCE_BLOCKS = 96;
	private static final int DEFAULT_HORDE_ENTITY_PRESSURE_DISTANCE_BLOCKS = 128;
	private static final int DEFAULT_HORDE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS = 96;
	private static final int DEFAULT_PARTICLE_DISTANCE_BLOCKS = 80;
	private static final int DEFAULT_LOD_CLOUD_CELL_WIDTH_BLOCKS = 12;
	private static final int DEFAULT_LOD_CLOUD_THICKNESS_BLOCKS = 4;
	private static final int DEFAULT_LOD_CLOUD_INNER_CULL_INSTANCES = 3;
	private static final int DEFAULT_SHADER_FALLBACK_LOD_CLOUD_INNER_CULL_INSTANCES = 4;
	private static final int DEFAULT_LOD_CLOUD_OUTER_ACTIVE_INSTANCES = 5;
	private static final int MAX_LOD_CLOUD_OUTER_ACTIVE_INSTANCES = 24;
	private static final float DEFAULT_LOD_CLOUD_SPEED_BLOCKS_PER_SECOND = 0.6F;
	private static final int DEFAULT_LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_BLOCKS = -128;
	private static final int DEFAULT_LOD_CLOUD_LOW_NEAR_CULL_HEIGHT_MARGIN_BLOCKS = 24;
	private static final int DEFAULT_LOD_CLOUD_LOW_NEAR_CULL_RING = 4;
	private static final ConcurrentHashMap<Long, Long> GENERIC_LOD_CULL_HOLDS = new ConcurrentHashMap<>();
	private static volatile long lastGenericHoldPruneMs;
	private static volatile GenericLodCullConfig cachedGenericLodCullConfig;
	private static volatile long cachedGenericLodCullConfigAtMs;

	private PauCLodRenderCulling() {
	}

	public static ObjectArrayList<LevelRenderer.RenderChunkInfo> filterVanillaRenderChunks(ObjectArrayList<LevelRenderer.RenderChunkInfo> chunks) {
		if (!shouldCullVanillaTerrain() || chunks == null || chunks.isEmpty()) {
			return chunks;
		}

		ObjectArrayList<LevelRenderer.RenderChunkInfo> filteredChunks = null;
		for (int index = 0; index < chunks.size(); index++) {
			LevelRenderer.RenderChunkInfo chunkInfo = chunks.get(index);
			boolean cull = shouldCullVanillaChunk(chunkInfo);
			if (cull) {
				if (filteredChunks == null) {
					filteredChunks = new ObjectArrayList<>(chunks.size());
					for (int copyIndex = 0; copyIndex < index; copyIndex++) {
						filteredChunks.add(chunks.get(copyIndex));
					}
				}
			} else if (filteredChunks != null) {
				filteredChunks.add(chunkInfo);
			}
		}

		return filteredChunks != null ? filteredChunks : chunks;
	}

	public static boolean shouldCullEntity(Entity entity) {
		if (!active() || entity == null) {
			return false;
		}
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return false;
		}
		if (entity == minecraft.player || entity == minecraft.getCameraEntity()) {
			return false;
		}
		if (entity.isPassenger() || entity.isVehicle()) {
			return false;
		}

		double maxDistance = distanceFromVanillaChunks(DEFAULT_ENTITY_EXTRA_CHUNKS, ENTITY_EXTRA_CHUNKS_PROPERTY, 96.0D);
		if (shouldApplyVillagePressureCulling() && PauCVillagePerformanceDiagnostics.isVillageEntity(entity)) {
			maxDistance = Math.min(maxDistance, readInt(VILLAGE_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_VILLAGE_ENTITY_PRESSURE_DISTANCE_BLOCKS, 64, 256));
		} else if (shouldApplyHordePressureCulling()) {
			maxDistance = Math.min(maxDistance, readInt(HORDE_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_HORDE_ENTITY_PRESSURE_DISTANCE_BLOCKS, 96, 384));
		}
		maxDistance = Math.min(maxDistance, scenePressureEntityDistanceBlocks(maxDistance));
		Vec3 camera = cameraPosition(minecraft);
		return horizontalDistanceSqr(entity.getX(), entity.getZ(), camera.x, camera.z) > maxDistance * maxDistance;
	}

	/** Base horizontal max render distance (blocks) used for entity culling; consumers derive far-band thresholds from it. */
	public static double entityRenderMaxDistanceBlocks() {
		return distanceFromVanillaChunks(DEFAULT_ENTITY_EXTRA_CHUNKS, ENTITY_EXTRA_CHUNKS_PROPERTY, 96.0D);
	}

	/** Base horizontal max render distance (blocks) used for block-entity culling; consumers derive far-band thresholds. */
	public static double blockEntityRenderMaxDistanceBlocks() {
		return distanceFromVanillaChunks(DEFAULT_BLOCK_ENTITY_EXTRA_CHUNKS, BLOCK_ENTITY_EXTRA_CHUNKS_PROPERTY, 96.0D);
	}

	public static boolean shouldCullBlockEntity(BlockEntity blockEntity) {
		if (!active() || blockEntity == null) {
			return false;
		}
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || blockEntity.getLevel() != minecraft.level) {
			return false;
		}

		double maxDistance = distanceFromVanillaChunks(DEFAULT_BLOCK_ENTITY_EXTRA_CHUNKS, BLOCK_ENTITY_EXTRA_CHUNKS_PROPERTY, 96.0D);
		if (shouldApplyVillagePressureCulling()) {
			if (PauCVillagePerformanceDiagnostics.isVillageBlockEntity(blockEntity)) {
				maxDistance = Math.min(maxDistance, readInt(VILLAGE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_VILLAGE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS, 32, 256));
			} else if (readBoolean(PRESSURE_BLOCK_ENTITY_CULLING_PROPERTY, true)) {
				maxDistance = Math.min(maxDistance, readInt(PRESSURE_BLOCK_ENTITY_DISTANCE_BLOCKS_PROPERTY, DEFAULT_PRESSURE_BLOCK_ENTITY_DISTANCE_BLOCKS, 64, 256));
			}
		} else if (shouldApplyHordePressureCulling()) {
			maxDistance = Math.min(maxDistance, readInt(HORDE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_HORDE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS, 64, 256));
		}
		maxDistance = Math.min(maxDistance, scenePressureBlockEntityDistanceBlocks(maxDistance));
		Vec3 camera = cameraPosition(minecraft);
		BlockPos pos = blockEntity.getBlockPos();
		return horizontalDistanceSqr(pos.getX() + 0.5D, pos.getZ() + 0.5D, camera.x, camera.z) > maxDistance * maxDistance;
	}

	public static boolean shouldCullParticle(double x, double y, double z) {
		if (!active()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return false;
		}

		double maxDistance = readInt(PARTICLE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_PARTICLE_DISTANCE_BLOCKS, 32, 384);
		boolean particlePressure = PauCFrameSpikeAbsorber.isAbsorbing() || PauCVillagePerformanceDiagnostics.isScenePressureActive();
		if (readBoolean("pauc.lod.cull.particleAbsorbDistance", true) && particlePressure) {
			// Pull the particle render horizon in while absorbing a spike so per-frame iteration cost stays bounded.
			// Never below a near floor so close-range gameplay feedback is preserved.
			double minParticleDistance = readInt("pauc.lod.cull.particleAbsorbMinDistanceBlocks", 24, 8, 128);
			double pressureScale = particlePressureDistanceScale();
			maxDistance = Math.max(minParticleDistance, maxDistance * pressureScale);
		}
		Vec3 camera = cameraPosition(minecraft);
		double dx = x - camera.x;
		double dy = y - camera.y;
		double dz = z - camera.z;
		return dx * dx + dy * dy + dz * dz > maxDistance * maxDistance;
	}

	public static boolean shouldCullLodCloudInstance(int instanceOffsetX, int instanceOffsetZ) {
		if (!PauCLodClientSettings.isLodCloudsEnabled()) {
			return true;
		}

		boolean distantCloudRing = shouldUseDistantLodCloudRing();
		int innerCull = PauCLodShaderRuntime.shouldProtectLodCloudCenter()
			? 0
			: readInt(
				LOD_CLOUD_INNER_CULL_INSTANCES_PROPERTY,
				distantCloudRing ? DEFAULT_SHADER_FALLBACK_LOD_CLOUD_INNER_CULL_INSTANCES : DEFAULT_LOD_CLOUD_INNER_CULL_INSTANCES,
				0,
				6
			);
		boolean vanillaCloudOverlap = !PauCLodShaderRuntime.shouldProtectLodCloudCenter()
			&& readBoolean(LOD_CLOUD_CULL_VANILLA_OVERLAP_PROPERTY, true)
			&& areVanillaCloudsVisible();
		if ((vanillaCloudOverlap || distantCloudRing) && innerCull > 0 && Math.abs(instanceOffsetX) <= innerCull && Math.abs(instanceOffsetZ) <= innerCull) {
			return true;
		}

		int outerActive = Math.max(innerCull + 1, effectiveLodCloudOuterActiveInstances());
		int radialDistanceSqr = instanceOffsetX * instanceOffsetX + instanceOffsetZ * instanceOffsetZ;
		return radialDistanceSqr > outerActive * outerActive;
	}

	public static boolean shouldCullLowNearLodCloud(double minPosY, int instanceOffsetX, int instanceOffsetZ) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.gameRenderer == null) {
			return false;
		}
		if (PauCLodShaderRuntime.shouldProtectLodCloudCenter()) {
			return false;
		}

		int ring = readInt(LOD_CLOUD_LOW_NEAR_CULL_RING_PROPERTY, DEFAULT_LOD_CLOUD_LOW_NEAR_CULL_RING, 0, 8);
		if (Math.abs(instanceOffsetX) > ring || Math.abs(instanceOffsetZ) > ring) {
			return false;
		}

		double cameraY = cameraPosition(minecraft).y;
		double margin = readInt(LOD_CLOUD_LOW_NEAR_CULL_HEIGHT_MARGIN_PROPERTY, DEFAULT_LOD_CLOUD_LOW_NEAR_CULL_HEIGHT_MARGIN_BLOCKS, 0, 96);
		return minPosY <= cameraY + margin;
	}

	public static boolean shouldCullVanillaFoliage(BlockState state, BlockPos pos) {
		if (!active() || state == null || pos == null) {
			return false;
		}

		boolean leaves = state.is(BlockTags.LEAVES);
		boolean aquaticPlant = isAquaticPlant(state);
		if (!leaves && !aquaticPlant) {
			return false;
		}
		if (leaves && !readBoolean(VANILLA_FOLIAGE_CULLING_PROPERTY, true)) {
			return false;
		}
		if (aquaticPlant && !readBoolean(VANILLA_AQUATIC_PLANT_CULLING_PROPERTY, true)) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return false;
		}

		Vec3 camera = cameraPosition(minecraft);
		if (aquaticPlant) {
			boolean underwater = isCameraUnderWater(minecraft);
			int defaultDistance = underwater ? DEFAULT_VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS : DEFAULT_VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS;
			String distanceProperty = underwater ? VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS_PROPERTY : VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS_PROPERTY;
			double maxDistance = readInt(distanceProperty, defaultDistance, 2, 16) * 16.0D;
			return horizontalDistanceSqr(pos.getX() + 0.5D, pos.getZ() + 0.5D, camera.x, camera.z) > maxDistance * maxDistance;
		}

		PauCLodRange range = currentActiveRange();
		int defaultDistance = DEFAULT_VANILLA_FOLIAGE_DISTANCE_CHUNKS;
		if (range != null) {
			defaultDistance = Math.max(3, Math.min(range.vanillaRenderDistanceChunks(), range.vanillaRenderDistanceChunks() - 3));
		}

		double maxDistance = readInt(VANILLA_FOLIAGE_DISTANCE_CHUNKS_PROPERTY, defaultDistance, 3, 32) * 16.0D;
		if (PauCVillagePerformanceDiagnostics.isScenePressureActive()) {
			int pressuredDistanceChunks = readInt(
				SCENE_PRESSURE_FOLIAGE_DISTANCE_CHUNKS_PROPERTY,
				PauCVillagePerformanceDiagnostics.scenePressureTier() >= 2 ? 5 : 6,
				2,
				16
			);
			maxDistance = Math.min(maxDistance, pressuredDistanceChunks * 16.0D);
		}
		return horizontalDistanceSqr(pos.getX() + 0.5D, pos.getZ() + 0.5D, camera.x, camera.z) > maxDistance * maxDistance;
	}

	public static boolean shouldCullGenericLodObject(long objectId, String resourceLocationPath, double originX, double originY, double originZ) {
		long now = System.currentTimeMillis();
		GenericLodCullConfig config = genericLodCullConfig(now);
		boolean featureTransitionMask = PauCLodNearClipOverride.shouldUseFeatureTransitionMask();
		if (!active()
			|| (PauCLodShaderContext.isShaderPackInUse() && !featureTransitionMask)
			|| !PauCLodNearClipOverride.shouldKeepLodsUnderVanilla()
			|| !config.enabled()
			|| isCloudGenericObject(resourceLocationPath)) {
			clearGenericHold(objectId);
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return false;
		}

		PauCLodRange range = currentActiveRange();
		if (range == null) {
			return false;
		}

		Vec3 camera = cameraPosition(minecraft);
		boolean groundedFeatureCandidate = PauCLodGenericObjectCulling.isGroundedFeatureMaskCandidate(resourceLocationPath);
		if (PauCLodGenericObjectCulling.shouldCullGroundedFeatureNearPlayer(objectId, resourceLocationPath, originX, originZ)) {
			holdGenericCull(objectId, now, Math.max(config.holdMs(), 1_800));
			pruneGenericHolds(now);
			return true;
		}
		if (!PauCLodShaderContext.isShaderPackInUse() && groundedFeatureCandidate) {
			clearGenericHold(objectId);
			pruneGenericHolds(now);
			return false;
		}

		int extraChunks = config.extraChunks() + (featureTransitionMask ? 1 : 0);
		double maxDistance = (range.vanillaRenderDistanceChunks() + extraChunks) * 16.0D;
		double distanceSqr = horizontalDistanceSqr(originX, originZ, camera.x, camera.z);
		int releaseHeight = config.releaseHeightBlocks();
		boolean nearVanillaCoverage = distanceSqr <= maxDistance * maxDistance;
		boolean cameraInsideVerticalVolume = featureTransitionMask || camera.y <= originY + releaseHeight;
		if (nearVanillaCoverage && cameraInsideVerticalVolume) {
			holdGenericCull(objectId, now, featureTransitionMask ? Math.max(config.holdMs(), 1_800) : config.holdMs());
			pruneGenericHolds(now);
			return true;
		}

		Long holdUntil = GENERIC_LOD_CULL_HOLDS.get(objectId);
		if (holdUntil != null && now <= holdUntil) {
			double holdDistance = maxDistance + (featureTransitionMask ? 48.0D : 32.0D);
			return distanceSqr <= holdDistance * holdDistance;
		}

		clearGenericHold(objectId);
		pruneGenericHolds(now);
		return false;
	}

	public static boolean shouldEnableLodCloudRendering(boolean requestedByPlayer) {
		if (!requestedByPlayer) {
			return false;
		}
		if (readBoolean(LOD_CLOUD_FORCE_PROPERTY, false)) {
			return true;
		}
		return !readBoolean(LOD_CLOUD_SAFE_MODE_PROPERTY, false);
	}

	public static int lodCloudCellWidthBlocks() {
		return readInt(LOD_CLOUD_CELL_WIDTH_BLOCKS_PROPERTY, DEFAULT_LOD_CLOUD_CELL_WIDTH_BLOCKS, 4, 256);
	}

	public static double lodCloudThicknessBlocks() {
		return readInt(LOD_CLOUD_THICKNESS_BLOCKS_PROPERTY, DEFAULT_LOD_CLOUD_THICKNESS_BLOCKS, 1, 32);
	}

	public static float lodCloudSpeedBlocksPerSecond() {
		return readFloat(LOD_CLOUD_SPEED_BLOCKS_PER_SECOND_PROPERTY, DEFAULT_LOD_CLOUD_SPEED_BLOCKS_PER_SECOND, 0.0F, 12.0F);
	}

	public static int lodCloudHeightOffsetFromWorldTopBlocks() {
		return readInt(LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_PROPERTY, DEFAULT_LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_BLOCKS, -384, 384);
	}

	private static int effectiveLodCloudOuterActiveInstances() {
		int configuredOuter = readInt(
			LOD_CLOUD_OUTER_ACTIVE_INSTANCES_PROPERTY,
			DEFAULT_LOD_CLOUD_OUTER_ACTIVE_INSTANCES,
			1,
			MAX_LOD_CLOUD_OUTER_ACTIVE_INSTANCES
		);
		PauCLodRange range = currentActiveRange();
		if (range == null) {
			return configuredOuter;
		}

		int configuredTarget = Math.max(PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, range.configuredExtraDistanceChunks());
		int scaledOuter = (int) Math.ceil(
			configuredOuter * (configuredTarget / (double) PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS)
		);
		int minimumOuter = PauCLodShaderRuntime.shouldKeepPauCLodCloudsVisible() ? 4 : 1;
		return Math.max(minimumOuter, Math.min(MAX_LOD_CLOUD_OUTER_ACTIVE_INSTANCES, scaledOuter));
	}

	public static String describe() {
		return "renderCulling[enabled="
			+ active()
			+ ", vanillaTerrain="
			+ shouldCullVanillaTerrain()
			+ ", vanillaMargin="
			+ readInt(VANILLA_TERRAIN_MARGIN_CHUNKS_PROPERTY, DEFAULT_VANILLA_TERRAIN_MARGIN_CHUNKS, 0, 4)
			+ ", foliage="
			+ readBoolean(VANILLA_FOLIAGE_CULLING_PROPERTY, true)
			+ "@"
			+ readInt(VANILLA_FOLIAGE_DISTANCE_CHUNKS_PROPERTY, DEFAULT_VANILLA_FOLIAGE_DISTANCE_CHUNKS, 3, 32)
			+ "c"
			+ ", genericUnderVanilla="
			+ readBoolean(LOD_GENERIC_UNDER_VANILLA_CULLING_PROPERTY, true)
			+ "+-"
			+ readInt(LOD_GENERIC_UNDER_VANILLA_EXTRA_CHUNKS_PROPERTY, DEFAULT_LOD_GENERIC_UNDER_VANILLA_EXTRA_CHUNKS, 0, 4)
			+ "c/"
			+ readInt(LOD_GENERIC_UNDER_VANILLA_RELEASE_HEIGHT_PROPERTY, DEFAULT_LOD_GENERIC_UNDER_VANILLA_RELEASE_HEIGHT_BLOCKS, 8, 128)
			+ "b"
			+ ", aquatic="
			+ readBoolean(VANILLA_AQUATIC_PLANT_CULLING_PROPERTY, true)
			+ "@"
			+ readInt(VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS_PROPERTY, DEFAULT_VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS, 2, 16)
			+ "/"
			+ readInt(VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS_PROPERTY, DEFAULT_VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS, 2, 16)
			+ "c"
			+ ", entityExtra="
			+ readInt(ENTITY_EXTRA_CHUNKS_PROPERTY, DEFAULT_ENTITY_EXTRA_CHUNKS, 0, 8)
			+ ", blockEntityExtra="
			+ readInt(BLOCK_ENTITY_EXTRA_CHUNKS_PROPERTY, DEFAULT_BLOCK_ENTITY_EXTRA_CHUNKS, 0, 8)
			+ ", villagePressureCull="
			+ shouldApplyVillagePressureCulling()
			+ "@"
			+ readInt(VILLAGE_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_VILLAGE_ENTITY_PRESSURE_DISTANCE_BLOCKS, 64, 256)
			+ "/"
			+ readInt(VILLAGE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_VILLAGE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS, 32, 256)
			+ "b"
			+ ", pressureBlockEntities="
			+ readBoolean(PRESSURE_BLOCK_ENTITY_CULLING_PROPERTY, true)
			+ "@"
			+ readInt(PRESSURE_BLOCK_ENTITY_DISTANCE_BLOCKS_PROPERTY, DEFAULT_PRESSURE_BLOCK_ENTITY_DISTANCE_BLOCKS, 64, 256)
			+ "b"
			+ ", hordePressureCull="
			+ shouldApplyHordePressureCulling()
			+ "@"
			+ readInt(HORDE_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_HORDE_ENTITY_PRESSURE_DISTANCE_BLOCKS, 96, 384)
			+ "/"
			+ readInt(HORDE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_HORDE_BLOCK_ENTITY_PRESSURE_DISTANCE_BLOCKS, 64, 256)
			+ "b"
			+ ", particles="
			+ readInt(PARTICLE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_PARTICLE_DISTANCE_BLOCKS, 32, 384)
			+ "b, cloudCell="
			+ lodCloudCellWidthBlocks()
			+ "b, cloudThickness="
			+ (int) lodCloudThicknessBlocks()
			+ "b, cloudInnerCull="
			+ readInt(LOD_CLOUD_INNER_CULL_INSTANCES_PROPERTY, DEFAULT_LOD_CLOUD_INNER_CULL_INSTANCES, 0, 4)
			+ ", cloudOuter="
			+ effectiveLodCloudOuterActiveInstances()
			+ ", vanillaClouds="
			+ areVanillaCloudsVisible()
			+ ", cloudSafe="
			+ readBoolean(LOD_CLOUD_SAFE_MODE_PROPERTY, false)
			+ ", cloudSpeed="
			+ lodCloudSpeedBlocksPerSecond()
			+ ", cloudYOffset="
			+ lodCloudHeightOffsetFromWorldTopBlocks()
			+ "]";
	}

	private static boolean shouldCullVanillaTerrain() {
		return active() && readBoolean(VANILLA_TERRAIN_CULLING_PROPERTY, true);
	}

	private static boolean shouldCullVanillaChunk(LevelRenderer.RenderChunkInfo chunkInfo) {
		if (chunkInfo == null || chunkInfo.chunk == null) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return false;
		}

		PauCLodRange range = currentActiveRange();
		if (range == null) {
			return false;
		}

		int marginChunks = readInt(VANILLA_TERRAIN_MARGIN_CHUNKS_PROPERTY, DEFAULT_VANILLA_TERRAIN_MARGIN_CHUNKS, 0, 4);
		double maxDistance = (range.vanillaRenderDistanceChunks() + marginChunks) * 16.0D;
		BlockPos origin = chunkInfo.chunk.getOrigin();
		Vec3 camera = cameraPosition(minecraft);
		double centerX = origin.getX() + 8.0D;
		double centerZ = origin.getZ() + 8.0D;
		return horizontalDistanceSqr(centerX, centerZ, camera.x, camera.z) > maxDistance * maxDistance;
	}

	private static boolean active() {
		if (!readBoolean(ENABLED_PROPERTY, true)) {
			return false;
		}

		return currentActiveRange() != null;
	}

	private static boolean shouldApplyVillagePressureCulling() {
		return readBoolean(VILLAGE_PRESSURE_CULLING_PROPERTY, true)
			&& PauCVillagePerformanceDiagnostics.isVillagePressureActive();
	}

	private static boolean shouldApplyHordePressureCulling() {
		return readBoolean(HORDE_PRESSURE_CULLING_PROPERTY, true)
			&& PauCVillagePerformanceDiagnostics.isHordePressureActive();
	}

	private static double distanceFromVanillaChunks(int defaultExtraChunks, String property, double minBlocks) {
		Minecraft minecraft = Minecraft.getInstance();
		int vanillaDistance = minecraft != null && minecraft.options != null ? minecraft.options.getEffectiveRenderDistance() : 8;
		PauCLodRange range = currentActiveRange();
		if (range != null) {
			vanillaDistance = range.vanillaRenderDistanceChunks();
		}

		int extraChunks = readInt(property, defaultExtraChunks, 0, 8);
		return Math.max(minBlocks, (vanillaDistance + extraChunks) * 16.0D);
	}

	private static double scenePressureEntityDistanceBlocks(double fallbackDistance) {
		if (!PauCVillagePerformanceDiagnostics.isScenePressureActive()) {
			return fallbackDistance;
		}
		int tier = PauCVillagePerformanceDiagnostics.scenePressureTier();
		int fallback = switch (tier) {
			case 3 -> 64;
			case 2 -> 72;
			case 1 -> 88;
			default -> (int) Math.round(fallbackDistance);
		};
		if (PauCVillagePerformanceDiagnostics.lastPlayerGrounded() && PauCVillagePerformanceDiagnostics.lastPlayerHorizontalSpeed() >= scenePressureMovementSpeedThreshold()) {
			fallback = Math.max(48, fallback - 8);
		}
		String property = switch (tier) {
			case 3 -> SCENE_PRESSURE_ENTITY_DISTANCE_TIER3_PROPERTY;
			case 2 -> SCENE_PRESSURE_ENTITY_DISTANCE_TIER2_PROPERTY;
			default -> SCENE_PRESSURE_ENTITY_DISTANCE_TIER1_PROPERTY;
		};
		return readInt(property, fallback, 40, 256);
	}

	private static double scenePressureBlockEntityDistanceBlocks(double fallbackDistance) {
		if (!PauCVillagePerformanceDiagnostics.isScenePressureActive()) {
			return fallbackDistance;
		}
		int tier = PauCVillagePerformanceDiagnostics.scenePressureTier();
		int fallback = switch (tier) {
			case 3 -> 48;
			case 2 -> 64;
			case 1 -> 80;
			default -> (int) Math.round(fallbackDistance);
		};
		if (PauCVillagePerformanceDiagnostics.lastPlayerGrounded() && PauCVillagePerformanceDiagnostics.lastPlayerHorizontalSpeed() >= scenePressureMovementSpeedThreshold()) {
			fallback = Math.max(40, fallback - 8);
		}
		String property = switch (tier) {
			case 3 -> SCENE_PRESSURE_BLOCK_ENTITY_DISTANCE_TIER3_PROPERTY;
			case 2 -> SCENE_PRESSURE_BLOCK_ENTITY_DISTANCE_TIER2_PROPERTY;
			default -> SCENE_PRESSURE_BLOCK_ENTITY_DISTANCE_TIER1_PROPERTY;
		};
		return readInt(property, fallback, 32, 256);
	}

	private static double particlePressureDistanceScale() {
		double scale = 1.0D;
		if (PauCFrameSpikeAbsorber.isAbsorbing()) {
			scale = Math.min(scale, PauCFrameSpikeAbsorber.workScale());
		}
		if (PauCVillagePerformanceDiagnostics.isScenePressureActive()) {
			scale = Math.min(scale, PauCVillagePerformanceDiagnostics.scenePressureScale());
			if (PauCVillagePerformanceDiagnostics.lastPlayerGrounded()
				&& PauCVillagePerformanceDiagnostics.lastPlayerHorizontalSpeed() >= scenePressureMovementSpeedThreshold()) {
				scale = Math.max(0.42D, scale - 0.08D);
			}
		}
		return scale;
	}

	private static double scenePressureMovementSpeedThreshold() {
		return readFloat("pauc.lod.scenePressureMovementSpeedThreshold", 0.10F, 0.01F, 2.0F);
	}

	private static Vec3 cameraPosition(Minecraft minecraft) {
		if (minecraft.gameRenderer != null) {
			return minecraft.gameRenderer.getMainCamera().getPosition();
		}
		if (minecraft.player != null) {
			return minecraft.player.position();
		}
		return Vec3.ZERO;
	}

	private static boolean isCameraUnderWater(Minecraft minecraft) {
		return minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER;
	}

	private static boolean areVanillaCloudsVisible() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null) {
			return false;
		}

		try {
			return minecraft.options.getCloudsType() != CloudStatus.OFF;
		} catch (RuntimeException | LinkageError ignored) {
			return false;
		}
	}

	private static boolean isAquaticPlant(BlockState state) {
		return state.is(Blocks.KELP)
			|| state.is(Blocks.KELP_PLANT)
			|| state.is(Blocks.SEAGRASS)
			|| state.is(Blocks.TALL_SEAGRASS)
			|| state.is(Blocks.SEA_PICKLE);
	}

	private static boolean isCloudGenericObject(String resourceLocationPath) {
		return resourceLocationPath != null && resourceLocationPath.equalsIgnoreCase("Clouds");
	}

	private static boolean shouldUseDistantLodCloudRing() {
		return PauCLodShaderContext.isFallbackActive();
	}

	private static GenericLodCullConfig genericLodCullConfig(long now) {
		GenericLodCullConfig cached = cachedGenericLodCullConfig;
		int cacheMs = readInt(LOD_GENERIC_UNDER_VANILLA_CONFIG_CACHE_MS_PROPERTY, 500, 50, 5_000);
		if (cached != null && now - cachedGenericLodCullConfigAtMs <= cacheMs) {
			return cached;
		}

		GenericLodCullConfig config = new GenericLodCullConfig(
			readBoolean(LOD_GENERIC_UNDER_VANILLA_CULLING_PROPERTY, true),
			readInt(
				LOD_GENERIC_UNDER_VANILLA_EXTRA_CHUNKS_PROPERTY,
				DEFAULT_LOD_GENERIC_UNDER_VANILLA_EXTRA_CHUNKS,
				0,
				4
			),
			readInt(
				LOD_GENERIC_UNDER_VANILLA_RELEASE_HEIGHT_PROPERTY,
				DEFAULT_LOD_GENERIC_UNDER_VANILLA_RELEASE_HEIGHT_BLOCKS,
				8,
				128
			),
			readInt(
			LOD_GENERIC_UNDER_VANILLA_HOLD_MS_PROPERTY,
			DEFAULT_LOD_GENERIC_UNDER_VANILLA_HOLD_MS,
			0,
			5_000
			)
		);
		cachedGenericLodCullConfig = config;
		cachedGenericLodCullConfigAtMs = now;
		return config;
	}

	private static void holdGenericCull(long objectId, long now, int holdMs) {
		if (objectId == 0L) {
			return;
		}
		if (holdMs <= 0) {
			return;
		}
		GENERIC_LOD_CULL_HOLDS.put(objectId, now + holdMs);
	}

	private static PauCLodRange currentActiveRange() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range != null && range.enabled()) {
			return range;
		}
		if (!PauCLodClientSettings.isLodsEnabled()) {
			return null;
		}
		Minecraft minecraft = Minecraft.getInstance();
		int vanillaDistance = minecraft != null && minecraft.options != null ? minecraft.options.getEffectiveRenderDistance() : 8;
		return PauCLodRange.fromVanillaDistance(vanillaDistance, PauCLodClientSettings.targetDistanceChunks(), true);
	}

	private static void clearGenericHold(long objectId) {
		if (objectId != 0L) {
			GENERIC_LOD_CULL_HOLDS.remove(objectId);
		}
	}

	private static void pruneGenericHolds(long now) {
		if (GENERIC_LOD_CULL_HOLDS.isEmpty()) {
			return;
		}
		if (now - lastGenericHoldPruneMs < 5_000L) {
			return;
		}
		lastGenericHoldPruneMs = now;
		Iterator<Map.Entry<Long, Long>> iterator = GENERIC_LOD_CULL_HOLDS.entrySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().getValue() < now) {
				iterator.remove();
			}
		}
	}

	private static double horizontalDistanceSqr(double x, double z, double cameraX, double cameraZ) {
		double dx = x - cameraX;
		double dz = z - cameraZ;
		return dx * dx + dz * dz;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Integer.parseInt(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Float.parseFloat(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private record GenericLodCullConfig(boolean enabled, int extraChunks, int releaseHeightBlocks, int holdMs) {
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
