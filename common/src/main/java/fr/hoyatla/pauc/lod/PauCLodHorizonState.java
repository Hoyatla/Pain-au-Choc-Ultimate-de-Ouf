package fr.hoyatla.pauc.lod;

public final class PauCLodHorizonState {
	private static final String EXTEND_VANILLA_FOG_PROPERTY = "pauc.lod.extendVanillaFog";
	private static final String FOG_START_BEFORE_TARGET_PROPERTY = "pauc.lod.vanillaFogStartBeforeTarget";
	private static final String FOG_END_MARGIN_PROPERTY = "pauc.lod.vanillaFogEndMargin";
	private static final String SHADERLESS_FOG_START_BEFORE_TARGET_PROPERTY = "pauc.lod.shaderlessFogStartBeforeTarget";
	private static final String SHADERLESS_FOG_END_MARGIN_PROPERTY = "pauc.lod.shaderlessFogEndMargin";
	private static final String PHOTON_SHADOW_FOG_START_BACKOFF_PROPERTY = "pauc.lod.photonShadowFogStartBackoff";
	private static final String PHOTON_SHADOW_FOG_END_MARGIN_PROPERTY = "pauc.lod.photonShadowFogEndMargin";
	private static final String PHOTON_SHADOW_FOG_FADE_SPAN_PROPERTY = "pauc.lod.photonShadowFogFadeSpan";
	// Native shader presentation keeps the SAME clear-visible LOD distance as shaderless (fog starts the same number of
	// chunks before the horizon — the player's set distance must look identical with shaders on/off), but uses a wider
	// fog END margin so the fade span stays long and there is no hard visible strip at the round-horizon boundary.
	private static final int DEFAULT_FOG_START_BEFORE_TARGET_CHUNKS = 4;
	private static final int DEFAULT_FOG_END_MARGIN_CHUNKS = 6;
	private static final int DEFAULT_SHADERLESS_FOG_START_BEFORE_TARGET_CHUNKS = 4;
	private static final int DEFAULT_SHADERLESS_FOG_END_MARGIN_CHUNKS = 2;
	private static final int DEFAULT_PHOTON_SHADOW_FOG_START_BACKOFF_CHUNKS = 1;
	private static final int DEFAULT_PHOTON_SHADOW_FOG_END_MARGIN_CHUNKS = 2;
	private static final int DEFAULT_PHOTON_SHADOW_FOG_FADE_SPAN_CHUNKS = 6;
	private static volatile PauCLodRange currentRange = PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS);
	private static volatile boolean vanillaFogExtended;
	private static volatile int shaderFallbackVisualEndChunk = -1;

	private PauCLodHorizonState() {
	}

	public static void update(PauCLodRange range) {
		currentRange = range == null ? PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS) : range;
		vanillaFogExtended = currentRange.enabled() && readBoolean(EXTEND_VANILLA_FOG_PROPERTY, true);
	}

	public static void reset() {
		currentRange = PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS);
		vanillaFogExtended = false;
		shaderFallbackVisualEndChunk = -1;
		PauCLodFogState.reset();
	}

	public static PauCLodRange currentRange() {
		return currentRange;
	}

	public static boolean shouldExtendVanillaFog() {
		return vanillaFogExtended && currentRange.enabled();
	}

	public static void setShaderFallbackVisualEndChunk(int visualEndChunk) {
		shaderFallbackVisualEndChunk = visualEndChunk;
	}

	public static int visualEndChunk() {
		PauCLodRange range = currentRange;
		if (range == null || !range.enabled()) {
			return 0;
		}

		int defaultVisualEndChunk = range.roundHorizonEndChunk();
		if (!PauCLodShaderContext.isFallbackActive()) {
			return defaultVisualEndChunk;
		}

		int overrideVisualEndChunk = shaderFallbackVisualEndChunk;
		if (overrideVisualEndChunk < range.lodEndChunk()) {
			return defaultVisualEndChunk;
		}
		return clamp(overrideVisualEndChunk, range.lodEndChunk(), defaultVisualEndChunk);
	}

	public static float vanillaFogStartBlocks() {
		return vanillaFogStartChunk() * 16.0F;
	}

	public static int vanillaFogStartChunk() {
		PauCLodRange range = currentRange;
		if (shouldUsePhotonShadowDrivenFog(range)) {
			return photonShadowFogStartChunk(range);
		}
		int startBeforeTarget = PauCLodShaderContext.isShaderPackInUse()
			? readInt(FOG_START_BEFORE_TARGET_PROPERTY, DEFAULT_FOG_START_BEFORE_TARGET_CHUNKS, 1, 8)
			: readInt(SHADERLESS_FOG_START_BEFORE_TARGET_PROPERTY, DEFAULT_SHADERLESS_FOG_START_BEFORE_TARGET_CHUNKS, 1, 12);
		int fogStartChunk = Math.max(range.lodStartChunk(), visualEndChunk() - startBeforeTarget);
		return fogStartChunk;
	}

	public static float vanillaFogEndBlocks() {
		return vanillaFogEndChunk() * 16.0F;
	}

	public static int vanillaFogEndChunk() {
		PauCLodRange range = currentRange;
		if (shouldUsePhotonShadowDrivenFog(range)) {
			return photonShadowFogEndChunk(range);
		}
		int endMargin = PauCLodShaderContext.isShaderPackInUse()
			? readInt(FOG_END_MARGIN_PROPERTY, DEFAULT_FOG_END_MARGIN_CHUNKS, -8, 64)
			: readInt(SHADERLESS_FOG_END_MARGIN_PROPERTY, DEFAULT_SHADERLESS_FOG_END_MARGIN_CHUNKS, 0, 32);
		return Math.max(visualEndChunk() + endMargin, vanillaFogStartChunk() + 1);
	}

	public static String describeVisualPolicy() {
		return "visualPolicy[shader="
			+ PauCLodShaderContext.isShaderPackInUse()
			+ ", vanillaFog="
			+ vanillaFogStartChunk()
			+ "-"
			+ vanillaFogEndChunk()
			+ ", extended="
			+ shouldExtendVanillaFog()
			+ "]";
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

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static boolean shouldUsePhotonShadowDrivenFog(PauCLodRange range) {
		return range != null
			&& range.enabled()
			&& PauCPhotonShadowCoverage.isPhotonShadowCoverageActive()
			&& PauCPhotonShadowCoverage.requestedShadowDistanceChunks() > 0;
	}

	private static int photonShadowFogStartChunk(PauCLodRange range) {
		int shadowDistanceChunks = PauCPhotonShadowCoverage.effectiveShadowCoverageRadiusChunks();
		int backoffChunks = readInt(
			PHOTON_SHADOW_FOG_START_BACKOFF_PROPERTY,
			DEFAULT_PHOTON_SHADOW_FOG_START_BACKOFF_CHUNKS,
			0,
			8
		);
		int maxStartChunk = Math.max(2, visualEndChunk() - 1);
		int minStartChunk = Math.max(2, range.lodStartChunk());
		return clamp(Math.max(minStartChunk, shadowDistanceChunks - backoffChunks), minStartChunk, maxStartChunk);
	}

	private static int photonShadowFogEndChunk(PauCLodRange range) {
		int startChunk = photonShadowFogStartChunk(range);
		int endMargin = readInt(
			PHOTON_SHADOW_FOG_END_MARGIN_PROPERTY,
			DEFAULT_PHOTON_SHADOW_FOG_END_MARGIN_CHUNKS,
			0,
			24
		);
		int fadeSpan = readInt(
			PHOTON_SHADOW_FOG_FADE_SPAN_PROPERTY,
			DEFAULT_PHOTON_SHADOW_FOG_FADE_SPAN_CHUNKS,
			1,
			24
		);
		int maxEndChunk = Math.max(startChunk + 1, visualEndChunk() + endMargin);
		return clamp(startChunk + fadeSpan, startChunk + 1, maxEndChunk);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
