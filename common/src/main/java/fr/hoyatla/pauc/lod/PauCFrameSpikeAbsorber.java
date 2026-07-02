package fr.hoyatla.pauc.lod;

import java.util.Locale;

/**
 * Measured per-frame "spike absorber" backbone.
 * <p>
 * The neoforge frame loop feeds this holder the measured frame time and the current FPS target every frame; downstream
 * consumers (particle spawn/render budgets, explosion VFX staggering, entity animation LOD) read the resulting
 * {@link #workScale()} / {@link #pressure01()} scalars to throttle non-essential work when a frame runs long. The intent
 * is to shave the invisible 1%-low spikes (particle floods, explosion bursts, animation storms) without touching steady
 * frames.
 * <p>
 * Everything here is expressed as a RATIO of the measured target frame time, never an absolute millisecond constant, so
 * it self-calibrates across arbitrary modpacks and hardware (see the perf-resilience principle). When healthy, pressure
 * is 0 and {@link #workScale()} is 1.0, i.e. consumers behave exactly like vanilla. A kill-switch
 * ({@code pauc.client.spikeAbsorber=false}) disables it entirely.
 */
public final class PauCFrameSpikeAbsorber {
	private static final String ENABLED_PROPERTY = "pauc.client.spikeAbsorber";
	private static final String ENTER_RATIO_PROPERTY = "pauc.client.spikeAbsorberEnterRatio";
	private static final String HARD_RATIO_PROPERTY = "pauc.client.spikeAbsorberHardRatio";
	private static final String MIN_SCALE_PROPERTY = "pauc.client.spikeAbsorberMinWorkScale";
	private static final String RISE_ALPHA_PROPERTY = "pauc.client.spikeAbsorberRiseAlpha";
	private static final String DECAY_ALPHA_PROPERTY = "pauc.client.spikeAbsorberDecayAlpha";
	private static final String DECAY_ALPHA_MAX_PROPERTY = "pauc.client.spikeAbsorberDecayAlphaMax";
	private static final String SPIKE_FLOOR_PROPERTY = "pauc.client.spikeAbsorberWatchdogFloor";
	private static final String BASELINE_ALPHA_PROPERTY = "pauc.client.spikeAbsorberBaselineAlpha";
	private static final String BASELINE_CLAMP_RATIO_PROPERTY = "pauc.client.spikeAbsorberBaselineClampRatio";

	private static volatile double pressure;
	private static volatile double workScale = 1.0D;
	private static volatile long frameSeq;
	private static double lastFrameMs = -1.0D;
	private static double baselineMs = -1.0D;

	private PauCFrameSpikeAbsorber() {
	}

