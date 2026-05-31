package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCRuntimeSwitches;
import fr.hoyatla.pauc.platform.forge.runtime.PauCStructureCheckCircuitBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevelStructureCircuitBreaker {
	@Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
	private void pauc$guardStructureSearch(
		TagKey<Structure> structureTag,
		BlockPos origin,
		int radius,
		boolean skipKnownStructures,
		CallbackInfoReturnable<BlockPos> cir
	) {
		if (!PauCRuntimeSwitches.enabled("structureBreaker.enabled", true)) {
			return;
		}

		ServerLevel level = (ServerLevel) (Object) this;
		if (!PauCStructureCheckCircuitBreaker.shouldAllow(level, origin, radius)) {
			cir.setReturnValue(null);
		}
	}
}
