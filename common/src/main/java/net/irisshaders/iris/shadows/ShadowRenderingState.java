package net.irisshaders.iris.shadows;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

public class ShadowRenderingState {
	private static final float CAMERA_ANGLE_STEP_DEGREES = 1.0F;
	private static final float MIN_SHADOW_ANGLE_STEP_DEGREES = 0.25F;
	private static final float MAX_SHADOW_ANGLE_STEP_DEGREES = 2.0F;
	private static BlockEntityRenderFunction function = (ShadowRenderer::renderBlockEntities);
	private static boolean shadowTerrainGraphDirty = true;
	private static long shadowTerrainGraphState = Long.MIN_VALUE;
	private static boolean disableShadowBlockFaceCulling = true;

	public static boolean areShadowsCurrentlyBeingRendered() {
		return ShadowRenderer.ACTIVE;
	}

	public static void setBlockEntityRenderFunction(BlockEntityRenderFunction function) {
		ShadowRenderingState.function = function;
	}

	public static int renderBlockEntities(ShadowRenderer shadowRenderer, MultiBufferSource.BufferSource bufferSource, PoseStack modelView, Camera camera, double cameraX, double cameraY, double cameraZ, float tickDelta, boolean hasEntityFrustum, boolean lightsOnly) {
		return function.renderBlockEntities(shadowRenderer, bufferSource, modelView, camera, cameraX, cameraY, cameraZ, tickDelta, hasEntityFrustum, lightsOnly);
	}

	public static int getRenderDistance() {
		return ShadowRenderer.renderDistance;
	}

	public static void updateShadowTerrainGraphState(double cameraX, double cameraY, double cameraZ, float cameraPitch, float cameraYaw,
													 int renderDistance, float intervalSize, float shadowAngle, float halfPlaneLength) {
		long nextState = 1469598103934665603L;

		nextState = mix(nextState, quantizeCoordinate(cameraX, intervalSize));
		nextState = mix(nextState, quantizeCoordinate(cameraY, intervalSize));
		nextState = mix(nextState, quantizeCoordinate(cameraZ, intervalSize));
		nextState = mix(nextState, quantizeAngle(cameraPitch, CAMERA_ANGLE_STEP_DEGREES));
		nextState = mix(nextState, quantizeAngle(Mth.wrapDegrees(cameraYaw), CAMERA_ANGLE_STEP_DEGREES));
		nextState = mix(nextState, quantizeShadowAngle(shadowAngle, intervalSize, halfPlaneLength));
		nextState = mix(nextState, renderDistance);
		nextState = mix(nextState, Float.floatToRawIntBits(intervalSize));
		nextState = mix(nextState, Float.floatToRawIntBits(halfPlaneLength));

		if (nextState != shadowTerrainGraphState) {
			shadowTerrainGraphState = nextState;
			shadowTerrainGraphDirty = true;
		}
	}

	public static boolean shouldRebuildShadowTerrainGraph() {
		return shadowTerrainGraphDirty;
	}

	public static void onShadowTerrainGraphRebuilt() {
		shadowTerrainGraphDirty = false;
	}

	public static void markShadowTerrainGraphDirty() {
		shadowTerrainGraphDirty = true;
	}

	public static void setDisableShadowBlockFaceCulling(boolean disableShadowBlockFaceCulling) {
		ShadowRenderingState.disableShadowBlockFaceCulling = disableShadowBlockFaceCulling;
	}

	public static boolean shouldDisableBlockFaceCullingInShadowPass() {
		return disableShadowBlockFaceCulling;
	}

	private static long quantizeCoordinate(double coordinate, float intervalSize) {
		double cellSize = Math.max(1.0D, Math.abs(intervalSize));

		return Mth.floor(coordinate / cellSize);
	}

	private static int quantizeAngle(float angleDegrees, float stepDegrees) {
		return Mth.floor(angleDegrees / stepDegrees);
	}

	private static int quantizeShadowAngle(float shadowAngle, float intervalSize, float halfPlaneLength) {
		float stepDegrees = getShadowAngleStepDegrees(intervalSize, halfPlaneLength);

		return quantizeAngle(shadowAngle * 360.0F, stepDegrees);
	}

	private static float getShadowAngleStepDegrees(float intervalSize, float halfPlaneLength) {
		double cellSize = Math.max(1.0D, Math.abs(intervalSize));
		double distance = Math.max(1.0D, halfPlaneLength);
		float stepDegrees = (float) Math.toDegrees(Math.atan(cellSize / distance));

		return Mth.clamp(stepDegrees, MIN_SHADOW_ANGLE_STEP_DEGREES, MAX_SHADOW_ANGLE_STEP_DEGREES);
	}

	private static long mix(long state, long value) {
		return (state ^ value) * 1099511628211L;
	}

	public interface BlockEntityRenderFunction {
		int renderBlockEntities(ShadowRenderer shadowRenderer, MultiBufferSource.BufferSource bufferSource, PoseStack modelView, Camera camera, double cameraX, double cameraY, double cameraZ, float tickDelta, boolean hasEntityFrustum, boolean lightsOnly);
	}
}
