package fr.hoyatla.pauc.platform.forge.compat;

import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import fr.hoyatla.pauc.platform.forge.client.PauCClientChunkRetentionManager;
import fr.hoyatla.pauc.platform.forge.client.PauCClientDistanceGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFrameMetrics;
import fr.hoyatla.pauc.platform.forge.client.PauCClientFpsGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientLodGovernor;
import fr.hoyatla.pauc.platform.forge.client.PauCClientRenderPrep;
import fr.hoyatla.pauc.platform.forge.client.PauCClientSurfaceLodMode;
import fr.hoyatla.pauc.platform.forge.client.PauCDynamicResolution;
import fr.hoyatla.pauc.platform.forge.diagnostics.PauCPerformanceTelemetry;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhaseBudgetController;
import fr.hoyatla.pauc.platform.forge.runtime.PauCPathfindingCircuitBreaker;
import fr.hoyatla.pauc.platform.forge.runtime.PauCPoiQueryDiagnostics;
import fr.hoyatla.pauc.platform.forge.runtime.PauCAsyncPathfinder;
import fr.hoyatla.pauc.platform.forge.runtime.PauCSpawnThrottler;
import fr.hoyatla.pauc.platform.forge.runtime.PauCStallGovernor;
import fr.hoyatla.pauc.platform.forge.runtime.PauCStructureCheckCircuitBreaker;
import fr.hoyatla.pauc.platform.forge.runtime.PauCTickDebtController;
import net.irisshaders.iris.Iris;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class PauCCompatEventBridge {
	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		PauCCompatManager.onServerStarting();
		PauCRenderLifecycle.onClientSessionResumed();
		PauCShutdownBarrier.onClientSessionResumed();
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		PauCCompatManager.onServerStarting();
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		PauCCompatManager.onServerStopping();
		Iris.requestPipelineShutdownForClientLogout();
		PauCClientRenderShutdownGuard.onClientLogoutStarted();
		PauCShutdownBarrier.onServerStopping(event.getServer());
	}

	@SubscribeEvent
	public void onServerStopped(ServerStoppedEvent event) {
		PauCCompatManager.onServerStopped();
		PauCRenderLifecycle.onClientSessionResumed();
		PauCShutdownBarrier.onServerStopped(event.getServer());
		PauCTickDebtController.onServerStopped(event.getServer());
		PauCServerPhaseBudgetController.onServerStopped(event.getServer());
		PauCStallGovernor.onServerStopped();
		PauCPathfindingCircuitBreaker.onServerStopped();
		PauCPoiQueryDiagnostics.onServerStopped();
		PauCStructureCheckCircuitBreaker.onServerStopped();
		PauCSpawnThrottler.reset();
		PauCAsyncPathfinder.reset();
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			PauCCompatManager.onPlayerLoggedIn(player);
		}
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			PauCCompatManager.onPlayerLoggedOut(player);
		}
	}

	@SubscribeEvent
	public void onClientPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
		if (event.getPlayer() != null) {
			PauCCompatManager.onClientPlayerLoggedIn(event.getPlayer());
		}
		PauCRenderLifecycle.onClientSessionResumed();
		PauCClientRenderShutdownGuard.onClientSessionResumed();
		PauCClientChunkRetentionManager.onClientSessionResumed();
		PauCClientDistanceGovernor.reset();
		PauCClientSurfaceLodMode.reset();
		PauCClientFpsGovernor.reset();
		PauCClientLodGovernor.reset();
		PauCDynamicResolution.reset();
		PauCShutdownBarrier.onClientSessionResumed();
		PauCPerformanceTelemetry.onClientSessionResumed();
	}

	@SubscribeEvent
	public void onClientPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
		if (!PauCCompatManager.shouldProcessClientLogout(event.getPlayer())) {
			return;
		}

		PauCRenderLifecycle.onClientLogoutStarted();
		Iris.requestPipelineShutdownForClientLogout();
		PauCCompatManager.onClientPlayerLoggedOut(event.getPlayer());
		PauCClientRenderShutdownGuard.onClientLogoutStarted();
		PauCClientChunkRetentionManager.onClientLogoutStarted();
		PauCClientDistanceGovernor.reset();
		PauCClientSurfaceLodMode.reset();
		PauCClientFpsGovernor.reset();
		PauCClientLodGovernor.reset();
		PauCDynamicResolution.reset();
		PauCShutdownBarrier.onClientLogoutStarted();
		PauCPerformanceTelemetry.onClientSessionFinished("client-logout");
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) {
			net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
			PauCClientDistanceGovernor.onClientTick(minecraft);
			PauCClientSurfaceLodMode.onClientTick(minecraft);
			PauCClientFpsGovernor.onClientTick(minecraft);
			PauCDynamicResolution.onClientTick(minecraft);
			PauCClientLodGovernor.onClientTick();
			PauCClientChunkRetentionManager.onClientTick();
			PauCPerformanceTelemetry.onClientTick(minecraft);
		}
	}

	@SubscribeEvent
	public void onRenderLevelStage(RenderLevelStageEvent event) {
		PauCClientFrameMetrics.onRenderStage(event.getStage());
		PauCClientRenderPrep.onRenderStage(event.getStage());
	}

	@SubscribeEvent
	public void onLevelUnload(LevelEvent.Unload event) {
		if (event.getLevel() instanceof ClientLevel level) {
			PauCClientChunkRetentionManager.onClientLevelUnload(level);
			PauCClientDistanceGovernor.reset();
			PauCClientFpsGovernor.reset();
			PauCClientLodGovernor.reset();
			PauCDynamicResolution.reset();
			PauCPerformanceTelemetry.onClientSessionFinished("level-unload");
		}
	}
}
