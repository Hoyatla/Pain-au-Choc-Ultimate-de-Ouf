package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import pauc.pain_au_choc.RenderBudgetManager;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Shadow
    public Camera camera;

    @Inject(
            method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$budgetBlockEntities(BlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource multiBufferSource, CallbackInfo callbackInfo) {
        if (!RenderBudgetManager.shouldRenderBlockEntity(blockEntity, this.camera)) {
            callbackInfo.cancel();
        }
    }
}

