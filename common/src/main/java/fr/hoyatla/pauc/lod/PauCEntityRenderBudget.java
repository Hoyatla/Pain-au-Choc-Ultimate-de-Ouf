package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame entity render budget (animation-LOD) driven by the {@link PauCFrameSpikeAbsorber}.
 * <p>
 * Rendering and animating every entity every frame is a major 1%-low contributor when a horde, raid, or dense modded
 * mob crowd is on screen. This budget <em>dephases</em> the rendering of small, distant entities while a frame spike is
 * being absorbed: each far entity is rendered on a rotating subset of frames keyed by its entity id, so per-frame entity
 * render/animation cost (a skipped entity is neither animated nor drawn that frame) is cut proportionally to the
 * measured pressure, while no single entity is consistently missing.
 * <p>
 * It is deliberately conservative to avoid any visible regression:
 * <ul>
 *   <li>It only engages while the absorber reports a real, measured spike — steady frames are untouched (vanilla).</li>
 *   <li>It never dephases near entities, the player/camera, vehicles/passengers, named entities, or large entities
 *       (bosses), which would be noticeable; only tiny far mobs are thinned, where a one-frame gap is imperceptible.</li>
 *   <li>It never runs during the shadow pass.</li>
 * </ul>
 * Thresholds derive from the measured entity cull distance and absorber pressure, never a per-pack constant. Kill-switch:
 * {@code pauc.lod.entityRenderBudget=false}.
 */
public final class PauCEntityRenderBudget {
	private static final String ENABLED_PROPERTY = "pauc.lod.entityRenderBudget";
	private static final String FAR_FRACTION_PROPERTY = "pauc.lod.entityRenderBudgetFarFraction";
	private static final String MAX_STRIDE_PROPERTY = "pauc.lod.entityRenderBudgetMaxStride";
	private static final String LARGE_ENTITY_SIZE_PROPERTY = "pauc.lod.entityRenderBudgetLargeSizeBlocks";

	private static final double DEFAULT_FAR_FRACTION = 0.6D;
	private static final int DEFAULT_MAX_STRIDE = 3;
	private static final double DEFAULT_LARGE_ENTITY_SIZE_BLOCKS = 3.0D;

	private static long observedFrameSeq = -1L;
	private static double cameraX;
	private static double cameraZ;
	private static double farThresholdSqr;
	private static boolean frameValid;
	private static int deferredThisFrame;
	private static int lastDeferredPerFrame;

	private PauCEntityRenderBudget() {
	}

	/**
	 * @return {@code true} if this entity's render should be deferred (skipped) for the current frame.
	 */
	public static boolean shouldDeferEntityRender(Entity entity) {
		if (entity == null) {
			return false;
		}
		// Default OFF: per-frame dephasing of visible far entities strobes during sustained pressure (hordes), which
		// reads as jank. Kept as opt-in. Hostile-mob visibility is gameplay-critical, so entities are otherwise left to
		// the (invisible) occlusion cull and the existing distance cull only.
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
			return false;
		}
		if (!PauCFrameSpikeAbsorber.isAbsorbing()) {
			return false;
		}
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return false;
		}

		syncFrame();
		if (!frameValid) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return false;
		}
		if (entity == minecraft.player || entity == minecraft.getCameraEntity()) {
			return false;
		}
		if (entity.isPassenger() || entity.isVehicle() || entity.hasCustomName()) {
			return false;
		}
		if (isLargeEntity(entity)) {
			return false;
		}

		double dx = entity.getX() - cameraX;
		double dz = entity.getZ() - cameraZ;
		if (dx * dx + dz * dz < farThresholdSqr) {
			return false;
		}

		int maxStride = readInt(MAX_STRIDE_PROPERTY, DEFAULT_MAX_STRIDE, 1, 8);
		int stride = 1 + (int) Math.round(PauCFrameSpikeAbsorber.pressure01() * (maxStride - 1));
		if (stride <= 1) {
			return false;
		}

		// Dephase by entity id so the rendered subset rotates each frame; no single far entity stays hidden.
		long phase = Math.floorMod((long) entity.getId() + observedFrameSeq, stride);
		if (phase != 0L) {
			deferredThisFrame++;
			return true;
		}
		return false;
	}

	public static String describeState() {
		return "entityRenderBudget[deferred=" + lastDeferredPerFrame + "]";
	}

	private static void syncFrame() {
		long seq = PauCFrameSpikeAbsorber.frameSeq();
		if (seq == observedFrameSeq) {
			return;
		}
		observedFrameSeq = seq;
		lastDeferredPerFrame = deferredThisFrame;
		deferredThisFrame = 0;
		frameValid = false;

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) {
			return;
		}
		try {
			Vec3 position = minecraft.gameRenderer.getMainCamera().getPosition();
			cameraX = position.x;
			cameraZ = position.z;
		} catch (RuntimeException ignored) {
			return;
		}

		double farFraction = readDouble(FAR_FRACTION_PROPERTY, DEFAULT_FAR_FRACTION, 0.2D, 0.95D);
		double cullDistance = PauCLodRenderCulling.entityRenderMaxDistanceBlocks();
		double farThreshold = cullDistance * farFraction;
		farThresholdSqr = farThreshold * farThreshold;
		frameValid = true;
	}

	private static boolean isLargeEntity(Entity entity) {
		double large = readDouble(LARGE_ENTITY_SIZE_PROPERTY, DEFAULT_LARGE_ENTITY_SIZE_BLOCKS, 1.0D, 32.0D);
		AABB box = entity.getBoundingBox();
		double width = Math.max(box.getXsize(), box.getZsize());
		return width >= large || box.getYsize() >= large;
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

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
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
