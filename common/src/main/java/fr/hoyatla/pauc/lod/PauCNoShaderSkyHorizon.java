package fr.hoyatla.pauc.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

public final class PauCNoShaderSkyHorizon {
	private static final String ENABLED_PROPERTY = "pauc.lod.noShaderSkyHorizon";
	private static final String SEGMENTS_PROPERTY = "pauc.lod.noShaderSkyHorizonSegments";
	private static final String TOP_PROPERTY = "pauc.lod.noShaderSkyHorizonTopBlocks";
	private static final String BOTTOM_PROPERTY = "pauc.lod.noShaderSkyHorizonBottomBlocks";
	private static final String RADIUS_MARGIN_PROPERTY = "pauc.lod.noShaderSkyHorizonRadiusMarginBlocks";
	private static final int DEFAULT_SEGMENTS = 48;
	private static final float DEFAULT_TOP_BLOCKS = 192.0F;
	private static final float DEFAULT_BOTTOM_BLOCKS = -96.0F;
	private static final float DEFAULT_RADIUS_MARGIN_BLOCKS = 12.0F;
	private static VertexBuffer buffer;
	private static int bufferSegments;
	private static float bufferRadius;
	private static float bufferTop;
	private static float bufferBottom;

	private PauCNoShaderSkyHorizon() {
	}

	public static void renderAfterVanillaSky(PoseStack poseStack, Matrix4f projection, Camera camera) {
		if (!shouldRender(camera)) {
			return;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		int segments = readInt(SEGMENTS_PROPERTY, DEFAULT_SEGMENTS, 16, 128);
		float radius = range.roundHorizonEndChunk() * 16.0F
			+ readFloat(RADIUS_MARGIN_PROPERTY, DEFAULT_RADIUS_MARGIN_BLOCKS, -64.0F, 256.0F);
		float bottom = readFloat(BOTTOM_PROPERTY, DEFAULT_BOTTOM_BLOCKS, -512.0F, 0.0F);
		float top = readFloat(TOP_PROPERTY, DEFAULT_TOP_BLOCKS, 64.0F, 2048.0F);
		if (top <= bottom + 16.0F) {
			top = bottom + 16.0F;
		}

		ensureBuffer(radius, bottom, top, segments);

		float[] fogColor = RenderSystem.getShaderFogColor();
		RenderSystem.disableBlend();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionShader);
		RenderSystem.setShaderColor(fogColor[0], fogColor[1], fogColor[2], 1.0F);

		try {
			buffer.bind();
			buffer.drawWithShader(new Matrix4f(poseStack.last().pose()), new Matrix4f(projection), GameRenderer.getPositionShader());
		} finally {
			VertexBuffer.unbind();
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			RenderSystem.enableCull();
			RenderSystem.depthMask(true);
			RenderSystem.enableDepthTest();
		}
	}

	private static boolean shouldRender(Camera camera) {
		if (!readBoolean(ENABLED_PROPERTY, true)
			|| PauCLodShaderContext.isShaderPackInUse()
			|| !PauCLodClientSettings.isLodsEnabled()
			|| !PauCLodClientSettings.isLodCloudsEnabled()) {
			return false;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled() || camera == null || camera.getFluidInCamera() != FogType.NONE) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			return false;
		}

		DimensionSpecialEffects.SkyType skyType = level.effects().skyType();
		return skyType == DimensionSpecialEffects.SkyType.NORMAL || level.dimensionType().hasSkyLight();
	}

	private static void ensureBuffer(float radius, float bottom, float top, int segments) {
		if (buffer != null
			&& bufferSegments == segments
			&& same(bufferRadius, radius)
			&& same(bufferBottom, bottom)
			&& same(bufferTop, top)) {
			return;
		}

		if (buffer != null) {
			buffer.close();
		}

		BufferBuilder builder = new BufferBuilder(DefaultVertexFormat.POSITION.getVertexSize() * segments * 4);
		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
		buildCylinder(builder, radius, bottom, top, segments);
		BufferBuilder.RenderedBuffer renderedBuffer = builder.end();

		buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		buffer.bind();
		buffer.upload(renderedBuffer);
		VertexBuffer.unbind();
		bufferSegments = segments;
		bufferRadius = radius;
		bufferBottom = bottom;
		bufferTop = top;
	}

	private static void buildCylinder(VertexConsumer consumer, float radius, float bottom, float top, int segments) {
		double step = Math.PI * 2.0D / segments;
		for (int i = 0; i < segments; i++) {
			double angle0 = i * step;
			double angle1 = (i + 1) * step;
			float x0 = (float) (Math.cos(angle0) * radius);
			float z0 = (float) (Math.sin(angle0) * radius);
			float x1 = (float) (Math.cos(angle1) * radius);
			float z1 = (float) (Math.sin(angle1) * radius);
			consumer.vertex(x0, bottom, z0).endVertex();
			consumer.vertex(x0, top, z0).endVertex();
			consumer.vertex(x1, top, z1).endVertex();
			consumer.vertex(x1, bottom, z1).endVertex();
		}
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}

		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue)));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Float.parseFloat(rawValue), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static boolean same(float left, float right) {
		return Math.abs(left - right) < 0.01F;
	}
}
