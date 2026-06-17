package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import fr.hoyatla.pauc.lod.PauCDynamicResolutionMode;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Minecraft;

import java.util.Locale;

public final class PauCDynamicResolution {
	private static final String ENABLED_PROPERTY = "pauc.client.dynamicResolution";
	private static final String MODE_PROPERTY = "pauc.client.dynamicResolutionMode";
	private static final String MIN_SCALE_PROPERTY = "pauc.client.dynamicResolutionMinScale";
	private static final String DOWN_RATE_PROPERTY = "pauc.client.dynamicResolutionDownRatePerSecond";
	private static final String UP_RATE_PROPERTY = "pauc.client.dynamicResolutionUpRatePerSecond";
	private static final String RUNTIME_SCALE_PROPERTY = "pauc.runtime.dynamicResolutionScale";
	private static double scale = 1.0D;
	private static double appliedScale = 1.0D;
	private static double lastTargetFrameMs = -1.0D;
	private static double lastAverageFrameMs = -1.0D;
	private static boolean active;
	private static int disabledTicks;
	private static int resizeCooldownTicks;
	private static int lastWindowWidth = -1;
	private static int lastWindowHeight = -1;
	private static PauCDynamicResolutionMode lastMode = PauCDynamicResolutionMode.OFF;

	private PauCDynamicResolution() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (resizeCooldownTicks > 0) {
			resizeCooldownTicks--;
		}
		PauCDynamicResolutionMode mode = readMode();
		lastMode = mode;
		if (mode == PauCDynamicResolutionMode.OFF
			|| !readBoolean(ENABLED_PROPERTY, mode != PauCDynamicResolutionMode.OFF)
			|| minecraft == null
			|| minecraft.level == null
			|| !PauCLodShaderContext.isShaderPackInUse()) {
			resetToNative("inactive");
			return;
		}
		if (disabledTicks > 0) {
			disabledTicks--;
			applyMainTargetScale(minecraft, 1.0D, true);
			publishScale();
			return;
		}

		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(minecraft);
		if (playerVideo.fpsUnlimited() && !PauCClientTargetFps.hasExplicitTargetFps()) {
			lastTargetFrameMs = -1.0D;
			lastAverageFrameMs = PauCClientFrameMetrics.averageFrameTimeMs();
			scale = readDouble("pauc.client.dynamicResolutionUnlimitedScale", mode.minScale(), 0.50D, 1.0D);
			active = scale < 0.999D;
			applyMainTargetScale(minecraft, scale, false);
			System.setProperty("pauc.runtime.dynamicResolutionReason", "player-fps-unlimited-fixed-" + mode.id());
			publishScale();
			return;
		}

		int targetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		if (targetFps <= 0) {
			resetToNative("target-unavailable");
			return;
		}
		lastTargetFrameMs = 1000.0D / targetFps;
		lastAverageFrameMs = PauCClientFrameMetrics.averageFrameTimeMs();
		if (lastAverageFrameMs <= 0.0D) {
			publishScale();
			return;
		}

