package fr.hoyatla.pauc.lod;

import java.util.Locale;

public final class PauCLodShaderProfiles {
	private static final Profile COMPLEMENTARY_PROFILE = new Profile(
		Family.COMPLEMENTARY,
		"complementary",
		true,
		false,
		false,
		"0.10",
		"0.07",
		"0.08",
		"0.09",
		"0.12",
		"32.0",
		"88.0",
		"0.72",
		"0.42",
		"0.46",
		"vec3(0.62, 0.74, 0.86)",
		"0.04",
		"144.0",
		"544.0",
		"0.14",
		"0.36",
		"0.44",
		false,
		false
	);
	private static final Profile RETHINKING_PROFILE = new Profile(
		Family.RETHINKING,
		"rethinking",
		true,
		false,
		false,
		"0.11",
		"0.08",
		"0.10",
		"0.10",
		"0.12",
		"96.0",
		"96.0",
		"0.50",
		"0.40",
		"0.38",
		"vec3(0.56, 0.68, 0.82)",
		"0.00",
		"144.0",
		"544.0",
		"0.08",
		"0.22",
		"0.30",
		false,
		false
	);
	private static final Profile BSL_PROFILE = new Profile(
		Family.BSL,
		"bsl",
		false,
		true,
		false,
		"0.05",
		"0.04",
		"0.05",
		"0.12",
		"0.12",
		"32.0",
		"128.0",
		"0.60",
		"0.46",
		"0.62",
		"vec3(0.23, 0.36, 0.50)",
		"0.00",
		"144.0",
		"528.0",
		"0.17",
		"0.40",
		"0.48",
		false,
		false
	);
	private static final Profile BLISS_PROFILE = new Profile(
		Family.BLISS,
		"bliss",
		true,
		false,
		true,
		"1.00",
		"1.00",
		"1.00",
		"1.00",
		"1.00",
		"96.0",
		"192.0",
		"0.84",
		"0.22",
		"0.44",
		"vec3(0.50, 0.62, 0.76)",
		"0.00",
		"176.0",
		"576.0",
		"0.12",
		"0.34",
		"0.42",
		false,
		false
	);
	private static final Profile PHOTON_PROFILE = new Profile(
		Family.PHOTON,
		"photon",
		false,
		false,
		false,
		"0.03",
		"0.04",
		"0.03",
		"0.08",
		"0.05",
		"24.0",
		"160.0",
		"0.52",
		"0.24",
		"0.34",
		"vec3(0.54, 0.68, 0.82)",
		"0.04",
		"160.0",
		"560.0",
		"0.12",
		"0.30",
		"0.40",
		true,
		true
	);
	private static final Profile SOLAS_PROFILE = new Profile(
		Family.SOLAS,
		"solas",
		false,
		false,
		true,
		"0.16",
		"0.12",
		"0.18",
		"0.18",
		"0.12",
		"40.0",
		"80.0",
		"0.82",
		"0.34",
		"0.42",
		"vec3(0.64, 0.76, 0.88)",
		"0.06",
		"144.0",
		"560.0",
		"0.15",
		"0.36",
		"0.46",
		false,
		false
	);
	private static final Profile SILDURS_ENHANCED_PROFILE = new Profile(
		Family.SILDURS_ENHANCED,
		"sildurs-enhanced-default",
		false,
		false,
		false,
		"0.08",
		"0.06",
		"0.08",
		"0.10",
		"0.10",
		"56.0",
		"112.0",
		"0.68",
		"0.30",
		"0.36",
		"vec3(0.44, 0.58, 0.74)",
		"0.00",
		"160.0",
		"544.0",
		"0.10",
		"0.28",
		"0.36",
		false,
		false
	);
	private static final Profile SILDURS_VIBRANT_PROFILE = new Profile(
		Family.SILDURS_VIBRANT,
		"sildurs-vibrant",
		false,
		false,
		false,
		"0.09",
		"0.07",
		"0.09",
		"0.11",
		"0.11",
		"64.0",
		"128.0",
		"0.74",
		"0.28",
		"0.34",
		"vec3(0.46, 0.60, 0.78)",
		"0.00",
		"176.0",
		"560.0",
		"0.12",
		"0.30",
		"0.38",
		false,
		false
	);
	private static final Profile GENERIC_PROFILE = new Profile(
		Family.GENERIC,
		"generic",
		false,
		false,
		false,
		"0.16",
		"0.10",
		"0.18",
		"0.18",
		"0.12",
		"48.0",
		"96.0",
		"0.86",
		"0.36",
		"0.52",
		"vec3(0.50, 0.64, 0.78)",
		"0.06",
		"160.0",
		"560.0",
		"0.16",
		"0.38",
		"0.46",
		false,
		false
	);
	private static volatile String cachedFamilyKey = "";
	private static volatile Family cachedFamily = Family.GENERIC;

	private PauCLodShaderProfiles() {
	}

	public static Profile current() {
		return profile(currentFamily());
	}

	public static Family currentFamily() {
		return familyForKey(PauCLodShaderContext.shaderPackKey());
	}

	public static Family familyForPackName(String packName) {
		return familyForKey(packName);
	}

	public static Family familyForKey(String key) {
		if (key == null) {
			return Family.GENERIC;
		}

		String lower = key.toLowerCase(Locale.ROOT);
		String cachedKey = cachedFamilyKey;
		if (lower.equals(cachedKey)) {
			return cachedFamily;
		}

		Family family = familyForLowerKey(lower);
		cachedFamilyKey = lower;
		cachedFamily = family;
		return family;
	}

