package net.irisshaders.iris.pathways;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Renders the sky horizon. Vanilla Minecraft simply uses the "clear color" for its horizon, and then draws a plane
 * above the player. This class extends the sky rendering so that an octagonal prism is drawn around the player instead,
 * allowing shaders to perform more advanced sky rendering.
 * <p>
 * However, the horizon rendering is designed so that when sky shaders are not being used, it looks almost exactly the
 * same as vanilla sky rendering, except a few almost entirely imperceptible differences where the walls
 * of the octagonal prism intersect the top plane.
 */
public class HorizonRenderer {
	/**
	 * The Y coordinate of the top skybox plane. Acts as the upper bound for the horizon prism, since the prism lies
	 * between the bottom and top skybox planes.
	 */
	private static final float DEFAULT_TOP = 96.0F;

	/**
	 * The Y coordinate of the bottom skybox plane. Acts as the lower bound for the horizon prism, since the prism lies
	 * between the bottom and top skybox planes.
	 */
	private static final float DEFAULT_BOTTOM = -16.0F;

	private static final int OPAQUE_ALPHA = 255;
	private static final int TRANSPARENT_ALPHA = 0;
	private static final int DEFAULT_MAX_RADIUS = 256;
	private static final int PAUC_MAX_LOD_RADIUS = 1024;
	private static final String PAUC_HORIZON_DOME_PROPERTY = "pauc.lod.horizonDome";
	private static final String PAUC_HORIZON_DOME_TOP_Y_PROPERTY = "pauc.lod.horizonDomeTopY";
	private static final String PAUC_HORIZON_DOME_BOTTOM_Y_PROPERTY = "pauc.lod.horizonDomeBottomY";
	private static final String PAUC_HORIZON_DOME_TOP_ALPHA_PROPERTY = "pauc.lod.horizonDomeTopAlpha";
	private static final String PAUC_HORIZON_DOME_RISE_PROPERTY = "pauc.lod.horizonDomeRise";
	private static final int PAUC_HORIZON_DOME_RINGS = 6;
	private static final double PAUC_HORIZON_DOME_INNER_RADIUS_RATIO = 0.06D;

	/**
	 * Cosine of 22.5 degrees.
	 */
	private static final double COS_22_5 = Math.cos(Math.toRadians(22.5));

	/**
	 * Sine of 22.5 degrees.
	 */
	private static final double SIN_22_5 = Math.sin(Math.toRadians(22.5));
	private VertexBuffer buffer;
	private int currentRadius;
	private boolean currentDomeActive;
	private float currentTopY;
	private float currentBottomY;
	private float currentDomeRise;
	private int currentTopAlpha;

	public HorizonRenderer() {
		currentRadius = currentHorizonRadius();
		currentDomeActive = currentHorizonDomeActive();
		currentTopY = currentHorizonTopY(currentDomeActive);
		currentBottomY = currentHorizonBottomY(currentDomeActive, currentTopY);
		currentDomeRise = currentHorizonDomeRise(currentDomeActive);
		currentTopAlpha = currentHorizonTopAlpha(currentDomeActive);

		rebuildBuffer();
	}

	private void rebuildBuffer() {
		if (this.buffer != null) {
			this.buffer.close();
		}

		BufferBuilder buffer = Tesselator.getInstance().getBuilder();

		// Build the horizon quads into a buffer
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buildHorizon(currentRadius, buffer);
		BufferBuilder.RenderedBuffer renderedBuffer = buffer.end();

		this.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		this.buffer.bind();
		this.buffer.upload(renderedBuffer);
		VertexBuffer.unbind();
	}

	private void buildQuad(VertexConsumer consumer, double x1, double z1, double x2, double z2) {
		consumer.vertex(x1, currentBottomY, z1);
		consumer.color(255, 255, 255, OPAQUE_ALPHA);
		consumer.endVertex();
		consumer.vertex(x1, currentTopY, z1);
		consumer.color(255, 255, 255, currentTopAlpha);
		consumer.endVertex();
		consumer.vertex(x2, currentTopY, z2);
		consumer.color(255, 255, 255, currentTopAlpha);
		consumer.endVertex();
		consumer.vertex(x2, currentBottomY, z2);
		consumer.color(255, 255, 255, OPAQUE_ALPHA);
		consumer.endVertex();
	}

