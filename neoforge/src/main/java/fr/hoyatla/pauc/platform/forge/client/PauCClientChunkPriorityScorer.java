package fr.hoyatla.pauc.platform.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public final class PauCClientChunkPriorityScorer {
	private static final double RETAIN_SCORE_THRESHOLD = 0.32D;
	private static final double WARM_SCORE_THRESHOLD = 0.45D;
	private static final double SNAP_DOT_THRESHOLD = 0.55D;
	private static final int SNAP_MODE_TICKS = 14;
	private static double previousLookX;
	private static double previousLookZ;
	private static boolean previousLookInitialized;
	private static int snapModeRemainingTicks;

	private PauCClientChunkPriorityScorer() {
	}

	public static void resetRuntimeState() {
		previousLookInitialized = false;
		previousLookX = 0.0D;
		previousLookZ = 0.0D;
		snapModeRemainingTicks = 0;
	}

	@Nullable
	public static PriorityFrame capture(Minecraft minecraft, ClientLevel level, int warmMarginChunks) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return null;
		}

		ChunkPos playerChunk = player.chunkPosition();
		Vec3 look = player.getLookAngle();
		Vec3 motion = player.getDeltaMovement();
		double speed = motion.horizontalDistance();
		double motionX = speed > 1.0E-4D ? motion.x / speed : look.x;
		double motionZ = speed > 1.0E-4D ? motion.z / speed : look.z;
		double lookLength = Math.max(1.0E-4D, Math.sqrt(look.x * look.x + look.z * look.z));
		double normalizedLookX = look.x / lookLength;
		double normalizedLookZ = look.z / lookLength;
		double turnSeverity = 0.0D;
		if (previousLookInitialized) {
			double dot = (previousLookX * normalizedLookX) + (previousLookZ * normalizedLookZ);
			turnSeverity = 1.0D - Math.max(-1.0D, Math.min(1.0D, dot));
		}
		previousLookX = normalizedLookX;
		previousLookZ = normalizedLookZ;
		previousLookInitialized = true;
		if (turnSeverity > SNAP_DOT_THRESHOLD) {
			snapModeRemainingTicks = SNAP_MODE_TICKS;
		} else if (snapModeRemainingTicks > 0) {
			snapModeRemainingTicks--;
		}

		int renderDistance = minecraft.options.getEffectiveRenderDistance();
		boolean elytraFlight = player.isFallFlying();
		boolean fastTravel = elytraFlight || speed >= 0.55D;
		boolean snapMode = snapModeRemainingTicks > 0;
		return new PriorityFrame(
			level.dimension().location().toString(),
			playerChunk.x,
			playerChunk.z,
			player.blockPosition().getY() >> 4,
			level.getMinSection(),
			level.getMaxSection() - 1,
			renderDistance,
			renderDistance + warmMarginChunks,
			normalizedLookX,
			normalizedLookZ,
			motionX,
			motionZ,
			speed,
			fastTravel,
			elytraFlight,
			turnSeverity,
			snapMode
		);
	}

	public static ChunkPriority score(PriorityFrame frame, int chunkX, int chunkZ, boolean retained) {
		int dx = chunkX - frame.playerChunkX();
		int dz = chunkZ - frame.playerChunkZ();
		int chebyshevDistance = Math.max(Math.abs(dx), Math.abs(dz));
		if (chebyshevDistance > frame.warmRadiusChunks()) {
			return new ChunkPriority(Double.NEGATIVE_INFINITY, chebyshevDistance, Math.sqrt((double) dx * dx + (double) dz * dz), false, false, false);
		}

		double ringCenter = frame.renderDistanceChunks() + 0.5D;
		double ringWidth = Math.max(1.0D, frame.warmRadiusChunks() - frame.renderDistanceChunks() + 1.0D);
		double length = Math.max(1.0D, Math.sqrt((double) dx * dx + (double) dz * dz));
		double radialDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
		double ringScore = 1.0D - Math.min(1.0D, Math.abs(radialDistance - ringCenter) / ringWidth);
		double directionX = dx / length;
		double directionZ = dz / length;
		double facingScore = Math.max(0.0D, directionX * frame.lookX() + directionZ * frame.lookZ());
		double motionScore = Math.max(0.0D, directionX * frame.motionX() + directionZ * frame.motionZ());
		double speedFactor = Math.min(1.0D, frame.speedBlocksPerTick() / (frame.fastTravel() ? 0.8D : 0.25D));
		double aheadScore = frame.fastTravel()
			? (facingScore * 0.45D) + (motionScore * speedFactor * 0.55D)
			: (facingScore * 0.65D) + (motionScore * speedFactor * 0.35D);
		double nearLiveBoundaryScore = chebyshevDistance >= frame.renderDistanceChunks() - 1 ? 1.0D : (frame.fastTravel() ? 0.40D : 0.25D);
		double retainedBonus = retained ? 0.08D : 0.0D;
		double snapBoost = frame.snapMode() ? (ringScore * 0.18D) + (nearLiveBoundaryScore * 0.10D) : 0.0D;
		double totalScore = (ringScore * 0.40D) + (aheadScore * 0.40D) + (nearLiveBoundaryScore * 0.20D) + retainedBonus + snapBoost;
		boolean ahead = facingScore >= 0.15D || motionScore >= 0.20D;
		boolean retain = totalScore >= RETAIN_SCORE_THRESHOLD
			|| chebyshevDistance <= frame.renderDistanceChunks() + (frame.elytraFlight() ? 4 : frame.fastTravel() ? 2 : 1)
			|| (frame.snapMode() && chebyshevDistance <= frame.warmRadiusChunks())
			|| (frame.fastTravel() && ahead && chebyshevDistance <= frame.warmRadiusChunks());
		boolean warm = frame.snapMode()
			? totalScore >= 0.34D && chebyshevDistance <= frame.warmRadiusChunks()
			: totalScore >= WARM_SCORE_THRESHOLD && (ahead || chebyshevDistance <= frame.renderDistanceChunks() + (frame.fastTravel() ? 2 : 0));
		return new ChunkPriority(totalScore, chebyshevDistance, radialDistance, ahead, retain, warm);
	}

	public record PriorityFrame(
		String dimensionId,
		int playerChunkX,
		int playerChunkZ,
		int playerSectionY,
		int minSectionY,
		int maxSectionY,
		int renderDistanceChunks,
		int warmRadiusChunks,
		double lookX,
		double lookZ,
		double motionX,
		double motionZ,
		double speedBlocksPerTick,
		boolean fastTravel,
		boolean elytraFlight,
		double turnSeverity,
		boolean snapMode
	) {
	}

	public record ChunkPriority(
		double score,
		int chebyshevDistance,
		double radialDistance,
		boolean ahead,
		boolean shouldRetain,
		boolean shouldWarm
	) {
	}
}
