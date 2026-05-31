package fr.hoyatla.pauc.lod;

import java.util.Locale;

public final class PauCLodShaderRuntime {
	private static final String ENABLED_PROPERTY = "pauc.lod.shaderRuntime";
	private static final String KEEP_LOD_CLOUDS_PROPERTY = "pauc.lod.shaderRuntime.keepLodClouds";
	private static final String MANAGE_NATIVE_DH_SHADOW_PROPERTY = "pauc.lod.shaderRuntime.manageNativeDhShadow";
	private static final String SHADOW_FALLBACK_BUDGET_PROPERTY = "pauc.lod.shaderRuntime.shadowFallbackBudgetChunks";
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
		Pressure pressure;
		if (ratio < 0.72D || heapPressure > 0.90D) {
			pressure = Pressure.RELIEF;
		} else if (ratio < 1.02D || heapPressure > 0.82D) {
			pressure = Pressure.BALANCED;
		} else {
			pressure = Pressure.HEADROOM;
		}

		state = new State(
			true,
			family == null ? PauCLodShaderProfiles.Family.GENERIC : family,
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
		return shouldKeepPauCLodCloudsVisible();
	}

	public static boolean shouldCreateNativeDhShadowProgram(boolean dhShadowEnabled) {
		if (!dhShadowEnabled) {
			return false;
		}
		if (!isActive() || !readBoolean(MANAGE_NATIVE_DH_SHADOW_PROPERTY, true)) {
			return true;
		}

		return currentFamily() != PauCLodShaderProfiles.Family.PHOTON;
	}

	public static boolean shouldRenderNativeDhShadowThisFrame() {
		if (!isActive() || !readBoolean(MANAGE_NATIVE_DH_SHADOW_PROPERTY, true)) {
			return true;
		}

		PauCLodShaderProfiles.Family family = currentFamily();
		if (family == PauCLodShaderProfiles.Family.PHOTON) {
			return false;
		}
		if (pressure() == Pressure.RELIEF) {
			return false;
		}
		return family != PauCLodShaderProfiles.Family.SOLAS || pressure() == Pressure.HEADROOM;
	}

	public static int shadowFallbackChunkBudget(int availableChunks) {
		if (availableChunks <= 0) {
			return 0;
		}
		if (!isActive()) {
			return availableChunks;
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
			case RELIEF -> 0.68D;
			case BALANCED -> 0.86D;
			case HEADROOM -> 1.10D;
			default -> 1.0D;
		};
		if (currentFamily() == PauCLodShaderProfiles.Family.SOLAS && pressure() != Pressure.HEADROOM) {
			scale *= 0.88D;
		}
		if (state.nvidiaMeshPath() && state.multiDrawIndirect()) {
			scale *= 1.08D;
		}
		return Math.max(8, Math.min(128, (int) Math.round(policyLimit * scale)));
	}

	public static double uploadBudgetScale() {
		if (!isActive()) {
			return 1.0D;
		}

		double scale = switch (pressure()) {
			case RELIEF -> 0.58D;
			case BALANCED -> 0.82D;
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
			case RELIEF -> 0.70D;
			case BALANCED -> 0.88D;
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
