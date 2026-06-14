package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodGameplayProfile;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Minecraft;

public final class PauCClientTargetFps {
	private static final String TARGET_FPS_PROPERTY = "pauc.client.targetFps";
	private static final int UNLIMITED_VANILLA_FRAMERATE_VALUE = 260;
	private static final int DEFAULT_SHADER_ADAPTIVE_TARGET_FPS = 90;
	private static final int DEFAULT_VANILLA_ADAPTIVE_TARGET_FPS = 144;
	private static final int MIN_SHADER_ADAPTIVE_TARGET_FPS = 72;
	private static final int MAX_SHADER_ADAPTIVE_TARGET_FPS = 144;
	private static final int MIN_VANILLA_ADAPTIVE_TARGET_FPS = 120;
	private static final int MAX_VANILLA_ADAPTIVE_TARGET_FPS = 240;
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
			return parseTarget(override, adaptiveUnlimitedTarget(minecraft, shaderActive));
		}
		if (minecraft == null || minecraft.options == null) {
			return adaptiveUnlimitedTarget(minecraft, shaderActive);
		}

		try {
			int framerateLimit = minecraft.options.framerateLimit().get();
			if (framerateLimit <= 0 || framerateLimit >= UNLIMITED_VANILLA_FRAMERATE_VALUE) {
				return adaptiveUnlimitedTarget(minecraft, shaderActive);
			}
			return sanitize(framerateLimit);
		} catch (RuntimeException | LinkageError ignored) {
			return adaptiveUnlimitedTarget(minecraft, shaderActive);
		}
	}

	private static int adaptiveUnlimitedTarget(Minecraft minecraft, boolean shaderActive) {
		PauCLodGameplayProfile.Profile profile = PauCLodGameplayProfile.current();
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

		int fallback = shaderActive ? DEFAULT_SHADER_ADAPTIVE_TARGET_FPS : DEFAULT_VANILLA_ADAPTIVE_TARGET_FPS;
		int min = shaderActive ? MIN_SHADER_ADAPTIVE_TARGET_FPS : MIN_VANILLA_ADAPTIVE_TARGET_FPS;
		int max = shaderActive ? MAX_SHADER_ADAPTIVE_TARGET_FPS : MAX_VANILLA_ADAPTIVE_TARGET_FPS;
		if (profile == PauCLodGameplayProfile.Profile.SHOOTER && !shaderActive) {
			fallback = Math.max(fallback, PauCLodGameplayProfile.defaultTargetFps());
			min = Math.max(min, Math.min(max, PauCLodGameplayProfile.defaultTargetFps() - 12));
		}
		double sourceFps = observed > 0.0D ? Math.max(observed, fallback) : fallback;
		int target = (int) Math.round(sourceFps * (shaderActive ? 0.92D : 0.90D));
		return sanitize(Math.max(min, Math.min(max, target)));
	}

	private static double smoothObservedFps(double previous, int fps) {
		double blend = fps >= previous ? 0.20D : 0.05D;
		return previous * (1.0D - blend) + fps * blend;
	}

	private static int parseTarget(String rawValue, int fallback) {
		try {
			return sanitize(Integer.parseInt(rawValue.trim()));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int sanitize(int value) {
		return Math.max(30, Math.min(500, value));
	}
}
