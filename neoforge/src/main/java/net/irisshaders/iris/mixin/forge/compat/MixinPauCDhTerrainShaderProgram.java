package net.irisshaders.iris.mixin.forge.compat;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_forge;
import fr.hoyatla.pauc.lod.PauCLodFallbackVisuals;
import fr.hoyatla.pauc.lod.PauCLodNearClipOverride;
import fr.hoyatla.pauc.lod.PauCLodShaderPresentation;
import org.slf4j.Logger;
import org.lwjgl.opengl.GL32;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlDhTerrainShaderProgram_forge.class, remap = false)
public abstract class MixinPauCDhTerrainShaderProgram {
	@Unique
	private static final Logger PAUC_FALLBACK_SHADER_LOGGER = LogUtils.getLogger();
	@Unique
	private static boolean pauc$fallbackShaderUniformsLogged;
	@Unique
	private static boolean pauc$clipFadeDisabledLogged;
	@Unique
	private static boolean pauc$clipBoundaryLogged;
	@Shadow
	public int uClipDistance;
	@Shadow
	public int uDitherDhRendering;
	@Unique
	private int pauc$fallbackVisualStrength = -1;
	@Unique
	private int pauc$fallbackFogColor = -1;
	@Unique
	private int pauc$fallbackFogStart = -1;
	@Unique
	private int pauc$fallbackFogEnd = -1;
	@Unique
	private int pauc$fallbackFogIntensity = -1;
	@Unique
	private int pauc$fallbackFogFloor = -1;
	@Unique
	private int pauc$fallbackRescueStrength = -1;
	@Unique
	private int pauc$fallbackBrightness = -1;
	@Unique
	private int pauc$fallbackShadowLift = -1;
	@Unique
	private int pauc$fallbackSaturation = -1;
	@Unique
	private int pauc$fallbackContrast = -1;
	@Unique
	private int pauc$fallbackGamma = -1;
	@Unique
	private int pauc$fallbackDirectionalLight = -1;
	@Unique
	private int pauc$fallbackWaterBlend = -1;
	@Unique
	private int pauc$fallbackFarDesaturation = -1;
	@Unique
	private int pauc$fallbackEmissiveBoost = -1;
	@Unique
	private int pauc$seamMorphStrength = -1;
	@Unique
	private int pauc$seamClipDistance = -1;
	@Unique
	private int pauc$seamMorphWidth = -1;
	@Unique
	private int pauc$seamYLift = -1;
	@Unique
	private int pauc$seamCameraPos = -1;
	@Unique
	private int pauc$seamMotion = -1;
	@Unique
	private int pauc$seamMotionStrength = -1;
	@Unique
	private int pauc$seamMotionWidth = -1;
	@Unique
	private int pauc$seamEdgeHeights = -1;
	@Unique
	private int pauc$seamCornerHeights = -1;
	@Unique
	private int pauc$seamHeightStrength = -1;
	@Unique
	private int pauc$seamMaxVerticalStep = -1;