	/**
	 * Feed one measured frame. Called once per rendered frame from the neoforge frame loop.
	 * <p>
	 * Pressure is measured RELATIVE TO A SELF-CALIBRATING BASELINE of the recent typical frame time, not to the FPS
	 * target. A "spike" is a frame much slower than your own usual frame, whatever framerate the hardware/modpack
	 * actually sustains — so steady (even steadily-below-target) play produces zero pressure, and the absorber never
	 * mis-fires just because the configured target is unreachable. The baseline tracks frames with a slow EMA and
	 * clamps each frame's influence, so brief multi-second hitches do not pollute it.
	 *
	 * @param frameMs       measured wall-clock time of the frame that just completed
	 * @param watchdogSpike whether the frame watchdog flagged a sustained spike this frame
	 */
	public static void update(double frameMs, boolean watchdogSpike) {
		frameSeq++;
		lastFrameMs = frameMs;

		if (!isEnabled() || frameMs <= 0.0D) {
			pressure = 0.0D;
			workScale = 1.0D;
			return;
		}

		// Self-calibrating baseline: slow EMA of frame time, with each frame's pull clamped so a giant hitch can only
		// nudge the baseline a little (otherwise one 4s stall would raise the baseline and blind us to later spikes).
		if (baselineMs <= 0.0D) {
			baselineMs = frameMs;
		} else {
			double clampRatio = readDouble(BASELINE_CLAMP_RATIO_PROPERTY, 2.0D, 1.2D, 8.0D);
			double clamped = Math.min(frameMs, baselineMs * clampRatio);
			double baseAlpha = readDouble(BASELINE_ALPHA_PROPERTY, 0.03D, 0.002D, 0.5D);
			baselineMs = baselineMs + baseAlpha * (clamped - baselineMs);
		}
		double baseline = Math.max(1.0D, baselineMs);

		double enterRatio = readDouble(ENTER_RATIO_PROPERTY, 2.0D, 1.05D, 6.0D);
		double hardRatio = readDouble(HARD_RATIO_PROPERTY, 5.0D, enterRatio + 0.1D, 16.0D);
		double over = frameMs / baseline;
		double instantaneous = clamp01((over - enterRatio) / (hardRatio - enterRatio));

		if (watchdogSpike) {
			instantaneous = Math.max(instantaneous, readDouble(SPIKE_FLOOR_PROPERTY, 0.5D, 0.0D, 1.0D));
		}

		// Rise fast so a spike is clamped on the very next frame.
		double riseAlpha = readDouble(RISE_ALPHA_PROPERTY, 0.60D, 0.05D, 1.0D);
		double alpha;
		if (instantaneous > pressure) {
			alpha = riseAlpha;
		} else {
			// Adaptive decay: release work (and let FPS climb back) faster the further the frame drops below the
			// enter threshold, but ease out slowly when frames hover right at the threshold so consumers do not
			// oscillate/flicker. headroom: 0 at the enter ratio, 1 once frames return to the baseline.
			double decayMin = readDouble(DECAY_ALPHA_PROPERTY, 0.05D, 0.005D, 1.0D);
			double decayMax = readDouble(DECAY_ALPHA_MAX_PROPERTY, 0.30D, decayMin, 1.0D);
			double headroom = clamp01((enterRatio - over) / Math.max(0.01D, enterRatio - 1.0D));
			alpha = decayMin + (decayMax - decayMin) * headroom;
		}
		double next = pressure + alpha * (instantaneous - pressure);
		pressure = clamp01(next);

		double minScale = readDouble(MIN_SCALE_PROPERTY, 0.25D, 0.05D, 1.0D);
		workScale = 1.0D - pressure * (1.0D - minScale);
	}

	/** Reset to the healthy state (no pressure). */
	public static void reset() {
		pressure = 0.0D;
		workScale = 1.0D;
		frameSeq++;
		lastFrameMs = -1.0D;
		baselineMs = -1.0D;
	}

	/** Smoothed pressure in [0,1]; 0 = comfortably within budget, 1 = sustained hard overrun. */
	public static double pressure01() {
		return pressure;
	}

	/**
	 * Multiplier in [minScale, 1] that consumers apply to their per-frame work budgets. 1.0 when healthy.
	 */
	public static double workScale() {
		return workScale;
	}

	/** True when the absorber is actively trimming work this frame. Consumers should no-op when this is false. */
	public static boolean isAbsorbing() {
		return isEnabled() && pressure > 0.02D;
	}

	/** Monotonic per-frame sequence number; consumers use it to detect frame boundaries and reset per-frame counters. */
	public static long frameSeq() {
		return frameSeq;
	}

	public static String describeState() {
		return "spikeAbsorber[enabled="
			+ (isEnabled() ? "on" : "off")
			+ ", pressure="
			+ String.format(Locale.ROOT, "%.2f", pressure)
			+ ", workScale="
			+ String.format(Locale.ROOT, "%.2f", workScale)
			+ ", frameMs="
			+ (lastFrameMs < 0.0D ? "-" : String.format(Locale.ROOT, "%.2f", lastFrameMs))
			+ ", baselineMs="
			+ (baselineMs < 0.0D ? "-" : String.format(Locale.ROOT, "%.2f", baselineMs))
			+ "]";
	}

	private static boolean isEnabled() {
		return fr.hoyatla.pauc.PauCTunables.readBoolean(ENABLED_PROPERTY, true);
	}

	private static double clamp01(double value) {
		return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
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
