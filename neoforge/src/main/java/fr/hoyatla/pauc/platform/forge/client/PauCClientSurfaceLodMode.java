package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodNearClipOverride;
import fr.hoyatla.pauc.lod.PauCLodRange;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import org.slf4j.Logger;

import java.util.Locale;

public final class PauCClientSurfaceLodMode {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.surfaceOnlyWhenAboveGround";
	private static final String FORCE_SURFACE_GENERATOR_PROPERTY = "pauc.lod.surfaceOnlyForceSurfaceGenerator";
	private static final String ALLOW_QUALITY_REDUCTION_PROPERTY = "pauc.lod.surfaceOnlyCanLowerQuality";
	private static final String VERTICAL_QUALITY_PROPERTY = "pauc.lod.surfaceOnlyVerticalQuality";
	private static final String MIN_Y_PROPERTY = "pauc.lod.surfaceOnlyMinY";
	private static final String MIN_SURFACE_Y_PROPERTY = "pauc.lod.surfaceOnlyMinSurfaceY";
	private static final String SURFACE_TOLERANCE_PROPERTY = "pauc.lod.surfaceOnlyToleranceBlocks";
	private static final String ESTIMATE_FROM_CAMERA_PROPERTY = "pauc.lod.surfaceOnlyEstimateFromCamera";
	private static final String ENTER_TICKS_PROPERTY = "pauc.lod.surfaceOnlyEnterTicks";
	private static final String EXIT_TICKS_PROPERTY = "pauc.lod.surfaceOnlyExitTicks";
	private static final String CAVE_CLEARANCE_PROPERTY = "pauc.lod.surfaceOnlyCaveClearanceBlocks";
	private static final String DENSE_VEGETATION_DISABLE_PROPERTY = "pauc.lod.surfaceOnlyDisableInDenseVegetation";
	private static final String DENSE_VEGETATION_SCAN_RADIUS_PROPERTY = "pauc.lod.surfaceOnlyDenseVegetationScanRadius";
	private static final String DENSE_VEGETATION_SCAN_HEIGHT_PROPERTY = "pauc.lod.surfaceOnlyDenseVegetationScanHeight";
	private static final String DENSE_VEGETATION_SCORE_PROPERTY = "pauc.lod.surfaceOnlyDenseVegetationScore";
	private static final String TALL_FEATURE_DISABLE_PROPERTY = "pauc.lod.surfaceOnlyDisableNearTallFeatures";
	private static final String TALL_FEATURE_SCAN_RADIUS_PROPERTY = "pauc.lod.surfaceOnlyTallFeatureScanRadius";
	private static final String TALL_FEATURE_SCORE_PROPERTY = "pauc.lod.surfaceOnlyTallFeatureScore";
	private static final String TALL_FEATURE_LEAF_DELTA_PROPERTY = "pauc.lod.surfaceOnlyTallFeatureLeafDelta";
	private static final String TALL_FEATURE_CAMERA_MARGIN_PROPERTY = "pauc.lod.surfaceOnlyTallFeatureCameraMargin";
	private static final String FEATURE_TRANSITION_ENTER_TICKS_PROPERTY = "pauc.lod.featureTransitionEnterTicks";
	private static final String FEATURE_TRANSITION_EXIT_TICKS_PROPERTY = "pauc.lod.featureTransitionExitTicks";
	private static final String FEATURE_TRANSITION_REASON_SWITCH_TICKS_PROPERTY = "pauc.lod.featureTransitionReasonSwitchTicks";
	private static final String FEATURE_TRANSITION_STICKY_TICKS_PROPERTY = "pauc.lod.featureTransitionStickyTicks";
	private static final String FEATURE_TRANSITION_STICKY_CHUNK_RADIUS_PROPERTY = "pauc.lod.featureTransitionStickyChunkRadius";
	private static final String FEATURE_TRANSITION_STICKY_SURFACE_DELTA_PROPERTY = "pauc.lod.featureTransitionStickySurfaceDelta";
	private static final String TRANSIENT_SURFACE_RECOVERY_TICKS_PROPERTY = "pauc.lod.surfaceOnlyTransientRecoveryTicks";
	private static final String TRANSIENT_SURFACE_RECOVERY_CHUNK_RADIUS_PROPERTY = "pauc.lod.surfaceOnlyTransientRecoveryChunkRadius";
	private static final String TRANSIENT_SURFACE_RECOVERY_SURFACE_DELTA_PROPERTY = "pauc.lod.surfaceOnlyTransientRecoverySurfaceDelta";
	private static final int LOG_THROTTLE_TICKS = 100;
	private static final int[] SURFACE_SAMPLE_OFFSETS = {
		0, 0,
		4, 0, -4, 0, 0, 4, 0, -4,
		8, 0, -8, 0, 0, 8, 0, -8,
		8, 8, 8, -8, -8, 8, -8, -8
	};
	private static final int[] VEGETATION_SAMPLE_OFFSETS = {
		0, 0,
		3, 0, -3, 0, 0, 3, 0, -3,
		6, 0, -6, 0, 0, 6, 0, -6,
		3, 3, 3, -3, -3, 3, -3, -3,
		6, 6, 6, -6, -6, 6, -6, -6
	};
	private static final Heightmap.Types[] SURFACE_HEIGHTMAPS = {
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
		Heightmap.Types.MOTION_BLOCKING,
		Heightmap.Types.WORLD_SURFACE
	};
	private static volatile SurfaceState lastState = SurfaceState.unavailable("not-started");
	private static int surfaceTicks;
	private static int nonSurfaceTicks;
	private static int ticksUntilNextLog;
	private static boolean featureTransitionActive;
	private static int featureTransitionTicks;
	private static int nonFeatureTransitionTicks;
	private static String featureTransitionReason = "";
	private static String pendingFeatureTransitionReason = "";
	private static FeaturePresentationMode featurePresentationMode = FeaturePresentationMode.NONE;
	private static int featureReasonSwitchTicks;
	private static int stickyFeatureTransitionTicks;
	private static String stickyFeatureTransitionReason = "";
	private static int stickyFeatureTransitionChunkX;
	private static int stickyFeatureTransitionChunkZ;
	private static int stickyFeatureTransitionSurfaceY = Integer.MIN_VALUE;
	private static int transientSurfaceRecoveryTicks;
	private static int transientSurfaceRecoveryChunkX;
	private static int transientSurfaceRecoveryChunkZ;
	private static int transientSurfaceRecoverySurfaceY = Integer.MIN_VALUE;

