package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Minecraft;

public final class PauCClientTargetFps {
	private static final String TARGET_FPS_PROPERTY = "pauc.client.targetFps";
	private static final String VANILLA_UNLIMITED_REFERENCE_FPS_PROPERTY = "pauc.client.unlimitedVanillaReferenceFps";
	private static final String SHADER_UNLIMITED_REFERENCE_FPS_PROPERTY = "pauc.client.unlimitedShaderReferenceFps";
	private static final String UNLIMITED_OBSERVED_HEADROOM_FPS_PROPERTY = "pauc.client.unlimitedObservedHeadroomFps";
	private static final int DEFAULT_VANILLA_UNLIMITED_REFERENCE_FPS = 360;
	private static final int DEFAULT_SHADER_UNLIMITED_REFERENCE_FPS = 144;
	private static volatile double shaderObservedFps = -1.0D;
	private static volatile double vanillaObservedFps = -1.0D;

	private PauCClientTargetFps() {
	}

	public static int effectiveTargetFps() {
		return effectiveTargetFps(Minecraft.getInstance());
	}

	public static int effectiveTargetFps(Minecraft minecraft) {
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		String override = System.getProperty(TARGET_FPS_PROPERTY);
		if (override != null) {
			return parseTarget(override, unlimitedReferenceFps(minecraft, shaderActive));
		}

		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(minecraft);
		if (playerVideo.available() && !playerVideo.fpsUnlimited()) {
			return sanitize(playerVideo.fpsLimit());
		}
		return unlimitedReferenceFps(minecraft, shaderActive);
	}

	public static boolean hasExplicitTargetFps() {
		return System.getProperty(TARGET_FPS_PROPERTY) != null;
	}

	public static boolean isPlayerFpsUnlimited(Minecraft minecraft) {
		return PauCPlayerVideoSettings.capture(minecraft).fpsUnlimited();
	}

	public static String referenceMode(Minecraft minecraft) {
		if (hasExplicitTargetFps()) {
			return "explicit";
		}
		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(minecraft);
		if (playerVideo.available() && !playerVideo.fpsUnlimited()) {
			return "player-limit";
		}
		return "unlimited-high-reference";
	}

	private static int unlimitedReferenceFps(Minecraft minecraft, boolean shaderActive) {
		int fps = PauCClientFrameMetrics.queryFps(minecraft);
		double observed = shaderActive ? shaderObservedFps : vanillaObservedFps;
		if (fps >= 15) {
			observed = observed < 0.0D ? fps : smoothObservedFps(observed, fps);
			if (shaderActive) {
				shaderObservedFps = observed;
			} else {
				vanillaObservedFps = observed;
			}
		}

		int floor = readInt(
			shaderActive ? SHADER_UNLIMITED_REFERENCE_FPS_PROPERTY : VANILLA_UNLIMITED_REFERENCE_FPS_PROPERTY,
			shaderActive ? DEFAULT_SHADER_UNLIMITED_REFERENCE_FPS : DEFAULT_VANILLA_UNLIMITED_REFERENCE_FPS,
			30,
			500
		);
		int headroom = readInt(UNLIMITED_OBSERVED_HEADROOM_FPS_PROPERTY, shaderActive ? 12 : 24, 0, 160);
		int observedReference = observed > 0.0D ? (int) Math.round(observed) + headroom : floor;
		return sanitize(Math.max(floor, observedReference));
	}

	private static double smoothObservedFps(double previous, int fps) {
		double blend = fps >= previous ? 0.20D : 0.35D;
		return previous * (1.0D - blend) + fps * blend;
	}

	private static int parseTarget(String rawValue, int fallback) {
		try {
			return sanitize(Integer.parseInt(rawValue.trim()));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null || rawValue.isBlank()) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static int sanitize(int value) {
		return Math.max(30, Math.min(500, value));
	}
}
