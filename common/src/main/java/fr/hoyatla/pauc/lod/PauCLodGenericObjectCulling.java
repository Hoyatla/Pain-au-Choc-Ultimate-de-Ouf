package fr.hoyatla.pauc.lod;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCLodGenericObjectCulling {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.cull.genericFeatureExternalization";
	private static final String PROFILE_CACHE_MS_PROPERTY = "pauc.lod.cull.genericFeatureCacheMs";
	private static final String FEATURE_LOGGING_PROPERTY = "pauc.lod.cull.genericFeatureLogging";
	private static final String VEGETATION_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.genericVegetationExtraChunks";
	private static final String STRUCTURE_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.genericStructureExtraChunks";
	private static final String VEGETATION_HOLD_MS_PROPERTY = "pauc.lod.cull.genericVegetationHoldMs";
	private static final String STRUCTURE_HOLD_MS_PROPERTY = "pauc.lod.cull.genericStructureHoldMs";
	private static final String VEGETATION_HOLD_MARGIN_BLOCKS_PROPERTY = "pauc.lod.cull.genericVegetationHoldMarginBlocks";
	private static final String STRUCTURE_HOLD_MARGIN_BLOCKS_PROPERTY = "pauc.lod.cull.genericStructureHoldMarginBlocks";
	private static final String TRANSITION_EXTRA_CHUNKS_PROPERTY = "pauc.lod.cull.genericTransitionExtraChunks";
	private static final String TRANSITION_HOLD_MS_PROPERTY = "pauc.lod.cull.genericTransitionHoldMs";
	private static final String TRANSITION_HOLD_MARGIN_BLOCKS_PROPERTY = "pauc.lod.cull.genericTransitionHoldMarginBlocks";
	private static final String GROUNDED_INNER_CHUNKS_PROPERTY = "pauc.lod.cull.genericGroundedInnerChunks";
	private static final String GROUNDED_FADE_CHUNKS_PROPERTY = "pauc.lod.cull.genericGroundedFadeChunks";
	private static final String GROUNDED_HOLD_MS_PROPERTY = "pauc.lod.cull.genericGroundedHoldMs";
	private static final String GROUNDED_RELEASE_HEIGHT_BLOCKS_PROPERTY = "pauc.lod.cull.genericGroundedReleaseHeightBlocks";
	private static final String GENERIC_OTHER_FEATURES_PROPERTY = "pauc.lod.cull.genericOtherFeatures";
	private static final int DEFAULT_VEGETATION_EXTRA_CHUNKS = 2;
	private static final int DEFAULT_STRUCTURE_EXTRA_CHUNKS = 1;
	private static final int DEFAULT_VEGETATION_HOLD_MS = 1_400;
	private static final int DEFAULT_STRUCTURE_HOLD_MS = 1_100;
	private static final int DEFAULT_VEGETATION_HOLD_MARGIN_BLOCKS = 48;
	private static final int DEFAULT_STRUCTURE_HOLD_MARGIN_BLOCKS = 32;
	private static final int DEFAULT_TRANSITION_EXTRA_CHUNKS = 1;
	private static final int DEFAULT_TRANSITION_HOLD_MS = 1_800;
	private static final int DEFAULT_TRANSITION_HOLD_MARGIN_BLOCKS = 24;
	private static final int DEFAULT_GROUNDED_INNER_CHUNKS = 4;
	private static final int DEFAULT_GROUNDED_FADE_CHUNKS = 2;
	private static final int DEFAULT_GROUNDED_HOLD_MS = 2_000;
	private static final int DEFAULT_GROUNDED_RELEASE_HEIGHT_BLOCKS = 40;
	private static final int DEFAULT_PROFILE_CACHE_MS = 500;
	private static final String[] VEGETATION_KEYWORDS = {
		"tree", "trees", "leaf", "leaves", "foliage", "forest", "bush", "grass", "flower", "vine",
		"oak", "spruce", "birch", "jungle", "mangrove", "acacia", "cherry", "sapling", "crop", "plant"
	};
	private static final String[] STRUCTURE_KEYWORDS = {
		"struct", "structure", "building", "house", "roof", "wall", "tower", "village", "bridge",
		"fort", "ruin", "hut", "stairs", "beacon", "castle", "temple"
	};
	private static final String[] DEBUG_KEYWORDS = {
		"debug", "chunkbox", "test", "cyanchunkbox", "magentagroup", "redgroup"
	};
	private static final Map<Long, CachedGenericObjectProfile> PROFILE_CACHE = new ConcurrentHashMap<>();
	private static final Map<Long, Long> HOLD_UNTIL_BY_GROUP = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOGGED_FEATURE_KEYS = new ConcurrentHashMap<>();
	private static volatile long lastHoldPruneAtMs;

	private PauCLodGenericObjectCulling() {
	}

	public static boolean shouldCullNearGenericLod(IDhApiRenderableBoxGroup boxGroup) {
		if (boxGroup == null || !readBoolean(ENABLED_PROPERTY, true)) {
			return false;
		}
		boolean shaderPackInUse = PauCLodShaderContext.isShaderPackInUse();
		boolean featureTransitionMask = PauCLodNearClipOverride.shouldUseFeatureTransitionMask();
		if ((shaderPackInUse && !featureTransitionMask) || !PauCLodNearClipOverride.shouldKeepLodsUnderVanilla()) {
			clearHold(boxGroup.getId());
			return false;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			clearHold(boxGroup.getId());
			return false;
		}

		long now = System.currentTimeMillis();
		GenericObjectProfile profile = captureProfile(boxGroup, now);
		if (profile.feature() == GenericObjectFeature.CLOUD || profile.feature() == GenericObjectFeature.DEBUG) {
			clearHold(profile.objectId());
			return false;
		}
		if (profile.feature() == GenericObjectFeature.OTHER && !readBoolean(GENERIC_OTHER_FEATURES_PROPERTY, false)) {
			return PauCLodRenderCulling.shouldCullGenericLodObject(
				profile.objectId(),
				profile.resourcePath(),
				profile.originX(),
				profile.originY(),
				profile.originZ()
			);
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return false;
		}

		Vec3 camera = cameraPosition(minecraft);
		boolean groundedFeatureMaskActive = shouldUseGroundedFeatureMask(minecraft);
		if (shouldCullGroundedFeatureNearPlayer(profile.objectId(), profile.feature(), profile.minX(), profile.maxX(), profile.minZ(), profile.maxZ(), minecraft, camera, now, groundedFeatureMaskActive)) {
			pruneHolds(now);
			logFeature(profile);
			return true;
		}

		double maxDistance = (range.vanillaRenderDistanceChunks() + extraChunks(profile.feature()) + transitionExtraChunks(profile.feature(), featureTransitionMask)) * 16.0D;
		double distanceSqr = horizontalDistanceSqrToAabb(profile.minX(), profile.maxX(), profile.minZ(), profile.maxZ(), camera.x, camera.z);
		if (distanceSqr <= maxDistance * maxDistance) {
			hold(profile.objectId(), now, holdMs(profile.feature(), featureTransitionMask));
			pruneHolds(now);
			logFeature(profile);
			return true;
		}

		Long holdUntil = HOLD_UNTIL_BY_GROUP.get(profile.objectId());
		if (holdUntil != null && now <= holdUntil) {
			double holdDistance = maxDistance + holdMarginBlocks(profile.feature(), featureTransitionMask);
			if (distanceSqr <= holdDistance * holdDistance) {
				return true;
			}
		}

		clearHold(profile.objectId());
		pruneHolds(now);
		return false;
	}

	public static boolean shouldCullGroundedFeatureNearPlayer(long objectId, String resourceLocationPath, double originX, double originZ) {
		Minecraft minecraft = Minecraft.getInstance();
		return shouldCullGroundedFeatureNearPlayer(
			objectId,
			classifyFeature(resourceLocationPath == null ? "" : resourceLocationPath.toLowerCase(Locale.ROOT), 0, 0.0D, 0.0D, 0.0D),
			originX,
			originX,
			originZ,
			originZ,
			minecraft,
			cameraPosition(minecraft),
			System.currentTimeMillis(),
			shouldUseGroundedFeatureMask(minecraft)
		);
	}

	public static boolean isGroundedFeatureMaskCandidate(String resourceLocationPath) {
		return isGroundedFeatureMaskCandidate(classifyFeature(resourceLocationPath == null ? "" : resourceLocationPath.toLowerCase(Locale.ROOT), 0, 0.0D, 0.0D, 0.0D));
	}

	private static GenericObjectProfile captureProfile(IDhApiRenderableBoxGroup boxGroup, long now) {
		long objectId = boxGroup.getId();
		String resourceKey = resourceKey(boxGroup);
		int boxCount = boxGroup.size();
		CachedGenericObjectProfile cached = PROFILE_CACHE.get(objectId);
		int cacheMs = readInt(PROFILE_CACHE_MS_PROPERTY, DEFAULT_PROFILE_CACHE_MS, 0, 5_000);
		if (cached != null
			&& now - cached.capturedAtMs() <= cacheMs
			&& cached.boxCount() == boxCount
			&& cached.resourceKey().equals(resourceKey)) {
			return cached.profile();
		}

		GenericObjectProfile profile = buildProfile(boxGroup, resourceKey);
		PROFILE_CACHE.put(objectId, new CachedGenericObjectProfile(profile, boxCount, resourceKey, now));
		return profile;
	}

	private static GenericObjectProfile buildProfile(IDhApiRenderableBoxGroup boxGroup, String resourceKey) {
		DhApiVec3d origin = boxGroup.getOriginBlockPos();
		double originX = origin != null ? origin.x : 0.0D;
		double originY = origin != null ? origin.y : 0.0D;
		double originZ = origin != null ? origin.z : 0.0D;
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		boolean relativeCoordinates = false;
		boolean relativeCoordinatesResolved = false;

		for (DhApiRenderableBox box : boxGroup) {
			if (box == null || box.minPos == null || box.maxPos == null) {
				continue;
			}

			if (!relativeCoordinatesResolved) {
				relativeCoordinates = shouldTreatBoxCoordinatesAsRelative(origin, box);
				relativeCoordinatesResolved = true;
			}

			double anchorX = relativeCoordinates ? originX : 0.0D;
			double anchorY = relativeCoordinates ? originY : 0.0D;
			double anchorZ = relativeCoordinates ? originZ : 0.0D;
			double boxMinX = Math.min(box.minPos.x, box.maxPos.x) + anchorX;
			double boxMinY = Math.min(box.minPos.y, box.maxPos.y) + anchorY;
			double boxMinZ = Math.min(box.minPos.z, box.maxPos.z) + anchorZ;
			double boxMaxX = Math.max(box.minPos.x, box.maxPos.x) + anchorX;
			double boxMaxY = Math.max(box.minPos.y, box.maxPos.y) + anchorY;
			double boxMaxZ = Math.max(box.minPos.z, box.maxPos.z) + anchorZ;
			minX = Math.min(minX, boxMinX);
			minY = Math.min(minY, boxMinY);
			minZ = Math.min(minZ, boxMinZ);
			maxX = Math.max(maxX, boxMaxX);
			maxY = Math.max(maxY, boxMaxY);
			maxZ = Math.max(maxZ, boxMaxZ);
		}

		if (!Double.isFinite(minX) || !Double.isFinite(maxX)) {
			minX = originX;
			maxX = originX;
			minY = originY;
			maxY = originY;
			minZ = originZ;
			maxZ = originZ;
		}

		double footprintX = Math.max(0.0D, maxX - minX);
		double footprintZ = Math.max(0.0D, maxZ - minZ);
		double height = Math.max(0.0D, maxY - minY);
		int boxCount = Math.max(0, boxGroup.size());
		GenericObjectFeature feature = classifyFeature(resourceKey, boxCount, footprintX, footprintZ, height);
		String resourcePath = boxGroup.getResourceLocationPath();
		return new GenericObjectProfile(
			boxGroup.getId(),
			resourcePath == null ? "" : resourcePath,
			resourceKey,
			feature,
			minX,
			minY,
			minZ,
			maxX,
			maxY,
			maxZ,
			originX,
			originY,
			originZ,
			boxCount,
			footprintX,
			footprintZ,
			height
		);
	}

	private static GenericObjectFeature classifyFeature(String resourceKey, int boxCount, double footprintX, double footprintZ, double height) {
		if (containsAny(resourceKey, "cloud")) {
			return GenericObjectFeature.CLOUD;
		}
		if (containsAny(resourceKey, DEBUG_KEYWORDS)) {
			return GenericObjectFeature.DEBUG;
		}
		if (containsAny(resourceKey, VEGETATION_KEYWORDS)) {
			return GenericObjectFeature.VEGETATION;
		}
		if (containsAny(resourceKey, STRUCTURE_KEYWORDS)) {
			return GenericObjectFeature.STRUCTURE;
		}

		double maxFootprint = Math.max(footprintX, footprintZ);
		double minFootprint = Math.min(footprintX, footprintZ);
		if (boxCount >= 10 && height >= 6.0D && height <= 48.0D && maxFootprint <= 40.0D && minFootprint <= 24.0D) {
			return GenericObjectFeature.VEGETATION;
		}
		if (boxCount >= 3 && height >= 4.0D && maxFootprint >= 6.0D && (footprintX * footprintZ >= 48.0D || height >= 10.0D)) {
			return GenericObjectFeature.STRUCTURE;
		}
		return GenericObjectFeature.OTHER;
	}

	private static boolean shouldTreatBoxCoordinatesAsRelative(DhApiVec3d origin, DhApiRenderableBox box) {
		if (origin == null || box == null || box.minPos == null || box.maxPos == null) {
			return false;
		}

		double centerX = (box.minPos.x + box.maxPos.x) * 0.5D;
		double centerZ = (box.minPos.z + box.maxPos.z) * 0.5D;
		double originMagnitude = Math.max(Math.abs(origin.x), Math.abs(origin.z));
		double rawMagnitude = Math.max(Math.abs(centerX), Math.abs(centerZ));
		if (originMagnitude > 256.0D && rawMagnitude <= 256.0D) {
			return true;
		}
		return Math.abs(centerX - origin.x) > 256.0D || Math.abs(centerZ - origin.z) > 256.0D;
	}

	private static void logFeature(GenericObjectProfile profile) {
		if (!readBoolean(FEATURE_LOGGING_PROPERTY, false)) {
			return;
		}

		String featureKey = profile.feature().name() + ":" + profile.resourceKey();
		if (LOGGED_FEATURE_KEYS.putIfAbsent(featureKey, Boolean.TRUE) != null) {
			return;
		}

		LOGGER.info(
			"PauC classified generic LOD group {} as {}: boxes={}, height={}, footprint={}x{}, bounds=({}, {}) -> ({}, {}).",
			profile.resourceKey(),
			profile.feature(),
			profile.boxCount(),
			roundOneDecimal(profile.height()),
			roundOneDecimal(profile.footprintX()),
			roundOneDecimal(profile.footprintZ()),
			roundOneDecimal(profile.minX()),
			roundOneDecimal(profile.minZ()),
			roundOneDecimal(profile.maxX()),
			roundOneDecimal(profile.maxZ())
		);
	}

	private static int extraChunks(GenericObjectFeature feature) {
		return switch (feature) {
			case VEGETATION -> readInt(VEGETATION_EXTRA_CHUNKS_PROPERTY, DEFAULT_VEGETATION_EXTRA_CHUNKS, 0, 4);
			case STRUCTURE -> readInt(STRUCTURE_EXTRA_CHUNKS_PROPERTY, DEFAULT_STRUCTURE_EXTRA_CHUNKS, 0, 4);
			default -> 1;
		};
	}

	private static int transitionExtraChunks(GenericObjectFeature feature, boolean featureTransitionMask) {
		if (!featureTransitionMask || (feature != GenericObjectFeature.VEGETATION && feature != GenericObjectFeature.STRUCTURE)) {
			return 0;
		}
		return readInt(TRANSITION_EXTRA_CHUNKS_PROPERTY, DEFAULT_TRANSITION_EXTRA_CHUNKS, 0, 3);
	}

	private static int holdMs(GenericObjectFeature feature, boolean featureTransitionMask) {
		int baseHoldMs = switch (feature) {
			case VEGETATION -> readInt(VEGETATION_HOLD_MS_PROPERTY, DEFAULT_VEGETATION_HOLD_MS, 0, 5_000);
			case STRUCTURE -> readInt(STRUCTURE_HOLD_MS_PROPERTY, DEFAULT_STRUCTURE_HOLD_MS, 0, 5_000);
			default -> 900;
		};
		if (!featureTransitionMask || (feature != GenericObjectFeature.VEGETATION && feature != GenericObjectFeature.STRUCTURE)) {
			return baseHoldMs;
		}
		return Math.max(baseHoldMs, readInt(TRANSITION_HOLD_MS_PROPERTY, DEFAULT_TRANSITION_HOLD_MS, 0, 5_000));
	}

	private static int holdMarginBlocks(GenericObjectFeature feature, boolean featureTransitionMask) {
		int baseMargin = switch (feature) {
			case VEGETATION -> readInt(VEGETATION_HOLD_MARGIN_BLOCKS_PROPERTY, DEFAULT_VEGETATION_HOLD_MARGIN_BLOCKS, 0, 128);
			case STRUCTURE -> readInt(STRUCTURE_HOLD_MARGIN_BLOCKS_PROPERTY, DEFAULT_STRUCTURE_HOLD_MARGIN_BLOCKS, 0, 128);
			default -> 32;
		};
		if (!featureTransitionMask || (feature != GenericObjectFeature.VEGETATION && feature != GenericObjectFeature.STRUCTURE)) {
			return baseMargin;
		}
		return baseMargin + readInt(TRANSITION_HOLD_MARGIN_BLOCKS_PROPERTY, DEFAULT_TRANSITION_HOLD_MARGIN_BLOCKS, 0, 96);
	}

	private static boolean shouldCullGroundedFeatureNearPlayer(
		long objectId,
		GenericObjectFeature feature,
		double minX,
		double maxX,
		double minZ,
		double maxZ,
		Minecraft minecraft,
		Vec3 camera,
		long now,
		boolean groundedFeatureMaskActive
	) {
		if (!isGroundedFeatureMaskCandidate(feature)
			|| PauCLodShaderContext.isShaderPackInUse()
			|| minecraft == null
			|| minecraft.level == null
			|| camera == null
			|| !groundedFeatureMaskActive) {
			return false;
		}

		double innerDistance = readInt(GROUNDED_INNER_CHUNKS_PROPERTY, DEFAULT_GROUNDED_INNER_CHUNKS, 0, 12) * 16.0D;
		double fadeDistance = innerDistance + readInt(GROUNDED_FADE_CHUNKS_PROPERTY, DEFAULT_GROUNDED_FADE_CHUNKS, 0, 8) * 16.0D;
		double distanceSqr = horizontalDistanceSqrToAabb(minX, maxX, minZ, maxZ, camera.x, camera.z);
		if (distanceSqr <= innerDistance * innerDistance) {
			hold(objectId, now, Math.max(holdMs(feature, true), readInt(GROUNDED_HOLD_MS_PROPERTY, DEFAULT_GROUNDED_HOLD_MS, 0, 5_000)));
			return true;
		}

		Long holdUntil = HOLD_UNTIL_BY_GROUP.get(objectId);
		return holdUntil != null && now <= holdUntil && distanceSqr <= fadeDistance * fadeDistance;
	}

	private static boolean shouldUseGroundedFeatureMask(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null) {
			return false;
		}
		if (!PauCLodNearClipOverride.shouldKeepLodsUnderVanilla()) {
			return false;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || player.isFallFlying() || player.getAbilities().flying) {
			return false;
		}

		BlockPos pos = player.blockPosition();
		int surfaceY = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		int releaseHeight = readInt(GROUNDED_RELEASE_HEIGHT_BLOCKS_PROPERTY, DEFAULT_GROUNDED_RELEASE_HEIGHT_BLOCKS, 8, 128);
		return player.getY() <= surfaceY + releaseHeight;
	}

	private static boolean isGroundedFeatureMaskCandidate(GenericObjectFeature feature) {
		return feature == GenericObjectFeature.VEGETATION || feature == GenericObjectFeature.STRUCTURE;
	}

	private static String resourceKey(IDhApiRenderableBoxGroup boxGroup) {
		String namespace = boxGroup.getResourceLocationNamespace();
		String path = boxGroup.getResourceLocationPath();
		String safeNamespace = namespace == null ? "" : namespace;
		String safePath = path == null ? "" : path;
		return (safeNamespace + ":" + safePath).toLowerCase(Locale.ROOT);
	}

	private static boolean containsAny(String haystack, String... needles) {
		if (haystack == null || haystack.isEmpty()) {
			return false;
		}
		for (String needle : needles) {
			if (needle != null && !needle.isEmpty() && haystack.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private static void hold(long objectId, long now, int holdMs) {
		if (objectId == 0L || holdMs <= 0) {
			return;
		}
		HOLD_UNTIL_BY_GROUP.put(objectId, now + holdMs);
	}

	private static void clearHold(long objectId) {
		if (objectId != 0L) {
			HOLD_UNTIL_BY_GROUP.remove(objectId);
		}
	}

	private static void pruneHolds(long now) {
		if (HOLD_UNTIL_BY_GROUP.isEmpty() || now - lastHoldPruneAtMs < 5_000L) {
			return;
		}
		lastHoldPruneAtMs = now;
		HOLD_UNTIL_BY_GROUP.entrySet().removeIf(entry -> entry.getValue() < now);
	}

	private static Vec3 cameraPosition(Minecraft minecraft) {
		if (minecraft == null) {
			return Vec3.ZERO;
		}
		if (minecraft.gameRenderer != null) {
			return minecraft.gameRenderer.getMainCamera().getPosition();
		}
		if (minecraft.player != null) {
			return minecraft.player.position();
		}
		return Vec3.ZERO;
	}

	private static double horizontalDistanceSqrToAabb(double minX, double maxX, double minZ, double maxZ, double cameraX, double cameraZ) {
		double dx = 0.0D;
		if (cameraX < minX) {
			dx = minX - cameraX;
		} else if (cameraX > maxX) {
			dx = cameraX - maxX;
		}

		double dz = 0.0D;
		if (cameraZ < minZ) {
			dz = minZ - cameraZ;
		} else if (cameraZ > maxZ) {
			dz = cameraZ - maxZ;
		}
		return dx * dx + dz * dz;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
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

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double roundOneDecimal(double value) {
		return Math.round(value * 10.0D) / 10.0D;
	}

	private enum GenericObjectFeature {
		CLOUD,
		VEGETATION,
		STRUCTURE,
		DEBUG,
		OTHER
	}

	private record CachedGenericObjectProfile(GenericObjectProfile profile, int boxCount, String resourceKey, long capturedAtMs) {
	}

	private record GenericObjectProfile(
		long objectId,
		String resourcePath,
		String resourceKey,
		GenericObjectFeature feature,
		double minX,
		double minY,
		double minZ,
		double maxX,
		double maxY,
		double maxZ,
		double originX,
		double originY,
		double originZ,
		int boxCount,
		double footprintX,
		double footprintZ,
		double height
	) {
	}
}
