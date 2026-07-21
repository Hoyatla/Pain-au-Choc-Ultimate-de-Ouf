package fr.hoyatla.pauc.lod;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Conservative opaque-terrain occlusion culling for block entities.
 * <p>
 * This targets the common dense-pack cost where large numbers of decorative block entities are fully hidden behind
 * walls, floors, or cave terrain but still reach the render path. Like {@link PauCEntityOcclusionCulling}, correctness
 * is biased toward rendering: any uncertainty resolves to visible. Off-screen block entities (beacons/end gateways/etc.)
 * are excluded by the caller so beam-style visuals never disappear.
 */
public final class PauCBlockEntityOcclusionCulling {
	private static final String ENABLED_PROPERTY = "pauc.lod.blockEntityOcclusionCulling";
	private static final String MIN_DISTANCE_PROPERTY = "pauc.lod.blockEntityOcclusionMinDistanceBlocks";
	private static final String MAX_DISTANCE_PROPERTY = "pauc.lod.blockEntityOcclusionMaxDistanceBlocks";
	private static final String STRIDE_FRAMES_PROPERTY = "pauc.lod.blockEntityOcclusionStrideFrames";
	private static final String STEP_BLOCKS_PROPERTY = "pauc.lod.blockEntityOcclusionStepBlocks";
	private static final String MAX_TRACKED_PROPERTY = "pauc.lod.blockEntityOcclusionMaxTracked";
	private static final String EVAL_BUDGET_PROPERTY = "pauc.lod.blockEntityOcclusionEvalsPerFrame";
	private static final String MOVE_CLEAR_BLOCKS_PROPERTY = "pauc.lod.blockEntityOcclusionMoveClearBlocks";

	private static final int DEFAULT_MIN_DISTANCE_BLOCKS = 10;
	private static final int DEFAULT_MAX_DISTANCE_BLOCKS = 96;
	private static final int DEFAULT_STRIDE_FRAMES = 6;
	private static final double DEFAULT_STEP_BLOCKS = 0.5D;
	private static final int DEFAULT_MAX_TRACKED = 4096;
	private static final int DEFAULT_EVALS_PER_FRAME = 10;
	private static final int DEFAULT_MOVE_CLEAR_BLOCKS = 3;

	private static final Long2BooleanOpenHashMap OCCLUDED = new Long2BooleanOpenHashMap();
	private static final Long2LongOpenHashMap NEXT_EVAL_FRAME = new Long2LongOpenHashMap();
	private static final BlockPos.MutableBlockPos SAMPLE_POS = new BlockPos.MutableBlockPos();

	private static long observedFrameSeq = -1L;
	private static double eyeX;
	private static double eyeY;
	private static double eyeZ;
	private static int cameraBlockX = Integer.MIN_VALUE;
	private static int cameraBlockY = Integer.MIN_VALUE;
	private static int cameraBlockZ = Integer.MIN_VALUE;
	private static boolean frameValid;
	private static int culledThisFrame;
	private static int lastCulledPerFrame;
	private static int evalsThisFrame;
	private static double minDistanceSqr = DEFAULT_MIN_DISTANCE_BLOCKS * DEFAULT_MIN_DISTANCE_BLOCKS;
	private static double maxDistanceSqr = DEFAULT_MAX_DISTANCE_BLOCKS * DEFAULT_MAX_DISTANCE_BLOCKS;
	private static int strideFrames = DEFAULT_STRIDE_FRAMES;
	private static double rayStepBlocks = DEFAULT_STEP_BLOCKS;
	private static int maxTracked = DEFAULT_MAX_TRACKED;
	private static int evalBudgetPerFrame = DEFAULT_EVALS_PER_FRAME;
	private static int moveClearBlocks = DEFAULT_MOVE_CLEAR_BLOCKS;

	static {
		OCCLUDED.defaultReturnValue(false);
		NEXT_EVAL_FRAME.defaultReturnValue(Long.MIN_VALUE);
	}

	private PauCBlockEntityOcclusionCulling() {
	}

	public static boolean shouldCull(BlockEntity blockEntity) {
		if (blockEntity == null) {
			return false;
		}
		if (!fr.hoyatla.pauc.PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return false;
		}
		if (fr.hoyatla.pauc.shadercompat.PauCShaderCompat.isShadowPassActive()) {
			return false;
		}

		syncFrame();
		if (!frameValid) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Level level = blockEntity.getLevel();
		if (minecraft == null || minecraft.level == null || level != minecraft.level || blockEntity.isRemoved()) {
			return false;
		}

		BlockPos pos = blockEntity.getBlockPos();
		double centerX = pos.getX() + 0.5D;
		double centerZ = pos.getZ() + 0.5D;
		double dx = centerX - eyeX;
		double dz = centerZ - eyeZ;
		double horizontalSqr = dx * dx + dz * dz;
		if (horizontalSqr < minDistanceSqr) {
			return false;
		}
		if (horizontalSqr > maxDistanceSqr) {
			return false;
		}

		long key = pos.asLong();
		long now = observedFrameSeq;
		long nextEval = NEXT_EVAL_FRAME.get(key);
		boolean hasCached = nextEval != Long.MIN_VALUE;
		boolean occluded;
		if (hasCached && now < nextEval) {
			occluded = OCCLUDED.get(key);
		} else {
			if (evalsThisFrame >= evalBudgetPerFrame) {
				return hasCached && OCCLUDED.get(key);
			}
			evalsThisFrame++;
			occluded = computeOccluded(level, pos);
			rememberVerdict(key, occluded, now + strideFrames + Math.floorMod(Long.hashCode(key), strideFrames));
		}

		if (occluded) {
			culledThisFrame++;
		}
		return occluded;
	}

