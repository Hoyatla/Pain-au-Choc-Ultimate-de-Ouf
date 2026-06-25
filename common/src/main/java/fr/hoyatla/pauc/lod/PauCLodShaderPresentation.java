package fr.hoyatla.pauc.lod;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class PauCLodShaderPresentation {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final int DEFAULT_PRESENTATION_DISTANCE_CHUNKS = PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS;
	private static final int DEFAULT_PRESENTATION_MARGIN_CHUNKS = 0;
	private static final int DEFAULT_FALLBACK_SHADER_FOG_START_MARGIN_CHUNKS = 0;
	private static final int DEFAULT_FALLBACK_SHADER_FOG_END_MARGIN_CHUNKS = 1;
	private static final int MIN_PRESENTATION_DISTANCE_CHUNKS = 2;
	private static final int MAX_PRESENTATION_DISTANCE_CHUNKS = 256;
	private static final String ENABLED_PROPERTY = "pauc.lod.shaderPresentation";
	private static final String DISTANCE_PROPERTY = "pauc.lod.shaderPresentationDistance";
	private static final String FOG_NEUTRALIZATION_PROPERTY = "pauc.lod.shaderFogNeutralization";
	private static final String NATIVE_SHADER_FOG_NEUTRALIZATION_PROPERTY = "pauc.lod.nativeShaderFogNeutralization";
	private static final String FOG_START_MARGIN_PROPERTY = "pauc.lod.shaderFogStartMargin";
	private static final String FOG_END_MARGIN_PROPERTY = "pauc.lod.shaderFogEndMargin";
	private static final String LATE_FALLBACK_RENDER_PROPERTY = "pauc.lod.shaderFallbackLateRender";
	private static final String EXTEND_SHADER_CAMERA_FAR_PROPERTY = "pauc.lod.extendShaderCameraFar";
	private static final String EXTEND_LATE_FALLBACK_SHADER_CAMERA_FAR_PROPERTY = "pauc.lod.extendLateFallbackShaderCameraFar";
	private static final String ALLOW_PRESENTATION_BEYOND_RANGE_PROPERTY = "pauc.lod.shaderPresentationBeyondRange";
	private static long lastFogNeutralizationLogMs;
	private static long lastLateFallbackRenderLogMs;

	private PauCLodShaderPresentation() {
	}

	public static boolean isPresentationEnabled() {
		return readBoolean(ENABLED_PROPERTY, true);
	}

	public static int shaderRenderDistanceBlocks(int realDhRenderDistanceBlocks) {
		if (!isPresentationEnabled()) {
			return realDhRenderDistanceBlocks;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			return realDhRenderDistanceBlocks;
		}

		int realDhRenderDistanceChunks = blocksToChunksCeil(realDhRenderDistanceBlocks);
		int visualEndChunk = PauCLodHorizonState.visualEndChunk();
		int minimumDistanceChunks = Math.max(visualEndChunk, realDhRenderDistanceChunks);
		int fallbackDistanceChunks = Math.max(
			DEFAULT_PRESENTATION_DISTANCE_CHUNKS,
			visualEndChunk + DEFAULT_PRESENTATION_MARGIN_CHUNKS
		);
		int presentationDistanceChunks = readInt(
			DISTANCE_PROPERTY,
			fallbackDistanceChunks,
			Math.max(MIN_PRESENTATION_DISTANCE_CHUNKS, minimumDistanceChunks),
			maxPresentationDistanceChunks(minimumDistanceChunks)
		);

		return chunksToBlocks(presentationDistanceChunks);
	}

	public static int shaderCameraFarBlocks(int realCameraFarBlocks) {
		if (!isPresentationEnabled()) {
			return realCameraFarBlocks;
		}

		if (shouldLateRenderFallbackLods()) {
			if (!readBoolean(EXTEND_LATE_FALLBACK_SHADER_CAMERA_FAR_PROPERTY, true)) {
				return realCameraFarBlocks;
			}
			return Math.max(realCameraFarBlocks, shaderPresentationDistanceBlocks(realCameraFarBlocks));
		}

		if (!readBoolean(EXTEND_SHADER_CAMERA_FAR_PROPERTY, PauCLodShaderContext.isFallbackActive())) {
			return realCameraFarBlocks;
		}

		return Math.max(realCameraFarBlocks, shaderPresentationDistanceBlocks(realCameraFarBlocks));
	}

	public static float shaderFogStartBlocks(float realFogStartBlocks) {
		if (!shouldNeutralizeShaderFog()) {
			return realFogStartBlocks;
		}

		PauCLodRange range = currentRange();
		int startChunk = PauCLodHorizonState.visualEndChunk() + readInt(
			FOG_START_MARGIN_PROPERTY,
			DEFAULT_FALLBACK_SHADER_FOG_START_MARGIN_CHUNKS,
			0,
			MAX_PRESENTATION_DISTANCE_CHUNKS
		);
		return chunksToBlocks(startChunk);
	}

	public static float shaderFogEndBlocks(float realFogEndBlocks) {
		if (!shouldNeutralizeShaderFog()) {
			return realFogEndBlocks;
		}

		PauCLodRange range = currentRange();
		float startBlocks = shaderFogStartBlocks(realFogEndBlocks);
		int endChunk = PauCLodHorizonState.visualEndChunk() + readInt(
			FOG_END_MARGIN_PROPERTY,
			DEFAULT_FALLBACK_SHADER_FOG_END_MARGIN_CHUNKS,
			0,
			MAX_PRESENTATION_DISTANCE_CHUNKS
		);
		float endBlocks = chunksToBlocks(Math.max(endChunk, blocksToChunksCeil(startBlocks) + 1));
		logFogNeutralization(range, startBlocks, endBlocks);
		return endBlocks;
	}

	public static float shaderFogDensity(float realFogDensity) {
		return shouldNeutralizeShaderFog() ? -1.0F : realFogDensity;
	}

	public static boolean shouldNeutralizeFallbackShaderFog() {
		PauCLodRange range = currentRange();
		return isPresentationEnabled()
			&& readBoolean(FOG_NEUTRALIZATION_PROPERTY, true)
			&& !readBoolean(LATE_FALLBACK_RENDER_PROPERTY, defaultLateFallbackRender())
			&& PauCLodShaderContext.isFallbackActive()
			&& range.enabled();
	}

	private static boolean shouldNeutralizeShaderFog() {
		PauCLodRange range = currentRange();
		if (!isPresentationEnabled() || !readBoolean(FOG_NEUTRALIZATION_PROPERTY, true) || !range.enabled()) {
			return false;
		}

		return shouldNeutralizeFallbackShaderFog()
			|| shouldNeutralizeNativeShaderFog();
	}

	private static boolean shouldNeutralizeNativeShaderFog() {
		if (!PauCLodShaderContext.isDhNativeShaderActive()) {
			return false;
		}

		if (PauCLodShaderProfiles.currentFamily() != PauCLodShaderProfiles.Family.PAUC) {
			return false;
		}

		return readBoolean(NATIVE_SHADER_FOG_NEUTRALIZATION_PROPERTY, true);
	}

	public static boolean shouldLateRenderFallbackLods() {
		return isPresentationEnabled()
			&& readBoolean(LATE_FALLBACK_RENDER_PROPERTY, defaultLateFallbackRender())
			&& PauCLodShaderContext.isFallbackActive()
			&& currentRange().enabled();
	}

	public static void logLateFallbackRender() {
		PauCLodRange range = currentRange();
		if (!range.enabled()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastLateFallbackRenderLogMs < 5000L) {
			return;
		}

		lastLateFallbackRenderLogMs = now;
		LOGGER.info(
			"PauC late shader fallback LOD render: drawing LODs after shader final pass, shaderFar={} chunks, {}",
			blocksToChunksCeil(shaderPresentationDistanceBlocks(chunksToBlocks(range.vanillaRenderDistanceChunks()))),
			range.describe()
		);
	}

	public static int vanillaRenderDistanceBlocks() {
		return chunksToBlocks(currentRange().vanillaRenderDistanceChunks());
	}

	public static int lodStartDistanceBlocks() {
		PauCLodRange range = currentRange();
		return range.enabled() ? chunksToBlocks(range.lodStartChunk()) : 0;
	}

	public static int lodEndDistanceBlocks() {
		PauCLodRange range = currentRange();
		return range.enabled() ? chunksToBlocks(PauCLodHorizonState.visualEndChunk()) : 0;
	}

	public static int shaderPresentationEnabled() {
		return isPresentationEnabled() ? 1 : 0;
	}

	private static PauCLodRange currentRange() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		return range == null ? PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS) : range;
	}

	private static boolean defaultLateFallbackRender() {
		if (!PauCLodShaderContext.isFallbackActive()) {
			return false;
		}

		return switch (PauCLodShaderProfiles.currentFamily()) {
			case PHOTON, SOLAS -> true;
			default -> false;
		};
	}

	private static int shaderPresentationDistanceBlocks(int realRenderDistanceBlocks) {
		PauCLodRange range = currentRange();
		if (!range.enabled()) {
			return realRenderDistanceBlocks;
		}

		int realDistanceChunks = blocksToChunksCeil(realRenderDistanceBlocks);
		int visualEndChunk = PauCLodHorizonState.visualEndChunk();
		int minimumDistanceChunks = Math.max(visualEndChunk, realDistanceChunks);
		int fallbackDistanceChunks = Math.max(
			DEFAULT_PRESENTATION_DISTANCE_CHUNKS,
			visualEndChunk + DEFAULT_PRESENTATION_MARGIN_CHUNKS
		);
		int presentationDistanceChunks = readInt(
			DISTANCE_PROPERTY,
			fallbackDistanceChunks,
			Math.max(MIN_PRESENTATION_DISTANCE_CHUNKS, minimumDistanceChunks),
			maxPresentationDistanceChunks(minimumDistanceChunks)
		);

		return chunksToBlocks(presentationDistanceChunks);
	}

	private static int maxPresentationDistanceChunks(int minimumDistanceChunks) {
		if (readBoolean(ALLOW_PRESENTATION_BEYOND_RANGE_PROPERTY, false)) {
			return Math.max(minimumDistanceChunks, MAX_PRESENTATION_DISTANCE_CHUNKS);
		}
		return Math.max(MIN_PRESENTATION_DISTANCE_CHUNKS, minimumDistanceChunks);
	}

	private static int chunksToBlocks(int chunks) {
		return Math.max(0, chunks) * 16;
	}

	private static int blocksToChunksCeil(int blocks) {
		return Math.max(0, (blocks + 15) / 16);
	}

	private static int blocksToChunksCeil(float blocks) {
		return Math.max(0, (int) Math.ceil(blocks / 16.0F));
	}

	private static void logFogNeutralization(PauCLodRange range, float startBlocks, float endBlocks) {
		long now = System.currentTimeMillis();
		if (now - lastFogNeutralizationLogMs < 5000L) {
			return;
		}

		lastFogNeutralizationLogMs = now;
		LOGGER.info(
			"PauC shader fog neutralization: shaderFog={}..{} chunks, shaderFar={} chunks, {}",
			blocksToChunksCeil(startBlocks),
			blocksToChunksCeil(endBlocks),
			blocksToChunksCeil(shaderPresentationDistanceBlocks(chunksToBlocks(range.vanillaRenderDistanceChunks()))),
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
			return Math.max(min, Math.min(max, fallback));
		}

		try {
			int parsed = Integer.parseInt(rawValue);
			return Math.max(min, Math.min(max, parsed));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
