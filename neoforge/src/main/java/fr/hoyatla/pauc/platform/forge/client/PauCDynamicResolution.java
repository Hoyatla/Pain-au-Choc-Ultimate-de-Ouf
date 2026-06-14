package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Minecraft;

import java.util.Locale;

public final class PauCDynamicResolution {
	private static final String ENABLED_PROPERTY = "pauc.client.dynamicResolution";
	private static final String MIN_SCALE_PROPERTY = "pauc.client.dynamicResolutionMinScale";
	private static final String DOWN_RATE_PROPERTY = "pauc.client.dynamicResolutionDownRatePerSecond";
	private static final String UP_RATE_PROPERTY = "pauc.client.dynamicResolutionUpRatePerSecond";
	private static final String RUNTIME_SCALE_PROPERTY = "pauc.runtime.dynamicResolutionScale";
	private static double scale = 1.0D;
	private static double lastTargetFrameMs = -1.0D;
	private static double lastAverageFrameMs = -1.0D;
	private static boolean active;
	private static int disabledTicks;

	private PauCDynamicResolution() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!readBoolean(ENABLED_PROPERTY, true) || minecraft == null || minecraft.level == null || !PauCLodShaderContext.isShaderPackInUse()) {
			resetToNative("inactive");
			return;
		}
		if (disabledTicks > 0) {
			disabledTicks--;
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

		double minScale = readDouble(MIN_SCALE_PROPERTY, 0.65D, 0.50D, 1.0D);
		double downStep = readDouble(DOWN_RATE_PROPERTY, 0.05D, 0.005D, 0.40D) / 20.0D;
		double upStep = readDouble(UP_RATE_PROPERTY, 0.025D, 0.005D, 0.25D) / 20.0D;
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
		}
		publishScale();
	}

	public static void reset() {
		scale = 1.0D;
		lastTargetFrameMs = -1.0D;
		lastAverageFrameMs = -1.0D;
		active = false;
		disabledTicks = 0;
		System.clearProperty(RUNTIME_SCALE_PROPERTY);
	}

	public static double scale() {
		return scale;
	}

	public static String describeState() {
		return "drs[active="
			+ active
			+ ", scale="
			+ round(scale)
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
		publishScale();
	}

	private static void publishScale() {
		System.setProperty(RUNTIME_SCALE_PROPERTY, round(scale));
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