		double minScale = readDouble(MIN_SCALE_PROPERTY, mode.minScale(), 0.50D, 1.0D);
		double downStep = readDouble(DOWN_RATE_PROPERTY, mode.downRatePerSecond(), 0.005D, 0.40D) / 20.0D;
		double upStep = readDouble(UP_RATE_PROPERTY, mode.upRatePerSecond(), 0.005D, 0.25D) / 20.0D;
		double pressure = lastAverageFrameMs / lastTargetFrameMs;
		PauCClientFluidityState.Band band = PauCClientFluidityState.lastSnapshot().band();
		boolean watchdogSpike = PauCClientFrameMetrics.lastWatchdogFrameMs() >= readDouble("pauc.client.dynamicResolutionWatchdogMs", 140.0D, 50.0D, 1_000.0D);
		if (pressure > 1.05D || watchdogSpike || band == PauCClientFluidityState.Band.RELIEF) {
			double severity = Math.max(1.0D, Math.min(2.5D, pressure));
			scale = Math.max(minScale, scale - (downStep * severity));
			active = scale < 0.999D;
		} else if (pressure < 0.85D && band != PauCClientFluidityState.Band.RECOVERY) {
			scale = Math.min(1.0D, scale + upStep);
			active = scale < 0.999D;
		}
		if (!Double.isFinite(scale)) {
			scale = 1.0D;
			active = false;
			disabledTicks = readInt("pauc.client.dynamicResolutionKillSwitchTicks", 1_200, 20, 12_000);
			applyMainTargetScale(minecraft, 1.0D, true);
		} else {
			applyMainTargetScale(minecraft, scale, false);
		}
		publishScale();
	}

	public static void reset() {
		scale = 1.0D;
		lastTargetFrameMs = -1.0D;
		lastAverageFrameMs = -1.0D;
		active = false;
		disabledTicks = 0;
		resizeCooldownTicks = 0;
		lastWindowWidth = -1;
		lastWindowHeight = -1;
		lastMode = PauCDynamicResolutionMode.OFF;
		applyMainTargetScale(Minecraft.getInstance(), 1.0D, true);
		System.clearProperty(RUNTIME_SCALE_PROPERTY);
	}

	public static double scale() {
		return scale;
	}

	public static String describeState() {
		return "drs[active="
			+ active
			+ ", mode="
			+ lastMode.id()
			+ ", scale="
			+ round(scale)
			+ ", applied="
			+ round(appliedScale)
			+ ", frame="
			+ round(lastAverageFrameMs)
			+ "/"
			+ round(lastTargetFrameMs)
			+ "ms, disabledTicks="
			+ disabledTicks
			+ "]";
	}

	private static void resetToNative(String reason) {
		if (scale != 1.0D || active) {
			scale = 1.0D;
			active = false;
			System.setProperty("pauc.runtime.dynamicResolutionReason", reason);
		}
		applyMainTargetScale(Minecraft.getInstance(), 1.0D, true);
		publishScale();
	}

	private static void applyMainTargetScale(Minecraft minecraft, double targetScale, boolean force) {
		if (minecraft == null || minecraft.getWindow() == null || minecraft.getMainRenderTarget() == null) {
			return;
		}
		int windowWidth = minecraft.getWindow().getWidth();
		int windowHeight = minecraft.getWindow().getHeight();
		if (windowWidth <= 0 || windowHeight <= 0) {
			return;
		}
		double quantizedScale = targetScale >= 0.995D ? 1.0D : Math.max(0.50D, Math.min(1.0D, Math.round(targetScale * 20.0D) / 20.0D));
		int targetWidth = Math.max(320, (int) Math.round(windowWidth * quantizedScale));
		int targetHeight = Math.max(180, (int) Math.round(windowHeight * quantizedScale));
		if (quantizedScale >= 0.999D) {
			targetWidth = windowWidth;
			targetHeight = windowHeight;
		}

		RenderTarget mainTarget = minecraft.getMainRenderTarget();
		boolean windowChanged = windowWidth != lastWindowWidth || windowHeight != lastWindowHeight;
		if (mainTarget.width == targetWidth && mainTarget.height == targetHeight) {
			appliedScale = quantizedScale;
			active = scale < 0.999D || appliedScale < 0.999D;
			lastWindowWidth = windowWidth;
			lastWindowHeight = windowHeight;
			return;
		}
		if (!force && !windowChanged && resizeCooldownTicks > 0) {
			return;
		}

		mainTarget.resize(targetWidth, targetHeight, Minecraft.ON_OSX);
		appliedScale = quantizedScale;
		active = scale < 0.999D || appliedScale < 0.999D;
		lastWindowWidth = windowWidth;
		lastWindowHeight = windowHeight;
		resizeCooldownTicks = quantizedScale >= 0.999D
			? 0
			: readInt("pauc.client.dynamicResolutionResizeCooldownTicks", 20, 4, 200);
		System.setProperty("pauc.runtime.dynamicResolutionAppliedScale", round(appliedScale));
	}

	private static void publishScale() {
		System.setProperty(RUNTIME_SCALE_PROPERTY, round(scale));
	}

	private static PauCDynamicResolutionMode readMode() {
		String rawMode = System.getProperty(MODE_PROPERTY);
		if (rawMode == null && readBoolean(ENABLED_PROPERTY, false)) {
			return PauCDynamicResolutionMode.BALANCED;
		}
		return PauCDynamicResolutionMode.byId(rawMode);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
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

	private static String round(double value) {
		return value < 0.0D ? "-" : String.format(Locale.ROOT, "%.3f", value);
	}
}
