package net.irisshaders.iris.mixin.vertices.block_rendering;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps chunk-local buffer context clean around vanilla rebuilds.
 * Block/fluid identity is attached closer to tessellation, where the render arguments are stable.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask", priority = 999)
public class MixinChunkRebuildTask {
	@Unique
	private static void pauc$endBlock(BufferBuilder bufferBuilder) {
		if (bufferBuilder instanceof BlockSensitiveBufferBuilder blockSensitiveBufferBuilder) {
			blockSensitiveBufferBuilder.endBlock();
		}
	}

	@Inject(method = "m_234467_", at = @At("HEAD"), remap = false, require = 0)
	private void pauc$resetContextAtStart(
		float x, float y, float z, ChunkBufferBuilderPack bufferPack,
		CallbackInfoReturnable<ChunkRenderDispatcher.RenderChunk.RebuildTask.CompileResults> cir
	) {
		for (RenderType renderType : RenderType.chunkBufferLayers()) {
			pauc$endBlock(bufferPack.builder(renderType));
		}
	}

	@Inject(method = "m_234467_", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$resetContextAtEnd(
		float x, float y, float z, ChunkBufferBuilderPack bufferPack,
		CallbackInfoReturnable<ChunkRenderDispatcher.RenderChunk.RebuildTask.CompileResults> cir
	) {
		for (RenderType renderType : RenderType.chunkBufferLayers()) {
			pauc$endBlock(bufferPack.builder(renderType));
		}
	}
}
