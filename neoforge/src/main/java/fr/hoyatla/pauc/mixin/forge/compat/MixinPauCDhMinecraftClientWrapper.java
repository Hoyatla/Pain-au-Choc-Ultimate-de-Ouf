package fr.hoyatla.pauc.mixin.forge.compat;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;

@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper_forge", remap = false)
public abstract class MixinPauCDhMinecraftClientWrapper {
	@Unique
	private static final Logger PAUC_DH_VANILLA_GRAPHICS_LOGGER = LogUtils.getLogger();
	@Unique
	private static boolean pauc$vanillaGraphicsOverrideLogged;

	@Inject(method = "disableVanillaClouds", at = @At("HEAD"), cancellable = true)
	private void pauc$keepVanillaClouds(CallbackInfo ci) {
		pauc$cancelDhVanillaGraphicsOverride("clouds", ci);
	}

	@Inject(method = "disableVanillaChunkFadeIn", at = @At("HEAD"), cancellable = true)
	private void pauc$keepVanillaChunkFade(CallbackInfo ci) {
		pauc$cancelDhVanillaGraphicsOverride("chunk fade", ci);
	}

	@Inject(method = "disableFabulousTransparency", at = @At("HEAD"), cancellable = true)
	private void pauc$keepFabulousTransparency(CallbackInfo ci) {
		pauc$cancelDhVanillaGraphicsOverride("fabulous transparency", ci);
	}

	@Unique
	private static void pauc$cancelDhVanillaGraphicsOverride(String target, CallbackInfo ci) {
		if (!PauCLodClientSettings.isLodsEnabled() && !PauCLodHorizonState.currentRange().enabled()) {
			return;
		}

		if (!pauc$vanillaGraphicsOverrideLogged) {
			pauc$vanillaGraphicsOverrideLogged = true;
			PAUC_DH_VANILLA_GRAPHICS_LOGGER.info("PauC blocked embedded DH from changing vanilla graphics settings before PauC LOD setup.");
		}
		PAUC_DH_VANILLA_GRAPHICS_LOGGER.debug("PauC blocked embedded DH vanilla graphics override for {}.", target);
		ci.cancel();
	}
}
