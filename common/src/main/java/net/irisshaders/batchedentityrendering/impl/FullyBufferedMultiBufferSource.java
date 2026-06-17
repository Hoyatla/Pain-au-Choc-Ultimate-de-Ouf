package net.irisshaders.batchedentityrendering.impl;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager;
import net.irisshaders.batchedentityrendering.impl.ordering.RenderOrderManager;
import net.irisshaders.iris.layer.WrappingMultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.profiling.ProfilerFiller;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class FullyBufferedMultiBufferSource extends MultiBufferSource.BufferSource implements MemoryTrackingBuffer, Groupable, WrappingMultiBufferSource {
	private static final int NUM_BUFFERS = 32;
	private static final int MIN_INITIAL_BUFFER_BYTES = 64 * 1024;
	private static final int MAX_INITIAL_BUFFER_BYTES = 512 * 1024;
	private static final int IDLE_TRIM_FRAMES = 15;
	private static final int HARD_IDLE_TRIM_FRAMES = 90;
	private static final int IDLE_TRIM_MIN_BYTES = 384 * 1024;
	private static final String[] DELEGATE_METHOD_NAMES = {"sodium$getDelegate", "paucor$getDelegate"};

	private final RenderOrderManager renderOrderManager;
	private final SegmentedBufferBuilder[] builders;
	private final boolean[] slotTouchedThisFrame;
	private final int[] slotIdleFrames;
	/**
	 * An LRU cache mapping RenderType objects to a relevant buffer.
	 */
	private final LinkedHashMap<RenderType, Integer> affinities;
	private final BufferSegmentRenderer segmentRenderer;
	private final UnflushableWrapper unflushableWrapper;
	private final List<Function<RenderType, RenderType>> wrappingFunctionStack;
	private final Map<RenderType, List<BufferSegment>> typeToSegment = new HashMap<>();
	private int drawCalls;
	private int renderTypes;
	private Function<RenderType, RenderType> wrappingFunction = null;
	private boolean isReady;
	private List<RenderType> renderOrder = new ArrayList<>();
	private final Class<?> vertexBufferExtensionClass;
	private final Method delegateMethod;

	public FullyBufferedMultiBufferSource() {
		super(new BufferBuilder(0), Collections.emptyMap());

		this.renderOrderManager = new GraphTranslucencyRenderOrderManager();
		this.builders = new SegmentedBufferBuilder[NUM_BUFFERS];
		this.slotTouchedThisFrame = new boolean[NUM_BUFFERS];
		this.slotIdleFrames = new int[NUM_BUFFERS];

		// use accessOrder=true so our LinkedHashMap works as an LRU cache.
		this.affinities = new LinkedHashMap<>(32, 0.75F, true);

		this.drawCalls = 0;
		this.segmentRenderer = new BufferSegmentRenderer();
		this.unflushableWrapper = new UnflushableWrapper(this);
		this.wrappingFunctionStack = new ArrayList<>();

		Class<?> extensionClass = null;
		Method resolvedDelegateMethod = null;
		for (String className : new String[]{
			"net.caffeinemc.mods.sodium.client.render.vertex.buffer.BufferBuilderExtension",
			"net.caffeinemc.mods.paucor.client.render.vertex.buffer.BufferBuilderExtension"
		}) {
			try {
				Class<?> candidate = Class.forName(className);
				Method delegate = pauc$findDelegateMethod(candidate);
				if (delegate != null) {
					extensionClass = candidate;
					resolvedDelegateMethod = delegate;
					break;
				}
			} catch (ClassNotFoundException ignored) {
			}
		}

		this.vertexBufferExtensionClass = extensionClass;
		this.delegateMethod = resolvedDelegateMethod;
	}

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		removeReady();

		if (wrappingFunction != null) {
			renderType = wrappingFunction.apply(renderType);
		}

		renderOrderManager.begin(renderType);
		Integer affinity = affinities.get(renderType);

		if (affinity == null) {
			if (affinities.size() < builders.length) {
				affinity = affinities.size();
			} else {
				// We remove the element from the map that is used least-frequently.
				// With how we've configured our LinkedHashMap, that is the first element.
				Iterator<Map.Entry<RenderType, Integer>> iterator = affinities.entrySet().iterator();
				Map.Entry<RenderType, Integer> evicted = iterator.next();
				iterator.remove();

				// The previous type is no longer associated with this buffer ...
				affinities.remove(evicted.getKey());

				// ... since our new type is now associated with it.
				affinity = evicted.getValue();
			}

			affinities.put(renderType, affinity);
		}

		SegmentedBufferBuilder builder = builders[affinity];
		if (builder == null) {
			builder = new SegmentedBufferBuilder(initialBufferBytes(renderType));
			builders[affinity] = builder;
		}
		slotTouchedThisFrame[affinity] = true;
		VertexConsumer buffer = builder.getBuffer(renderType);

		if (vertexBufferExtensionClass != null && delegateMethod != null && vertexBufferExtensionClass.isInstance(buffer)) {
			try {
				Object replacement = delegateMethod.invoke(buffer);
				if (replacement instanceof VertexConsumer vertexConsumer) {
					return vertexConsumer;
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}

		return buffer;
	}

	private static int initialBufferBytes(RenderType renderType) {
		return Math.max(MIN_INITIAL_BUFFER_BYTES, Math.min(MAX_INITIAL_BUFFER_BYTES, renderType.bufferSize()));
	}

	private static Method pauc$findDelegateMethod(Class<?> extensionClass) {
		for (String methodName : DELEGATE_METHOD_NAMES) {
			try {
				return extensionClass.getMethod(methodName);
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private void removeReady() {
		isReady = false;
		typeToSegment.clear();
		renderOrder.clear();
	}

	public void readyUp() {
		isReady = true;

		ProfilerFiller profiler = Minecraft.getInstance().getProfiler();

		profiler.push("collect");

		for (SegmentedBufferBuilder builder : builders) {
			if (builder == null) {
				continue;
			}
			List<BufferSegment> segments = builder.getSegments();

			for (BufferSegment segment : segments) {
				typeToSegment.computeIfAbsent(segment.type(), (type) -> new ArrayList<>()).add(segment);
			}
		}

		profiler.popPush("resolve ordering");

		renderOrder = renderOrderManager.getRenderOrder();

		renderOrderManager.reset();
		affinities.clear();

		profiler.pop();
	}

	@Override
	public void endBatch() {
		ProfilerFiller profiler = Minecraft.getInstance().getProfiler();

		if (!isReady) readyUp();

		profiler.push("draw buffers");

		for (RenderType type : renderOrder) {
			type.setupRenderState();

			renderTypes += 1;

			for (BufferSegment segment : typeToSegment.getOrDefault(type, Collections.emptyList())) {
				segmentRenderer.drawInner(segment);
				drawCalls += 1;
			}

			type.clearRenderState();
		}

		profiler.popPush("reset");

		removeReady();

		profiler.pop();
	}

	public void endBatchWithType(TransparencyType transparencyType) {
		ProfilerFiller profiler = Minecraft.getInstance().getProfiler();

		if (!isReady) readyUp();

		profiler.push("draw buffers");

		List<RenderType> types = new ArrayList<>();

		for (RenderType type : renderOrder) {
			if (((BlendingStateHolder) type).getTransparencyType() != transparencyType) {
				continue;
			}

			types.add(type);

			type.setupRenderState();

			renderTypes += 1;

			for (BufferSegment segment : typeToSegment.getOrDefault(type, Collections.emptyList())) {
				segmentRenderer.drawInner(segment);
				drawCalls += 1;
			}

			typeToSegment.remove(type);

			type.clearRenderState();
		}

		profiler.popPush("reset type " + transparencyType);

		renderOrder.removeAll(types);

		profiler.pop();
	}

	public int getDrawCalls() {
		return drawCalls;
	}

	public int getRenderTypes() {
		return renderTypes;
	}

	public void resetDrawCalls() {
		drawCalls = 0;
		renderTypes = 0;
	}

	@Override
	public void endBatch(RenderType type) {
		// Disable explicit flushing
	}

	public MultiBufferSource.BufferSource getUnflushableWrapper() {
		return unflushableWrapper;
	}

	@Override
	public int getAllocatedSize() {
		int size = 0;

		for (SegmentedBufferBuilder builder : builders) {
			if (builder != null) {
				size += builder.getAllocatedSize();
			}
		}

		return size;
	}

	@Override
	public int getUsedSize() {
		int size = 0;

		for (SegmentedBufferBuilder builder : builders) {
			if (builder != null) {
				size += builder.getUsedSize();
			}
		}

		return size;
	}

	@Override
	public void freeAndDeleteBuffer() {
		for (int i = 0; i < builders.length; i++) {
			SegmentedBufferBuilder builder = builders[i];
			if (builder != null) {
				builder.freeAndDeleteBuffer();
				builders[i] = null;
			}
			slotTouchedThisFrame[i] = false;
			slotIdleFrames[i] = 0;
		}
	}

	public void onFrameComplete() {
		for (int i = 0; i < builders.length; i++) {
			SegmentedBufferBuilder builder = builders[i];
			if (slotTouchedThisFrame[i]) {
				slotTouchedThisFrame[i] = false;
				slotIdleFrames[i] = 0;
				continue;
			}

			slotTouchedThisFrame[i] = false;
			if (builder == null) {
				slotIdleFrames[i] = 0;
				continue;
			}

			int idleFrames = ++slotIdleFrames[i];
			int allocatedBytes = builder.getAllocatedSize();
			if (idleFrames >= HARD_IDLE_TRIM_FRAMES || (idleFrames >= IDLE_TRIM_FRAMES && allocatedBytes >= IDLE_TRIM_MIN_BYTES)) {
				builder.freeAndDeleteBuffer();
				builders[i] = null;
				slotIdleFrames[i] = 0;
			}
		}
	}

	@Override
	public void startGroup() {
		renderOrderManager.startGroup();
	}

	@Override
	public boolean maybeStartGroup() {
		return renderOrderManager.maybeStartGroup();
	}

	@Override
	public void endGroup() {
		renderOrderManager.endGroup();
	}

	@Override
	public void pushWrappingFunction(Function<RenderType, RenderType> wrappingFunction) {
		if (this.wrappingFunction != null) {
			this.wrappingFunctionStack.add(this.wrappingFunction);
		}

		this.wrappingFunction = wrappingFunction;
	}

	@Override
	public void popWrappingFunction() {
		if (this.wrappingFunctionStack.isEmpty()) {
			this.wrappingFunction = null;
		} else {
			this.wrappingFunction = this.wrappingFunctionStack.remove(this.wrappingFunctionStack.size() - 1);
		}
	}

	@Override
	public void assertWrapStackEmpty() {
		if (!this.wrappingFunctionStack.isEmpty() || this.wrappingFunction != null) {
			throw new IllegalStateException("Wrapping function stack not empty!");
		}
	}

	/**
	 * A wrapper that prevents callers from explicitly flushing anything.
	 */
	private static class UnflushableWrapper extends MultiBufferSource.BufferSource implements Groupable {
		private final FullyBufferedMultiBufferSource wrapped;

		UnflushableWrapper(FullyBufferedMultiBufferSource wrapped) {
			super(new BufferBuilder(0), Collections.emptyMap());

			this.wrapped = wrapped;
		}

		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			return wrapped.getBuffer(renderType);
		}

		@Override
		public void endBatch() {
			// Disable explicit flushing
		}

		@Override
		public void endBatch(RenderType type) {
			// Disable explicit flushing
		}

		@Override
		public void startGroup() {
			wrapped.startGroup();
		}

		@Override
		public boolean maybeStartGroup() {
			return wrapped.maybeStartGroup();
		}

		@Override
		public void endGroup() {
			wrapped.endGroup();
		}
	}
}
