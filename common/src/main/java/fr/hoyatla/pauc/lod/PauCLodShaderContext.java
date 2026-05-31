package fr.hoyatla.pauc.lod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class PauCLodShaderContext {
	private static final String FALLBACK_PROPERTY = "pauc.lod.shaderFallback";
	private static final String FALLBACK_FOG_PROPERTY = "pauc.lod.shaderFallbackFog";
	private static final String CONSERVATIVE_EMBEDDED_FALLBACK_PROPERTY = "pauc.lod.conservativeEmbeddedShaderFallback";
	private static final String STICKY_COMPATIBILITY_PROPERTY = "pauc.lod.stickyShaderCompatibility";
	private static final String HOLD_FRAMES_PROPERTY = "pauc.lod.transitionHoldFrames";
	private static final int DEFAULT_HOLD_FRAMES = 6;
	private static final int MAX_SCAN_FILES = 4096;
	private static final long MAX_SCAN_FILE_BYTES = 262_144L;
	private static final Map<String, Boolean> INCOMPATIBLE_SHADER_PACKS = new ConcurrentHashMap<>();
	private static volatile String shaderPackName = "(off)";
	private static volatile String shaderPackKey = "(off)";
	private static volatile ShaderPackDhScan dhScan = ShaderPackDhScan.unavailable("shader-off");
	private static volatile boolean shaderPackInUse;
	private static volatile boolean dhNativeShaderAvailable;
	private static volatile boolean incompatibleDhShaderPack;
	private static volatile boolean fallbackActive;
	private static volatile String status = "shader-off";
	private static volatile int transitionHoldFrames;

	private PauCLodShaderContext() {
	}

	public static void markShaderPackSelected(String packName, boolean inUse) {
		markShaderPackSelected(packName, inUse, null);
	}

	public static void markShaderPackSelected(String packName, boolean inUse, Path shaderDirectory) {
		String normalizedName = packName == null || packName.isBlank() ? "(unknown)" : packName;
		String normalizedKey = cacheKey(normalizedName);
		boolean changed = shaderPackInUse != inUse || !shaderPackName.equals(normalizedName);
		shaderPackName = normalizedName;
		shaderPackKey = normalizedKey;
		shaderPackInUse = inUse;
		dhScan = inUse ? scanShaderPackDhCapabilities(shaderDirectory) : ShaderPackDhScan.unavailable("shader-off");
		if (!inUse) {
			dhNativeShaderAvailable = false;
			incompatibleDhShaderPack = false;
			fallbackActive = false;
			status = "shader-off";
		} else if (isCurrentPackCachedIncompatible()) {
			dhNativeShaderAvailable = false;
			incompatibleDhShaderPack = true;
			fallbackActive = readBoolean(FALLBACK_PROPERTY, true);
			status = fallbackActive ? "pauc-fallback:cached-missing-dh-shader" : "dh-incompatible:cached-missing-dh-shader";
		} else if (changed) {
			dhNativeShaderAvailable = false;
			incompatibleDhShaderPack = false;
			fallbackActive = false;
			status = "shader-pending-dh-compat";
		}

		if (changed) {
			armTransitionHold();
		}
	}

	public static void markDhShaderCompatibility(boolean nativeShaderAvailable, String reason) {
		markDhShaderCompatibility(nativeShaderAvailable, reason, true);
	}

	public static void markDhShaderCompatibility(boolean nativeShaderAvailable, String reason, boolean cacheIncompatibility) {
		boolean previousFallback = fallbackActive;
		boolean previousNative = dhNativeShaderAvailable;
		boolean forcedFallback = nativeShaderAvailable && shouldForceFallbackForCurrentPack();
		boolean effectiveNativeShaderAvailable = nativeShaderAvailable && !forcedFallback;
		if (shaderPackInUse && !effectiveNativeShaderAvailable && cacheIncompatibility && !dhScan.suggestsDhSupport()) {
			INCOMPATIBLE_SHADER_PACKS.put(shaderPackKey, Boolean.TRUE);
		}

		dhNativeShaderAvailable = effectiveNativeShaderAvailable;
		incompatibleDhShaderPack = shaderPackInUse && !effectiveNativeShaderAvailable;
		fallbackActive = incompatibleDhShaderPack && readBoolean(FALLBACK_PROPERTY, true);
		if (!shaderPackInUse) {
			status = "shader-off";
		} else if (effectiveNativeShaderAvailable) {
			status = "dh-native";
		} else if (fallbackActive) {
			status = "pauc-fallback:" + sanitizeReason(forcedFallback ? "cached-missing-dh-shader" : reason);
		} else {
			status = "dh-incompatible:" + sanitizeReason(forcedFallback ? "cached-missing-dh-shader" : reason);
		}

		if (previousFallback != fallbackActive || previousNative != dhNativeShaderAvailable) {
			armTransitionHold();
		}
	}

	public static void markDhShaderRuntimeFallback(String reason) {
		boolean previousFallback = fallbackActive;
		boolean previousNative = dhNativeShaderAvailable;
		dhNativeShaderAvailable = false;
		incompatibleDhShaderPack = shaderPackInUse;
		fallbackActive = incompatibleDhShaderPack && readBoolean(FALLBACK_PROPERTY, true);
		if (!shaderPackInUse) {
			status = "shader-off";
		} else if (fallbackActive) {
			status = "pauc-fallback:" + sanitizeReason(reason);
		} else {
			status = "dh-incompatible:" + sanitizeReason(reason);
		}

		if (previousFallback != fallbackActive || previousNative != dhNativeShaderAvailable) {
			armTransitionHold();
		}
	}

	public static boolean shouldForceFallbackForCurrentPack() {
		return shaderPackInUse && isCurrentPackCachedIncompatible();
	}

	public static boolean shouldUseConservativeEmbeddedShaderFallback() {
		return shaderPackInUse && readBoolean(CONSERVATIVE_EMBEDDED_FALLBACK_PROPERTY, false);
	}

	public static boolean isFallbackActive() {
		return fallbackActive;
	}

	public static boolean isShaderPackInUse() {
		return shaderPackInUse;
	}

	public static boolean isDhNativeShaderActive() {
		return shaderPackInUse && dhNativeShaderAvailable;
	}

	public static String shaderPackKey() {
		return shaderPackKey;
	}

	public static boolean shouldApplyFallbackFog() {
		return fallbackActive && readBoolean(FALLBACK_FOG_PROPERTY, true);
	}

	public static boolean consumeTransitionHoldFrame() {
		int frames = transitionHoldFrames;
		if (frames <= 0) {
			return false;
		}

		transitionHoldFrames = frames - 1;
		return true;
	}

	public static boolean isTransitionHoldActive() {
		return transitionHoldFrames > 0;
	}

	public static String describe() {
		return "shaderContext[pack="
			+ shaderPackName
			+ ", cachedIncompatible="
			+ isCurrentPackCachedIncompatible()
			+ ", inUse="
			+ shaderPackInUse
			+ ", "
			+ dhScan.describe()
			+ ", dhNative="
			+ dhNativeShaderAvailable
			+ ", incompatible="
			+ incompatibleDhShaderPack
			+ ", fallback="
			+ fallbackActive
			+ ", conservativeEmbeddedFallback="
			+ shouldUseConservativeEmbeddedShaderFallback()
			+ ", holdFrames="
			+ transitionHoldFrames
			+ ", status="
			+ status
			+ "]";
	}

	private static void armTransitionHold() {
		transitionHoldFrames = Math.max(transitionHoldFrames, readInt(HOLD_FRAMES_PROPERTY, DEFAULT_HOLD_FRAMES, 0, 60));
	}

	private static boolean isCurrentPackCachedIncompatible() {
		return readBoolean(STICKY_COMPATIBILITY_PROPERTY, true) && INCOMPATIBLE_SHADER_PACKS.containsKey(shaderPackKey);
	}

	private static String cacheKey(String packName) {
		return packName.trim().toLowerCase();
	}

	private static String sanitizeReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return "unknown";
		}

		return reason.replace(' ', '-');
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}

		try {
			int parsed = Integer.parseInt(rawValue);
			return Math.max(min, Math.min(max, parsed));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static ShaderPackDhScan scanShaderPackDhCapabilities(Path shaderDirectory) {
		if (shaderDirectory == null) {
			return ShaderPackDhScan.unavailable("no-pack-path");
		}
		if (!Files.isDirectory(shaderDirectory)) {
			return ShaderPackDhScan.unavailable("missing-shaders-dir");
		}

		boolean[] terrain = { false };
		boolean[] water = { false };
		boolean[] shadow = { false };
		boolean[] markers = { false };
		int[] scannedFiles = { 0 };
		try (Stream<Path> stream = Files.walk(shaderDirectory, 4)) {
			stream
				.filter(Files::isRegularFile)
				.limit(MAX_SCAN_FILES)
				.forEach(path -> {
					scannedFiles[0]++;
					String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
					terrain[0] |= isProgramFile(fileName, "dh_terrain");
					water[0] |= isProgramFile(fileName, "dh_water");
					shadow[0] |= isProgramFile(fileName, "dh_shadow");
					markers[0] |= containsDhMarker(path, fileName);
				});
			return new ShaderPackDhScan(true, terrain[0], water[0], shadow[0], markers[0], scannedFiles[0], "ok");
		} catch (IOException | SecurityException exception) {
			return ShaderPackDhScan.unavailable("scan-error");
		}
	}

	private static boolean isProgramFile(String fileName, String programName) {
		return fileName.startsWith(programName + ".")
			&& (fileName.endsWith(".vsh")
				|| fileName.endsWith(".fsh")
				|| fileName.endsWith(".gsh")
				|| fileName.endsWith(".tcs")
				|| fileName.endsWith(".tes"));
	}

	private static boolean containsDhMarker(Path path, String fileName) {
		if (!isReadableShaderText(fileName)) {
			return false;
		}

		try {
			if (Files.size(path) > MAX_SCAN_FILE_BYTES) {
				return false;
			}

			String source = Files.readString(path, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
			return source.contains("distant_horizons")
				|| source.contains("dhrenderdistance")
				|| source.contains("dhdepthtex")
				|| source.contains("dhshadow.enabled");
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private static boolean isReadableShaderText(String fileName) {
		return fileName.endsWith(".vsh")
			|| fileName.endsWith(".fsh")
			|| fileName.endsWith(".gsh")
			|| fileName.endsWith(".tcs")
			|| fileName.endsWith(".tes")
			|| fileName.endsWith(".csh")
			|| fileName.endsWith(".glsl")
			|| fileName.endsWith(".properties");
	}

	private record ShaderPackDhScan(
		boolean available,
		boolean terrainProgram,
		boolean waterProgram,
		boolean shadowProgram,
		boolean markerFound,
		int scannedFiles,
		String status
	) {
		static ShaderPackDhScan unavailable(String status) {
			return new ShaderPackDhScan(false, false, false, false, false, 0, status);
		}

		boolean suggestsDhSupport() {
			return terrainProgram || waterProgram || shadowProgram || markerFound;
		}

		String describe() {
			return "dhScan[available="
				+ available
				+ ", terrain="
				+ terrainProgram
				+ ", water="
				+ waterProgram
				+ ", shadow="
				+ shadowProgram
				+ ", markers="
				+ markerFound
				+ ", files="
				+ scannedFiles
				+ ", status="
				+ status
				+ "]";
		}
	}
}
