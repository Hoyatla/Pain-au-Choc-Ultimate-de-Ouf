package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.jelly.sandworm_mod.vfx.SonicBoomPostProcessor", remap = false)
public abstract class MixinSandwormSonicBoomPostProcessor {
	private static final ResourceLocation PAUC_SAFE_SONIC_BOOM = new ResourceLocation("paucultimate", "sandworm_sonic_boom_safe");

	@Inject(method = "getPostChainLocation", at = @At("HEAD"), cancellable = true, remap = false)
	private void pauc$useSafeSonicBoomPostChain(CallbackInfoReturnable<ResourceLocation> cir) {
		if (PauCCompatManager.isEnabled(PauCCompatModule.SANDWORM_SONIC_BOOM)) {
			cir.setReturnValue(PAUC_SAFE_SONIC_BOOM);
		}
	}
}
