package fr.hoyatla.pauc.lod;

import net.minecraft.client.Minecraft;

public final class PauCLodDiagnostics {
	public static final String HUD_RAW_FPS_PROPERTY = "pauc.runtime.hud.rawFps";
	public static final String HUD_AVERAGE_FPS_PROPERTY = "pauc.runtime.hud.averageFps";

	private PauCLodDiagnostics() {
	}

	public static boolean enabled() {
		return PauCLodClientSettings.diagnosticsEnabled();
	}

	public static boolean hasHudFpsMetrics() {
		return System.getProperty(HUD_RAW_FPS_PROPERTY) != null || System.getProperty(HUD_AVERAGE_FPS_PROPERTY) != null;
	}

	public static String overviewLine() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		return "[PauC LOD] "
			+ (range != null ? range.describe() : "lodRange[unknown]")
			+ ", "
			+ PauCLodShaderContext.describe();
	}

	public static String shaderLine() {
		// Program-patch state via the reflective facade (P3): empty string once the vendored pipeline
		// is removed — the line simply loses its last segment instead of crashing the HUD.
		String patches = fr.hoyatla.pauc.shadercompat.PauCShaderCompat.describeProgramPatches();
		return "[PauC LOD] "
			+ PauCLodShaderProfiles.describeCurrent()
			+ ", "
			+ PauCLodShaderRuntime.describe()
			+ (patches.isEmpty() ? "" : ", " + patches);
	}

	public static String policyLine() {
		return "[PauC LOD] "
			+ PauCLodClientSettings.describePerformancePolicy()
			+ ", fog="
			+ Math.round(PauCLodHorizonState.vanillaFogStartBlocks())
			+ "-"
			+ Math.round(PauCLodHorizonState.vanillaFogEndBlocks())
			+ " blocks, "
			+ PauCLodHorizonState.describeVisualPolicy();
	}

	public static String validationLine() {
		Minecraft minecraft = Minecraft.getInstance();
		String dimension = minecraft != null && minecraft.level != null
			? minecraft.level.dimension().location().toString()
			: "-";
		return "[PauC LOD] validation[dimension="
			+ dimension
			+ ", light=lightmap+emissiveFallback, structures=blockEntitiesSidecar, retention=turn-safe]";
	}

	public static String cullingLine() {
		return "[PauC LOD] " + PauCLodRenderCulling.describe();
	}

	public static String fpsLine() {
		return "[PauC FPS] brut=" + hudMetric(HUD_RAW_FPS_PROPERTY) + " | moyen=" + hudMetric(HUD_AVERAGE_FPS_PROPERTY);
	}

	public static String compactOverviewLine() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null) {
			return "[PauC LOD] plage=indisponible";
		}
		return "[PauC LOD] vanilla="
			+ range.vanillaRenderDistanceChunks()
			+ " | extra="
			+ range.configuredExtraDistanceChunks()
			+ " | abs="
			+ range.lodEndChunk()
			+ " | horizon="
			+ range.roundHorizonEndChunk();
	}

	public static String compactModeLine() {
		return "[PauC Mode] shader="
			+ onOff(PauCLodShaderContext.isShaderPackInUse())
			+ " | dh="
			+ PauCLodShaderContext.effectiveDhMode().id()
			+ " | fallback="
			+ onOff(PauCLodShaderContext.isFallbackActive())
			+ " | fog="
			+ Math.round(PauCLodHorizonState.vanillaFogStartBlocks())
			+ "-"
			+ Math.round(PauCLodHorizonState.vanillaFogEndBlocks())
			+ "b";
	}

	private static String hudMetric(String key) {
		String value = System.getProperty(key);
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String onOff(boolean value) {
		return value ? "on" : "off";
	}
}
