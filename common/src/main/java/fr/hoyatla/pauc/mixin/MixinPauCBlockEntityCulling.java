package fr.hoyatla.pauc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.hoyatla.pauc.lod.PauCBlockEntityOcclusionCulling;
import fr.hoyatla.pauc.lod.PauCBlockEntityRenderBudget;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PauC block-entity culling and render budget (extracted from the vendored shader tree — pure PauC):
 * far cull, occlusion cull, spike-absorber deferral. Beam/off-screen renderers (beacons, gateways)
 * are exempt so they never flicker.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class MixinPauCBlockEntityCulling {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void pauc$cullFarBlockEntity(BlockEntity blockEntity, float tickDelta, PoseStack matrix,
										 MultiBufferSource bufferSource, CallbackInfo ci) {
		if (PauCLodRenderCulling.shouldCullBlockEntity(blockEntity)) {
			PauCVillagePerformanceDiagnostics.recordBlockEntityCull(blockEntity);
			ci.cancel();
			return;
		}
		boolean rendersOffScreen = pauc$rendersOffScreen(blockEntity);
		if (!rendersOffScreen && PauCBlockEntityOcclusionCulling.shouldCull(blockEntity)) {
			PauCVillagePerformanceDiagnostics.recordBlockEntityCull(blockEntity);
			ci.cancel();
			return;
		}
		if (PauCBlockEntityRenderBudget.shouldDeferRender(blockEntity) && !rendersOffScreen) {
			// Spike absorber: dephase tiny far block entities during a measured frame spike (no steady-state effect).
			ci.cancel();
			return;
		}
		PauCVillagePerformanceDiagnostics.recordBlockEntityRender(blockEntity);
	}

	@Unique
	@SuppressWarnings({"unchecked", "rawtypes"})
	private boolean pauc$rendersOffScreen(BlockEntity blockEntity) {
		try {
			BlockEntityRenderer renderer = ((BlockEntityRenderDispatcher) (Object) this).getRenderer(blockEntity);
			return renderer != null && renderer.shouldRenderOffScreen(blockEntity);
		} catch (RuntimeException ignored) {
			return true;
		}
	}
}
