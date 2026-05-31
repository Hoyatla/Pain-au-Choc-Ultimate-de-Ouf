package net.irisshaders.iris.mixin.forge.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_forge;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.renderer.LightTexture;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public abstract class MixinPauCDhLightTexture {
	@Unique
	private static final Logger PAUC_DH_LIGHTMAP_LOGGER = LogUtils.getLogger();
	@Unique
	private static boolean pauc$dhLightmapUploadedLogged;
	@Unique
	private static boolean pauc$dhLightmapWaitingLogged;
	@Unique
	private static boolean pauc$dhLightmapFailureLogged;

	@Shadow
	@Final
	private NativeImage lightPixels;

	@Inject(method = "updateLightTexture(F)V", at = @At("RETURN"))
	private void pauc$uploadLightmapForEmbeddedDh(float partialTicks, CallbackInfo ci) {
		try {
			IMinecraftClientWrapper minecraft = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
			IClientLevelWrapper clientLevel = minecraft != null ? minecraft.getWrappedClientLevel() : null;
			if (clientLevel == null) {
				if (!pauc$dhLightmapWaitingLogged) {
					pauc$dhLightmapWaitingLogged = true;
					PAUC_DH_LIGHTMAP_LOGGER.info("PauC embedded DH lightmap bridge is waiting for a wrapped client level.");
				}
				return;
			}

			MinecraftRenderWrapper_forge.INSTANCE.updateLightmap(this.lightPixels, clientLevel);
			if (!pauc$dhLightmapUploadedLogged) {
				pauc$dhLightmapUploadedLogged = true;
				PAUC_DH_LIGHTMAP_LOGGER.info("PauC embedded DH lightmap bridge uploaded Minecraft lightmap for {}.", clientLevel.getDhIdentifier());
			}
		} catch (Exception | Error error) {
			if (!pauc$dhLightmapFailureLogged) {
				pauc$dhLightmapFailureLogged = true;
				PAUC_DH_LIGHTMAP_LOGGER.warn("PauC embedded DH lightmap bridge failed to upload Minecraft lightmap.", error);
			}
		}
	}
}
