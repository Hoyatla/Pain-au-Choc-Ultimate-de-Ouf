package fr.hoyatla.pauc.lod;

public final class PauCLodShaderSafety {
	private static final String SUPPRESS_SHADER_CLOUDS_PROPERTY = "pauc.lod.suppressShaderClouds";
	private static final String SUPPRESS_SHADER_TRANSLUCENT_PROPERTY = "pauc.lod.suppressShaderTransparent";
	private static final String SUPPRESS_NATIVE_DH_SHADOW_PROPERTY = "pauc.lod.suppressNativeDhShadow";

	private PauCLodShaderSafety() {
	}

	public static boolean shouldSuppressShaderClouds() {
		return readBoolean(SUPPRESS_SHADER_CLOUDS_PROPERTY, false) && activeShaderLodRange();
	}

	public static boolean shouldSuppressShaderTransparentLodPass() {
		return readBoolean(SUPPRESS_SHADER_TRANSLUCENT_PROPERTY, false) && activeShaderLodRange();
	}

	public static boolean shouldSuppressNativeDhShadowPass() {
		return readBoolean(SUPPRESS_NATIVE_DH_SHADOW_PROPERTY, false) && activeShaderLodRange();
	}

	private static boolean activeShaderLodRange() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		return PauCLodShaderContext.isShaderPackInUse()
			&& (PauCLodClientSettings.isLodsEnabled() || range != null && range.enabled());
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}
}
