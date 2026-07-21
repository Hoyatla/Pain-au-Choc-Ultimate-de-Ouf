package fr.hoyatla.pauc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.hoyatla.pauc.lod.PauCEntityOcclusionCulling;
import fr.hoyatla.pauc.lod.PauCEntityRenderBudget;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PauC entity culling and render budget (extracted from the vendored shader tree — pure PauC, no
 * shader-mod dependency): far-entity cull, occlusion cull behind opaque terrain, and the spike
 * absorber's animation-LOD deferral.
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinPauCEntityCulling {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void pauc$cullFarEntity(Entity entity, double x, double y, double z, float yaw, float tickDelta,
									PoseStack poseStack, MultiBufferSource bufferSource, int light,
									CallbackInfo ci) {
		if (PauCLodRenderCulling.shouldCullEntity(entity)) {
			PauCVillagePerformanceDiagnostics.recordEntityCull(entity);
			ci.cancel();
			return;
		}
		if (PauCEntityOcclusionCulling.shouldCull(entity)) {
			// Hidden behind opaque terrain (caves/walls): not visible, so skip render + animation entirely.
			PauCVillagePerformanceDiagnostics.recordEntityCull(entity);
			ci.cancel();
			return;
		}
		if (PauCEntityRenderBudget.shouldDeferEntityRender(entity)) {
			// Animation-LOD: dephase tiny far entities during a measured frame spike (no steady-state effect).
			ci.cancel();
			return;
		}
		PauCVillagePerformanceDiagnostics.recordEntityRender(entity);
	}
}
