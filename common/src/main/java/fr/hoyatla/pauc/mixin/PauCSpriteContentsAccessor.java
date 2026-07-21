package fr.hoyatla.pauc.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * PauC-owned access to a sprite's original image — used by the LOD engine's block colour cache to
 * average texture pixels. Extracted from the vendored shader tree (Iris-removal P2) so the colour
 * cache has zero shader-mod dependency.
 */
@Mixin(SpriteContents.class)
public interface PauCSpriteContentsAccessor {
	@Accessor("originalImage")
	NativeImage getOriginalImage();
}
