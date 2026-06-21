package net.irisshaders.iris.mixin;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
	@Accessor("chunks")
	ChunkRenderDispatcher.RenderChunk[] getChunks();
}
