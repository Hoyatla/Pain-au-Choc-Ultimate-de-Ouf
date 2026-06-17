package net.irisshaders.batchedentityrendering.impl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.batchedentityrendering.mixin.RenderTypeAccessor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class SegmentedBufferBuilder implements MultiBufferSource, MemoryTrackingBuffer {
	private static final int MIN_INITIAL_BUFFER_BYTES = 16 * 1024;
	private final BufferBuilder buffer;
	private final List<BufferSegment> buffers;
	private RenderType currentType;

	public SegmentedBufferBuilder() {
		this(512 * 1024);
	}

	public SegmentedBufferBuilder(int initialCapacityBytes) {
		this.buffer = new BufferBuilder(Math.max(MIN_INITIAL_BUFFER_BYTES, initialCapacityBytes));
		this.buffers = new ArrayList<>(8);
		this.currentType = null;
	}

	private static boolean shouldSortOnUpload(RenderType type) {
		return ((RenderTypeAccessor) type).shouldSortOnUpload();
	}

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		if (!Objects.equals(currentType, renderType)) {
			finishCurrentType();

			buffer.begin(renderType.mode(), renderType.format());

			currentType = renderType;
		}

		// Use duplicate vertices to break up triangle strips
		// https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/Art/degenerate_triangle_strip_2x.png
		// This works by generating zero-area triangles that don't end up getting rendered.
		// TODO: How do we handle DEBUG_LINE_STRIP?
		if (RenderTypeUtil.isTriangleStripDrawMode(currentType)) {
			if (buffer instanceof BufferBuilderExt ext) {
				ext.splitStrip();
			}
		}

		return buffer;
	}

	private void finishCurrentType() {
		if (currentType == null) {
			return;
		}

		if (shouldSortOnUpload(currentType)) {
			buffer.setQuadSorting(RenderSystem.getVertexSorting());
		}

		buffers.add(new BufferSegment(Objects.requireNonNull(buffer.end()), currentType));
		currentType = null;
	}

	public List<BufferSegment> getSegments() {
		if (currentType == null && buffers.isEmpty()) {
			return Collections.emptyList();
		}

		finishCurrentType();

		List<BufferSegment> finalSegments = new ArrayList<>(buffers);
		buffers.clear();
		return finalSegments;
	}

	public List<BufferSegment> getSegmentsForType(TransparencyType transparencyType) {
		finishCurrentType();
		if (buffers.isEmpty()) {
			return Collections.emptyList();
		}

		List<BufferSegment> finalSegments = null;
		for (int i = 0; i < buffers.size(); ) {
			BufferSegment segment = buffers.get(i);
			if (((BlendingStateHolder) segment.type()).getTransparencyType() == transparencyType) {
				if (finalSegments == null) {
					finalSegments = new ArrayList<>();
				}
				finalSegments.add(segment);
				buffers.remove(i);
				continue;
			}
			i++;
		}
		return finalSegments == null ? Collections.emptyList() : finalSegments;
	}

	@Override
	public int getAllocatedSize() {
		if (buffer instanceof MemoryTrackingBuffer trackingBuffer) {
			return trackingBuffer.getAllocatedSize();
		}

		return 0;
	}

	@Override
	public int getUsedSize() {
		if (buffer instanceof MemoryTrackingBuffer trackingBuffer) {
			return trackingBuffer.getUsedSize();
		}

		return 0;
	}

	@Override
	public void freeAndDeleteBuffer() {
		buffers.clear();
		currentType = null;
		if (buffer instanceof MemoryTrackingBuffer trackingBuffer) {
			trackingBuffer.freeAndDeleteBuffer();
		}
	}
}
