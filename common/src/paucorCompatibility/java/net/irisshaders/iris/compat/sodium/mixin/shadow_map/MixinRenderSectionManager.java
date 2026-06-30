package net.irisshaders.iris.compat.sodium.mixin.shadow_map;

import fr.hoyatla.pauc.compat.PauCCompatibility;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

@Mixin(RenderSectionManager.class)
public abstract class MixinRenderSectionManager {
	@Shadow
	private @NotNull SortedRenderLists renderLists;
	@Shadow
	private @NotNull Map<ChunkUpdateType, ArrayDeque<RenderSection>> taskLists;
	@Shadow(remap = false)
	public abstract int getVisibleChunkCount();
	@Unique
	private @NotNull SortedRenderLists shadowRenderLists = SortedRenderLists.empty();
	@Unique
	private @NotNull Map<ChunkUpdateType, ArrayDeque<RenderSection>> shadowTaskLists = new EnumMap<>(ChunkUpdateType.class);
	@Unique
	private @NotNull SortedRenderLists pauc$lastStableShadowRenderLists = SortedRenderLists.empty();
	@Unique
	private @NotNull Map<ChunkUpdateType, ArrayDeque<RenderSection>> pauc$lastStableShadowTaskLists = new EnumMap<>(ChunkUpdateType.class);
	@Unique
	private boolean pauc$hasStableShadowRenderLists;
	@Unique
	private static boolean pauc$reportedSodiumShadowListFallback;
	@Unique
	private static boolean pauc$reportedSodiumStableShadowListFallback;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void create(ClientLevel level, int renderDistance, CommandList commandList, CallbackInfo ci) {
		for(int var6 = 0; var6 < ChunkUpdateType.values().length; ++var6) {
			ChunkUpdateType type = ChunkUpdateType.values()[var6];
			shadowTaskLists.put(type, new ArrayDeque<>());
			pauc$lastStableShadowTaskLists.put(type, new ArrayDeque<>());
		}
	}

