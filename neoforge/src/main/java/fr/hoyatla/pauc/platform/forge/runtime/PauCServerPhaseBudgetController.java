package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.server.MinecraftServer;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCServerPhaseBudgetController {
	private static final Map<String, ServerBudgetState> STATES = new ConcurrentHashMap<>();
	private static final Map<PauCServerPhase, PhaseDefaults> DEFAULTS = new EnumMap<>(PauCServerPhase.class);

	static {
		DEFAULTS.put(PauCServerPhase.FAR_QUERY, new PhaseDefaults(768, 64, 2.0D));
		DEFAULTS.put(PauCServerPhase.PATHFINDING, new PhaseDefaults(96, 8, 1.0D));
		DEFAULTS.put(PauCServerPhase.STRUCTURE_CHECK, new PhaseDefaults(64, 4, 1.0D));
		DEFAULTS.put(PauCServerPhase.FLUID, new PhaseDefaults(96, 8, 1.0D));
		DEFAULTS.put(PauCServerPhase.CHUNK_POST_LOAD, new PhaseDefaults(96, 8, 1.0D));
		DEFAULTS.put(PauCServerPhase.NEIGHBOR_CASCADE, new PhaseDefaults(48, 4, 1.0D));
		DEFAULTS.put(PauCServerPhase.WORLDGEN_APPLY, new PhaseDefaults(32, 4, 1.0D));
		DEFAULTS.put(PauCServerPhase.WORLDGEN_FORCE_LOAD, new PhaseDefaults(3, 1, 1.0D));
		DEFAULTS.put(PauCServerPhase.SAVE_FLUSH, new PhaseDefaults(64, 8, 1.5D));
	}

	private PauCServerPhaseBudgetController() {
	}

	public static void onServerTickStart(MinecraftServer server) {
		if (!PauCServerOptimizationProfile.enabled("phaseBudget.enabled", PauCServerOptimizationProfile.Feature.PHASE_BUDGET)) {
			return;
		}

		ServerBudgetState state = stateFor(server);
		double scale = PauCTickDebtController.nonCriticalScale(server);

		for (PauCServerPhase phase : PauCServerPhase.values()) {
			PhaseDefaults defaults = DEFAULTS.get(phase);
			int configuredBase = PauCRuntimeSwitches.readInt("phaseBudget." + phase.id() + ".base", defaults.baseBudget(), 1, 16_384);
			int configuredMin = PauCRuntimeSwitches.readInt("phaseBudget." + phase.id() + ".min", defaults.minBudget(), 1, 16_384);
			double capacityFactor = PauCRuntimeSwitches.readDouble("phaseBudget." + phase.id() + ".capacity", defaults.capacityFactor(), 1.0D, 6.0D);
			double refill = Math.max(configuredMin, configuredBase * scale);
			double maxTokens = Math.max(configuredBase, configuredBase * capacityFactor);
			double existing = state.tokens.getOrDefault(phase, maxTokens);
			double replenished = Math.min(maxTokens, existing + refill);
			state.tokens.put(phase, replenished);
			state.lastRefill.put(phase, refill);
			state.maxTokens.put(phase, maxTokens);
		}
	}

	public static boolean tryConsume(MinecraftServer server, PauCServerPhase phase, double cost) {
		if (!PauCServerOptimizationProfile.enabled("phaseBudget.enabled", PauCServerOptimizationProfile.Feature.PHASE_BUDGET)) {
			return true;
		}

		ServerBudgetState state = stateFor(server);
		double available = state.tokens.getOrDefault(phase, 0.0D);
		if (available < cost) {
			return false;
		}

		state.tokens.put(phase, Math.max(0.0D, available - cost));
		return true;
	}

	public static int scaledBudget(MinecraftServer server, PauCServerPhase phase, int baseBudget, int minBudget) {
		if (!PauCServerOptimizationProfile.enabled("phaseBudget.enabled", PauCServerOptimizationProfile.Feature.PHASE_BUDGET)) {
			return baseBudget;
		}

		double scale = PauCTickDebtController.nonCriticalScale(server);
		return Math.max(minBudget, (int) Math.round(baseBudget * scale));
	}

	public static String describeState(MinecraftServer server) {
		ServerBudgetState state = STATES.get(serverKey(server));
		if (state == null) {
			return "phaseBudget[idle]";
		}

		StringBuilder builder = new StringBuilder("phaseBudget[");
		boolean first = true;
		for (PauCServerPhase phase : PauCServerPhase.values()) {
			if (!first) {
				builder.append(", ");
			}
			first = false;
			double tokens = state.tokens.getOrDefault(phase, 0.0D);
			double max = state.maxTokens.getOrDefault(phase, 0.0D);
			builder.append(phase.id()).append("=").append((int) tokens).append("/").append((int) max);
		}
		builder.append("]");
		return builder.toString();
	}

	public static void onServerStopped(MinecraftServer server) {
		STATES.remove(serverKey(server));
	}

	private static ServerBudgetState stateFor(MinecraftServer server) {
		return STATES.computeIfAbsent(serverKey(server), ignored -> new ServerBudgetState());
	}

	private static String serverKey(MinecraftServer server) {
		return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString();
	}

	private record PhaseDefaults(
		int baseBudget,
		int minBudget,
		double capacityFactor
	) {
	}

	private static final class ServerBudgetState {
		private final Map<PauCServerPhase, Double> tokens = new EnumMap<>(PauCServerPhase.class);
		private final Map<PauCServerPhase, Double> lastRefill = new EnumMap<>(PauCServerPhase.class);
		private final Map<PauCServerPhase, Double> maxTokens = new EnumMap<>(PauCServerPhase.class);
	}
}
