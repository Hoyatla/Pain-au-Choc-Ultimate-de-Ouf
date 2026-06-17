package fr.hoyatla.pauc.platform.forge.runtime;

import java.util.Locale;

public final class PauCServerOptimizationProfile {
	private static final String PROFILE_PROPERTY = "serverProfile";

	private PauCServerOptimizationProfile() {
	}

	public static Profile current() {
		String rawProfile = PauCRuntimeSwitches.readString(PROFILE_PROPERTY, Profile.SAFE.id);
		String normalized = normalize(rawProfile);
		for (Profile profile : Profile.values()) {
			if (profile.id.equals(normalized)) {
				return profile;
			}
		}
		return Profile.SAFE;
	}

	public static boolean enabled(String key, Feature feature) {
		return PauCRuntimeSwitches.enabled(key, current().enables(feature));
	}

	public static String describeState() {
		return "serverProfile[" + current().id + "]";
	}

	private static String normalize(String value) {
		return value == null ? Profile.SAFE.id : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	public enum Feature {
		SPAWN_THROTTLE,
		MOB_AI_THROTTLE,
		SURFACE_UNDERGROUND_AI_THROTTLE,
		PATHFINDING_BREAKER,
		PATHFINDING_CACHE,
		STRUCTURE_BREAKER,
		STALL_GOVERNOR,
		PHASE_BUDGET
	}

	public enum Profile {
		SAFE("safe"),
		HEAVY_PACK("heavy-pack"),
		SHOOTER("shooter");

		private final String id;

		Profile(String id) {
			this.id = id;
		}

		private boolean enables(Feature feature) {
			return switch (this) {
				case SAFE -> false;
				case HEAVY_PACK -> true;
				case SHOOTER -> true;
			};
		}
	}
}
