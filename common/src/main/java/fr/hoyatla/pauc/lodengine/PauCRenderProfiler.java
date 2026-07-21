package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight per-subsystem CPU timing for the PauC render passes (LOD terrain, vegetation imposters,
 * distant structures, shadows, clouds). Wraps each render call in the event bridge; logs an aggregate
 * every few seconds during real gameplay so we MEASURE where render-thread time goes instead of guessing.
 *
 * <p>All calls happen on the render thread (single-threaded), so no synchronisation. These are CPU
 * render-thread milliseconds — the time to build buffers and submit draw commands — NOT GPU execution
 * time. That is exactly what catches rebuild spikes, per-frame scans, and draw-call overhead (the costs
 * PauC controls). Pure GPU fill-rate/overdraw would need GL timer queries (a later probe if CPU is clean).
 * Toggle with {@code -Dpauc.diag.renderProfiler=false}.</p>
 */
public final class PauCRenderProfiler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.diag.renderProfiler";
	private static final long FLUSH_INTERVAL_MS = 3_000L;

	private static final Map<String, long[]> STATS = new LinkedHashMap<>(); // name -> [totalNanos, samples, maxNanos]
	private static long lastFlushMs;

	private PauCRenderProfiler() {
	}

	public static boolean enabled() {
		return PauCTunables.readBoolean(ENABLED_PROPERTY, true);
	}

	/** Start timing a subsystem. Returns a nanosecond stamp to pass back to {@link #record}. */
	public static long begin() {
		return enabled() ? System.nanoTime() : 0L;
	}

	/** Record the elapsed CPU time of a subsystem since {@link #begin}. */
	public static void record(String subsystem, long startNanos) {
		if (startNanos == 0L || !enabled()) {
			return;
		}
		long dt = System.nanoTime() - startNanos;
		long[] s = STATS.get(subsystem);
		if (s == null) {
			s = new long[3];
			STATS.put(subsystem, s);
		}
		s[0] += dt;
		s[1]++;
		if (dt > s[2]) {
			s[2] = dt;
		}
	}

	/** Once per frame (end of the render passes): flush an aggregate line every few seconds. */
	public static void maybeFlush() {
		if (!enabled() || STATS.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastFlushMs == 0L) {
			lastFlushMs = now;
			return;
		}
		if (now - lastFlushMs < FLUSH_INTERVAL_MS) {
			return;
		}
		StringBuilder sb = new StringBuilder("PauC render profiler (CPU render-thread ms/frame): ");
		long frames = 0L;
		boolean first = true;
		for (Map.Entry<String, long[]> e : STATS.entrySet()) {
			long[] s = e.getValue();
			frames = Math.max(frames, s[1]);
			double avg = s[1] > 0 ? (s[0] / (double) s[1]) / 1_000_000.0 : 0.0;
			double max = s[2] / 1_000_000.0;
			if (!first) {
				sb.append(", ");
			}
			first = false;
			sb.append(String.format("%s avg=%.2f max=%.2f", e.getKey(), avg, max));
		}
		sb.append(String.format(" [frames=%d]", frames));
		LOGGER.info(sb.toString());
		STATS.clear();
		lastFlushMs = now;
	}
}
