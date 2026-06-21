package net.irisshaders.iris.mixin.gui;

import fr.hoyatla.pauc.lod.PauCLodVideoSettings;
import net.irisshaders.iris.gui.element.EmbeddedShaderPackWidget;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen extends Screen {
	@Unique
	private static final int PAUC_SHADER_PANEL_GAP = 6;
	@Unique
	private static final int PAUC_DONE_BUTTON_SPACE = 27;

	@Shadow
	private OptionsList list;

	@Unique
	private EmbeddedShaderPackWidget pauc$shaderBrowserWidget;
	@Unique
	private boolean pauc$optionsInjected;

	protected MixinVideoSettingsScreen(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
	private void pauc$resetVideoSettingsInjectionState(CallbackInfo ci) {
		this.pauc$optionsInjected = false;
	}

	@ModifyArg(
		method = "init",
		at = @org.spongepowered.asm.mixin.injection.At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V"
		),
		index = 0
	)
	private OptionInstance<?>[] iris$addShaderPackScreenButton(OptionInstance<?>[] $$0) {
		if (this.pauc$optionsInjected) {
			return $$0;
		}
		this.pauc$optionsInjected = true;

		OptionInstance<?>[] paucOptions = new OptionInstance<?>[] {
			IrisVideoSettings.RENDER_DISTANCE,
			PauCLodVideoSettings.VANILLA_FOG,
			PauCLodVideoSettings.SHADERS_ENABLED,
			PauCLodVideoSettings.LOD_RENDER_DISTANCE,
			PauCLodVideoSettings.NVIDIA_ACCELERATION,
			PauCLodVideoSettings.TERRAIN_MORPHING,
			PauCLodVideoSettings.LOD_CLOUDS,
			PauCLodVideoSettings.DYNAMIC_RESOLUTION
		};
		OptionInstance<?>[] options = new OptionInstance[$$0.length + paucOptions.length];
		System.arraycopy($$0, 0, options, 0, $$0.length);
		System.arraycopy(paucOptions, 0, options, $$0.length, paucOptions.length);
		return options;
	}

	@Inject(method = "init", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
	private void pauc$addShaderQuickAccess(CallbackInfo ci) {
		PauCLodVideoSettings.syncFromClientSettings();

		if (this.pauc$shaderBrowserWidget != null) {
			this.removeWidget(this.pauc$shaderBrowserWidget);
		}

		int panelWidth = Math.min(520, this.width - 32);
		int panelX = (this.width - panelWidth) / 2;
		int panelY = this.height - PAUC_DONE_BUTTON_SPACE - PAUC_SHADER_PANEL_GAP - EmbeddedShaderPackWidget.HEIGHT;
		int listBottom = panelY - PAUC_SHADER_PANEL_GAP;

		this.list.updateSize(this.width, this.height, 32, listBottom);
		this.pauc$shaderBrowserWidget = this.addRenderableWidget(
			new EmbeddedShaderPackWidget((Screen) (Object) this, panelX, panelY, panelWidth, PauCLodVideoSettings::syncFromClientSettings)
		);
	}
}
