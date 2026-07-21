package net.irisshaders.iris.compat.sodium.mixin.pauc;

import fr.hoyatla.pauc.lod.PauCSquareRenderDistance;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the vanilla chunk render footprint a SQUARE while a LOD field surrounds the player, so it lines
 * up with the LOD engine's square grid. Sodium's {@code isWithinRenderDistance} uses a Euclidean
 * horizontal check ({@code dx*dx + dz*dz < d*d}) = a cylinder; we swap it for a Chebyshev check
 * ({@code |dx| < d && |dz| < d}) = a square, keeping Sodium's original vertical bound ({@code |dy| < d}).
 * When the LOD field is not active the original method runs unchanged.
 */
@Mixin(OcclusionCuller.class)
public class MixinOcclusionCuller {
	@Inject(method = "isWithinRenderDistance", at = @At("HEAD"), cancellable = true, remap = false)
	private static void pauc$squareRenderDistance(CameraTransform camera, RenderSection section, float distance, CallbackInfoReturnable<Boolean> cir) {
		if (!PauCSquareRenderDistance.isActive()) {
			return;
		}
		int rx = section.getOriginX() - camera.intX;
		int ry = section.getOriginY() - camera.intY;
		int rz = section.getOriginZ() - camera.intZ;
		float dx = pauc$nearestToZero(rx, rx + 16) - camera.fracX;
		float dy = pauc$nearestToZero(ry, ry + 16) - camera.fracY;
		float dz = pauc$nearestToZero(rz, rz + 16) - camera.fracZ;
		boolean visible = Math.abs(dx) < distance && Math.abs(dz) < distance && Math.abs(dy) < distance;
		cir.setReturnValue(visible);
	}

	@Unique
	private static int pauc$nearestToZero(int min, int max) {
		int result = 0;
		if (min > 0) {
			result = min;
		}
		if (max < 0) {
			result = max;
		}
		return result;
	}
}
