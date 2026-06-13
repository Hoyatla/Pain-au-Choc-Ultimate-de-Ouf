package fr.hoyatla.pauc.platform.forge.diagnostics;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.lod.PauCLodDiagnostics;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import fr.hoyatla.pauc.platform.forge.client.PauCClientChunkRetentionManager;
import fr.hoyatla.pauc.platform.forge.client.PauCCudaWorker;
import fr.hoyatla.pauc.platform.forge.client.PauCClientDistanceGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFrameMetrics;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFpsGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientLodGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientSurfaceLodMode;
import fr.hoyatla.pauc.platform.forge.client.PauCClientTargetFps;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatibilityGuards;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class PauCPerformanceTelemetry {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final String DETAIL_CAPTURE_INTERVAL_MS_PROPERTY = "pauc.telemetry.detailCaptureIntervalMs";
	private static final long LOG_INTERVAL_MS = 5_000L;
	private static long sessionStartedAtMs;
	private static long lastLogAtMs;
	private static long lastDetailCaptureAtMs;
	private static int samples;
	private static int fpsSamples;
	private static int minFps;
	private static int maxFps;
	private static long fpsTotal;
	private static int belowTargetSamples;
	private static int severeBelowTargetSamples;
	private static int criticalFpsSamples;
	private static int worstFpsTargetGap;
	private static int currentBelowTargetStreak;
	private static int longestBelowTargetStreak;
	private static int currentSevereBelowTargetStreak;
	private static int longestSevereBelowTargetStreak;
	private static long maxUsedMemoryBytes;
	private static long lastUsedMemoryBytes;
	private static long lastMaxMemoryBytes;
	private static String lastLodLine = "not-captured";
	private static String lastClientLine = "not-captured";
	private static String lastPolicyLine = "not-captured";
	private static String lastShaderLine = "not-captured";
	private static String lastValidationLine = "not-captured";
	private static String lastCullingLine = "not-captured";
	private static String lastFpsGovernorLine = "not-captured";
	private static String lastDistanceGovernorLine = "not-captured";
	private static String lastSurfaceLodLine = "not-captured";
	private static String lastCudaLine = "not-captured";
	private static String lastCompatLine = "not-captured";
	private static String lastVillageLine = "not-captured";
	private static String lastReloadLine = "not-captured";
	private static boolean active;

	private PauCPerformanceTelemetry() {
	}

	public static void onClientSessionResumed() {
		reset();
		active = true;
		sessionStartedAtMs = System.currentTimeMillis();
		lastLogAtMs = sessionStartedAtMs;
	}

	public static void onClientTick(Minecraft minecraft) {
		if (!active || minecraft == null || !PauCLodDiagnostics.enabled()) {
			return;
		}

		samples++;
		int fps = queryFps(minecraft);
		int targetFps = PauCClientTargetFps.effectiveTargetFps(minecraft);
		if (fps > 0) {
			fpsSamples++;
			fpsTotal += fps;
			minFps = minFps <= 0 ? fps : Math.min(minFps, fps);
			maxFps = Math.max(maxFps, fps);
			if (fps < targetFps) {
				belowTargetSamples++;
				currentBelowTargetStreak++;
				longestBelowTargetStreak = Math.max(longestBelowTargetStreak, currentBelowTargetStreak);
			} else {
				currentBelowTargetStreak = 0;
			}
			if (fps < targetFps * 0.65D) {
				severeBelowTargetSamples++;
				currentSevereBelowTargetStreak++;
				longestSevereBelowTargetStreak = Math.max(longestSevereBelowTargetStreak, currentSevereBelowTargetStreak);
			} else {
				currentSevereBelowTargetStreak = 0;
			}
			if (fps <= 10) {
				criticalFpsSamples++;
			}
			worstFpsTargetGap = Math.max(worstFpsTargetGap, Math.max(0, targetFps - fps));
		}

		Runtime runtime = Runtime.getRuntime();
		lastUsedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
		lastMaxMemoryBytes = runtime.maxMemory();
		maxUsedMemoryBytes = Math.max(maxUsedMemoryBytes, lastUsedMemoryBytes);
		PauCVillagePerformanceDiagnostics.onClientTick(minecraft);

		long now = System.currentTimeMillis();
		long detailIntervalMs = readLong(DETAIL_CAPTURE_INTERVAL_MS_PROPERTY, 1_000L, 250L, 30_000L);
		if (now - lastDetailCaptureAtMs >= detailIntervalMs || now - lastLogAtMs >= LOG_INTERVAL_MS) {
			captureDetailLines();
			lastDetailCaptureAtMs = now;
		}
		if (now - lastLogAtMs >= LOG_INTERVAL_MS) {
			lastLogAtMs = now;
			LOGGER.info("PauC performance telemetry: {}", describe());
		}
	}

	public static void onClientSessionFinished(String reason) {
		if (!active) {
			return;
		}

		active = false;
		writeReport(reason);
	}

	public static String describe() {
		return "perf[fps="
			+ averageFps()
			+ " avg/"
			+ minFps
			+ " min/"
			+ maxFps
			+ " max, frame="
			+ PauCClientFrameMetrics.describeFrameTimes()
			+ ", build="
			+ PauCIdentity.buildId()
			+ ", target="
			+ PauCClientTargetFps.effectiveTargetFps()
			+ ", below="
			+ percent(belowTargetSamples, fpsSamples)
			+ "%, severe="
			+ percent(severeBelowTargetSamples, fpsSamples)
			+ "%, critical="
			+ percent(criticalFpsSamples, fpsSamples)
			+ "%, worstGap="
			+ worstFpsTargetGap
			+ ", streak="
			+ longestBelowTargetStreak
			+ "/"
			+ longestSevereBelowTargetStreak
			+ " samples, heap="
			+ mib(lastUsedMemoryBytes)
			+ "/"
			+ mib(lastMaxMemoryBytes)
			+ "MiB, maxHeapUsed="
			+ mib(maxUsedMemoryBytes)
			+ "MiB, antiReload="
			+ lastReloadLine
			+ "]";
	}

	private static void writeReport(String reason) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameDirectory == null || !PauCLodDiagnostics.enabled()) {
			return;
		}

		Path reportDir = minecraft.gameDirectory.toPath().resolve("pauc_diagnostics");
		Path reportPath = reportDir.resolve("performance-" + FILE_TIMESTAMP.format(Instant.now()) + ".json");
		String json = "{\n"
			+ "  \"buildId\": \"" + json(PauCIdentity.buildId()) + "\",\n"
			+ "  \"buildVersion\": \"" + json(PauCIdentity.runtimeVersion()) + "\",\n"
			+ "  \"buildGitHash\": \"" + json(PauCIdentity.buildGitHash()) + "\",\n"
			+ "  \"reason\": \"" + json(reason) + "\",\n"
			+ "  \"durationMs\": " + Math.max(0L, System.currentTimeMillis() - sessionStartedAtMs) + ",\n"
			+ "  \"samples\": " + samples + ",\n"
			+ "  \"fpsSamples\": " + fpsSamples + ",\n"
			+ "  \"frameSamples\": " + PauCClientFrameMetrics.frameSampleCount() + ",\n"
			+ "  \"targetFps\": " + PauCClientTargetFps.effectiveTargetFps() + ",\n"
			+ "  \"averageFps\": " + averageFps() + ",\n"
			+ "  \"minFps\": " + minFps + ",\n"
			+ "  \"maxFps\": " + maxFps + ",\n"
			+ "  \"averageFrameMs\": " + decimal(PauCClientFrameMetrics.averageFrameTimeMs()) + ",\n"
			+ "  \"p99FrameMs\": " + decimal(PauCClientFrameMetrics.percentileFrameTimeMs(99.0D)) + ",\n"
			+ "  \"p999FrameMs\": " + decimal(PauCClientFrameMetrics.percentileFrameTimeMs(99.9D)) + ",\n"
			+ "  \"frameTimeHistogram\": \"" + json(PauCClientFrameMetrics.histogramSummary()) + "\",\n"
			+ "  \"framePacingSleeps\": " + PauCClientFrameMetrics.pacingSleepCount() + ",\n"
			+ "  \"framePacingSleepMs\": " + decimal(PauCClientFrameMetrics.pacingSleepMs()) + ",\n"
			+ "  \"frameWatchdogSpikes\": " + PauCClientFrameMetrics.watchdogSpikeCount() + ",\n"
			+ "  \"lastFrameWatchdogMs\": " + decimal(PauCClientFrameMetrics.lastWatchdogFrameMs()) + ",\n"
			+ "  \"belowTargetPercent\": " + percent(belowTargetSamples, fpsSamples) + ",\n"
			+ "  \"severeBelowTargetPercent\": " + percent(severeBelowTargetSamples, fpsSamples) + ",\n"
			+ "  \"criticalFpsPercent\": " + percent(criticalFpsSamples, fpsSamples) + ",\n"
			+ "  \"worstFpsTargetGap\": " + worstFpsTargetGap + ",\n"
			+ "  \"longestBelowTargetStreakSamples\": " + longestBelowTargetStreak + ",\n"
			+ "  \"longestSevereBelowTargetStreakSamples\": " + longestSevereBelowTargetStreak + ",\n"
			+ "  \"heapUsedMiB\": " + mib(lastUsedMemoryBytes) + ",\n"
			+ "  \"heapMaxMiB\": " + mib(lastMaxMemoryBytes) + ",\n"
			+ "  \"heapPeakUsedMiB\": " + mib(maxUsedMemoryBytes) + ",\n"
			+ "  \"antiReload\": {\n"
			+ "    \"signatureChanges\": " + PauCLodReloadDiagnostics.signatureChanges() + ",\n"
			+ "    \"shaderRuntimeChanges\": " + PauCLodReloadDiagnostics.shaderRuntimeChanges() + ",\n"
			+ "    \"qualityOnlyChanges\": " + PauCLodReloadDiagnostics.qualityOnlyChanges() + ",\n"
			+ "    \"presentationOnlyChanges\": " + PauCLodReloadDiagnostics.presentationOnlyChanges() + ",\n"
			+ "    \"restoresQueued\": " + PauCLodReloadDiagnostics.restoresQueued() + ",\n"
			+ "    \"restoresApplied\": " + PauCLodReloadDiagnostics.restoresApplied() + ",\n"
			+ "    \"restores\": " + PauCLodReloadDiagnostics.restores() + ",\n"
			+ "    \"swaps\": " + PauCLodReloadDiagnostics.swaps() + ",\n"
			+ "    \"cacheClears\": " + PauCLodReloadDiagnostics.cacheClears() + ",\n"
			+ "    \"cacheClearsAvoided\": " + PauCLodReloadDiagnostics.cacheClearAvoided() + ",\n"
			+ "    \"cacheClearsDeferred\": " + PauCLodReloadDiagnostics.cacheClearDeferred() + ",\n"
			+ "    \"cacheClearsDisabled\": " + PauCLodReloadDiagnostics.cacheClearDisabled() + ",\n"
			+ "    \"coarseRefreshes\": " + PauCLodReloadDiagnostics.coarseRefreshes() + ",\n"
			+ "    \"coarseRefreshSkips\": " + PauCLodReloadDiagnostics.coarseRefreshSkips() + "\n"
			+ "  },\n"
			+ "  \"summary\": \"" + json(describe()) + "\",\n"
			+ "  \"lod\": \"" + json(lastLodLine) + "\",\n"
			+ "  \"policy\": \"" + json(lastPolicyLine) + "\",\n"
			+ "  \"shader\": \"" + json(lastShaderLine) + "\",\n"
			+ "  \"validation\": \"" + json(lastValidationLine) + "\",\n"
			+ "  \"culling\": \"" + json(lastCullingLine) + "\",\n"
			+ "  \"client\": \"" + json(lastClientLine) + "\",\n"
			+ "  \"lodGovernor\": \"" + json(PauCClientLodGovernor.describeState()) + "\",\n"
			+ "  \"fpsGovernor\": \"" + json(lastFpsGovernorLine) + "\",\n"
			+ "  \"distanceGovernor\": \"" + json(lastDistanceGovernorLine) + "\",\n"
			+ "  \"surfaceLod\": \"" + json(lastSurfaceLodLine) + "\",\n"
			+ "  \"cuda\": \"" + json(lastCudaLine) + "\",\n"
			+ "  \"village\": \"" + json(lastVillageLine) + "\",\n"
			+ "  \"antiReloadSummary\": \"" + json(lastReloadLine) + "\",\n"
			+ "  \"compatGuards\": \"" + json(lastCompatLine) + "\"\n"
			+ "}\n";

		try {
			Files.createDirectories(reportDir);
			Files.writeString(reportPath, json, StandardCharsets.UTF_8);
			LOGGER.info("PauC wrote performance telemetry report: {}", reportPath);
		} catch (IOException exception) {
			LOGGER.warn("PauC could not write performance telemetry report to {}.", reportPath, exception);
		}
	}

	private static void reset() {
		samples = 0;
		fpsSamples = 0;
		minFps = 0;
		maxFps = 0;
		fpsTotal = 0L;
		belowTargetSamples = 0;
		severeBelowTargetSamples = 0;
		criticalFpsSamples = 0;
		worstFpsTargetGap = 0;
		currentBelowTargetStreak = 0;
		longestBelowTargetStreak = 0;
		currentSevereBelowTargetStreak = 0;
		longestSevereBelowTargetStreak = 0;
		maxUsedMemoryBytes = 0L;
		lastUsedMemoryBytes = 0L;
		lastMaxMemoryBytes = 0L;
		lastLodLine = "not-captured";
		lastClientLine = "not-captured";
		lastPolicyLine = "not-captured";
		lastShaderLine = "not-captured";
		lastValidationLine = "not-captured";
		lastCullingLine = "not-captured";
		lastFpsGovernorLine = "not-captured";
		lastDistanceGovernorLine = "not-captured";
		lastSurfaceLodLine = "not-captured";
		lastCudaLine = "not-captured";
		lastCompatLine = "not-captured";
		lastVillageLine = "not-captured";
		lastReloadLine = "not-captured";
		lastDetailCaptureAtMs = 0L;
		PauCClientFrameMetrics.reset();
		PauCLodReloadDiagnostics.reset();
		PauCVillagePerformanceDiagnostics.reset();
		PauCCudaWorker.resetMetrics();
	}

	private static void captureDetailLines() {
		lastLodLine = PauCLodDiagnostics.overviewLine();
		lastClientLine = PauCClientChunkRetentionManager.describeState();
		lastPolicyLine = PauCLodDiagnostics.policyLine();
		lastShaderLine = PauCLodDiagnostics.shaderLine();
		lastValidationLine = PauCLodDiagnostics.validationLine();
		lastCullingLine = PauCLodDiagnostics.cullingLine();
		lastFpsGovernorLine = PauCClientFpsGovernor.describeState();
		lastDistanceGovernorLine = PauCClientDistanceGovernor.describeState();
		lastSurfaceLodLine = PauCClientSurfaceLodMode.describeState();
		lastCudaLine = PauCCudaWorker.describeMetrics();
		lastCompatLine = PauCCompatibilityGuards.describeState();
		lastVillageLine = PauCVillagePerformanceDiagnostics.describeState();
		lastReloadLine = PauCLodReloadDiagnostics.describeState();
	}

	private static int queryFps(Minecraft minecraft) {
		return PauCClientFrameMetrics.queryFps(minecraft);
	}

	private static int averageFps() {
		return fpsSamples <= 0 ? -1 : (int) Math.round(fpsTotal / (double) fpsSamples);
	}

	private static long mib(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

	private static int percent(int count, int total) {
		if (total <= 0) {
			return 0;
		}
		return (int) Math.round((count * 100.0D) / total);
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

	private static String decimal(double value) {
		return value < 0.0D ? "-1" : String.format(Locale.ROOT, "%.3f", value);
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
