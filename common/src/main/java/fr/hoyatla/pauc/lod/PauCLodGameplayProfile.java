package fr.hoyatla.pauc.lod;

import java.util.Locale;

public final class PauCLodGameplayProfile {
	private static final String PROFILE_PROPERTY = "pauc.client.gameplayProfile";
	private static final String LEGACY_BETA_PROFILE_PROPERTY = "pauc.client.betaProfile";

	private PauCLodGameplayProfile() {
	}

	public static Profile current() {
		String rawValue = System.getProperty(PROFILE_PROPERTY);
		if (rawValue == null || rawValue.isBlank()) {
			rawValue = System.getProperty(LEGACY_BETA_PROFILE_PROPERTY, "auto");
		}

		String normalized = rawValue.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		return switch (normalized) {
			case "competitive", "competition", "fps", "vanilla", "no_shader", "no_shaders" -> Profile.COMPETITIVE;
			case "cinematic", "shader", "shaders", "quality", "visual" -> Profile.CINEMATIC;
			case "balanced", "safe" -> Profile.BALANCED;
			default -> PauCLodShaderContext.isShaderPackInUse() ? Profile.CINEMATIC : Profile.COMPETITIVE;
		};
	}

	public static String describe() {
		Profile profile = current();
		return "gameplayProfile[id="
			+ profile.id
			+ ", defaultTarget="
			+ profile.defaultTargetDistanceChunks
			+ ", recommendedVanilla<="
			+ profile.recommendedVanillaDistanceChunks
			+ ", autoVanilla="
			+ profile.autoReduceVanillaDistance
			+ ", minGeneration="
			+ profile.minimumGenerationRequestRate
			+ ", dynamicDistance="
			+ profile.allowDynamicTargetDistanceReduction
			+ ", dynamicGeneration="
			+ profile.allowDynamicGenerationReduction
			+ "]";
	}

	public static int defaultTargetDistanceChunks() {
		return current().defaultTargetDistanceChunks;
	}

	public static int recommendedVanillaDistanceChunks() {
		return current().recommendedVanillaDistanceChunks;
	}

	public static boolean autoReduceVanillaDistance() {
		return current().autoReduceVanillaDistance;
	}

	public static boolean allowDynamicTargetDistanceReduction() {
		return current().allowDynamicTargetDistanceReduction;
	}

	public static boolean allowDynamicGenerationReduction() {
		return current().allowDynamicGenerationReduction;
	}

	public static int minimumGenerationRequestRate() {
		return current().minimumGenerationRequestRate;
	}

	public static int defaultGenerationRequestRateLimit(int hardwareDefault) {
		Profile profile = current();
		int clampedHardwareDefault = Math.max(20, Math.min(384, hardwareDefault));
		return switch (profile) {
			case COMPETITIVE -> Math.max(profile.minimumGenerationRequestRate, clampedHardwareDefault);
			case BALANCED -> Math.max(profile.minimumGenerationRequestRate, clampedHardwareDefault);
			case CINEMATIC -> Math.max(profile.minimumGenerationRequestRate, Math.min(128, clampedHardwareDefault));
		};
	}

	public enum Profile {
		COMPETITIVE("competitive", 56, 10, false, false, true, 144),
		BALANCED("balanced", 60, 9, false, true, true, 112),
		CINEMATIC("cinematic", 48, 7, false, true, true, 80);

		private final String id;
		private final int defaultTargetDistanceChunks;
		private final int recommendedVanillaDistanceChunks;
		private final boolean autoReduceVanillaDistance;
		private final boolean allowDynamicTargetDistanceReduction;
		private final boolean allowDynamicGenerationReduction;
		private final int minimumGenerationRequestRate;

		Profile(
			String id,
			int defaultTargetDistanceChunks,
			int recommendedVanillaDistanceChunks,
			boolean autoReduceVanillaDistance,
			boolean allowDynamicTargetDistanceReduction,
			boolean allowDynamicGenerationReduction,
			int minimumGenerationRequestRate
		) {
			this.id = id;
			this.defaultTargetDistanceChunks = defaultTargetDistanceChunks;
			this.recommendedVanillaDistanceChunks = recommendedVanillaDistanceChunks;
			this.autoReduceVanillaDistance = autoReduceVanillaDistance;
			this.allowDynamicTargetDistanceReduction = allowDynamicTargetDistanceReduction;
			this.allowDynamicGenerationReduction = allowDynamicGenerationReduction;
			this.minimumGenerationRequestRate = minimumGenerationRequestRate;
		}

		public String id() {
			return id;
		}
	}
}
