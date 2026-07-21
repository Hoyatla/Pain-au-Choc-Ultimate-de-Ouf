package fr.hoyatla.pauc.mixin.forge.compat;

import com.seibel.distanthorizons.core.util.RenderUtil;
import fr.hoyatla.pauc.lod.PauCLodNearClipOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderUtil.class, remap = false)
public abstract class MixinPauCDhRenderUtil {
	@Inject(method = "getNearClipPlaneInBlocks", at = @At("RETURN"), cancellable = true)
	private static void pauc$overrideNearClipForLateFallback(CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(PauCLodNearClipOverride.overrideGlobalRenderUtilNearClipBlocks(cir.getReturnValue()));
	}
}
