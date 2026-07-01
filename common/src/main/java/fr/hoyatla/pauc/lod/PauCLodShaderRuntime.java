package fr.hoyatla.pauc.lod;

import java.util.Locale;

public final class PauCLodShaderRuntime {
	private static final String ENABLED_PROPERTY = "pauc.lod.shaderRuntime";
	private static final String KEEP_LOD_CLOUDS_PROPERTY = "pauc.lod.shaderRuntime.keepLodClouds";
	private static final String PROTECT_LOD_CLOUD_CENTER_PROPERTY = "pauc.lod.shaderRuntime.protectLodCloudCenter";
	private static final String MANAGE_NATIVE_DH_SHADOW_PROPERTY = "pauc.lod.shaderRuntime.manageNativeDhShadow";
	private static final String STABLE_SYNTHETIC_SHADOW_PROPERTY = "pauc.lod.shaderRuntime.stableSyntheticShadow";
	private static final String FULL_SYNTHETIC_SHADOW_FALLBACK_PROPERTY = "pauc.lod.shaderRuntime.fullSyntheticShadowFallback";
	private static final String SOLAS_SYNTHETIC_SHADOW_FALLBACK_PROPERTY = "pauc.lod.shaderRuntime.solasSyntheticShadowFallback";
	private static final String SHADOW_FALLBACK_BUDGET_PROPERTY = "pauc.lod.shaderRuntime.shadowFallbackBudgetChunks";
	private static final String MAX_GENERATION_RATE_PROPERTY = "pauc.lod.shaderRuntime.maxGenerationRateLimit";
	private static volatile State state = State.off();

	private PauCLodShaderRuntime() {
	}

	public static void updatePerformance(
		boolean shaderActive,
		PauCLodShaderProfiles.Family family,
		int fps,
		int targetFps,
		double heapPressure,
		boolean nvidiaMeshPath,
		boolean multiDrawIndirect,
		boolean bindlessIndirect
	) {
		if (!readBoolean(ENABLED_PROPERTY, true) || !shaderActive || targetFps <= 0) {
			state = State.off();
			return;
		}

		double ratio = fps > 0 ? fps / (double) targetFps : 0.0D;
		PauCLodShaderContext.DhShaderMode shaderMode = PauCLodShaderContext.effectiveDhMode();
		Pressure pressure;
		if (shaderMode == PauCLodShaderContext.DhShaderMode.SYNTHETIC_NATIVE) {
			if (ratio < 0.78D || heapPressure > 0.88D) {
				pressure = Pressure.RELIEF;
			} else if (ratio < 1.08D || heapPressure > 0.80D) {
				pressure = Pressure.BALANCED;
			} else {
				pressure = Pressure.HEADROOM;
			}
		} else if (ratio < 0.72D || heapPressure > 0.90D) {
			pressure = Pressure.RELIEF;
		} else if (ratio < 1.02D || heapPressure > 0.82D) {
			pressure = Pressure.BALANCED;
		} else {
			pressure = Pressure.HEADROOM;
		}

		state = new State(
			true,
			family == null ? PauCLodShaderProfiles.Family.GENERIC : family,
			shaderMode,
			pressure,
			fps,
			targetFps,
			ratio,
			heapPressure,
			nvidiaMeshPath,
			multiDrawIndirect,
			bindlessIndirect
		);
	}

	public static void onShaderPackStateChanged(
		boolean shaderInUse,
		PauCLodShaderProfiles.Family family,
		PauCLodShaderContext.DhShaderMode shaderMode
	) {
		if (!shaderInUse) {
			state = State.off();
			return;
		}

		State snapshot = state;
		PauCLodShaderProfiles.Family resolvedFamily = family == null ? PauCLodShaderProfiles.Family.GENERIC : family;
		PauCLodShaderContext.DhShaderMode resolvedMode =
			shaderMode == null ? PauCLodShaderContext.DhShaderMode.PENDING : shaderMode;
		boolean preservePerformance = snapshot.active() && snapshot.family() == resolvedFamily;
		state = new State(
			preservePerformance,
			resolvedFamily,
			resolvedMode,
			preservePerformance ? snapshot.pressure() : Pressure.OFF,
			preservePerformance ? snapshot.fps() : -1,
			preservePerformance ? snapshot.targetFps() : -1,
			preservePerformance ? snapshot.ratio() : 0.0D,
			preservePerformance ? snapshot.heapPressure() : 0.0D,
			preservePerformance && snapshot.nvidiaMeshPath(),
			preservePerformance && snapshot.multiDrawIndirect(),
			preservePerformance && snapshot.bindlessIndirect()
		);
	}

