package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import pauc.pain_au_choc.DynamicResolutionController;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void pauc$beginDynamicResolutionPass(float partialTick, long gameTimeNanos, boolean renderLevel, CallbackInfo callbackInfo) {
        DynamicResolutionController.beginWorldRenderPass();
    }

    @Redirect(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V",
                    ordinal = 0
            )
    )
    private void pauc$finishDynamicResolutionPass(RenderTarget renderTarget, boolean setViewport) {
        DynamicResolutionController.endWorldRenderPass(renderTarget, setViewport);
    }

    @Inject(method = "render(FJZ)V", at = @At("RETURN"))
    private void pauc$restoreMainTargetAfterRender(float partialTick, long gameTimeNanos, boolean renderLevel, CallbackInfo callbackInfo) {
        DynamicResolutionController.failSafeRestore();
    }
}

