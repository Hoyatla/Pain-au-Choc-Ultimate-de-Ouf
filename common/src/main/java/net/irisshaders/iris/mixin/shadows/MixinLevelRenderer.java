package net.irisshaders.iris.mixin.shadows;

import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.mixin.ViewAreaAccessor;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer implements CullingDataCache {
	@Unique
	private static final int PAUC_STABLE_MAIN_CAMERA_SHADOW_CACHE_LIMIT = 3072;

	@Shadow
	@Final
	@Mutable
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> renderChunksInFrustum;

	@Shadow
	private double prevCamRotX;

	@Shadow
	private double prevCamRotY;

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$savedRenderChunks = new ObjectArrayList<>(69696);

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$mainRenderChunksSnapshot = new ObjectArrayList<>(69696);

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$lastStableShadowChunks = new ObjectArrayList<>(2048);

	@Unique
	private final Map<Long, LevelRenderer.RenderChunkInfo> pauc$shadowChunkInfoCache = new HashMap<>();

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$stableMainCameraShadowCandidates =
		new ObjectArrayList<>(PAUC_STABLE_MAIN_CAMERA_SHADOW_CACHE_LIMIT);

	@Unique
	private ClientLevel pauc$shadowCacheLevel;

	@Unique
	private double pauc$savedLastCameraPitch;

	@Unique
	private double pauc$savedLastCameraYaw;

	@Unique
	private int pauc$savedTicks = Integer.MIN_VALUE;

	@Unique
	private double pauc$stableMainCameraShadowCameraX = Double.NaN;

	@Unique
	private double pauc$stableMainCameraShadowCameraZ = Double.NaN;

	@Unique
	private int pauc$stableMainCameraShadowRadiusChunks = -1;

	@Unique
	private static boolean pauc$reportedShadowCullingSwap;

	@Unique
	private static boolean pauc$reportedShadowCullingFallback;

	@Unique
	private static boolean pauc$reportedStableShadowFallback;

	@Unique
	private static boolean pauc$reportedViewAreaShadowFallback;

	@Unique
	private static boolean pauc$reportedMainCameraShadowFallback;

	@Unique
	private static boolean pauc$reportedShadowChunkInfoConstructorFailure;

	@Unique
	private static Constructor<LevelRenderer.RenderChunkInfo> pauc$renderChunkInfoConstructor;

	@Override
	public void saveState() {
		pauc$syncShadowCacheLevel();
		pauc$mainRenderChunksSnapshot = new ObjectArrayList<>(renderChunksInFrustum);
		pauc$cacheShadowChunkInfos(pauc$mainRenderChunksSnapshot);
		pauc$saveTraversalTickState();
		int recoveryRadiusChunks = pauc$localVanillaShadowRecoveryRadiusChunks();
		if (pauc$shouldRefreshStableMainCameraShadowCandidates(recoveryRadiusChunks)) {
			pauc$mergeStableMainCameraShadowCandidates(pauc$mainRenderChunksSnapshot);
			pauc$rememberStableMainCameraShadowCandidateState(recoveryRadiusChunks);
		}
		swap();
	}

	@Override
	public void restoreState() {
		swap();
		pauc$restoreTraversalTickState();
		pauc$mainRenderChunksSnapshot.clear();
	}

	@Override
	public void useMainCameraChunksIfShadowSetupFailed() {
		pauc$syncShadowCacheLevel();

		// Opt-out: if the pack drives its own shadow terrain and explicitly disables PauC's fallback,
		// leave whatever the shadow setup produced untouched.
		int availableChunks = pauc$viewAreaChunkCount();
		if (availableChunks <= 0) {
			availableChunks = pauc$mainRenderChunksSnapshot.size();
		}
		if (availableChunks <= 0) {
			if (renderChunksInFrustum.isEmpty() && !pauc$lastStableShadowChunks.isEmpty()) {
				renderChunksInFrustum = new ObjectArrayList<>(pauc$lastStableShadowChunks);
			}
			return;
		}
		if (PauCLodShaderRuntime.shadowFallbackChunkBudget(availableChunks) <= 0) {
			return;
		}

		// STABILITY: build the same deterministic shadow terrain set EVERY frame, instead of keeping the
		// shader's own shadow-frustum set when it is non-empty. The embedded (no-Sodium) shadow setup is
		// intermittent — empty on most frames, non-empty on a fraction — so deferring to it makes the
		// shadow region alternate between two different sets and flicker. We also use a pressure-INDEPENDENT
		// budget (the vanilla render distance + junction margin) so the set size does not swing frame to
		// frame with the family pressure budget (the measured 640<->1152 swing that lit up a thin unstable
		// ring). A constant set size + a snapshot-first radial build = a stable shadow region that simply
		// follows the player.
		int budget = pauc$vanillaShadowZoneBudget();

		// Reuse the previous result while the camera stays in the same chunk and the inputs are stable. The
		// set is nearly identical across those frames, so this skips the per-frame copy+sort entirely.
		Vec3 fallbackCamera = pauc$mainCameraPosition();
		int camChunkX = Mth.floor(fallbackCamera.x) >> 4;
		int camChunkZ = Mth.floor(fallbackCamera.z) >> 4;
		if (pauc$cachedFallbackResult != null
			&& !pauc$cachedFallbackResult.isEmpty()
			&& camChunkX == pauc$cachedFallbackCamChunkX
			&& camChunkZ == pauc$cachedFallbackCamChunkZ
			&& budget == pauc$cachedFallbackBudget
			&& Math.abs(availableChunks - pauc$cachedFallbackSnapshotSize) <= 8) {
			// Hand out a FRESH COPY, never the cached list itself. After the shadow pass, invokeSetupRender repopulates
			// (and in the no-Sodium embedded setup CLEARS) renderChunksInFrustum; if that were the cached object, the
			// cache would be emptied every frame and the set rebuilt every frame (constant shadow flicker + a 625-chunk
			// rebuild per frame). Keeping the cache independent makes a stationary camera reuse one stable set.
			renderChunksInFrustum = new ObjectArrayList<>(pauc$cachedFallbackResult);
			return;
		}

		// Retention headroom: keep the previous frame's shadowed chunks for a frame or two beyond the live
		// zone so that a chunk briefly dropping out of the snapshot during movement (a leading-edge rebuild
		// or a partial main-render list on a heavy frame) does not blink its shadow off. Bounded at 1.5x so
		// the extra depth-only near draws stay cheap.
		int retentionBudget = budget + (budget / 2);

		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks =
			pauc$buildStableShadowFallbackFromViewArea(budget, retentionBudget);
		if (selectedChunks.isEmpty()) {
			selectedChunks = pauc$buildStableShadowFallbackFromSnapshot(budget, retentionBudget);
		}
		if (selectedChunks.isEmpty()) {
			if (renderChunksInFrustum.isEmpty() && !pauc$lastStableShadowChunks.isEmpty()) {
				renderChunksInFrustum = new ObjectArrayList<>(pauc$lastStableShadowChunks);
			}
			return;
		}

		renderChunksInFrustum = selectedChunks;
		// Cache an INDEPENDENT copy (not selectedChunks, which is handed to renderChunksInFrustum and later cleared by
		// the shadow setup) so the cache survives across frames and hits while the camera stays in the same chunk.
		pauc$cachedFallbackResult = new ObjectArrayList<>(selectedChunks);
		pauc$cachedFallbackCamChunkX = camChunkX;
		pauc$cachedFallbackCamChunkZ = camChunkZ;
		pauc$cachedFallbackBudget = budget;
		pauc$cachedFallbackSnapshotSize = availableChunks;
		pauc$rememberShadowChunks(renderChunksInFrustum, renderChunksInFrustum.size());
		if (!pauc$reportedShadowCullingFallback) {
			pauc$reportedShadowCullingFallback = true;
			Iris.logger.info(
				"PauC rebuilt {} shadow terrain chunks from {} loaded render chunks after shader shadow terrain setup returned empty; {}",
				renderChunksInFrustum.size(),
				availableChunks,
				PauCLodShaderRuntime.describe()
			);
		}
	}

	@Unique
	private void pauc$syncShadowCacheLevel() {
		ClientLevel currentLevel = Minecraft.getInstance().level;
		if (pauc$shadowCacheLevel == currentLevel) {
			return;
		}

		pauc$shadowCacheLevel = currentLevel;
		pauc$lastStableShadowChunks.clear();
		pauc$shadowChunkInfoCache.clear();
		pauc$stableMainCameraShadowCandidates.clear();
		pauc$stableMainCameraShadowCameraX = Double.NaN;
		pauc$stableMainCameraShadowCameraZ = Double.NaN;
		pauc$stableMainCameraShadowRadiusChunks = -1;
		pauc$cachedFallbackResult = null;
		pauc$cachedFallbackCamChunkX = Integer.MIN_VALUE;
		pauc$cachedFallbackCamChunkZ = Integer.MIN_VALUE;
		pauc$cachedFallbackBudget = -1;
		pauc$cachedFallbackSnapshotSize = -1;
		pauc$savedTicks = Integer.MIN_VALUE;
	}

	@Unique
	private void pauc$saveTraversalTickState() {
		if (!((Object) this instanceof LevelRendererAccessor accessor)) {
			return;
		}

		pauc$savedTicks = accessor.getTicks();
		accessor.setTicks(pauc$savedTicks + 1);
	}

	@Unique
	private void pauc$restoreTraversalTickState() {
		if (pauc$savedTicks == Integer.MIN_VALUE || !((Object) this instanceof LevelRendererAccessor accessor)) {
			return;
		}

		accessor.setTicks(pauc$savedTicks);
		pauc$savedTicks = Integer.MIN_VALUE;
	}

	@Unique
	private void pauc$rememberShadowChunks(ObjectArrayList<LevelRenderer.RenderChunkInfo> chunks, int chunkCount) {
		if (chunkCount <= 0 || chunks == null || chunks.isEmpty()) {
			return;
		}
		pauc$cacheShadowChunkInfos(chunks);
		pauc$lastStableShadowChunks = new ObjectArrayList<>(chunks);
	}

	@Unique
	private void pauc$mergeStableMainCameraShadowCandidates(ObjectArrayList<LevelRenderer.RenderChunkInfo> source) {
		if (source == null || source.isEmpty()) {
			return;
		}

		pauc$cacheShadowChunkInfos(source);
		pauc$refreshSortCamera();
		ObjectArrayList<LevelRenderer.RenderChunkInfo> merged =
			new ObjectArrayList<>(source.size() + pauc$stableMainCameraShadowCandidates.size());
		LongOpenHashSet seenOrigins = new LongOpenHashSet(Math.max(
			16,
			(source.size() + pauc$stableMainCameraShadowCandidates.size()) * 2
		));
		pauc$appendUniqueShadowChunks(merged, seenOrigins, source, Integer.MAX_VALUE);
		pauc$appendUniqueShadowChunks(merged, seenOrigins, pauc$stableMainCameraShadowCandidates, Integer.MAX_VALUE);
		merged.sort(Comparator.comparingDouble(this::pauc$shadowFallbackDistanceScore));
		while (merged.size() > PAUC_STABLE_MAIN_CAMERA_SHADOW_CACHE_LIMIT) {
			merged.remove(merged.size() - 1);
		}
		pauc$stableMainCameraShadowCandidates = merged;
	}

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$buildStableShadowFallbackFromViewArea(int budget, int retentionBudget) {
		if (!((Object) this instanceof LevelRendererAccessor accessor)) {
			return new ObjectArrayList<>(0);
		}

		ViewArea viewArea = accessor.getViewArea();
		if (!(viewArea instanceof ViewAreaAccessor viewAreaAccessor)) {
			return new ObjectArrayList<>(0);
		}

		ChunkRenderDispatcher.RenderChunk[] renderChunks = viewAreaAccessor.getChunks();
		if (renderChunks == null || renderChunks.length == 0) {
			return new ObjectArrayList<>(0);
		}

		Vec3 fallbackCamera = pauc$mainCameraPosition();
		int camChunkX = Mth.floor(fallbackCamera.x) >> 4;
		int camChunkZ = Mth.floor(fallbackCamera.z) >> 4;
		int primaryRadiusChunks = pauc$vanillaShadowZoneRadiusChunks();
		int retentionRadiusChunks = Math.max(primaryRadiusChunks + 2, pauc$localVanillaShadowRecoveryRadiusChunks());

		ObjectArrayList<LevelRenderer.RenderChunkInfo> primaryChunks =
			new ObjectArrayList<>(Math.min(budget, renderChunks.length));
		ObjectArrayList<LevelRenderer.RenderChunkInfo> retainedChunks =
			new ObjectArrayList<>(Math.min(retentionBudget, renderChunks.length));
		for (ChunkRenderDispatcher.RenderChunk renderChunk : renderChunks) {
			if (renderChunk == null) {
				continue;
			}

			BlockPos origin = renderChunk.getOrigin();
			if (origin == null) {
				continue;
			}

			int chunkX = origin.getX() >> 4;
			int chunkZ = origin.getZ() >> 4;
			int chunkDistance = Math.max(Math.abs(chunkX - camChunkX), Math.abs(chunkZ - camChunkZ));
			if (chunkDistance > retentionRadiusChunks) {
				continue;
			}

			LevelRenderer.RenderChunkInfo chunkInfo = pauc$getOrCreateShadowChunkInfo(renderChunk);
			if (chunkInfo == null) {
				continue;
			}

			if (chunkDistance <= primaryRadiusChunks) {
				primaryChunks.add(chunkInfo);
			} else {
				retainedChunks.add(chunkInfo);
			}
		}

		if (primaryChunks.isEmpty() && retainedChunks.isEmpty()) {
			return new ObjectArrayList<>(0);
		}

		pauc$refreshSortCamera();
		primaryChunks.sort(Comparator.comparingDouble(this::pauc$shadowFallbackDistanceScore));
		retainedChunks.sort(Comparator.comparingDouble(this::pauc$shadowFallbackDistanceScore));

		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks =
			new ObjectArrayList<>(Math.min(retentionBudget, primaryChunks.size() + retainedChunks.size() + pauc$lastStableShadowChunks.size()));
		LongOpenHashSet seenOrigins = new LongOpenHashSet(Math.max(16, retentionBudget * 2));
		boolean usedViewAreaPrimary = pauc$appendUniqueShadowChunks(selectedChunks, seenOrigins, primaryChunks, budget);
		boolean usedViewAreaRetention = pauc$appendUniqueShadowChunks(selectedChunks, seenOrigins, retainedChunks, retentionBudget);
		boolean usedLastStableShadow = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$lastStableShadowChunks,
			retentionBudget
		);

		if (usedViewAreaPrimary && !pauc$reportedViewAreaShadowFallback) {
			pauc$reportedViewAreaShadowFallback = true;
			Iris.logger.info("PauC shadow fallback rebuilt a stable shadow terrain set from loaded ViewArea chunks around the player.");
		}
		if ((usedViewAreaRetention || usedLastStableShadow) && !pauc$reportedStableShadowFallback) {
			pauc$reportedStableShadowFallback = true;
			Iris.logger.info("PauC shadow fallback backfilled the stable shadow terrain set from retained chunks while the loaded view area refreshed.");
		}

		return selectedChunks;
	}

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$buildStableShadowFallbackFromSnapshot(int budget, int retentionBudget) {
		pauc$refreshSortCamera();
		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks = new ObjectArrayList<>(retentionBudget);
		LongOpenHashSet seenOrigins = new LongOpenHashSet(Math.max(16, retentionBudget * 2));

		// Priority order matters for STABLE + SMOOTH shadows. The fresh main-camera snapshot follows the
		// player every frame and is the same set frame to frame when stationary, so it fills the primary
		// budget FIRST — this both follows movement smoothly and keeps the near zone identical each frame.
		// The previous frame's set is then retained (up to the retention budget) so a chunk briefly dropping
		// out during movement keeps its shadow instead of blinking. The radial candidate cache adds coverage
		// the current view frustum excludes, and the shader's own intermittent shadow-frustum set is appended
		// LAST as a pure bonus — it must never push the stable near chunks out, otherwise its frame-to-frame
		// on/off presence makes the region flicker.
		boolean usedMainCameraSnapshot = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$mainRenderChunksSnapshot,
			budget
		);
		boolean usedLastStableShadow = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$lastStableShadowChunks,
			retentionBudget
		);
		boolean usedMainCameraCache = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$stableMainCameraShadowCandidates,
			retentionBudget
		);
		pauc$appendClosestShadowChunks(selectedChunks, seenOrigins, renderChunksInFrustum, retentionBudget);

		if (usedMainCameraSnapshot && !pauc$reportedMainCameraShadowFallback) {
			pauc$reportedMainCameraShadowFallback = true;
			Iris.logger.info("PauC shadow fallback rebuilt a radial shadow terrain set from the live main-camera chunks around the player.");
		}
		if ((usedLastStableShadow || usedMainCameraCache) && !pauc$reportedStableShadowFallback) {
			pauc$reportedStableShadowFallback = true;
			Iris.logger.info("PauC shadow fallback backfilled the shadow terrain set from the stable cache during a transient main-camera shrink.");
		}

		return selectedChunks;
	}

	@Unique
	private int pauc$viewAreaChunkCount() {
		if (!((Object) this instanceof LevelRendererAccessor accessor)) {
			return 0;
		}

		ViewArea viewArea = accessor.getViewArea();
		if (!(viewArea instanceof ViewAreaAccessor viewAreaAccessor)) {
			return 0;
		}

		ChunkRenderDispatcher.RenderChunk[] renderChunks = viewAreaAccessor.getChunks();
		return renderChunks != null ? renderChunks.length : 0;
	}

	@Unique
	private void pauc$cacheShadowChunkInfos(ObjectArrayList<LevelRenderer.RenderChunkInfo> source) {
		if (source == null || source.isEmpty()) {
			return;
		}

		for (LevelRenderer.RenderChunkInfo chunkInfo : source) {
			if (chunkInfo == null || chunkInfo.chunk == null) {
				continue;
			}

			BlockPos origin = chunkInfo.chunk.getOrigin();
			if (origin != null) {
				pauc$shadowChunkInfoCache.put(origin.asLong(), chunkInfo);
			}
		}
	}

	@Unique
	private LevelRenderer.RenderChunkInfo pauc$getOrCreateShadowChunkInfo(ChunkRenderDispatcher.RenderChunk renderChunk) {
		if (renderChunk == null) {
			return null;
		}

		BlockPos origin = renderChunk.getOrigin();
		if (origin == null) {
			return null;
		}

		long originKey = origin.asLong();
		LevelRenderer.RenderChunkInfo cachedChunkInfo = pauc$shadowChunkInfoCache.get(originKey);
		if (cachedChunkInfo != null && cachedChunkInfo.chunk == renderChunk) {
			return cachedChunkInfo;
		}

		Constructor<LevelRenderer.RenderChunkInfo> constructor = pauc$renderChunkInfoConstructor();
		if (constructor == null) {
			return null;
		}

		try {
			LevelRenderer.RenderChunkInfo createdChunkInfo = constructor.newInstance(renderChunk, null, 0);
			pauc$shadowChunkInfoCache.put(originKey, createdChunkInfo);
			return createdChunkInfo;
		} catch (ReflectiveOperationException exception) {
			if (!pauc$reportedShadowChunkInfoConstructorFailure) {
				pauc$reportedShadowChunkInfoConstructorFailure = true;
				Iris.logger.warn("PauC failed to instantiate RenderChunkInfo for stable shader shadow fallback.", exception);
			}
			return null;
		}
	}

	@Unique
	private Constructor<LevelRenderer.RenderChunkInfo> pauc$renderChunkInfoConstructor() {
		if (pauc$renderChunkInfoConstructor != null) {
			return pauc$renderChunkInfoConstructor;
		}

		try {
			Constructor<LevelRenderer.RenderChunkInfo> constructor = LevelRenderer.RenderChunkInfo.class.getDeclaredConstructor(
				ChunkRenderDispatcher.RenderChunk.class,
				Direction.class,
				int.class
			);
			constructor.setAccessible(true);
			pauc$renderChunkInfoConstructor = constructor;
			return constructor;
		} catch (ReflectiveOperationException exception) {
			if (!pauc$reportedShadowChunkInfoConstructorFailure) {
				pauc$reportedShadowChunkInfoConstructorFailure = true;
				Iris.logger.warn("PauC failed to access RenderChunkInfo constructor for stable shader shadow fallback.", exception);
			}
			return null;
		}
	}

	@Unique
	private boolean pauc$appendClosestShadowChunks(
		ObjectArrayList<LevelRenderer.RenderChunkInfo> target,
		LongOpenHashSet seenOrigins,
		ObjectArrayList<LevelRenderer.RenderChunkInfo> source,
		int budget
	) {
		if (source == null || source.isEmpty() || target.size() >= budget) {
			return false;
		}

		ObjectArrayList<LevelRenderer.RenderChunkInfo> sortedChunks = new ObjectArrayList<>(source);
		sortedChunks.sort(Comparator.comparingDouble(this::pauc$shadowFallbackDistanceScore));
		return pauc$appendUniqueShadowChunks(target, seenOrigins, sortedChunks, budget);
	}

	@Unique
	private boolean pauc$appendUniqueShadowChunks(
		ObjectArrayList<LevelRenderer.RenderChunkInfo> target,
		LongOpenHashSet seenOrigins,
		ObjectArrayList<LevelRenderer.RenderChunkInfo> source,
		int budget
	) {
		if (source == null || source.isEmpty() || target.size() >= budget) {
			return false;
		}

		boolean appended = false;
		for (LevelRenderer.RenderChunkInfo chunkInfo : source) {
			if (chunkInfo == null || chunkInfo.chunk == null) {
				continue;
			}

			BlockPos origin = chunkInfo.chunk.getOrigin();
			if (origin == null || !seenOrigins.add(origin.asLong())) {
				continue;
			}

			target.add(chunkInfo);
			appended = true;
			if (target.size() >= budget) {
				break;
			}
		}
		return appended;
	}

	@Unique
	private int pauc$localVanillaShadowRecoveryRadiusChunks() {
		Minecraft minecraft = Minecraft.getInstance();
		int vanillaDistanceChunks = minecraft != null && minecraft.options != null
			? minecraft.options.getEffectiveRenderDistance()
			: 8;
		int shadowDistanceChunks = Math.max(0, ShadowRenderingState.getRenderDistance());
		int configuredRadius = shadowDistanceChunks > 0
			? Math.min(vanillaDistanceChunks, shadowDistanceChunks)
			: vanillaDistanceChunks;
		int junctionExtensionChunks = switch (PauCLodShaderProfiles.currentFamily()) {
			case SOLAS -> 3;
			case PHOTON -> 2;
			case PAUC -> 1;
			default -> 0;
		};
		int expandedRadius = configuredRadius + junctionExtensionChunks;
		if (shadowDistanceChunks > 0) {
			expandedRadius = Math.min(shadowDistanceChunks, expandedRadius);
		}
		return Math.max(4, expandedRadius);
	}

	@Unique
	private boolean pauc$shouldRefreshStableMainCameraShadowCandidates(int recoveryRadiusChunks) {
		if (pauc$mainRenderChunksSnapshot.isEmpty()) {
			return false;
		}
		if (!PauCLodShaderContext.isShaderPackInUse() || PauCLodShaderContext.hasScannedDhShadowProgram()) {
			return false;
		}
		if (pauc$stableMainCameraShadowCandidates.isEmpty() || recoveryRadiusChunks != pauc$stableMainCameraShadowRadiusChunks) {
			return true;
		}

		Vec3 cameraPosition = pauc$mainCameraPosition();
		if (!Double.isFinite(pauc$stableMainCameraShadowCameraX) || !Double.isFinite(pauc$stableMainCameraShadowCameraZ)) {
			return true;
		}

		double refreshDistanceBlocks = Math.max(8.0D, recoveryRadiusChunks * 4.0D);
		double cameraDriftBlocks = Math.max(
			Math.abs(cameraPosition.x - pauc$stableMainCameraShadowCameraX),
			Math.abs(cameraPosition.z - pauc$stableMainCameraShadowCameraZ)
		);
		if (cameraDriftBlocks >= refreshDistanceBlocks) {
			return true;
		}

		int snapshotSize = pauc$mainRenderChunksSnapshot.size();
		int candidateSize = pauc$stableMainCameraShadowCandidates.size();
		return snapshotSize > candidateSize + 256 || snapshotSize * 2 < candidateSize;
	}

	@Unique
	private void pauc$rememberStableMainCameraShadowCandidateState(int recoveryRadiusChunks) {
		Vec3 cameraPosition = pauc$mainCameraPosition();
		pauc$stableMainCameraShadowCameraX = cameraPosition.x;
		pauc$stableMainCameraShadowCameraZ = cameraPosition.z;
		pauc$stableMainCameraShadowRadiusChunks = recoveryRadiusChunks;
	}

	// Stable upper bound on the number of near chunks needed to shadow the entire vanilla render
	// distance plus a one-chunk junction margin. Derived from the render distance (not the momentary
	// snapshot size), so it does not shrink during a pitch swing — keeping coverage stable there too.
	@Unique
	private int pauc$vanillaShadowZoneBudget() {
		int radiusChunks = pauc$vanillaShadowZoneRadiusChunks();
		int squareEstimate = (2 * radiusChunks + 1) * (2 * radiusChunks + 1);
		return Math.min(2048, squareEstimate);
	}

	@Unique
	private int pauc$vanillaShadowZoneRadiusChunks() {
		Minecraft minecraft = Minecraft.getInstance();
		int renderDistanceChunks = minecraft != null && minecraft.options != null
			? minecraft.options.getEffectiveRenderDistance()
			: 8;
		// Render distance plus a two-chunk junction margin: the outer vanilla ring that meets the LOD horizon
		// is where coverage gaps appeared, so the shadow zone reaches a little past the vanilla edge.
		return Math.max(2, renderDistanceChunks) + 2;
	}

	@Unique
	private Vec3 pauc$mainCameraPosition() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && minecraft.gameRenderer != null
			? minecraft.gameRenderer.getMainCamera().getPosition()
			: Vec3.ZERO;
	}

	// Camera X/Z captured once per shadow-terrain rebuild. The distance score is evaluated O(n log n) times by
	// the sort comparator, so it must NOT re-resolve Minecraft.getInstance().getMainCamera().getPosition() per
	// comparison (that was tens of thousands of lookups per shadow frame).
	@Unique
	private double pauc$sortCameraX = Double.NaN;
	@Unique
	private double pauc$sortCameraZ = Double.NaN;

	// Result cache for the empty-setup fallback. The rebuilt set is nearly identical frame to frame (same
	// snapshot, same camera chunk), so we only re-copy/re-sort when the camera crosses a chunk, the budget
	// changes, or the snapshot size shifts meaningfully — turning a per-frame O(n log n) cost into per-move.
	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$cachedFallbackResult;
	@Unique
	private int pauc$cachedFallbackCamChunkX = Integer.MIN_VALUE;
	@Unique
	private int pauc$cachedFallbackCamChunkZ = Integer.MIN_VALUE;
	@Unique
	private int pauc$cachedFallbackBudget = -1;
	@Unique
	private int pauc$cachedFallbackSnapshotSize = -1;

	@Unique
	private void pauc$refreshSortCamera() {
		Vec3 cameraPosition = pauc$mainCameraPosition();
		pauc$sortCameraX = cameraPosition.x;
		pauc$sortCameraZ = cameraPosition.z;
	}

	@Unique
	private double pauc$shadowFallbackDistanceScore(LevelRenderer.RenderChunkInfo chunkInfo) {
		if (chunkInfo == null || chunkInfo.chunk == null) {
			return Double.MAX_VALUE;
		}

		double cameraX = pauc$sortCameraX;
		double cameraZ = pauc$sortCameraZ;
		if (Double.isNaN(cameraX) || Double.isNaN(cameraZ)) {
			Vec3 cameraPosition = pauc$mainCameraPosition();
			cameraX = cameraPosition.x;
			cameraZ = cameraPosition.z;
		}
		BlockPos origin = chunkInfo.chunk.getOrigin();
		double centerX = origin.getX() + 8.0D;
		double centerZ = origin.getZ() + 8.0D;
		return Math.max(Math.abs(centerX - cameraX), Math.abs(centerZ - cameraZ));
	}

	@Unique
	private void swap() {
		ObjectArrayList<LevelRenderer.RenderChunkInfo> temporaryChunks = renderChunksInFrustum;
		renderChunksInFrustum = pauc$savedRenderChunks;
		pauc$savedRenderChunks = temporaryChunks;

		double temporaryRotation = prevCamRotX;
		prevCamRotX = pauc$savedLastCameraPitch;
		pauc$savedLastCameraPitch = temporaryRotation;

		temporaryRotation = prevCamRotY;
		prevCamRotY = pauc$savedLastCameraYaw;
		pauc$savedLastCameraYaw = temporaryRotation;

		if (!pauc$reportedShadowCullingSwap) {
			pauc$reportedShadowCullingSwap = true;
			Iris.logger.info("PauC preserves the main terrain visibility list across shader shadow passes.");
		}
	}
}
