package fr.hoyatla.pauc.lod;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public final class PauCLodNearClipOverride {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.nearClipOverride";
	private static final String SHADER_ENABLED_PROPERTY = "pauc.lod.shaderNearClipOverride";
	private static final String GLOBAL_RENDER_UTIL_PROPERTY = "pauc.lod.globalRenderUtilClip";
	private static final String KEEP_UNDER_VANILLA_PROPERTY = "pauc.lod.keepLodsUnderVanilla";
	private static final String AUTO_KEEP_UNDER_VANILLA_FOR_PAUC_SHADER_PROPERTY = "pauc.lod.autoKeepLodsUnderVanillaForPauCShader";
	private static final String UNDER_VANILLA_CLIP_BLOCKS_PROPERTY = "pauc.lod.underVanillaClipBlocks";
	private static final String SHADER_OFF_GROUNDED_OVERLAP_CLIP_PROPERTY = "pauc.lod.shaderOffGroundedOverlapClip";
	private static final String SHADER_OFF_GROUNDED_OVERLAP_CLIP_CHUNKS_PROPERTY = "pauc.lod.shaderOffGroundedOverlapClipChunks";
	private static final String SHADER_OFF_GROUNDED_OVERLAP_HEIGHT_PROPERTY = "pauc.lod.shaderOffGroundedOverlapHeightBlocks";
	private static final String SHADER_OFF_GROUNDED_OVERLAP_RELEASE_MS_PROPERTY = "pauc.lod.shaderOffGroundedOverlapReleaseMs";
	private static final String FEATURE_TRANSITION_MASK_HOLD_MS_PROPERTY = "pauc.lod.featureTransitionMaskHoldMs";
	private static final String FEATURE_TRANSITION_LOCAL_CLIP_CHUNKS_PROPERTY = "pauc.lod.featureTransitionLocalClipChunks";
	private static final String TERRAIN_CONTINUITY_HOLD_MS_PROPERTY = "pauc.lod.terrainContinuityHoldMs";
	private static final String TERRAIN_CONTINUITY_LOCAL_CLIP_CHUNKS_PROPERTY = "pauc.lod.terrainContinuityLocalClipChunks";
	private static final String INSET_CHUNKS_PROPERTY = "pauc.lod.nearClipInsetChunks";
	private static final String SHADER_OFF_INSET_CHUNKS_PROPERTY = "pauc.lod.shaderOffNearClipInsetChunks";
	private static final String SHADER_OFF_MOVING_INSET_CHUNKS_PROPERTY = "pauc.lod.shaderOffMovingNearClipInsetChunks";
	private static final String SHADER_OFF_STARTUP_INSET_CHUNKS_PROPERTY = "pauc.lod.shaderOffStartupNearClipInsetChunks";
	private static final String SHADER_OFF_BOUNDARY_RECOVERY_CLIP_PROPERTY = "pauc.lod.shaderOffBoundaryRecoveryClip";
	private static final int DEFAULT_INSET_CHUNKS = 2;
	private static final int DEFAULT_SHADER_OFF_INSET_CHUNKS = 3;
	private static final int DEFAULT_SHADER_OFF_MOVING_INSET_CHUNKS = 4;
	private static final int DEFAULT_SHADER_OFF_STARTUP_INSET_CHUNKS = 4;
	private static final int DEFAULT_SHADER_OFF_GROUNDED_OVERLAP_HEIGHT_BLOCKS = 24;
	private static final int DEFAULT_SHADER_OFF_GROUNDED_OVERLAP_RELEASE_MS = 1_800;
	private static final int DEFAULT_FEATURE_TRANSITION_MASK_HOLD_MS = 3_500;
	private static final int DEFAULT_LOCAL_EXCLUSION_CLIP_CHUNKS = 6;
	private static final int DEFAULT_TERRAIN_CONTINUITY_HOLD_MS = 1_500;
	private static final int MAX_LOCAL_EXCLUSION_CLIP_CHUNKS = 6;
	private static final long SHADER_OFF_STARTUP_WINDOW_MS = 8_000L;
	private static final long SHADER_OFF_MOVING_HOLD_MS = 3_000L;
	private static final double MOVING_SPEED_THRESHOLD = 0.08D;
	private static volatile float lastLoggedOriginal = Float.NaN;
	private static volatile float lastLoggedApplied = Float.NaN;
	private static volatile int lastLoggedLodStartChunk = -1;
	private static volatile int lastLoggedInsetChunks = -1;
	private static volatile boolean lastLoggedUnderVanilla;
	private static volatile boolean lastLoggedGroundedOverlapClip;
	private static volatile long lastLogMs;
	private static volatile long shaderOffRangeStartedAtMs;
	private static volatile String shaderOffRangeKey = "";
	private static volatile long movingInsetHoldUntilMs;
	private static volatile long groundedOverlapClipHoldUntilMs;
	private static volatile long featureTransitionMaskHoldUntilMs;
	private static volatile String featureTransitionMaskReason = "";
	private static volatile long terrainContinuityHoldUntilMs;
	private static volatile String terrainContinuityHoldReason = "";

	private PauCLodNearClipOverride() {
	}

	public static float overrideNearClipBlocks(float originalNearClipBlocks) {
		if (!shouldOverrideCurrentRange()) {
			return originalNearClipBlocks;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		boolean underVanilla = shouldKeepLodsUnderVanilla();
		int insetChunks = underVanilla ? 0 : insetChunks();
		boolean groundedOverlapClip = shouldUseGroundedOverlapClip();
		boolean boundaryRecoveryClip = shouldUseBoundaryRecoveryClip();
		float underVanillaClipBlocks = underVanillaClipBlocks(range, groundedOverlapClip);
		float appliedNearClipBlocks = underVanilla
			? underVanillaClipBlocks
			: boundaryRecoveryClip
				? originalNearClipBlocks
			: Math.max(originalNearClipBlocks, boundaryClipBlocks(range, insetChunks));
		logOverride(originalNearClipBlocks, appliedNearClipBlocks, range, insetChunks, underVanilla, groundedOverlapClip, boundaryRecoveryClip);
		return appliedNearClipBlocks;
	}

	public static boolean shouldOverrideGlobalRenderUtil() {
		return readBoolean(GLOBAL_RENDER_UTIL_PROPERTY, false) && shouldOverrideCurrentRange();
	}

	public static float overrideGlobalRenderUtilNearClipBlocks(float originalNearClipBlocks) {
		return shouldOverrideGlobalRenderUtil() ? overrideNearClipBlocks(originalNearClipBlocks) : originalNearClipBlocks;
	}

	public static boolean shouldOverrideCurrentRange() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (!readBoolean(ENABLED_PROPERTY, true) || range == null || !range.enabled()) {
			return false;
		}
		if (PauCLodShaderContext.isShaderPackInUse()) {
			return shouldKeepLodsUnderVanilla() || readBoolean(SHADER_ENABLED_PROPERTY, false);
		}
		return true;
	}

	public static float currentBoundaryClipBlocks(float fallbackBlocks) {
		if (!shouldOverrideCurrentRange()) {
			return fallbackBlocks;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			return fallbackBlocks;
		}
		return Math.max(fallbackBlocks, boundaryClipBlocks(range, insetChunks()));
	}

	public static boolean shouldKeepLodsUnderVanilla() {
		return readBoolean(KEEP_UNDER_VANILLA_PROPERTY, false)
			|| shouldAutoKeepLodsUnderVanillaForCurrentShader()
			|| isNonPaucShaderActive();
	}

	// Option 1: under a non-PAUC shaderpack (Photon, Solas...) keep LODs rendered UNDER vanilla at all times so LOD
	// coverage tracks ACTUAL vanilla presence per-position (a LOD only vanishes when the vanilla chunk at its exact
	// position covers it; and it fills under the player when vanilla unloads at altitude). The coverage-completeness
	// guard (terrain-continuity / coverage-recovery hold) is deliberately NOT honoured for these packs in
	// underVanillaClipBlocks(), so it can no longer pull the LODs back to a distance clip. Trade-off: still-building
	// LODs may briefly show incomplete. This should NOT override true native DH shader paths, otherwise the shader's
	// own join behavior never reaches the screen.
	private static boolean isNonPaucShaderActive() {
		return PauCLodShaderContext.isShaderPackInUse()
			&& !PauCLodShaderContext.isDhNativeShaderActive()
			&& PauCLodShaderProfiles.currentFamily() != PauCLodShaderProfiles.Family.PAUC;
	}

	private static boolean shouldAutoKeepLodsUnderVanillaForCurrentShader() {
		if (!PauCLodShaderContext.isShaderPackInUse()
			|| !readBoolean(AUTO_KEEP_UNDER_VANILLA_FOR_PAUC_SHADER_PROPERTY, true)) {
			return false;
		}

		if (PauCLodShaderProfiles.currentFamily() != PauCLodShaderProfiles.Family.PAUC) {
			return false;
		}

		// Native PauC DH shaders already own the vanilla/LOD transition. Forcing the
		// "under vanilla" clip there creates a visible water/terrain cut band instead
		// of improving the junction.
		return PauCLodShaderContext.isFallbackActive();
	}

	public static void setFeatureTransitionMask(boolean active, String reason) {
		long now = System.currentTimeMillis();
		if (active) {
			int holdMs = readInt(FEATURE_TRANSITION_MASK_HOLD_MS_PROPERTY, DEFAULT_FEATURE_TRANSITION_MASK_HOLD_MS, 0, 10_000);
			featureTransitionMaskHoldUntilMs = now + holdMs;
			featureTransitionMaskReason = reason == null ? "" : reason;
			return;
		}

		if (now > featureTransitionMaskHoldUntilMs) {
			featureTransitionMaskReason = "";
		}
	}

	public static void setTerrainContinuityHold(boolean active, String reason) {
		long now = System.currentTimeMillis();
		if (active) {
			int holdMs = readInt(TERRAIN_CONTINUITY_HOLD_MS_PROPERTY, DEFAULT_TERRAIN_CONTINUITY_HOLD_MS, 0, 10_000);
			terrainContinuityHoldUntilMs = now + holdMs;
			terrainContinuityHoldReason = reason == null ? "" : reason;
			return;
		}

		if (now > terrainContinuityHoldUntilMs) {
			terrainContinuityHoldReason = "";
		}
	}

	public static boolean shouldUseFeatureTransitionMask() {
		if (!shouldKeepLodsUnderVanilla()) {
			return false;
		}
		// Keep feature masking independent from terrain continuity recovery. The
		// continuity hold only suppresses terrain near clipping to avoid map holes.
		return System.currentTimeMillis() <= featureTransitionMaskHoldUntilMs;
	}

	public static boolean shouldUseLocalExclusionClip() {
		return shouldKeepLodsUnderVanilla() && (shouldHoldTerrainContinuity() || shouldUseFeatureTransitionMask());
	}

	public static String featureTransitionMaskReason() {
		return shouldUseFeatureTransitionMask() ? featureTransitionMaskReason : "";
	}

	private static float boundaryClipBlocks(PauCLodRange range, int insetChunks) {
		int nearClipChunk = Math.max(PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, range.lodStartChunk() - insetChunks);
		return nearClipChunk * 16.0F;
	}

	private static int insetChunks() {
		if (PauCLodShaderContext.isShaderPackInUse()) {
			shaderOffRangeStartedAtMs = 0L;
			shaderOffRangeKey = "";
			return readInt(INSET_CHUNKS_PROPERTY, DEFAULT_INSET_CHUNKS, 0, 8);
		}

		int baseInset = readInt(SHADER_OFF_INSET_CHUNKS_PROPERTY, DEFAULT_SHADER_OFF_INSET_CHUNKS, 0, 8);
		int movingInset = readInt(SHADER_OFF_MOVING_INSET_CHUNKS_PROPERTY, DEFAULT_SHADER_OFF_MOVING_INSET_CHUNKS, 0, 8);
		int startupInset = readInt(SHADER_OFF_STARTUP_INSET_CHUNKS_PROPERTY, DEFAULT_SHADER_OFF_STARTUP_INSET_CHUNKS, 0, 8);
		int dynamicInset = baseInset;
		long now = System.currentTimeMillis();
		if (isMoving()) {
			movingInsetHoldUntilMs = now + SHADER_OFF_MOVING_HOLD_MS;
		}
		if (now <= movingInsetHoldUntilMs) {
			dynamicInset = Math.max(dynamicInset, movingInset);
		}
		if (isWithinShaderOffStartupWindow()) {
			dynamicInset = Math.max(dynamicInset, startupInset);
		}
		return clamp(dynamicInset, 0, 8);
	}

	private static boolean isMoving() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft != null ? minecraft.player : null;
		return player != null && player.getDeltaMovement().horizontalDistance() >= MOVING_SPEED_THRESHOLD;
	}

	private static boolean isWithinShaderOffStartupWindow() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			shaderOffRangeStartedAtMs = 0L;
			shaderOffRangeKey = "";
			return false;
		}

		String rangeKey = range.lodStartChunk() + ":" + range.lodEndChunk();
		long now = System.currentTimeMillis();
		if (!rangeKey.equals(shaderOffRangeKey)) {
			shaderOffRangeKey = rangeKey;
			shaderOffRangeStartedAtMs = now;
		}
		return now - shaderOffRangeStartedAtMs <= SHADER_OFF_STARTUP_WINDOW_MS;
	}

	private static float underVanillaClipBlocks(PauCLodRange range, boolean groundedOverlapClip) {
		float configuredClip = readFloat(UNDER_VANILLA_CLIP_BLOCKS_PROPERTY, 0.0F, 0.0F, 256.0F);
		if (range == null || !range.enabled()) {
			return configuredClip;
		}

		// Option 1 (non-PAUC shaders): keep LODs hard under vanilla at the configured clip (default 0) and do NOT
		// let the coverage-recovery / feature-transition holds pull them back to a distance clip.
		if (isNonPaucShaderActive()) {
			return configuredClip;
		}

		if (shouldHoldTerrainContinuity()) {
			return Math.max(
				configuredClip,
				localClipChunks(range, TERRAIN_CONTINUITY_LOCAL_CLIP_CHUNKS_PROPERTY) * 16.0F
			);
		}

		if (shouldUseFeatureTransitionMask()) {
			return Math.max(
				configuredClip,
				localClipChunks(range, FEATURE_TRANSITION_LOCAL_CLIP_CHUNKS_PROPERTY) * 16.0F
			);
		}

		if (!groundedOverlapClip) {
			return configuredClip;
		}

		int defaultClipChunks = localClipChunks(range, SHADER_OFF_GROUNDED_OVERLAP_CLIP_CHUNKS_PROPERTY);
		int maxClipChunks = maxLocalClipChunks(range);
		int clipChunks = readInt(SHADER_OFF_GROUNDED_OVERLAP_CLIP_CHUNKS_PROPERTY, defaultClipChunks, -1, maxClipChunks);
		if (clipChunks < 0) {
			clipChunks = defaultClipChunks;
		}
		clipChunks = clamp(clipChunks, PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, maxClipChunks);
		return Math.max(configuredClip, clipChunks * 16.0F);
	}

	private static int localClipChunks(PauCLodRange range, String property) {
		int maxClipChunks = maxLocalClipChunks(range);
		int fallback = clamp(DEFAULT_LOCAL_EXCLUSION_CLIP_CHUNKS, PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, maxClipChunks);
		return readInt(property, fallback, PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, maxClipChunks);
	}

	private static int maxLocalClipChunks(PauCLodRange range) {
		return Math.max(
			PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS,
			Math.min(MAX_LOCAL_EXCLUSION_CLIP_CHUNKS, Math.min(range.vanillaRenderDistanceChunks(), range.lodStartChunk()))
		);
	}

	private static boolean shouldUseGroundedOverlapClip() {
		if (!shouldKeepLodsUnderVanilla()) {
			groundedOverlapClipHoldUntilMs = 0L;
			return false;
		}
		if (shouldHoldTerrainContinuity()) {
			groundedOverlapClipHoldUntilMs = 0L;
			return false;
		}

		long now = System.currentTimeMillis();
		if (now <= groundedOverlapClipHoldUntilMs) {
			return true;
		}
		if (shouldUseFeatureTransitionMask()) {
			int releaseMs = readInt(
				SHADER_OFF_GROUNDED_OVERLAP_RELEASE_MS_PROPERTY,
				DEFAULT_SHADER_OFF_GROUNDED_OVERLAP_RELEASE_MS,
				0,
				10_000
			);
			groundedOverlapClipHoldUntilMs = now + releaseMs;
			return true;
		}
		if (PauCLodShaderContext.isShaderPackInUse()
			|| !readBoolean(SHADER_OFF_GROUNDED_OVERLAP_CLIP_PROPERTY, false)) {
			groundedOverlapClipHoldUntilMs = 0L;
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.gameRenderer == null) {
			return false;
		}

		Camera camera = minecraft.gameRenderer.getMainCamera();
		Vec3 cameraPos = camera.getPosition();
		int surfaceY = surfaceHeight(minecraft.level, cameraPos);
		int heightTolerance = readInt(
			SHADER_OFF_GROUNDED_OVERLAP_HEIGHT_PROPERTY,
			DEFAULT_SHADER_OFF_GROUNDED_OVERLAP_HEIGHT_BLOCKS,
			0,
			160
		);
		// Terrain continuity must not depend on the noisy near-feature transition state.
		boolean grounded = cameraPos.y <= surfaceY + heightTolerance;
		if (grounded) {
			int releaseMs = readInt(
				SHADER_OFF_GROUNDED_OVERLAP_RELEASE_MS_PROPERTY,
				DEFAULT_SHADER_OFF_GROUNDED_OVERLAP_RELEASE_MS,
				0,
				10_000
			);
			groundedOverlapClipHoldUntilMs = now + releaseMs;
			return true;
		}
		return now <= groundedOverlapClipHoldUntilMs;
	}

	private static boolean shouldUseBoundaryRecoveryClip() {
		return !shouldKeepLodsUnderVanilla()
			&& !PauCLodShaderContext.isShaderPackInUse()
			&& shouldHoldTerrainContinuity()
			&& readBoolean(SHADER_OFF_BOUNDARY_RECOVERY_CLIP_PROPERTY, true);
	}

	private static boolean shouldHoldTerrainContinuity() {
		long now = System.currentTimeMillis();
		if (now > terrainContinuityHoldUntilMs) {
			terrainContinuityHoldReason = "";
			return false;
		}
		return true;
	}

	private static int surfaceHeight(ClientLevel level, Vec3 cameraPos) {
		BlockPos pos = BlockPos.containing(cameraPos.x, cameraPos.y, cameraPos.z);
		int directSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
		if (directSurface > level.getMinBuildHeight() + 1) {
			return directSurface;
		}

		return pos.getY();
	}

	private static void logOverride(float originalNearClipBlocks, float appliedNearClipBlocks, PauCLodRange range, int insetChunks, boolean underVanilla, boolean groundedOverlapClip, boolean boundaryRecoveryClip) {
		long now = System.currentTimeMillis();
		boolean changed = Math.abs(appliedNearClipBlocks - lastLoggedApplied) >= 1.0F
			|| range.lodStartChunk() != lastLoggedLodStartChunk
			|| insetChunks != lastLoggedInsetChunks
			|| underVanilla != lastLoggedUnderVanilla
			|| groundedOverlapClip != lastLoggedGroundedOverlapClip;
		if (now - lastLogMs < 3_000L) {
			return;
		}
		if (!changed && now - lastLogMs < 15_000L) {
			return;
		}

		lastLoggedOriginal = originalNearClipBlocks;
		lastLoggedApplied = appliedNearClipBlocks;
		lastLoggedLodStartChunk = range.lodStartChunk();
		lastLoggedInsetChunks = insetChunks;
		lastLoggedUnderVanilla = underVanilla;
		lastLoggedGroundedOverlapClip = groundedOverlapClip;
		lastLogMs = now;
		LOGGER.info(
			"PauC DH near-clip override: mode={}, original={} blocks, applied={} blocks, lodStart={} chunks, inset={} chunks, groundedOverlapClip={}, featureTransition={}, continuityHold={}, {}",
			underVanilla
				? groundedOverlapClip ? "lods-under-vanilla-grounded-overlap-mask" : "lods-under-vanilla"
				: boundaryRecoveryClip ? "boundary-recovery" : "boundary-clip",
			roundOneDecimal(originalNearClipBlocks),
			roundOneDecimal(appliedNearClipBlocks),
			range.lodStartChunk(),
			insetChunks,
			groundedOverlapClip,
			featureTransitionMaskReason().isEmpty() ? "off" : featureTransitionMaskReason(),
			shouldHoldTerrainContinuity() ? (terrainContinuityHoldReason.isEmpty() ? "active" : terrainContinuityHoldReason) : "off",
			range.describe()
		);
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
			return clamp(Integer.parseInt(rawValue), min, max);
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
			return clamp(Float.parseFloat(rawValue), min, max);
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

	private static float roundOneDecimal(float value) {
		return Math.round(value * 10.0F) / 10.0F;
	}
}