	public static String describeState() {
		return "blockEntityOcclusion[culled=" + lastCulledPerFrame + ", tracked=" + OCCLUDED.size() + "]";
	}

	private static void syncFrame() {
		long seq = PauCFrameSpikeAbsorber.frameSeq();
		if (seq == observedFrameSeq) {
			return;
		}
		observedFrameSeq = seq;
		lastCulledPerFrame = culledThisFrame;
		culledThisFrame = 0;
		evalsThisFrame = 0;
		frameValid = false;

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) {
			return;
		}
		try {
			Vec3 position = minecraft.gameRenderer.getMainCamera().getPosition();
			eyeX = position.x;
			eyeY = position.y;
			eyeZ = position.z;
		} catch (RuntimeException ignored) {
			return;
		}
		frameValid = true;
		int minDistance = readInt(MIN_DISTANCE_PROPERTY, DEFAULT_MIN_DISTANCE_BLOCKS, 2, 64);
		int maxDistance = readInt(MAX_DISTANCE_PROPERTY, DEFAULT_MAX_DISTANCE_BLOCKS, 32, 256);
		strideFrames = readInt(STRIDE_FRAMES_PROPERTY, DEFAULT_STRIDE_FRAMES, 1, 40);
		rayStepBlocks = readDouble(STEP_BLOCKS_PROPERTY, DEFAULT_STEP_BLOCKS, 0.25D, 2.0D);
		maxTracked = readInt(MAX_TRACKED_PROPERTY, DEFAULT_MAX_TRACKED, 256, 65536);
		evalBudgetPerFrame = readInt(EVAL_BUDGET_PROPERTY, DEFAULT_EVALS_PER_FRAME, 1, 128);
		moveClearBlocks = readInt(MOVE_CLEAR_BLOCKS_PROPERTY, DEFAULT_MOVE_CLEAR_BLOCKS, 1, 32);
		minDistanceSqr = minDistance * (double) minDistance;
		maxDistanceSqr = maxDistance * (double) maxDistance;

		int bx = (int) Math.floor(eyeX);
		int by = (int) Math.floor(eyeY);
		int bz = (int) Math.floor(eyeZ);
		int dxBlocks = bx - cameraBlockX;
		int dyBlocks = by - cameraBlockY;
		int dzBlocks = bz - cameraBlockZ;
		if (cameraBlockX == Integer.MIN_VALUE
			|| dxBlocks * dxBlocks + dyBlocks * dyBlocks + dzBlocks * dzBlocks >= moveClearBlocks * moveClearBlocks) {
			cameraBlockX = bx;
			cameraBlockY = by;
			cameraBlockZ = bz;
			OCCLUDED.clear();
			NEXT_EVAL_FRAME.clear();
		}
	}

	private static void rememberVerdict(long key, boolean occluded, long nextEvalFrame) {
		if (OCCLUDED.size() > maxTracked) {
			OCCLUDED.clear();
			NEXT_EVAL_FRAME.clear();
		}
		OCCLUDED.put(key, occluded);
		NEXT_EVAL_FRAME.put(key, nextEvalFrame);
	}

	private static boolean computeOccluded(Level level, BlockPos pos) {
		// Derive the sample points directly from the block position (unit cube) - avoids allocating an AABB on every
		// occlusion re-evaluation. Identical geometry: center 0.5, top maxY-0.1=+0.9, inset (max-min)*0.2=0.2.
		double x = pos.getX();
		double y = pos.getY();
		double z = pos.getZ();
		double centerX = x + 0.5D;
		double centerZ = z + 0.5D;
		double midY = y + 0.5D;
		double topY = y + 0.9D;
		double loX = x + 0.2D;
		double hiX = x + 0.8D;
		double loZ = z + 0.2D;
		double hiZ = z + 0.8D;

		return rayBlocked(level, centerX, midY, centerZ)
			&& rayBlocked(level, centerX, topY, centerZ)
			&& rayBlocked(level, loX, midY, loZ)
			&& rayBlocked(level, hiX, midY, hiZ)
			&& rayBlocked(level, loX, topY, hiZ);
	}

	private static boolean rayBlocked(Level level, double tx, double ty, double tz) {
		double dx = tx - eyeX;
		double dy = ty - eyeY;
		double dz = tz - eyeZ;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 2.0D) {
			return false;
		}

		double invDist = 1.0D / dist;
		double nx = dx * invDist;
		double ny = dy * invDist;
		double nz = dz * invDist;
		double start = 1.0D;
		double end = dist - 0.75D;
		int lastX = Integer.MIN_VALUE;
		int lastY = Integer.MIN_VALUE;
		int lastZ = Integer.MIN_VALUE;
		for (double t = start; t <= end; t += rayStepBlocks) {
			int bx = (int) Math.floor(eyeX + nx * t);
			int by = (int) Math.floor(eyeY + ny * t);
			int bz = (int) Math.floor(eyeZ + nz * t);
			if (bx == lastX && by == lastY && bz == lastZ) {
				continue;
			}
			lastX = bx;
			lastY = by;
			lastZ = bz;
			SAMPLE_POS.set(bx, by, bz);
			BlockState state = level.getBlockState(SAMPLE_POS);
			if (!state.isAir() && state.isSolidRender(level, SAMPLE_POS)) {
				return true;
			}
		}
		return false;
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = fr.hoyatla.pauc.PauCTunables.raw(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
