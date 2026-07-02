package fr.hoyatla.pauc.platform.forge.runtime;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCTerrainGeneratorDetector;
import java.util.Locale;
import org.slf4j.Logger;

public final class PauCServerOptimizationProfile {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String PROFILE_PROPERTY = "serverProfile";
	private static volatile boolean defaultProfileLogged;

	private PauCServerOptimizationProfile() {
	}

	public static Profile current() {
		String rawProfile = PauCRuntimeSwitches.readString(PROFILE_PROPERTY, defaultProfile().id);
		String normalized = normalize(rawProfile);
		for (Profile profile : Profile.values()) {
			if (profile.id.equals(normalized)) {
				return profile;
			}
		}
		return defaultProfile();
	}

	/**
	 * Self-calibrating default: light packs keep the untouched SAFE profile, heavy/extreme modpacks get the
	 * server optimization stack (AI/spawn throttles, pathfinding breaker, stall governor, phase budget) by
	 * default — measured on Cursed Walking (modpack=extreme): the integrated server fell 2-9 SECONDS behind
	 * with the whole stack gated off by the static SAFE default. Explicit pauc.runtime.serverProfile wins.
	 */
	private static Profile defaultProfile() {
		Profile detected = switch (PauCTerrainGeneratorDetector.currentModpackClass()) {
			case HEAVY, EXTREME -> Profile.HEAVY_PACK;
			case LIGHT, MEDIUM -> Profile.SAFE;
		};
		if (!defaultProfileLogged) {
			defaultProfileLogged = true;
			LOGGER.info("PauC server optimization profile default: {} (modpackClass={}).",
				detected.id, PauCTerrainGeneratorDetector.currentModpackClass());
		}
		return detected;
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
		PHASE_BUDGET,
		ITEM_MERGE_THROTTLE
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
