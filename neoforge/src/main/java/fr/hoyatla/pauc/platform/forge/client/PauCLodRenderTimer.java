package fr.hoyatla.pauc.platform.forge.client;

import java.util.Locale;

/**
 * Measures the CPU (render-thread) time spent submitting the embedded Distant Horizons LOD terrain pass
 * ({@code ClientApi.renderLods()}) each frame. Pure instrumentation — no behaviour change.
 * <p>
 * Purpose: decide whether the heavy open-terrain frames are CPU-draw-bound (the LOD pass eats most of the frame → a
 * multi-draw-indirect batching of DH's per-section draws would help) or GPU-fill-bound (the LOD pass submits quickly but
 * the frame is still long → the GPU is the limit, batching won't help). Compare {@link #describeState()} against the
 * frame time from {@link PauCClientFrameMetrics}.
 */
public final class PauCLodRenderTimer {
	private static volatile double lastMs;
	private static volatile double avgMs = -1.0D;
	private static volatile double maxMs;
	private static volatile long samples;

	private PauCLodRenderTimer() {
	}

	public static void recordSolidPassNanos(long nanos) {
		if (nanos <= 0L) {
			return;
		}
		double ms = nanos / 1_000_000.0D;
		lastMs = ms;
		avgMs = avgMs < 0.0D ? ms : (avgMs * 0.95D) + (ms * 0.05D);
		if (ms > maxMs) {
			maxMs = ms;
		}
		samples++;
	}

	public static void reset() {
		lastMs = 0.0D;
		avgMs = -1.0D;
		maxMs = 0.0D;
		samples = 0L;
	}

	public static String describeState() {
		return "lodRenderPass[last="
			+ format(lastMs)
			+ ", avg="
			+ (avgMs < 0.0D ? "-" : format(avgMs))
			+ ", max="
			+ format(maxMs)
			+ ", n="
			+ samples
			+ "]";
	}

	private static String format(double ms) {
		return String.format(Locale.ROOT, "%.2fms", ms);
	}
}
