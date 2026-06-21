package fr.hoyatla.pauc.lod;

import fr.hoyatla.pauc.shader.PauCShaders;

public final class PauCAnimatedTextureBudget {
	private static final String ENABLED_PROPERTY = "pauc.lod.animatedTextureBudget";
	private static final String TIER2_STRIDE_PROPERTY = "pauc.lod.animatedTextureBudgetStrideTier2";
	private static final String TIER3_STRIDE_PROPERTY = "pauc.lod.animatedTextureBudgetStrideTier3";

	private PauCAnimatedTextureBudget() {
	}

	public static boolean shouldAdvanceThisFrame() {
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return true;
		}

		int stride = activeStride();
		if (stride <= 1) {
			return true;
		}

		return Math.floorMod(PauCFrameSpikeAbsorber.frameSeq(), stride) == 0L;
	}

	public static String describeState() {
		return "animatedTextureBudget[stride=" + activeStride() + "]";
	}

	private static int activeStride() {
		boolean shaderActive = PauCShaders.isShaderPackInUse();
		int sceneTier = PauCVillagePerformanceDiagnostics.projectedScenePressureTier();
		double absorberPressure = PauCFrameSpikeAbsorber.pressure01();
		if (sceneTier >= 3 || absorberPressure >= 0.85D) {
			return readInt(TIER3_STRIDE_PROPERTY, shaderActive ? 3 : 2, 1, 4);
		}
		if (sceneTier >= 2 || (shaderActive && sceneTier >= 1) || absorberPressure >= 0.50D) {
			return readInt(TIER2_STRIDE_PROPERTY, 2, 1, 4);
		}
		return 1;
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
