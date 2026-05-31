package fr.hoyatla.pauc.platform.forge.diagnostics;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodDiagnostics;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import fr.hoyatla.pauc.platform.forge.client.PauCClientChunkRetentionManager;
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
	private static final long LOG_INTERVAL_MS = 5_000L;
	private static long sessionStartedAtMs;
	private static long lastLogAtMs;
	private static int samples;
	private static int fpsSamples;
	private static int minFps;
	private static int maxFps;
	private static long fpsTotal;
	private static int belowTargetSamples;
	private static int severeBelowTargetSamples;
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
	private static String lastCompatLine = "not-captured";
	private static String lastVillageLine = "not-captured";
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
			}
			if (fps < targetFps * 0.65D) {
				severeBelowTargetSamples++;
			}
		}

		Runtime runtime = Runtime.getRuntime();
		lastUsedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
		lastMaxMemoryBytes = runtime.maxMemory();
		maxUsedMemoryBytes = Math.max(maxUsedMemoryBytes, lastUsedMemoryBytes);
		lastLodLine = PauCLodDiagnostics.overviewLine();
		lastClientLine = PauCClientChunkRetentionManager.describeState();
		lastPolicyLine = PauCLodDiagnostics.policyLine();
		lastShaderLine = PauCLodDiagnostics.shaderLine();
		lastValidationLine = PauCLodDiagnostics.validationLine();
		lastCullingLine = PauCLodDiagnostics.cullingLine();
		lastFpsGovernorLine = PauCClientFpsGovernor.describeState();
		lastDistanceGovernorLine = PauCClientDistanceGovernor.describeState();
		lastSurfaceLodLine = PauCClientSurfaceLodMode.describeState();
		lastCompatLine = PauCCompatibilityGuards.describeState();
		PauCVillagePerformanceDiagnostics.onClientTick(minecraft);
		lastVillageLine = PauCVillagePerformanceDiagnostics.describeState();

		long now = System.currentTimeMillis();
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
			+ " max, target="
			+ PauCClientTargetFps.effectiveTargetFps()
			+ ", below="
			+ percent(belowTargetSamples, fpsSamples)
			+ "%, severe="
			+ percent(severeBelowTargetSamples, fpsSamples)
			+ "%, heap="
			+ mib(lastUsedMemoryBytes)
			+ "/"
			+ mib(lastMaxMemoryBytes)
			+ "MiB, maxHeapUsed="
			+ mib(maxUsedMemoryBytes)
			+ "MiB]";
	}

	private static void writeReport(String reason) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameDirectory == null || !PauCLodDiagnostics.enabled()) {
			return;
		}

		Path reportDir = minecraft.gameDirectory.toPath().resolve("pauc_diagnostics");
		Path reportPath = reportDir.resolve("performance-" + FILE_TIMESTAMP.format(Instant.now()) + ".json");
		String json = "{\n"
			+ "  \"reason\": \"" + json(reason) + "\",\n"
			+ "  \"durationMs\": " + Math.max(0L, System.currentTimeMillis() - sessionStartedAtMs) + ",\n"
			+ "  \"samples\": " + samples + ",\n"
			+ "  \"fpsSamples\": " + fpsSamples + ",\n"
			+ "  \"targetFps\": " + PauCClientTargetFps.effectiveTargetFps() + ",\n"
			+ "  \"averageFps\": " + averageFps() + ",\n"
			+ "  \"minFps\": " + minFps + ",\n"
			+ "  \"maxFps\": " + maxFps + ",\n"
			+ "  \"belowTargetPercent\": " + percent(belowTargetSamples, fpsSamples) + ",\n"
			+ "  \"severeBelowTargetPercent\": " + percent(severeBelowTargetSamples, fpsSamples) + ",\n"
			+ "  \"heapUsedMiB\": " + mib(lastUsedMemoryBytes) + ",\n"
			+ "  \"heapMaxMiB\": " + mib(lastMaxMemoryBytes) + ",\n"
			+ "  \"heapPeakUsedMiB\": " + mib(maxUsedMemoryBytes) + ",\n"
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
			+ "  \"village\": \"" + json(lastVillageLine) + "\",\n"
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
		lastCompatLine = "not-captured";
		lastVillageLine = "not-captured";
		PauCVillagePerformanceDiagnostics.reset();
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
}
