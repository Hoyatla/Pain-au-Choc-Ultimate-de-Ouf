package fr.hoyatla.pauc.platform.forge.runtime;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCStallGovernor {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Set<String> INTERNAL_PREFIXES = Set.of(
		"java.",
		"javax.",
		"jdk.",
		"sun.",
		"org.spongepowered.",
		"net.minecraftforge.",
		"net.minecraft.",
		"com.mojang.",
		"fr.hoyatla.pauc."
	);
	private static final Map<CallsiteKey, CallsiteWindow> WINDOWS = new ConcurrentHashMap<>();
	private static volatile long lastPruneAtMs;

	private PauCStallGovernor() {
	}

	public static boolean allow(ServerLevel level, PauCServerPhase phase, @Nullable String explicitFamily) {
		if (!PauCServerOptimizationProfile.enabled("stallGovernor.enabled", PauCServerOptimizationProfile.Feature.STALL_GOVERNOR)) {
			return true;
		}

		if (!PauCServerPhaseBudgetController.tryConsume(level.getServer(), phase, 1.0D)) {
			return false;
		}

		Callsite sample = explicitFamily != null ? new Callsite(explicitFamily, 0) : captureCallsite();
		pruneIfNeeded();
		String family = explicitFamily != null ? explicitFamily : classifyFamily(sample.className());
		CallsiteKey key = new CallsiteKey(level.dimension(), phase, family, sample.hash());
		CallsiteWindow window = WINDOWS.computeIfAbsent(key, ignored -> new CallsiteWindow());
		long now = System.currentTimeMillis();
		long windowMs = PauCRuntimeSwitches.readLong("stallGovernor.windowMs", 1_250L, 100L, 30_000L);

		synchronized (window) {
			if (now - window.windowStartMs > windowMs) {
				window.windowStartMs = now;
				window.hits = 0;
			}

			if (window.blockedUntilMs > now) {
				window.denied++;
				return false;
			}

			window.hits++;
			int baseQuota = phaseBaseQuota(phase, family);
			double pressure = PauCTickDebtController.pressure(level.getServer());
			int dynamicQuota = Math.max(1, (int) Math.round(baseQuota * (1.0D - (pressure * 0.75D))));

			if (window.hits > dynamicQuota) {
				window.denied++;
				window.penalty = Math.min(window.penalty + 1, 8);
				long backoffMs = computeBackoffMs(window.penalty, pressure);
				window.blockedUntilMs = now + backoffMs;
				logEscalation(key, window, dynamicQuota, backoffMs);
				return false;
			}

			return true;
		}
	}

	public static String describeState() {
		if (WINDOWS.isEmpty()) {
			return "stallGovernor[idle]";
		}

		long now = System.currentTimeMillis();
		int active = 0;
		int blocked = 0;
		long denied = 0L;
		for (CallsiteWindow window : WINDOWS.values()) {
			active++;
			denied += window.denied;
			if (window.blockedUntilMs > now) {
				blocked++;
			}
		}

		return "stallGovernor[active="
			+ active
			+ ", blocked="
			+ blocked
			+ ", denied="
			+ denied
			+ "]";
	}

	public static void onServerStopped() {
		WINDOWS.clear();
	}

	private static int phaseBaseQuota(PauCServerPhase phase, String family) {
		String quotaKey = "stallGovernor.quota." + phase.id() + "." + family;
		int fallback = switch (phase) {
			case FAR_QUERY -> 32;
			case PATHFINDING -> 18;
			case STRUCTURE_CHECK -> 10;
			case FLUID -> 14;
			case CHUNK_POST_LOAD -> 16;
			case NEIGHBOR_CASCADE -> 12;
			case WORLDGEN_APPLY -> 16;
			case WORLDGEN_FORCE_LOAD -> 3;
			case SAVE_FLUSH -> 20;
		};
		return PauCRuntimeSwitches.readInt(quotaKey, fallback, 1, 1024);
	}

	private static long computeBackoffMs(int penalty, double pressure) {
		long baseMs = PauCRuntimeSwitches.readLong("stallGovernor.backoffBaseMs", 80L, 10L, 10_000L);
		double pressureScale = 1.0D + pressure;
		long computed = (long) Math.round(baseMs * Math.pow(1.7D, penalty) * pressureScale);
		return Math.min(computed, PauCRuntimeSwitches.readLong("stallGovernor.backoffMaxMs", 4_000L, 50L, 30_000L));
	}

	private static void logEscalation(CallsiteKey key, CallsiteWindow window, int dynamicQuota, long backoffMs) {
		long now = System.currentTimeMillis();
		if (now - window.lastLogAtMs < 2_000L) {
			return;
		}

		window.lastLogAtMs = now;
		LOGGER.debug(
			"PauC stall governor throttled {} {} family '{}' (quota={}, penalty={}, backoff={}ms, denied={}).",
			key.dimensionKey().location(),
			key.phase().id(),
			key.family(),
			dynamicQuota,
			window.penalty,
			backoffMs,
			window.denied
		);
	}

	private static void pruneIfNeeded() {
		long now = System.currentTimeMillis();
		if (WINDOWS.size() < 4_096 && now - lastPruneAtMs < 10_000L) {
			return;
		}

		lastPruneAtMs = now;
		long staleWindowMs = PauCRuntimeSwitches.readLong("stallGovernor.staleWindowMs", 120_000L, 5_000L, 3_600_000L);
		WINDOWS.entrySet().removeIf(entry -> now - entry.getValue().windowStartMs > staleWindowMs);
	}

	private static Callsite captureCallsite() {
		for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
			String className = element.getClassName();
			if (shouldSkip(className)) {
				continue;
			}

			String signature = className + "#" + element.getMethodName() + ":" + element.getLineNumber();
			return new Callsite(className, signature.hashCode());
		}

		return new Callsite("unknown", 0);
	}

	private static boolean shouldSkip(String className) {
		for (String prefix : INTERNAL_PREFIXES) {
			if (className.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static String classifyFamily(String className) {
		String lower = className.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("smartbrain") || lower.contains("brain") || lower.contains("path") || lower.contains("goal")) {
			return "ai-brain";
		}
		if (lower.contains("structure") || lower.contains("village") || lower.contains("jigsaw")) {
			return "structure";
		}
		if (lower.contains("fluid")) {
			return "fluid";
		}
		if (lower.contains("neighbor") || lower.contains("update")) {
			return "neighbor";
		}
		return "unknown";
	}

	private record Callsite(String className, int hash) {
	}

	private record CallsiteKey(
		ResourceKey<Level> dimensionKey,
		PauCServerPhase phase,
		String family,
		int callsiteHash
	) {
	}

	private static final class CallsiteWindow {
		private long windowStartMs = System.currentTimeMillis();
		private long blockedUntilMs;
		private long lastLogAtMs;
		private int hits;
		private int denied;
		private int penalty;
	}
}