	private static Family familyForLowerKey(String lower) {
		if (lower.contains("bliss")) {
			return Family.BLISS;
		}
		if (lower.contains("bsl")) {
			return Family.BSL;
		}
		if (lower.contains("photon")) {
			return Family.PHOTON;
		}
		if (lower.contains("sildur")) {
			if (lower.contains("enhanced default") || lower.contains("enhanceddefault")) {
				return Family.SILDURS_ENHANCED;
			}
			if (lower.contains("vibrant")) {
				return Family.SILDURS_VIBRANT;
			}
		}
		if (lower.contains("rethinking")) {
			return Family.RETHINKING;
		}
		if (lower.contains("complementary")) {
			return Family.COMPLEMENTARY;
		}
		if (lower.contains("solas")) {
			return Family.SOLAS;
		}
		return Family.GENERIC;
	}

	public static Profile profile(Family family) {
		return switch (family == null ? Family.GENERIC : family) {
			case COMPLEMENTARY -> COMPLEMENTARY_PROFILE;
			case RETHINKING -> RETHINKING_PROFILE;
			case BSL -> BSL_PROFILE;
			case BLISS -> BLISS_PROFILE;
			case PHOTON -> PHOTON_PROFILE;
			case SOLAS -> SOLAS_PROFILE;
			case SILDURS_ENHANCED -> SILDURS_ENHANCED_PROFILE;
			case SILDURS_VIBRANT -> SILDURS_VIBRANT_PROFILE;
			case GENERIC -> GENERIC_PROFILE;
		};
	}

	public static boolean allowsRuntimeDhTerrainPath(Family family) {
		return switch (family == null ? Family.GENERIC : family) {
			case PHOTON, SOLAS, SILDURS_ENHANCED, SILDURS_VIBRANT -> true;
			default -> false;
		};
	}

	public static String describeCurrent() {
		return current().describe();
	}

	public enum Family {
		COMPLEMENTARY,
		RETHINKING,
		BSL,
		BLISS,
		PHOTON,
		SOLAS,
		SILDURS_ENHANCED,
		SILDURS_VIBRANT,
		GENERIC
	}

	public record Profile(
		Family family,
		String id,
		boolean directColorPresentation,
		boolean albedoPresentation,
		boolean albedoWaterOnly,
		String doFogMix,
		String rgbFogMix,
		String commonFogMix,
		String borderAlphaFogMix,
		String blissBorderFogMix,
		String nearBlendEndExtra,
		String farFogWidth,
		String farFogStrength,
		String waterGradientStrength,
		String waterEndFogStrength,
		String waterDeepTone,
		String waterTransparencyStrength,
		String lodShadowJoinNear,
		String lodShadowJoinFar,
		String lodShadowNearStrength,
		String lodShadowSideStrength,
		String lodShadowMax,
		boolean photonCloudDepthPatch,
		boolean photonCloudHistoryPatch
	) {
		public boolean shouldApplyDirectColorPresentation(boolean waterProgram) {
			return directColorPresentation && (!preservesNativeDhPresentation() || waterProgram);
		}

		public boolean shouldApplyAlbedoPresentation(boolean waterProgram) {
			return (albedoPresentation || (albedoWaterOnly && waterProgram))
				&& (!preservesNativeDhPresentation() || waterProgram || family == Family.BSL);
		}

		// Packs that ship NO native DH terrain/shadow programs (verified: dhScan terrain/shadow=false) need PauC's own
		// LOD fog + shadow synthesis, exactly like GENERIC - otherwise the LOD field ends in a hard horizon cut (no
		// map-closing fog) and no vanilla-shadow junction forms. Sildur's Vibrant/Enhanced are such packs; Photon/Solas
		// ship their own DH programs and keep the native path. This gives each pack its own path (no cross-conflict).
		public boolean lacksNativeDhPrograms() {
			return family == Family.GENERIC
				|| family == Family.SILDURS_VIBRANT
				|| family == Family.SILDURS_ENHANCED;
		}

		public boolean preservesNativeDhPresentation() {
			return !lacksNativeDhPrograms();
		}

		public boolean shouldAttenuateNativeFog() {
			return lacksNativeDhPrograms();
		}

		public boolean shouldApplyNativeWaterTonePatch() {
			return lacksNativeDhPrograms() || family == Family.PHOTON;
		}

		public boolean shouldApplySyntheticLodShadow() {
			return lacksNativeDhPrograms();
		}

		// Sildur ships no DH shadow program, so the boundary-shadow path (which needs a native DH shadow source) can
		// produce nothing - the synthetic LOD shadow is the only way to form the LOD<->vanilla shadow junction here.
		// Default it ON for these packs only (Photon/Solas/GENERIC keep their existing opt-in default).
		public boolean defaultSyntheticLodShadowEnabled() {
			return family == Family.SILDURS_VIBRANT || family == Family.SILDURS_ENHANCED;
		}

		public String describe() {
			return "shaderRuntime[id="
				+ id
				+ ", fog="
				+ farFogStrength
				+ "/"
				+ farFogWidth
				+ ", water="
				+ waterGradientStrength
				+ "/"
				+ waterEndFogStrength
				+ ", shadow="
				+ lodShadowNearStrength
				+ "+"
				+ lodShadowSideStrength
				+ "/"
				+ lodShadowMax
				+ "]";
		}
	}
}
