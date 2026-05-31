package fr.hoyatla.pauc;

public final class PauCIdentity {
	public static final String MOD_ID = "paucultimate";
	public static final String MOD_NAME = "PauC_Ultimate_de_Ouf";
	public static final String CORE_NAME = "PauC Core";
	public static final String SHADER_NAME = "PauC Shader";
	public static final String LEGACY_IRIS_MOD_ID = "iris";
	public static final String LEGACY_OCULUS_MOD_ID = "oculus";
	public static final String LEGACY_SODIUM_MOD_ID = "sodium";
	public static final String CORE_OPTIONS_FILE = "paucultimate-core-options.json";
	public static final String CORE_FINGERPRINT_FILE = "paucultimate-core-fingerprint.json";
	public static final String SHADER_CONFIG_FILE = "paucultimate-shader.properties";
	public static final String SHADER_UPDATE_FILE = "paucultimate-shader-update-info.json";

	private PauCIdentity() {
	}

	public static boolean isProvidedLegacyModId(String modId) {
		return LEGACY_IRIS_MOD_ID.equals(modId)
			|| LEGACY_OCULUS_MOD_ID.equals(modId)
			|| LEGACY_SODIUM_MOD_ID.equals(modId);
	}
}
