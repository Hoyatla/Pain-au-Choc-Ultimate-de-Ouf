package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Mixin(TheEndPortalRenderer.class)
public class MixinTheEndPortalRenderer {
	@Unique
	private static final float RED = 0.075f;

	@Unique
	private static final float GREEN = 0.15f;

	@Unique
	private static final float BLUE = 0.2f;

	@Unique
	private static final String[] PAUC$OFFSET_UP_CANDIDATES = {"getOffsetUp", "m_142491_", "b"};

	@Unique
	private static final String[] PAUC$OFFSET_DOWN_CANDIDATES = {"getOffsetDown", "m_142489_", "c"};

	@Unique
	private static Method pauC$offsetUpMethod;

	@Unique
	private static Method pauC$offsetDownMethod;

	@Unique
	private static boolean pauC$offsetUpResolved;

	@Unique
	private static boolean pauC$offsetDownResolved;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	public void iris$onRender(TheEndPortalBlockEntity entity, float tickDelta, PoseStack poseStack, MultiBufferSource multiBufferSource, int light, int overlay, CallbackInfo ci) {
		if (!Iris.getCurrentPack().isPresent()) {
			return;
		}

		ci.cancel();

		// POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL
		VertexConsumer vertexConsumer =
			multiBufferSource.getBuffer(RenderType.entitySolid(TheEndPortalRenderer.END_PORTAL_LOCATION));

		PoseStack.Pose pose = poseStack.last();
		Matrix3f normal = poseStack.last().normal();

		// animation with a period of 100 seconds.
		// note that texture coordinates are wrapping, not clamping.
		float progress = (SystemTimeUniforms.TIMER.getFrameTimeCounter() * 0.01f) % 1f;
		float topHeight = pauC$getOffsetUpSafe();
		float bottomHeight = pauC$getOffsetDownSafe();

		quad(entity, vertexConsumer, pose.pose(), normal, Direction.UP, progress, overlay, light,
			0.0f, topHeight, 1.0f,
			1.0f, topHeight, 1.0f,
			1.0f, topHeight, 0.0f,
			0.0f, topHeight, 0.0f);

		quad(entity, vertexConsumer, pose.pose(), normal, Direction.DOWN, progress, overlay, light,
			0.0f, bottomHeight, 1.0f,
			0.0f, bottomHeight, 0.0f,
			1.0f, bottomHeight, 0.0f,
			1.0f, bottomHeight, 1.0f);

		quad(entity, vertexConsumer, pose.pose(), normal, Direction.NORTH, progress, overlay, light,
			0.0f, topHeight, 0.0f,
			1.0f, topHeight, 0.0f,
			1.0f, bottomHeight, 0.0f,
			0.0f, bottomHeight, 0.0f);

		quad(entity, vertexConsumer, pose.pose(), normal, Direction.WEST, progress, overlay, light,
			0.0f, topHeight, 1.0f,
			0.0f, topHeight, 0.0f,
			0.0f, bottomHeight, 0.0f,
			0.0f, bottomHeight, 1.0f);

		quad(entity, vertexConsumer, pose.pose(), normal, Direction.SOUTH, progress, overlay, light,
			0.0f, topHeight, 1.0f,
			0.0f, bottomHeight, 1.0f,
			1.0f, bottomHeight, 1.0f,
			1.0f, topHeight, 1.0f);

		quad(entity, vertexConsumer, pose.pose(), normal, Direction.EAST, progress, overlay, light,
			1.0f, topHeight, 1.0f,
			1.0f, bottomHeight, 1.0f,
			1.0f, bottomHeight, 0.0f,
			1.0f, topHeight, 0.0f);
	}

	@Unique
	private float pauC$getOffsetUpSafe() {
		return pauC$getOffsetSafely(true, 0.75F, PAUC$OFFSET_UP_CANDIDATES);
	}

	@Unique
	private float pauC$getOffsetDownSafe() {
		return pauC$getOffsetSafely(false, 0.375F, PAUC$OFFSET_DOWN_CANDIDATES);
	}

	@Unique
	private float pauC$getOffsetSafely(boolean up, float fallback, String[] methodCandidates) {
		Method method = up ? pauC$offsetUpMethod : pauC$offsetDownMethod;
		boolean resolved = up ? pauC$offsetUpResolved : pauC$offsetDownResolved;

		if (!resolved) {
			method = pauC$findOffsetMethod(methodCandidates);

			if (up) {
				pauC$offsetUpMethod = method;
				pauC$offsetUpResolved = true;
			} else {
				pauC$offsetDownMethod = method;
				pauC$offsetDownResolved = true;
			}
		}

		if (method == null) {
			return fallback;
		}

		try {
			return ((Float) method.invoke(this)).floatValue();
		} catch (ReflectiveOperationException ignored) {
			return fallback;
		}
	}

	@Unique
	private static Method pauC$findOffsetMethod(String[] methodCandidates) {
		for (String candidate : methodCandidates) {
			try {
				Method method = TheEndPortalRenderer.class.getDeclaredMethod(candidate);
				if (method.getParameterCount() == 0 && method.getReturnType() == float.class) {
					method.setAccessible(true);
					return method;
				}
			} catch (NoSuchMethodException ignored) {
			}
		}

		return null;
	}

	@Unique
	private void quad(TheEndPortalBlockEntity entity, VertexConsumer vertexConsumer, Matrix4f pose, Matrix3f normal,
					  Direction direction, float progress, int overlay, int light,
					  float x1, float y1, float z1,
					  float x2, float y2, float z2,
					  float x3, float y3, float z3,
					  float x4, float y4, float z4) {
		if (!entity.shouldRenderFace(direction)) {
			return;
		}

		float nx = direction.getStepX();
		float ny = direction.getStepY();
		float nz = direction.getStepZ();

		vertexConsumer.vertex(pose, x1, y1, z1).color(RED, GREEN, BLUE, 1.0f)
			.uv(0.0F + progress, 0.0F + progress).overlayCoords(overlay).uv2(light)
			.normal(normal, nx, ny, nz).endVertex();

		vertexConsumer.vertex(pose, x2, y2, z2).color(RED, GREEN, BLUE, 1.0f)
			.uv(0.0F + progress, 0.2F + progress).overlayCoords(overlay).uv2(light)
			.normal(normal, nx, ny, nz).endVertex();

		vertexConsumer.vertex(pose, x3, y3, z3).color(RED, GREEN, BLUE, 1.0f)
			.uv(0.2F + progress, 0.2F + progress).overlayCoords(overlay).uv2(light)
			.normal(normal, nx, ny, nz).endVertex();

		vertexConsumer.vertex(pose, x4, y4, z4).color(RED, GREEN, BLUE, 1.0f)
			.uv(0.2F + progress, 0.0F + progress).overlayCoords(overlay).uv2(light)
			.normal(normal, nx, ny, nz).endVertex();
	}
}
