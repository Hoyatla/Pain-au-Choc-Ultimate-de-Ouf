package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import pauc.pain_au_choc.RenderBudgetManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(
            method = "renderShadow",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void pauc$budgetEntityShadows(PoseStack poseStack, MultiBufferSource multiBufferSource, Entity entity, float strength, float partialTick, LevelReader levelReader, float radius, CallbackInfo callbackInfo) {
        if (!RenderBudgetManager.shouldRenderEntityShadow(entity)) {
            callbackInfo.cancel();
        }
    }
}

