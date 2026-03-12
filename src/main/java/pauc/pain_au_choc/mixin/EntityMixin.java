package pauc.pain_au_choc.mixin;

import pauc.pain_au_choc.RenderBudgetManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "isPushable()Z", at = @At("HEAD"), cancellable = true)
    private void pauc$disableFarPushCollision(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (RenderBudgetManager.shouldDisableClientCollision((Entity) (Object) this)) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "canBeCollidedWith()Z", at = @At("HEAD"), cancellable = true)
    private void pauc$disableFarRayCollision(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (RenderBudgetManager.shouldDisableClientCollision((Entity) (Object) this)) {
            callbackInfo.setReturnValue(false);
        }
    }
}

