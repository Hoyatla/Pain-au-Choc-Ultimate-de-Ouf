package fr.hoyatla.pauc.platform.forge.diagnostics;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.lod.PauCTerrainGeneratorDetector;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFrameMetrics;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFpsGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFrontierWarmupManager;
import fr.hoyatla.pauc.platform.forge.client.PauCEmbeddedDhBridge;
import fr.hoyatla.pauc.platform.forge.client.PauCClientGpuPathController;
import fr.hoyatla.pauc.platform.forge.client.PauCClientLodGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientSurfaceLodMode;
import fr.hoyatla.pauc.platform.forge.client.PauCClientTargetFps;
import fr.hoyatla.pauc.platform.forge.client.PauCDynamicResolution;
import fr.hoyatla.pauc.platform.forge.client.PauCEmbeddedLodRuntimeDiagnostics;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class PauCTelemetryTimeline {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String HEARTBEAT_INTERVAL_MS_PROPERTY = "pauc.telemetry.timelineHeartbeatMs";
	private static final String RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY = "pauc.runtime.frameWatchdogSpike";
	private static boolean active;
	private static long sessionStartedAtMs;
	private static String sessionId = "";
	private static long lastHeartbeatAtMs;
	private static String lastGovernorKey = "";
	private static String lastSurfaceKey = "";
	private static String lastLodKey = "";
	private static boolean lastWatchdogSpike;
	private static int heartbeats;
	private static int governorTransitions;
	private static int surfaceTransitions;
	private static int lodTransitions;
	private static int watchdogTransitions;
	private static final StringBuilder JSONL = new StringBuilder(32 * 1024);

	private PauCTelemetryTimeline() {
	}

	public static void onClientSessionResumed(long startedAtMs, String newSessionId) {
		reset();
		active = true;
		sessionStartedAtMs = startedAtMs;
		sessionId = newSessionId == null ? "" : newSessionId;
		appendSessionPhase(startedAtMs, "start", "resumed");
	}

	public static void onClientTick(Minecraft minecraft, int fps, int targetFps, long usedMemoryBytes, long maxMemoryBytes) {
		if (!active || minecraft == null) {
			return;
		}

		long now = System.currentTimeMillis();
		String governorKey = PauCClientFpsGovernor.telemetryStateKey();
		String surfaceKey = fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()
			? PauCClientSurfaceLodMode.telemetryStateKey()
			: "dh-off";
		String lodKey = PauCClientLodGovernor.telemetryStateKey();
		boolean watchdogSpike = Boolean.parseBoolean(System.getProperty(RUNTIME_FRAME_WATCHDOG_SPIKE_PROPERTY, "false"));
		recordTransition(now, "governor", lastGovernorKey, governorKey);
		recordTransition(now, "surface", lastSurfaceKey, surfaceKey);
		recordTransition(now, "lodRange", lastLodKey, lodKey);
		recordWatchdogTransition(now, watchdogSpike);
		lastGovernorKey = governorKey;
		lastSurfaceKey = surfaceKey;
		lastLodKey = lodKey;

		long heartbeatIntervalMs = readLong(HEARTBEAT_INTERVAL_MS_PROPERTY, 1_000L, 250L, 10_000L);
		if (lastHeartbeatAtMs == 0L || now - lastHeartbeatAtMs >= heartbeatIntervalMs) {
			lastHeartbeatAtMs = now;
			heartbeats++;
			appendHeartbeat(now, fps, targetFps, usedMemoryBytes, maxMemoryBytes, governorKey, surfaceKey, lodKey, watchdogSpike);
		}
	}

	public static TraceSummary onClientSessionFinished(@Nullable Path reportDir, String reason) {
		if (!active) {
			return TraceSummary.unavailable();
		}

		long now = System.currentTimeMillis();
		appendSessionPhase(now, "end", reason);
		active = false;
		if (reportDir == null || JSONL.length() <= 0) {
			return TraceSummary.unavailable();
		}

		Path timelinePath = reportDir.resolve("timeline-" + sessionId + ".jsonl");
		try {
			Files.createDirectories(reportDir);
			Files.writeString(timelinePath, JSONL.toString(), StandardCharsets.UTF_8);
			LOGGER.info("PauC wrote telemetry timeline: {}", timelinePath);
			return new TraceSummary(
				true,
				timelinePath.getFileName().toString(),
				heartbeats,
				governorTransitions,
				surfaceTransitions,
				lodTransitions,
				watchdogTransitions
			);
		} catch (IOException exception) {
			LOGGER.warn("PauC could not write telemetry timeline to {}.", timelinePath, exception);
			return TraceSummary.unavailable();
		}
	}

	public static void reset() {
		active = false;
		sessionStartedAtMs = 0L;
		sessionId = "";
		lastHeartbeatAtMs = 0L;
		lastGovernorKey = "";
		lastSurfaceKey = "";
		lastLodKey = "";
		lastWatchdogSpike = false;
		heartbeats = 0;
		governorTransitions = 0;
		surfaceTransitions = 0;
		lodTransitions = 0;
		watchdogTransitions = 0;
		JSONL.setLength(0);
	}

	private static void recordTransition(long now, String component, String previousValue, String nextValue) {
		if (nextValue == null || nextValue.isBlank() || nextValue.equals(previousValue)) {
			return;
		}
		if ("governor".equals(component)) {
			governorTransitions++;
		} else if ("surface".equals(component)) {
			surfaceTransitions++;
		} else if ("lodRange".equals(component)) {
			lodTransitions++;
		}
		appendLine(
			"{\"sessionId\":\"" + json(sessionId) + "\","
				+ "\"tMs\":" + elapsedMs(now) + ','
				+ "\"type\":\"transition\","
				+ "\"component\":\"" + component + "\","
				+ "\"from\":\"" + json(previousValue) + "\","
				+ "\"to\":\"" + json(nextValue) + "\"}"
		);
	}

	private static void recordWatchdogTransition(long now, boolean watchdogSpike) {
		if (watchdogSpike == lastWatchdogSpike) {
			return;
		}
		lastWatchdogSpike = watchdogSpike;
		watchdogTransitions++;
		appendLine(
			"{\"sessionId\":\"" + json(sessionId) + "\","
				+ "\"tMs\":" + elapsedMs(now) + ','
				+ "\"type\":\"transition\","
				+ "\"component\":\"watchdog\","
				+ "\"to\":\"" + (watchdogSpike ? "spike" : "clear") + "\"}"
		);
	}

	private static void appendSessionPhase(long now, String phase, String reason) {
		appendLine(
			"{\"sessionId\":\"" + json(sessionId) + "\","
				+ "\"tMs\":" + elapsedMs(now) + ','
				+ "\"type\":\"session\","
				+ "\"phase\":\"" + phase + "\","
				+ "\"reason\":\"" + json(reason) + "\","
				+ "\"buildId\":\"" + json(PauCIdentity.buildId()) + "\","
				+ "\"version\":\"" + json(PauCIdentity.runtimeVersion()) + "\"}"
		);
	}

	private static void appendHeartbeat(
		long now,
		int fps,
		int targetFps,
		long usedMemoryBytes,
		long maxMemoryBytes,
		String governorKey,
		String surfaceKey,
		String lodKey,
		boolean watchdogSpike
	) {
		appendLine(
			"{\"sessionId\":\"" + json(sessionId) + "\","
				+ "\"tMs\":" + elapsedMs(now) + ','
				+ "\"type\":\"heartbeat\","
				+ "\"fps\":" + fps + ','
				+ "\"targetFps\":" + targetFps + ','
				+ "\"fpsReferenceMode\":\"" + json(PauCClientTargetFps.referenceMode(Minecraft.getInstance())) + "\","
				+ "\"avgFrameMs\":" + decimal(PauCClientFrameMetrics.averageFrameTimeMs()) + ','
				+ "\"p99FrameMs\":" + decimal(PauCClientFrameMetrics.percentileFrameTimeMs(99.0D)) + ','
				+ "\"heapUsedMiB\":" + mib(usedMemoryBytes) + ','
				+ "\"heapMaxMiB\":" + mib(maxMemoryBytes) + ','
				+ "\"queuePressure\":" + decimal(PauCEmbeddedLodRuntimeDiagnostics.backlogPressure()) + ','
				+ "\"backlogTasks\":" + PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() + ','
				+ "\"pendingTasks\":" + PauCEmbeddedLodRuntimeDiagnostics.pendingTasks() + ','
				+ "\"pendingChunks\":" + PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() + ','
				+ "\"avgChunkMs\":" + decimal(PauCEmbeddedLodRuntimeDiagnostics.rollingAverageChunkMs()) + ','
				+ "\"drsScale\":" + decimal(PauCDynamicResolution.scale()) + ','
				+ "\"watchdogSpike\":" + watchdogSpike + ','
				+ "\"terrain\":\"" + json(PauCTerrainGeneratorDetector.describeCurrentClientContext()) + "\","
				+ "\"gpu\":\"" + json(PauCClientGpuPathController.describeState()) + "\","
				+ "\"scheduler\":\"" + json(PauCScheduler.describeState()) + "\","
				+ "\"governor\":\"" + json(governorKey) + "\","
				+ "\"governorActuation\":\"" + json(PauCClientFpsGovernor.describeActuationState()) + "\","
				+ "\"embeddedDhActuation\":\"" + json(fr.hoyatla.pauc.lod.PauCLodBridgeAccess.describeActuationState()) + "\","
				+ "\"frontierActuation\":\"" + json(PauCClientFrontierWarmupManager.describeActuationState()) + "\","
				+ "\"surface\":\"" + json(surfaceKey) + "\","
				+ "\"lodRange\":\"" + json(lodKey) + "\","
				+ "\"drs\":\"" + json(PauCDynamicResolution.describeState()) + "\","
				+ "\"fill\":\"" + json(PauCEmbeddedLodRuntimeDiagnostics.describeFillPresentationState()) + "\","
				+ "\"reload\":\"" + json(PauCLodReloadDiagnostics.describeState()) + "\"}"
		);
	}

	private static void appendLine(String line) {
		JSONL.append(line).append('\n');
	}

	private static long elapsedMs(long now) {
		return Math.max(0L, now - sessionStartedAtMs);
	}

	private static long mib(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

	private static String decimal(double value) {
		return value < 0.0D ? "-1" : String.format(Locale.ROOT, "%.3f", value);
	}

	private static String json(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\r", "\\r")
			.replace("\n", "\\n")
			.replace("\t", "\\t");
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

	public record TraceSummary(
		boolean available,
		String fileName,
		int heartbeats,
		int governorTransitions,
		int surfaceTransitions,
		int lodTransitions,
		int watchdogTransitions
	) {
		private static TraceSummary unavailable() {
			return new TraceSummary(false, "", 0, 0, 0, 0, 0);
		}
	}
}
