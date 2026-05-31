package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler", remap = false)
public abstract class MixinPauCDhCloudRenderHandler {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 128))
	private int pauc$cloudCellWidth(int original) {
		return PauCLodRenderCulling.lodCloudCellWidthBlocks();
	}

	@ModifyConstant(method = "<init>", constant = @Constant(doubleValue = 32.0D))
	private double pauc$cloudThickness(double original) {
		return PauCLodRenderCulling.lodCloudThicknessBlocks();
	}

	@ModifyConstant(method = "preRender", constant = @Constant(floatValue = 6.0F))
	private float pauc$cloudSpeed(float original) {
		return PauCLodRenderCulling.lodCloudSpeedBlocksPerSecond();
	}

	@ModifyConstant(method = "preRender", constant = @Constant(intValue = 200))
	private int pauc$cloudHeightOffset(int original) {
		return PauCLodRenderCulling.lodCloudHeightOffsetFromWorldTopBlocks();
	}

	@Inject(method = "shouldCloudBeCulled", at = @At("HEAD"), cancellable = true)
	private void pauc$cullVanillaCoveredCloudInstances(float minPosX, float minPosY, float minPosZ,
													   @Coerce Object cloudParams,
													   CallbackInfoReturnable<Boolean> cir) {
		MixinPauCDhCloudParamsAccessor accessor = (MixinPauCDhCloudParamsAccessor) cloudParams;
		if (PauCLodRenderCulling.shouldCullLodCloudInstance(accessor.pauc$getInstanceOffsetX(), accessor.pauc$getInstanceOffsetZ())) {
			cir.setReturnValue(true);
		}
	}
}
