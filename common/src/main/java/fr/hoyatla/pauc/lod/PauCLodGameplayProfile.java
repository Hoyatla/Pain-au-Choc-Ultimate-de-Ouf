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
			case "shooter", "fps", "warzone", "tactical", "arena" -> Profile.SHOOTER;
			case "competitive", "competition", "vanilla", "no_shader", "no_shaders" -> Profile.COMPETITIVE;
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
			+ ", advisoryFps="
			+ profile.defaultTargetFps
			+ ", qualityScale="
			+ String.format(Locale.ROOT, "%.2f", profile.qualityScale)
			+ ", viewportBias="
			+ profile.viewportCentralBias
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

	public static int defaultTargetFps() {
		return current().defaultTargetFps;
	}

	public static double qualityScale() {
		return current().qualityScale;
	}

	public static boolean viewportCentralBias() {
		return current().viewportCentralBias;
	}

	public static int defaultGenerationRequestRateLimit(int hardwareDefault) {
		Profile profile = current();
		int clampedHardwareDefault = Math.max(20, Math.min(768, hardwareDefault));
		return switch (profile) {
			case SHOOTER -> Math.max(profile.minimumGenerationRequestRate, clampedHardwareDefault);
			case COMPETITIVE -> Math.max(profile.minimumGenerationRequestRate, clampedHardwareDefault);
			case BALANCED -> Math.max(profile.minimumGenerationRequestRate, clampedHardwareDefault);
			case CINEMATIC -> Math.max(profile.minimumGenerationRequestRate, Math.min(128, clampedHardwareDefault));
		};
	}

	public enum Profile {
		SHOOTER("shooter", 40, 12, true, true, true, 192, 144, 0.85D, true),
		COMPETITIVE("competitive", 56, 10, false, true, true, 144, 120, 1.00D, false),
		BALANCED("balanced", 60, 9, false, true, true, 112, 80, 1.00D, false),
		CINEMATIC("cinematic", 48, 7, false, true, true, 80, 60, 1.00D, false);

		private final String id;
		private final int defaultTargetDistanceChunks;
		private final int recommendedVanillaDistanceChunks;
		private final boolean autoReduceVanillaDistance;
		private final boolean allowDynamicTargetDistanceReduction;
		private final boolean allowDynamicGenerationReduction;
		private final int minimumGenerationRequestRate;
		private final int defaultTargetFps;
		private final double qualityScale;
		private final boolean viewportCentralBias;

		Profile(
			String id,
			int defaultTargetDistanceChunks,
			int recommendedVanillaDistanceChunks,
			boolean autoReduceVanillaDistance,
			boolean allowDynamicTargetDistanceReduction,
			boolean allowDynamicGenerationReduction,
			int minimumGenerationRequestRate,
			int defaultTargetFps,
			double qualityScale,
			boolean viewportCentralBias
		) {
			this.id = id;
			this.defaultTargetDistanceChunks = defaultTargetDistanceChunks;
			this.recommendedVanillaDistanceChunks = recommendedVanillaDistanceChunks;
			this.autoReduceVanillaDistance = autoReduceVanillaDistance;
			this.allowDynamicTargetDistanceReduction = allowDynamicTargetDistanceReduction;
			this.allowDynamicGenerationReduction = allowDynamicGenerationReduction;
			this.minimumGenerationRequestRate = minimumGenerationRequestRate;
			this.defaultTargetFps = defaultTargetFps;
			this.qualityScale = qualityScale;
			this.viewportCentralBias = viewportCentralBias;
		}

		public String id() {
			return id;
		}
	}
}
