package fr.hoyatla.pauc.lod;

public final class PauCLodHorizonState {
	private static final String EXTEND_VANILLA_FOG_PROPERTY = "pauc.lod.extendVanillaFog";
	private static final String FOG_START_BEFORE_TARGET_PROPERTY = "pauc.lod.vanillaFogStartBeforeTarget";
	private static final String FOG_END_MARGIN_PROPERTY = "pauc.lod.vanillaFogEndMargin";
	private static final String SHADERLESS_FOG_START_BEFORE_TARGET_PROPERTY = "pauc.lod.shaderlessFogStartBeforeTarget";
	private static final String SHADERLESS_FOG_END_MARGIN_PROPERTY = "pauc.lod.shaderlessFogEndMargin";
	// Native shader presentation needs a wider fog span than the fallback path to
	// avoid a hard visible strip at the round-horizon boundary.
	private static final int DEFAULT_FOG_START_BEFORE_TARGET_CHUNKS = 8;
	private static final int DEFAULT_FOG_END_MARGIN_CHUNKS = 2;
	private static final int DEFAULT_SHADERLESS_FOG_START_BEFORE_TARGET_CHUNKS = 4;
	private static final int DEFAULT_SHADERLESS_FOG_END_MARGIN_CHUNKS = 2;
	private static volatile PauCLodRange currentRange = PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS);
	private static volatile boolean vanillaFogExtended;

	private PauCLodHorizonState() {
	}

	public static void update(PauCLodRange range) {
		currentRange = range == null ? PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS) : range;
		vanillaFogExtended = currentRange.enabled() && readBoolean(EXTEND_VANILLA_FOG_PROPERTY, true);
	}

	public static void reset() {
		currentRange = PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS);
		vanillaFogExtended = false;
		PauCLodFogState.reset();
	}

	public static PauCLodRange currentRange() {
		return currentRange;
	}

	public static boolean shouldExtendVanillaFog() {
		return vanillaFogExtended && currentRange.enabled();
	}

	public static float vanillaFogStartBlocks() {
		return vanillaFogStartChunk() * 16.0F;
	}

	public static int vanillaFogStartChunk() {
		PauCLodRange range = currentRange;
		int startBeforeTarget = PauCLodShaderContext.isShaderPackInUse()
			? readInt(FOG_START_BEFORE_TARGET_PROPERTY, DEFAULT_FOG_START_BEFORE_TARGET_CHUNKS, 1, 8)
			: readInt(SHADERLESS_FOG_START_BEFORE_TARGET_PROPERTY, DEFAULT_SHADERLESS_FOG_START_BEFORE_TARGET_CHUNKS, 1, 12);
		int fogStartChunk = Math.max(range.lodStartChunk(), range.roundHorizonEndChunk() - startBeforeTarget);
		return fogStartChunk;
	}

	public static float vanillaFogEndBlocks() {
		return vanillaFogEndChunk() * 16.0F;
	}

	public static int vanillaFogEndChunk() {
		PauCLodRange range = currentRange;
		int endMargin = PauCLodShaderContext.isShaderPackInUse()
			? readInt(FOG_END_MARGIN_PROPERTY, DEFAULT_FOG_END_MARGIN_CHUNKS, -8, 64)
			: readInt(SHADERLESS_FOG_END_MARGIN_PROPERTY, DEFAULT_SHADERLESS_FOG_END_MARGIN_CHUNKS, 0, 32);
		return Math.max(range.roundHorizonEndChunk() + endMargin, vanillaFogStartChunk() + 1);
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

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
