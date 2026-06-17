package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Asynchronous A* pathfinding: moves the expensive {@code PathFinder.findPath} search off the server thread.
 * <p>
 * Vanilla's {@code findPath} already operates on a {@link PathNavigationRegion} — a SNAPSHOT of the chunk sections
 * around the mob, built on the server thread before the search. The search therefore reads only that snapshot (plus the
 * mob's transform/attributes), so it can run on a worker thread. While the new path computes, the mob keeps following
 * its current path; the fresh result is adopted when ready (≈1 server tick later — imperceptible, since mobs re-path
 * only every few ticks anyway). At most one search is in flight per mob, bounding worker load.
 * <p>
 * Gameplay-safety: the worker computation is fully guarded — any exception (e.g. a concurrent read of a mob attribute
 * mid-mutation) is caught on the worker and yields a null path for that cycle (the mob simply re-paths next tick), so it
 * can never crash or corrupt the server thread. It is DEFAULT-OFF ({@code asyncPathfinding.enabled=false}); running
 * vanilla pathfinding off-thread must be validated in-session before becoming default.
 */
public final class PauCAsyncPathfinder {
	private static final ThreadLocal<Boolean> IN_WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);
	private static final ConcurrentHashMap<Integer, Slot> SLOTS = new ConcurrentHashMap<>();
	private static volatile ExecutorService pool;
	private static long sweepCounter;

	private PauCAsyncPathfinder() {
	}

	public static boolean enabled() {
		return PauCRuntimeSwitches.enabled("asyncPathfinding.enabled", false);
	}

	/** True when the current thread is a PauC pathfinding worker (so the mixin lets the real search run). */
	public static boolean isWorkerThread() {
		return Boolean.TRUE.equals(IN_WORKER.get());
	}

	/**
	 * Called on the server thread from the {@code PathFinder.findPath} mixin. Returns the mob's current path while an
	 * async search runs, or the freshly computed path once ready.
	 */
	public static Path computeOrCurrent(PathFinder pathFinder, PathNavigationRegion region, Mob mob,
										Set<BlockPos> targets, float maxRange, int accuracy, float searchDepthMultiplier) {
		int id = mob.getId();
		long sig = signature(targets, mob);
		Slot slot = SLOTS.get(id);
		if (slot != null) {
			if (!slot.future.isDone()) {
				// A search is already in flight for this mob; keep it the only one and wait it out.
				return currentPath(mob);
			}
			if (slot.sig == sig) {
				SLOTS.remove(id);
				try {
					return slot.future.get();
				} catch (Throwable ignored) {
					return null;
				}
			}
			// Done but for a stale target: drop it and start a fresh search below.
			SLOTS.remove(id);
		}

		ExecutorService executor = ensurePool();
		Future<Path> future = executor.submit(() -> {
			IN_WORKER.set(Boolean.TRUE);
			try {
				return pathFinder.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
			} catch (Throwable ignored) {
				return null;
			} finally {
				IN_WORKER.set(Boolean.FALSE);
			}
		});
		SLOTS.put(id, new Slot(sig, future));
		maybeSweep();
		return currentPath(mob);
	}

	public static void reset() {
		SLOTS.clear();
		ExecutorService p = pool;
		if (p != null) {
			p.shutdownNow();
			pool = null;
		}
	}

	public static String describeState() {
		return "asyncPathfinding[" + (enabled() ? "on" : "off") + ", inFlight=" + SLOTS.size() + "]";
	}

	private static Path currentPath(Mob mob) {
		try {
			return mob.getNavigation() != null ? mob.getNavigation().getPath() : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static long signature(Set<BlockPos> targets, Mob mob) {
		long hash = targets == null ? 0L : targets.hashCode();
		return hash * 31L + mob.blockPosition().asLong();
	}

	private static ExecutorService ensurePool() {
		ExecutorService current = pool;
		if (current != null && !current.isShutdown()) {
			return current;
		}
		synchronized (PauCAsyncPathfinder.class) {
			if (pool == null || pool.isShutdown()) {
				int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
				pool = Executors.newFixedThreadPool(threads, runnable -> {
					Thread thread = new Thread(runnable, "PauC-AsyncPath");
					thread.setDaemon(true);
					thread.setPriority(Thread.NORM_PRIORITY - 1);
					return thread;
				});
			}
			return pool;
		}
	}

	private static void maybeSweep() {
		if ((++sweepCounter & 1023L) != 0L) {
			return;
		}
		SLOTS.entrySet().removeIf(entry -> entry.getValue().future.isDone());
	}

	private record Slot(long sig, Future<Path> future) {
	}
}
