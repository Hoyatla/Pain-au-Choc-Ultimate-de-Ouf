package net.irisshaders.iris.mixin.forge.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter_forge;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_forge;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_forge;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodLateDepthBuffer;
import fr.hoyatla.pauc.lod.PauCLodScreenFogColor;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderPresentation;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class MixinPauCDhLevelRenderer {
	@Unique
	private static final Logger PAUC_DH_RENDER_LOGGER = LogUtils.getLogger();
	@Unique
	private static boolean pauc$dhRenderWarningLogged;
	@Unique
	private static long pauc$dhLastDiagnosticLogMs;
	@Unique
	private static boolean pauc$transitionHoldLogged;
	@Unique
	private static boolean pauc$skipLodsThisFrame;
	@Unique
	private static boolean pauc$lateRenderStateLogged;
	@Unique
	private static final String PAUC_LATE_RENDER_STATE_BARRIER_PROPERTY = "pauc.lod.lateRenderStateBarrier";
	@Unique
	private static final String PAUC_LATE_RENDER_CLEAR_DEPTH_PROPERTY = "pauc.lod.lateRenderClearDepth";

	@Shadow
	private ClientLevel level;

	@Inject(method = "renderChunkLayer", at = @At("HEAD"))
	private void pauc$renderEmbeddedDhLods(RenderType renderType, PoseStack modelViewMatrixStack, double cameraX, double cameraY, double cameraZ, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (this.level == null) {
			return;
		}
		if (!PauCLodHorizonState.currentRange().enabled()) {
			return;
		}

		try {
			pauc$syncDhRenderState(modelViewMatrixStack, projectionMatrix);

			if (PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
				if (renderType.equals(RenderType.solid())) {
					if (PauCLodShaderContext.consumeTransitionHoldFrame()) {
						pauc$skipLodsThisFrame = true;
						if (!pauc$transitionHoldLogged) {
							pauc$transitionHoldLogged = true;
							PAUC_DH_RENDER_LOGGER.info("PauC is holding embedded DH rendering briefly after a LOD/shader transition: {}", PauCLodShaderContext.describe());
						}
					} else {
						pauc$skipLodsThisFrame = false;
						pauc$transitionHoldLogged = false;
					}
				}
				if (renderType.equals(RenderType.solid())
					|| renderType.equals(RenderType.translucent())
					|| renderType.equals(RenderType.cutout())
					|| renderType.equals(RenderType.tripwire())) {
					return;
				}
			}

			if (renderType.equals(RenderType.solid())) {
				if (PauCLodShaderContext.consumeTransitionHoldFrame()) {
					pauc$skipLodsThisFrame = true;
					if (!pauc$transitionHoldLogged) {
						pauc$transitionHoldLogged = true;
						PAUC_DH_RENDER_LOGGER.info("PauC is holding embedded DH rendering briefly after a LOD/shader transition: {}", PauCLodShaderContext.describe());
					}
					return;
				}
				pauc$skipLodsThisFrame = false;
				pauc$transitionHoldLogged = false;
				ClientApi.INSTANCE.renderLods();
				pauc$logDhRenderDiagnostics();
			} else if (renderType.equals(RenderType.translucent())) {
				if (pauc$skipLodsThisFrame || PauCLodShaderContext.isTransitionHoldActive()) {
					return;
				}
				ClientApi.INSTANCE.renderDeferredLodsForShaders();
			}

			if (renderType.equals(RenderType.cutout())) {
				ClientApi.INSTANCE.renderFadeOpaque();
			} else if (renderType.equals(RenderType.tripwire())) {
				ClientApi.INSTANCE.renderFadeTransparent();
			}
		} catch (Exception | Error error) {
			ClientApi.INSTANCE.rendererDisabledBecauseOfExceptions = true;
			if (!pauc$dhRenderWarningLogged) {
				pauc$dhRenderWarningLogged = true;
				PAUC_DH_RENDER_LOGGER.warn("PauC disabled embedded Distant Horizons rendering after an unexpected render error.", error);
			}
		}
	}

	@Unique
	private void pauc$syncDhRenderState(PoseStack modelViewMatrixStack, Matrix4f projectionMatrix) {
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter_forge.Convert(modelViewMatrixStack.last().pose());
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter_forge.Convert(projectionMatrix);
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper_forge.INSTANCE.getPartialTickTime();
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper_forge.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.level);
		ClientApi.RENDER_STATE.vanillaFogEnabled = !PauCLodShaderPresentation.shouldLateRenderFallbackLods()
			&& (PauCLodHorizonState.shouldExtendVanillaFog() || PauCLodShaderContext.shouldApplyFallbackFog());
	}

	@Unique
	private static void pauc$logDhRenderDiagnostics() {
		long now = System.currentTimeMillis();
		if (now - pauc$dhLastDiagnosticLogMs < 5000L) {
			return;
		}

		pauc$dhLastDiagnosticLogMs = now;
		try {
			IClientLevelWrapper levelWrapper = ClientApi.RENDER_STATE.clientLevelWrapper;
			IDhClientWorld clientWorld = SharedApi.tryGetDhClientWorld();
			IDhClientLevel dhLevel = clientWorld != null && levelWrapper != null ? clientWorld.getClientLevel(levelWrapper) : null;
			RenderBufferHandler renderBufferHandler = dhLevel != null ? dhLevel.getRenderBufferHandler() : null;
			IDhGenericRenderer genericRenderer = dhLevel != null ? dhLevel.getGenericRenderer() : null;
			String bufferDebug = renderBufferHandler != null ? renderBufferHandler.getVboRenderDebugMenuString() : "VBO Render Count: unavailable";
			String genericDebug = genericRenderer != null ? genericRenderer.getVboRenderDebugMenuString() : "Generic Render Count: unavailable";
			PAUC_DH_RENDER_LOGGER.info(
				"PauC embedded DH render diagnostics: world={}, wrapper={}, level={}, rendering={}, validation={}, disabled={}, {}, {}",
				clientWorld != null,
				levelWrapper != null ? levelWrapper.getDhIdentifier() : "none",
				dhLevel != null ? dhLevel.getClass().getSimpleName() : "none",
				dhLevel != null && dhLevel.isRendering(),
				ClientApi.INSTANCE.lastRenderParamValidationMessage,
				ClientApi.INSTANCE.rendererDisabledBecauseOfExceptions,
				bufferDebug,
				genericDebug
			);
			PAUC_DH_RENDER_LOGGER.info("PauC LOD shader diagnostics: {}", PauCLodShaderContext.describe());
		} catch (Exception | Error error) {
			PAUC_DH_RENDER_LOGGER.debug("PauC could not collect embedded Distant Horizons render diagnostics.", error);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "RETURN", shift = At.Shift.BEFORE))
	private void pauc$renderEmbeddedDhLodsAfterShaderFinal(PoseStack poseStack, float tickDelta, long startTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (this.level == null
			|| !PauCLodHorizonState.currentRange().enabled()
			|| !PauCLodShaderPresentation.shouldLateRenderFallbackLods()
			|| pauc$skipLodsThisFrame
			|| PauCLodShaderContext.isTransitionHoldActive()) {
			return;
		}

		boolean prepared = false;
		try {
			pauc$prepareLateFallbackRenderState();
			prepared = true;
			pauc$syncDhRenderState(poseStack, projectionMatrix);
			ClientApi.INSTANCE.renderLods();
			ClientApi.INSTANCE.renderDeferredLodsForShaders();
			PauCLodShaderPresentation.logLateFallbackRender();
			pauc$logDhRenderDiagnostics();
		} catch (Exception | Error error) {
			ClientApi.INSTANCE.rendererDisabledBecauseOfExceptions = true;
			if (!pauc$dhRenderWarningLogged) {
				pauc$dhRenderWarningLogged = true;
				PAUC_DH_RENDER_LOGGER.warn("PauC disabled late embedded Distant Horizons rendering after an unexpected render error.", error);
			}
		} finally {
			if (prepared) {
				pauc$finishLateFallbackRenderState();
			}
		}
	}

	@Unique
	private static void pauc$prepareLateFallbackRenderState() {
		RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
		mainTarget.bindWrite(true);
		PauCLodScreenFogColor.captureFromMainTarget();
		if (!pauc$readBoolean(PAUC_LATE_RENDER_STATE_BARRIER_PROPERTY, true)) {
			return;
		}

		RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
		pauc$clearLateFallbackShaderBindings();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.colorMask(true, true, true, true);
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11C.GL_LEQUAL);
		RenderSystem.depthMask(true);
		GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
		GL11C.glDisable(GL11C.GL_STENCIL_TEST);
		GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);
		GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
		boolean restoredDepth = PauCLodLateDepthBuffer.restoreForLateFallbackRender();
		boolean clearDepth = !restoredDepth && pauc$readBoolean(PAUC_LATE_RENDER_CLEAR_DEPTH_PROPERTY, false);
		if (clearDepth) {
			GL11C.glClearDepth(1.0D);
			GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
		}
		if (!pauc$lateRenderStateLogged) {
			pauc$lateRenderStateLogged = true;
			PAUC_DH_RENDER_LOGGER.info("PauC prepared late fallback LOD render state: restoredDepth={}, clearDepth={}.", restoredDepth, clearDepth);
		}
	}

	@Unique
	private static void pauc$finishLateFallbackRenderState() {
		if (!pauc$readBoolean(PAUC_LATE_RENDER_STATE_BARRIER_PROPERTY, true)) {
			return;
		}

		pauc$clearLateFallbackShaderBindings();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.colorMask(true, true, true, true);
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11C.GL_LEQUAL);
		RenderSystem.depthMask(true);
	}

	@Unique
	private static void pauc$clearLateFallbackShaderBindings() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		BlendModeOverride.restore();
		GlStateManager._glUseProgram(0);
		for (int i = 0; i < SamplerLimits.get().getMaxTextureUnits(); i++) {
			if (GlStateManagerAccessor.getTEXTURES()[i].binding != 0) {
				RenderSystem.activeTexture(GL15C.GL_TEXTURE0 + i);
				RenderSystem.bindTexture(0);
			}
		}

		RenderSystem.activeTexture(GL15C.GL_TEXTURE0);
	}

	@Unique
	private static boolean pauc$readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}
}
