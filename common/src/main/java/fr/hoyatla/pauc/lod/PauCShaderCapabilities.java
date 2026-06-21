package fr.hoyatla.pauc.lod;

import java.util.Locale;

public record PauCShaderCapabilities(
	int schemaVersion,
	String packId,
	PauCShaderProfileId profileId,
	boolean supportsDhTerrain,
	boolean supportsDhShadow,
	boolean supportsTransitionFog,
	boolean supportsColoredLights,
	boolean supportsWeatherFog,
	boolean fromManifest,
	String source,
	String status
) {
	public static PauCShaderCapabilities shaderOff() {
		return new PauCShaderCapabilities(
			1,
			"(off)",
			PauCShaderProfileId.SHADER_OFF,
			false,
			false,
			false,
			false,
			false,
			false,
			"runtime-off",
			"shader-off"
		);
	}

	public static PauCShaderCapabilities externalPack(String packName, PauCLodShaderProfiles.Family family, String status) {
		return new PauCShaderCapabilities(
			1,
			normalizePackId(packName),
			PauCShaderProfileId.forExternalFamily(family),
			false,
			false,
			false,
			false,
			false,
			false,
			"implicit-compat",
			sanitizeStatus(status, "implicit-compat")
		);
	}

	public static PauCShaderCapabilities bundledPack(
		String packName,
		PauCLodShaderProfiles.Family family,
		boolean supportsDhTerrain,
		boolean supportsDhShadow,
		boolean supportsTransitionFog,
		boolean supportsColoredLights,
		boolean supportsWeatherFog
	) {
		return new PauCShaderCapabilities(
			1,
			normalizePackId(packName),
			PauCShaderProfileId.forExternalFamily(family),
			supportsDhTerrain,
			supportsDhShadow,
			supportsTransitionFog,
			supportsColoredLights,
			supportsWeatherFog,
			false,
			"bundled-profile",
			"bundled-integrated"
		);
	}

	public static PauCShaderCapabilities manifest(
		int schemaVersion,
		String packId,
		PauCShaderProfileId profileId,
		boolean supportsDhTerrain,
		boolean supportsDhShadow,
		boolean supportsTransitionFog,
		boolean supportsColoredLights,
		boolean supportsWeatherFog,
		String status
	) {
		return new PauCShaderCapabilities(
			Math.max(1, schemaVersion),
			normalizePackId(packId),
			profileId == null ? PauCShaderProfileId.GENERIC_COMPAT : profileId,
			supportsDhTerrain,
			supportsDhShadow,
			supportsTransitionFog,
			supportsColoredLights,
			supportsWeatherFog,
			true,
			"manifest",
			sanitizeStatus(status, "manifest-ok")
		);
	}

	public boolean isPaucNative() {
		return profileId == PauCShaderProfileId.PAUC_NATIVE;
	}

	public int statusCode() {
		return switch (status) {
			case "shader-off" -> 0;
			case "manifest-ok" -> 1;
			case "manifest-missing" -> 2;
			case "manifest-invalid" -> 3;
			case "manifest-io-error" -> 4;
			case "missing-shaders-dir" -> 5;
			case "implicit-compat" -> 6;
			case "runtime-off" -> 7;
			case "bundled-integrated" -> 8;
			default -> 15;
		};
	}

	public String describe() {
		return "shaderCaps[profile="
			+ profileId.id()
			+ ", packId="
			+ packId
			+ ", dhTerrain="
			+ supportsDhTerrain
			+ ", dhShadow="
			+ supportsDhShadow
			+ ", transitionFog="
			+ supportsTransitionFog
			+ ", coloredLights="
			+ supportsColoredLights
			+ ", weatherFog="
			+ supportsWeatherFog
			+ ", source="
			+ source
			+ ", status="
			+ status
			+ "]";
	}

	private static String normalizePackId(String value) {
		if (value == null || value.isBlank()) {
			return "unknown-pack";
		}

		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char character = Character.toLowerCase(value.charAt(i));
			if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
				builder.append(character);
			} else if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '-') {
				builder.append('-');
			}
		}
		while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '-') {
			builder.deleteCharAt(builder.length() - 1);
		}
		return builder.length() == 0 ? "unknown-pack" : builder.toString();
	}

	private static String sanitizeStatus(String value, String fallback) {
		String raw = value == null || value.isBlank() ? fallback : value;
		return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
	}
}
