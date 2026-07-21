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
		fr.hoyatla.pauc.shadercompat.PauCShaderCompat.requestPipelineShutdown();
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
		if (fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			PauCClientSurfaceLodMode.reset();
		}
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
		fr.hoyatla.pauc.shadercompat.PauCShaderCompat.requestPipelineShutdown();
		PauCCompatManager.onClientPlayerLoggedOut(event.getPlayer());
		PauCClientRenderShutdownGuard.onClientLogoutStarted();
		PauCClientChunkRetentionManager.onClientLogoutStarted();
		PauCClientDistanceGovernor.reset();
		if (fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			PauCClientSurfaceLodMode.reset();
			fr.hoyatla.pauc.platform.forge.client.PauCDhRenderCoordinator.onSessionEnd(); // restore DH's own rendering for a PauC-less run
		}
		PauCClientFpsGovernor.reset();
		PauCClientLodGovernor.reset();
		PauCDynamicResolution.reset();
		PauCShutdownBarrier.onClientLogoutStarted();
		fr.hoyatla.pauc.lodengine.PauCSurfaceSampler.onSessionEnd();
		fr.hoyatla.pauc.lodengine.PauCDistantStructureLocator.reset();
		fr.hoyatla.pauc.lodengine.PauCTreeImposterRenderer.reset();
		PauCPerformanceTelemetry.onClientSessionFinished("client-logout");
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) {
			net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
			fr.hoyatla.pauc.platform.forge.client.PauCClientSettings.ensureLoaded();
			fr.hoyatla.pauc.lod.PauCSquareRenderDistance.update();
			PauCClientDistanceGovernor.onClientTick(minecraft);
			if (fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
				// Surface LOD mode drives DH quality enums; never touch the class without the external DH mod.
				PauCClientSurfaceLodMode.onClientTick(minecraft);
				// When PauC's witness owns the LOD, silence DH's own render so it doesn't draw its opaque
				// terrain OVER PauC's (which was hiding all PauC trees/structures). Guarded: DH is present.
				fr.hoyatla.pauc.platform.forge.client.PauCDhRenderCoordinator.onClientTick(minecraft);
			}
			PauCClientFpsGovernor.onClientTick(minecraft);
			PauCDynamicResolution.onClientTick(minecraft);
			PauCClientLodGovernor.onClientTick();
			PauCClientChunkRetentionManager.onClientTick();
			fr.hoyatla.pauc.lodengine.PauCSurfaceSampler.onClientTick();
			fr.hoyatla.pauc.lodengine.PauCDistantStructureLocator.onClientTick();
			// P3 (iris-removal): keeps the shader context alive with an EXTERNAL Iris/Oculus, whose
			// pack changes are never pushed into PauC. No-op while the vendored push handles it.
			fr.hoyatla.pauc.lod.PauCLodShaderContext.pollExternalShaderState();
			PauCPerformanceTelemetry.onClientTick(minecraft);
		}
	}

	@SubscribeEvent
	public void onRenderLevelStage(RenderLevelStageEvent event) {
		PauCClientFrameMetrics.onRenderStage(event.getStage());
		PauCClientRenderPrep.onRenderStage(event.getStage());
		if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
			long tTerrain = fr.hoyatla.pauc.lodengine.PauCRenderProfiler.begin();
			fr.hoyatla.pauc.lodengine.PauCSurfaceWitnessRenderer.render(
				event.getPoseStack(),
				event.getCamera().getPosition()
			);
			fr.hoyatla.pauc.lodengine.PauCRenderProfiler.record("terrain", tTerrain);
			// 0.6.1 tree imposters — canopy billboards for the LOD ring, drawn right after the terrain so
			// they depth-test against it (the terrain now emits ground-only for tree tiles). Before the
			// shadow multiply pass, so distant trees are shaded with the heightfield like the ground.
			long tImposters = fr.hoyatla.pauc.lodengine.PauCRenderProfiler.begin();
			fr.hoyatla.pauc.lodengine.PauCTreeImposterRenderer.render(
				event.getPoseStack(),
				event.getCamera().getPosition()
			);
			fr.hoyatla.pauc.lodengine.PauCRenderProfiler.record("imposters", tImposters);
			// Distant structure archetypes (seed-located, singleplayer) drawn with the terrain, before
			// the shadow pass so they shade like the ground; skips chunks vanilla already renders.
			long tStructures = fr.hoyatla.pauc.lodengine.PauCRenderProfiler.begin();
			fr.hoyatla.pauc.lodengine.PauCStructureLodRenderer.render(
				event.getPoseStack(),
				event.getCamera().getPosition()
			);
			fr.hoyatla.pauc.lodengine.PauCRenderProfiler.record("structures", tStructures);
		}
		if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
			if (!fr.hoyatla.pauc.shadercompat.PauCShaderCompat.isShaderPackInUse()) {
				// PauC heightfield terrain shadows (shaderless only). Player shadow = native vanilla, no blob.
				// PROFILED since 07-19: the depth-copy + fullscreen march SYNCS the GPU pipeline, so its
				// real cost lands on the render THREAD — it was the big unmeasured slice of the frame.
				long tShadow = fr.hoyatla.pauc.lodengine.PauCRenderProfiler.begin();
				fr.hoyatla.pauc.shadow.PauCShadowMapRenderer.render(
					event.getPoseStack(),
					event.getCamera().getPosition(),
					event.getPartialTick()
				);
				fr.hoyatla.pauc.lodengine.PauCRenderProfiler.record("shadow", tShadow);
			}
			// Clouds AFTER the shadow multiply pass (else terrain shadow mottles the cloud undersides).
			long tClouds = fr.hoyatla.pauc.lodengine.PauCRenderProfiler.begin();
			fr.hoyatla.pauc.lodengine.PauCCloudLodRenderer.render(
				event.getPoseStack(),
				event.getCamera().getPosition(),
				event.getPartialTick()
			);
			fr.hoyatla.pauc.lodengine.PauCRenderProfiler.record("clouds", tClouds);
			fr.hoyatla.pauc.lodengine.PauCRenderProfiler.maybeFlush();
		}
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
