package net.irisshaders.iris.compat.sodium.mixin.shadow_map;

import fr.hoyatla.pauc.compat.PauCCompatibility;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.gl.arena.staging.StagingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderRegion.class)
public class MixinRenderRegion {
	@Unique
	private ChunkRenderList iris$shadowRenderList;

	@Inject(method = "<init>", at = @At("RETURN"), remap = false)
	private void iris$createShadowRenderList(int x, int y, int z, StagingBuffer stagingBuffer, CallbackInfo ci) {
		this.iris$shadowRenderList = new ChunkRenderList((RenderRegion) (Object) this);
	}

	@Inject(method = "getRenderList", at = @At("HEAD"), cancellable = true, remap = false)
	private void iris$useShadowRenderList(CallbackInfoReturnable<ChunkRenderList> cir) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) {
			cir.setReturnValue(this.iris$shadowRenderList);
		}
	}
}
