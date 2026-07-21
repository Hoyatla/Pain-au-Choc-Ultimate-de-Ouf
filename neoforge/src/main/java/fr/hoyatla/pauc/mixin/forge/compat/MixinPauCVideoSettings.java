package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.client.PauCClientSettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PauC product polish: the player-facing quality toggles live directly in the vanilla VIDEO SETTINGS
 * screen (no separate config mod, no JVM flags) - terrain relief shading and biome colour blending.
 * Values persist via {@link PauCClientSettings}; the LOD mesh re-reads them on its next rebuild.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class MixinPauCVideoSettings {
	@Shadow
	private OptionsList list;

	@Inject(method = "init", at = @At("TAIL"))
	private void pauc$addToggles(CallbackInfo ci) {
		if (this.list == null) {
			return;
		}
		OptionInstance<Boolean> shading = OptionInstance.createBoolean(
			"paucultimate.options.terrainShading",
			PauCClientSettings.readBoolean("pauc.client.terrainShading", true),
			value -> PauCClientSettings.setBoolean("pauc.client.terrainShading", value));
		OptionInstance<Boolean> biomeBlend = OptionInstance.createBoolean(
			"paucultimate.options.biomeBlend",
			PauCClientSettings.readBoolean("pauc.client.biomeBlend", false),
			value -> PauCClientSettings.setBoolean("pauc.client.biomeBlend", value));
		OptionInstance<Boolean> fog = OptionInstance.createBoolean(
			"paucultimate.options.fog",
			PauCClientSettings.readBoolean("pauc.client.fog", true),
			value -> PauCClientSettings.setBoolean("pauc.client.fog", value));
		this.list.addSmall(new OptionInstance[] { shading, biomeBlend, fog });
	}
}
