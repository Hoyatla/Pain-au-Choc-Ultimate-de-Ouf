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
	private static final int DEFAULT_HOLD_FRAMES = 10;
	private static final int MAX_SCAN_FILES = 4096;
	private static final long MAX_SCAN_FILE_BYTES = 262_144L;
	private static final Map<String, Boolean> INCOMPATIBLE_SHADER_PACKS = new ConcurrentHashMap<>();
	private static volatile String shaderPackName = "(off)";
	private static volatile String shaderPackKey = "(off)";
	private static volatile ShaderPackDhScan dhScan = ShaderPackDhScan.unavailable("shader-off");
	private static volatile boolean shaderPackInUse;
	private static volatile boolean dhNativeShaderAvailable;
	private static volatile boolean dhExplicitNativeShaderAvailable;
	private static volatile boolean dhSyntheticShaderAvailable;
	private static volatile boolean incompatibleDhShaderPack;
	private static volatile boolean fallbackActive;
	private static volatile DhShaderMode effectiveDhMode = DhShaderMode.SHADER_OFF;
	private static volatile String status = "shader-off";
	private static volatile int transitionHoldFrames;

	private PauCLodShaderContext() {
	}

	private static long lastExternalPollMs;

	/**
	 * P3 (iris-removal plan): EXTERNAL shader-state detection. The vendored pipeline PUSHES pack
	 * changes into this context ({@code Iris.java} calls {@link #markShaderPackSelected}); an external
	 * Iris/Oculus never will — so the client tick POLLS the reflective facade (~1s cadence) and feeds
	 * the same entry point. While the vendored push still exists the poll observes identical state and
	 * exits before touching anything, so the two sources never fight.
	 */
	public static void pollExternalShaderState() {
		long now = System.currentTimeMillis();
		if (now - lastExternalPollMs < 1_000L) {
			return;
		}
		lastExternalPollMs = now;
		boolean inUse = fr.hoyatla.pauc.shadercompat.PauCShaderCompat.isShaderPackInUse();
		String name = inUse ? fr.hoyatla.pauc.shadercompat.PauCShaderCompat.currentPackName() : null;
		String normalized = name == null || name.isBlank() ? "(unknown)" : name;
		if (inUse == shaderPackInUse && (!inUse || shaderPackName.equals(normalized))) {
			return; // no change (or the vendored push already recorded it) — never rescan on a timer
		}
		markShaderPackSelected(normalized, inUse,
			inUse ? fr.hoyatla.pauc.shadercompat.PauCShaderCompat.currentPackPath() : null);
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
			dhExplicitNativeShaderAvailable = false;
			dhSyntheticShaderAvailable = false;
			incompatibleDhShaderPack = false;
			fallbackActive = false;
			effectiveDhMode = DhShaderMode.SHADER_OFF;
			status = "shader-off";
		} else if (isCurrentPackCachedIncompatible()) {
			dhNativeShaderAvailable = false;
			dhExplicitNativeShaderAvailable = false;
			dhSyntheticShaderAvailable = false;
			incompatibleDhShaderPack = true;
			fallbackActive = readBoolean(FALLBACK_PROPERTY, true);
			effectiveDhMode = fallbackActive ? DhShaderMode.FALLBACK : DhShaderMode.INCOMPATIBLE;
			status = fallbackActive ? "pauc-fallback:cached-missing-dh-shader" : "dh-incompatible:cached-missing-dh-shader";
		} else if (changed) {
			dhNativeShaderAvailable = false;
			dhExplicitNativeShaderAvailable = false;
			dhSyntheticShaderAvailable = false;
			incompatibleDhShaderPack = false;
			fallbackActive = false;
			effectiveDhMode = DhShaderMode.PENDING;
			status = "shader-pending-dh-compat";
		}

		if (changed) {
			armTransitionHold();
		}
		PauCLodShaderRuntime.onShaderPackStateChanged(shaderPackInUse, PauCLodShaderProfiles.familyForKey(shaderPackKey), effectiveDhMode);
	}

	public static void markDhShaderCompatibility(boolean nativeShaderAvailable, String reason) {
		markDhShaderCompatibility(nativeShaderAvailable, false, reason, true);
	}

	public static void markDhShaderCompatibility(boolean nativeShaderAvailable, String reason, boolean cacheIncompatibility) {
		markDhShaderCompatibility(nativeShaderAvailable, false, reason, cacheIncompatibility);
	}

	public static void markDhShaderCompatibility(
		boolean explicitNativeShaderAvailable,
		boolean syntheticShaderAvailable,
		String reason,
		boolean cacheIncompatibility
	) {
		boolean previousFallback = fallbackActive;
		boolean previousNative = dhNativeShaderAvailable;
		DhShaderMode previousMode = effectiveDhMode;
		boolean requestedDhShaderPath = explicitNativeShaderAvailable || syntheticShaderAvailable;
		boolean forcedFallback = requestedDhShaderPath && shouldForceFallbackForCurrentPack();
		boolean effectiveExplicitShaderAvailable = explicitNativeShaderAvailable && !forcedFallback;
		boolean effectiveSyntheticShaderAvailable = !effectiveExplicitShaderAvailable && syntheticShaderAvailable && !forcedFallback;
		boolean effectiveNativeShaderAvailable = effectiveExplicitShaderAvailable || effectiveSyntheticShaderAvailable;
		if (shaderPackInUse && !effectiveNativeShaderAvailable && cacheIncompatibility && !dhScan.suggestsDhSupport()) {
			INCOMPATIBLE_SHADER_PACKS.put(shaderPackKey, Boolean.TRUE);
		}

		dhNativeShaderAvailable = effectiveNativeShaderAvailable;
		dhExplicitNativeShaderAvailable = effectiveExplicitShaderAvailable;
		dhSyntheticShaderAvailable = effectiveSyntheticShaderAvailable;
		incompatibleDhShaderPack = shaderPackInUse && !effectiveNativeShaderAvailable;
		fallbackActive = incompatibleDhShaderPack && readBoolean(FALLBACK_PROPERTY, true);
		if (!shaderPackInUse) {
			effectiveDhMode = DhShaderMode.SHADER_OFF;
			status = "shader-off";
		} else if (effectiveExplicitShaderAvailable) {
			effectiveDhMode = DhShaderMode.EXPLICIT_NATIVE;
			status = "dh-native-explicit";
		} else if (effectiveSyntheticShaderAvailable) {
			effectiveDhMode = DhShaderMode.SYNTHETIC_NATIVE;
			status = "dh-native-synthetic";
		} else if (fallbackActive) {
			effectiveDhMode = DhShaderMode.FALLBACK;
			status = "pauc-fallback:" + sanitizeReason(forcedFallback ? "cached-missing-dh-shader" : reason);
		} else if (effectiveNativeShaderAvailable) {
			effectiveDhMode = DhShaderMode.EXPLICIT_NATIVE;
			status = "dh-native-explicit";
		} else {
			effectiveDhMode = DhShaderMode.INCOMPATIBLE;
			status = "dh-incompatible:" + sanitizeReason(forcedFallback ? "cached-missing-dh-shader" : reason);
		}

		if (previousFallback != fallbackActive || previousNative != dhNativeShaderAvailable || previousMode != effectiveDhMode) {
			armTransitionHold();
		}
		PauCLodShaderRuntime.onShaderPackStateChanged(shaderPackInUse, PauCLodShaderProfiles.familyForKey(shaderPackKey), effectiveDhMode);
	}

	public static void markDhShaderRuntimeFallback(String reason) {
		boolean previousFallback = fallbackActive;
		boolean previousNative = dhNativeShaderAvailable;
		DhShaderMode previousMode = effectiveDhMode;
		dhNativeShaderAvailable = false;
		dhExplicitNativeShaderAvailable = false;
		dhSyntheticShaderAvailable = false;
		incompatibleDhShaderPack = shaderPackInUse;
		fallbackActive = incompatibleDhShaderPack && readBoolean(FALLBACK_PROPERTY, true);
		if (!shaderPackInUse) {
			effectiveDhMode = DhShaderMode.SHADER_OFF;
			status = "shader-off";
		} else if (fallbackActive) {
			effectiveDhMode = DhShaderMode.FALLBACK;
			status = "pauc-fallback:" + sanitizeReason(reason);
		} else {
			effectiveDhMode = DhShaderMode.INCOMPATIBLE;
			status = "dh-incompatible:" + sanitizeReason(reason);
		}

		if (previousFallback != fallbackActive || previousNative != dhNativeShaderAvailable || previousMode != effectiveDhMode) {
			armTransitionHold();
		}
		PauCLodShaderRuntime.onShaderPackStateChanged(shaderPackInUse, PauCLodShaderProfiles.familyForKey(shaderPackKey), effectiveDhMode);
	}

	public static boolean shouldForceFallbackForCurrentPack() {
		return shaderPackInUse && isCurrentPackCachedIncompatible();
	}

	public static boolean blocksSyntheticDhTerrainShader() {
		return shaderPackInUse && dhScan.available() && !dhScan.terrainProgram() && !shouldUseSyntheticDhTerrainShader();
	}

	public static boolean shouldUseSyntheticDhTerrainShader() {
		if (!shaderPackInUse) {
			return false;
		}
		return switch (PauCLodShaderProfiles.currentFamily()) {
			case BLISS, BSL, COMPLEMENTARY, RETHINKING -> true;
			default -> false;
		};
	}

	public static boolean hasScannedDhTerrainProgram() {
		return shaderPackInUse && dhScan.available() && dhScan.terrainProgram();
	}

	public static boolean hasScannedDhShadowProgram() {
		return shaderPackInUse && dhScan.available() && dhScan.shadowProgram();
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

	public static boolean isExplicitDhNativeShaderActive() {
		return shaderPackInUse && dhExplicitNativeShaderAvailable;
	}

	public static boolean isSyntheticDhShaderActive() {
		return shaderPackInUse && dhSyntheticShaderAvailable;
	}

	public static DhShaderMode effectiveDhMode() {
		return effectiveDhMode;
	}

	public static String shaderPackKey() {
		return shaderPackKey;
	}

	public static boolean shouldApplyFallbackFog() {
		return fallbackActive && readBoolean(FALLBACK_FOG_PROPERTY, true);
	}

	// Packs that render LODs but ship NO native DH fog program (e.g. Sildur's Vibrant - dhScan terrain/water=false)
	// leave the LOD field ending in a hard cut against the sky, with no map-closing fog. They DO read the RenderSystem
	// fog uniforms, so we extend the vanilla distance fog out to the LOD horizon for them (as the fallback path does),
	// which closes the map. Native-DH packs (Photon/Solas) own their atmospheric fog and are untouched.
	public static boolean shouldApplyLodHorizonFogForNoDhFogPack() {
		return shaderPackInUse
			&& readBoolean(FALLBACK_FOG_PROPERTY, true)
			&& PauCLodShaderProfiles.profile(PauCLodShaderProfiles.familyForKey(shaderPackKey)).lacksNativeDhPrograms();
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
			+ ", dhMode="
			+ effectiveDhMode.id
			+ ", dhEffective="
			+ dhNativeShaderAvailable
			+ ", dhExplicit="
			+ dhExplicitNativeShaderAvailable
			+ ", dhSynthetic="
			+ dhSyntheticShaderAvailable
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

	public enum DhShaderMode {
		SHADER_OFF("shader-off"),
		PENDING("pending"),
		EXPLICIT_NATIVE("native-explicit"),
		SYNTHETIC_NATIVE("native-synthetic"),
		FALLBACK("fallback"),
		INCOMPATIBLE("incompatible");

		private final String id;

		DhShaderMode(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public boolean usesDhShaderPath() {
			return this == EXPLICIT_NATIVE || this == SYNTHETIC_NATIVE;
		}
	}

	private static void armTransitionHold() {
		transitionHoldFrames = Math.max(transitionHoldFrames, readInt(HOLD_FRAMES_PROPERTY, defaultTransitionHoldFrames(), 0, 120));
	}

	private static int defaultTransitionHoldFrames() {
		int frames = DEFAULT_HOLD_FRAMES;
		PauCLodShaderProfiles.Family family = PauCLodShaderProfiles.currentFamily();
		if (family == PauCLodShaderProfiles.Family.PHOTON || family == PauCLodShaderProfiles.Family.SOLAS) {
			frames += 8;
		}
		PauCTerrainGeneratorDetector.ModpackClass modpackClass = PauCTerrainGeneratorDetector.currentModpackClass();
		return switch (modpackClass) {
			case EXTREME -> frames + 10;
			case HEAVY -> frames + 6;
			case MEDIUM -> frames + 2;
			case LIGHT -> frames;
		};
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
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
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
					String relativeName = shaderDirectory.relativize(path).toString().toLowerCase(Locale.ROOT).replace('\\', '/');
					terrain[0] |= isProgramFile(fileName, "dh_terrain") || isProgramFile(relativeName, "dh_terrain");
					water[0] |= isProgramFile(fileName, "dh_water") || isProgramFile(relativeName, "dh_water");
					shadow[0] |= isProgramFile(fileName, "dh_shadow") || isProgramFile(relativeName, "dh_shadow");
					markers[0] |= containsDhMarker(path, fileName);
				});
			return new ShaderPackDhScan(true, terrain[0], water[0], shadow[0], markers[0], scannedFiles[0], "ok");
		} catch (IOException | SecurityException exception) {
			return ShaderPackDhScan.unavailable("scan-error");
		}
	}

	private static boolean isProgramFile(String fileName, String programName) {
		if (!isShaderProgramFile(fileName)) {
			return false;
		}

		int start = fileName.indexOf(programName);
		if (start < 0) {
			return false;
		}

		int end = start + programName.length();
		return hasProgramBoundary(fileName, start - 1)
			&& hasProgramBoundary(fileName, end);
	}

	private static boolean isShaderProgramFile(String fileName) {
		return fileName.endsWith(".vsh")
			|| fileName.endsWith(".fsh")
			|| fileName.endsWith(".gsh")
			|| fileName.endsWith(".tcs")
			|| fileName.endsWith(".tes");
	}

	private static boolean hasProgramBoundary(String fileName, int index) {
		if (index < 0 || index >= fileName.length()) {
			return true;
		}

		char character = fileName.charAt(index);
		return character == '.' || character == '_' || character == '-' || character == '/' || character == '\\';
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
