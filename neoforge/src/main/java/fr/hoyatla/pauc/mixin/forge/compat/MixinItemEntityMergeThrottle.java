package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCItemEntityThrottler;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class MixinItemEntityMergeThrottle {
	@Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
	private void pauc$spreadMergeScans(CallbackInfo ci) {
		if (PauCItemEntityThrottler.shouldDeferMergeScan((ItemEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
