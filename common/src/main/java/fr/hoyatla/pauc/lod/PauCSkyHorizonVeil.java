package fr.hoyatla.pauc.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.slf4j.Logger;

public final class PauCSkyHorizonVeil {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.skyHorizonVeil";
	private static final String START_RATIO_PROPERTY = "pauc.lod.skyHorizonVeilStartRatio";
	private static final String STRENGTH_PROPERTY = "pauc.lod.skyHorizonVeilStrength";
	private static final String MIN_WIDTH_BLOCKS_PROPERTY = "pauc.lod.skyHorizonVeilMinWidthBlocks";
	private static final String SEGMENTS_PROPERTY = "pauc.lod.skyHorizonVeilSegments";
	private static final String SKY_SHADER_PHASE_PROPERTY = "pauc.lod.skyHorizonVeilSkyPhase";
	private static final String VERTICAL_TOP_BLOCKS_PROPERTY = "pauc.lod.skyHorizonVeilTopBlocks";
	private static final String VERTICAL_TOP_STRENGTH_PROPERTY = "pauc.lod.skyHorizonVeilTopStrength";
	private static final String VERTICAL_SHELLS_PROPERTY = "pauc.lod.skyHorizonVeilShells";
	private static final float SKY_TOP_Y = 16.0F;
	private static final float SKY_BOTTOM_Y = -16.0F;
	private static final float DEFAULT_START_RATIO = 0.82F;
	private static final float DEFAULT_STRENGTH = 1.0F;
	private static final float DEFAULT_MIN_WIDTH_BLOCKS = 128.0F;
	private static final float DEFAULT_VERTICAL_TOP_BLOCKS = 512.0F;
	private static final float DEFAULT_VERTICAL_TOP_STRENGTH = 0.72F;
	private static final int DEFAULT_SEGMENTS = 96;
	private static final int DEFAULT_VERTICAL_SHELLS = 8;
	private static long lastDiagnosticLogMs;

	private PauCSkyHorizonVeil() {
	}

