package fr.hoyatla.pauc.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class PauCLodSeamState {
	private static final String SHADER_SAMPLING_ENABLED_PROPERTY = "pauc.lod.shaderSeamHeightSampling";
	private static final String VANILLA_BATCH_SAMPLES_PROPERTY = "pauc.lod.cuda.vanillaSeamBatchSamples";
	private static final String SHADER_BATCH_SAMPLES_PROPERTY = "pauc.lod.cuda.shaderSeamBatchSamples";
	private static final String VANILLA_SAMPLE_INTERVAL_MS_PROPERTY = "pauc.lod.cuda.vanillaSeamSampleIntervalMs";
	private static final String SHADER_SAMPLE_INTERVAL_MS_PROPERTY = "pauc.lod.cuda.shaderSeamSampleIntervalMs";
	private static final float HEIGHT_BLEND_STRENGTH = 0.10F;
	private static final float MAX_VERTICAL_STEP = 4.0F;
	private static final float MOTION_WIDTH_BLOCKS = 48.0F;
	private static final float MOTION_STRENGTH_SCALE = 0.06F;
	private static final int SEAM_HEIGHT_COUNT = 8;
	private static Snapshot current = Snapshot.disabled();
	private static EdgeHeights lastHeights;
	private static double lastCameraX;
	private static double lastCameraZ;
	private static long lastUpdateNs;
	private static long lastSampleNs;
	private static boolean hasLastCamera;
	private static float lastClipDistanceBlocks;
	private static float lastMorphWidthBlocks;
	private static int[] reusableSums = new int[0];
	private static int[] reusableCounts = new int[0];
	private static float[] reusableSampleHeights = new float[0];
	private static float[] reusableFeatureHeights = new float[0];

	private PauCLodSeamState() {
	}

	public static void update(float clipDistanceBlocks, float baseMorphWidthBlocks) {
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		if (clipDistanceBlocks <= 0.0F || shaderActive && !readBoolean(SHADER_SAMPLING_ENABLED_PROPERTY, true)) {
			current = Snapshot.disabled();
			hasLastCamera = false;
			lastHeights = null;
			lastSampleNs = 0L;
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null || minecraft.level == null) {
			current = Snapshot.disabled();
			hasLastCamera = false;
			lastHeights = null;
			lastSampleNs = 0L;
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

		boolean refreshHeights = shouldRefreshHeights(now, clipDistanceBlocks, baseMorphWidthBlocks, shaderActive);
		EdgeHeights sampledHeights = refreshHeights
			? sampleEdgeHeights(minecraft.level, camera, clipDistanceBlocks, baseMorphWidthBlocks, shaderActive)
			: lastHeights;
		if (sampledHeights == null) {
			sampledHeights = sampleEdgeHeights(minecraft.level, camera, clipDistanceBlocks, baseMorphWidthBlocks, shaderActive);
			refreshHeights = true;
		}
		if (refreshHeights) {
			lastSampleNs = now;
		}
		EdgeHeights heights = smoothHeights(lastHeights, sampledHeights, hasLastCamera ? 0.18F : 1.0F);
		lastHeights = heights;
		lastClipDistanceBlocks = clipDistanceBlocks;
		lastMorphWidthBlocks = baseMorphWidthBlocks;
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

	private static boolean shouldRefreshHeights(long now, float clipDistanceBlocks, float baseMorphWidthBlocks, boolean shaderActive) {
		if (lastHeights == null || lastSampleNs <= 0L) {
			return true;
		}
		if (Math.abs(clipDistanceBlocks - lastClipDistanceBlocks) > 4.0F || Math.abs(baseMorphWidthBlocks - lastMorphWidthBlocks) > 4.0F) {
			return true;
		}

		long intervalNs = sampleIntervalMillis(shaderActive) * 1_000_000L;
		return intervalNs <= 0L || now - lastSampleNs >= intervalNs;
	}

	private static EdgeHeights sampleEdgeHeights(ClientLevel level, Vec3 camera, float clipDistanceBlocks, float baseMorphWidthBlocks, boolean shaderActive) {
		int centerX = floor(camera.x);
		int centerZ = floor(camera.z);
		int distance = Math.max(16, Math.round(clipDistanceBlocks));
		int sideOffset = Math.max(12, Math.round(Math.max(baseMorphWidthBlocks, 32.0F) * 0.75F));
		int samplesPerFeature = Math.max(1, terrainBatchSamples(shaderActive) / SEAM_HEIGHT_COUNT);
		int sampleCount = samplesPerFeature * SEAM_HEIGHT_COUNT;
		int[] sums = reusableSums(sampleCount);
		int[] counts = reusableCounts(sampleCount);
		fillBatchedHeightSamples(level, centerX, centerZ, distance, sideOffset, samplesPerFeature, sums, counts);

		long cpuAverageStarted = System.nanoTime();
		float[] sampleHeights = averageHeights(sums, counts, reusableSampleHeights(sampleCount));
		float[] cpuHeights = aggregateFeatureHeights(sampleHeights, samplesPerFeature, reusableFeatureHeights());
		long cpuAverageMicros = Math.max(1L, (System.nanoTime() - cpuAverageStarted) / 1_000L);
		PauCLodCudaBridge.Result cudaResult = PauCLodCudaBridge.averageSeamHeights(sums, counts, samplesPerFeature, cpuHeights, cpuAverageMicros);
		float[] heights = cudaResult.heights();
		return EdgeHeights.from(heights != null && heights.length == SEAM_HEIGHT_COUNT ? heights : cpuHeights);
	}

	private static void fillBatchedHeightSamples(ClientLevel level, int centerX, int centerZ, int distance, int sideOffset, int samplesPerFeature, int[] sums, int[] counts) {
		int sideSpread = Math.max(4, sideOffset / Math.max(2, samplesPerFeature));
		for (int feature = 0; feature < SEAM_HEIGHT_COUNT; feature++) {
			for (int sample = 0; sample < samplesPerFeature; sample++) {
				int index = feature * samplesPerFeature + sample;
				float factor = samplesPerFeature == 1 ? 0.5F : sample / (float) (samplesPerFeature - 1);
				int along = Math.round(lerp(-sideOffset, sideOffset, factor));
				int inward = Math.round(lerp(0.0F, sideOffset, factor));
				switch (feature) {
					case 0 -> {
						sums[index] = summedHeight(level, centerX - distance, centerZ + along, 0, sideSpread);
						counts[index] = 5;
					}
					case 1 -> {
						sums[index] = summedHeight(level, centerX + distance, centerZ + along, 0, sideSpread);
						counts[index] = 5;
					}
					case 2 -> {
						sums[index] = summedHeight(level, centerX + along, centerZ - distance, sideSpread, 0);
						counts[index] = 5;
					}
					case 3 -> {
						sums[index] = summedHeight(level, centerX + along, centerZ + distance, sideSpread, 0);
						counts[index] = 5;
					}
					case 4 -> {
						sums[index] = summedCornerHeight(level, centerX - distance + inward, centerZ - distance + inward, sideSpread, 1, 1);
						counts[index] = 4;
					}
					case 5 -> {
						sums[index] = summedCornerHeight(level, centerX + distance - inward, centerZ - distance + inward, sideSpread, -1, 1);
						counts[index] = 4;
					}
					case 6 -> {
						sums[index] = summedCornerHeight(level, centerX - distance + inward, centerZ + distance - inward, sideSpread, 1, -1);
						counts[index] = 4;
					}
					case 7 -> {
						sums[index] = summedCornerHeight(level, centerX + distance - inward, centerZ + distance - inward, sideSpread, -1, -1);
						counts[index] = 4;
					}
					default -> throw new IllegalStateException("Unexpected seam feature " + feature);
				}
			}
		}
	}

	private static float[] aggregateFeatureHeights(float[] samples, int samplesPerFeature, float[] heights) {
		for (int feature = 0; feature < SEAM_HEIGHT_COUNT; feature++) {
			float total = 0.0F;
			int valid = 0;
			int offset = feature * samplesPerFeature;
			for (int sample = 0; sample < samplesPerFeature; sample++) {
				float value = samples[offset + sample];
				if (Float.isFinite(value)) {
					total += value;
					valid++;
				}
			}
			heights[feature] = valid <= 0 ? 0.0F : total / valid;
		}
		return heights;
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

	private static int summedHeight(ClientLevel level, int x, int z, int xSpread, int zSpread) {
		int first = surfaceHeight(level, x, z);
		int second = surfaceHeight(level, x - xSpread, z - zSpread);
		int third = surfaceHeight(level, x + xSpread, z + zSpread);
		int fourth = surfaceHeight(level, x - xSpread / 2, z - zSpread / 2);
		int fifth = surfaceHeight(level, x + xSpread / 2, z + zSpread / 2);
		return first + second + third + fourth + fifth;
	}

	private static int summedCornerHeight(ClientLevel level, int x, int z, int spread, int inwardX, int inwardZ) {
		int first = surfaceHeight(level, x, z);
		int second = surfaceHeight(level, x + inwardX * spread, z);
		int third = surfaceHeight(level, x, z + inwardZ * spread);
		int fourth = surfaceHeight(level, x + inwardX * spread, z + inwardZ * spread);
		return first + second + third + fourth;
	}

	private static float[] averageHeights(int[] sums, int[] counts, float[] heights) {
		for (int index = 0; index < sums.length; index++) {
			heights[index] = counts[index] <= 0 ? 0.0F : sums[index] / (float) counts[index];
		}
		return heights;
	}

	private static int[] reusableSums(int length) {
		if (reusableSums.length != length) {
			reusableSums = new int[length];
		}
		return reusableSums;
	}

	private static int[] reusableCounts(int length) {
		if (reusableCounts.length != length) {
			reusableCounts = new int[length];
		}
		return reusableCounts;
	}

	private static float[] reusableSampleHeights(int length) {
		if (reusableSampleHeights.length != length) {
			reusableSampleHeights = new float[length];
		}
		return reusableSampleHeights;
	}

	private static float[] reusableFeatureHeights() {
		if (reusableFeatureHeights.length != SEAM_HEIGHT_COUNT) {
			reusableFeatureHeights = new float[SEAM_HEIGHT_COUNT];
		}
		return reusableFeatureHeights;
	}

	private static int terrainBatchSamples(boolean shaderActive) {
		int requested = shaderActive
			? readInt(SHADER_BATCH_SAMPLES_PROPERTY, defaultShaderBatchSamples(), SEAM_HEIGHT_COUNT, 1024)
			: readInt(VANILLA_BATCH_SAMPLES_PROPERTY, 192, SEAM_HEIGHT_COUNT, 1024);
		int samplesPerFeature = Math.max(1, requested / SEAM_HEIGHT_COUNT);
		return samplesPerFeature * SEAM_HEIGHT_COUNT;
	}

	private static int defaultShaderBatchSamples() {
		return switch (PauCLodShaderRuntime.pressure()) {
			case RELIEF -> 384;
			case BALANCED -> 256;
			case HEADROOM -> 192;
			default -> 256;
		};
	}

	private static long sampleIntervalMillis(boolean shaderActive) {
		if (!shaderActive) {
			return readLong(VANILLA_SAMPLE_INTERVAL_MS_PROPERTY, 50L, 0L, 5_000L);
		}

		long fallback = switch (PauCLodShaderRuntime.pressure()) {
			case RELIEF -> 260L;
			case BALANCED -> 160L;
			case HEADROOM -> 90L;
			default -> 180L;
		};
		return readLong(SHADER_SAMPLE_INTERVAL_MS_PROPERTY, fallback, 0L, 5_000L);
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

	private static long readLong(String key, long fallback, long min, long max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Long.parseLong(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private record EdgeHeights(float west, float east, float north, float south, float northWest, float northEast, float southWest, float southEast) {
		private static EdgeHeights from(float[] heights) {
			return new EdgeHeights(heights[0], heights[1], heights[2], heights[3], heights[4], heights[5], heights[6], heights[7]);
		}
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
