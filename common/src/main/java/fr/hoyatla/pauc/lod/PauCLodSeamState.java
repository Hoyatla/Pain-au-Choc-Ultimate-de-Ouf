package fr.hoyatla.pauc.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class PauCLodSeamState {
	private static final float HEIGHT_BLEND_STRENGTH = 0.10F;
	private static final float MAX_VERTICAL_STEP = 4.0F;
	private static final float MOTION_WIDTH_BLOCKS = 48.0F;
	private static final float MOTION_STRENGTH_SCALE = 0.06F;
	private static Snapshot current = Snapshot.disabled();
	private static EdgeHeights lastHeights;
	private static double lastCameraX;
	private static double lastCameraZ;
	private static long lastUpdateNs;
	private static boolean hasLastCamera;

	private PauCLodSeamState() {
	}

	public static void update(float clipDistanceBlocks, float baseMorphWidthBlocks) {
		if (PauCLodShaderContext.isShaderPackInUse() || clipDistanceBlocks <= 0.0F) {
			current = Snapshot.disabled();
			hasLastCamera = false;
			lastHeights = null;
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null || minecraft.level == null) {
			current = Snapshot.disabled();
			hasLastCamera = false;
			lastHeights = null;
			return;
		}

		Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
		long now = System.nanoTime();
		double deltaX = hasLastCamera ? camera.x - lastCameraX : 0.0D;
		double deltaZ = hasLastCamera ? camera.z - lastCameraZ : 0.0D;
		double seconds = hasLastCamera ? Math.max((now - lastUpdateNs) / 1_000_000_000.0D, 0.001D) : 0.05D;
		float speedBlocksPerSecond = (float) (Math.sqrt(deltaX * deltaX + deltaZ * deltaZ) / seconds);
		float motionStrength = clamp(speedBlocksPerSecond * MOTION_STRENGTH_SCALE, 0.0F, 1.0F);
		float directionX = 0.0F;
		float directionZ = 0.0F;
		double deltaLength = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
		if (deltaLength > 0.0001D) {
			directionX = (float) (deltaX / deltaLength);
			directionZ = (float) (deltaZ / deltaLength);
		}

		EdgeHeights sampledHeights = sampleEdgeHeights(minecraft.level, camera, clipDistanceBlocks, baseMorphWidthBlocks);
		EdgeHeights heights = smoothHeights(lastHeights, sampledHeights, hasLastCamera ? 0.18F : 1.0F);
		lastHeights = heights;
		current = new Snapshot(
			(float) camera.x,
			(float) camera.y,
			(float) camera.z,
			directionX,
			directionZ,
			motionStrength,
			motionStrength * MOTION_WIDTH_BLOCKS,
			heights.west(),
			heights.east(),
			heights.north(),
			heights.south(),
			heights.northWest(),
			heights.northEast(),
			heights.southWest(),
			heights.southEast(),
			HEIGHT_BLEND_STRENGTH * (1.0F - motionStrength * 0.75F),
			MAX_VERTICAL_STEP
		);
		lastCameraX = camera.x;
		lastCameraZ = camera.z;
		lastUpdateNs = now;
		hasLastCamera = true;
	}

	public static Snapshot current() {
		return current;
	}

	private static EdgeHeights sampleEdgeHeights(ClientLevel level, Vec3 camera, float clipDistanceBlocks, float baseMorphWidthBlocks) {
		int centerX = floor(camera.x);
		int centerZ = floor(camera.z);
		int distance = Math.max(16, Math.round(clipDistanceBlocks));
		int sideOffset = Math.max(12, Math.round(Math.max(baseMorphWidthBlocks, 32.0F) * 0.75F));
		float west = averageHeight(level, centerX - distance, centerZ, 0, sideOffset);
		float east = averageHeight(level, centerX + distance, centerZ, 0, sideOffset);
		float north = averageHeight(level, centerX, centerZ - distance, sideOffset, 0);
		float south = averageHeight(level, centerX, centerZ + distance, sideOffset, 0);
		float northWest = averageCornerHeight(level, centerX - distance, centerZ - distance, sideOffset, 1, 1);
		float northEast = averageCornerHeight(level, centerX + distance, centerZ - distance, sideOffset, -1, 1);
		float southWest = averageCornerHeight(level, centerX - distance, centerZ + distance, sideOffset, 1, -1);
		float southEast = averageCornerHeight(level, centerX + distance, centerZ + distance, sideOffset, -1, -1);
		return new EdgeHeights(west, east, north, south, northWest, northEast, southWest, southEast);
	}

	private static EdgeHeights smoothHeights(EdgeHeights previous, EdgeHeights next, float factor) {
		if (previous == null) {
			return next;
		}

		float clampedFactor = clamp(factor, 0.0F, 1.0F);
		return new EdgeHeights(
			lerp(previous.west(), next.west(), clampedFactor),
			lerp(previous.east(), next.east(), clampedFactor),
			lerp(previous.north(), next.north(), clampedFactor),
			lerp(previous.south(), next.south(), clampedFactor),
			lerp(previous.northWest(), next.northWest(), clampedFactor),
			lerp(previous.northEast(), next.northEast(), clampedFactor),
			lerp(previous.southWest(), next.southWest(), clampedFactor),
			lerp(previous.southEast(), next.southEast(), clampedFactor)
		);
	}

	private static float averageHeight(ClientLevel level, int x, int z, int xSpread, int zSpread) {
		int first = surfaceHeight(level, x, z);
		int second = surfaceHeight(level, x - xSpread, z - zSpread);
		int third = surfaceHeight(level, x + xSpread, z + zSpread);
		int fourth = surfaceHeight(level, x - xSpread / 2, z - zSpread / 2);
		int fifth = surfaceHeight(level, x + xSpread / 2, z + zSpread / 2);
		return (first + second + third + fourth + fifth) / 5.0F;
	}

	private static float averageCornerHeight(ClientLevel level, int x, int z, int spread, int inwardX, int inwardZ) {
		int first = surfaceHeight(level, x, z);
		int second = surfaceHeight(level, x + inwardX * spread, z);
		int third = surfaceHeight(level, x, z + inwardZ * spread);
		int fourth = surfaceHeight(level, x + inwardX * spread, z + inwardZ * spread);
		return (first + second + third + fourth) / 4.0F;
	}

	private static int surfaceHeight(ClientLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
	}

	private static int floor(double value) {
		int integer = (int) value;
		return value < integer ? integer - 1 : integer;
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float lerp(float start, float end, float factor) {
		return start + (end - start) * factor;
	}

	private record EdgeHeights(float west, float east, float north, float south, float northWest, float northEast, float southWest, float southEast) {
	}

	public record Snapshot(
		float cameraX,
		float cameraY,
		float cameraZ,
		float motionX,
		float motionZ,
		float motionStrength,
		float motionWidth,
		float westHeight,
		float eastHeight,
		float northHeight,
		float southHeight,
		float northWestHeight,
		float northEastHeight,
		float southWestHeight,
		float southEastHeight,
		float heightStrength,
		float maxVerticalStep
	) {
		private static Snapshot disabled() {
			return new Snapshot(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}
	}
}