	public static boolean isActive() {
		return readBoolean(ENABLED_PROPERTY, true) && PauCLodShaderContext.isShaderPackInUse();
	}

	public static Pressure pressure() {
		return state.active() ? state.pressure() : Pressure.OFF;
	}

	public static boolean isUnderPressure() {
		return pressure() == Pressure.RELIEF;
	}

	public static boolean shouldKeepPauCLodCloudsVisible() {
		return isActive()
			&& readBoolean(KEEP_LOD_CLOUDS_PROPERTY, true)
			&& PauCLodClientSettings.isLodCloudsEnabled();
	}

	public static boolean shouldProtectLodCloudCenter() {
		if (!shouldKeepPauCLodCloudsVisible()) {
			return false;
		}
		PauCLodShaderProfiles.Family family = currentFamily();
		boolean stableNativeCloudPath = PauCLodShaderContext.isDhNativeShaderActive()
			&& (family == PauCLodShaderProfiles.Family.SILDURS_ENHANCED
				|| family == PauCLodShaderProfiles.Family.SILDURS_VIBRANT);
		boolean defaultProtect = stableNativeCloudPath || (!PauCLodShaderContext.isFallbackActive() && !isUnderPressure());
		return readBoolean(PROTECT_LOD_CLOUD_CENTER_PROPERTY, defaultProtect);
	}

	public static boolean shouldCreateNativeDhShadowProgram(boolean dhShadowEnabled) {
		if (!dhShadowEnabled) {
			return false;
		}
		if (!isActive() || !readBoolean(MANAGE_NATIVE_DH_SHADOW_PROPERTY, true)) {
			return true;
		}

		if (usesSyntheticShaderShadowFallback()) {
			return readBoolean(STABLE_SYNTHETIC_SHADOW_PROPERTY, false);
		}
		if (blocksSyntheticShaderShadowFallback()) {
			return false;
		}
		return true;
	}

	public static boolean shouldRenderNativeDhShadowThisFrame() {
		if (!isActive() || !readBoolean(MANAGE_NATIVE_DH_SHADOW_PROPERTY, true)) {
			return true;
		}

		PauCLodShaderProfiles.Family family = currentFamily();
		if (usesSyntheticShaderShadowFallback()) {
			return readBoolean(STABLE_SYNTHETIC_SHADOW_PROPERTY, false);
		}
		if (blocksSyntheticShaderShadowFallback()) {
			return false;
		}
		if (pressure() == Pressure.RELIEF) {
			return false;
		}
		if (family == PauCLodShaderProfiles.Family.PHOTON || family == PauCLodShaderProfiles.Family.SOLAS) {
			return true;
		}
		return true;
	}

	public static int shadowFallbackChunkBudget(int availableChunks) {
		if (availableChunks <= 0) {
			return 0;
		}
		if (!isActive()) {
			return availableChunks;
		}
		// NOTE: blocksSyntheticShaderShadowFallback() must NOT gate this budget. That flag suppresses the
		// synthetic DH *LOD* shadow program for packs like Photon/Solas (correct), but this budget governs
		// whether the *vanilla terrain* main-camera chunks are restored into the shadow terrain list when
		// the vanilla shadow-frustum setup comes back empty (the embedded, no-Sodium path). Vanilla terrain
		// must always be submitted to the shader's shadow.vsh pass, otherwise terrain casts no shadow while
		// entities still do. Conflating the two zeroed this budget for Photon/Solas and removed all terrain
		// shadows in the vanilla zone (0.3.0 regression).
		if (usesSyntheticShaderShadowFallback() && readBoolean(FULL_SYNTHETIC_SHADOW_FALLBACK_PROPERTY, false)) {
			return Math.max(
				0,
				Math.min(
					availableChunks,
					readInt(SHADOW_FALLBACK_BUDGET_PROPERTY, availableChunks, 0, availableChunks)
				)
			);
		}

		int base = switch (currentFamily()) {
			case PHOTON -> switch (pressure()) {
				case RELIEF -> 256;
				case BALANCED -> 448;
				case HEADROOM -> 768;
				default -> 448;
			};
			case SOLAS -> switch (pressure()) {
				case RELIEF -> 384;
				case BALANCED -> 640;
				case HEADROOM -> 960;
				default -> 640;
			};
			default -> switch (pressure()) {
				case RELIEF -> 320;
				case BALANCED -> 560;
				case HEADROOM -> 896;
				default -> 560;
			};
		};
		if (state.nvidiaMeshPath() && state.bindlessIndirect() && pressure() == Pressure.HEADROOM) {
			base = (int) Math.round(base * 1.20D);
		}

		int configured = readInt(SHADOW_FALLBACK_BUDGET_PROPERTY, base, 0, 4096);
		return Math.max(0, Math.min(availableChunks, configured));
	}

