package net.irisshaders.iris.mixin.sky;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables vanilla's dark lower sky disc so the horizon remains driven by the normal sky/fog pass instead
 * of a separate blue void cap.
 * <p>
 * Inspired by <a href="https://github.com/CaffeineMC/sodium-fabric/pull/710">this Sodium PR</a>, but this implementation
 * only changes the horizon height queried by LevelRenderer's void-plane branch.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public class MixinClientLevelData_DisableVoidPlane {
	@Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true)
	private void iris$getHorizonHeight(CallbackInfoReturnable<Double> cir) {
		cir.setReturnValue(Double.NEGATIVE_INFINITY);
	}
}
