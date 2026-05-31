package fr.hoyatla.pauc.platform.forge.client;

import net.minecraft.client.Minecraft;

public final class PauCClientTargetFps {
	private static final String TARGET_FPS_PROPERTY = "pauc.client.targetFps";
	private static final int UNLIMITED_VANILLA_FRAMERATE_VALUE = 260;
	private static final int DEFAULT_UNLIMITED_TARGET_FPS = 200;

	private PauCClientTargetFps() {
	}

	public static int effectiveTargetFps() {
		return effectiveTargetFps(Minecraft.getInstance());
	}

	public static int effectiveTargetFps(Minecraft minecraft) {
		String override = System.getProperty(TARGET_FPS_PROPERTY);
		if (override != null) {
			return parseTarget(override, DEFAULT_UNLIMITED_TARGET_FPS);
		}
		if (minecraft == null || minecraft.options == null) {
			return DEFAULT_UNLIMITED_TARGET_FPS;
		}

		try {
			int framerateLimit = minecraft.options.framerateLimit().get();
			if (framerateLimit <= 0 || framerateLimit >= UNLIMITED_VANILLA_FRAMERATE_VALUE) {
				return DEFAULT_UNLIMITED_TARGET_FPS;
			}
			return sanitize(framerateLimit);
		} catch (RuntimeException | LinkageError ignored) {
			return DEFAULT_UNLIMITED_TARGET_FPS;
		}
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
