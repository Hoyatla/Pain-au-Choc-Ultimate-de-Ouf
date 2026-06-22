package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.layer.IsOutlineRenderStateShard;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
	private static final String RENDER = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V";
	private static final String CLEAR = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V";
	private static final String RENDER_SKY = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V";
	private static final String RENDER_CLOUDS = "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V";
	private static final String RENDER_WEATHER = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V";

	@Unique
	private WorldRenderingPipeline pipeline;

	@Unique
	private static final String PAUC_SKY_FOG_BLEND_PROPERTY = "pauc.lod.skyFogColorBlend";

	@Unique
	private static boolean pauc$shouldBypassPipeline() {
		return PauCRenderLifecycle.isClientLogoutInProgress()
			|| PauCRenderLifecycle.isClientLogoutPipelineDestroyActive();
	}

	@Unique
	private static float pauc$skyFogBlend() {
		String rawValue = System.getProperty(PAUC_SKY_FOG_BLEND_PROPERTY);
		if (rawValue == null) {
			return 1.0F;
		}

		try {
			return Math.max(0.0F, Math.min(1.0F, Float.parseFloat(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return 1.0F;
		}
	}

	@Unique
	private static boolean pauc$shouldUseFogSkyColor() {
		boolean shaderPackInUse = PauCLodShaderContext.isShaderPackInUse();
		boolean paucShaderpackOwnsSky = shaderPackInUse
			&& PauCLodShaderProfiles.currentFamily() == PauCLodShaderProfiles.Family.PAUC;
		return PauCLodClientSettings.isVanillaFogEnabled()
			&& PauCLodHorizonState.shouldExtendVanillaFog()
			&& (!shaderPackInUse || paucShaderpackOwnsSky)
			&& pauc$skyFogBlend() > 0.0F;
	}

	@Unique
	private static float pauc$blendSkyWithFog(float original, double fogComponent) {
		pauc$logFogDomeProbe();
		if (!pauc$shouldUseFogSkyColor()) {
			return original;
		}

		float blend = pauc$skyFogBlend();
		return original + ((float) fogComponent - original) * blend;
	}

	@Unique
	private static boolean pauc$shouldHideVoidSkyBand() {
		return PauCLodShaderContext.isShaderPackInUse()
			&& PauCLodShaderProfiles.currentFamily() == PauCLodShaderProfiles.Family.PAUC;
	}

	@Unique
	private static boolean pauc$fogDomeProbeInit;
	@Unique
	private static boolean pauc$fogDomeProbeLastFog;
	// Verification probe: logs the vanilla fog button together with the distance-fog and sky-dome-tint states
	// on each button flip, so it is visible in a session log that they switch off together (no fog<->dome swap).
	@Unique
	private static void pauc$logFogDomeProbe() {
		boolean fog = PauCLodClientSettings.isVanillaFogEnabled();
		if (pauc$fogDomeProbeInit && fog == pauc$fogDomeProbeLastFog) {
			return;
		}
		pauc$fogDomeProbeInit = true;
		pauc$fogDomeProbeLastFog = fog;
		boolean shader = PauCLodShaderContext.isShaderPackInUse();
		Iris.logger.info(
			"PauC FOG-DOME: vanillaFog={}, distanceFogDisabled={}, skyDomeTint={}, shader={}",
			fog,
			!fog && !shader,
			pauc$shouldUseFogSkyColor(),
			shader
		);
	}

	// Begin shader rendering after buffers have been cleared.
	// At this point we've ensured that Minecraft's main framebuffer is cleared.
	// This is important or else very odd issues will happen with shaders that have a final pass that doesn't write to
	// all pixels.
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void iris$setupPipeline(PoseStack poseStack, float tickDelta, long startTime, boolean renderBlockOutline,
									Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
									Matrix4f projection, CallbackInfo callback) {
		if (pauc$shouldBypassPipeline()) {
			pipeline = null;
			return;
		}

		// PauC dynamic resolution: the main render target is resized smaller (e.g. 0.65x), but its viewWidth/viewHeight
		// can remain at the window size. Every RenderTarget.bindWrite(true) then sets an oversized viewport on the smaller
		// framebuffer, so immediate-mode gbuffer geometry (entities/hand/items/hitboxes) rasterizes off-center. Realign
		// viewWidth/viewHeight to the actual framebuffer size each frame so all downstream binds use the correct viewport.
		com.mojang.blaze3d.pipeline.RenderTarget paucMainTarget = Minecraft.getInstance().getMainRenderTarget();
		if (paucMainTarget != null
			&& (paucMainTarget.viewWidth != paucMainTarget.width || paucMainTarget.viewHeight != paucMainTarget.height)) {
			paucMainTarget.viewWidth = paucMainTarget.width;
			paucMainTarget.viewHeight = paucMainTarget.height;
		}

		DHCompat.checkFrame();

		IrisTimeUniforms.updateTime();
		CapturedRenderingState.INSTANCE.setGbufferModelView(poseStack.last().pose());
		CapturedRenderingState.INSTANCE.setGbufferProjection(projection);
		CapturedRenderingState.INSTANCE.setTickDelta(tickDelta);
		CapturedRenderingState.INSTANCE.setRealTickDelta(tickDelta);
		ClientLevel clientLevel = Minecraft.getInstance().level;
		float cloudTicks = clientLevel != null ? (float) clientLevel.getGameTime() : 0.0F;
		CapturedRenderingState.INSTANCE.setCloudTime((cloudTicks + tickDelta) * 0.03F);
		SystemTimeUniforms.COUNTER.beginFrame();
		SystemTimeUniforms.TIMER.beginFrame(startTime);

		pipeline = Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());

		if (pipeline.shouldDisableFrustumCulling()) {
			((LevelRendererAccessor) this).setCullingFrustum(new NonCullingFrustum());
		}

		Minecraft.getInstance().smartCull = !pipeline.shouldDisableOcclusionCulling();

		if (Iris.shouldActivateWireframe() && Minecraft.getInstance().isLocalServer()) {
			IrisRenderSystem.setPolygonMode(GL43C.GL_LINE);
		}
	}

	// Begin shader rendering after buffers have been cleared.
	// At this point we've ensured that Minecraft's main framebuffer is cleared.
	// This is important or else very odd issues will happen with shaders that have a final pass that doesn't write to
	// all pixels.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = CLEAR, shift = At.Shift.AFTER, remap = false))
	private void iris$beginLevelRender(PoseStack poseStack, float tickDelta, long startTime, boolean renderBlockOutline,
									   Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
									   Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null || pauc$shouldBypassPipeline()) {
			return;
		}

		pipeline.beginLevelRendering();
		pipeline.setPhase(WorldRenderingPhase.NONE);
	}


	// Inject a bit early so that we can end our rendering before mods like VoxelMap (which inject at RETURN)
	// render their waypoint beams.
	@Inject(method = RENDER, at = @At(value = "RETURN", shift = At.Shift.BEFORE))
	private void iris$endLevelRender(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		HandRenderer.INSTANCE.renderTranslucent(poseStack, tickDelta, camera, gameRenderer, pipeline);
		Minecraft.getInstance().getProfiler().popPush("iris_final");
		pipeline.finalizeLevelRendering();
		pipeline = null;

		if (Iris.shouldActivateWireframe() && Minecraft.getInstance().isLocalServer()) {
			IrisRenderSystem.setPolygonMode(GL43C.GL_FILL);
		}
	}

	// Setup shadow terrain & render shadows before the main terrain setup. We need to do things in this order to
	// avoid breaking other mods such as Light Overlay: https://github.com/IrisShaders/Iris/issues/1356

	// Do this before sky rendering so it's ready before the sky render starts.
	@Inject(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"))
	private void iris$renderTerrainShadows(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo callback) {
		if (pipeline == null || pauc$shouldBypassPipeline()) {
			return;
		}

		pipeline.renderShadows((LevelRendererAccessor) this, camera);
	}

	@ModifyVariable(method = "renderSky", at = @At(value = "HEAD"), index = 5, argsOnly = true)
	private boolean iris$alwaysRenderSky(boolean value) {
		return false;
	}

	@ModifyArg(
		method = "renderSky",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V", ordinal = 0, remap = false),
		index = 0
	)
	private float pauc$useFogColorForVanillaSkyRed(float original) {
		Vector3d fogColor = CapturedRenderingState.INSTANCE.getFogColor();
		return pauc$blendSkyWithFog(original, fogColor.x);
	}

	@ModifyArg(
		method = "renderSky",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V", ordinal = 0, remap = false),
		index = 1
	)
	private float pauc$useFogColorForVanillaSkyGreen(float original) {
		Vector3d fogColor = CapturedRenderingState.INSTANCE.getFogColor();
		return pauc$blendSkyWithFog(original, fogColor.y);
	}

	@ModifyArg(
		method = "renderSky",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V", ordinal = 0, remap = false),
		index = 2
	)
	private float pauc$useFogColorForVanillaSkyBlue(float original) {
		Vector3d fogColor = CapturedRenderingState.INSTANCE.getFogColor();
		return pauc$blendSkyWithFog(original, fogColor.z);
	}

	@ModifyArg(
		method = "renderSky",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V", ordinal = 5, remap = false),
		index = 3
	)
	private float pauc$hideVoidSkyBandAlpha(float original) {
		return pauc$shouldHideVoidSkyBand() ? 0.0F : original;
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_SKY))
	private void iris$beginSky(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		// Use CUSTOM_SKY until levelFogColor is called as a heuristic to catch FabricSkyboxes.
		pipeline.setPhase(WorldRenderingPhase.CUSTOM_SKY);

		// We've changed the phase, but vanilla doesn't update the shader program at this point before rendering stuff,
		// so we need to manually refresh the shader program so that the correct shader override gets applied.
		// TODO: Move the injection instead
		RenderSystem.setShader(GameRenderer::getPositionShader);
	}

	@Inject(method = RENDER_SKY,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V"))
	private void iris$renderSky$beginNormalSky(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		// None of the vanilla sky is rendered until after this call, so if anything is rendered before, it's
		// CUSTOM_SKY.
		pipeline.setPhase(WorldRenderingPhase.SKY);
	}

	@Inject(method = "renderSky", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;SUN_LOCATION:Lnet/minecraft/resources/ResourceLocation;"))
	private void iris$setSunRenderStage(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.SUN);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"))
	private void iris$setSunsetRenderStage(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.SUNSET);
	}

	@Inject(method = "renderSky", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"))
	private void iris$setMoonRenderStage(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.MOON);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
	private void iris$setStarRenderStage(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.STARS);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
	private void iris$setVoidRenderStage(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.VOID);
	}

	@Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getTimeOfDay(F)F"),
		slice = @Slice(from = @At(value = "FIELD", target = "Lcom/mojang/math/Axis;YP:Lcom/mojang/math/Axis;")))
	private void iris$renderSky$tiltSun(PoseStack poseStack, Matrix4f projectionMatrix, float f, Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		poseStack.mulPose(Axis.ZP.rotationDegrees(pipeline.getSunPathRotation()));
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_SKY, shift = At.Shift.AFTER))
	private void iris$endSky(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_CLOUDS))
	private void iris$beginClouds(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.CLOUDS);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_CLOUDS, shift = At.Shift.AFTER))
	private void iris$endClouds(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.NONE);
	}


	@Inject(method = "renderChunkLayer", at = @At("HEAD"))
	private void iris$beginTerrainLayer(RenderType renderType, PoseStack poseStack, double d, double e, double f, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.fromTerrainRenderType(renderType));
	}

	@Inject(method = "renderChunkLayer", at = @At("RETURN"))
	private void iris$endTerrainLayer(RenderType renderType, PoseStack poseStack, double d, double e, double f, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_WEATHER))
	private void iris$beginWeather(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.RAIN_SNOW);
	}

	@ModifyArg(method = RENDER_WEATHER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;depthMask(Z)V", ordinal = 0, remap = false))
	private boolean iris$writeRainAndSnowToDepthBuffer(boolean depthMaskEnabled) {
		if (pipeline == null) {
			return depthMaskEnabled;
		}

		if (pipeline.shouldWriteRainAndSnowToDepthBuffer()) {
			return true;
		}

		return depthMaskEnabled;
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_WEATHER, shift = At.Shift.AFTER))
	private void iris$endWeather(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V"))
	private void iris$beginWorldBorder(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.WORLD_BORDER);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V", shift = At.Shift.AFTER))
	private void iris$endWorldBorder(PoseStack poseStack, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V"))
	private void iris$setDebugRenderStage(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.DEBUG);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V", shift = At.Shift.AFTER))
	private void iris$resetDebugRenderStage(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (pipeline == null) {
			return;
		}

		pipeline.setPhase(WorldRenderingPhase.NONE);
	}

	@ModifyArg(method = "renderLevel",
		at = @At(value = "INVOKE", target = "net/minecraft/client/renderer/MultiBufferSource$BufferSource.getBuffer (Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
		slice = @Slice(
			from = @At(value = "CONSTANT", args = "stringValue=outline"),
			to = @At(value = "INVOKE", target = "net/minecraft/client/renderer/LevelRenderer.renderHitOutline (Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V")
		))
	private RenderType iris$beginBlockOutline(RenderType type) {
		return new OuterWrappedRenderType("iris:is_outline", type, IsOutlineRenderStateShard.INSTANCE);
	}

	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"))
	private void iris$beginTranslucents(PoseStack poseStack, float tickDelta, long limitTime,
										boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
										LightTexture lightTexture, Matrix4f projection,
										CallbackInfo ci) {
		if (pipeline == null || pauc$shouldBypassPipeline()) {
			return;
		}

		pipeline.beginHand();
		HandRenderer.INSTANCE.renderSolid(poseStack, tickDelta, camera, gameRenderer, pipeline);
		Minecraft.getInstance().getProfiler().popPush("iris_pre_translucent");
		pipeline.beginTranslucents();
	}
}