	@Redirect(remap = false, method = "createTerrainRenderList", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;renderLists:Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;"))
	private void useShadowRenderList(RenderSectionManager instance, SortedRenderLists value) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) {
			shadowRenderLists = value;
		} else {
			renderLists = value;
		}
	}
	@Redirect(remap = false, method = "createTerrainRenderList", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;taskLists:Ljava/util/Map;"))
	private void useShadowTaskrList(RenderSectionManager instance, @NotNull Map<ChunkUpdateType, ArrayDeque<RenderSection>> value) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) {
			shadowTaskLists = value;
		} else {
			taskLists = value;
		}
	}

	// PauC efficiency #1: the shadow chunk graph is re-traversed every frame with occlusion culling disabled - the
	// dominant shader-on CPU cost. ShadowRenderingState already tracks (and is fed, in ShadowRenderer) whether the
	// graph actually changed: camera position/pitch/yaw + sun angle (quantized) + section add/remove/rebuild. When it
	// has NOT changed and a cached stable shadow visibility list exists, skip the whole re-traversal and reuse the
	// cache. The shadow map is still re-rendered each frame with the current sun projection, so shadows stay correct;
	// we only avoid recomputing WHICH chunks are visible when nothing relevant moved. Kill-switch: pauc.shadow.cacheTerrainList=false.
	@Inject(method = "update", at = @At("HEAD"), cancellable = true, remap = false)
	private void pauc$skipStableShadowRebuild(Camera camera, Viewport viewport, boolean spectator, CallbackInfo ci) {
		if (!PauCCompatibility.shouldUseSodiumShadowPass()) {
			return;
		}
		if (!Boolean.parseBoolean(System.getProperty("pauc.shadow.cacheTerrainList", "true"))) {
			return;
		}
		if (pauc$hasStableShadowRenderLists && !ShadowRenderingState.shouldRebuildShadowTerrainGraph()) {
			shadowRenderLists = pauc$lastStableShadowRenderLists;
			shadowTaskLists = pauc$lastStableShadowTaskLists;
			ci.cancel();
		}
	}

	@Inject(remap = false, method = "update", at = @At(remap = true, value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;createTerrainRenderList(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;IZ)V", shift = At.Shift.AFTER), cancellable = true)
	private void cancelIfShadow(Camera camera, Viewport viewport, boolean spectator, CallbackInfo ci) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) {
			boolean emptyShadowGraph = getVisibleChunkCount() <= 0;
			if (!emptyShadowGraph) {
				pauc$lastStableShadowRenderLists = shadowRenderLists;
				pauc$lastStableShadowTaskLists = shadowTaskLists;
				pauc$hasStableShadowRenderLists = true;
			} else if (pauc$hasStableShadowRenderLists) {
				shadowRenderLists = pauc$lastStableShadowRenderLists;
				shadowTaskLists = pauc$lastStableShadowTaskLists;
				ShadowRenderingState.markShadowTerrainGraphDirty();
				if (!pauc$reportedSodiumStableShadowListFallback) {
					pauc$reportedSodiumStableShadowListFallback = true;
					Iris.logger.warn("PauC reused the last stable Sodium shadow visibility list because the current shadow graph came back empty; this avoids player-camera pitch dependent shadow loss while the graph is rebuilt.");
				}
			} else if (renderLists != null) {
				shadowRenderLists = renderLists;
				shadowTaskLists = taskLists;
				ShadowRenderingState.markShadowTerrainGraphDirty();
				if (!pauc$reportedSodiumShadowListFallback) {
					pauc$reportedSodiumShadowListFallback = true;
					Iris.logger.warn("PauC used the main terrain visibility list as a first-frame Sodium shadow fallback because no stable shadow list exists yet; the shadow graph stays dirty and will be rebuilt again on the next pass.");
				}
			}
			if (getVisibleChunkCount() > 0) {
				ShadowRenderingState.onShadowTerrainGraphRebuilt();
			} else {
				ShadowRenderingState.markShadowTerrainGraphDirty();
			}
			ci.cancel();
		}
	}

	@Redirect(method = {
		"getRenderLists",
		"getVisibleChunkCount",
		"renderLayer"
	}, at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;renderLists:Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;"), remap = false)
	private SortedRenderLists useShadowRenderList2(RenderSectionManager instance) {
		return PauCCompatibility.shouldUseSodiumShadowPass() ? shadowRenderLists : renderLists;
	}

	@Inject(method = "updateChunks", at = @At("HEAD"), cancellable = true, remap = false)
	private void doNotUpdateDuringShadow(boolean updateImmediately, CallbackInfo ci) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) ci.cancel();
	}

	@Inject(method = "uploadChunks", at = @At("HEAD"), cancellable = true, remap = false)
	private void doNotUploadDuringShadow(CallbackInfo ci) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) ci.cancel();
	}

	@Inject(method = "markGraphDirty", at = @At("HEAD"), remap = false)
	private void iris$markShadowGraphDirty(CallbackInfo ci) {
		ShadowRenderingState.markShadowTerrainGraphDirty();
	}

	@Inject(method = "onSectionAdded", at = @At("HEAD"), remap = false)
	private void iris$markShadowGraphDirtyOnSectionAdded(int x, int y, int z, CallbackInfo ci) {
		ShadowRenderingState.markShadowTerrainGraphDirty();
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"), remap = false)
	private void iris$markShadowGraphDirtyOnSectionRemoved(int x, int y, int z, CallbackInfo ci) {
		ShadowRenderingState.markShadowTerrainGraphDirty();
	}

	@Inject(method = "scheduleRebuild", at = @At("HEAD"), remap = false)
	private void iris$markShadowGraphDirtyOnScheduleRebuild(int x, int y, int z, boolean important, CallbackInfo ci) {
		ShadowRenderingState.markShadowTerrainGraphDirty();
	}

	@Redirect(method = {
		"resetRenderLists",
		"submitSectionTasks(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;)V"
	}, at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;taskLists:Ljava/util/Map;"), remap = false)
	private @NotNull Map<ChunkUpdateType, ArrayDeque<RenderSection>> useShadowTaskList3(RenderSectionManager instance) {
		return PauCCompatibility.shouldUseSodiumShadowPass() ? shadowTaskLists : taskLists;
	}

	@Redirect(method = {
		"resetRenderLists"
	}, at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;renderLists:Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;"), remap = false)
	private void useShadowRenderList3(RenderSectionManager instance, SortedRenderLists value) {
		if (PauCCompatibility.shouldUseSodiumShadowPass()) shadowRenderLists = value;
		else renderLists = value;
	}
}
