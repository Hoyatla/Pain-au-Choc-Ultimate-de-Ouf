package net.irisshaders.iris.mixin.vertices;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public class MixinVertexBuffer {
	@Shadow
	private VertexFormat format;
	@Shadow
	private VertexFormat.Mode mode;
	@Shadow
	private int indexCount;
	@Unique
	private static boolean pauc$reportedLegacyTerrainDefaults;
	@Unique
	private static boolean pauc$reportedIncompleteChunkDraw;

	@Inject(method = "draw", at = @At("HEAD"), cancellable = true)
	private void pauc$skipIncompleteChunkDraw(CallbackInfo ci) {
		if (this.mode != null && this.indexCount > 0) {
			return;
		}

		if (!pauc$reportedIncompleteChunkDraw) {
			pauc$reportedIncompleteChunkDraw = true;
			Iris.logger.warn("PauC skipped an incomplete vanilla chunk buffer draw during a transient rebuild.");
		}

		ci.cancel();
	}

	@Inject(method = "_drawWithShader", at = @At("HEAD"))
	private void pauc$stabilizeLegacyTerrainAttributes(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, CallbackInfo ci) {
		if (this.format != DefaultVertexFormat.BLOCK
			|| !(shader instanceof ExtendedShader)
			|| !WorldRenderingSettings.INSTANCE.shouldUseExtendedVertexFormat()
			|| !pauc$isTerrainPhase()) {
			return;
		}

		if (!pauc$reportedLegacyTerrainDefaults) {
			pauc$reportedLegacyTerrainDefaults = true;
			Iris.logger.info("PauC stabilizes legacy terrain chunk buffers while extended shader chunks rebuild.");
		}

		GL20C.glVertexAttrib4f(6, -1.0F, -1.0F, 0.0F, 1.0F);
		GL20C.glVertexAttrib4f(7, 0.0F, 0.0F, 0.0F, 1.0F);
		GL20C.glVertexAttrib4f(8, 0.0F, 0.0F, 0.0F, 1.0F);
		GL20C.glVertexAttrib4f(9, 0.0F, 0.0F, 0.0F, 1.0F);
	}

	@Unique
	private static boolean pauc$isTerrainPhase() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		if (pipeline == null) {
			return false;
		}

		return switch (pipeline.getPhase()) {
			case TERRAIN_SOLID, TERRAIN_CUTOUT_MIPPED, TERRAIN_CUTOUT, TERRAIN_TRANSLUCENT, TRIPWIRE -> true;
			default -> false;
		};
	}
}
