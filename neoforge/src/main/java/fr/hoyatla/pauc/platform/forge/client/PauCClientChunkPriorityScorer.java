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
	private static final double MOVEMENT_CATCHUP_SPEED_THRESHOLD = 0.10D;
	private static final int MOVEMENT_CATCHUP_TICKS = 120;
	private static final int MOVEMENT_SUSTAINED_TICKS = 6;
	private static final String MOVEMENT_CATCHUP_IDLE_RELEASE_STEP_PROPERTY = "pauc.lod.movementCatchupIdleReleaseStep";
	private static final String MOVEMENT_CATCHUP_IDLE_MAX_QUEUE_PRESSURE_PROPERTY = "pauc.lod.movementCatchupIdleMaxQueuePressure";
	private static final String MOVEMENT_CATCHUP_IDLE_MAX_PENDING_CHUNKS_PROPERTY = "pauc.lod.movementCatchupIdleMaxPendingChunks";
	private static final String HIGH_TARGET_FPS_PROPERTY = "pauc.lod.vanillaHighTargetFps";
	private static final String HIGH_TARGET_WARM_RADIUS_LEAD_PROPERTY = "pauc.lod.vanillaHighTargetWarmRadiusLeadChunks";
	private static final String HIGH_TARGET_LOOKAHEAD_BONUS_PROPERTY = "pauc.lod.vanillaHighTargetLookaheadBonusChunks";
	private static final String FOG_PRELOAD_RADIUS_PROPERTY = "pauc.lod.fogPreloadRadiusChunks";
	private static final String FOG_PRELOAD_MIN_EXTRA_PROPERTY = "pauc.lod.fogPreloadMinExtraChunks";
	private static final String SHORT_RANGE_ROUND_HORIZON_WARMUP_PROPERTY = "pauc.lod.shortRangeRoundHorizonWarmup";
	private static final String VANILLA_SEAL_RING_CHUNKS_PROPERTY = "pauc.lod.vanillaSealRingChunks";
	private static final String VANILLA_SEAL_TRAVEL_RING_CHUNKS_PROPERTY = "pauc.lod.vanillaSealTravelRingChunks";
	private static final String HIGH_TARGET_WARM_BEYOND_SLIDER_PROPERTY = "pauc.lod.vanillaHighTargetWarmBeyondSlider";
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
		boolean fpsFirstVanilla = isFpsFirstVanillaMode(minecraft, PauCClientTargetFps.effectiveTargetFps(minecraft));
		int warmRadiusChunks = fogPreloadWarmRadius(renderDistance, warmMarginChunks, fastTravel || snapMode || movementCatchup, fpsFirstVanilla);
		LookaheadCenter lookaheadCenter = computeLookaheadCenter(
			playerChunk,
			warmRadiusChunks,
			renderDistance,
			normalizedLookX,
			normalizedLookZ,
			motionX,
			motionZ,
			speed,
			turnSeverity,
			fastTravel,
			snapMode,
			movementCatchup,
			fpsFirstVanilla
		);
		lastMovementCatchup = movementCatchup;
		return new PriorityFrame(
			dimensionId,
			playerChunk.x,
			playerChunk.z,
			lookaheadCenter.chunkX(),
			lookaheadCenter.chunkZ(),
			player.blockPosition().getY() >> 4,
			level.getMinSection(),
			level.getMaxSection() - 1,
			renderDistance,
			warmRadiusChunks,
			lookaheadCenter.lookaheadChunks(),
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
		return isFpsFirstVanillaMode(Minecraft.getInstance(), PauCClientTargetFps.effectiveTargetFps());
	}

	public static boolean isFpsFirstVanillaMode(int targetFps) {
		return isFpsFirstVanillaMode(Minecraft.getInstance(), targetFps);
	}

	private static boolean isFpsFirstVanillaMode(Minecraft minecraft, int targetFps) {
		PauCLodGameplayProfile.Profile profile = PauCLodGameplayProfile.current();
		int threshold = readInt(HIGH_TARGET_FPS_PROPERTY, profile == PauCLodGameplayProfile.Profile.SHOOTER ? 120 : 132, 90, 240);
		PauCPlayerVideoSettings.Snapshot playerVideo = PauCPlayerVideoSettings.capture(minecraft);
		boolean highFpsIntent = playerVideo.available()
			? playerVideo.fpsUnlimited() || playerVideo.fpsLimit() >= threshold
			: targetFps >= threshold;
		return !PauCLodShaderContext.isShaderPackInUse()
			&& (profile == PauCLodGameplayProfile.Profile.SHOOTER || profile == PauCLodGameplayProfile.Profile.COMPETITIVE)
			&& highFpsIntent;
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
		int projectedDx = chunkX - frame.priorityCenterChunkX();
		int projectedDz = chunkZ - frame.priorityCenterChunkZ();
		double projectedRadialDistance = Math.sqrt((double) projectedDx * projectedDx + (double) projectedDz * projectedDz);
		double length = Math.max(1.0D, radialDistance);
		double ringScore = 1.0D - Math.min(1.0D, Math.abs(radialDistance - ringCenter) / ringWidth);
		double projectedRingScore = frame.lookaheadChunks() > 0
			? 1.0D - Math.min(1.0D, Math.abs(projectedRadialDistance - ringCenter) / ringWidth)
			: ringScore;
		ringScore = Math.max(ringScore, projectedRingScore);
		double directionX = dx / length;
		double directionZ = dz / length;
		double rawFacingDot = directionX * frame.lookX() + directionZ * frame.lookZ();
		double rawMotionDot = directionX * frame.motionX() + directionZ * frame.motionZ();
		double facingScore = Math.max(0.0D, rawFacingDot);
		double motionScore = Math.max(0.0D, rawMotionDot);
		boolean movementCatchup = frame.movementCatchup();
		boolean priorityTravel = frame.fastTravel() || movementCatchup;
		boolean fpsFirstVanilla = frame.fpsFirstVanilla();
		int sealRingChunks = vanillaSealRingChunks(priorityTravel);
		boolean sealRing = chebyshevDistance > frame.renderDistanceChunks()
			&& chebyshevDistance <= frame.renderDistanceChunks() + sealRingChunks;
		if (sealRing) {
			boolean ahead = rawFacingDot >= -0.35D || rawMotionDot >= -0.35D;
			double sealScore = 1.0D
				+ Math.max(0.0D, sealRingChunks - (chebyshevDistance - frame.renderDistanceChunks())) * 0.04D
				+ (ahead ? 0.05D : 0.0D);
			return new ChunkPriority(sealScore, chebyshevDistance, radialDistance, ahead, true, true);
		}
		boolean viewportCentralBias = PauCLodGameplayProfile.viewportCentralBias() && fpsFirstVanilla && !priorityTravel;
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
		if (frame.lookaheadChunks() > 0) {
			double projectedAlignment = 1.0D - Math.min(1.0D, projectedRadialDistance / Math.max(1.0D, frame.renderDistanceChunks() + frame.lookaheadChunks()));
			movementBoost += Math.max(0.0D, projectedAlignment) * (priorityTravel ? 0.10D : 0.06D);
		}
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
		if (viewportCentralBias && chebyshevDistance > frame.renderDistanceChunks() + 1) {
			double lateralExposure = 1.0D - Math.max(facingScore, motionScore);
			sidePenalty += Math.max(0.0D, lateralExposure - 0.22D) * 0.14D;
		}
		double ringWeight = viewportCentralBias ? 0.24D : fpsFirstVanilla ? 0.32D : 0.40D;
		double aheadWeight = viewportCentralBias ? 0.60D : fpsFirstVanilla ? 0.48D : 0.40D;
		double boundaryWeight = 1.0D - ringWeight - aheadWeight;
		double totalScore = (ringScore * ringWeight)
			+ (aheadScore * aheadWeight)
			+ (nearLiveBoundaryScore * boundaryWeight)
			+ retainedBonus
			+ snapBoost
			+ movementBoost
			- rearPenalty
			- sidePenalty;
		boolean ahead = rawFacingDot >= (viewportCentralBias ? 0.04D : fpsFirstVanilla ? 0.08D : 0.15D)
			|| rawMotionDot >= (viewportCentralBias ? 0.08D : fpsFirstVanilla ? 0.12D : 0.20D);
		int boundaryExtra = frame.elytraFlight() ? 4 : frame.fastTravel() ? 2 : movementCatchup ? 2 : 1;
		boolean retain = totalScore >= RETAIN_SCORE_THRESHOLD
			|| chebyshevDistance <= frame.renderDistanceChunks() + boundaryExtra
			|| (frame.snapMode() && chebyshevDistance <= frame.warmRadiusChunks())
			|| (priorityTravel && ahead && chebyshevDistance <= frame.warmRadiusChunks());
		boolean nearLiveBand = chebyshevDistance <= frame.renderDistanceChunks() + (priorityTravel ? 2 : fpsFirstVanilla ? 1 : 0);
		boolean warm = frame.snapMode()
			? totalScore >= 0.34D && chebyshevDistance <= frame.warmRadiusChunks()
			: totalScore >= (movementCatchup ? 0.38D : viewportCentralBias ? 0.54D : fpsFirstVanilla ? 0.50D : WARM_SCORE_THRESHOLD)
				&& (ahead || nearLiveBand);
		return new ChunkPriority(totalScore, chebyshevDistance, radialDistance, ahead, retain, warm);
	}

	private static LookaheadCenter computeLookaheadCenter(
		ChunkPos playerChunk,
		int warmRadiusChunks,
		int renderDistance,
		double lookX,
		double lookZ,
		double motionX,
		double motionZ,
		double speed,
		double turnSeverity,
		boolean fastTravel,
		boolean snapMode,
		boolean movementCatchup,
		boolean fpsFirstVanilla
	) {
		double guideWeight = speed >= 0.08D ? 0.72D : 0.35D;
		double guideX = (motionX * guideWeight) + (lookX * (1.0D - guideWeight));
		double guideZ = (motionZ * guideWeight) + (lookZ * (1.0D - guideWeight));
		double guideLength = Math.max(1.0E-4D, Math.sqrt(guideX * guideX + guideZ * guideZ));
		double normalizedGuideX = guideX / guideLength;
		double normalizedGuideZ = guideZ / guideLength;

		double lead = snapMode ? 2.0D : 0.0D;
		if (movementCatchup) {
			lead = Math.max(lead, 3.0D + (speed * 14.0D));
		}
		if (fastTravel) {
			lead = Math.max(lead, 5.0D + (speed * 10.0D));
		}
		if (turnSeverity > 0.32D) {
			lead += 1.0D;
		}
		if (fpsFirstVanilla && (movementCatchup || fastTravel || speed >= 0.08D)) {
			lead += readInt(HIGH_TARGET_LOOKAHEAD_BONUS_PROPERTY, 2, 0, 8);
		}

		int maxLead = Math.max(0, Math.min(warmRadiusChunks, Math.max(4, warmRadiusChunks - Math.max(renderDistance - 1, 1))));
		int lookaheadChunks = Math.max(0, Math.min(maxLead, (int) Math.round(lead)));
		return new LookaheadCenter(
			playerChunk.x + (int) Math.round(normalizedGuideX * lookaheadChunks),
			playerChunk.z + (int) Math.round(normalizedGuideZ * lookaheadChunks),
			lookaheadChunks
		);
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
		boolean idleQueueResolved = isMovementCatchupQueueResolved();
		boolean idleRelease = idleQueueResolved && !chunkChanged && speed < (MOVEMENT_CATCHUP_SPEED_THRESHOLD * 0.50D);
		if (chunkChanged || moving) {
			sustainedMovementTicks = Math.min(MOVEMENT_SUSTAINED_TICKS, sustainedMovementTicks + 1);
		} else {
			sustainedMovementTicks = Math.max(0, sustainedMovementTicks - (idleRelease ? 2 : 1));
		}

		if (chunkChanged || sustainedMovementTicks >= MOVEMENT_SUSTAINED_TICKS) {
			movementCatchupRemainingTicks = MOVEMENT_CATCHUP_TICKS;
		} else if (movementCatchupRemainingTicks > 0) {
			int releaseStep = idleRelease
				? readInt(MOVEMENT_CATCHUP_IDLE_RELEASE_STEP_PROPERTY, 4, 1, 20)
				: 1;
			movementCatchupRemainingTicks = Math.max(0, movementCatchupRemainingTicks - releaseStep);
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
		if (fpsFirstVanilla && !readBoolean(HIGH_TARGET_WARM_BEYOND_SLIDER_PROPERTY, false)) {
			targetRadius = range.lodEndChunk();
		}
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
		int radiusCap = fpsFirstVanilla && !readBoolean(HIGH_TARGET_WARM_BEYOND_SLIDER_PROPERTY, false)
			? range.lodEndChunk()
			: fpsFirstVanilla && !travelFill ? Math.min(roundHorizonRadius, targetRadius) : roundHorizonRadius;
		int warmRadius = Math.max(baseWarmRadius, Math.min(configuredRadius, radiusCap));
		if (fpsFirstVanilla && !readBoolean(HIGH_TARGET_WARM_BEYOND_SLIDER_PROPERTY, false)) {
			return Math.max(renderDistance, Math.min(warmRadius, range.lodEndChunk()));
		}
		return warmRadius;
	}

	public static int vanillaSealRingChunks(boolean travelFill) {
		return readInt(
			travelFill ? VANILLA_SEAL_TRAVEL_RING_CHUNKS_PROPERTY : VANILLA_SEAL_RING_CHUNKS_PROPERTY,
			travelFill ? 6 : 4,
			1,
			16
		);
	}

	private static boolean isMovementCatchupQueueResolved() {
		if (!PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()) {
			return false;
		}
		return PauCEmbeddedLodRuntimeDiagnostics.pendingTasks() <= 0
			&& PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() <= 0
			&& PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() <= readInt(MOVEMENT_CATCHUP_IDLE_MAX_PENDING_CHUNKS_PROPERTY, 12, 0, 512)
			&& PauCEmbeddedLodRuntimeDiagnostics.backlogPressure() <= readDouble(MOVEMENT_CATCHUP_IDLE_MAX_QUEUE_PRESSURE_PROPERTY, 0.02D, 0.0D, 1.0D);
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

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	public record PriorityFrame(
		String dimensionId,
		int playerChunkX,
		int playerChunkZ,
		int priorityCenterChunkX,
		int priorityCenterChunkZ,
		int playerSectionY,
		int minSectionY,
		int maxSectionY,
		int renderDistanceChunks,
		int warmRadiusChunks,
		int lookaheadChunks,
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

	private record LookaheadCenter(
		int chunkX,
		int chunkZ,
		int lookaheadChunks
	) {
	}
}
