package fr.hoyatla.pauc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.hoyatla.pauc.lodengine.PauCCloudLodRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the PauC LOD engine owns the cloud layer, VANILLA clouds are cancelled entirely: PauC draws ONE
 * coherent cloud layer from the player out to the LOD horizon (same cells, same drift, same colours).
 * Patching a PauC ring around the vanilla ±384-block cloud mesh never lined up — two separately-built
 * layers always betray a seam, which read as "these are not Minecraft's clouds".
 */
@Mixin(LevelRenderer.class)
public class MixinPauCLodClouds {
	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void pauc$replaceVanillaClouds(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
			double camX, double camY, double camZ, CallbackInfo ci) {
		if (PauCCloudLodRenderer.ownsClouds()) {
			ci.cancel();
		}
	}
}