	@Inject(method = "tryInit", at = @At("TAIL"))
	private void pauc$locateFallbackVisualUniforms(CallbackInfo ci) {
		GlShaderProgram shader = (GlShaderProgram) (Object) this;
		pauc$fallbackVisualStrength = shader.tryGetUniformLocation("uPaucFallbackVisualStrength");
		pauc$fallbackFogColor = shader.tryGetUniformLocation("uPaucFallbackFogColor");
		pauc$fallbackFogStart = shader.tryGetUniformLocation("uPaucFallbackFogStart");
		pauc$fallbackFogEnd = shader.tryGetUniformLocation("uPaucFallbackFogEnd");
		pauc$fallbackFogIntensity = shader.tryGetUniformLocation("uPaucFallbackFogIntensity");
		pauc$fallbackFogFloor = shader.tryGetUniformLocation("uPaucFallbackFogFloor");
		pauc$fallbackRescueStrength = shader.tryGetUniformLocation("uPaucFallbackRescueStrength");
		pauc$fallbackBrightness = shader.tryGetUniformLocation("uPaucFallbackBrightness");
		pauc$fallbackShadowLift = shader.tryGetUniformLocation("uPaucFallbackShadowLift");
		pauc$fallbackSaturation = shader.tryGetUniformLocation("uPaucFallbackSaturation");
		pauc$fallbackContrast = shader.tryGetUniformLocation("uPaucFallbackContrast");
		pauc$fallbackGamma = shader.tryGetUniformLocation("uPaucFallbackGamma");
		pauc$fallbackDirectionalLight = shader.tryGetUniformLocation("uPaucFallbackDirectionalLight");
		pauc$fallbackWaterBlend = shader.tryGetUniformLocation("uPaucFallbackWaterBlend");
		pauc$fallbackFarDesaturation = shader.tryGetUniformLocation("uPaucFallbackFarDesaturation");
		pauc$fallbackEmissiveBoost = shader.tryGetUniformLocation("uPaucFallbackEmissiveBoost");
		pauc$seamMorphStrength = shader.tryGetUniformLocation("uPaucSeamMorphStrength");
		pauc$seamClipDistance = shader.tryGetUniformLocation("uPaucSeamClipDistance");
		pauc$seamMorphWidth = shader.tryGetUniformLocation("uPaucSeamMorphWidth");
		pauc$seamYLift = shader.tryGetUniformLocation("uPaucSeamYLift");
		pauc$seamCameraPos = shader.tryGetUniformLocation("uPaucSeamCameraPos");
		pauc$seamMotion = shader.tryGetUniformLocation("uPaucSeamMotion");
		pauc$seamMotionStrength = shader.tryGetUniformLocation("uPaucSeamMotionStrength");
		pauc$seamMotionWidth = shader.tryGetUniformLocation("uPaucSeamMotionWidth");
		pauc$seamEdgeHeights = shader.tryGetUniformLocation("uPaucSeamEdgeHeights");
		pauc$seamCornerHeights = shader.tryGetUniformLocation("uPaucSeamCornerHeights");
		pauc$seamHeightStrength = shader.tryGetUniformLocation("uPaucSeamHeightStrength");
		pauc$seamMaxVerticalStep = shader.tryGetUniformLocation("uPaucSeamMaxVerticalStep");
		if (!pauc$fallbackShaderUniformsLogged) {
			pauc$fallbackShaderUniformsLogged = true;
			if (pauc$fallbackVisualStrength != -1) {
				PAUC_FALLBACK_SHADER_LOGGER.info("PauC fallback LOD visual uniforms are active in the embedded DH terrain shader.");
			} else {
				PAUC_FALLBACK_SHADER_LOGGER.warn("PauC fallback LOD visual uniforms are missing from the embedded DH terrain shader.");
			}
		}
	}

