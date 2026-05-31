package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.pipeline.PauCShaderPackProgramPatches;
import net.minecraft.client.Minecraft;

public final class PauCLodDiagnostics {
	private PauCLodDiagnostics() {
	}

	public static boolean enabled() {
		return PauCLodClientSettings.diagnosticsEnabled();
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
}
