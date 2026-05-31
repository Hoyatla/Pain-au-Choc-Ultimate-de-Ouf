package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler$CloudParams", remap = false)
public abstract class MixinPauCDhCloudParams {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 128))
	private int pauc$cloudCellWidth(int original) {
		return PauCLodRenderCulling.lodCloudCellWidthBlocks();
	}
}
