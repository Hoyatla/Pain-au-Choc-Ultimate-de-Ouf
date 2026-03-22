package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import pauc.pain_au_choc.ParticleBudgetController;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.RenderBudgetManager;
import pauc.pain_au_choc.render.entity.PauCEntityRenderOptimizer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(
            method = "add(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$budgetParticleSpawn(Particle particle, CallbackInfo callbackInfo) {
        if (!RenderBudgetManager.shouldRenderParticles()) {
            callbackInfo.cancel();
            return;
        }

        if (!ParticleBudgetController.shouldAcceptParticle((ParticleEngine) (Object) this, particle)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$budgetParticles(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float partialTick, CallbackInfo callbackInfo) {
        if (!RenderBudgetManager.shouldRenderParticles()) {
            callbackInfo.cancel();
        }
    }

    @Redirect(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
            )
    )
    private void pauc$adaptiveParticleDistanceCulling(Particle particle, VertexConsumer vertexConsumer, Camera camera, float partialTick) {
        if (particle == null || shouldCullParticleByDistance(particle, camera)) {
            return;
        }
        particle.render(vertexConsumer, camera, partialTick);
    }

    private static boolean shouldCullParticleByDistance(Particle particle, Camera camera) {
        if (!PauCClient.isBudgetActive()) {
            return false;
        }

        double multiplier = PauCEntityRenderOptimizer.getParticleRenderDistanceSqMultiplier();
        if (multiplier >= 1.0D || camera == null) {
            return false;
        }

        var cameraPos = camera.getPosition();
        var bb = particle.getBoundingBox();
        double centerX = (bb.minX + bb.maxX) * 0.5D;
        double centerY = (bb.minY + bb.maxY) * 0.5D;
        double centerZ = (bb.minZ + bb.maxZ) * 0.5D;
        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double distSqr = dx * dx + dy * dy + dz * dz;

        double maxDistSqr = 65536.0D * multiplier;
        return distSqr > maxDistSqr;
    }
}

