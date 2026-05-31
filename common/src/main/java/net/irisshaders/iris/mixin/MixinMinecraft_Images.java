package net.irisshaders.iris.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.targets.backed.NativeImageBackedCustomTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

/**
 * Registers the PauC shader GUI widget texture during client startup.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_Images {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void iris$setupImages(GameConfig arg, CallbackInfo ci) {
		try {
			Minecraft.getInstance().getTextureManager().register(new ResourceLocation("iris", "textures/gui/widgets.png"), new NativeImageBackedCustomTexture(new CustomTextureData.PngData(new TextureFilteringData(false, false), IOUtils.toByteArray(Iris.class.getResourceAsStream("/assets/iris/textures/gui/widgets.png")))));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
