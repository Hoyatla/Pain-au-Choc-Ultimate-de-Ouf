package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame block-entity render budget driven by the {@link PauCFrameSpikeAbsorber}.
 * <p>
 * Block-entity rendering is the single largest client cost in dense packs (Lost Cities-style dimensions, decoration-heavy
 * modpacks): observed bursts of &gt;13000 block entities rendered in one frame. Like the far-entity animation-LOD
 * ({@link PauCEntityRenderBudget}), this dephases the rendering of small, distant block entities <em>only while a frame
 * spike is being absorbed</em>: each far block entity is drawn on a rotating subset of frames keyed by its position, so a
 * 13k-BE burst is thinned proportionally to the measured pressure while no single block entity is consistently missing.
 * <p>
 * Conservative by design — zero effect on healthy frames, never touches block entities near the camera or during the
 * shadow pass, and beam/off-screen block entities (beacons, end gateways, …) are excluded by the caller. Thresholds
 * derive from the measured block-entity cull distance and absorber pressure, not a per-pack constant. Kill-switch:
 * {@code pauc.lod.blockEntityRenderBudget=false}.
 */
public final class PauCBlockEntityRenderBudget {
	private static final String ENABLED_PROPERTY = "pauc.lod.blockEntityRenderBudget";
	private static final String FAR_FRACTION_PROPERTY = "pauc.lod.blockEntityRenderBudgetFarFraction";
	private static final String MAX_STRIDE_PROPERTY = "pauc.lod.blockEntityRenderBudgetMaxStride";

	private static final double DEFAULT_FAR_FRACTION = 0.82D;
	private static final int DEFAULT_MAX_STRIDE = 3;

	private static long observedFrameSeq = -1L;
	private static double cameraX;
	private static double cameraZ;
	private static double farThresholdSqr;
	private static boolean frameValid;
	private static int deferredThisFrame;
	private static int lastDeferredPerFrame;

	private PauCBlockEntityRenderBudget() {
	}

	/**
	 * @return {@code true} if this block entity's render should be deferred (skipped) for the current frame.
	 */
	public static boolean shouldDeferRender(BlockEntity blockEntity) {
		if (blockEntity == null) {
			return false;
		}
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			return false;
		}
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return false;
		}

		syncFrame();
		if (!frameValid) {
			return false;
		}

		BlockPos pos = blockEntity.getBlockPos();
		double dx = (pos.getX() + 0.5D) - cameraX;
		double dz = (pos.getZ() + 0.5D) - cameraZ;
		if (dx * dx + dz * dz < farThresholdSqr) {
			return false;
		}

		int stride = activeStride();
		if (stride <= 1) {
			return false;
		}

		// Dephase by a hash of the block position so the rendered subset rotates each frame and neighbouring block
		// entities fall on different frames (no whole-region blink).
		long hash = mix(pos.asLong());
		long phase = Math.floorMod(hash + observedFrameSeq, stride);
		if (phase != 0L) {
			deferredThisFrame++;
			return true;
		}
		return false;
	}

	public static String describeState() {
		return "blockEntityRenderBudget[deferred=" + lastDeferredPerFrame + "]";
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
		double cullDistance = PauCLodRenderCulling.blockEntityRenderMaxDistanceBlocks();
		double farThreshold = cullDistance * farFraction;
		farThresholdSqr = farThreshold * farThreshold;
		frameValid = true;
	}

	private static long mix(long value) {
		// SplitMix64-style finalizer for good dephasing distribution across adjacent positions.
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static int activeStride() {
		int maxStride = readInt(MAX_STRIDE_PROPERTY, DEFAULT_MAX_STRIDE, 1, 8);
		int tier = Math.max(PauCVillagePerformanceDiagnostics.projectedAnimationLodTier(), PauCVillagePerformanceDiagnostics.projectedScenePressureTier());
		if (PauCFrameSpikeAbsorber.isAbsorbing() && PauCFrameSpikeAbsorber.pressure01() >= 0.80D) {
			tier = Math.max(tier, 3);
		}
		if (tier >= 3) {
			return Math.min(maxStride, 2);
		}
		return 1;
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
