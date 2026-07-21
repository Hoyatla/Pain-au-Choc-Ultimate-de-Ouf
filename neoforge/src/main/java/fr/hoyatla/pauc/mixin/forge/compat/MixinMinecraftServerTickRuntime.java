package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhaseBudgetController;
import fr.hoyatla.pauc.platform.forge.runtime.PauCTickDebtController;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerTickRuntime {
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void pauc$beginServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		PauCTickDebtController.onServerTickStart(server);
		PauCServerPhaseBudgetController.onServerTickStart(server);
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void pauc$endServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		PauCTickDebtController.onServerTickEnd((MinecraftServer) (Object) this);
	}
}
