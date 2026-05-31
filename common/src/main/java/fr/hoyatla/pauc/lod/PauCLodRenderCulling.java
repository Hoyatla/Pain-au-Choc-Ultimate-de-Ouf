package fr.hoyatla.pauc.lod;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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

public final class PauCLodRenderCulling {
	private static final String ENABLED_PROPERTY = "pauc.lod.renderCulling";
	private static final String VANILLA_TERRAIN_CULLING_PROPERTY = "pauc.lod.cull.vanillaTerrain";
	private static final String VANILLA_TERRAIN_MARGIN_CHUNKS_PROPERTY = "pauc.lod.cull.vanillaTerrainMarginChunks";
	private static final String VANILLA_FOLIAGE_CULLING_PROPERTY = "pauc.lod.cull.vanillaFoliage";
	private static final String VANILLA_FOLIAGE_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.vanillaFoliageDistanceChunks";
	private static final String VANILLA_AQUATIC_PLANT_CULLING_PROPERTY = "pauc.lod.cull.aquaticPlants";
	private static final String VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.aquaticPlantDistanceChunks";
	private static final String VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS_PROPERTY = "pauc.lod.cull.underwaterPlantDistanceChunks";
	private static final String ENTITY_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.entityExtraChunks";
	private static final String BLOCK_ENTITY_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.blockEntityExtraChunks";
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
	private static final int DEFAULT_VANILLA_TERRAIN_MARGIN_CHUNKS = 1;
	private static final int DEFAULT_VANILLA_FOLIAGE_DISTANCE_CHUNKS = 7;
	private static final int DEFAULT_VANILLA_AQUATIC_PLANT_DISTANCE_CHUNKS = 3;
	private static final int DEFAULT_VANILLA_UNDERWATER_PLANT_DISTANCE_CHUNKS = 5;
	private static final int DEFAULT_ENTITY_EXTRA_CHUNKS = 0;
	private static final int DEFAULT_BLOCK_ENTITY_EXTRA_CHUNKS = 0;
	private static final int DEFAULT_PARTICLE_DISTANCE_BLOCKS = 80;
	private static final int DEFAULT_LOD_CLOUD_CELL_WIDTH_BLOCKS = 12;
	private static final int DEFAULT_LOD_CLOUD_THICKNESS_BLOCKS = 4;
	private static final int DEFAULT_LOD_CLOUD_INNER_CULL_INSTANCES = 0;
	private static final int DEFAULT_LOD_CLOUD_OUTER_ACTIVE_INSTANCES = 6;
	private static final float DEFAULT_LOD_CLOUD_SPEED_BLOCKS_PER_SECOND = 6.0F;
	private static final int DEFAULT_LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_BLOCKS = -128;

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
		Vec3 camera = cameraPosition(minecraft);
		return horizontalDistanceSqr(entity.getX(), entity.getZ(), camera.x, camera.z) > maxDistance * maxDistance;
	}

	public static boolean shouldCullBlockEntity(BlockEntity blockEntity) {
		if (!active() || blockEntity == null) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || blockEntity.getLevel() != minecraft.level) {
			return false;
		}

		double maxDistance = distanceFromVanillaChunks(DEFAULT_BLOCK_ENTITY_EXTRA_CHUNKS, BLOCK_ENTITY_EXTRA_CHUNKS_PROPERTY, 96.0D);
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

		int innerCull = PauCLodShaderRuntime.shouldProtectLodCloudCenter()
			? 0
			: readInt(LOD_CLOUD_INNER_CULL_INSTANCES_PROPERTY, DEFAULT_LOD_CLOUD_INNER_CULL_INSTANCES, 0, 4);
		boolean vanillaCloudOverlap = !PauCLodShaderRuntime.shouldProtectLodCloudCenter()
			&& readBoolean(LOD_CLOUD_CULL_VANILLA_OVERLAP_PROPERTY, true)
			&& areVanillaCloudsVisible();
		if (vanillaCloudOverlap && innerCull > 0 && Math.abs(instanceOffsetX) <= innerCull && Math.abs(instanceOffsetZ) <= innerCull) {
			return true;
		}

		int outerActive = readInt(LOD_CLOUD_OUTER_ACTIVE_INSTANCES_PROPERTY, DEFAULT_LOD_CLOUD_OUTER_ACTIVE_INSTANCES, 1, 8);
		int radialDistanceSqr = instanceOffsetX * instanceOffsetX + instanceOffsetZ * instanceOffsetZ;
		return radialDistanceSqr > outerActive * outerActive;
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

		PauCLodRange range = PauCLodHorizonState.currentRange();
		int defaultDistance = DEFAULT_VANILLA_FOLIAGE_DISTANCE_CHUNKS;
		if (range != null && range.enabled()) {
			defaultDistance = Math.max(3, Math.min(range.vanillaRenderDistanceChunks(), range.vanillaRenderDistanceChunks() - 3));
		}

		double maxDistance = readInt(VANILLA_FOLIAGE_DISTANCE_CHUNKS_PROPERTY, defaultDistance, 3, 32) * 16.0D;
		return horizontalDistanceSqr(pos.getX() + 0.5D, pos.getZ() + 0.5D, camera.x, camera.z) > maxDistance * maxDistance;
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
		return readInt(LOD_CLOUD_THICKNESS_BLOCKS_PROPERTY, DEFAULT_LOD_CLOUD_THICKNESS_BLOCKS, 4, 32);
	}

	public static float lodCloudSpeedBlocksPerSecond() {
		return readFloat(LOD_CLOUD_SPEED_BLOCKS_PER_SECOND_PROPERTY, DEFAULT_LOD_CLOUD_SPEED_BLOCKS_PER_SECOND, 0.0F, 12.0F);
	}

	public static int lodCloudHeightOffsetFromWorldTopBlocks() {
		return readInt(LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_PROPERTY, DEFAULT_LOD_CLOUD_HEIGHT_OFFSET_FROM_WORLD_TOP_BLOCKS, -384, 384);
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
			+ ", particles="
			+ readInt(PARTICLE_DISTANCE_BLOCKS_PROPERTY, DEFAULT_PARTICLE_DISTANCE_BLOCKS, 32, 384)
			+ "b, cloudCell="
			+ lodCloudCellWidthBlocks()
			+ "b, cloudThickness="
			+ (int) lodCloudThicknessBlocks()
			+ "b, cloudInnerCull="
			+ readInt(LOD_CLOUD_INNER_CULL_INSTANCES_PROPERTY, DEFAULT_LOD_CLOUD_INNER_CULL_INSTANCES, 0, 4)
			+ ", cloudOuter="
			+ readInt(LOD_CLOUD_OUTER_ACTIVE_INSTANCES_PROPERTY, DEFAULT_LOD_CLOUD_OUTER_ACTIVE_INSTANCES, 1, 8)
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

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
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

		PauCLodRange range = PauCLodHorizonState.currentRange();
		return range != null && range.enabled();
	}

	private static double distanceFromVanillaChunks(int defaultExtraChunks, String property, double minBlocks) {
		Minecraft minecraft = Minecraft.getInstance();
		int vanillaDistance = minecraft != null && minecraft.options != null ? minecraft.options.getEffectiveRenderDistance() : 8;
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range != null && range.enabled()) {
			vanillaDistance = range.vanillaRenderDistanceChunks();
		}

		int extraChunks = readInt(property, defaultExtraChunks, 0, 8);
		return Math.max(minBlocks, (vanillaDistance + extraChunks) * 16.0D);
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

	private static double horizontalDistanceSqr(double x, double z, double cameraX, double cameraZ) {
		double dx = x - cameraX;
		double dz = z - cameraZ;
		return dx * dx + dz * dz;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
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
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Float.parseFloat(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
