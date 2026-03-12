package pauc.pain_au_choc.mixin;

import pauc.pain_au_choc.PauCClient;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @ModifyArg(
            method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"
            ),
            index = 1
    )
    private ItemDisplayContext pauc$forceFlatGroundItems(ItemDisplayContext originalDisplayContext) {
        if (!PauCClient.isBudgetActive()) {
            return originalDisplayContext;
        }

        return ItemDisplayContext.GUI;
    }
}

