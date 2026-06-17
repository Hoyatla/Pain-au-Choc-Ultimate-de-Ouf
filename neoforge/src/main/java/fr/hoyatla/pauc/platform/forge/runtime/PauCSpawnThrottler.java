package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;

/**
 * Measured natural-spawn attempt throttle, tied to the server's measured MSPT pressure
 * ({@link PauCTickDebtController#pressure(MinecraftServer)}).
 * <p>
 * Natural mob spawning (the {@code NaturalSpawner} category sweep over every chunk in spawn range, every tick) is a
 * dominant server-tick cost in dense modpacks — especially hostile-heavy packs — and feeds a vicious cycle: more mobs ⇒
 * more AI + spawn cost ⇒ higher MSPT ⇒ integrated-server client stutter. This throttle thins <em>natural spawn
 * attempts</em> only while the server is genuinely overrun, breaking that cycle.
 * <p>
 * It is deliberately gameplay-safe and imperceptible:
 * <ul>
 *   <li>Zero effect below a real MSPT pressure threshold — healthy ticks spawn exactly like vanilla.</li>
 *   <li>Above the threshold it skips a <em>fraction</em> of attempts proportional to pressure, capped well below 100%
 *       (some natural spawning always continues), and the fraction snaps back to 0 the instant pressure clears.</li>
 *   <li>It only gates {@code NaturalSpawner}'s natural category sweep — spawners, breeding, conversions, structure and
 *       command spawns, and all already-spawned mobs are untouched, and nothing is ever despawned.</li>
 * </ul>
 * The skip decision uses a deterministic accumulator (no RNG, even distribution across categories/chunks). Thresholds
 * derive from measured MSPT pressure, not a per-pack constant. Kill-switch: {@code spawnThrottle.enabled=false}.
 */
public final class PauCSpawnThrottler {
	private static double skipAccumulator;

	private PauCSpawnThrottler() {
	}

	public static boolean shouldSkipSpawnAttempt(ServerLevel level, MobCategory category) {
		if (level == null || category == null) {
			return false;
		}
		if (!PauCServerOptimizationProfile.enabled("spawnThrottle.enabled", PauCServerOptimizationProfile.Feature.SPAWN_THROTTLE)) {
			return false;
		}
		if (category == MobCategory.MISC) {
			return false;
		}

		MinecraftServer server = level.getServer();
		if (server == null) {
			return false;
		}

		double enterPressure = PauCRuntimeSwitches.readDouble("spawnThrottle.enterPressure", 0.30D, 0.05D, 0.95D);
		double pressure = PauCTickDebtController.pressure(server);
		if (pressure <= enterPressure) {
			return false;
		}

		double maxSkipFraction = PauCRuntimeSwitches.readDouble("spawnThrottle.maxSkipFraction", 0.75D, 0.10D, 0.95D);
		double normalized = (pressure - enterPressure) / Math.max(0.01D, 1.0D - enterPressure);
		double skipFraction = Math.min(maxSkipFraction, normalized * maxSkipFraction);
		if (skipFraction <= 0.0D) {
			return false;
		}

		// Deterministic even thinning: advance an accumulator by the skip fraction; skip whenever it crosses 1.
		skipAccumulator += skipFraction;
		if (skipAccumulator >= 1.0D) {
			skipAccumulator -= 1.0D;
			return true;
		}
		return false;
	}

	public static String describeState(MinecraftServer server) {
		if (server == null || !PauCServerOptimizationProfile.enabled("spawnThrottle.enabled", PauCServerOptimizationProfile.Feature.SPAWN_THROTTLE)) {
			return "spawnThrottle[off]";
		}
		double enterPressure = PauCRuntimeSwitches.readDouble("spawnThrottle.enterPressure", 0.30D, 0.05D, 0.95D);
		double maxSkipFraction = PauCRuntimeSwitches.readDouble("spawnThrottle.maxSkipFraction", 0.75D, 0.10D, 0.95D);
		double pressure = PauCTickDebtController.pressure(server);
		double skipFraction = pressure <= enterPressure
			? 0.0D
			: Math.min(maxSkipFraction, ((pressure - enterPressure) / Math.max(0.01D, 1.0D - enterPressure)) * maxSkipFraction);
		return "spawnThrottle[skip=" + Math.round(skipFraction * 100.0D) + "%]";
	}

	public static void reset() {
		skipAccumulator = 0.0D;
	}
}
