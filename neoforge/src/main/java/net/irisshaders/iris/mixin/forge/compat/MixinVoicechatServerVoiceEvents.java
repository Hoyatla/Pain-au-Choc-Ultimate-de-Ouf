package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.server.ServerVoiceEvents", remap = false)
public abstract class MixinVoicechatServerVoiceEvents {
	@Inject(method = "initializePlayerConnection", at = @At("HEAD"), cancellable = true, remap = false)
	private void pauc$blockLateReconnectDuringShutdown(ServerPlayer player, CallbackInfo ci) {
		if (!PauCCompatManager.shouldBlockVoicechatInitialization(player)) {
			return;
		}

		PauCCompatManager.logBlockedVoicechatInitialization(player);
		ci.cancel();
	}
}
