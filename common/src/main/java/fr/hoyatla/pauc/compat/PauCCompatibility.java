package fr.hoyatla.pauc.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Shadow-pass capability queries, routed through the reflective {@link
 * fr.hoyatla.pauc.shadercompat.PauCShaderCompat} facade (P3 of the iris-removal plan): no direct
 * shader-mod class reference survives here, per the eager-classload law.
 */
public final class PauCCompatibility {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static boolean warnedAboutConservativeShadowTerrainPath;

	private PauCCompatibility() {
	}

	public static boolean supportsSodiumShadowPass() {
		return fr.hoyatla.pauc.shadercompat.PauCShaderCompat.pipelineSupportsSodiumShadowPass();
	}

	public static boolean shouldUseSodiumShadowPass() {
		if (!fr.hoyatla.pauc.shadercompat.PauCShaderCompat.isShadowPassActive()) {
			return false;
		}

		if (supportsSodiumShadowPass()) {
			return true;
		}

		if (!warnedAboutConservativeShadowTerrainPath) {
			warnedAboutConservativeShadowTerrainPath = true;
			LOGGER.warn("PauC Shader is falling back to the conservative shadow terrain path because the active pipeline does not expose a complete accelerated chunk shadow pass.");
		}

		return false;
	}
}
