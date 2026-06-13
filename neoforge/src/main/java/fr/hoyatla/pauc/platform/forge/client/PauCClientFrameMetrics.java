package fr.hoyatla.pauc.platform.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.Arrays;
import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.locks.LockSupport;

public final class PauCClientFrameMetrics {
	private static final String[] FPS_METHOD_NAMES = { "getFps", "m_260875_", "m" };
	private static final String[] FPS_STRING_FIELD_NAMES = { "fpsString", "f_90977_", "A" };
	private static final String FRAME_PACING_ENABLED_PROPERTY = "pauc.client.framePacing";
	private static final String FRAME_PACING_SLACK_MS_PROPERTY = "pauc.client.framePacingSlackMs";
	private static final String FRAME_PACING_MAX_SLEEP_MS_PROPERTY = "pauc.client.framePacingMaxSleepMs";
	private static final String FRAME_WATCHDOG_SPIKE_MS_PROPERTY = "pauc.client.frameWatchdogSpikeMs";
	private static final String FRAME_WATCHDOG_RELIEF_SPIKES_PROPERTY = "pauc.client.frameWatchdogReliefSpikes";
	private static final String RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY = "pauc.runtime.frameWatchdogSpike";
	private static final int MAX_FRAME_SAMPLES = 4096;
	private static final long MAX_FRAME_SAMPLE_NANOS = 500_000_000L;
	private static final long[] FRAME_SAMPLES_NANOS = new long[MAX_FRAME_SAMPLES];
	private static final int[] FRAME_BUCKET_UPPER_BOUNDS_MS = { 8, 12, 16, 20, 25, 33, 50, 66, 100, 150, 250 };
	private static final int[] FRAME_BUCKET_COUNTS = new int[FRAME_BUCKET_UPPER_BOUNDS_MS.length + 1];
	private static int frameWriteIndex;
	private static int frameSampleCount;
	private static long frameSampleTotalNanos;
	private static long lastFrameStageAtNanos;
	private static long lastPacedFrameAtNanos;
	private static long pacingSleepTotalNanos;
	private static int pacingSleepCount;
	private static int watchdogSpikeCount;
	private static int consecutiveWatchdogSpikes;
	private static double lastWatchdogFrameMs;

	private PauCClientFrameMetrics() {
	}

	public static void onRenderStage(RenderLevelStageEvent.Stage stage) {
		if (stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
			return;
		}

		long now = System.nanoTime();
		if (lastFrameStageAtNanos > 0L) {
			long frameNanos = now - lastFrameStageAtNanos;
			recordFrameSample(frameNanos);
			updateFrameWatchdog(frameNanos);
		}
		lastFrameStageAtNanos = applyFramePacing(now);
	}

