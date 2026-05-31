package net.irisshaders.batchedentityrendering.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.batchedentityrendering.impl.BufferBuilderExt;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(value = BufferBuilder.class, priority = 1010)
public class MixinBufferBuilder_SegmentRendering implements BufferBuilderExt {
	@Shadow(aliases = "f_85648_")
	private ByteBuffer buffer;

	@Shadow(aliases = "f_85658_")
	private VertexFormat format;

	@Shadow(aliases = "f_85654_")
	private int vertices;
	@Shadow(aliases = "f_85652_")
	private int nextElementByte;
	@Unique
	private boolean dupeNextVertex;

	@Shadow(aliases = "m_85665_")
	private void ensureVertexCapacity() {
		throw new AssertionError("not shadowed");
	}

	@Override
	public void splitStrip() {
		if (vertices == 0) {
			// no strip to split, not building.
			return;
		}

		duplicateLastVertex();
		dupeNextVertex = true;
	}

	private void duplicateLastVertex() {
		int i = this.format.getVertexSize();
		MemoryUtil.memCopy(MemoryUtil.memAddress(this.buffer, this.nextElementByte - i), MemoryUtil.memAddress(this.buffer, this.nextElementByte), i);
		this.nextElementByte += i;
		++this.vertices;
		this.ensureVertexCapacity();
	}

	@Inject(method = "end", at = @At("RETURN"))
	private void batchedentityrendering$onEnd(CallbackInfoReturnable<BufferBuilder.RenderedBuffer> cir) {
		dupeNextVertex = false;
	}

	@Inject(method = "endVertex", at = @At("RETURN"))
	private void batchedentityrendering$onNext(CallbackInfo ci) {
		if (dupeNextVertex) {
			dupeNextVertex = false;
			duplicateLastVertex();
		}
	}

	@Dynamic
	@Inject(method = "sodium$moveToNextVertex", at = @At("RETURN"), require = 0)
	private void batchedentityrendering$onNextSodium(CallbackInfo ci) {
		if (dupeNextVertex) {
			dupeNextVertex = false;
			duplicateLastVertex();
		}
	}
}