	@Inject(method = "fillUniformData", at = @At("TAIL"))
	private void pauc$fillFallbackVisualUniforms(DhApiRenderParam renderParameters, CallbackInfo ci) {
		PauCLodFallbackVisuals.State state = PauCLodFallbackVisuals.currentStateWithUpdatedSeam();
		pauc$uniform1f(pauc$fallbackVisualStrength, state.strength());
		pauc$uniform4f(pauc$fallbackFogColor, state.fogRed(), state.fogGreen(), state.fogBlue(), state.fogAlpha());
		pauc$uniform1f(pauc$fallbackFogStart, state.fogStartBlocks());
		pauc$uniform1f(pauc$fallbackFogEnd, state.fogEndBlocks());
		pauc$uniform1f(pauc$fallbackFogIntensity, state.fogIntensity());
		pauc$uniform1f(pauc$fallbackFogFloor, state.fogFloor());
		pauc$uniform1f(pauc$fallbackRescueStrength, state.rescueStrength());
		pauc$uniform1f(pauc$fallbackBrightness, state.brightness());
		pauc$uniform1f(pauc$fallbackShadowLift, state.shadowLift());
		pauc$uniform1f(pauc$fallbackSaturation, state.saturation());
		pauc$uniform1f(pauc$fallbackContrast, state.contrast());
		pauc$uniform1f(pauc$fallbackGamma, state.gamma());
		pauc$uniform1f(pauc$fallbackDirectionalLight, state.directionalLight());
		pauc$uniform1f(pauc$fallbackWaterBlend, state.waterBlend());
		pauc$uniform1f(pauc$fallbackFarDesaturation, state.farDesaturation());
		pauc$uniform1f(pauc$fallbackEmissiveBoost, state.emissiveBoost());
		pauc$uniform1f(pauc$seamMorphStrength, state.seamMorphStrength());
		pauc$uniform1f(pauc$seamClipDistance, state.seamClipDistance());
		pauc$uniform1f(pauc$seamMorphWidth, state.seamMorphWidth());
		pauc$uniform1f(pauc$seamYLift, state.seamYLift());
		pauc$uniform3f(pauc$seamCameraPos, state.seamCameraX(), state.seamCameraY(), state.seamCameraZ());
		pauc$uniform2f(pauc$seamMotion, state.seamMotionX(), state.seamMotionZ());
		pauc$uniform1f(pauc$seamMotionStrength, state.seamMotionStrength());
		pauc$uniform1f(pauc$seamMotionWidth, state.seamMotionWidth());
		pauc$uniform4f(pauc$seamEdgeHeights, state.seamWestHeight(), state.seamEastHeight(), state.seamNorthHeight(), state.seamSouthHeight());
		pauc$uniform4f(pauc$seamCornerHeights, state.seamNorthWestHeight(), state.seamNorthEastHeight(), state.seamSouthWestHeight(), state.seamSouthEastHeight());
		pauc$uniform1f(pauc$seamHeightStrength, state.seamHeightStrength());
		pauc$uniform1f(pauc$seamMaxVerticalStep, state.seamMaxVerticalStep());
		pauc$applyPaucBoundaryClip(renderParameters, state);
	}

	@Unique
	private void pauc$applyPaucBoundaryClip(DhApiRenderParam renderParameters, PauCLodFallbackVisuals.State state) {
		if (!PauCLodNearClipOverride.shouldOverrideCurrentRange()) {
			return;
		}

		float boundaryClipBlocks = PauCLodNearClipOverride.overrideNearClipBlocks(renderParameters.nearClipPlane);
		pauc$uniform1f(this.uClipDistance, boundaryClipBlocks);
		if (!pauc$clipBoundaryLogged) {
			pauc$clipBoundaryLogged = true;
			PAUC_FALLBACK_SHADER_LOGGER.info("PauC applied vanilla-to-LOD clip policy to embedded DH terrain shader: {} blocks.", boundaryClipBlocks);
		}
		if (state.seamMorphStrength() <= 0.0F && !PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
			return;
		}

		pauc$uniform1i(this.uDitherDhRendering, 0);
		if (!pauc$clipFadeDisabledLogged) {
			pauc$clipFadeDisabledLogged = true;
			PAUC_FALLBACK_SHADER_LOGGER.info("PauC disabled DH dither fade for late fallback LOD rendering while keeping the vanilla-to-LOD clip policy.");
		}
	}

	@Unique
	private static void pauc$uniform1f(int location, float value) {
		if (location != -1) {
			GL32.glUniform1f(location, value);
		}
	}

	@Unique
	private static void pauc$uniform1i(int location, int value) {
		if (location != -1) {
			GL32.glUniform1i(location, value);
		}
	}

	@Unique
	private static void pauc$uniform2f(int location, float x, float y) {
		if (location != -1) {
			GL32.glUniform2f(location, x, y);
		}
	}

	@Unique
	private static void pauc$uniform3f(int location, float x, float y, float z) {
		if (location != -1) {
			GL32.glUniform3f(location, x, y, z);
		}
	}

	@Unique
	private static void pauc$uniform4f(int location, float x, float y, float z, float w) {
		if (location != -1) {
			GL32.glUniform4f(location, x, y, z, w);
		}
	}
}
