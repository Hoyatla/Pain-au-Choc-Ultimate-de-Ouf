package net.irisshaders.iris.mixin;

import net.irisshaders.iris.mixinterface.LocalPlayerInterface;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LocalPlayer.class)
public class MixinLocalPlayer implements LocalPlayerInterface {
	@Override
	public float getCurrentConstantMood() {
		// Robust path: avoid fragile shadowing, rely on vanilla LocalPlayer mood tracking.
		return ((LocalPlayer) (Object) this).getCurrentMood();
	}
}