	private PauCClientSurfaceLodMode() {
	}

	public static void onClientTick(Minecraft minecraft) {
		SurfaceSample sample = recoverTransientSurfaceSample(sample(minecraft));
		sample = stabilizeFeatureBlockingSample(sample);
		updateFeatureTransitionState(sample);
		updateTransientSurfaceRecovery(sample);
		PauCLodNearClipOverride.setFeatureTransitionMask(featureTransitionActive, featureTransitionReason);
		SurfaceState previous = lastState;
		boolean active = previous.active();
		if (sample.candidate()) {
			surfaceTicks++;
			nonSurfaceTicks = 0;
			if (!active && surfaceTicks >= readInt(ENTER_TICKS_PROPERTY, 30, 0, 200)) {
				active = true;
			}
		} else {
			nonSurfaceTicks++;
			surfaceTicks = 0;
			if (active && nonSurfaceTicks >= readInt(EXIT_TICKS_PROPERTY, 12, 0, 80)) {
				active = false;
			}
		}
		if (sample.forceExit()) {
			active = false;
			surfaceTicks = 0;
			nonSurfaceTicks = readInt(EXIT_TICKS_PROPERTY, 12, 0, 80);
		}

		SurfaceState state = new SurfaceState(
			sample.available(),
			active,
			sample.candidate(),
			sample.reason(),
			sample.cameraY(),
			sample.surfaceY()
		);
		lastState = state;
		boolean periodicLogAllowed = state.available() || !"no-client-level".equals(state.reason());
		if (!sameMode(state, previous) || (periodicLogAllowed && ticksUntilNextLog-- <= 0)) {
			ticksUntilNextLog = LOG_THROTTLE_TICKS;
			LOGGER.info("PauC surface LOD mode: {}.", state.describe());
		}
	}

	public static void reset() {
		lastState = SurfaceState.unavailable("reset");
		surfaceTicks = 0;
		nonSurfaceTicks = 0;
		ticksUntilNextLog = 0;
		featureTransitionActive = false;
		featureTransitionTicks = 0;
		nonFeatureTransitionTicks = 0;
		featureTransitionReason = "";
		pendingFeatureTransitionReason = "";
		featurePresentationMode = FeaturePresentationMode.NONE;
		featureReasonSwitchTicks = 0;
		stickyFeatureTransitionTicks = 0;
		stickyFeatureTransitionReason = "";
		stickyFeatureTransitionChunkX = 0;
		stickyFeatureTransitionChunkZ = 0;
		stickyFeatureTransitionSurfaceY = Integer.MIN_VALUE;
		transientSurfaceRecoveryTicks = 0;
		transientSurfaceRecoveryChunkX = 0;
		transientSurfaceRecoveryChunkZ = 0;
		transientSurfaceRecoverySurfaceY = Integer.MIN_VALUE;
		PauCLodNearClipOverride.setFeatureTransitionMask(false, "reset");
	}

