package fr.hoyatla.pauc.platform.forge.runtime;

import net.minecraft.server.MinecraftServer;

public final class PauCServerRuntimeDashboard {
	private PauCServerRuntimeDashboard() {
	}

	public static String describe(MinecraftServer server) {
		return PauCTickDebtController.describeState(server)
			+ ", "
			+ PauCServerPhaseBudgetController.describeState(server)
			+ ", "
			+ PauCStallGovernor.describeState()
			+ ", "
			+ PauCPathfindingCircuitBreaker.describeState()
			+ ", "
			+ PauCPoiQueryDiagnostics.describeState()
			+ ", "
			+ PauCStructureCheckCircuitBreaker.describeState();
	}
}
