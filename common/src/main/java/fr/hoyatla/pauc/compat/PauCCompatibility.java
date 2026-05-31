package fr.hoyatla.pauc.compat;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;

public final class PauCCompatibility {
	private static boolean warnedAboutConservativeShadowTerrainPath;

	private PauCCompatibility() {
	}

	public static boolean supportsSodiumShadowPass() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		return pipeline != null && pipeline.supportsSodiumShadowPass();
	}

	public static boolean shouldUseSodiumShadowPass() {
		if (!ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return false;
		}

		if (supportsSodiumShadowPass()) {
			return true;
		}

		if (!warnedAboutConservativeShadowTerrainPath) {
			warnedAboutConservativeShadowTerrainPath = true;
			Iris.logger.warn("PauC Shader is falling back to the conservative shadow terrain path because the active pipeline does not expose a complete accelerated chunk shadow pass.");
		}

		return false;
	}
}
