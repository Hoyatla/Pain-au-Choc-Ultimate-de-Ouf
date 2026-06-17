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
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodFallbackVisuals;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodLateDepthBuffer;
import fr.hoyatla.pauc.lod.PauCLodScreenFogColor;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderPresentation;
import fr.hoyatla.pauc.platform.forge.client.PauCCudaLodProxyRenderer;
import fr.hoyatla.pauc.platform.forge.client.PauCEmbeddedLodRuntimeDiagnostics;
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
	private static boolean pauc$lateDeferredSkipLogged;
	@Unique
	private static long pauc$lateFallbackTimingLastLogMs;
	@Unique
	private static long pauc$lateFallbackTotalNanos;
	@Unique
	private static long pauc$lateFallbackRenderNanos;
	@Unique
	private static long pauc$lateFallbackDeferredNanos;
	@Unique
	private static long pauc$lateFallbackMaxTotalNanos;
	@Unique
	private static int pauc$lateFallbackTimingSamples;
	@Unique
	private static final String PAUC_LATE_RENDER_STATE_BARRIER_PROPERTY = "pauc.lod.lateRenderStateBarrier";
	@Unique
	private static final String PAUC_LATE_RENDER_CLEAR_DEPTH_PROPERTY = "pauc.lod.lateRenderClearDepth";
	@Unique
	private static final String PAUC_LATE_RENDER_DEFERRED_PASS_PROPERTY = "pauc.lod.shaderFallbackLateDeferredPass";
	@Unique
	private static final String PAUC_LATE_RENDER_TIMING_PROPERTY = "pauc.lod.shaderFallbackLateTiming";
	@Unique
	private static final String PAUC_LATE_RENDER_TIMING_LOG_MS_PROPERTY = "pauc.lod.shaderFallbackLateTimingLogMs";
	@Unique
	private static final String PAUC_TRANSITION_HOLD_PROXY_PROPERTY = "pauc.lod.transitionHoldKeepsProxy";
	@Unique
	private static final String PAUC_SHADER_FALLBACK_PROXY_PREPASS_PROPERTY = "pauc.lod.shaderFallbackProxyPrepass";

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
		if (renderType.equals(RenderType.solid())) {
			PauCLodFallbackVisuals.beginFrameSnapshot();
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
						pauc$renderShaderFallbackProxyPrepass(modelViewMatrixStack);
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
					pauc$renderTransitionHoldProxy(modelViewMatrixStack, "solid-transition-hold");
					return;
				}
				pauc$skipLodsThisFrame = false;
				pauc$transitionHoldLogged = false;
				long pauc$lodPassStart = System.nanoTime();
				ClientApi.INSTANCE.renderLods();
				fr.hoyatla.pauc.platform.forge.client.PauCLodRenderTimer.recordSolidPassNanos(System.nanoTime() - pauc$lodPassStart);
				PauCCudaLodProxyRenderer.render(this.level, Minecraft.getInstance().gameRenderer.getMainCamera(), modelViewMatrixStack, "solid");
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
		// The Vanilla Fog toggle also governs the DH LOD runtime's own vanilla fog (shaderless only): when the
		// player turns fog off without a shader pack, the runtime skips applying vanilla fog to the distant
		// LODs (a flag it already honors). With a shader pack active the toggle is forced on, so we never touch
		// the shader's fog on the LODs — the shader owns its atmosphere.
		boolean fogToggle = PauCLodClientSettings.isVanillaFogEnabled() || PauCLodShaderContext.isShaderPackInUse();
		ClientApi.RENDER_STATE.vanillaFogEnabled = fogToggle
			&& !PauCLodShaderPresentation.shouldLateRenderFallbackLods()
			&& (PauCLodHorizonState.shouldExtendVanillaFog() || PauCLodShaderContext.shouldApplyFallbackFog());
		pauc$logFogPerfProbe(fogToggle, ClientApi.RENDER_STATE.vanillaFogEnabled);
	}

	@Unique
	private static long pauc$fogPerfProbeLastLogMs;
	@Unique
	private static boolean pauc$fogPerfProbeLastToggle = true;

	@Unique
	private static void pauc$logFogPerfProbe(boolean fogToggle, boolean dhVanillaFog) {
		// Probe: surfaces when the fog perf lever flips and what the DH LOD runtime fog ends up at, so the
		// FPS delta of cutting fog can be attributed in a session log.
		if (fogToggle == pauc$fogPerfProbeLastToggle) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - pauc$fogPerfProbeLastLogMs < 1000L) {
			return;
		}
		pauc$fogPerfProbeLastLogMs = now;
		pauc$fogPerfProbeLastToggle = fogToggle;
		PAUC_DH_RENDER_LOGGER.info(
			"PauC FOG-PERF: vanillaFogToggle={}, dhLodVanillaFog={}, lateRender={}",
			fogToggle,
			dhVanillaFog,
			PauCLodShaderPresentation.shouldLateRenderFallbackLods()
		);
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
			PAUC_DH_RENDER_LOGGER.info("PauC LOD shader diagnostics: {}, {}.", PauCLodShaderContext.describe(), PauCEmbeddedLodRuntimeDiagnostics.describeState());
		} catch (Exception | Error error) {
			PAUC_DH_RENDER_LOGGER.debug("PauC could not collect embedded Distant Horizons render diagnostics.", error);
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "RETURN", shift = At.Shift.BEFORE))
	private void pauc$renderEmbeddedDhLodsAfterShaderFinal(PoseStack poseStack, float tickDelta, long startTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (this.level == null
			|| !PauCLodHorizonState.currentRange().enabled()
			|| !PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
			return;
		}
		if (pauc$skipLodsThisFrame || PauCLodShaderContext.isTransitionHoldActive()) {
			pauc$renderLateTransitionHoldProxy(poseStack, projectionMatrix, camera);
			return;
		}

		boolean prepared = false;
		long passStartNanos = System.nanoTime();
		long renderNanos = 0L;
		long deferredNanos = 0L;
		try {
			pauc$prepareLateFallbackRenderState();
			prepared = true;
			pauc$syncDhRenderState(poseStack, projectionMatrix);
			long renderStartNanos = System.nanoTime();
			ClientApi.INSTANCE.renderLods();
			PauCCudaLodProxyRenderer.render(this.level, camera, poseStack, "late-shader");
			renderNanos = System.nanoTime() - renderStartNanos;
			if (pauc$readBoolean(PAUC_LATE_RENDER_DEFERRED_PASS_PROPERTY, false)) {
				long deferredStartNanos = System.nanoTime();
				ClientApi.INSTANCE.renderDeferredLodsForShaders();
				deferredNanos = System.nanoTime() - deferredStartNanos;
			} else if (!pauc$lateDeferredSkipLogged) {
				pauc$lateDeferredSkipLogged = true;
				PAUC_DH_RENDER_LOGGER.info("PauC skipped the late deferred PL fallback pass; terrain LODs still render, generic/cloud shader artifacts are avoided.");
			}
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
			pauc$recordLateFallbackTiming(System.nanoTime() - passStartNanos, renderNanos, deferredNanos);
		}
	}

	@Unique
	private void pauc$renderTransitionHoldProxy(PoseStack poseStack, String passName) {
		if (!pauc$readBoolean(PAUC_TRANSITION_HOLD_PROXY_PROPERTY, true) || this.level == null) {
			return;
		}
		PauCCudaLodProxyRenderer.render(this.level, Minecraft.getInstance().gameRenderer.getMainCamera(), poseStack, passName);
	}

	@Unique
	private void pauc$renderShaderFallbackProxyPrepass(PoseStack poseStack) {
		if (!pauc$readBoolean(PAUC_SHADER_FALLBACK_PROXY_PREPASS_PROPERTY, false) || this.level == null) {
			return;
		}
		PauCCudaLodProxyRenderer.render(this.level, Minecraft.getInstance().gameRenderer.getMainCamera(), poseStack, "solid-shader-prepass");
	}

	@Unique
	private void pauc$renderLateTransitionHoldProxy(PoseStack poseStack, Matrix4f projectionMatrix, Camera camera) {
		if (!pauc$readBoolean(PAUC_TRANSITION_HOLD_PROXY_PROPERTY, true) || this.level == null) {
			return;
		}
		boolean prepared = false;
		try {
			pauc$prepareLateFallbackRenderState();
			prepared = true;
			pauc$syncDhRenderState(poseStack, projectionMatrix);
			PauCCudaLodProxyRenderer.render(this.level, camera, poseStack, "late-transition-hold");
		} catch (Exception | Error error) {
			if (!pauc$dhRenderWarningLogged) {
				pauc$dhRenderWarningLogged = true;
				PAUC_DH_RENDER_LOGGER.warn("PauC skipped the transition-hold CUDA proxy after an unexpected render error.", error);
			}
		} finally {
			if (prepared) {
				pauc$finishLateFallbackRenderState();
			}
		}
	}

	@Unique
	private static void pauc$recordLateFallbackTiming(long totalNanos, long renderNanos, long deferredNanos) {
		if (!pauc$readBoolean(PAUC_LATE_RENDER_TIMING_PROPERTY, true)) {
			return;
		}

		pauc$lateFallbackTimingSamples++;
		pauc$lateFallbackTotalNanos += totalNanos;
		pauc$lateFallbackRenderNanos += renderNanos;
		pauc$lateFallbackDeferredNanos += deferredNanos;
		pauc$lateFallbackMaxTotalNanos = Math.max(pauc$lateFallbackMaxTotalNanos, totalNanos);

		long now = System.currentTimeMillis();
		long logIntervalMs = pauc$readInt(PAUC_LATE_RENDER_TIMING_LOG_MS_PROPERTY, 5_000, 1_000, 60_000);
		if (now - pauc$lateFallbackTimingLastLogMs < logIntervalMs) {
			return;
		}
		int samples = Math.max(1, pauc$lateFallbackTimingSamples);
		PAUC_DH_RENDER_LOGGER.info(
			"PauC late fallback LOD timings: samples={}, totalAvg={}ms, renderAvg={}ms, deferredAvg={}ms, totalMax={}ms.",
			samples,
			pauc$formatMillis(pauc$lateFallbackTotalNanos / samples),
			pauc$formatMillis(pauc$lateFallbackRenderNanos / samples),
			pauc$formatMillis(pauc$lateFallbackDeferredNanos / samples),
			pauc$formatMillis(pauc$lateFallbackMaxTotalNanos)
		);
		pauc$lateFallbackTimingLastLogMs = now;
		pauc$lateFallbackTimingSamples = 0;
		pauc$lateFallbackTotalNanos = 0L;
		pauc$lateFallbackRenderNanos = 0L;
		pauc$lateFallbackDeferredNanos = 0L;
		pauc$lateFallbackMaxTotalNanos = 0L;
	}

	@Unique
	private static void pauc$prepareLateFallbackRenderState() {
		RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
		mainTarget.bindWrite(true);
		PauCLodScreenFogColor.captureFromMainTarget();
		PauCLodFallbackVisuals.beginFrameSnapshot();
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

	@Unique
	private static int pauc$readInt(String key, int fallback, int min, int max) {
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

	@Unique
	private static String pauc$formatMillis(long nanos) {
		return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
	}
}
