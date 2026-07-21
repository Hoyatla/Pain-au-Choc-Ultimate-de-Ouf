package fr.hoyatla.pauc.lod;

import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PauC's own entity occlusion culling: skips rendering (and therefore animating) entities that are fully hidden behind
 * opaque terrain — mobs in caves, behind walls, under floors. Unlike the spike absorber's far-entity dephasing
 * ({@link PauCEntityRenderBudget}), this is a steady optimization that runs every frame, because by construction it only
 * removes entities the player cannot see: there is no visible regression.
 * <p>
 * Correctness is biased hard toward rendering. An entity is culled only when <em>every</em> sample ray from the camera
 * to its bounding box is blocked by a full opaque (solid-render) block; any uncertainty (thin/diagonal wall stepped
 * over, non-opaque occluder like glass/leaves/fences, too close to evaluate) resolves to "visible". Verdicts are cached
 * and re-evaluated on a per-entity stride, and the whole cache is invalidated whenever the camera crosses into a new
 * block so a moving player never sees a hidden mob pop in late.
 * <p>
 * This is PauC-provided and self-contained — it does not depend on any culling mod being present. Distances/strides are
 * generic, not per-pack constants. Kill-switch: {@code pauc.lod.entityOcclusionCulling=false}.
 */
public final class PauCEntityOcclusionCulling {
	private static final String ENABLED_PROPERTY = "pauc.lod.entityOcclusionCulling";
	private static final String MIN_DISTANCE_PROPERTY = "pauc.lod.entityOcclusionMinDistanceBlocks";
	private static final String MAX_DISTANCE_PROPERTY = "pauc.lod.entityOcclusionMaxDistanceBlocks";
	private static final String STRIDE_FRAMES_PROPERTY = "pauc.lod.entityOcclusionStrideFrames";
	private static final String STEP_BLOCKS_PROPERTY = "pauc.lod.entityOcclusionStepBlocks";
	private static final String MAX_TRACKED_PROPERTY = "pauc.lod.entityOcclusionMaxTracked";
	private static final String EVAL_BUDGET_PROPERTY = "pauc.lod.entityOcclusionEvalsPerFrame";
	private static final String MOVE_CLEAR_BLOCKS_PROPERTY = "pauc.lod.entityOcclusionMoveClearBlocks";

	private static final int DEFAULT_MIN_DISTANCE_BLOCKS = 8;
	private static final int DEFAULT_MAX_DISTANCE_BLOCKS = 128;
	private static final int DEFAULT_STRIDE_FRAMES = 5;
	private static final double DEFAULT_STEP_BLOCKS = 0.5D;
	private static final int DEFAULT_MAX_TRACKED = 4096;
	private static final int DEFAULT_EVALS_PER_FRAME = 8;
	private static final int DEFAULT_MOVE_CLEAR_BLOCKS = 3;

	private static final Int2BooleanOpenHashMap OCCLUDED = new Int2BooleanOpenHashMap();
	private static final Int2LongOpenHashMap NEXT_EVAL_FRAME = new Int2LongOpenHashMap();
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

	private PauCEntityOcclusionCulling() {
	}

	/**
	 * @return {@code true} if this entity is fully occluded by opaque terrain and can be skipped this frame.
	 */
	public static boolean shouldCull(Entity entity) {
		if (entity == null) {
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
		if (minecraft == null || minecraft.level == null) {
			return false;
		}
		if (entity == minecraft.player || entity == minecraft.getCameraEntity()) {
			return false;
		}
		if (entity.isPassenger() || entity.isVehicle() || entity.hasCustomName()) {
			return false;
		}

		double dx = entity.getX() - eyeX;
		double dz = entity.getZ() - eyeZ;
		double horizontalSqr = dx * dx + dz * dz;
		if (horizontalSqr < minDistanceSqr) {
			return false;
		}
		if (horizontalSqr > maxDistanceSqr) {
			// Beyond our LOS budget; leave the verdict to distance culling and let it render.
			return false;
		}

		int id = entity.getId();
		long now = observedFrameSeq;
		long nextEval = NEXT_EVAL_FRAME.getOrDefault(id, Long.MIN_VALUE);
		boolean hasCached = nextEval != Long.MIN_VALUE;
		boolean occluded;
		if (hasCached && now < nextEval) {
			occluded = OCCLUDED.get(id);
		} else {
			// Hard per-frame cap on fresh raycast evaluations so occlusion can never spike with on-screen entity count
			// (e.g. a horde): entities over budget this frame keep their cached verdict, or render if never evaluated.
			if (evalsThisFrame >= evalBudgetPerFrame) {
				return hasCached && OCCLUDED.get(id);
			}
			evalsThisFrame++;
			occluded = computeOccluded(minecraft.level, entity);
			rememberVerdict(id, occluded, now + strideFrames + Math.floorMod(id, strideFrames));
		}

		if (occluded) {
			culledThisFrame++;
		}
		return occluded;
	}

	public static String describeState() {
		return "entityOcclusion[culled=" + lastCulledPerFrame + ", tracked=" + OCCLUDED.size() + "]";
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
		int maxDistance = readInt(MAX_DISTANCE_PROPERTY, DEFAULT_MAX_DISTANCE_BLOCKS, 32, 512);
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
			// Camera moved enough that occlusion may have changed: drop cached verdicts so revealed entities are
			// re-evaluated. Only on a real move (not every block step) so combat strafing does not thrash the cache.
			cameraBlockX = bx;
			cameraBlockY = by;
			cameraBlockZ = bz;
			OCCLUDED.clear();
			NEXT_EVAL_FRAME.clear();
		}
	}

	private static void rememberVerdict(int id, boolean occluded, long nextEvalFrame) {
		if (OCCLUDED.size() > maxTracked) {
			OCCLUDED.clear();
			NEXT_EVAL_FRAME.clear();
		}
		OCCLUDED.put(id, occluded);
		NEXT_EVAL_FRAME.put(id, nextEvalFrame);
	}

	private static boolean computeOccluded(Level level, Entity entity) {
		AABB box = entity.getBoundingBox();
		double midY = (box.minY + box.maxY) * 0.5D;
		double cx = (box.minX + box.maxX) * 0.5D;
		double cz = (box.minZ + box.maxZ) * 0.5D;
		// Inset the box slightly so grazing rays along a wall do not count a peeking silhouette as visible-by-edge.
		double insetX = (box.maxX - box.minX) * 0.25D;
		double insetZ = (box.maxZ - box.minZ) * 0.25D;
		double topY = box.maxY - (box.maxY - box.minY) * 0.1D;

		// If ANY of these sample rays reaches the entity unobstructed, the entity is visible -> do not cull.
		return rayBlocked(level, cx, midY, cz)
			&& rayBlocked(level, cx, topY, cz)
			&& rayBlocked(level, box.minX + insetX, midY, box.minZ + insetZ)
			&& rayBlocked(level, box.maxX - insetX, midY, box.maxZ - insetZ)
			&& rayBlocked(level, box.minX + insetX, topY, box.maxZ - insetZ);
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

		// Skip ~1 block at each end (camera head / entity's own occupied block).
		double start = 1.0D;
		double end = dist - 1.0D;
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
