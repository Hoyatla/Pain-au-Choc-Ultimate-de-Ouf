package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.lod.PauCFrameSpikeAbsorber;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

public final class PauCClientFrameMetrics {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final DateTimeFormatter WATCHDOG_FILE_TIMESTAMP =
		DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
	private static final String[] FPS_METHOD_NAMES = { "getFps", "m_260875_", "m" };
	private static final String[] FPS_STRING_FIELD_NAMES = { "fpsString", "f_90977_", "A" };
	private static final String FRAME_PACING_ENABLED_PROPERTY = "pauc.client.framePacing";
	private static final String FRAME_PACING_SLACK_MS_PROPERTY = "pauc.client.framePacingSlackMs";
	private static final String FRAME_PACING_MAX_SLEEP_MS_PROPERTY = "pauc.client.framePacingMaxSleepMs";
	private static final String FRAME_WATCHDOG_SPIKE_MS_PROPERTY = "pauc.client.frameWatchdogSpikeMs";
	private static final String FRAME_WATCHDOG_DUMP_MS_PROPERTY = "pauc.client.frameWatchdogDumpMs";
	private static final String FRAME_WATCHDOG_DUMP_COOLDOWN_MS_PROPERTY = "pauc.client.frameWatchdogDumpCooldownMs";
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
	private static volatile long lastWatchdogDumpAtMs;
	private static volatile boolean watchdogDumpRunning;
	private static volatile long cachedFpsFrameSeq = Long.MIN_VALUE;
	private static volatile Minecraft cachedFpsMinecraft;
	private static volatile int cachedFps = -1;

	private PauCClientFrameMetrics() {
	}

	public static void onRenderStage(RenderLevelStageEvent.Stage stage) {
		if (stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
			return;
		}
		// Under Iris/shaders the render-stage event fires once during the shadow pass AND once during the main pass,
		// so measuring between consecutive fires yields sub-frame (≈half) intervals — which made the absorber and
		// watchdog under-read true frame time by ~2x and barely react to real GPU-bound dips. Ignore the shadow-pass
		// firing so every sample is a true frame boundary.
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return;
		}
		long now = System.nanoTime();
		// Functional work (scheduler frame-start) ALWAYS runs — it must never depend on the metrics gate.
		PauCScheduler.onClientFrameStart();

