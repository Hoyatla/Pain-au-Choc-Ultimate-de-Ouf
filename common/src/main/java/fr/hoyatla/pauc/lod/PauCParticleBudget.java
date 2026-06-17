package fr.hoyatla.pauc.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame particle spawn budget driven by the {@link PauCFrameSpikeAbsorber}.
 * <p>
 * Particle floods (explosion bursts, mob death/horde effects, mod VFX) are a dominant source of invisible 1%-low spikes:
 * hundreds of particles can be enqueued in a single tick, then ticked and rendered for seconds afterwards. This budget
 * caps how many <em>new</em> particles are admitted per frame while a spike is being absorbed, dropping distant and
 * overflow particles first and always protecting particles close to the camera so gameplay feedback stays intact.
 * <p>
 * When the absorber reports no pressure (the steady-state case) this never rejects anything, so healthy frames behave
 * exactly like vanilla. All ceilings scale with the measured {@link PauCFrameSpikeAbsorber#workScale()}, so the budget
 * self-calibrates per modpack / hardware rather than being a fixed per-pack constant. Kill-switch:
 * {@code pauc.lod.particleBudget=false}.
 */
public final class PauCParticleBudget {
	private static final String ENABLED_PROPERTY = "pauc.lod.particleBudget";
	private static final String NEAR_RADIUS_PROPERTY = "pauc.lod.particleBudgetNearRadiusBlocks";
	private static final String FAR_CEILING_PROPERTY = "pauc.lod.particleBudgetFarSpawnsPerFrame";
	private static final String BURST_CEILING_PROPERTY = "pauc.lod.particleBudgetBurstSpawnsPerFrame";
	private static final String FAR_KEEP_EVERY_PROPERTY = "pauc.lod.particleBudgetFarKeepEvery";
	private static final String BURST_KEEP_EVERY_PROPERTY = "pauc.lod.particleBudgetBurstKeepEvery";

	private static final int DEFAULT_NEAR_RADIUS_BLOCKS = 20;
	private static final int DEFAULT_FAR_SPAWNS_PER_FRAME = 256;
	private static final int DEFAULT_BURST_SPAWNS_PER_FRAME = 768;
	private static final int DEFAULT_FAR_KEEP_EVERY = 4;
	private static final int DEFAULT_BURST_KEEP_EVERY = 3;

	private static long observedFrameSeq = -1L;
	private static int spawnedThisFrame;
	private static int farDropCounter;
	private static int burstDropCounter;
	private static int rejectedThisFrame;
	private static int lastRejectedPerFrame;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static boolean cameraValid;

	private PauCParticleBudget() {
	}

	/**
	 * @return {@code true} if this particle spawn should be rejected (dropped before it is ever ticked or rendered).
	 */
	public static boolean shouldRejectSpawn(double x, double y, double z) {
		syncFrame();
		spawnedThisFrame++;

		// Default OFF (known-good visual baseline): dropping particles is a visible change. Opt-in only.
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
			return false;
		}
		if (!PauCFrameSpikeAbsorber.isAbsorbing()) {
			return false;
		}

		double workScale = PauCFrameSpikeAbsorber.workScale();
		boolean near = cameraValid && isNear(x, y, z);

		if (!near) {
			int farCeiling = scale(readInt(FAR_CEILING_PROPERTY, DEFAULT_FAR_SPAWNS_PER_FRAME, 16, 8192), workScale);
			if (spawnedThisFrame > farCeiling) {
				int keepEvery = readInt(FAR_KEEP_EVERY_PROPERTY, DEFAULT_FAR_KEEP_EVERY, 2, 64);
				if ((farDropCounter++ % keepEvery) != 0) {
					rejectedThisFrame++;
					return true;
				}
			}
		}

		// Hard burst cap (covers near mega-explosions / dense mod VFX) that still keeps a representative sample.
		int burstCeiling = scale(readInt(BURST_CEILING_PROPERTY, DEFAULT_BURST_SPAWNS_PER_FRAME, 64, 16384), workScale);
		if (spawnedThisFrame > burstCeiling) {
			int keepEvery = readInt(BURST_KEEP_EVERY_PROPERTY, DEFAULT_BURST_KEEP_EVERY, 2, 64);
			if ((burstDropCounter++ % keepEvery) != 0) {
				rejectedThisFrame++;
				return true;
			}
		}

		return false;
	}

	public static String describeState() {
		return "particleBudget[spawned="
			+ spawnedThisFrame
			+ ", rejected="
			+ lastRejectedPerFrame
			+ "]";
	}

	private static void syncFrame() {
		long seq = PauCFrameSpikeAbsorber.frameSeq();
		if (seq == observedFrameSeq) {
			return;
		}
		observedFrameSeq = seq;
		lastRejectedPerFrame = rejectedThisFrame;
		spawnedThisFrame = 0;
		farDropCounter = 0;
		burstDropCounter = 0;
		rejectedThisFrame = 0;
		refreshCamera();
	}

	private static void refreshCamera() {
		cameraValid = false;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) {
			return;
		}
		try {
			Vec3 position = minecraft.gameRenderer.getMainCamera().getPosition();
			cameraX = position.x;
			cameraY = position.y;
			cameraZ = position.z;
			cameraValid = true;
		} catch (RuntimeException ignored) {
			cameraValid = false;
		}
	}

	private static boolean isNear(double x, double y, double z) {
		int nearRadius = readInt(NEAR_RADIUS_PROPERTY, DEFAULT_NEAR_RADIUS_BLOCKS, 4, 128);
		double dx = x - cameraX;
		double dy = y - cameraY;
		double dz = z - cameraZ;
		double nearSqr = (double) nearRadius * nearRadius;
		return dx * dx + dy * dy + dz * dz <= nearSqr;
	}

	private static int scale(int base, double workScale) {
		return Math.max(8, (int) Math.round(base * workScale));
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
}
