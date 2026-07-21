package fr.hoyatla.pauc.mixin.forge.compat;

import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader;
import fr.hoyatla.pauc.lod.PauCLodFallbackVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GlShader.class, remap = false)
public abstract class MixinPauCDhGlShader {
	@Inject(method = "loadFile", at = @At("RETURN"), cancellable = true)
	private static void pauc$patchFallbackLodShader(String path, boolean absoluteFilePath, CallbackInfoReturnable<String> cir) {
		if (!absoluteFilePath) {
			cir.setReturnValue(PauCLodFallbackVisuals.patchDhShaderSource(path, cir.getReturnValue()));
		}
	}
}
