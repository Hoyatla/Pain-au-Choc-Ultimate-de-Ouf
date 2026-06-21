package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.pipeline.PauCShaderPackProgramPatches;
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
		return "[PauC LOD] "
			+ PauCLodShaderProfiles.describeCurrent()
			+ ", "
			+ PauCLodShaderRuntime.describe()
			+ ", "
			+ PauCShaderPackProgramPatches.describeState();
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
			+ " | profile="
			+ profileToken(PauCLodShaderContext.currentProfileId())
			+ " | dh="
			+ dhModeToken(PauCLodShaderContext.effectiveDhMode())
			+ " | p="
			+ pressureToken(PauCLodShaderRuntime.pressure())
			+ " | fallback="
			+ onOff(PauCLodShaderContext.isFallbackActive())
			+ " | fog="
			+ Math.round(PauCLodHorizonState.vanillaFogStartBlocks())
			+ "-"
			+ Math.round(PauCLodHorizonState.vanillaFogEndBlocks())
			+ "b";
	}

	public static String compactShaderCapsLine() {
		PauCShaderFrameState shaderFrame = PauCShaderFrameState.current();
		return "[PauC Shader] caps="
			+ flag("tf", shaderFrame.capabilities().supportsTransitionFog())
			+ ","
			+ flag("dt", shaderFrame.capabilities().supportsDhTerrain())
			+ ","
			+ flag("ds", shaderFrame.capabilities().supportsDhShadow())
			+ ","
			+ flag("cl", shaderFrame.capabilities().supportsColoredLights())
			+ ","
			+ flag("wf", shaderFrame.capabilities().supportsWeatherFog());
	}

	public static String compactShaderTuneLine() {
		PauCShaderFrameState shaderFrame = PauCShaderFrameState.current();
		return "[PauC Tune] fog="
			+ shortFloat(shaderFrame.profileFarFogStrength())
			+ "/"
			+ Math.round(shaderFrame.profileFarFogWidth())
			+ " | water="
			+ shortFloat(shaderFrame.profileWaterGradientStrength())
			+ "/"
			+ shortFloat(shaderFrame.profileWaterEndFogStrength())
			+ " | sh="
			+ Math.round(shaderFrame.profileShadowJoinNear())
			+ "-"
			+ Math.round(shaderFrame.profileShadowJoinFar());
	}

	public static String compactShaderPathLine() {
		PauCShaderFrameState shaderFrame = PauCShaderFrameState.current();
		return "[PauC Path] st="
			+ shaderFrame.shaderStatusCode()
			+ "/"
			+ statusToken(shaderFrame.shaderStatusCode())
			+ " | rc="
			+ shaderFrame.shaderReasonCode()
			+ "/"
			+ reasonToken(shaderFrame.shaderReasonCode())
			+ " | cap="
			+ shaderFrame.shaderCapabilityStatusCode()
			+ "/"
			+ capabilityToken(shaderFrame.shaderCapabilityStatusCode());
	}

	private static String hudMetric(String key) {
		String value = System.getProperty(key);
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String onOff(boolean value) {
		return value ? "on" : "off";
	}

	private static String flag(String label, boolean enabled) {
		return label + (enabled ? "+" : "-");
	}

	private static String shortFloat(float value) {
		String raw = String.format(java.util.Locale.ROOT, "%.2f", value);
		if (raw.endsWith("00")) {
			return raw.substring(0, raw.length() - 3);
		}
		if (raw.endsWith("0")) {
			return raw.substring(0, raw.length() - 1);
		}
		return raw;
	}

	private static String profileToken(PauCShaderProfileId profileId) {
		return switch (profileId) {
			case SHADER_OFF -> "off";
			case GENERIC_COMPAT -> "gen";
			case PHOTON_COMPAT -> "pho";
			case SOLAS_COMPAT -> "sol";
			case PAUC_NATIVE -> "pauc";
		};
	}

	private static String dhModeToken(PauCLodShaderContext.DhShaderMode mode) {
		return switch (mode) {
			case SHADER_OFF -> "off";
			case PENDING -> "pend";
			case EXPLICIT_NATIVE -> "exp";
			case SYNTHETIC_NATIVE -> "syn";
			case FALLBACK -> "fbk";
			case INCOMPATIBLE -> "inc";
		};
	}

	private static String pressureToken(PauCLodShaderRuntime.Pressure pressure) {
		return switch (pressure) {
			case OFF -> "off";
			case RELIEF -> "rel";
			case BALANCED -> "bal";
			case HEADROOM -> "head";
		};
	}

	private static String statusToken(int code) {
		return switch (code) {
			case 0 -> "off";
			case 1 -> "pend";
			case 2 -> "n-exp";
			case 3 -> "n-syn";
			case 4 -> "fbk";
			case 5 -> "inc";
			default -> "other";
		};
	}

	private static String reasonToken(int code) {
		return switch (code) {
			case 0 -> "-";
			case 1 -> "cache-miss";
			case 2 -> "pauc-emb";
			case 3 -> "pack-no-dh";
			case 4 -> "dh-miss";
			case 5 -> "dh-terrain-miss";
			case 6 -> "dh-native";
			case 7 -> "dh-synth";
			default -> "other";
		};
	}

	private static String capabilityToken(int code) {
		return switch (code) {
			case 0 -> "off";
			case 1 -> "ok";
			case 2 -> "miss";
			case 3 -> "invalid";
			case 4 -> "io";
			case 5 -> "no-dir";
			case 6 -> "compat";
			case 7 -> "rt-off";
			default -> "other";
		};
	}
}
