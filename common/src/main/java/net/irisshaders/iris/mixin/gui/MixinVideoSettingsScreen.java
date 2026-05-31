package net.irisshaders.iris.mixin.gui;

import fr.hoyatla.pauc.lod.PauCLodVideoSettings;
import net.irisshaders.iris.gui.element.ShaderPackQuickAccessWidget;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen extends Screen {
	protected MixinVideoSettingsScreen(Component title) {
		super(title);
	}

	@ModifyArg(
		method = "init",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/OptionsList;<init>(Lnet/minecraft/client/Minecraft;IIIII)V"
		),
		index = 4
	)
	private int iris$reserveShaderPackQuickAccessSpace(int bottom) {
		return Math.max(32, bottom - ShaderPackQuickAccessWidget.RESERVED_HEIGHT);
	}

	@ModifyArg(
		method = "init",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V"
		),
		index = 0
	)
	private OptionInstance<?>[] iris$addShaderPackScreenButton(OptionInstance<?>[] $$0) {
		OptionInstance<?>[] options = new OptionInstance[$$0.length + 4];
		System.arraycopy($$0, 0, options, 0, $$0.length);
		options[$$0.length] = IrisVideoSettings.RENDER_DISTANCE;
		options[$$0.length + 1] = PauCLodVideoSettings.LODS_ENABLED;
		options[$$0.length + 2] = PauCLodVideoSettings.LOD_RENDER_DISTANCE;
		options[$$0.length + 3] = PauCLodVideoSettings.LOD_CLOUDS;
		return options;
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void iris$addShaderPackQuickAccess(CallbackInfo ci) {
		int width = Math.min(400, this.width - 24);
		int x = (this.width - width) / 2;
		int y = this.height - 86;
		this.addRenderableWidget(new ShaderPackQuickAccessWidget(this, x, y, width));
	}
}
