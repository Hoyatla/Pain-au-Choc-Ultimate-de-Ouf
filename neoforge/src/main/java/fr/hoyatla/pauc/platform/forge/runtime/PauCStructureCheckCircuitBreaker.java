package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCStructureCheckCircuitBreaker {
	private static final Map<RegionKey, RegionWindow> REGIONS = new ConcurrentHashMap<>();

	private PauCStructureCheckCircuitBreaker() {
	}

	public static boolean shouldAllow(ServerLevel level, BlockPos origin, int radius) {
		if (!PauCServerOptimizationProfile.enabled("structureBreaker.enabled", PauCServerOptimizationProfile.Feature.STRUCTURE_BREAKER)) {
			return true;
		}

		if (!PauCStallGovernor.allow(level, PauCServerPhase.STRUCTURE_CHECK, "structure")) {
			return false;
		}

		int regionX = origin.getX() >> 9;
		int regionZ = origin.getZ() >> 9;
		int clampedRadius = clampRadius(radius);
		RegionKey regionKey = RegionKey.of(level, regionX, regionZ, clampedRadius);
		RegionWindow window = REGIONS.computeIfAbsent(regionKey, ignored -> new RegionWindow());
		long now = System.currentTimeMillis();
		long windowMs = PauCRuntimeSwitches.readLong("structureBreaker.windowMs", 2_000L, 100L, 60_000L);
		int burst = PauCRuntimeSwitches.readInt("structureBreaker.burst", 3, 1, 128);
		long cooldownMs = PauCRuntimeSwitches.readLong("structureBreaker.cooldownMs", 1_500L, 10L, 120_000L);

		synchronized (window) {
			if (window.cooldownUntilMs > now) {
				return false;
			}

			if (now - window.windowStartMs > windowMs) {
				window.windowStartMs = now;
				window.count = 0;
			}

			window.count++;
			if (window.count > burst) {
				window.cooldownUntilMs = now + cooldownMs;
				return false;
			}
			return true;
		}
	}

	public static String describeState() {
		return "structureBreaker[regions=" + REGIONS.size() + "]";
	}

	public static void onServerStopped() {
		REGIONS.clear();
	}

	private static int clampRadius(int radius) {
		return Math.max(1, Math.min(256, radius));
	}

	private static final class RegionWindow {
		private long windowStartMs = System.currentTimeMillis();
		private long cooldownUntilMs;
		private int count;
	}

	private record RegionKey(ResourceKey<Level> dimensionKey, long regionPos, int radius) {
		private static RegionKey of(ServerLevel level, int regionX, int regionZ, int radius) {
			return new RegionKey(level.dimension(), ChunkPos.asLong(regionX, regionZ), radius);
		}
	}
}