	public static void reset() {
		Arrays.fill(FRAME_SAMPLES_NANOS, 0L);
		Arrays.fill(FRAME_BUCKET_COUNTS, 0);
		frameWriteIndex = 0;
		frameSampleCount = 0;
		frameSampleTotalNanos = 0L;
		lastFrameStageAtNanos = 0L;
		lastPacedFrameAtNanos = 0L;
		pacingSleepTotalNanos = 0L;
		pacingSleepCount = 0;
		watchdogSpikeCount = 0;
		consecutiveWatchdogSpikes = 0;
		lastWatchdogFrameMs = -1.0D;
		System.clearProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY);
	}

	public static int frameSampleCount() {
		return frameSampleCount;
	}

	public static double averageFrameTimeMs() {
		return frameSampleCount <= 0 ? -1.0D : nanosToMs(frameSampleTotalNanos / (double) frameSampleCount);
	}

	public static double percentileFrameTimeMs(double percentile) {
		if (frameSampleCount <= 0) {
			return -1.0D;
		}

		long[] samples = copySamples();
		Arrays.sort(samples);
		double clampedPercentile = Math.max(0.0D, Math.min(100.0D, percentile));
		int index = (int) Math.ceil((clampedPercentile / 100.0D) * samples.length) - 1;
		index = Math.max(0, Math.min(samples.length - 1, index));
		return nanosToMs(samples[index]);
	}

	public static String histogramSummary() {
		if (frameSampleCount <= 0) {
			return "-";
		}

		StringBuilder builder = new StringBuilder();
		int lowerBound = 0;
		for (int i = 0; i < FRAME_BUCKET_COUNTS.length; i++) {
			int count = FRAME_BUCKET_COUNTS[i];
			if (count <= 0) {
				lowerBound = i < FRAME_BUCKET_UPPER_BOUNDS_MS.length ? FRAME_BUCKET_UPPER_BOUNDS_MS[i] : lowerBound;
				continue;
			}
			if (builder.length() > 0) {
				builder.append('/');
			}
			if (i < FRAME_BUCKET_UPPER_BOUNDS_MS.length) {
				builder.append(lowerBound).append('-').append(FRAME_BUCKET_UPPER_BOUNDS_MS[i]).append("ms=").append(count);
				lowerBound = FRAME_BUCKET_UPPER_BOUNDS_MS[i];
			} else {
				builder.append(">").append(FRAME_BUCKET_UPPER_BOUNDS_MS[FRAME_BUCKET_UPPER_BOUNDS_MS.length - 1]).append("ms=").append(count);
			}
		}
		return builder.length() > 0 ? builder.toString() : "-";
	}

	public static String describeFrameTimes() {
		return "frames[samples="
			+ frameSampleCount()
			+ ", avg="
			+ formatMs(averageFrameTimeMs())
			+ ", p99="
			+ formatMs(percentileFrameTimeMs(99.0D))
			+ ", p999="
			+ formatMs(percentileFrameTimeMs(99.9D))
			+ ", pacingSleeps="
			+ pacingSleepCount()
			+ "@"
			+ formatMs(pacingSleepMs())
			+ ", watchdog="
			+ watchdogSpikeCount()
			+ "]";
	}

	public static int pacingSleepCount() {
		return pacingSleepCount;
	}

	public static double pacingSleepMs() {
		return nanosToMs(pacingSleepTotalNanos);
	}

	public static int watchdogSpikeCount() {
		return watchdogSpikeCount;
	}

	public static double lastWatchdogFrameMs() {
		return lastWatchdogFrameMs;
	}

	public static int queryFps(Minecraft minecraft) {
		if (minecraft == null) {
			return -1;
		}

		try {
			int fps = minecraft.getFps();
			if (fps > 0) {
				return fps;
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Reflection fallbacks below cover remapped production runtimes.
		}

		int reflectedFps = queryReflectedFps();
		if (reflectedFps > 0) {
			return reflectedFps;
		}

		return parseFpsString(queryFpsString());
	}

	private static int queryReflectedFps() {
		for (String methodName : FPS_METHOD_NAMES) {
			try {
				Method method = Minecraft.class.getDeclaredMethod(methodName);
				if (method.getReturnType() != Integer.TYPE || method.getParameterCount() != 0) {
					continue;
				}
				method.setAccessible(true);
				Object receiver = Modifier.isStatic(method.getModifiers()) ? null : Minecraft.getInstance();
				Object result = method.invoke(receiver);
				if (result instanceof Integer fps && fps > 0) {
					return fps;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Try the next mapping name.
			}
		}
		return -1;
	}

	private static String queryFpsString() {
		for (String fieldName : FPS_STRING_FIELD_NAMES) {
			try {
				Field field = Minecraft.class.getDeclaredField(fieldName);
				if (field.getType() != String.class) {
					continue;
				}
				field.setAccessible(true);
				Object receiver = Modifier.isStatic(field.getModifiers()) ? null : Minecraft.getInstance();
				Object result = field.get(receiver);
				if (result instanceof String fpsString && !fpsString.isBlank()) {
					return fpsString;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Try the next mapping name.
			}
		}
		return "";
	}

	private static int parseFpsString(String value) {
		if (value == null || value.isBlank()) {
			return -1;
		}

		int start = -1;
		for (int index = 0; index < value.length(); index++) {
			if (Character.isDigit(value.charAt(index))) {
				start = index;
				break;
			}
		}
		if (start < 0) {
			return -1;
		}

		int end = start;
		while (end < value.length() && Character.isDigit(value.charAt(end))) {
			end++;
		}

		try {
			return Integer.parseInt(value.substring(start, end));
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private static void recordFrameSample(long frameNanos) {
		if (frameNanos <= 0L || frameNanos > MAX_FRAME_SAMPLE_NANOS) {
			return;
		}

		if (frameSampleCount == MAX_FRAME_SAMPLES) {
			long previousSample = FRAME_SAMPLES_NANOS[frameWriteIndex];
			frameSampleTotalNanos -= previousSample;
			decrementBucket(previousSample);
		} else {
			frameSampleCount++;
		}

		FRAME_SAMPLES_NANOS[frameWriteIndex] = frameNanos;
		frameSampleTotalNanos += frameNanos;
		incrementBucket(frameNanos);
		frameWriteIndex = (frameWriteIndex + 1) % MAX_FRAME_SAMPLES;
	}

	private static long[] copySamples() {
		long[] copy = new long[frameSampleCount];
		for (int i = 0; i < frameSampleCount; i++) {
			copy[i] = FRAME_SAMPLES_NANOS[i];
		}
		return copy;
	}

	private static void incrementBucket(long frameNanos) {
		FRAME_BUCKET_COUNTS[bucketIndex(frameNanos)]++;
	}

	private static void decrementBucket(long frameNanos) {
		int bucketIndex = bucketIndex(frameNanos);
		if (FRAME_BUCKET_COUNTS[bucketIndex] > 0) {
			FRAME_BUCKET_COUNTS[bucketIndex]--;
		}
	}

	private static int bucketIndex(long frameNanos) {
		double frameMs = nanosToMs(frameNanos);
		for (int i = 0; i < FRAME_BUCKET_UPPER_BOUNDS_MS.length; i++) {
			if (frameMs <= FRAME_BUCKET_UPPER_BOUNDS_MS[i]) {
				return i;
			}
		}
		return FRAME_BUCKET_UPPER_BOUNDS_MS.length;
	}

	private static void updateFrameWatchdog(long frameNanos) {
		long spikeMillis = readLong(FRAME_WATCHDOG_SPIKE_MS_PROPERTY, 90L, 16L, 1_000L);
		double frameMs = nanosToMs(frameNanos);
		if (frameMs >= spikeMillis) {
			watchdogSpikeCount++;
			consecutiveWatchdogSpikes++;
			lastWatchdogFrameMs = frameMs;
		} else {
			consecutiveWatchdogSpikes = 0;
		}
		int reliefSpikes = readInt(FRAME_WATCHDOG_RELIEF_SPIKES_PROPERTY, 2, 1, 8);
		System.setProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY, Boolean.toString(consecutiveWatchdogSpikes >= reliefSpikes));
	}

	private static long applyFramePacing(long now) {
		if (!readBoolean(FRAME_PACING_ENABLED_PROPERTY, true)) {
			lastPacedFrameAtNanos = now;
			return now;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || PauCClientFpsGovernor.isUnderPressure()) {
			lastPacedFrameAtNanos = now;
			return now;
		}

		int targetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		if (targetFps <= 0 || targetFps > 240) {
			lastPacedFrameAtNanos = now;
			return now;
		}

		long targetFrameNanos = Math.max(1L, 1_000_000_000L / targetFps);
		long slackNanos = readLong(FRAME_PACING_SLACK_MS_PROPERTY, 1L, 0L, 8L) * 1_000_000L;
		long maxSleepNanos = readLong(FRAME_PACING_MAX_SLEEP_MS_PROPERTY, 4L, 0L, 20L) * 1_000_000L;
		if (lastPacedFrameAtNanos > 0L) {
			long elapsed = now - lastPacedFrameAtNanos;
			long sleepNanos = Math.min(maxSleepNanos, targetFrameNanos - elapsed - slackNanos);
			if (sleepNanos > 0L) {
				LockSupport.parkNanos(sleepNanos);
				pacingSleepCount++;
				pacingSleepTotalNanos += sleepNanos;
				now = System.nanoTime();
			}
		}
		lastPacedFrameAtNanos = now;
		return now;
	}

	private static double nanosToMs(double nanos) {
		return nanos / 1_000_000.0D;
	}

	private static String formatMs(double value) {
		return value < 0.0D ? "-" : String.format(Locale.ROOT, "%.2fms", value);
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

	private static long readLong(String key, long fallback, long min, long max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Long.parseLong(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
