package pauc.pain_au_choc.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pauc.pain_au_choc.DynamicResolutionController;

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

    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void pauc$finishDynamicResolutionPass(float partialTick, long gameTimeNanos, boolean renderLevel, CallbackInfo callbackInfo) {
        DynamicResolutionController.endWorldRenderPass(Minecraft.getInstance().getMainRenderTarget(), true);
    }

    @Inject(method = "render(FJZ)V", at = @At("RETURN"))
    private void pauc$restoreMainTargetAfterRender(float partialTick, long gameTimeNanos, boolean renderLevel, CallbackInfo callbackInfo) {
        DynamicResolutionController.failSafeRestore();
    }
}

