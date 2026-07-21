package fr.hoyatla.pauc.mixin;

import fr.hoyatla.pauc.lodengine.PauCSurfaceWitnessRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends {@code GameRenderer.getDepthFar()} to cover the PauC LOD engine radius when a LOD field is
 * active. Vanilla far plane = {@code renderDistance * 4} = 640 blocks at RD10; the LOD engine renders
 * up to 96 chunks (1536 blocks) by default, so anything beyond 640 gets clipped without this.
 *
 * <p>Injection is at RETURN so it composes safely with any other mixin that modifies the same method.
 * When no LOD data is present the injection does nothing (returns 0 and the {@code max} keeps
 * the vanilla value).</p>
 */
@Mixin(GameRenderer.class)
public class MixinPauCLodFarPlane {

	@Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
	private void pauc$extendForLodEngine(CallbackInfoReturnable<Float> cir) {
		float lodFar = PauCSurfaceWitnessRenderer.requiredFarPlaneBlocks();
		if (lodFar > cir.getReturnValue()) {
			cir.setReturnValue(lodFar);
		}
	}
}
