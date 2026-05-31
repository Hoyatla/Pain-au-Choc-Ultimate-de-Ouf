package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCTickDebtController {
	private static final Map<String, TickState> TICK_STATES = new ConcurrentHashMap<>();

	private PauCTickDebtController() {
	}

	public static void onServerTickStart(MinecraftServer server) {
		if (!PauCRuntimeSwitches.enabled("tickDebt.enabled", true)) {
			return;
		}

		TickState state = stateFor(server);
		state.lastTickStartNanos = System.nanoTime();
	}

	public static void onServerTickEnd(MinecraftServer server) {
		if (!PauCRuntimeSwitches.enabled("tickDebt.enabled", true)) {
			return;
		}

		TickState state = stateFor(server);
		long startedAt = state.lastTickStartNanos;
		if (startedAt <= 0L) {
			return;
		}

		double targetMs = PauCRuntimeSwitches.readDouble("tickDebt.targetMs", 50.0D, 10.0D, 200.0D);
		double decay = PauCRuntimeSwitches.readDouble("tickDebt.decay", 0.88D, 0.30D, 0.99D);
		double reliefFactor = PauCRuntimeSwitches.readDouble("tickDebt.reliefFactor", 0.35D, 0.05D, 1.00D);
		double nowMs = (System.nanoTime() - startedAt) / 1_000_000.0D;

		state.lastTickDurationMs = nowMs;
		state.smoothedTickDurationMs = state.smoothedTickDurationMs <= 0.0D
			? nowMs
			: (state.smoothedTickDurationMs * 0.9D) + (nowMs * 0.1D);

		double overflowMs = nowMs - targetMs;
		if (overflowMs > 0.0D) {
			state.tickDebtMs = (state.tickDebtMs * decay) + overflowMs;
		} else {
			state.tickDebtMs = Math.max(0.0D, (state.tickDebtMs * decay) + (overflowMs * reliefFactor));
		}

		state.lastUpdatedAtMillis = System.currentTimeMillis();
	}

	public static double debtMs(MinecraftServer server) {
		TickState state = TICK_STATES.get(serverKey(server));
		return state != null ? state.tickDebtMs : 0.0D;
	}

	public static double pressure(MinecraftServer server) {
		double fullPressureAtMs = PauCRuntimeSwitches.readDouble("tickDebt.fullPressureAtMs", 250.0D, 50.0D, 2_000.0D);
		return clamp01(debtMs(server) / fullPressureAtMs);
	}

	public static double nonCriticalScale(MinecraftServer server) {
		double floor = PauCRuntimeSwitches.readDouble("tickDebt.nonCriticalFloor", 0.20D, 0.05D, 1.00D);
		double pressure = pressure(server);
		return floor + ((1.0D - pressure) * (1.0D - floor));
	}

	public static String describeState(MinecraftServer server) {
		TickState state = TICK_STATES.get(serverKey(server));
		if (state == null) {
			return "tickDebt[idle]";
		}

		return "tickDebt[dur="
			+ round(state.lastTickDurationMs)
			+ "ms, smooth="
			+ round(state.smoothedTickDurationMs)
			+ "ms, debt="
			+ round(state.tickDebtMs)
			+ "ms, pressure="
			+ round(pressure(server) * 100.0D)
			+ "%]";
	}

	public static void onServerStopped(MinecraftServer server) {
		TICK_STATES.remove(serverKey(server));
	}

	private static TickState stateFor(MinecraftServer server) {
		return TICK_STATES.computeIfAbsent(serverKey(server), ignored -> new TickState());
	}

	private static String serverKey(MinecraftServer server) {
		return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString();
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static String round(double value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}

	private static final class TickState {
		private volatile long lastTickStartNanos;
		private volatile long lastUpdatedAtMillis;
		private volatile double tickDebtMs;
		private volatile double lastTickDurationMs;
		private volatile double smoothedTickDurationMs;
	}
}
