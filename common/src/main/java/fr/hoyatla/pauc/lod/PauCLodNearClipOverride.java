package fr.hoyatla.pauc.lod;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

public final class PauCLodNearClipOverride {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.nearClipOverride";
	private static final String GLOBAL_RENDER_UTIL_PROPERTY = "pauc.lod.globalRenderUtilClip";
	private static final String INSET_CHUNKS_PROPERTY = "pauc.lod.nearClipInsetChunks";
	private static final String SHADER_OFF_INSET_CHUNKS_PROPERTY = "pauc.lod.shaderOffNearClipInsetChunks";
	private static final String SHADER_OFF_MOVING_INSET_CHUNKS_PROPERTY = "pauc.lod.shaderOffMovingNearClipInsetChunks";
	private static final String SHADER_OFF_STARTUP_INSET_CHUNKS_PROPERTY = "pauc.lod.shaderOffStartupNearClipInsetChunks";
	private static final int DEFAULT_INSET_CHUNKS = 0;
	private static final int DEFAULT_SHADER_OFF_INSET_CHUNKS = 3;
	private static final int DEFAULT_SHADER_OFF_MOVING_INSET_CHUNKS = 4;
	private static final int DEFAULT_SHADER_OFF_STARTUP_INSET_CHUNKS = 4;
	private static final long SHADER_OFF_STARTUP_WINDOW_MS = 8_000L;
	private static final long SHADER_OFF_MOVING_HOLD_MS = 3_000L;
	private static final double MOVING_SPEED_THRESHOLD = 0.08D;
	private static volatile float lastLoggedOriginal = Float.NaN;
	private static volatile float lastLoggedApplied = Float.NaN;
	private static volatile int lastLoggedLodStartChunk = -1;
	private static volatile int lastLoggedInsetChunks = -1;
	private static volatile long lastLogMs;
	private static volatile long shaderOffRangeStartedAtMs;
	private static volatile String shaderOffRangeKey = "";
	private static volatile long movingInsetHoldUntilMs;

	private PauCLodNearClipOverride() {
	}

	public static float overrideNearClipBlocks(float originalNearClipBlocks) {
		if (!shouldOverrideCurrentRange()) {
			return originalNearClipBlocks;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		int insetChunks = insetChunks();
		float appliedNearClipBlocks = Math.max(originalNearClipBlocks, boundaryClipBlocks(range, insetChunks));
		logOverride(originalNearClipBlocks, appliedNearClipBlocks, range, insetChunks);
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
		return readBoolean(ENABLED_PROPERTY, true) && range != null && range.enabled();
	}

	public static float currentBoundaryClipBlocks(float fallbackBlocks) {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (!readBoolean(ENABLED_PROPERTY, true) || range == null || !range.enabled()) {
			return fallbackBlocks;
		}

		return Math.max(fallbackBlocks, boundaryClipBlocks(range, insetChunks()));
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

	private static void logOverride(float originalNearClipBlocks, float appliedNearClipBlocks, PauCLodRange range, int insetChunks) {
		long now = System.currentTimeMillis();
		boolean changed = Math.abs(appliedNearClipBlocks - lastLoggedApplied) >= 1.0F
			|| range.lodStartChunk() != lastLoggedLodStartChunk
			|| insetChunks != lastLoggedInsetChunks;
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
		lastLogMs = now;
		LOGGER.info(
			"PauC DH near-clip override: original={} blocks, applied={} blocks, lodStart={} chunks, inset={} chunks, {}",
			roundOneDecimal(originalNearClipBlocks),
			roundOneDecimal(appliedNearClipBlocks),
			range.lodStartChunk(),
			insetChunks,
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

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float roundOneDecimal(float value) {
		return Math.round(value * 10.0F) / 10.0F;
	}
}