	public static boolean isSurfaceOnlyActive() {
		return lastState.active();
	}

	public static boolean prefersAccurateVegetationLods() {
		return prefersAccurateFeatureLods();
	}

	public static boolean prefersAccurateFeatureLods() {
		return featurePresentationMode.requiresAccuratePresentation() && shouldHoldAccurateFeaturePresentation();
	}

	public static EDhApiMaxHorizontalResolution adjustMaxHorizontalResolution(EDhApiMaxHorizontalResolution requestedResolution) {
		if (prefersAccurateFeatureLods()) {
			return EDhApiMaxHorizontalResolution.BLOCK;
		}
		return requestedResolution;
	}

	public static EDhApiHorizontalQuality adjustHorizontalQuality(EDhApiHorizontalQuality requestedQuality) {
		if (prefersAccurateFeatureLods()) {
			return EDhApiHorizontalQuality.HIGH;
		}
		return requestedQuality;
	}

	public static String featurePresentationModeId() {
		return featurePresentationMode.id;
	}

	public static String adjustVerticalQuality(String requestedQuality) {
		if (prefersAccurateFeatureLods()) {
			return EDhApiVerticalQuality.HIGH.name();
		}
		if (!isSurfaceOnlyActive() || !readBoolean(ALLOW_QUALITY_REDUCTION_PROPERTY, false)) {
			return requestedQuality;
		}
		return System.getProperty(VERTICAL_QUALITY_PROPERTY, EDhApiVerticalQuality.HEIGHT_MAP.name());
	}

	public static EDhApiVerticalQuality adjustVerticalQuality(EDhApiVerticalQuality requestedQuality) {
		if (prefersAccurateFeatureLods()) {
			return EDhApiVerticalQuality.HIGH;
		}
		if (!isSurfaceOnlyActive() || !readBoolean(ALLOW_QUALITY_REDUCTION_PROPERTY, false)) {
			return requestedQuality;
		}
		return readEnum(VERTICAL_QUALITY_PROPERTY, EDhApiVerticalQuality.class, EDhApiVerticalQuality.HEIGHT_MAP);
	}

	public static EDhApiDistantGeneratorMode adjustGeneratorMode(EDhApiDistantGeneratorMode requestedMode) {
		if (!isSurfaceOnlyActive() || !readBoolean(FORCE_SURFACE_GENERATOR_PROPERTY, true) || requestedMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY) {
			return requestedMode;
		}
		if (PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation() || PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
			return requestedMode;
		}
		return EDhApiDistantGeneratorMode.SURFACE;
	}

	public static int surfaceCaveCullingHeight(int fallbackHeight) {
		SurfaceState state = lastState;
		if (!state.active() || state.surfaceY() <= Integer.MIN_VALUE / 2) {
			return fallbackHeight;
		}
		int clearance = readInt(CAVE_CLEARANCE_PROPERTY, 8, 0, 96);
		return Math.max(fallbackHeight, state.surfaceY() - clearance);
	}

	public static String describeState() {
		return lastState.describe();
	}

