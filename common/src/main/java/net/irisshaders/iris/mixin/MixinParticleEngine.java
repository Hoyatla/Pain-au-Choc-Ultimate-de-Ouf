package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures that all particles are rendered with the textured_lit shader program.
 */
@Mixin(ParticleEngine.class)
public class MixinParticleEngine {
	@Inject(method = "render", at = @At("HEAD"))
	private void iris$beginDrawingParticles(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float f, CallbackInfo ci) {
		Iris.getPipelineManager().getPipeline().ifPresent(pipeline -> pipeline.setPhase(WorldRenderingPhase.PARTICLES));
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void iris$finishDrawingParticles(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float f, CallbackInfo ci) {
		Iris.getPipelineManager().getPipeline().ifPresent(pipeline -> pipeline.setPhase(WorldRenderingPhase.NONE));
	}

	@WrapWithCondition(
		method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V")
	)
	private boolean pauc$renderVisibleParticle(Particle particle, VertexConsumer vertexConsumer, Camera camera, float tickDelta) {
		Vec3 position = particle.getPos();
		return !PauCLodRenderCulling.shouldCullParticle(position.x, position.y, position.z);
	}
}
