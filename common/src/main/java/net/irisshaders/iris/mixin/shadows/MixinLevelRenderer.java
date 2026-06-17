package net.irisshaders.iris.mixin.shadows;

import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Comparator;

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
	private static boolean pauc$reportedMainCameraShadowFallback;

	@Override
	public void saveState() {
		pauc$syncShadowCacheLevel();
		pauc$mainRenderChunksSnapshot = new ObjectArrayList<>(renderChunksInFrustum);
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
		if (pauc$mainRenderChunksSnapshot.isEmpty()) {
			// Warmup / transition frame: the main camera has not produced its visible-chunk list yet.
			// If the shader shadow setup is also empty but we still hold a recent stable shadow set, keep
			// using it so terrain shadows do not blink off for these few frames while the world settles.
			if (renderChunksInFrustum.isEmpty() && !pauc$lastStableShadowChunks.isEmpty()) {
				renderChunksInFrustum = new ObjectArrayList<>(pauc$lastStableShadowChunks);
			}
			return;
		}

		// Opt-out: if the pack drives its own shadow terrain and explicitly disables PauC's fallback,
		// leave whatever the shadow setup produced untouched.
		int availableChunks = pauc$mainRenderChunksSnapshot.size();
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
			renderChunksInFrustum = pauc$cachedFallbackResult;
			return;
		}

		// Retention headroom: keep the previous frame's shadowed chunks for a frame or two beyond the live
		// zone so that a chunk briefly dropping out of the snapshot during movement (a leading-edge rebuild
		// or a partial main-render list on a heavy frame) does not blink its shadow off. Bounded at 1.5x so
		// the extra depth-only near draws stay cheap.
		int retentionBudget = budget + (budget / 2);

		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks = pauc$buildStableShadowFallback(budget, retentionBudget);
		if (selectedChunks.isEmpty()) {
			return;
		}

		renderChunksInFrustum = selectedChunks;
		pauc$cachedFallbackResult = selectedChunks;
		pauc$cachedFallbackCamChunkX = camChunkX;
		pauc$cachedFallbackCamChunkZ = camChunkZ;
		pauc$cachedFallbackBudget = budget;
		pauc$cachedFallbackSnapshotSize = availableChunks;
		pauc$rememberShadowChunks(renderChunksInFrustum, renderChunksInFrustum.size());
		if (!pauc$reportedShadowCullingFallback) {
			pauc$reportedShadowCullingFallback = true;
			Iris.logger.info(
				"PauC rebuilt {} shadow terrain chunks from {} stable main-camera chunks after shader shadow terrain setup returned empty; {}",
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
		pauc$lastStableShadowChunks = new ObjectArrayList<>(chunks);
	}

	@Unique
	private void pauc$mergeStableMainCameraShadowCandidates(ObjectArrayList<LevelRenderer.RenderChunkInfo> source) {
		if (source == null || source.isEmpty()) {
			return;
		}

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
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$buildStableShadowFallback(int budget, int retentionBudget) {
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
		Minecraft minecraft = Minecraft.getInstance();
		int renderDistanceChunks = minecraft != null && minecraft.options != null
			? minecraft.options.getEffectiveRenderDistance()
			: 8;
		// Render distance plus a two-chunk junction margin: the outer vanilla ring that meets the LOD horizon
		// is where coverage gaps appeared, so the shadow zone reaches a little past the vanilla edge.
		int radiusChunks = Math.max(2, renderDistanceChunks) + 2;
		int squareEstimate = (2 * radiusChunks + 1) * (2 * radiusChunks + 1);
		return Math.min(2048, squareEstimate);
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
