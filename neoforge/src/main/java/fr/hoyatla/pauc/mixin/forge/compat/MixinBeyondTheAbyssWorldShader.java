package fr.hoyatla.pauc.mixin.forge.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "com.min01.beyondtheabyss.shader.BTAWorldShader", remap = false)
public abstract class MixinBeyondTheAbyssWorldShader {
	@Inject(
		method = "registerWorldShader(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Supplier;Lnet/minecraft/resources/ResourceKey;ZLjava/lang/String;)V",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private static void pauc$skipWorldShaderRegistration(
		ResourceKey<Level> world,
		Supplier<?> shader,
		@Nullable ResourceKey<Biome> biome,
		boolean is3DSampler,
		String samplerName,
		CallbackInfo ci
	) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER)) {
			return;
		}

		PauCCompatManager.logActionOnce(
			PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER,
			"world-shader-registration-disabled",
			"PauC disabled Beyond The Abyss world shader registration for Iris/Sodium compatibility."
		);
		ci.cancel();
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
	private void pauc$skipWorldShaderRender(PoseStack poseStack, float partialTick, Camera camera, CallbackInfo ci) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER)) {
			return;
		}

		PauCCompatManager.logActionOnce(
			PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER,
			"world-shader-render-blocked",
			"PauC blocked an already-registered Beyond The Abyss world shader render pass."
		);
		ci.cancel();
	}

	@Inject(method = "requestUpdate", at = @At("HEAD"), cancellable = true, remap = false)
	private void pauc$skipWorldShaderVolumeUpdate(BlockPos pos, ClientLevel level, CallbackInfo ci) {
		if (PauCCompatManager.isEnabled(PauCCompatModule.BEYOND_THE_ABYSS_WORLD_SHADER)) {
			ci.cancel();
		}
	}
}
