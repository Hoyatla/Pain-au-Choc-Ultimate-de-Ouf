package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCFrameSpikeAbsorber;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public final class PauCPlayerVideoSettings {
	private static final int UNLIMITED_VANILLA_FRAMERATE_VALUE = 260;
	private static volatile long cachedFrameSeq = Long.MIN_VALUE;
	private static volatile Options cachedOptions;
	private static volatile Snapshot cachedSnapshot = Snapshot.unavailable();

	private PauCPlayerVideoSettings() {
	}

	public static Snapshot capture(Minecraft minecraft) {
		if (minecraft == null || minecraft.options == null) {
			return Snapshot.unavailable();
		}

		long frameSeq = PauCFrameSpikeAbsorber.frameSeq();
		Options options = minecraft.options;
		if (frameSeq == cachedFrameSeq && cachedOptions == options) {
			return cachedSnapshot;
		}

		int fpsLimit = -1;
		boolean vsync = false;
		int renderDistance = -1;
		String graphicsMode = "-";
		try {
			fpsLimit = minecraft.options.framerateLimit().get();
		} catch (RuntimeException | LinkageError ignored) {
			fpsLimit = -1;
		}
		try {
			vsync = minecraft.options.enableVsync().get();
		} catch (RuntimeException | LinkageError ignored) {
			vsync = false;
		}
		try {
			renderDistance = minecraft.options.getEffectiveRenderDistance();
		} catch (RuntimeException | LinkageError ignored) {
			renderDistance = -1;
		}
		try {
			graphicsMode = minecraft.options.graphicsMode().get().getKey();
		} catch (RuntimeException | LinkageError ignored) {
			graphicsMode = "-";
		}
		Snapshot snapshot = new Snapshot(
			true,
			fpsLimit,
			fpsLimit <= 0 || fpsLimit >= UNLIMITED_VANILLA_FRAMERATE_VALUE,
			vsync,
			renderDistance,
			graphicsMode
		);
		cachedFrameSeq = frameSeq;
		cachedOptions = options;
		cachedSnapshot = snapshot;
		return snapshot;
	}

	public record Snapshot(
		boolean available,
		int fpsLimit,
		boolean fpsUnlimited,
		boolean vsync,
		int renderDistance,
		String graphicsMode
	) {
		private static Snapshot unavailable() {
			return new Snapshot(false, -1, true, false, -1, "-");
		}

		public String fpsLimitLabel() {
			if (!available) {
				return "unknown";
			}
			return fpsUnlimited ? "unlimited" : Integer.toString(fpsLimit);
		}

		public String describe() {
			return "playerVideo[fpsLimit="
				+ fpsLimitLabel()
				+ ", vsync="
				+ vsync
				+ ", vanillaDistance="
				+ (renderDistance >= 0 ? renderDistance : "-")
				+ ", graphics="
				+ graphicsMode
				+ "]";
		}
	}
}
