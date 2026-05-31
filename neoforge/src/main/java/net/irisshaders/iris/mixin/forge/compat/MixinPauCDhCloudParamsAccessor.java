package net.irisshaders.iris.mixin.forge.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler$CloudParams", remap = false)
public interface MixinPauCDhCloudParamsAccessor {
	@Accessor("instanceOffsetX")
	int pauc$getInstanceOffsetX();

	@Accessor("instanceOffsetZ")
	int pauc$getInstanceOffsetZ();
}
