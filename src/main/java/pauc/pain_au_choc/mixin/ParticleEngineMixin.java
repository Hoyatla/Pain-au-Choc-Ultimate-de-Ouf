package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import pauc.pain_au_choc.ParticleBudgetController;
import pauc.pain_au_choc.RenderBudgetManager;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}