	public static void renderAfterSky(PoseStack poseStack) {
		if (!shouldRender()) {
			return;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		float outerRadiusBlocks = range.roundHorizonEndChunk() * 16.0F;
		float minWidthBlocks = readFloat(MIN_WIDTH_BLOCKS_PROPERTY, DEFAULT_MIN_WIDTH_BLOCKS, 16.0F, 512.0F);
		float requestedInnerRadiusBlocks = outerRadiusBlocks * readFloat(START_RATIO_PROPERTY, DEFAULT_START_RATIO, 0.05F, 0.99F);
		float innerRadiusBlocks = Math.max(
			range.lodStartChunk() * 16.0F,
			Math.min(requestedInnerRadiusBlocks, outerRadiusBlocks - minWidthBlocks)
		);
		if (outerRadiusBlocks <= innerRadiusBlocks + 1.0F) {
			return;
		}

		float strength = readFloat(STRENGTH_PROPERTY, DEFAULT_STRENGTH, 0.0F, 1.0F);
		if (strength <= 0.0F) {
			return;
		}

		int segments = readInt(SEGMENTS_PROPERTY, DEFAULT_SEGMENTS, 32, 192);
		int shells = readInt(VERTICAL_SHELLS_PROPERTY, DEFAULT_VERTICAL_SHELLS, 1, 16);
		float topY = readFloat(VERTICAL_TOP_BLOCKS_PROPERTY, DEFAULT_VERTICAL_TOP_BLOCKS, 64.0F, 1024.0F);
		float topStrength = readFloat(VERTICAL_TOP_STRENGTH_PROPERTY, DEFAULT_VERTICAL_TOP_STRENGTH, 0.0F, 1.0F);
		float[] fogColor = RenderSystem.getShaderFogColor();
		float red = color(fogColor, 0);
		float green = color(fogColor, 1);
		float blue = color(fogColor, 2);
		Matrix4f pose = poseStack.last().pose();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

		BufferBuilder builder = Tesselator.getInstance().getBuilder();
		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buildAnnulus(builder, pose, innerRadiusBlocks, outerRadiusBlocks, SKY_TOP_Y, red, green, blue, strength, segments, false);
		buildAnnulus(builder, pose, innerRadiusBlocks, outerRadiusBlocks, SKY_BOTTOM_Y, red, green, blue, strength, segments, true);
		buildVerticalCurtain(builder, pose, innerRadiusBlocks, outerRadiusBlocks, SKY_BOTTOM_Y, topY, red, green, blue, strength, topStrength, segments, shells);
		BufferUploader.drawWithShader(builder.end());

		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		logDiagnostic(range, innerRadiusBlocks, outerRadiusBlocks, topY, strength, topStrength, segments, shells);
	}

	private static boolean shouldRender() {
		if (!readBoolean(ENABLED_PROPERTY, true)
			|| !PauCLodClientSettings.isLodsEnabled()
			|| !PauCLodClientSettings.isLodCloudsEnabled()) {
			return false;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.cameraEntity == null) {
			return false;
		}

		if (minecraft.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
			return false;
		}

		DimensionSpecialEffects.SkyType skyType = minecraft.level.effects().skyType();
		return skyType == DimensionSpecialEffects.SkyType.NORMAL || minecraft.level.dimensionType().hasSkyLight();
	}

	private static void buildAnnulus(
		BufferBuilder builder,
		Matrix4f pose,
		float innerRadius,
		float outerRadius,
		float y,
		float red,
		float green,
		float blue,
		float strength,
		int segments,
		boolean reverse
	) {
		for (int index = 0; index < segments; index++) {
			double angleA = Math.PI * 2.0D * index / segments;
			double angleB = Math.PI * 2.0D * (index + 1) / segments;
			float innerAX = (float) (Math.cos(angleA) * innerRadius);
			float innerAZ = (float) (Math.sin(angleA) * innerRadius);
			float outerAX = (float) (Math.cos(angleA) * outerRadius);
			float outerAZ = (float) (Math.sin(angleA) * outerRadius);
			float innerBX = (float) (Math.cos(angleB) * innerRadius);
			float innerBZ = (float) (Math.sin(angleB) * innerRadius);
			float outerBX = (float) (Math.cos(angleB) * outerRadius);
			float outerBZ = (float) (Math.sin(angleB) * outerRadius);

			if (reverse) {
				vertex(builder, pose, innerAX, y, innerAZ, red, green, blue, 0.0F);
				vertex(builder, pose, outerAX, y, outerAZ, red, green, blue, strength);
				vertex(builder, pose, outerBX, y, outerBZ, red, green, blue, strength);
				vertex(builder, pose, innerBX, y, innerBZ, red, green, blue, 0.0F);
			} else {
				vertex(builder, pose, innerAX, y, innerAZ, red, green, blue, 0.0F);
				vertex(builder, pose, innerBX, y, innerBZ, red, green, blue, 0.0F);
				vertex(builder, pose, outerBX, y, outerBZ, red, green, blue, strength);
				vertex(builder, pose, outerAX, y, outerAZ, red, green, blue, strength);
			}
		}
	}

	private static void buildVerticalCurtain(
		BufferBuilder builder,
		Matrix4f pose,
		float innerRadius,
		float outerRadius,
		float bottomY,
		float topY,
		float red,
		float green,
		float blue,
		float strength,
		float topStrength,
		int segments,
		int shells
	) {
		float shellAlphaScale = 1.35F / shells;
		for (int shell = 1; shell <= shells; shell++) {
			float distanceProgress = (float) shell / shells;
			float shellRadius = lerp(innerRadius, outerRadius, distanceProgress);
			float bottomAlpha = strength * smoothStep(distanceProgress) * shellAlphaScale;
			float topAlpha = bottomAlpha * topStrength;
			buildCylinderShell(builder, pose, shellRadius, bottomY, topY, red, green, blue, bottomAlpha, topAlpha, segments);
		}
	}

	private static void buildCylinderShell(
		BufferBuilder builder,
		Matrix4f pose,
		float radius,
		float bottomY,
		float topY,
		float red,
		float green,
		float blue,
		float bottomAlpha,
		float topAlpha,
		int segments
	) {
		for (int index = 0; index < segments; index++) {
			double angleA = Math.PI * 2.0D * index / segments;
			double angleB = Math.PI * 2.0D * (index + 1) / segments;
			float ax = (float) (Math.cos(angleA) * radius);
			float az = (float) (Math.sin(angleA) * radius);
			float bx = (float) (Math.cos(angleB) * radius);
			float bz = (float) (Math.sin(angleB) * radius);

			vertex(builder, pose, ax, bottomY, az, red, green, blue, bottomAlpha);
			vertex(builder, pose, ax, topY, az, red, green, blue, topAlpha);
			vertex(builder, pose, bx, topY, bz, red, green, blue, topAlpha);
			vertex(builder, pose, bx, bottomY, bz, red, green, blue, bottomAlpha);
		}
	}

	private static void vertex(BufferBuilder builder, Matrix4f pose, float x, float y, float z, float red, float green, float blue, float alpha) {
		builder.vertex(pose, x, y, z).color(red, green, blue, alpha).endVertex();
	}

	private static float color(float[] values, int index) {
		return values == null || values.length <= index ? 0.0F : clamp(values[index], 0.0F, 1.0F);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Integer.parseInt(rawValue), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
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

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float lerp(float start, float end, float progress) {
		return start + (end - start) * progress;
	}

	private static float smoothStep(float value) {
		float progress = clamp(value, 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static void logDiagnostic(
		PauCLodRange range,
		float innerRadiusBlocks,
		float outerRadiusBlocks,
		float topY,
		float strength,
		float topStrength,
		int segments,
		int shells
	) {
		if (!PauCLodClientSettings.diagnosticsEnabled()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastDiagnosticLogMs < 5000L) {
			return;
		}

		lastDiagnosticLogMs = now;
		LOGGER.info(
			"PauC sky horizon veil: inner={} blocks, outer={} blocks, top={} blocks, strength={}, topStrength={}, segments={}, shells={}, shaderPhase={}, tiedToLodClouds=true, {}",
			Math.round(innerRadiusBlocks),
			Math.round(outerRadiusBlocks),
			Math.round(topY),
			strength,
			topStrength,
			segments,
			shells,
			shouldUseSkyShaderPhase() ? "sky" : "basic",
			range.describe()
		);
	}

	public static boolean shouldUseSkyShaderPhase() {
		String override = System.getProperty(SKY_SHADER_PHASE_PROPERTY);
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

		return PauCLodShaderProfiles.currentFamily() != PauCLodShaderProfiles.Family.PHOTON;
	}
}
