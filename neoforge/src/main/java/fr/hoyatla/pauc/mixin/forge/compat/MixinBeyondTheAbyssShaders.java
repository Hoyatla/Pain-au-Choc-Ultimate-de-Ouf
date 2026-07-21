package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.min01.beyondtheabyss.shader.BTAShaders", remap = false)
public abstract class MixinBeyondTheAbyssShaders {
	@Inject(method = "init", at = @At("HEAD"), cancellable = true, remap = false)
	private static void pauc$skipDepthPostChains(ResourceManager resourceManager, CallbackInfo ci) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER)) {
			return;
		}

		PauCCompatManager.logActionOnce(
			PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER,
			"depth-post-chains-disabled",
			"PauC disabled Beyond The Abyss depth post-chain shaders for Iris/Sodium compatibility."
		);
		ci.cancel();
	}
}
