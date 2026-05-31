package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCMobAiThrottler;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MixinMobAiThrottler {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void pauc$throttleFarMobAi(CallbackInfo ci) {
		if (PauCMobAiThrottler.shouldSkipServerAiStep((Mob) (Object) this)) {
			ci.cancel();
		}
	}
}
