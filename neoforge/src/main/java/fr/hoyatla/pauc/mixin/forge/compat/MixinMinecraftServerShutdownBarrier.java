package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCShutdownBarrier;
import fr.hoyatla.pauc.platform.forge.compat.PauCShutdownTiming;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerShutdownBarrier {
	@Inject(method = "saveEverything", at = @At("HEAD"))
	private void pauc$markSaveEverythingStart(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
		PauCShutdownTiming.pushServerSaveStart();
		PauCShutdownBarrier.onSaveEverythingStart((MinecraftServer) (Object) this, suppressLog, flush, force);
	}

	@Inject(method = "saveEverything", at = @At("RETURN"))
	private void pauc$markSaveEverythingEnd(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
		Long startedAt = PauCShutdownTiming.popServerSaveStart();
		if (startedAt != null) {
			PauCShutdownBarrier.onSaveEverythingEnd((MinecraftServer) (Object) this, cir.getReturnValueZ(), System.currentTimeMillis() - startedAt);
		}
	}
}
