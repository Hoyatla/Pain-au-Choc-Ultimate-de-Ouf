package fr.hoyatla.pauc.lod;

import java.util.Locale;

public enum PauCShaderProfileId {
	SHADER_OFF("shader-off"),
	GENERIC_COMPAT("generic-compat"),
	PHOTON_COMPAT("photon-compat"),
	SOLAS_COMPAT("solas-compat"),
	PAUC_NATIVE("pauc-native");

	private final String id;

	PauCShaderProfileId(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public boolean isCompatibilityProfile() {
		return this == GENERIC_COMPAT || this == PHOTON_COMPAT || this == SOLAS_COMPAT;
	}

	public static PauCShaderProfileId fromManifestValue(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return GENERIC_COMPAT;
		}

		String normalized = rawValue.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		return switch (normalized) {
			case "shader-off", "off" -> SHADER_OFF;
			case "generic", "generic-compat", "compat", "compat-generic" -> GENERIC_COMPAT;
			case "photon", "photon-compat", "compat-photon" -> PHOTON_COMPAT;
			case "solas", "solas-compat", "compat-solas" -> SOLAS_COMPAT;
			default -> GENERIC_COMPAT;
		};
	}

	public static PauCShaderProfileId forExternalFamily(PauCLodShaderProfiles.Family family) {
		return switch (family == null ? PauCLodShaderProfiles.Family.GENERIC : family) {
			case PHOTON -> PHOTON_COMPAT;
			case SOLAS -> SOLAS_COMPAT;
			case PAUC -> PAUC_NATIVE;
			default -> GENERIC_COMPAT;
		};
	}
}