		// METRICS ONLY are gated to active gameplay: a paused game / open menu renders the world trivially (or behind a
		// screen), producing meaninglessly high fps that polluted the in-game stats. screen==null = real play. When not
		// active, reset the timestamp so the first frame after resuming does not record the paused gap as a giant spike.
		Minecraft minecraft = Minecraft.getInstance();
		boolean activeGameplay = minecraft != null
			&& minecraft.level != null
			&& minecraft.player != null
			&& minecraft.screen == null;
		if (activeGameplay) {
			if (lastFrameStageAtNanos > 0L) {
				long frameNanos = now - lastFrameStageAtNanos;
				recordFrameSample(frameNanos);
				updateFrameWatchdog(frameNanos);
				updateSpikeAbsorber(frameNanos);
			}
			lastFrameStageAtNanos = applyFramePacing(now);
		} else {
			lastFrameStageAtNanos = 0L;
		}
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
		cachedFpsFrameSeq = Long.MIN_VALUE;
		cachedFpsMinecraft = null;
		cachedFps = -1;
		System.clearProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY);
		PauCFrameSpikeAbsorber.reset();
		PauCLodRenderTimer.reset();
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
			+ "] "
			+ PauCLodRenderTimer.describeState();
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

		long frameSeq = PauCFrameSpikeAbsorber.frameSeq();
		if (frameSeq == cachedFpsFrameSeq && cachedFpsMinecraft == minecraft) {
			return cachedFps;
		}

		int fps = queryFpsUncached(minecraft);
		cachedFpsFrameSeq = frameSeq;
		cachedFpsMinecraft = minecraft;
		cachedFps = fps;
		return fps;
	}

	private static int queryFpsUncached(Minecraft minecraft) {
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
			long dumpMillis = readLong(FRAME_WATCHDOG_DUMP_MS_PROPERTY, 250L, 100L, 2_000L);
			if (frameMs >= dumpMillis) {
				scheduleFrameWatchdogDump(frameMs);
			}
		} else {
			consecutiveWatchdogSpikes = 0;
		}
		int reliefSpikes = readInt(FRAME_WATCHDOG_RELIEF_SPIKES_PROPERTY, 2, 1, 8);
		System.setProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY, Boolean.toString(consecutiveWatchdogSpikes >= reliefSpikes));
	}

	private static void updateSpikeAbsorber(long frameNanos) {
		boolean watchdogSpike = Boolean.parseBoolean(System.getProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY, "false"));
		PauCFrameSpikeAbsorber.update(nanosToMs(frameNanos), watchdogSpike);
	}

	private static void scheduleFrameWatchdogDump(double frameMs) {
		long nowMs = System.currentTimeMillis();
		long cooldownMs = readLong(FRAME_WATCHDOG_DUMP_COOLDOWN_MS_PROPERTY, 30_000L, 1_000L, 600_000L);
		if (watchdogDumpRunning || nowMs - lastWatchdogDumpAtMs < cooldownMs) {
			return;
		}

		lastWatchdogDumpAtMs = nowMs;
		watchdogDumpRunning = true;
		Thread dumpThread = new Thread(() -> {
			try {
				writeFrameWatchdogDump(frameMs, nowMs);
			} catch (RuntimeException exception) {
				LOGGER.warn("PauC could not prepare the frame watchdog dump.", exception);
			} finally {
				watchdogDumpRunning = false;
			}
		}, "PauC Frame Watchdog Dump");
		dumpThread.setDaemon(true);
		dumpThread.start();
	}

	private static void writeFrameWatchdogDump(double frameMs, long capturedAtMs) {
		Minecraft minecraft = Minecraft.getInstance();
		Path reportDir = minecraft != null && minecraft.gameDirectory != null
			? minecraft.gameDirectory.toPath().resolve("pauc_diagnostics")
			: Path.of("pauc_diagnostics");
		Path dumpPath = reportDir.resolve("frame-watchdog-" + WATCHDOG_FILE_TIMESTAMP.format(Instant.ofEpochMilli(capturedAtMs)) + ".txt");
		StringBuilder builder = new StringBuilder(64 * 1024);
		builder.append("PauC Frame Watchdog Dump\n");
		builder.append("buildId=").append(PauCIdentity.buildId()).append('\n');
		builder.append("frameMs=").append(formatMs(frameMs)).append('\n');
		builder.append("frames=").append(describeFrameTimes()).append('\n');
		builder.append("fpsGovernor=").append(PauCClientFpsGovernor.describeState()).append('\n');
		builder.append("spikeAbsorber=").append(PauCFrameSpikeAbsorber.describeState()).append('\n');
		builder.append("lodRenderPass=").append(PauCLodRenderTimer.describeState()).append('\n');
		builder.append("shaderShadowBudget=").append(fr.hoyatla.pauc.lod.PauCShaderShadowBudget.describeState()).append('\n');
		builder.append("particleBudget=").append(fr.hoyatla.pauc.lod.PauCParticleBudget.describeState()).append('\n');
		builder.append("entityRenderBudget=").append(fr.hoyatla.pauc.lod.PauCEntityRenderBudget.describeState()).append('\n');
		builder.append("blockEntityRenderBudget=").append(fr.hoyatla.pauc.lod.PauCBlockEntityRenderBudget.describeState()).append('\n');
		builder.append("entityOcclusion=").append(fr.hoyatla.pauc.lod.PauCEntityOcclusionCulling.describeState()).append('\n');
		builder.append("blockEntityOcclusion=").append(fr.hoyatla.pauc.lod.PauCBlockEntityOcclusionCulling.describeState()).append('\n');
		builder.append("lodGovernor=").append(PauCClientLodGovernor.describeState()).append('\n');
		builder.append("lodRuntime=").append(PauCEmbeddedLodRuntimeDiagnostics.describeState()).append('\n');
		builder.append("fillPresentation=").append(PauCEmbeddedLodRuntimeDiagnostics.describeFillPresentationState()).append('\n');
		builder.append("scheduler=").append(PauCScheduler.describeState()).append('\n');
		builder.append('\n').append("Threads\n");
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
			Thread thread = entry.getKey();
			builder.append('\n')
				.append('"').append(thread.getName()).append('"')
				.append(" state=").append(thread.getState())
				.append(" daemon=").append(thread.isDaemon())
				.append('\n');
			for (StackTraceElement stackTraceElement : entry.getValue()) {
				builder.append("  at ").append(stackTraceElement).append('\n');
			}
		}
		try {
			Files.createDirectories(reportDir);
			Files.writeString(dumpPath, builder.toString(), StandardCharsets.UTF_8);
			LOGGER.warn("PauC wrote frame watchdog dump: {}", dumpPath);
		} catch (IOException exception) {
			LOGGER.warn("PauC could not write frame watchdog dump to {}.", dumpPath, exception);
		}
	}

	private static long applyFramePacing(long now) {
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
