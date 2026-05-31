package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;

public final class PauCMobAiThrottler {
	private PauCMobAiThrottler() {
	}

	public static boolean shouldSkipServerAiStep(Mob mob) {
		if (!PauCRuntimeSwitches.enabled("mobAiThrottle.enabled", true) || mob == null || !(mob.level() instanceof ServerLevel level)) {
			return false;
		}
		if (mob.getTarget() != null || mob.hasCustomName() || mob.isPassenger() || mob.isVehicle()) {
			return false;
		}

		int nearDistance = PauCRuntimeSwitches.readInt("mobAiThrottle.nearDistanceBlocks", 96, 32, 256);
		int farDistance = PauCRuntimeSwitches.readInt("mobAiThrottle.farDistanceBlocks", 160, 64, 512);
		double nearestPlayerDistanceSqr = nearestPlayerDistanceSqr(level, mob);
		if (nearestPlayerDistanceSqr <= nearDistance * (double) nearDistance) {
			return false;
		}

		if (shouldUseSurfaceUndergroundThrottle(level, mob, nearestPlayerDistanceSqr)) {
			int interval = PauCRuntimeSwitches.readInt("mobAiThrottle.surfaceUndergroundIntervalTicks", 10, 2, 80);
			long phase = level.getGameTime() + mob.getId();
			return Math.floorMod(phase, interval) != 0;
		}

		int interval = nearestPlayerDistanceSqr >= farDistance * (double) farDistance
			? PauCRuntimeSwitches.readInt("mobAiThrottle.farIntervalTicks", 6, 2, 40)
			: PauCRuntimeSwitches.readInt("mobAiThrottle.midIntervalTicks", 3, 2, 20);
		long phase = level.getGameTime() + mob.getId();
		return Math.floorMod(phase, interval) != 0;
	}

	private static boolean shouldUseSurfaceUndergroundThrottle(ServerLevel level, Mob mob, double nearestPlayerDistanceSqr) {
		if (!PauCRuntimeSwitches.enabled("mobAiThrottle.surfaceUnderground.enabled", true)) {
			return false;
		}
		if (!allPlayersSafelyOnSurface(level)) {
			return false;
		}

		int activationDistance = PauCRuntimeSwitches.readInt("mobAiThrottle.surfaceUndergroundDistanceBlocks", 128, 64, 512);
		if (nearestPlayerDistanceSqr < activationDistance * (double) activationDistance) {
			return false;
		}

		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mob.getBlockX(), mob.getBlockZ());
		int depth = (int) Math.floor(surfaceY - mob.getY());
		int minimumDepth = PauCRuntimeSwitches.readInt("mobAiThrottle.surfaceUndergroundDepthBlocks", 24, 8, 128);
		return depth >= minimumDepth && !level.canSeeSky(mob.blockPosition());
	}

	private static boolean allPlayersSafelyOnSurface(ServerLevel level) {
		int checkedPlayers = 0;
		int minimumY = PauCRuntimeSwitches.readInt("mobAiThrottle.surfacePlayerMinY", 48, -64, 320);
		int tolerance = PauCRuntimeSwitches.readInt("mobAiThrottle.surfacePlayerToleranceBlocks", 8, 0, 32);
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			checkedPlayers++;
			if (player.isInWaterOrBubble() || player.isInLava()) {
				return false;
			}
			int playerY = player.blockPosition().getY();
			if (playerY < minimumY) {
				return false;
			}
			int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ());
			if (playerY < surfaceY - tolerance) {
				return false;
			}
		}
		return checkedPlayers > 0;
	}

	private static double nearestPlayerDistanceSqr(ServerLevel level, Mob mob) {
		double nearest = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			nearest = Math.min(nearest, player.distanceToSqr(mob));
		}
		return nearest;
	}
}