	public static int shaderGenerationRateLimit(int policyLimit) {
		if (!isActive()) {
			return policyLimit;
		}

		double scale = switch (pressure()) {
			case RELIEF -> 0.82D;
			case BALANCED -> 0.96D;
			case HEADROOM -> 1.10D;
			default -> 1.0D;
		};
		if (currentFamily() == PauCLodShaderProfiles.Family.SOLAS && pressure() != Pressure.HEADROOM) {
			scale *= 0.94D;
		}
		if (state.nvidiaMeshPath() && state.multiDrawIndirect()) {
			scale *= 1.08D;
		}
		int maxRate = readInt(MAX_GENERATION_RATE_PROPERTY, 512, 64, 1024);
		return Math.max(20, Math.min(maxRate, (int) Math.round(policyLimit * scale)));
	}

	public static double uploadBudgetScale() {
		if (!isActive()) {
			return 1.0D;
		}

		double scale = switch (pressure()) {
			case RELIEF -> 0.72D;
			case BALANCED -> 0.92D;
			case HEADROOM -> 1.12D;
			default -> 1.0D;
		};
		if (state.nvidiaMeshPath() && state.multiDrawIndirect()) {
			scale *= pressure() == Pressure.HEADROOM ? 1.12D : 1.04D;
		}
		return Math.max(0.35D, Math.min(1.35D, scale));
	}

	public static double generationThreadRuntimeRatio(double configuredDefault) {
		if (!isActive()) {
			return configuredDefault;
		}

		double scale = switch (pressure()) {
			case RELIEF -> 0.82D;
			case BALANCED -> 0.94D;
			case HEADROOM -> 1.05D;
			default -> 1.0D;
		};
		return Math.max(0.05D, Math.min(1.0D, configuredDefault * scale));
	}

	public static String describe() {
		State snapshot = state;
		return "shaderRuntime[active="
			+ isActive()
			+ ", family="
			+ currentFamily().name().toLowerCase(Locale.ROOT)
			+ ", mode="
			+ snapshot.shaderMode().id()
			+ ", pressure="
			+ pressure().id
			+ ", fps="
			+ (snapshot.fps() > 0 ? snapshot.fps() : "-")
			+ "/"
			+ (snapshot.targetFps() > 0 ? snapshot.targetFps() : "-")
			+ ", ratio="
			+ String.format(Locale.ROOT, "%.2f", snapshot.ratio())
			+ ", meshActive="
			+ snapshot.nvidiaMeshPath()
			+ ", mdi="
			+ snapshot.multiDrawIndirect()
			+ ", bindless="
			+ snapshot.bindlessIndirect()
			+ ", lodClouds="
			+ shouldKeepPauCLodCloudsVisible()
			+ ", nativeDhShadow="
			+ shouldRenderNativeDhShadowThisFrame()
			+ "]";
	}

	private static PauCLodShaderProfiles.Family currentFamily() {
		return state.active() ? state.family() : PauCLodShaderProfiles.currentFamily();
	}

	private static boolean usesSyntheticShaderShadowFallback() {
		PauCLodShaderProfiles.Family family = currentFamily();
		if (!PauCLodShaderContext.isShaderPackInUse() || PauCLodShaderContext.hasScannedDhShadowProgram()) {
			return false;
		}
		return false;
	}

	private static boolean blocksSyntheticShaderShadowFallback() {
		PauCLodShaderProfiles.Family family = currentFamily();
		return (family == PauCLodShaderProfiles.Family.SOLAS || family == PauCLodShaderProfiles.Family.PHOTON)
			&& PauCLodShaderContext.isShaderPackInUse()
			&& !PauCLodShaderContext.hasScannedDhShadowProgram();
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
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	public enum Pressure {
		OFF("off"),
		RELIEF("relief"),
		BALANCED("balanced"),
		HEADROOM("headroom");

		private final String id;

		Pressure(String id) {
			this.id = id;
		}
	}

	private record State(
		boolean active,
		PauCLodShaderProfiles.Family family,
		PauCLodShaderContext.DhShaderMode shaderMode,
		Pressure pressure,
		int fps,
		int targetFps,
		double ratio,
		double heapPressure,
		boolean nvidiaMeshPath,
		boolean multiDrawIndirect,
		boolean bindlessIndirect
	) {
		static State off() {
			return new State(
				false,
				PauCLodShaderProfiles.Family.GENERIC,
				PauCLodShaderContext.DhShaderMode.SHADER_OFF,
				Pressure.OFF,
				-1,
				-1,
				0.0D,
				0.0D,
				false,
				false,
				false
			);
		}
	}
}
