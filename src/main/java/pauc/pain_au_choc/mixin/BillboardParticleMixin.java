package pauc.pain_au_choc.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.render.entity.PauCEntityRenderOptimizer;

/**
 * Mixin to optimize billboard particle rendering.
 *
 * Reduces particle render distance under PAUC budget pressure.
 * Distant particles are culled before any vertex generation occurs,
 * saving both CPU (vertex building) and GPU (draw calls) time.
 *
 * Adapted from Embeddium's BillboardParticleMixin.
 */
@Mixin(SingleQuadParticle.class)
public abstract class BillboardParticleMixin extends Particle {

    protected BillboardParticleMixin(net.minecraft.client.multiplayer.ClientLevel level,
                                      double x, double y, double z) {
        super(level, x, y, z);
    }

    /**
     * Override the render distance check to apply PAUC's adaptive culling.
     * Particles beyond the adjusted render distance are skipped.
     */
    @Inject(method = "shouldCull", at = @At("HEAD"), cancellable = true)
    private void pauc$adaptiveParticleCulling(CallbackInfoReturnable<Boolean> cir) {
        if (!PauCClient.isBudgetActive()) {
            return;
        }

        double multiplier = PauCEntityRenderOptimizer.getParticleRenderDistanceSqMultiplier();
        if (multiplier >= 1.0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return;
        }

        var cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        double dx = this.x - cameraPos.x;
        double dy = this.y - cameraPos.y;
        double dz = this.z - cameraPos.z;
        double distSqr = dx * dx + dy * dy + dz * dz;

        // Base render distance for particles is roughly 256 blocks (65536 squared)
        double maxDistSqr = 65536.0 * multiplier;
        if (distSqr > maxDistSqr) {
            cir.setReturnValue(true); // shouldCull = true → skip this particle
        }
    }
}