	private void buildHalf(VertexConsumer consumer, double adjacent, double opposite, boolean invert) {
		if (invert) {
			adjacent = -adjacent;
			opposite = -opposite;
		}

		// NB: Make sure that these vertices are being specified in counterclockwise order!
		// Otherwise back face culling will remove your quads, and you'll be wondering why there's a hole in your horizon.
		// Don't poke holes in the horizon. Specify vertices in counterclockwise order.

		// +X,-Z face
		buildQuad(consumer, adjacent, -opposite, opposite, -adjacent);
		// +X face
		buildQuad(consumer, adjacent, opposite, adjacent, -opposite);
		// +X,+Z face
		buildQuad(consumer, opposite, adjacent, adjacent, opposite);
		// +Z face
		buildQuad(consumer, -opposite, adjacent, opposite, adjacent);
	}

	/**
	 * @param adjacent the adjacent side length of the a triangle with a hypotenuse extending from the center of the
	 *                 octagon to a given vertex on the perimeter.
	 * @param opposite the opposite side length of the a triangle with a hypotenuse extending from the center of the
	 *                 octagon to a given vertex on the perimeter.
	 */
	private void buildOctagonalPrism(VertexConsumer consumer, double adjacent, double opposite) {
		buildHalf(consumer, adjacent, opposite, false);
		buildHalf(consumer, adjacent, opposite, true);
	}

	private void buildRegularOctagonalPrism(VertexConsumer consumer, double radius) {
		buildOctagonalPrism(consumer, radius * COS_22_5, radius * SIN_22_5);
	}

	private void buildBottomPlane(VertexConsumer consumer, int radius) {
		for (int x = -radius; x <= radius; x += 64) {
			for (int z = -radius; z <= radius; z += 64) {
				consumer.vertex(x + 64, currentBottomY, z);
				consumer.color(255, 255, 255, OPAQUE_ALPHA);
				consumer.endVertex();
				consumer.vertex(x, currentBottomY, z);
				consumer.color(255, 255, 255, OPAQUE_ALPHA);
				consumer.endVertex();
				consumer.vertex(x, currentBottomY, z + 64);
				consumer.color(255, 255, 255, OPAQUE_ALPHA);
				consumer.endVertex();
				consumer.vertex(x + 64, currentBottomY, z + 64);
				consumer.color(255, 255, 255, OPAQUE_ALPHA);
				consumer.endVertex();
			}
		}
	}

	private void buildDomeCap(VertexConsumer consumer, double radius) {
		double centerRadius = Math.max(16.0D, radius * PAUC_HORIZON_DOME_INNER_RADIUS_RATIO);

		for (int ring = 0; ring < PAUC_HORIZON_DOME_RINGS; ring++) {
			double outerT = (double) ring / PAUC_HORIZON_DOME_RINGS;
			double innerT = (double) (ring + 1) / PAUC_HORIZON_DOME_RINGS;
			double outerRadius = lerp(radius, centerRadius, outerT);
			double innerRadius = lerp(radius, centerRadius, innerT);
			float outerY = domeY(outerT);
			float innerY = domeY(innerT);
			int outerAlpha = domeAlpha(outerT);
			int innerAlpha = domeAlpha(innerT);

			for (int side = 0; side < 8; side++) {
				double angle = Math.toRadians(side * 45.0D + 22.5D);
				double nextAngle = Math.toRadians((side + 1) * 45.0D + 22.5D);

				consumer.vertex(Math.cos(angle) * outerRadius, outerY, Math.sin(angle) * outerRadius);
				consumer.color(255, 255, 255, outerAlpha);
				consumer.endVertex();
				consumer.vertex(Math.cos(nextAngle) * outerRadius, outerY, Math.sin(nextAngle) * outerRadius);
				consumer.color(255, 255, 255, outerAlpha);
				consumer.endVertex();
				consumer.vertex(Math.cos(nextAngle) * innerRadius, innerY, Math.sin(nextAngle) * innerRadius);
				consumer.color(255, 255, 255, innerAlpha);
				consumer.endVertex();
				consumer.vertex(Math.cos(angle) * innerRadius, innerY, Math.sin(angle) * innerRadius);
				consumer.color(255, 255, 255, innerAlpha);
				consumer.endVertex();
			}
		}
	}

