package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.jelly.sandworm_mod.event.ClientEvents$ModClientEvents", remap = false)
public abstract class MixinSandwormClientEvents {
	@Redirect(
		method = "onClientSetup",
		at = @At(
			value = "INVOKE",
			target = "Lteam/lodestar/lodestone/systems/postprocess/PostProcessHandler;addInstance(Lteam/lodestar/lodestone/systems/postprocess/PostProcessor;)V"
		),
		remap = false
	)
	private static void pauc$skipSonicBoomPostProcessor(@Coerce Object processor) {
		if (PauCCompatManager.isEnabled(PauCCompatModule.SANDWORM_SONIC_BOOM)) {
			return;
		}

		pauc$registerOriginalPostProcessor(processor);
	}

	private static void pauc$registerOriginalPostProcessor(Object processor) {
		try {
			Class<?> postProcessorType = Class.forName("team.lodestar.lodestone.systems.postprocess.PostProcessor");
			Class<?> handlerType = Class.forName("team.lodestar.lodestone.systems.postprocess.PostProcessHandler");
			handlerType.getMethod("addInstance", postProcessorType).invoke(null, processor);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not restore Sandworm sonic boom post processor", exception);
		}
	}
}
