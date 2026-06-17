package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.server.MinecraftServer;

public final class PauCServerRuntimeDashboard {
	private PauCServerRuntimeDashboard() {
	}

	public static String describe(MinecraftServer server) {
		return PauCServerOptimizationProfile.describeState()
			+ ", "
			+ PauCTickDebtController.describeState(server)
			+ ", "
			+ PauCServerPhaseBudgetController.describeState(server)
			+ ", "
			+ PauCStallGovernor.describeState()
			+ ", "
			+ PauCPathfindingCircuitBreaker.describeState()
			+ ", "
			+ PauCPoiQueryDiagnostics.describeState()
			+ ", "
			+ PauCStructureCheckCircuitBreaker.describeState()
			+ ", "
			+ PauCSpawnThrottler.describeState(server)
			+ ", "
			+ PauCAsyncPathfinder.describeState();
	}
}