	private float domeY(double t) {
		double eased = 1.0D - Math.cos(t * Math.PI * 0.5D);
		return currentTopY + (float) (currentDomeRise * eased);
	}

	private int domeAlpha(double t) {
		double eased = t * t * (3.0D - 2.0D * t);
		return Math.max(TRANSPARENT_ALPHA, Math.min(OPAQUE_ALPHA, (int) Math.round(currentTopAlpha * (1.0D - eased))));
	}

	private void buildHorizon(int radius, VertexConsumer consumer) {
		buildRegularOctagonalPrism(consumer, radius);

		// Keep vanilla's coverage, but avoid a near horizon disk cutting through extended PauC LOD ranges.
		buildBottomPlane(consumer, Math.max(384, radius));
		if (currentDomeActive && currentTopAlpha > 0) {
			buildDomeCap(consumer, radius);
		}
	}

	public void renderHorizon(Matrix4fc modelView, Matrix4fc projection, ShaderInstance shader) {
		int horizonRadius = currentHorizonRadius();
		boolean domeActive = currentHorizonDomeActive();
		float topY = currentHorizonTopY(domeActive);
		float bottomY = currentHorizonBottomY(domeActive, topY);
		float domeRise = currentHorizonDomeRise(domeActive);
		int topAlpha = currentHorizonTopAlpha(domeActive);
		if (currentRadius != horizonRadius || currentDomeActive != domeActive || currentTopY != topY || currentBottomY != bottomY || currentDomeRise != domeRise || currentTopAlpha != topAlpha) {
			currentRadius = horizonRadius;
			currentDomeActive = domeActive;
			currentTopY = topY;
			currentBottomY = bottomY;
			currentDomeRise = domeRise;
			currentTopAlpha = topAlpha;
			rebuildBuffer();
		}

		buffer.bind();
		buffer.drawWithShader(new Matrix4f(modelView), new Matrix4f(projection), shader);
		VertexBuffer.unbind();
	}

	public void destroy() {
		buffer.close();
	}

	private int currentHorizonRadius() {
		int vanillaRadius = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range != null && range.enabled() && PauCLodShaderContext.isShaderPackInUse()) {
			int lodRadius = (range.roundHorizonEndChunk() + 2) * 16;
			return Math.min(Math.max(vanillaRadius, lodRadius), PAUC_MAX_LOD_RADIUS);
		}
		return Math.min(vanillaRadius, DEFAULT_MAX_RADIUS);
	}

	private boolean currentHorizonDomeActive() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		return readBoolean(PAUC_HORIZON_DOME_PROPERTY, true)
			&& range != null
			&& range.enabled()
			&& !PauCLodShaderContext.isShaderPackInUse();
	}

	private float currentHorizonTopY(boolean domeActive) {
		return domeActive ? readFloat(PAUC_HORIZON_DOME_TOP_Y_PROPERTY, 192.0F, DEFAULT_TOP, 512.0F) : DEFAULT_TOP;
	}

	private float currentHorizonBottomY(boolean domeActive, float topY) {
		float bottomY = domeActive ? readFloat(PAUC_HORIZON_DOME_BOTTOM_Y_PROPERTY, DEFAULT_BOTTOM, -64.0F, DEFAULT_TOP) : DEFAULT_BOTTOM;
		return Math.min(bottomY, topY - 16.0F);
	}

	private float currentHorizonDomeRise(boolean domeActive) {
		return domeActive ? readFloat(PAUC_HORIZON_DOME_RISE_PROPERTY, 128.0F, 0.0F, 512.0F) : 0.0F;
	}

	private int currentHorizonTopAlpha(boolean domeActive) {
		return domeActive ? readInt(PAUC_HORIZON_DOME_TOP_ALPHA_PROPERTY, 96, 0, OPAQUE_ALPHA) : TRANSPARENT_ALPHA;
	}

	private static double lerp(double start, double end, double t) {
		return start + (end - start) * t;
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
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Float.parseFloat(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
