package net.irisshaders.batchedentityrendering.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.batchedentityrendering.impl.BatchedEntityRenderingPolicy;
import net.irisshaders.batchedentityrendering.impl.DrawCallTrackingRenderBuffers;
import net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource;
import net.irisshaders.batchedentityrendering.impl.Groupable;
import net.irisshaders.batchedentityrendering.impl.RenderBuffersExt;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.lang.reflect.Field;

/**
 * Tracks whether or not the world is being rendered, and manages grouping
 * with different entities.
 */
// Uses a priority of 999 to apply before the main Iris mixins to draw entities before deferred runs.
@Mixin(value = LevelRenderer.class, priority = 999)
public class MixinLevelRenderer {
	private static final String RENDER_ENTITY =
		"Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V";

	@Unique
	private static Field batchedentityrendering$renderBuffersField;

	@Unique
	private Groupable groupable;

	@Unique
	private RenderBuffers batchedentityrendering$getRenderBuffers() {
		Field field = batchedentityrendering$renderBuffersField;
		if (field == null) {
			field = batchedentityrendering$findRenderBuffersField();
			batchedentityrendering$renderBuffersField = field;
		}

		if (field == null) {
			return null;
		}

		try {
			return (RenderBuffers) field.get(this);
		} catch (IllegalAccessException e) {
			return null;
		}
	}

	@Unique
	private static Field batchedentityrendering$findRenderBuffersField() {
		try {
			Field field = LevelRenderer.class.getDeclaredField("renderBuffers");
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException ignored) {
		}

		try {
			Field field = LevelRenderer.class.getDeclaredField("f_109464_");
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException ignored) {
		}

		return null;
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void batchedentityrendering$beginLevelRender(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		RenderBuffers renderBuffers = batchedentityrendering$getRenderBuffers();
		if (renderBuffers == null) {
			return;
		}

		if (renderBuffers instanceof DrawCallTrackingRenderBuffers) {
			((DrawCallTrackingRenderBuffers) renderBuffers).resetDrawCounts();
		}

		if (!BatchedEntityRenderingPolicy.isEnabled()) {
			groupable = null;
			return;
		}

		((RenderBuffersExt) renderBuffers).beginLevelRendering();
		MultiBufferSource provider = renderBuffers.bufferSource();

		if (provider instanceof Groupable) {
			groupable = (Groupable) provider;
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_ENTITY))
	private void batchedentityrendering$preRenderEntity(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		if (!BatchedEntityRenderingPolicy.isEnabled()) {
			return;
		}
		if (groupable != null) {
			groupable.startGroup();
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_ENTITY, shift = At.Shift.AFTER))
	private void batchedentityrendering$postRenderEntity(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		if (!BatchedEntityRenderingPolicy.isEnabled()) {
			return;
		}
		if (groupable != null) {
			groupable.endGroup();
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"), locals = LocalCapture.CAPTURE_FAILHARD)
	private void batchedentityrendering$beginTranslucents(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		if (!BatchedEntityRenderingPolicy.isEnabled()) {
			return;
		}

		RenderBuffers renderBuffers = batchedentityrendering$getRenderBuffers();
		if (renderBuffers == null) {
			return;
		}

		if (renderBuffers.bufferSource() instanceof FullyBufferedMultiBufferSource fullyBufferedMultiBufferSource) {
			fullyBufferedMultiBufferSource.readyUp();
		}

		if (WorldRenderingSettings.INSTANCE.shouldSeparateEntityDraws()) {
			Minecraft.getInstance().getProfiler().popPush("entity_draws_opaque");
			if (renderBuffers.bufferSource() instanceof FullyBufferedMultiBufferSource source) {
				source.endBatchWithType(TransparencyType.OPAQUE);
				source.endBatchWithType(TransparencyType.OPAQUE_DECAL);
			} else {
				renderBuffers.bufferSource().endBatch();
			}
		} else {
			Minecraft.getInstance().getProfiler().popPush("entity_draws");
			renderBuffers.bufferSource().endBatch();
		}
	}


	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
	private void batchedentityrendering$endTranslucents(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		if (!BatchedEntityRenderingPolicy.isEnabled()) {
			return;
		}

		RenderBuffers renderBuffers = batchedentityrendering$getRenderBuffers();
		if (renderBuffers == null) {
			return;
		}

		if (WorldRenderingSettings.INSTANCE.shouldSeparateEntityDraws()) {
			renderBuffers.bufferSource().endBatch();
		}
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void batchedentityrendering$endLevelRender(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		RenderBuffers renderBuffers = batchedentityrendering$getRenderBuffers();
		if (renderBuffers == null) {
			groupable = null;
			return;
		}

		if (!BatchedEntityRenderingPolicy.isEnabled()) {
			groupable = null;
			return;
		}

		((RenderBuffersExt) renderBuffers).endLevelRendering();
		groupable = null;
	}
}
