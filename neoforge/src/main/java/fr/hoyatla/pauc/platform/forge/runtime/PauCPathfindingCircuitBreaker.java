package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCPathfindingCircuitBreaker {
	private static final Map<AttemptKey, AttemptWindow> WINDOWS = new ConcurrentHashMap<>();
	private static final Map<PathFailureKey, CachedPathFailure> FAILED_PATHS = new ConcurrentHashMap<>();
	private static volatile long lastFailurePruneAtMs;
	private static long deniedAttempts;
	private static long cachedFailureHits;
	private static long cachedFailureStores;

	private PauCPathfindingCircuitBreaker() {
	}

	public static boolean shouldAllow(ServerLevel level, Mob mob, BlockPos targetPos) {
		if (!PauCServerOptimizationProfile.enabled("pathfindingBreaker.enabled", PauCServerOptimizationProfile.Feature.PATHFINDING_BREAKER)) {
			return true;
		}

		if (!PauCStallGovernor.allow(level, PauCServerPhase.PATHFINDING, "ai-brain")) {
			deniedAttempts++;
			return false;
		}

		int chunkX = targetPos.getX() >> 4;
		int chunkZ = targetPos.getZ() >> 4;
		int distance = Math.max(Math.abs(mob.chunkPosition().x - chunkX), Math.abs(mob.chunkPosition().z - chunkZ));
		int farDistance = PauCRuntimeSwitches.readInt("pathfindingBreaker.farDistanceChunks", 10, 1, 256);

		if (distance < farDistance) {
			return true;
		}

		long windowMs = PauCRuntimeSwitches.readLong("pathfindingBreaker.windowMs", 1_000L, 50L, 60_000L);
		int maxAttempts = PauCRuntimeSwitches.readInt("pathfindingBreaker.maxAttempts", 4, 1, 128);
		AttemptKey key = AttemptKey.of(level, mob, chunkX, chunkZ);
		AttemptWindow window = WINDOWS.computeIfAbsent(key, ignored -> new AttemptWindow());
		long now = System.currentTimeMillis();

		synchronized (window) {
			if (now - window.windowStartMs > windowMs) {
				window.windowStartMs = now;
				window.attempts = 0;
			}
			window.attempts++;
			boolean allowed = window.attempts <= maxAttempts;
			if (!allowed) {
				deniedAttempts++;
			}
			return allowed;
		}
	}

	public static boolean hasCachedFailure(ServerLevel level, Mob mob, BlockPos targetPos) {
		if (!PauCServerOptimizationProfile.enabled("pathfindingCache.enabled", PauCServerOptimizationProfile.Feature.PATHFINDING_CACHE)) {
			return false;
		}

		PathFailureKey key = PathFailureKey.of(level, mob, targetPos);
		CachedPathFailure cached = FAILED_PATHS.get(key);
		if (cached == null) {
			return false;
		}

		long now = System.currentTimeMillis();
		if (now - cached.createdAtMs > failureTtlMs()) {
			FAILED_PATHS.remove(key, cached);
			return false;
		}

		cachedFailureHits++;
		return true;
	}

	public static void rememberFailure(ServerLevel level, Mob mob, BlockPos targetPos) {
		if (!PauCServerOptimizationProfile.enabled("pathfindingCache.enabled", PauCServerOptimizationProfile.Feature.PATHFINDING_CACHE)) {
			return;
		}

		long now = System.currentTimeMillis();
		pruneFailures(now);
		int maxEntries = PauCRuntimeSwitches.readInt("pathfindingCache.maxEntries", 4096, 128, 65536);
		if (FAILED_PATHS.size() >= maxEntries) {
			return;
		}

		FAILED_PATHS.put(PathFailureKey.of(level, mob, targetPos), new CachedPathFailure(now));
		cachedFailureStores++;
	}

	public static String describeState() {
		return "pathBreaker[windows="
			+ WINDOWS.size()
			+ ", failedCache="
			+ FAILED_PATHS.size()
			+ ", cacheHits="
			+ cachedFailureHits
			+ ", cacheStores="
			+ cachedFailureStores
			+ ", denied="
			+ deniedAttempts
			+ "]";
	}

	public static void onServerStopped() {
		WINDOWS.clear();
		FAILED_PATHS.clear();
		lastFailurePruneAtMs = 0L;
		deniedAttempts = 0L;
		cachedFailureHits = 0L;
		cachedFailureStores = 0L;
	}

	private static void pruneFailures(long now) {
		long ttlMs = failureTtlMs();
		if (now - lastFailurePruneAtMs < ttlMs) {
			return;
		}

		lastFailurePruneAtMs = now;
		FAILED_PATHS.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs > ttlMs);
	}

	private static long failureTtlMs() {
		return PauCRuntimeSwitches.readLong("pathfindingCache.failedPathTtlMs", 350L, 50L, 2_000L);
	}

	private static final class AttemptWindow {
		private long windowStartMs = System.currentTimeMillis();
		private int attempts;
	}

	private record AttemptKey(ResourceKey<Level> dimensionKey, UUID mobId, long targetChunkPos) {
		private static AttemptKey of(ServerLevel level, Mob mob, int chunkX, int chunkZ) {
			return new AttemptKey(level.dimension(), mob.getUUID(), ChunkPos.asLong(chunkX, chunkZ));
		}
	}

	private record PathFailureKey(ResourceKey<Level> dimensionKey, UUID mobId, long targetPos) {
		private static PathFailureKey of(ServerLevel level, Mob mob, BlockPos targetPos) {
			return new PathFailureKey(level.dimension(), mob.getUUID(), targetPos.asLong());
		}
	}

	private record CachedPathFailure(long createdAtMs) {
	}
}
