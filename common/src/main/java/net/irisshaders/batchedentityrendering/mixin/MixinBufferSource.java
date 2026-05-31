package net.irisshaders.batchedentityrendering.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.irisshaders.batchedentityrendering.impl.MemoryTrackingBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@Mixin(MultiBufferSource.BufferSource.class)
public class MixinBufferSource implements MemoryTrackingBuffer {
	@Unique
	private BufferBuilder pauC$getBuilder() {
		return ((BufferSourceAccessor) (Object) this).getBuilder();
	}

	@Unique
	private Map<RenderType, BufferBuilder> pauC$getFixedBuffers() {
		return ((BufferSourceAccessor) (Object) this).getFixedBuffers();
	}

	@Override
	public int getAllocatedSize() {
		int allocatedSize = 0;
		BufferBuilder builder = pauC$getBuilder();
		Map<RenderType, BufferBuilder> fixedBuffers = pauC$getFixedBuffers();
		if (builder instanceof MemoryTrackingBuffer trackingBuilder) {
			allocatedSize += trackingBuilder.getAllocatedSize();
		}

		for (BufferBuilder bufferBuilder : fixedBuffers.values()) {
			if (bufferBuilder instanceof MemoryTrackingBuffer trackingBuilder) {
				allocatedSize += trackingBuilder.getAllocatedSize();
			}
		}

		return allocatedSize;
	}

	@Override
	public int getUsedSize() {
		int allocatedSize = 0;
		BufferBuilder builder = pauC$getBuilder();
		Map<RenderType, BufferBuilder> fixedBuffers = pauC$getFixedBuffers();
		if (builder instanceof MemoryTrackingBuffer trackingBuilder) {
			allocatedSize += trackingBuilder.getUsedSize();
		}

		for (BufferBuilder bufferBuilder : fixedBuffers.values()) {
			if (bufferBuilder instanceof MemoryTrackingBuffer trackingBuilder) {
				allocatedSize += trackingBuilder.getUsedSize();
			}
		}

		return allocatedSize;
	}

	@Override
	public void freeAndDeleteBuffer() {
		BufferBuilder builder = pauC$getBuilder();
		Map<RenderType, BufferBuilder> fixedBuffers = pauC$getFixedBuffers();
		if (builder instanceof MemoryTrackingBuffer trackingBuilder) {
			trackingBuilder.freeAndDeleteBuffer();
		}

		for (BufferBuilder bufferBuilder : fixedBuffers.values()) {
			if (bufferBuilder instanceof MemoryTrackingBuffer trackingBuilder) {
				trackingBuilder.freeAndDeleteBuffer();
			}
		}
	}
}
