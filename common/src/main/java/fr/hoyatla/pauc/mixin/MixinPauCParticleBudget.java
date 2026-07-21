package fr.hoyatla.pauc.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import fr.hoyatla.pauc.lod.PauCParticleBudget;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PauC particle spike absorber (extracted from the vendored shader tree — pure PauC): rejects
 * overflow/distant particle SPAWNS during a measured frame spike, and skips rendering particles
 * culled by the LOD render culling. No-ops on healthy frames.
 */
@Mixin(ParticleEngine.class)
public class MixinPauCParticleBudget {
	@Inject(method = "add", at = @At("HEAD"), cancellable = true)
	private void pauc$budgetParticleSpawn(Particle particle, CallbackInfo ci) {
		if (particle == null) {
			return;
		}
		Vec3 position = particle.getPos();
		if (PauCParticleBudget.shouldRejectSpawn(position.x, position.y, position.z)) {
			ci.cancel();
		}
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
