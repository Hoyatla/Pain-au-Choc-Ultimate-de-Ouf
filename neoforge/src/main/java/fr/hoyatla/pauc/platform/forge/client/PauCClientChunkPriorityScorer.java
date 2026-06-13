package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCLodGameplayProfile;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
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
	private static final double MOVEMENT_CATCHUP_SPEED_THRESHOLD = 0.16D;
	private static final int MOVEMENT_CATCHUP_TICKS = 80;
	private static final int MOVEMENT_SUSTAINED_TICKS = 8;
	private static final String HIGH_TARGET_FPS_PROPERTY = "pauc.lod.vanillaHighTargetFps";
	private static final String HIGH_TARGET_WARM_RADIUS_LEAD_PROPERTY = "pauc.lod.vanillaHighTargetWarmRadiusLeadChunks";
	private static final String FOG_PRELOAD_RADIUS_PROPERTY = "pauc.lod.fogPreloadRadiusChunks";
	private static final String FOG_PRELOAD_MIN_EXTRA_PROPERTY = "pauc.lod.fogPreloadMinExtraChunks";
	private static final String SHORT_RANGE_ROUND_HORIZON_WARMUP_PROPERTY = "pauc.lod.shortRangeRoundHorizonWarmup";
	private static double previousLookX;
	private static double previousLookZ;
	private static boolean previousLookInitialized;
	private static int snapModeRemainingTicks;
	@Nullable
	private static String previousDimensionId;
	private static int previousPlayerChunkX;
	private static int previousPlayerChunkZ;
	private static boolean previousPlayerChunkInitialized;
	private static int sustainedMovementTicks;
	private static int movementCatchupRemainingTicks;
	private static boolean lastMovementCatchup;

	private PauCClientChunkPriorityScorer() {
	}

	public static void resetRuntimeState() {
		previousLookInitialized = false;
		previousLookX = 0.0D;
		previousLookZ = 0.0D;
		snapModeRemainingTicks = 0;
		previousDimensionId = null;
		previousPlayerChunkX = 0;
		previousPlayerChunkZ = 0;
		previousPlayerChunkInitialized = false;
		sustainedMovementTicks = 0;
		movementCatchupRemainingTicks = 0;
		lastMovementCatchup = false;
	}

	@Nullable
	public static PriorityFrame capture(Minecraft minecraft, ClientLevel level, int warmMarginChunks) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return null;
		}

		ChunkPos playerChunk = player.chunkPosition();
		String dimensionId = level.dimension().location().toString();
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
		boolean movementCatchup = updateMovementCatchup(dimensionId, playerChunk, speed);
		boolean fpsFirstVanilla = isFpsFirstVanillaMode(PauCClientTargetFps.effectiveTargetFps(minecraft));
		int warmRadiusChunks = fogPreloadWarmRadius(renderDistance, warmMarginChunks, fastTravel || snapMode || movementCatchup, fpsFirstVanilla);
		lastMovementCatchup = movementCatchup;
		return new PriorityFrame(
			dimensionId,
			playerChunk.x,
			playerChunk.z,
			player.blockPosition().getY() >> 4,
			level.getMinSection(),
			level.getMaxSection() - 1,
			renderDistance,
			warmRadiusChunks,
			normalizedLookX,
			normalizedLookZ,
			motionX,
			motionZ,
			speed,
			fastTravel,
			elytraFlight,
			turnSeverity,
			snapMode,
			movementCatchup,
			fpsFirstVanilla
		);
	}

	public static boolean isMovementCatchupActive() {
		return lastMovementCatchup || movementCatchupRemainingTicks > 0;
	}

	public static boolean isFpsFirstVanillaMode() {
		return isFpsFirstVanillaMode(PauCClientTargetFps.effectiveTargetFps());
	}

	public static boolean isFpsFirstVanillaMode(int targetFps) {
		return !PauCLodShaderContext.isShaderPackInUse()
			&& PauCLodGameplayProfile.current() == PauCLodGameplayProfile.Profile.COMPETITIVE
			&& targetFps >= readInt(HIGH_TARGET_FPS_PROPERTY, 132, 90, 240);
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
		double radialDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
		double length = Math.max(1.0D, radialDistance);
		double ringScore = 1.0D - Math.min(1.0D, Math.abs(radialDistance - ringCenter) / ringWidth);
		double directionX = dx / length;
		double directionZ = dz / length;
		double rawFacingDot = directionX * frame.lookX() + directionZ * frame.lookZ();
		double rawMotionDot = directionX * frame.motionX() + directionZ * frame.motionZ();
		double facingScore = Math.max(0.0D, rawFacingDot);
		double motionScore = Math.max(0.0D, rawMotionDot);
		boolean movementCatchup = frame.movementCatchup();
		boolean priorityTravel = frame.fastTravel() || movementCatchup;
		boolean fpsFirstVanilla = frame.fpsFirstVanilla();
		double speedReference = frame.fastTravel() ? 0.8D : (movementCatchup ? 0.22D : 0.25D);
		double speedFactor = Math.min(1.0D, frame.speedBlocksPerTick() / speedReference);
		double aheadScore = frame.fastTravel()
			? (facingScore * 0.45D) + (motionScore * speedFactor * 0.55D)
			: movementCatchup
				? (facingScore * 0.50D) + (motionScore * speedFactor * 0.50D)
			: (facingScore * 0.65D) + (motionScore * speedFactor * 0.35D);
		double nearLiveBoundaryScore = chebyshevDistance >= frame.renderDistanceChunks() - 1
			? 1.0D
			: (priorityTravel ? 0.40D : fpsFirstVanilla ? 0.18D : 0.25D);
		double retainedBonus = retained ? 0.08D : 0.0D;
		double snapBoost = frame.snapMode() ? (ringScore * 0.18D) + (nearLiveBoundaryScore * 0.10D) : 0.0D;
		double movementBoost = movementCatchup ? (aheadScore * 0.12D) + (nearLiveBoundaryScore * 0.08D) : 0.0D;
		double rearPenalty = 0.0D;
		if (chebyshevDistance > frame.renderDistanceChunks() + 1) {
			rearPenalty = Math.max(0.0D, -rawFacingDot) * (priorityTravel ? 0.16D : fpsFirstVanilla ? 0.18D : 0.10D)
				+ Math.max(0.0D, -rawMotionDot) * (priorityTravel ? 0.14D : fpsFirstVanilla ? 0.15D : 0.08D);
		}
		double sidePenalty = 0.0D;
		if (fpsFirstVanilla && !priorityTravel && chebyshevDistance > frame.renderDistanceChunks() + 2) {
			double lateralExposure = 1.0D - Math.max(facingScore, motionScore);
			sidePenalty = Math.max(0.0D, lateralExposure - 0.35D) * 0.08D;
		}
		double ringWeight = fpsFirstVanilla ? 0.32D : 0.40D;
		double aheadWeight = fpsFirstVanilla ? 0.48D : 0.40D;
		double boundaryWeight = 1.0D - ringWeight - aheadWeight;
		double totalScore = (ringScore * ringWeight)
			+ (aheadScore * aheadWeight)
			+ (nearLiveBoundaryScore * boundaryWeight)
			+ retainedBonus
			+ snapBoost
			+ movementBoost
			- rearPenalty
			- sidePenalty;
		boolean ahead = rawFacingDot >= (fpsFirstVanilla ? 0.08D : 0.15D)
			|| rawMotionDot >= (fpsFirstVanilla ? 0.12D : 0.20D);
		int boundaryExtra = frame.elytraFlight() ? 4 : frame.fastTravel() ? 2 : movementCatchup ? 2 : 1;
		boolean retain = totalScore >= RETAIN_SCORE_THRESHOLD
			|| chebyshevDistance <= frame.renderDistanceChunks() + boundaryExtra
			|| (frame.snapMode() && chebyshevDistance <= frame.warmRadiusChunks())
			|| (priorityTravel && ahead && chebyshevDistance <= frame.warmRadiusChunks());
		boolean nearLiveBand = chebyshevDistance <= frame.renderDistanceChunks() + (priorityTravel ? 2 : fpsFirstVanilla ? 1 : 0);
		boolean warm = frame.snapMode()
			? totalScore >= 0.34D && chebyshevDistance <= frame.warmRadiusChunks()
			: totalScore >= (movementCatchup ? 0.38D : fpsFirstVanilla ? 0.50D : WARM_SCORE_THRESHOLD)
				&& (ahead || nearLiveBand);
		return new ChunkPriority(totalScore, chebyshevDistance, radialDistance, ahead, retain, warm);
	}

	private static boolean updateMovementCatchup(String dimensionId, ChunkPos playerChunk, double speed) {
		if (!previousPlayerChunkInitialized || !dimensionId.equals(previousDimensionId)) {
			previousDimensionId = dimensionId;
			previousPlayerChunkX = playerChunk.x;
			previousPlayerChunkZ = playerChunk.z;
			previousPlayerChunkInitialized = true;
			sustainedMovementTicks = 0;
			movementCatchupRemainingTicks = 0;
			return false;
		}

		boolean chunkChanged = playerChunk.x != previousPlayerChunkX || playerChunk.z != previousPlayerChunkZ;
		boolean moving = speed >= MOVEMENT_CATCHUP_SPEED_THRESHOLD;
		if (chunkChanged || moving) {
			sustainedMovementTicks = Math.min(MOVEMENT_SUSTAINED_TICKS, sustainedMovementTicks + 1);
		} else {
			sustainedMovementTicks = Math.max(0, sustainedMovementTicks - 1);
		}

		if (chunkChanged || sustainedMovementTicks >= MOVEMENT_SUSTAINED_TICKS) {
			movementCatchupRemainingTicks = MOVEMENT_CATCHUP_TICKS;
		} else if (movementCatchupRemainingTicks > 0) {
			movementCatchupRemainingTicks--;
		}

		previousPlayerChunkX = playerChunk.x;
		previousPlayerChunkZ = playerChunk.z;
		previousDimensionId = dimensionId;
		return movementCatchupRemainingTicks > 0;
	}

	private static int fogPreloadWarmRadius(int renderDistance, int warmMarginChunks, boolean travelFill, boolean fpsFirstVanilla) {
		int baseWarmRadius = renderDistance + warmMarginChunks;
		PauCLodRange range = PauCClientLodGovernor.currentRange();
		if (range == null || !range.enabled()) {
			return baseWarmRadius;
		}

		int roundHorizonRadius = range.roundHorizonEndChunk();
		int minimumExtra = readInt(FOG_PRELOAD_MIN_EXTRA_PROPERTY, travelFill ? 20 : 12, 0, PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS);
		if (fpsFirstVanilla) {
			minimumExtra = Math.min(minimumExtra, travelFill ? 12 : 8);
		}
		int targetRadius = Math.max(range.lodEndChunk(), renderDistance + minimumExtra);
		boolean shortRangeRoundWarmup = range.lodEndChunk() <= 64 && readBoolean(SHORT_RANGE_ROUND_HORIZON_WARMUP_PROPERTY, true);
		if (!fpsFirstVanilla && (travelFill || shortRangeRoundWarmup)) {
			targetRadius = Math.max(targetRadius, Math.min(roundHorizonRadius, range.lodEndChunk() + minimumExtra));
		}
		if (!fpsFirstVanilla && shortRangeRoundWarmup) {
			targetRadius = Math.max(targetRadius, roundHorizonRadius);
		}
		if (fpsFirstVanilla && !travelFill) {
			int lead = readInt(HIGH_TARGET_WARM_RADIUS_LEAD_PROPERTY, 12, 4, 24);
			targetRadius = Math.min(targetRadius, Math.min(roundHorizonRadius, range.lodEndChunk() + lead));
		}
		int maxConfiguredRadius = Math.max(renderDistance, roundHorizonRadius);
		int configuredRadius = readInt(
			FOG_PRELOAD_RADIUS_PROPERTY,
			fpsFirstVanilla && !travelFill ? targetRadius : Math.min(roundHorizonRadius, targetRadius),
			renderDistance,
			maxConfiguredRadius
		);
		int radiusCap = fpsFirstVanilla && !travelFill ? Math.min(roundHorizonRadius, targetRadius) : roundHorizonRadius;
		return Math.max(baseWarmRadius, Math.min(configuredRadius, radiusCap));
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

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
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
		boolean snapMode,
		boolean movementCatchup,
		boolean fpsFirstVanilla
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