	private static SurfaceSample sample(Minecraft minecraft) {
		if (!readBoolean(ENABLED_PROPERTY, true)) {
			return SurfaceSample.inactive("disabled", 0, 0);
		}
		if (minecraft == null || minecraft.level == null || minecraft.player == null || minecraft.gameRenderer == null) {
			return SurfaceSample.inactive("no-client-level", 0, 0);
		}
		if (minecraft.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
			return SurfaceSample.inactive("camera-in-fluid", 0, 0);
		}

		ClientLevel level = minecraft.level;
		BlockPos cameraPos = BlockPos.containing(minecraft.gameRenderer.getMainCamera().getPosition());
		int cameraChunkX = cameraPos.getX() >> 4;
		int cameraChunkZ = cameraPos.getZ() >> 4;
		int cameraY = cameraPos.getY();
		if (!level.hasChunk(cameraChunkX, cameraChunkZ)) {
			return SurfaceSample.inactive("local-chunk-unavailable", cameraChunkX, cameraChunkZ);
		}

		int minY = readInt(MIN_Y_PROPERTY, 48, -64, 320);
		if (cameraY < minY) {
			return SurfaceSample.of(false, "below-surface-y", cameraY, Integer.MIN_VALUE, cameraChunkX, cameraChunkZ);
		}

		int minSurfaceY = readInt(MIN_SURFACE_Y_PROPERTY, 0, -64, 320);
		int surfaceY = sampleSurfaceY(level, cameraPos);
		if (surfaceY <= level.getMinBuildHeight() + 1) {
			if (readBoolean(ESTIMATE_FROM_CAMERA_PROPERTY, true) && cameraY >= minSurfaceY + 16) {
				return SurfaceSample.of(true, "surface-height-estimated", cameraY, cameraY, cameraChunkX, cameraChunkZ);
			}
			return SurfaceSample.of(false, "surface-height-unavailable", cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}
		if (surfaceY < minSurfaceY) {
			return SurfaceSample.of(false, "surface-height-too-low", cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}

		int tolerance = readInt(SURFACE_TOLERANCE_PROPERTY, 6, 0, 32);
		if (cameraY < surfaceY - tolerance) {
			return SurfaceSample.of(false, "below-local-surface", cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}
		if (readBoolean(DENSE_VEGETATION_DISABLE_PROPERTY, true) && isDenseVegetationAroundCamera(level, cameraPos, cameraY, surfaceY)) {
			return SurfaceSample.of(false, "dense-vegetation", cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}
		String tallFeatureReason = readBoolean(TALL_FEATURE_DISABLE_PROPERTY, true)
			? tallFeatureReason(level, cameraPos, cameraY)
			: null;
		if (tallFeatureReason != null) {
			return SurfaceSample.of(false, tallFeatureReason, cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}

		return SurfaceSample.of(true, "surface-stable", cameraY, surfaceY, cameraChunkX, cameraChunkZ);
	}

	private static int sampleSurfaceY(ClientLevel level, BlockPos center) {
		int centerSurfaceY = samplePrimarySurfaceY(level, center.getX(), center.getZ());
		if (isUsableSurfaceHeight(level, centerSurfaceY)) {
			return centerSurfaceY;
		}

		int bestSurfaceY = Integer.MIN_VALUE;
		for (int index = 0; index < SURFACE_SAMPLE_OFFSETS.length; index += 2) {
			int x = center.getX() + SURFACE_SAMPLE_OFFSETS[index];
			int z = center.getZ() + SURFACE_SAMPLE_OFFSETS[index + 1];
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			int surfaceY = samplePrimarySurfaceY(level, x, z);
			if (isUsableSurfaceHeight(level, surfaceY)) {
				bestSurfaceY = Math.max(bestSurfaceY, surfaceY);
			}
		}
		if (isUsableSurfaceHeight(level, bestSurfaceY)) {
			return bestSurfaceY;
		}
		return scanLocalColumnSurfaceY(level, center);
	}

	private static int samplePrimarySurfaceY(ClientLevel level, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return Integer.MIN_VALUE;
		}

		int noLeavesSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (isUsableSurfaceHeight(level, noLeavesSurface)) {
			return noLeavesSurface;
		}
		for (Heightmap.Types heightmap : SURFACE_HEIGHTMAPS) {
			int surfaceY = level.getHeight(heightmap, x, z);
			if (isUsableSurfaceHeight(level, surfaceY)) {
				return surfaceY;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static int scanLocalColumnSurfaceY(ClientLevel level, BlockPos center) {
		if (!level.hasChunk(center.getX() >> 4, center.getZ() >> 4)) {
			return Integer.MIN_VALUE;
		}
		int startY = Math.min(level.getMaxBuildHeight() - 1, Math.max(center.getY() + 16, level.getMinBuildHeight()));
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(center.getX(), startY, center.getZ());
		for (int y = startY; y >= level.getMinBuildHeight(); y--) {
			mutable.setY(y);
			BlockState state = level.getBlockState(mutable);
			if (!state.isAir() || !state.getFluidState().isEmpty()) {
				return y + 1;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static boolean isDenseVegetationAroundCamera(ClientLevel level, BlockPos cameraPos, int cameraY, int surfaceY) {
		int scanRadius = readInt(DENSE_VEGETATION_SCAN_RADIUS_PROPERTY, 6, 2, 12);
		int scanHeight = readInt(DENSE_VEGETATION_SCAN_HEIGHT_PROPERTY, 12, 4, 24);
		int minScore = readInt(DENSE_VEGETATION_SCORE_PROPERTY, 22, 4, 96);
		int lowerY = Math.max(level.getMinBuildHeight(), Math.min(cameraY, surfaceY) - 2);
		int upperY = Math.min(level.getMaxBuildHeight() - 1, Math.max(cameraY, surfaceY) + scanHeight);
		int score = 0;
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

		for (int index = 0; index < VEGETATION_SAMPLE_OFFSETS.length; index += 2) {
			int dx = VEGETATION_SAMPLE_OFFSETS[index];
			int dz = VEGETATION_SAMPLE_OFFSETS[index + 1];
			if (Math.abs(dx) > scanRadius || Math.abs(dz) > scanRadius) {
				continue;
			}
			int x = cameraPos.getX() + dx;
			int z = cameraPos.getZ() + dz;
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}

			for (int y = lowerY; y <= upperY; y += 2) {
				mutable.set(x, y, z);
				BlockState state = level.getBlockState(mutable);
				score += vegetationScore(state);
				if (score >= minScore) {
					return true;
				}
			}
		}

		return false;
	}

	private static String tallFeatureReason(ClientLevel level, BlockPos cameraPos, int cameraY) {
		int scanRadius = readInt(TALL_FEATURE_SCAN_RADIUS_PROPERTY, 8, 2, 16);
		int minScore = readInt(TALL_FEATURE_SCORE_PROPERTY, 16, 4, 96);
		int minLeafDelta = readInt(TALL_FEATURE_LEAF_DELTA_PROPERTY, 4, 1, 24);
		int cameraMargin = readInt(TALL_FEATURE_CAMERA_MARGIN_PROPERTY, 2, 0, 12);
		int vegetationScore = 0;
		int structureScore = 0;
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

		for (int index = 0; index < VEGETATION_SAMPLE_OFFSETS.length; index += 2) {
			int dx = VEGETATION_SAMPLE_OFFSETS[index];
			int dz = VEGETATION_SAMPLE_OFFSETS[index + 1];
			if (Math.abs(dx) > scanRadius || Math.abs(dz) > scanRadius) {
				continue;
			}
			int x = cameraPos.getX() + dx;
			int z = cameraPos.getZ() + dz;
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}

			int leafTopY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			int solidTopY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			int canopyDepth = leafTopY - solidTopY;
			if (leafTopY >= cameraY - cameraMargin && canopyDepth >= minLeafDelta) {
				vegetationScore += 3 + Math.max(0, canopyDepth - minLeafDelta);
			}

			int topBlockY = solidTopY - 1;
			if (topBlockY >= cameraY + cameraMargin) {
				mutable.set(x, topBlockY, z);
				if (isTallStructureTop(level, mutable)) {
					structureScore += hasStructureSupportBelow(level, mutable) ? 4 : 2;
				}
			}

			if (vegetationScore >= minScore && structureScore >= minScore / 3) {
				return "tall-local-features:mixed";
			}
			if (vegetationScore >= minScore) {
				return "tall-local-features:vegetation";
			}
			if (structureScore >= minScore) {
				return "tall-local-features:structure";
			}
		}

		return null;
	}

	private static int vegetationScore(BlockState state) {
		if (state == null || state.isAir()) {
			return 0;
		}
		if (state.is(BlockTags.LEAVES)) {
			return 3;
		}
		if (state.is(BlockTags.LOGS)) {
			return 2;
		}
		if (state.is(Blocks.VINE)
			|| state.is(Blocks.CAVE_VINES)
			|| state.is(Blocks.CAVE_VINES_PLANT)
			|| state.is(Blocks.WEEPING_VINES)
			|| state.is(Blocks.WEEPING_VINES_PLANT)
			|| state.is(Blocks.TWISTING_VINES)
			|| state.is(Blocks.TWISTING_VINES_PLANT)
			|| state.is(Blocks.BAMBOO)
			|| state.is(Blocks.BAMBOO_SAPLING)) {
			return 2;
		}
		return 0;
	}

	private static boolean isTallStructureTop(ClientLevel level, BlockPos.MutableBlockPos mutable) {
		BlockState state = level.getBlockState(mutable);
		if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		if (state.is(BlockTags.LEAVES)) {
			return false;
		}
		if (state.is(BlockTags.LOGS)) {
			return false;
		}
		if (state.is(Blocks.VINE)
			|| state.is(Blocks.CAVE_VINES)
			|| state.is(Blocks.CAVE_VINES_PLANT)
			|| state.is(Blocks.WEEPING_VINES)
			|| state.is(Blocks.WEEPING_VINES_PLANT)
			|| state.is(Blocks.TWISTING_VINES)
			|| state.is(Blocks.TWISTING_VINES_PLANT)
			|| state.is(Blocks.BAMBOO)
			|| state.is(Blocks.BAMBOO_SAPLING)) {
			return false;
		}
		return state.canOcclude() && state.isCollisionShapeFullBlock(level, mutable);
	}

	private static boolean hasStructureSupportBelow(ClientLevel level, BlockPos.MutableBlockPos mutable) {
		int x = mutable.getX();
		int z = mutable.getZ();
		int y = mutable.getY();
		int support = 0;
		for (int dy = 0; dy < 3; dy++) {
			mutable.set(x, y - dy, z);
			BlockState state = level.getBlockState(mutable);
			if (state == null || state.isAir() || !state.canOcclude() || !state.isCollisionShapeFullBlock(level, mutable)) {
				break;
			}
			support++;
		}
		mutable.set(x, y, z);
		return support >= 2;
	}

	private static boolean isUsableSurfaceHeight(ClientLevel level, int surfaceY) {
		return surfaceY > level.getMinBuildHeight() + 1 && surfaceY <= level.getMaxBuildHeight() + 1;
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

	private static <T extends Enum<T>> T readEnum(String key, Class<T> enumType, T fallback) {
		String rawValue = System.getProperty(key);
		if (rawValue == null || rawValue.isBlank()) {
			return fallback;
		}
		try {
			return Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static boolean sameMode(SurfaceState current, SurfaceState previous) {
		return current.available() == previous.available()
			&& current.active() == previous.active()
			&& current.candidate() == previous.candidate()
			&& sameReasonFamily(current.reason(), previous.reason());
	}

	private static boolean shouldRequestFeatureTransitionMask(SurfaceSample sample) {
		if (sample == null || !sample.available()) {
			return false;
		}

		String reason = sample.reason();
		return "dense-vegetation".equals(reason) || reason.startsWith("tall-local-features");
	}

	private static boolean shouldHoldAccurateFeaturePresentation() {
		if (!featureTransitionActive || PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
			return false;
		}
		if (PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation()
			|| PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage()) {
			return false;
		}
		if (PauCEmbeddedLodRuntimeDiagnostics.backlogPressure() > readDouble("pauc.lod.featureAccuratePresentationMaxQueuePressure", 0.05D, 0.0D, 1.0D)) {
			return false;
		}
		if (PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() > readInt("pauc.lod.featureAccuratePresentationMaxBacklogTasks", 2, 0, 128)) {
			return false;
		}
		if (PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() > readInt("pauc.lod.featureAccuratePresentationMaxPendingChunks", 48, 0, 2048)) {
			return false;
		}
		int configuredTarget = PauCLodClientSettings.configuredTargetDistanceChunks();
		int maxAccurateTarget = readInt(
			"pauc.lod.featureAccuratePresentationMaxTarget",
			24,
			PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS,
			PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS
		);
		return configuredTarget <= maxAccurateTarget;
	}

	private static SurfaceSample stabilizeFeatureBlockingSample(SurfaceSample sample) {
		if (sample == null) {
			return SurfaceSample.inactive("sample-null", 0, 0);
		}
		if (!sample.available()) {
			clearStickyFeatureTransition();
			return sample;
		}
		if (shouldRequestFeatureTransitionMask(sample)) {
			stickyFeatureTransitionTicks = readInt(FEATURE_TRANSITION_STICKY_TICKS_PROPERTY, 16, 0, 80);
			stickyFeatureTransitionReason = sample.reason();
			stickyFeatureTransitionChunkX = sample.cameraChunkX();
			stickyFeatureTransitionChunkZ = sample.cameraChunkZ();
			stickyFeatureTransitionSurfaceY = sample.surfaceY();
			return sample;
		}
		if (stickyFeatureTransitionTicks <= 0 || stickyFeatureTransitionReason.isBlank()) {
			clearStickyFeatureTransition();
			return sample;
		}
		boolean sameRegion = Math.max(
			Math.abs(sample.cameraChunkX() - stickyFeatureTransitionChunkX),
			Math.abs(sample.cameraChunkZ() - stickyFeatureTransitionChunkZ)
		) <= readInt(FEATURE_TRANSITION_STICKY_CHUNK_RADIUS_PROPERTY, 1, 0, 4);
		int stickySurfaceDelta = readInt(FEATURE_TRANSITION_STICKY_SURFACE_DELTA_PROPERTY, 10, 0, 64);
		boolean similarSurface = !isFiniteSurface(stickyFeatureTransitionSurfaceY)
			|| !isFiniteSurface(sample.surfaceY())
			|| Math.abs(sample.surfaceY() - stickyFeatureTransitionSurfaceY) <= stickySurfaceDelta;
		if (!sameRegion || !similarSurface) {
			clearStickyFeatureTransition();
			return sample;
		}
		stickyFeatureTransitionTicks--;
		return SurfaceSample.of(
			false,
			stickyFeatureTransitionReason,
			sample.cameraY(),
			sample.surfaceY(),
			sample.cameraChunkX(),
			sample.cameraChunkZ()
		);
	}

	private static SurfaceSample recoverTransientSurfaceSample(SurfaceSample sample) {
		if (sample == null || !sample.available() || sample.candidate()) {
			return sample;
		}
		if (!isTransientSurfaceReason(sample.reason())) {
			return sample;
		}
		if (transientSurfaceRecoveryTicks <= 0 || !isFiniteSurface(transientSurfaceRecoverySurfaceY)) {
			return sample;
		}

		int chunkRadius = readInt(TRANSIENT_SURFACE_RECOVERY_CHUNK_RADIUS_PROPERTY, 1, 0, 4);
		boolean sameRegion = Math.max(
			Math.abs(sample.cameraChunkX() - transientSurfaceRecoveryChunkX),
			Math.abs(sample.cameraChunkZ() - transientSurfaceRecoveryChunkZ)
		) <= chunkRadius;
		if (!sameRegion) {
			return sample;
		}

		int surfaceDelta = readInt(TRANSIENT_SURFACE_RECOVERY_SURFACE_DELTA_PROPERTY, 24, 0, 128);
		boolean compatibleSurface = !isFiniteSurface(sample.surfaceY())
			|| Math.abs(sample.surfaceY() - transientSurfaceRecoverySurfaceY) <= surfaceDelta;
		if (!compatibleSurface) {
			return sample;
		}

		transientSurfaceRecoveryTicks--;
		return SurfaceSample.of(
			true,
			"surface-stable-cache",
			sample.cameraY(),
			transientSurfaceRecoverySurfaceY,
			sample.cameraChunkX(),
			sample.cameraChunkZ()
		);
	}

	private static void updateTransientSurfaceRecovery(SurfaceSample sample) {
		if (sample != null && sample.available() && sample.candidate() && isFiniteSurface(sample.surfaceY())) {
			transientSurfaceRecoveryTicks = readInt(TRANSIENT_SURFACE_RECOVERY_TICKS_PROPERTY, 12, 0, 80);
			transientSurfaceRecoveryChunkX = sample.cameraChunkX();
			transientSurfaceRecoveryChunkZ = sample.cameraChunkZ();
			transientSurfaceRecoverySurfaceY = sample.surfaceY();
			return;
		}
		if (transientSurfaceRecoveryTicks > 0) {
			transientSurfaceRecoveryTicks--;
			return;
		}
		transientSurfaceRecoverySurfaceY = Integer.MIN_VALUE;
	}

	private static void updateFeatureTransitionState(SurfaceSample sample) {
		boolean requestTransition = shouldRequestFeatureTransitionMask(sample);
		if (requestTransition) {
			featureTransitionTicks++;
			nonFeatureTransitionTicks = 0;
			FeaturePresentationMode sampleMode = FeaturePresentationMode.fromReason(sample.reason());
			if (featureTransitionReason.isBlank() || sampleMode != featurePresentationMode || sample.reason().equals(featureTransitionReason)) {
				featureTransitionReason = sample.reason();
				featurePresentationMode = sampleMode;
				pendingFeatureTransitionReason = "";
				featureReasonSwitchTicks = 0;
			} else if (sample.reason().equals(pendingFeatureTransitionReason)) {
				featureReasonSwitchTicks++;
				if (featureReasonSwitchTicks >= readInt(FEATURE_TRANSITION_REASON_SWITCH_TICKS_PROPERTY, 4, 1, 40)) {
					featureTransitionReason = sample.reason();
					featurePresentationMode = sampleMode;
					pendingFeatureTransitionReason = "";
					featureReasonSwitchTicks = 0;
				}
			} else {
				pendingFeatureTransitionReason = sample.reason();
				featureReasonSwitchTicks = 1;
			}
		} else {
			nonFeatureTransitionTicks++;
			featureTransitionTicks = 0;
			pendingFeatureTransitionReason = "";
			featureReasonSwitchTicks = 0;
		}

		int enterTicks = readInt(FEATURE_TRANSITION_ENTER_TICKS_PROPERTY, 1, 0, 40);
		int exitTicks = readInt(FEATURE_TRANSITION_EXIT_TICKS_PROPERTY, 80, 0, 200);
		if (!featureTransitionActive && requestTransition && featureTransitionTicks >= enterTicks) {
			featureTransitionActive = true;
		}
		if (featureTransitionActive && !requestTransition && nonFeatureTransitionTicks >= exitTicks) {
			featureTransitionActive = false;
			featureTransitionReason = "";
			pendingFeatureTransitionReason = "";
			featurePresentationMode = FeaturePresentationMode.NONE;
			featureReasonSwitchTicks = 0;
		}
		if (!featureTransitionActive && !requestTransition && nonFeatureTransitionTicks >= exitTicks) {
			featureTransitionReason = "";
			pendingFeatureTransitionReason = "";
			featurePresentationMode = FeaturePresentationMode.NONE;
			featureReasonSwitchTicks = 0;
		}
	}

	private static boolean sameReasonFamily(String left, String right) {
		if (left.equals(right)) {
			return true;
		}
		return isFeatureReason(left) && isFeatureReason(right);
	}

	private static boolean isFeatureReason(String reason) {
		return "dense-vegetation".equals(reason) || reason.startsWith("tall-local-features");
	}

	private static boolean isTransientSurfaceReason(String reason) {
		return "surface-height-unavailable".equals(reason)
			|| "surface-height-too-low".equals(reason);
	}

	private static boolean isFiniteSurface(int surfaceY) {
		return surfaceY > Integer.MIN_VALUE / 2;
	}

	private static void clearStickyFeatureTransition() {
		stickyFeatureTransitionTicks = 0;
		stickyFeatureTransitionReason = "";
		stickyFeatureTransitionChunkX = 0;
		stickyFeatureTransitionChunkZ = 0;
		stickyFeatureTransitionSurfaceY = Integer.MIN_VALUE;
	}

	private record SurfaceSample(
		boolean available,
		boolean candidate,
		boolean forceExit,
		String reason,
		int cameraY,
		int surfaceY,
		int cameraChunkX,
		int cameraChunkZ
	) {
		private static SurfaceSample inactive(String reason, int cameraChunkX, int cameraChunkZ) {
			return new SurfaceSample(false, false, false, reason, Integer.MIN_VALUE, Integer.MIN_VALUE, cameraChunkX, cameraChunkZ);
		}

		private static SurfaceSample of(boolean candidate, String reason, int cameraY, int surfaceY, int cameraChunkX, int cameraChunkZ) {
			return new SurfaceSample(true, candidate, false, reason, cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}

		private static SurfaceSample forceInactive(String reason, int cameraY, int surfaceY, int cameraChunkX, int cameraChunkZ) {
			return new SurfaceSample(true, false, true, reason, cameraY, surfaceY, cameraChunkX, cameraChunkZ);
		}
	}

	private record SurfaceState(boolean available, boolean active, boolean candidate, String reason, int cameraY, int surfaceY) {
		private static SurfaceState unavailable(String reason) {
			return new SurfaceState(false, false, false, reason, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}

		private String describe() {
			if (!available) {
				return "surfaceLod[unavailable, reason=" + reason + "]";
			}
			return "surfaceLod[active="
				+ active
				+ ", candidate="
				+ candidate
				+ ", reason="
				+ reason
				+ ", cameraY="
				+ cameraY
				+ ", surfaceY="
				+ surfaceY
				+ "]";
		}
	}

	private enum FeaturePresentationMode {
		NONE("none"),
		VEGETATION("vegetation"),
		STRUCTURE("structure"),
		MIXED("mixed");

		private final String id;

		FeaturePresentationMode(String id) {
			this.id = id;
		}

		private boolean requiresAccuratePresentation() {
			return this != NONE;
		}

		private static FeaturePresentationMode fromReason(String reason) {
			if (reason == null || reason.isBlank()) {
				return NONE;
			}
			if ("dense-vegetation".equals(reason) || "tall-local-features:vegetation".equals(reason)) {
				return VEGETATION;
			}
			if ("tall-local-features:structure".equals(reason)) {
				return STRUCTURE;
			}
			if ("tall-local-features:mixed".equals(reason)) {
				return MIXED;
			}
			return NONE;
		}
	}
}
