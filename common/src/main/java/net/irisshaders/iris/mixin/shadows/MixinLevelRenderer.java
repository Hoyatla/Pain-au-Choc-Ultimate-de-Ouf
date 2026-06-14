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
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$lastLocalVanillaShadowChunks =
		new ObjectArrayList<>(256);

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
	private double pauc$lastLocalVanillaShadowCameraX = Double.NaN;

	@Unique
	private double pauc$lastLocalVanillaShadowCameraZ = Double.NaN;

	@Unique
	private int pauc$lastLocalVanillaShadowRadiusChunks = -1;

	@Unique
	private static boolean pauc$reportedShadowCullingSwap;

	@Unique
	private static boolean pauc$reportedShadowCullingFallback;

	@Unique
	private static boolean pauc$reportedStableShadowFallback;

	@Unique
	private static boolean pauc$reportedMainCameraShadowFallback;

	@Unique
	private static PauCLodShaderProfiles.Family pauc$reportedLocalVanillaShadowRecoveryFamily;

	@Unique
	private static boolean pauc$reportedLocalVanillaShadowCacheReuse;

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
			return;
		}

		int availableChunks = pauc$mainRenderChunksSnapshot.size();
		int shadowSetupChunks = renderChunksInFrustum.size();
		if (pauc$shouldRecoverLocalVanillaShadowTerrain(availableChunks, shadowSetupChunks)) {
			ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks = pauc$buildLocalVanillaShadowRecovery(
				availableChunks
			);
			if (!selectedChunks.isEmpty()) {
				renderChunksInFrustum = selectedChunks;
				pauc$rememberShadowChunks(renderChunksInFrustum, renderChunksInFrustum.size());
				PauCLodShaderProfiles.Family currentFamily = PauCLodShaderProfiles.currentFamily();
				if (pauc$reportedLocalVanillaShadowRecoveryFamily != currentFamily) {
					pauc$reportedLocalVanillaShadowRecoveryFamily = currentFamily;
					Iris.logger.info(
						"PauC recovered {} local vanilla shadow chunks for {} after shader shadow terrain setup became {}; shadowSetup={}, radius={} chunks, {}",
						renderChunksInFrustum.size(),
						currentFamily.name().toLowerCase(),
						shadowSetupChunks == 0 ? "empty" : "too sparse",
						shadowSetupChunks,
						pauc$localVanillaShadowRecoveryRadiusChunks(),
						PauCLodShaderRuntime.describe()
					);
				}
				return;
			}
		}
		if (pauc$shouldLimitShadowFallbackToLocalVanillaRecovery()) {
			pauc$rememberShadowChunks(renderChunksInFrustum, shadowSetupChunks);
			return;
		}

		int budget = PauCLodShaderRuntime.shadowFallbackChunkBudget(availableChunks);
		if (budget <= 0) {
			pauc$rememberShadowChunks(renderChunksInFrustum, shadowSetupChunks);
			return;
		}
		if (!pauc$shouldRecoverShadowTerrainSetup(availableChunks)) {
			pauc$rememberShadowChunks(renderChunksInFrustum, shadowSetupChunks);
			return;
		}

		if (shadowSetupChunks >= budget) {
			pauc$rememberShadowChunks(renderChunksInFrustum, shadowSetupChunks);
			return;
		}

		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks = pauc$buildStableShadowFallback(budget);
		if (selectedChunks.isEmpty()) {
			return;
		}

		renderChunksInFrustum = selectedChunks;
		pauc$rememberShadowChunks(renderChunksInFrustum, renderChunksInFrustum.size());
		if (!pauc$reportedShadowCullingFallback) {
			pauc$reportedShadowCullingFallback = true;
			Iris.logger.info(
				"PauC restored {} shadow chunks from {} main-camera chunks after shader shadow terrain setup became {}; shadowSetup={}, {}",
				renderChunksInFrustum.size(),
				availableChunks,
				shadowSetupChunks == 0 ? "empty" : "too sparse",
				shadowSetupChunks,
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
		pauc$lastLocalVanillaShadowChunks.clear();
		pauc$stableMainCameraShadowCameraX = Double.NaN;
		pauc$stableMainCameraShadowCameraZ = Double.NaN;
		pauc$stableMainCameraShadowRadiusChunks = -1;
		pauc$lastLocalVanillaShadowCameraX = Double.NaN;
		pauc$lastLocalVanillaShadowCameraZ = Double.NaN;
		pauc$lastLocalVanillaShadowRadiusChunks = -1;
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
	private boolean pauc$shouldRecoverShadowTerrainSetup(int availableChunks) {
		if (renderChunksInFrustum.isEmpty()) {
			return true;
		}
		if (!PauCLodShaderContext.isShaderPackInUse()
			|| PauCLodShaderContext.hasScannedDhShadowProgram()
			|| availableChunks < 256) {
			return false;
		}

		PauCLodShaderProfiles.Family family = PauCLodShaderProfiles.currentFamily();
		int sparseThreshold = switch (family) {
			case SOLAS -> Math.max(96, (int) Math.round(availableChunks * 0.12D));
			case PHOTON -> Math.max(64, (int) Math.round(availableChunks * 0.08D));
			default -> 0;
		};
		return sparseThreshold > 0 && renderChunksInFrustum.size() < sparseThreshold;
	}

	@Unique
	private boolean pauc$shouldRecoverLocalVanillaShadowTerrain(int availableChunks, int shadowSetupChunks) {
		if (availableChunks < 64
			|| !pauc$usesLocalVanillaShadowRecoveryMode()) {
			return false;
		}

		PauCLodShaderProfiles.Family family = PauCLodShaderProfiles.currentFamily();
		int localBudget = pauc$localVanillaShadowRecoveryBudget(availableChunks);
		if (localBudget <= 0) {
			return false;
		}

		int sparseThreshold = switch (family) {
			case SOLAS -> Math.max(48, localBudget - 16);
			case PHOTON -> Math.max(40, localBudget - 24);
			default -> Math.max(32, localBudget / 3);
		};
		return shadowSetupChunks < sparseThreshold;
	}

	@Unique
	private boolean pauc$usesLocalVanillaShadowRecoveryMode() {
		if (!PauCLodShaderContext.isShaderPackInUse() || PauCLodShaderContext.hasScannedDhShadowProgram()) {
			return false;
		}

		PauCLodShaderProfiles.Family family = PauCLodShaderProfiles.currentFamily();
		return family == PauCLodShaderProfiles.Family.SOLAS || family == PauCLodShaderProfiles.Family.PHOTON;
	}

	@Unique
	private boolean pauc$shouldLimitShadowFallbackToLocalVanillaRecovery() {
		return pauc$usesLocalVanillaShadowRecoveryMode();
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
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$buildStableShadowFallback(int budget) {
		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks = new ObjectArrayList<>(budget);
		LongOpenHashSet seenOrigins = new LongOpenHashSet(Math.max(16, budget * 2));
		pauc$appendClosestShadowChunks(selectedChunks, seenOrigins, renderChunksInFrustum, budget);
		boolean usedLastStableShadow = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$lastStableShadowChunks,
			budget
		);
		boolean usedMainCameraCache = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$stableMainCameraShadowCandidates,
			budget
		);
		boolean usedMainCameraSnapshot = pauc$appendClosestShadowChunks(
			selectedChunks,
			seenOrigins,
			pauc$mainRenderChunksSnapshot,
			budget
		);

		if (usedLastStableShadow && !pauc$reportedStableShadowFallback) {
			pauc$reportedStableShadowFallback = true;
			Iris.logger.info("PauC shadow fallback reused the last stable shadow terrain set instead of the player camera frustum.");
		}
		if ((usedMainCameraCache || usedMainCameraSnapshot) && !pauc$reportedMainCameraShadowFallback) {
			pauc$reportedMainCameraShadowFallback = true;
			Iris.logger.info("PauC shadow fallback rebuilt a radial shadow terrain set from stable main-camera chunks around the player.");
		}

		return selectedChunks;
	}

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$buildLocalVanillaShadowRecovery(int availableChunks) {
		int budget = pauc$localVanillaShadowRecoveryBudget(availableChunks);
		if (budget <= 0) {
			return new ObjectArrayList<>(0);
		}

		int radiusChunks = pauc$localVanillaShadowRecoveryRadiusChunks();
		double maxDistanceBlocks = radiusChunks * 16.0D;
		int minimumUsefulChunks = Math.max(24, budget / 3);

		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks = new ObjectArrayList<>(budget);
		LongOpenHashSet seenOrigins = new LongOpenHashSet(Math.max(16, budget * 2));
		boolean reusedLocalRecovery = pauc$canReuseLocalVanillaShadowRecovery(budget, radiusChunks);
		boolean usedLocalRecoveryCache = reusedLocalRecovery && pauc$appendClosestShadowChunksWithinDistance(
			selectedChunks,
			seenOrigins,
			pauc$lastLocalVanillaShadowChunks,
			budget,
			maxDistanceBlocks
		);
		pauc$appendUniqueShadowChunks(selectedChunks, seenOrigins, renderChunksInFrustum, budget);
		pauc$appendClosestShadowChunksWithinDistance(
			selectedChunks,
			seenOrigins,
			pauc$lastStableShadowChunks,
			budget,
			maxDistanceBlocks
		);
		pauc$appendClosestShadowChunksWithinDistance(
			selectedChunks,
			seenOrigins,
			pauc$stableMainCameraShadowCandidates,
			budget,
			maxDistanceBlocks
		);
		if (!reusedLocalRecovery || selectedChunks.size() < minimumUsefulChunks) {
			pauc$appendClosestShadowChunksWithinDistance(
				selectedChunks,
				seenOrigins,
				pauc$mainRenderChunksSnapshot,
				budget,
				maxDistanceBlocks
			);
		}
		if (!reusedLocalRecovery) {
			pauc$appendClosestShadowChunksWithinDistance(
				selectedChunks,
				seenOrigins,
				pauc$lastLocalVanillaShadowChunks,
				budget,
				maxDistanceBlocks
			);
		}
		if (usedLocalRecoveryCache && !pauc$reportedLocalVanillaShadowCacheReuse) {
			pauc$reportedLocalVanillaShadowCacheReuse = true;
			Iris.logger.info("PauC reused the stable local vanilla shadow cache before current player-camera visibility to avoid pitch-dependent shadow loss.");
		}
		pauc$rememberLocalVanillaShadowRecovery(selectedChunks, radiusChunks, minimumUsefulChunks);
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
	private boolean pauc$appendClosestShadowChunksWithinDistance(
		ObjectArrayList<LevelRenderer.RenderChunkInfo> target,
		LongOpenHashSet seenOrigins,
		ObjectArrayList<LevelRenderer.RenderChunkInfo> source,
		int budget,
		double maxDistanceBlocks
	) {
		if (source == null || source.isEmpty() || target.size() >= budget) {
			return false;
		}

		ObjectArrayList<LevelRenderer.RenderChunkInfo> sortedChunks = new ObjectArrayList<>(source);
		sortedChunks.sort(Comparator.comparingDouble(this::pauc$shadowFallbackDistanceScore));
		return pauc$appendUniqueShadowChunksWithinDistance(target, seenOrigins, sortedChunks, budget, maxDistanceBlocks);
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
	private boolean pauc$appendUniqueShadowChunksWithinDistance(
		ObjectArrayList<LevelRenderer.RenderChunkInfo> target,
		LongOpenHashSet seenOrigins,
		ObjectArrayList<LevelRenderer.RenderChunkInfo> source,
		int budget,
		double maxDistanceBlocks
	) {
		if (source == null || source.isEmpty() || target.size() >= budget) {
			return false;
		}

		boolean appended = false;
		for (LevelRenderer.RenderChunkInfo chunkInfo : source) {
			if (chunkInfo == null || chunkInfo.chunk == null || !pauc$isShadowChunkWithinDistance(chunkInfo, maxDistanceBlocks)) {
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
	private boolean pauc$isShadowChunkWithinDistance(LevelRenderer.RenderChunkInfo chunkInfo, double maxDistanceBlocks) {
		return pauc$shadowFallbackDistanceScore(chunkInfo) <= maxDistanceBlocks;
	}

	@Unique
	private int pauc$localVanillaShadowRecoveryBudget(int availableChunks) {
		if (availableChunks <= 0) {
			return 0;
		}

		PauCLodShaderProfiles.Family family = PauCLodShaderProfiles.currentFamily();
		int pressureBudget = switch (family) {
			case SOLAS -> switch (PauCLodShaderRuntime.pressure()) {
				case RELIEF -> 112;
				case BALANCED -> 144;
				case HEADROOM -> 192;
				default -> 144;
			};
			case PHOTON -> switch (PauCLodShaderRuntime.pressure()) {
				case RELIEF -> 80;
				case BALANCED -> 112;
				case HEADROOM -> 144;
				default -> 112;
			};
			default -> 0;
		};
		if (pressureBudget <= 0) {
			return 0;
		}

		int radiusChunks = pauc$localVanillaShadowRecoveryRadiusChunks();
		int estimatedVisibleChunks = Math.max(48, radiusChunks * radiusChunks);
		return Math.max(24, Math.min(availableChunks, Math.min(pressureBudget, estimatedVisibleChunks)));
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

	@Unique
	private boolean pauc$canReuseLocalVanillaShadowRecovery(int budget, int radiusChunks) {
		if (pauc$lastLocalVanillaShadowChunks.isEmpty()
			|| radiusChunks != pauc$lastLocalVanillaShadowRadiusChunks
			|| pauc$lastLocalVanillaShadowChunks.size() < Math.max(24, budget / 3)) {
			return false;
		}

		if (!Double.isFinite(pauc$lastLocalVanillaShadowCameraX) || !Double.isFinite(pauc$lastLocalVanillaShadowCameraZ)) {
			return false;
		}

		Vec3 cameraPosition = pauc$mainCameraPosition();
		double reuseDistanceBlocks = Math.max(8.0D, radiusChunks * 8.0D);
		double cameraDriftBlocks = Math.max(
			Math.abs(cameraPosition.x - pauc$lastLocalVanillaShadowCameraX),
			Math.abs(cameraPosition.z - pauc$lastLocalVanillaShadowCameraZ)
		);
		return cameraDriftBlocks < reuseDistanceBlocks;
	}

	@Unique
	private void pauc$rememberLocalVanillaShadowRecovery(
		ObjectArrayList<LevelRenderer.RenderChunkInfo> chunks,
		int radiusChunks,
		int minimumUsefulChunks
	) {
		if (chunks == null || chunks.size() < minimumUsefulChunks) {
			return;
		}

		pauc$lastLocalVanillaShadowChunks = new ObjectArrayList<>(chunks);
		Vec3 cameraPosition = pauc$mainCameraPosition();
		pauc$lastLocalVanillaShadowCameraX = cameraPosition.x;
		pauc$lastLocalVanillaShadowCameraZ = cameraPosition.z;
		pauc$lastLocalVanillaShadowRadiusChunks = radiusChunks;
	}

	@Unique
	private Vec3 pauc$mainCameraPosition() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && minecraft.gameRenderer != null
			? minecraft.gameRenderer.getMainCamera().getPosition()
			: Vec3.ZERO;
	}

	@Unique
	private double pauc$shadowFallbackDistanceScore(LevelRenderer.RenderChunkInfo chunkInfo) {
		if (chunkInfo == null || chunkInfo.chunk == null) {
			return Double.MAX_VALUE;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Vec3 cameraPosition = minecraft != null && minecraft.gameRenderer != null
			? minecraft.gameRenderer.getMainCamera().getPosition()
			: Vec3.ZERO;
		BlockPos origin = chunkInfo.chunk.getOrigin();
		double centerX = origin.getX() + 8.0D;
		double centerZ = origin.getZ() + 8.0D;
		return Math.max(Math.abs(centerX - cameraPosition.x), Math.abs(centerZ - cameraPosition.z));
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
