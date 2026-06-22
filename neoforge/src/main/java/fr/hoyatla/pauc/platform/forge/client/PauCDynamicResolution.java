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
		lastMode = readMode();
		// Dynamic resolution is DISABLED. Profiling proved it yields no fps gain here: the frame is bottlenecked by the
		// shadow pass (~8 ms re-rendering terrain into the shadow map) and CPU draw submission, NOT fragment/pixel work,
		// so cutting render resolution saves almost nothing (~1 ms of deferred+composite). It also caused viewport
		// artifacts (offset hand/HUD/menu under the resize). The menu control is kept inert so the UI doesn't break;
		// no resolution scaling or shader TAAU is ever applied. Always render at native resolution.
		resetToNative("disabled-no-fps-benefit");
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
