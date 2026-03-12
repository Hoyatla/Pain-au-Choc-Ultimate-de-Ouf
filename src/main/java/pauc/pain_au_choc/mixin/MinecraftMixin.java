package pauc.pain_au_choc.mixin;

import pauc.pain_au_choc.AdaptiveFrameCapController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    @Final
    public Options options;

    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void pauc$updateAdaptiveFrameCap(boolean renderLevel, CallbackInfo callbackInfo) {
        AdaptiveFrameCapController.onFrameStart();
    }

    @Inject(method = "getFramerateLimit()I", at = @At("RETURN"), cancellable = true)
    private void pauc$clampFramerateLimit(CallbackInfoReturnable<Integer> callbackInfo) {
        if (this.options == null) {
            return;
        }

        int configuredCap = this.options.framerateLimit().get();
        callbackInfo.setReturnValue(AdaptiveFrameCapController.clampVanillaFrameLimit(callbackInfo.getReturnValueI(), configuredCap));
    }
}

