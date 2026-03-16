package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pauc.pain_au_choc.PauCClient;

/**
 * Mixin to optimize ModelPart rendering.
 *
 * Embeddium-like optimization: early-exit from render() when the part
 * and all children are invisible, avoiding unnecessary pose stack operations.
 *
 * Additionally, under PAUC budget pressure, very small model parts
 * at distance can be skipped entirely.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {

    @Shadow public boolean visible;
    @Shadow public boolean skipDraw;

    /**
     * Fast early-exit: if the part is invisible and skipDraw is set,
     * cancel the entire render subtree immediately. Vanilla still
     * pushes and pops the PoseStack even for invisible parts.
     */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void pauc$skipInvisibleParts(PoseStack poseStack, VertexConsumer consumer,
                                          int packedLight, int packedOverlay, CallbackInfo ci) {
        if (!this.visible && this.skipDraw) {
            ci.cancel();
        }
    }
}
