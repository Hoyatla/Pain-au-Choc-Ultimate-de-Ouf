package fr.hoyatla.pauc.lod;

import java.util.Locale;

/**
 * Floor-driven shadow-distance actuator for shader packs (the real GPU lever for the Photon/Solas fps floors).
 * <p>
 * The shadow pass — re-rendering world geometry from the sun's point of view — is one of the largest GPU costs in
 * Photon/Solas. PauC owns the vendored {@code ShadowRenderer}, whose shadow render distance is
 * {@code halfPlaneLength * renderDistanceMultiplier}. When the measured fps is below the mode's floor (GPU-bound), this
 * holder pulls that distance in, so fewer shadow casters are rendered and GPU time is recovered; when fps comfortably
 * clears the floor again it restores full distance. Distant shadows fade — an acceptable trade for a stable fps floor in
 * fast-paced gameplay, and exactly the kind of lever a competitive/shooter profile should pull.
 * <p>
 * The scale ramps SLOWLY (a few % per tick) with a hysteresis band, so shadow distance never strobes/flickers — the
 * lesson from the per-frame dephasing regression. Floors are configurable and self-calibrating per pack family; the
 * defaults are the user's reference floors (Solas 70 / Photon 80 / generic shader 75 fps). Kill-switch:
 * {@code pauc.client.shaderShadowFloor=false}.
 */
public final class PauCShaderShadowBudget {
	private static final String ENABLED_PROPERTY = "pauc.client.shaderShadowFloor";
	private static final String FLOOR_SOLAS_PROPERTY = "pauc.client.shaderFloorSolasFps";
	private static final String FLOOR_PHOTON_PROPERTY = "pauc.client.shaderFloorPhotonFps";
	private static final String FLOOR_GENERIC_PROPERTY = "pauc.client.shaderFloorGenericFps";
	private static final String MIN_SCALE_PROPERTY = "pauc.client.shaderShadowMinScale";
	private static final String RAMP_STEP_PROPERTY = "pauc.client.shaderShadowRampStep";
	private static final String RECOVER_MARGIN_PROPERTY = "pauc.client.shaderShadowRecoverMarginFps";

	private static volatile double scale = 1.0D;
	private static int lastFloor;
	private static int lastObservedFps;

	private PauCShaderShadowBudget() {
	}

	/**
	 * Drive the actuator from the FPS governor each client tick.
	 *
	 * @param shaderActive whether a shader pack is in use (no shadow pass to trim otherwise)
	 * @param shaderFamily the {@code PauCLodShaderProfiles.Family} name (e.g. "SOLAS", "PHOTON")
	 * @param observedFps  the governor's smoothed/steady real fps
	 */
	public static void update(boolean shaderActive, String shaderFamily, int observedFps) {
		double step = readDouble(RAMP_STEP_PROPERTY, 0.03D, 0.005D, 0.5D);
		if (!isEnabled() || !shaderActive || observedFps <= 0) {
			rampToward(1.0D, step);
			lastFloor = 0;
			lastObservedFps = observedFps;
			return;
		}

		int floor = floorFor(shaderFamily);
		lastFloor = floor;
		lastObservedFps = observedFps;
		double minScale = readDouble(MIN_SCALE_PROPERTY, 0.6D, 0.3D, 1.0D);
		int recoverMargin = readInt(RECOVER_MARGIN_PROPERTY, 10, 0, 60);

		if (observedFps < floor) {
			rampToward(minScale, step);
		} else if (observedFps > floor + recoverMargin) {
			rampToward(1.0D, step);
		}
		// Hysteresis band [floor, floor+recoverMargin]: hold the current scale to avoid oscillation.
	}

	/** Multiplier in [minScale, 1] applied to the shader shadow render distance. 1.0 when not defending the floor. */
	public static double shadowDistanceScale() {
		return isEnabled() ? scale : 1.0D;
	}

	public static String describeState() {
		return "shaderShadowBudget[scale="
			+ String.format(Locale.ROOT, "%.2f", scale)
			+ ", floor="
			+ (lastFloor > 0 ? lastFloor + "fps" : "-")
			+ ", fps="
			+ (lastObservedFps > 0 ? Integer.toString(lastObservedFps) : "-")
			+ "]";
	}

	public static void reset() {
		scale = 1.0D;
		lastFloor = 0;
		lastObservedFps = 0;
	}

	private static int floorFor(String shaderFamily) {
		String family = shaderFamily == null ? "" : shaderFamily.toUpperCase(Locale.ROOT);
		return switch (family) {
			case "SOLAS" -> readInt(FLOOR_SOLAS_PROPERTY, 70, 30, 240);
			case "PHOTON" -> readInt(FLOOR_PHOTON_PROPERTY, 80, 30, 240);
			default -> readInt(FLOOR_GENERIC_PROPERTY, 75, 30, 240);
		};
	}

	private static void rampToward(double target, double step) {
		double delta = target - scale;
		if (delta > step) {
			delta = step;
		} else if (delta < -step) {
			delta = -step;
		}
		double next = scale + delta;
		scale = next < 0.0D ? 0.0D : (next > 1.0D ? 1.0D : next);
	}

	private static boolean isEnabled() {
		// Default OFF: trading shadow render distance for fps is a visible quality cut. PauC's goal is higher fps at
		// FULL quality (via efficiency), not quality/fps tradeoffs. Opt-in only.
		return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
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

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
