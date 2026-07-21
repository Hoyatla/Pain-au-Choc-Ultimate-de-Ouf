package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodCudaBridge;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodNearClipOverride;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import fr.hoyatla.pauc.platform.forge.diagnostics.PauCLodReloadDiagnostics;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCTaskPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class PauCClientFrontierWarmupManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final int LOD_CACHE_STATE_DIRTY = 0;
	public static final int LOD_CACHE_STATE_METADATA_CLEAN = 1;
	public static final int LOD_CACHE_STATE_CPU_PREPARED = 2;
	public static final int LOD_CACHE_STATE_CUDA_PREPARED = 3;
	public static final int LOD_CACHE_STATE_RENDER_READY = 4;
	public static final int LOD_QUALITY_UNKNOWN = 0;
	public static final int LOD_QUALITY_COARSE = 1;
	public static final int LOD_QUALITY_MEDIUM = 2;
	public static final int LOD_QUALITY_FINE = 3;
	private static final int WARM_CACHE_SCHEMA_VERSION = 3;
	private static final int PLAN_COMPLETION_BUDGET_PER_TICK = 12;
	private static final int PLAN_COMPLETION_BUDGET_MIN = 1;
	private static final long PLAN_RESCHEDULE_COOLDOWN_MS = 150L;
	private static final long STALE_RECORD_TTL_MS = 120_000L;
	private static final String PRESENTATION_STABILIZE_RATIO_PROPERTY = "pauc.lod.stabilizePresentationCoverageRatio";
	private static final String SHADER_PRESENTATION_STABILIZE_RATIO_PROPERTY = "pauc.lod.shaderStabilizePresentationCoverageRatio";
	private static final String PRESENTATION_STABILIZE_HOLD_MS_PROPERTY = "pauc.lod.stabilizePresentationHoldMs";
	private static final String COARSE_FILL_COVERAGE_RATIO_PROPERTY = "pauc.lod.coarseFillCoverageRatio";
	private static final String COARSE_FILL_CATCHUP_COVERAGE_RATIO_PROPERTY = "pauc.lod.coarseFillCatchupCoverageRatio";
	private static final String DIRECT_HORIZON_FILL_PROPERTY = "pauc.lod.directHorizonFill";
	private static final String DIRECT_HORIZON_FILL_MAX_TARGET_DISTANCE_PROPERTY = "pauc.lod.directHorizonFillMaxTargetDistanceChunks";
	private static final String DISK_SEED_ENABLED_PROPERTY = "pauc.client.cache.diskSeedEnabled";
	private static final String DISK_SEED_INTERVAL_MS_PROPERTY = "pauc.client.cache.diskSeedIntervalMs";
	private static final String DISK_SEED_REGION_TTL_MS_PROPERTY = "pauc.client.cache.diskSeedRegionTtlMs";
	private static final String DISK_SEED_MAX_PENDING_REGIONS_PROPERTY = "pauc.client.cache.diskSeedMaxPendingRegions";
	private static final String DISK_SEED_MAX_REGIONS_PER_TICK_PROPERTY = "pauc.client.cache.diskSeedMaxRegionsPerTick";
	private static final String DISK_SEED_MAX_CHUNKS_PER_REGION_PROPERTY = "pauc.client.cache.diskSeedMaxChunksPerRegion";
	private static final String DISK_SEED_DRAIN_BATCHES_PROPERTY = "pauc.client.cache.diskSeedDrainBatchesPerTick";
	private static final String DISK_SEED_DRAIN_CHUNKS_PROPERTY = "pauc.client.cache.diskSeedDrainChunksPerTick";
	private static final String HOT_RESTORE_DURATION_MS_PROPERTY = "pauc.client.cache.hotRestoreDurationMs";
	private static final String HOT_RESTORE_COVERAGE_TARGET_PROPERTY = "pauc.client.cache.hotRestoreCoverageTarget";
	private static final String HOT_RESTORE_MIN_RENDER_READY_PROPERTY = "pauc.client.cache.hotRestoreMinRenderReady";
	private static final String CUDA_WORLD_CACHE_ENABLED_PROPERTY = "pauc.lod.cuda.worldCachePreparation";
	private static final String CUDA_WORLD_CACHE_INTERVAL_MS_PROPERTY = "pauc.lod.cuda.worldCacheIntervalMs";
	private static final String CUDA_WORLD_CACHE_MAX_PENDING_PROPERTY = "pauc.lod.cuda.worldCacheMaxPendingBatches";
	private static final String CUDA_WORLD_CACHE_BATCH_FEATURES_PROPERTY = "pauc.lod.cuda.worldCacheBatchFeatures";
	private static final String CUDA_WORLD_CACHE_DRAIN_CELLS_PROPERTY = "pauc.lod.cuda.worldCacheDrainCells";
	private static final String CUDA_WORLD_CACHE_PERSIST_BATCH_CELLS_PROPERTY = "pauc.lod.cuda.worldCachePersistBatchCells";
	private static final String CUDA_WORLD_CACHE_RETRY_MS_PROPERTY = "pauc.lod.cuda.worldCacheRetryMs";
	private static final String CUDA_WORLD_CACHE_MIN_SCORE_PROPERTY = "pauc.lod.cuda.worldCacheMinScore";
	private static final String CUDA_WORLD_CACHE_BACKGROUND_EXTRA_PROPERTY = "pauc.lod.cuda.worldCacheBackgroundExtraChunks";
	private static final String CUDA_WORLD_CACHE_LOG_INTERVAL_MS_PROPERTY = "pauc.lod.cuda.worldCacheLogIntervalMs";
	private static final int[] FILL_BANDS = { 32, 64, 96, 128, 256 };
	private static final ConcurrentMap<Long, WarmChunkRecord> TRACKED_CHUNKS = new ConcurrentHashMap<>();
	private static final Set<Long> PENDING_PLANS = ConcurrentHashMap.newKeySet();
	private static final ConcurrentLinkedDeque<PreparedWarmPlan> COMPLETED_PLANS = new ConcurrentLinkedDeque<>();
	private static final Set<String> PENDING_DISK_SEED_REGIONS = ConcurrentHashMap.newKeySet();
	private static final ConcurrentMap<String, Long> DISK_SEED_REGION_SCAN_TIMES = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedDeque<List<WarmChunkMetadata>> COMPLETED_DISK_SEEDS = new ConcurrentLinkedDeque<>();
	private static final Set<Long> PENDING_CUDA_PREP = ConcurrentHashMap.newKeySet();
	private static final ConcurrentLinkedDeque<CudaPreparedBatch> COMPLETED_CUDA_PREPARATIONS = new ConcurrentLinkedDeque<>();
	private static final AtomicInteger SESSION_GENERATION = new AtomicInteger();
	@Nullable
	private static volatile Field clientChunkStorageField;
	@Nullable
	private static volatile Field clientChunkStorageChunksField;
	private static volatile boolean storageReflectionResolved;
	private static volatile boolean storageReflectionFailureLogged;
	@Nullable
	private static volatile String lastKnownDimension;
	private static volatile CoverageSnapshot lastCoverageSnapshot = CoverageSnapshot.unavailable();
	private static volatile PauCClientMemoryBudgetController.BudgetSnapshot lastBudget = PauCClientMemoryBudgetController.capture(12, 3);
	private static volatile long lastKnownRetainedRamBytes;
	private static volatile int lastKnownRetainedChunks;
	private static volatile int lastKnownHotMeshSections;
	private static volatile int lastKnownQueuedMeshSections;
	private static volatile int zeroVisibleChunkStreak;
	private static volatile boolean lastSnapMode;
	private static volatile boolean lastFastTravel;
	private static volatile boolean lastMovementCatchup;
	private static volatile long lastCoveragePresentationHoldUntilMillis;
	private static volatile long lastCoverageHoldDemandAtMillis;
	private static volatile long lastConfiguredTargetChangeAtMillis;
	private static volatile long lastDiskSeedScheduleAtMillis;
	private static volatile long lastCudaPreparationScheduleAtMillis;
	private static volatile long lastCudaPreparationLogAtMillis;
	private static volatile String lastCudaPreparationStatus = "not-run";
	private static volatile int lastCudaScheduledCells;
	private static volatile int lastCudaCompletedCells;
	private static volatile boolean hotRestoreActive;
	private static volatile long sessionResumedAtMillis;
	private static volatile int lastHotRestoreQueued;
	private static volatile int lastHotRestoreApplied;
	private static volatile int lastHotRestoreRenderReady;
	private static volatile long lastHotRestoreCompletionAtMillis;
	private static volatile int lastWarmupBaseSectionBudget;
	private static volatile int lastWarmupLimitedSectionBudget;
	private static volatile int lastWarmupGrantedSectionBudget;
	private static volatile int lastWarmupCompletionBudget;
	private static volatile int lastWarmupAppliedPlans;
	private static volatile int lastWarmupScheduledSections;
	private static volatile int lastVisualRecoveryBudget;
	private static volatile int lastVisualRecoveryGrantedBudget;
	private static volatile int lastVisualRecoveryScheduledSections;
	private static final Comparator<WarmChunkRecord> PLAN_CANDIDATE_ORDER = (left, right) -> {
		int hotRestoreCompare = Integer.compare(hotRestorePriority(left), hotRestorePriority(right));
		if (hotRestoreCompare != 0) {
			return hotRestoreCompare;
		}
		int scoreCompare = Double.compare(right.lastPriorityScore, left.lastPriorityScore);
		if (scoreCompare != 0) {
			return scoreCompare;
		}
		int radialCompare = Double.compare(left.currentRadialDistance, right.currentRadialDistance);
		if (radialCompare != 0) {
			return radialCompare;
		}
		return Integer.compare(left.currentDistance, right.currentDistance);
	};
	private static final Comparator<WarmChunkRecord> PROXY_CELL_ORDER = (left, right) -> {
		int distanceCompare = Integer.compare(left.currentDistance, right.currentDistance);
		if (distanceCompare != 0) {
			return distanceCompare;
		}
		int radialCompare = Double.compare(left.currentRadialDistance, right.currentRadialDistance);
		if (radialCompare != 0) {
			return radialCompare;
		}
		return Double.compare(right.lastPriorityScore, left.lastPriorityScore);
	};
	private static final Comparator<RetentionCandidate> STALE_RETENTION_ORDER = Comparator
		.comparingDouble(RetentionCandidate::score)
		.thenComparingLong(RetentionCandidate::retainedAtMillis);
	private static final Comparator<Map.Entry<Long, WarmChunkRecord>> TRACKED_RECORD_PRUNE_ORDER = Comparator
		.comparingInt((Map.Entry<Long, WarmChunkRecord> entry) -> entry.getValue().lodCacheState >= LOD_CACHE_STATE_CUDA_PREPARED ? 1 : 0)
		.thenComparingDouble(entry -> entry.getValue().lastPriorityScore)
		.thenComparingLong(entry -> entry.getValue().lastSeenAtMillis);

	private PauCClientFrontierWarmupManager() {
	}

	public static boolean shouldRetainChunk(Minecraft minecraft, ClientLevel level, int chunkX, int chunkZ) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE)) {
			return true;
		}

		PauCClientChunkPriorityScorer.PriorityFrame frame = PauCClientChunkPriorityScorer.capture(minecraft, level, PauCClientChunkRetentionManager.getRetentionMarginChunks());
		if (frame == null) {
			return false;
		}

		PauCClientChunkPriorityScorer.ChunkPriority priority = PauCClientChunkPriorityScorer.score(frame, chunkX, chunkZ, true);
		if (!priority.shouldRetain()) {
			return false;
		}

		long chunkKey = new ChunkPos(chunkX, chunkZ).toLong();
		WarmChunkRecord record = TRACKED_CHUNKS.computeIfAbsent(chunkKey, ignored -> WarmChunkRecord.placeholder(level.dimension().location().toString(), new ChunkPos(chunkKey)));
		record.markRetained(priority.score(), System.currentTimeMillis());
		return true;
	}

	public static void onChunkDataReady(ClientLevel level, @Nullable LevelChunk chunk) {
		if (chunk == null || !PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE)) {
			return;
		}

		PauCCompatManager.logActionOnce(
			PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE,
			"enabled",
			"PauC enabled the client frontier warmup pipeline with RAM/GPU budgets, FOV prioritization, and async planning."
		);

		WarmChunkMetadata metadata = WarmChunkMetadata.capture(level, chunk);
		TRACKED_CHUNKS.compute(metadata.chunkPos().toLong(), (chunkKey, existing) -> {
			WarmChunkRecord record = existing != null ? existing : WarmChunkRecord.placeholder(metadata.dimensionId(), metadata.chunkPos());
			record.applyMetadata(metadata);
			record.live = true;
			record.retained = false;
			record.lastSeenAtMillis = System.currentTimeMillis();
			return record;
		});
		lastKnownDimension = metadata.dimensionId();
		PauCClientWarmChunkDiskCache.persist(level, metadata);
	}

	public static void onChunkDropped(@Nullable ClientLevel level, long chunkKey) {
		WarmChunkRecord record = TRACKED_CHUNKS.get(chunkKey);
		if (record == null) {
			return;
		}

		record.live = false;
		if (level == null) {
			record.retained = false;
		}
		record.lastSeenAtMillis = System.currentTimeMillis();
	}

	public static void onRetainedChunkReleased(long chunkKey) {
		WarmChunkRecord record = TRACKED_CHUNKS.get(chunkKey);
		if (record != null) {
			record.retained = false;
		}
	}

	public static void onClientTick(ClientLevel level, Map<Long, PauCClientChunkRetentionManager.RetainedChunkState> retainedChunks) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE)) {
			return;
		}

		lastKnownDimension = level.dimension().location().toString();
		PauCClientChunkPriorityScorer.PriorityFrame frame = PauCClientChunkPriorityScorer.capture(Minecraft.getInstance(), level, PauCClientChunkRetentionManager.getRetentionMarginChunks());
		if (frame == null) {
			return;
		}

		lastBudget = PauCClientMemoryBudgetController.capture(frame.renderDistanceChunks(), Math.max(0, frame.warmRadiusChunks() - frame.renderDistanceChunks()));
		PauCorRendererBridge.RendererStats rendererStats = PauCorRendererBridge.getStats(level);
		if (!rendererStats.available()) {
			PauCCompatManager.logActionOnce(
				PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE,
				"paucor-unavailable-metadata",
				"PauC keeps client frontier warmup in metadata mode while the PauCor renderer bridge is unavailable."
			);
			seedTrackedChunksFromLiveStorage(level);
			drainDiskSeedResults(level, frame);
			refreshPriorities(frame, retainedChunks);
			lastCoverageSnapshot = CoverageSnapshot.capture(level, frame, false, TRACKED_CHUNKS.values());
			refreshHotRestoreState();
			updateTerrainContinuityHold();
			requestCoarseFillRenderRefreshIfNeeded();
			lastKnownQueuedMeshSections = 0;
			lastKnownHotMeshSections = 0;
			lastKnownRetainedChunks = retainedChunks.size();
			zeroVisibleChunkStreak = 0;
			PENDING_PLANS.clear();
			COMPLETED_PLANS.clear();
			PauCClientUploadBudgetController.reset();
			PauCClientRenderPrep.reset();
			lastSnapMode = frame.snapMode();
			lastFastTravel = frame.fastTravel();
			lastMovementCatchup = frame.movementCatchup();
			drainCompletedCudaPreparations(level);
			scheduleCudaPreparations(level, frame);
			scheduleDiskSeedScan(level, frame);
			pruneTrackedRecords();
			return;
		}

		seedTrackedChunksFromLiveStorage(level);
		drainDiskSeedResults(level, frame);
		refreshPriorities(frame, retainedChunks);
		lastCoverageSnapshot = CoverageSnapshot.capture(level, frame, true, TRACKED_CHUNKS.values());
		refreshHotRestoreState();
		updateTerrainContinuityHold();
		requestCoarseFillRenderRefreshIfNeeded();
		refreshHotMeshCounts(level, rendererStats);
		PauCClientUploadBudgetController.onClientTick(
			Minecraft.getInstance(),
			rendererStats,
			lastBudget,
			frame.fastTravel() || frame.snapMode() || frame.movementCatchup()
		);
		PauCClientRenderPrep.onClientTick(level, frame, lastBudget, rendererStats);

		runVisualRecovery(level, frame, rendererStats);
		lastSnapMode = frame.snapMode();
		lastFastTravel = frame.fastTravel();
		lastMovementCatchup = frame.movementCatchup();
		drainCompletedCudaPreparations(level);
		scheduleCudaPreparations(level, frame);
		scheduleDiskSeedScan(level, frame);
		drainCompletedPlans(level, frame);
		schedulePlans(level, frame);
		pruneTrackedRecords();
	}

	public static List<Long> collectRetentionEvictions(ClientLevel level, Map<Long, PauCClientChunkRetentionManager.RetainedChunkState> retainedChunks) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE) || retainedChunks.isEmpty()) {
			return List.of();
		}

		List<RetentionCandidate> candidates = new ArrayList<>(retainedChunks.size());
		long retainedRamBytes = 0L;
		for (Map.Entry<Long, PauCClientChunkRetentionManager.RetainedChunkState> entry : retainedChunks.entrySet()) {
			WarmChunkRecord record = TRACKED_CHUNKS.get(entry.getKey());
			if (record == null || !record.dimensionId.equals(level.dimension().location().toString())) {
				candidates.add(new RetentionCandidate(entry.getKey(), Double.NEGATIVE_INFINITY, entry.getValue().retainedAtMillis(), 0L, true));
				continue;
			}

			retainedRamBytes += record.estimatedRamBytes;
			boolean protectFastAhead = (lastFastTravel || lastMovementCatchup) && record.ahead && record.currentDistance <= PauCClientChunkRetentionManager.getRetentionRadiusChunks();
			boolean protectSnapRing = lastSnapMode && record.currentDistance <= PauCClientChunkRetentionManager.getRetentionRadiusChunks();
			boolean stale = !protectFastAhead && !protectSnapRing && (record.lastPriorityScore < 0.20D
				|| record.currentDistance > PauCClientChunkRetentionManager.getRetentionRadiusChunks()
				|| (!record.ahead && record.currentDistance > Math.max(2, PauCClientChunkRetentionManager.getRetentionRadiusChunks() - 2)));
			candidates.add(new RetentionCandidate(entry.getKey(), record.lastPriorityScore, entry.getValue().retainedAtMillis(), record.estimatedRamBytes, stale));
		}

		lastKnownRetainedRamBytes = retainedRamBytes;
		lastKnownRetainedChunks = candidates.size();

		boolean overRetainedChunks = candidates.size() > lastBudget.maxRetainedChunks();
		boolean overRamBudget = retainedRamBytes > lastBudget.ramBudgetBytes();
		if (!overRetainedChunks && !overRamBudget) {
			List<RetentionCandidate> staleCandidates = new ArrayList<>(8);
			for (RetentionCandidate candidate : candidates) {
				if (candidate.stale()) {
					addBoundedCandidate(staleCandidates, candidate, 8, STALE_RETENTION_ORDER);
				}
			}
			staleCandidates.sort(STALE_RETENTION_ORDER);
			List<Long> evictions = new ArrayList<>(staleCandidates.size());
			for (RetentionCandidate candidate : staleCandidates) {
				evictions.add(candidate.chunkKey());
			}
			return evictions;
		}

		List<RetentionCandidate> ordered = new ArrayList<>(candidates);
		ordered.sort(Comparator
			.comparing(RetentionCandidate::stale).reversed()
			.thenComparingDouble(RetentionCandidate::score)
			.thenComparingLong(RetentionCandidate::retainedAtMillis));

		List<Long> evictions = new ArrayList<>();
		int retainedCount = candidates.size();
		long ramBytes = retainedRamBytes;
		for (RetentionCandidate candidate : ordered) {
			if (retainedCount <= lastBudget.maxRetainedChunks() && ramBytes <= lastBudget.ramBudgetBytes() && !candidate.stale()) {
				break;
			}

			evictions.add(candidate.chunkKey());
			retainedCount--;
			ramBytes = Math.max(0L, ramBytes - candidate.estimatedRamBytes());
		}

		return evictions;
	}

	public static void onClientSessionResumed() {
		resetState(true);
	}

	public static void onClientLogoutStarted() {
		resetState(false);
	}

	public static void onClientLevelUnload() {
		resetState(false);
	}

	public static String describeState() {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE)) {
			return "frontierWarm[disabled]";
		}

		return "frontierWarm[tracked="
			+ TRACKED_CHUNKS.size()
			+ ", pending="
			+ PENDING_PLANS.size()
			+ ", ready="
			+ COMPLETED_PLANS.size()
			+ ", retainedRam="
			+ (lastKnownRetainedRamBytes / (1024L * 1024L))
			+ "MiB"
			+ ", retained="
			+ lastKnownRetainedChunks
			+ ", hotMesh="
			+ lastKnownHotMeshSections
			+ ", queuedMesh="
			+ lastKnownQueuedMeshSections
			+ ", zeroVisibleStreak="
			+ zeroVisibleChunkStreak
			+ ", snap="
			+ lastSnapMode
			+ ", fastTravel="
			+ lastFastTravel
			+ ", movementCatchup="
			+ lastMovementCatchup
			+ ", "
			+ lastCoverageSnapshot.describe()
			+ ", "
			+ describeHotRestoreState()
			+ ", dimension="
			+ (lastKnownDimension != null ? lastKnownDimension : "-")
			+ ", "
			+ PauCEmbeddedLodRuntimeDiagnostics.describeFillPresentationState()
			+ ", "
			+ lastBudget.describe()
			+ ", "
			+ PauCorRendererBridge.getStats(Minecraft.getInstance().level).describe()
			+ ", "
			+ PauCClientUploadBudgetController.describeState()
			+ ", "
			+ PauCClientRenderPrep.describeState()
			+ ", "
			+ describeWorldCacheState()
			+ ", "
			+ PauCCudaLodProxyRenderer.describeState()
			+ ", "
			+ PauCEmbeddedLodRuntimeDiagnostics.describeState()
			+ "]";
	}

	public static String describeActuationState() {
		return "frontierAct[warmBase="
			+ lastWarmupBaseSectionBudget
			+ ", warmLimited="
			+ lastWarmupLimitedSectionBudget
			+ ", warmGranted="
			+ lastWarmupGrantedSectionBudget
			+ ", completionBudget="
			+ lastWarmupCompletionBudget
			+ ", appliedPlans="
			+ lastWarmupAppliedPlans
			+ ", scheduledSections="
			+ lastWarmupScheduledSections
			+ ", visualRecovery="
			+ lastVisualRecoveryBudget
			+ "/"
			+ lastVisualRecoveryGrantedBudget
			+ "/"
			+ lastVisualRecoveryScheduledSections
			+ "]";
	}

	public static boolean isHotRestoreActive() {
		return hotRestoreActive;
	}

	private static void refreshHotRestoreState() {
		lastHotRestoreRenderReady = countRenderReadyRecords(lastKnownDimension);
		if (sessionResumedAtMillis <= 0L) {
			hotRestoreActive = false;
			return;
		}

		long now = System.currentTimeMillis();
		long durationMillis = readLong(HOT_RESTORE_DURATION_MS_PROPERTY, 18_000L, 1_000L, 120_000L);
		double coverageTarget = readDouble(HOT_RESTORE_COVERAGE_TARGET_PROPERTY, 0.62D, 0.20D, 1.0D);
		int minimumRenderReady = readInt(HOT_RESTORE_MIN_RENDER_READY_PROPERTY, 64, 8, 4096);
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		boolean coverageReached = snapshot.available()
			&& (snapshot.ratio() >= coverageTarget || snapshot.activeBandRatio() >= coverageTarget);
		boolean renderReadyReached = lastHotRestoreRenderReady >= minimumRenderReady;
		boolean complete = renderReadyReached && (!snapshot.available() || coverageReached);
		boolean active = now - sessionResumedAtMillis < durationMillis && !complete;
		if (active) {
			lastHotRestoreCompletionAtMillis = 0L;
		} else if (hotRestoreActive && lastHotRestoreCompletionAtMillis <= 0L) {
			lastHotRestoreCompletionAtMillis = now;
		}
		hotRestoreActive = active;
	}

	private static String describeHotRestoreState() {
		long remainingMillis = 0L;
		if (sessionResumedAtMillis > 0L) {
			long durationMillis = readLong(HOT_RESTORE_DURATION_MS_PROPERTY, 18_000L, 1_000L, 120_000L);
			remainingMillis = Math.max(0L, durationMillis - (System.currentTimeMillis() - sessionResumedAtMillis));
		}
		return "hotRestore[active="
			+ hotRestoreActive
			+ ", queued="
			+ lastHotRestoreQueued
			+ ", applied="
			+ lastHotRestoreApplied
			+ ", renderReady="
			+ lastHotRestoreRenderReady
			+ ", remainingMs="
			+ remainingMillis
			+ ", completedAt="
			+ lastHotRestoreCompletionAtMillis
			+ "]";
	}

	private static int countRenderReadyRecords(@Nullable String dimensionId) {
		int count = 0;
		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (record.lodCacheState < LOD_CACHE_STATE_RENDER_READY) {
				continue;
			}
			if (dimensionId != null && !dimensionId.equals(record.dimensionId)) {
				continue;
			}
			count++;
		}
		return count;
	}

	private static void onHotRestoreQueued() {
		lastHotRestoreQueued++;
		PauCLodReloadDiagnostics.onRestoreQueued();
	}

	private static void onHotRestoreApplied() {
		lastHotRestoreApplied++;
		PauCLodReloadDiagnostics.onRestoreApplied();
	}

	public static boolean isDirectHorizonFillActive() {
		return isDirectHorizonFillActive(PauCLodClientSettings.configuredTargetDistanceChunks());
	}

	public static boolean isDirectHorizonFillActive(int fallbackRadiusChunks) {
		int targetRadius = sanitizeFillRadiusChunks(fallbackRadiusChunks);
		if (targetRadius <= 0 || !readBoolean(DIRECT_HORIZON_FILL_PROPERTY, true)) {
			return false;
		}
		int maxTarget = readInt(
			DIRECT_HORIZON_FILL_MAX_TARGET_DISTANCE_PROPERTY,
			PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS,
			PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS,
			PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS
		);
		return PauCLodClientSettings.configuredTargetDistanceChunks() <= maxTarget;
	}

	public static boolean shouldPreferCoarseFill() {
		if (isDirectHorizonFillActive()) {
			return false;
		}
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (snapshot.available()) {
			return snapshot.preferCoarseFill();
		}
		PauCLodRange range = PauCClientLodGovernor.currentRange();
		return range != null && range.enabled();
	}

	public static int activeFillRadiusChunks(int fallbackRadiusChunks) {
		int targetRadius = sanitizeFillRadiusChunks(fallbackRadiusChunks);
		if (isDirectHorizonFillActive(targetRadius)) {
			return directFillFocusRadiusChunks(targetRadius);
		}
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (snapshot.available() && snapshot.activeBandEnd() > 0) {
			return Math.max(0, Math.min(fallbackRadiusChunks, snapshot.activeBandEnd()));
		}
		PauCLodRange range = PauCClientLodGovernor.currentRange();
		if (range == null || !range.enabled()) {
			return Math.max(0, fallbackRadiusChunks);
		}
		return Math.max(0, Math.min(fallbackRadiusChunks, firstFillBandEnd(range.lodEndChunk())));
	}

	public static int requestedFillRadiusChunks(int fallbackRadiusChunks) {
		int targetRadius = sanitizeFillRadiusChunks(fallbackRadiusChunks);
		if (targetRadius <= 0) {
			return 0;
		}
		if (isDirectHorizonFillActive(targetRadius)) {
			return advanceFillBandEnd(
				directFillFocusRadiusChunks(targetRadius),
				targetRadius,
				directFillRequestedBandLead()
			);
		}

		CoverageSnapshot snapshot = lastCoverageSnapshot;
		boolean coverageHold = shouldHoldPresentationForCoverage();
		if (snapshot.available()) {
			if (snapshot.nearDebt()) {
				int nearRadius = Math.max(0, Math.min(targetRadius, snapshot.nearBandEnd()));
				return advanceFillBandEnd(nearRadius, targetRadius, requestedFillBandLead(snapshot));
			}
			if (!snapshot.preferCoarseFill() && !coverageHold) {
				return targetRadius;
			}
			int activeRadius = Math.max(0, Math.min(targetRadius, snapshot.activeBandEnd()));
			if (activeRadius > 0) {
				return advanceFillBandEnd(activeRadius, targetRadius, requestedFillBandLead(snapshot));
			}
		}

		PauCLodRange range = PauCClientLodGovernor.currentRange();
		if (range == null || !range.enabled()) {
			return targetRadius;
		}
		int seedRadius = firstFillBandEnd(Math.min(targetRadius, range.lodEndChunk()));
		int seedLead = targetRadius >= 128 || shaderFallbackFillActive() || coverageHold ? 2 : 1;
		if (isActiveTravelFill()) {
			seedLead++;
		}
		return advanceFillBandEnd(seedRadius, targetRadius, seedLead);
	}

	public static int backgroundFillRadiusChunks(int fallbackRadiusChunks) {
		int targetRadius = sanitizeFillRadiusChunks(fallbackRadiusChunks);
		if (targetRadius <= 0) {
			return 0;
		}
		if (isDirectHorizonFillActive(targetRadius)) {
			return advanceFillBandEnd(
				requestedFillRadiusChunks(targetRadius),
				targetRadius,
				directFillBackgroundBandLead()
			);
		}

		int requestRadius = requestedFillRadiusChunks(targetRadius);
		if (requestRadius >= targetRadius) {
			return targetRadius;
		}

		boolean coverageHold = shouldHoldPresentationForCoverage();
		int extraBands = isActiveTravelFill() || shaderFallbackFillActive() ? 2 : shouldPreferCoarseFill() ? 1 : 0;
		if (lastCoverageSnapshot.available() && lastCoverageSnapshot.nearDebt()) {
			extraBands = Math.max(extraBands, 2);
		}
		if (coverageHold && PauCLodShaderContext.isShaderPackInUse()) {
			extraBands = Math.max(extraBands, isActiveTravelFill() ? 2 : 1);
		}
		if (PauCClientChunkPriorityScorer.isFpsFirstVanillaMode() && !shaderFallbackFillActive() && !isActiveTravelFill()) {
			extraBands = Math.max(0, extraBands - 1);
		}
		return advanceFillBandEnd(requestRadius, targetRadius, extraBands);
	}

	private static int directFillFocusRadiusChunks(int targetRadius) {
		int sanitizedTarget = sanitizeFillRadiusChunks(targetRadius);
		if (sanitizedTarget <= 0) {
			return 0;
		}

		PauCLodRange range = PauCClientLodGovernor.currentRange();
		int lodStart = range != null && range.enabled()
			? range.lodStartChunk()
			: Math.max(PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, Math.min(sanitizedTarget, 8));
		int configuredExtra = range != null && range.enabled()
			? range.configuredExtraDistanceChunks()
			: PauCLodClientSettings.configuredTargetDistanceChunks();
		int leadChunks = 4 + Math.min(12, Math.max(2, configuredExtra / 6));
		if (isActiveTravelFill()) {
			leadChunks += 2;
		}

		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (snapshot.available()) {
			if (snapshot.nearDebt()) {
				leadChunks = Math.min(leadChunks, isActiveTravelFill() ? 8 : 6);
			} else if (snapshot.activeBandRatio() >= 0.55D && snapshot.ratio() >= 0.28D) {
				leadChunks += 1;
			}
		}

		if (PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()) {
			double backlogPressure = PauCEmbeddedLodRuntimeDiagnostics.backlogPressure();
			if (backlogPressure >= 0.55D) {
				leadChunks = Math.min(leadChunks, isActiveTravelFill() ? 8 : 6);
			} else if (backlogPressure >= 0.35D) {
				leadChunks = Math.min(leadChunks, isActiveTravelFill() ? 10 : 8);
			}
		}

		int focusRadius = lodStart + Math.max(4, Math.min(18, leadChunks));
		return Math.max(lodStart, Math.min(sanitizedTarget, focusRadius));
	}

	private static int directFillRequestedBandLead() {
		int lead = isActiveTravelFill() ? 2 : 1;
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		double backlogPressure = PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()
			? PauCEmbeddedLodRuntimeDiagnostics.backlogPressure()
			: 0.0D;
		if (backlogPressure >= 0.55D) {
			return 1;
		}
		if (snapshot.available()
			&& !snapshot.nearDebt()
			&& snapshot.activeBandRatio() >= 0.60D
			&& snapshot.ratio() >= 0.30D
			&& backlogPressure < 0.35D) {
			lead++;
		}
		return Math.max(1, Math.min(FILL_BANDS.length, lead));
	}

	private static int directFillBackgroundBandLead() {
		int lead = 0;
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		double backlogPressure = PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()
			? PauCEmbeddedLodRuntimeDiagnostics.backlogPressure()
			: 0.0D;
		if (backlogPressure < 0.35D) {
			lead = isActiveTravelFill() ? 2 : 1;
		}
		if (snapshot.available()
			&& !snapshot.nearDebt()
			&& snapshot.activeBandRatio() >= 0.72D
			&& snapshot.ratio() >= 0.44D
			&& backlogPressure < 0.22D) {
			lead++;
		}
		return Math.max(0, Math.min(FILL_BANDS.length, lead));
	}

	public static boolean isActiveTravelFill() {
		return lastSnapMode || lastFastTravel || lastMovementCatchup || PauCClientChunkPriorityScorer.isMovementCatchupActive();
	}

	private static boolean shaderFallbackFillActive() {
		return PauCLodShaderContext.isShaderPackInUse() && PauCLodShaderContext.isFallbackActive();
	}

	public static void onConfiguredTargetDistanceChanged(int configuredTargetDistance) {
		lastCoverageSnapshot = CoverageSnapshot.unavailable();
		lastCoveragePresentationHoldUntilMillis = 0L;
		lastCoverageHoldDemandAtMillis = 0L;
		lastConfiguredTargetChangeAtMillis = System.currentTimeMillis();
		PauCLodNearClipOverride.setTerrainContinuityHold(false, "target-distance-changed:" + configuredTargetDistance);
	}

	public static boolean shouldStabilizeLodPresentation() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (!snapshot.available()) {
			PauCLodRange range = PauCClientLodGovernor.currentRange();
			return (range != null && range.enabled()) && PauCEmbeddedLodRuntimeDiagnostics.shouldKeepFillPresentation();
		}
		return shouldHoldPresentationForCoverage();
	}

	public static boolean hasCoverageTelemetry() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		return snapshot != null && snapshot.available();
	}

	public static boolean hasNearCoverageDebt() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		return snapshot != null && snapshot.available() && snapshot.nearDebt();
	}

	public static double nearCoverageRatio() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		return snapshot != null && snapshot.available() ? snapshot.nearRatio() : 1.0D;
	}

	public static boolean isPresentationHoldActive() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (snapshot == null || !snapshot.available()) {
			return false;
		}
		if (snapshot.nearDebt()) {
			return true;
		}
		return coverageNeedsPresentationHold(snapshot)
			|| System.currentTimeMillis() < lastCoveragePresentationHoldUntilMillis;
	}

	public static boolean hasStablePresentationCoverage() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		return snapshot.available()
			&& !snapshot.preferCoarseFill()
			&& !isPresentationHoldActive()
			&& hasRecoveredPresentationCoverage(snapshot);
	}

	public static boolean isTargetDistanceUpgradeReady(int currentTargetDistance, int configuredTargetDistance) {
		long now = System.currentTimeMillis();
		if (isTargetDistanceChangeGraceActive(now)) {
			return true;
		}
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (!snapshot.available()) {
			return true;
		}
		if (snapshot.preferCoarseFill() || isPresentationHoldActive()) {
			return false;
		}
		if (hasRecoveredPresentationCoverage(snapshot)) {
			return true;
		}
		if (isPauCQueueFullyDrained() && hasStaleQueueDrainedReleaseCoverage(snapshot, now)) {
			return true;
		}

		int relaxedCeiling = readInt(
			"pauc.lod.targetDistanceCoverageReleaseCeiling",
			12,
			PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS,
			PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS
		);
		return currentTargetDistance <= relaxedCeiling
			&& configuredTargetDistance <= Math.max(16, relaxedCeiling * 2)
			&& isPauCQueueDrained()
			&& hasQueueDrainedReleaseCoverage(snapshot);
	}

	public static boolean shouldHoldPresentationForCoverage() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (!snapshot.available()) {
			return false;
		}

		long now = System.currentTimeMillis();
		if (isTargetDistanceChangeGraceActive(now)) {
			lastCoveragePresentationHoldUntilMillis = now;
			return false;
		}
		boolean queueDrained = isPauCQueueDrained();
		boolean queueFullyDrained = isPauCQueueFullyDrained();
		if (PauCEmbeddedLodRuntimeDiagnostics.canReleaseFillPresentationDuringCoverageDebt()) {
			lastCoveragePresentationHoldUntilMillis = now;
			lastCoverageHoldDemandAtMillis = 0L;
			return false;
		}
		if (queueDrained && hasRecoveredPresentationCoverage(snapshot)) {
			lastCoveragePresentationHoldUntilMillis = now;
			lastCoverageHoldDemandAtMillis = 0L;
			return false;
		}
		if (queueFullyDrained && hasQueueDrainedReleaseCoverage(snapshot)) {
			lastCoveragePresentationHoldUntilMillis = now;
			lastCoverageHoldDemandAtMillis = 0L;
			return false;
		}
		if (queueFullyDrained && hasStaleQueueDrainedReleaseCoverage(snapshot, now)) {
			lastCoveragePresentationHoldUntilMillis = now;
			lastCoverageHoldDemandAtMillis = 0L;
			return false;
		}
		if (coverageNeedsPresentationHold(snapshot)) {
			if (lastCoverageHoldDemandAtMillis <= 0L) {
				lastCoverageHoldDemandAtMillis = now;
			}
			long defaultHoldMs = PauCLodShaderContext.isShaderPackInUse()
				? (isActiveTravelFill() ? 320L : 180L)
				: (isActiveTravelFill() ? 160L : 100L);
			if (queueFullyDrained) {
				defaultHoldMs = Math.max(10L, defaultHoldMs / 4L);
			} else if (queueDrained) {
				defaultHoldMs = Math.max(20L, defaultHoldMs / 3L);
			}
			long holdMs = readLong(PRESENTATION_STABILIZE_HOLD_MS_PROPERTY, defaultHoldMs, 0L, 5_000L);
			lastCoveragePresentationHoldUntilMillis = Math.max(lastCoveragePresentationHoldUntilMillis, now + holdMs);
			return true;
		}

		if ((queueDrained && hasRecoveredPresentationCoverage(snapshot))
			|| (queueFullyDrained && hasQueueDrainedReleaseCoverage(snapshot))
			|| (queueFullyDrained && hasStaleQueueDrainedReleaseCoverage(snapshot, now))) {
			lastCoveragePresentationHoldUntilMillis = now;
			lastCoverageHoldDemandAtMillis = 0L;
			return false;
		}
		return now < lastCoveragePresentationHoldUntilMillis;
	}

	private static void requestCoarseFillRenderRefreshIfNeeded() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (!snapshot.preferCoarseFill() && !coverageNeedsPresentationHold(snapshot)) {
			return;
		}
		fr.hoyatla.pauc.lod.PauCLodBridgeAccess.refreshRenderCacheForCoarseFill(snapshot.ratio(), snapshot.expected(), snapshot.covered());
	}

	private static void requestCudaReadyRenderRefreshIfNeeded(int readyCells) {
		if (readyCells <= 0) {
			return;
		}
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (!snapshot.available()) {
			return;
		}
		if (!snapshot.preferCoarseFill() && !shaderFallbackFillActive() && !coverageNeedsPresentationHold(snapshot)) {
			return;
		}
		fr.hoyatla.pauc.lod.PauCLodBridgeAccess.refreshRenderCacheForCoarseFill(snapshot.ratio(), snapshot.expected(), snapshot.covered());
	}

	private static boolean coverageNeedsPresentationHold(CoverageSnapshot snapshot) {
		if (!snapshot.available()) {
			return false;
		}
		if (snapshot.nearDebt()) {
			return true;
		}
		if (hasRecoveredPresentationCoverage(snapshot)) {
			return false;
		}
		if (isPauCQueueFullyDrained() && hasQueueDrainedReleaseCoverage(snapshot)) {
			return false;
		}
		if (isPauCQueueFullyDrained() && hasStaleQueueDrainedReleaseCoverage(snapshot, System.currentTimeMillis())) {
			return false;
		}
		if (PauCEmbeddedLodRuntimeDiagnostics.canReleaseFillPresentationDuringCoverageDebt()) {
			return false;
		}

		boolean queueDrained = isPauCQueueDrained();
		double stabilizeRatio = readDouble(
			PRESENTATION_STABILIZE_RATIO_PROPERTY,
			queueDrained ? 0.58D : 0.68D,
			0.20D,
			0.98D
		);
		if (snapshot.preferCoarseFill() || snapshot.ratio() < stabilizeRatio) {
			return true;
		}

		boolean shaderRuntime = PauCLodShaderContext.isShaderPackInUse() && !PauCLodShaderContext.isFallbackActive();
		if (!shaderRuntime || snapshot.activeBandExpected() <= 0) {
			return false;
		}

		double defaultShaderRatio = queueDrained
			? (isActiveTravelFill() ? 0.70D : 0.64D)
			: (isActiveTravelFill() ? 0.80D : 0.74D);
		double shaderRatio = readDouble(SHADER_PRESENTATION_STABILIZE_RATIO_PROPERTY, defaultShaderRatio, 0.20D, 0.99D);
		return snapshot.activeBandRatio() < shaderRatio;
	}

	private static void updateTerrainContinuityHold() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		if (!snapshot.available()) {
			PauCLodNearClipOverride.setTerrainContinuityHold(false, "coverage-unavailable");
			return;
		}

		boolean queueDrained = isPauCQueueDrained();
		boolean recoveredCoverage = hasRecoveredPresentationCoverage(snapshot);
		boolean continuityRisk = (!recoveredCoverage && shouldHoldPresentationForCoverage())
			|| snapshot.nearDebt()
			|| snapshot.ratio() < (queueDrained ? 0.10D : 0.14D)
			|| snapshot.activeBandRatio() < (queueDrained ? 0.42D : 0.48D);
		PauCLodNearClipOverride.setTerrainContinuityHold(continuityRisk, continuityRisk ? "coverage-recovery" : "");
	}

	public static boolean hasRecoveredPresentationCoverage() {
		return hasRecoveredPresentationCoverage(lastCoverageSnapshot);
	}

	private static void refreshPriorities(
		PauCClientChunkPriorityScorer.PriorityFrame frame,
		Map<Long, PauCClientChunkRetentionManager.RetainedChunkState> retainedChunks
	) {
		String dimensionId = frame.dimensionId();
		long now = System.currentTimeMillis();
		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (!record.dimensionId.equals(dimensionId)) {
				record.lastPriorityScore = Double.NEGATIVE_INFINITY;
				continue;
			}

			boolean retained = retainedChunks.containsKey(record.chunkPos.toLong()) || record.retained;
			PauCClientChunkPriorityScorer.ChunkPriority priority = PauCClientChunkPriorityScorer.score(frame, record.chunkPos.x, record.chunkPos.z, retained);
			record.lastPriorityScore = priority.score();
			record.currentDistance = priority.chebyshevDistance();
			record.currentRadialDistance = priority.radialDistance();
			record.ahead = priority.ahead();
			record.retained = retained;
			if (record.live || retained) {
				record.lastSeenAtMillis = now;
			}
		}
	}

	private static void seedTrackedChunksFromLiveStorage(ClientLevel level) {
		ClientChunkCache chunkCache = level.getChunkSource();
		AtomicReferenceArray<LevelChunk> chunks = getLiveChunkArray(chunkCache);
		if (chunks == null) {
			logStorageReflectionFailureOnce("PauC could not inspect the live client chunk storage; frontier warmup will rely only on packet-visible chunks.");
			return;
		}

		String dimensionId = level.dimension().location().toString();
		long now = System.currentTimeMillis();

		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (record.dimensionId.equals(dimensionId) && !record.retained) {
				record.live = false;
			}
		}

		int length = chunks.length();
		for (int index = 0; index < length; index++) {
			try {
				LevelChunk chunk = chunks.get(index);
				if (chunk == null) {
					continue;
				}

				WarmChunkMetadata metadata = WarmChunkMetadata.capture(level, chunk);
				TRACKED_CHUNKS.compute(metadata.chunkPos().toLong(), (chunkKey, existing) -> {
					WarmChunkRecord record = existing != null ? existing : WarmChunkRecord.placeholder(metadata.dimensionId(), metadata.chunkPos());
					record.applyMetadata(metadata);
					record.live = true;
					record.lastSeenAtMillis = now;
					return record;
				});
			} catch (RuntimeException exception) {
				LOGGER.debug("PauC skipped one live client chunk storage slot during frontier warmup seeding.", exception);
			}
		}
	}

	@Nullable
	private static AtomicReferenceArray<LevelChunk> getLiveChunkArray(ClientChunkCache chunkCache) {
		resolveStorageReflection(chunkCache);
		if (clientChunkStorageField == null || clientChunkStorageChunksField == null) {
			return null;
		}

		try {
			Object storage = clientChunkStorageField.get(chunkCache);
			if (storage == null) {
				return null;
			}

			Object chunks = clientChunkStorageChunksField.get(storage);
			if (chunks instanceof AtomicReferenceArray<?> atomicReferenceArray) {
				@SuppressWarnings("unchecked")
				AtomicReferenceArray<LevelChunk> typedChunks = (AtomicReferenceArray<LevelChunk>) atomicReferenceArray;
				return typedChunks;
			}

			logStorageReflectionFailureOnce(
				"PauC resolved client chunk storage but the chunks field is not an AtomicReferenceArray: "
					+ (chunks != null ? chunks.getClass().getName() : "null")
			);
			return null;
		} catch (IllegalAccessException | RuntimeException exception) {
			LOGGER.debug("PauC could not inspect the live client chunk storage.", exception);
			return null;
		}
	}

	private static void resolveStorageReflection(ClientChunkCache chunkCache) {
		if (storageReflectionResolved) {
			return;
		}

		synchronized (PauCClientFrontierWarmupManager.class) {
			if (storageReflectionResolved) {
				return;
			}

			clientChunkStorageField = findNamedField(chunkCache.getClass(), "storage", "f_104410_");
			if (clientChunkStorageField != null) {
				try {
					Object storage = clientChunkStorageField.get(chunkCache);
					if (storage != null) {
						clientChunkStorageChunksField = findAtomicReferenceArrayField(storage.getClass(), "chunks", "f_104466_");
					}
				} catch (IllegalAccessException exception) {
					LOGGER.debug("PauC could not resolve the client chunk storage fields.", exception);
				}
			}

			if (clientChunkStorageField == null || clientChunkStorageChunksField == null) {
				logStorageReflectionFailureOnce(
					"PauC did not find compatible ClientChunkCache storage fields on "
						+ chunkCache.getClass().getName()
						+ "; live chunk seeding is disabled for this mapping/runtime."
				);
			}

			storageReflectionResolved = true;
		}
	}

	@Nullable
	private static Field findNamedField(Class<?> owner, String... candidateNames) {
		for (String candidateName : candidateNames) {
			try {
				Field field = owner.getDeclaredField(candidateName);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// Try the next candidate.
			}
		}

		return null;
	}

	@Nullable
	private static Field findAtomicReferenceArrayField(Class<?> owner, String... candidateNames) {
		Field namedField = findNamedField(owner, candidateNames);
		if (namedField != null && AtomicReferenceArray.class.isAssignableFrom(namedField.getType())) {
			return namedField;
		}

		for (Field field : owner.getDeclaredFields()) {
			if (AtomicReferenceArray.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				return field;
			}
		}

		return null;
	}

	private static void logStorageReflectionFailureOnce(String message) {
		if (!storageReflectionFailureLogged) {
			storageReflectionFailureLogged = true;
			LOGGER.warn(message);
		}
	}

	private static void refreshHotMeshCounts(ClientLevel level, PauCorRendererBridge.RendererStats stats) {
		lastKnownQueuedMeshSections = stats.scheduledJobs();
		lastKnownHotMeshSections = 0;

		if (!stats.available()) {
			return;
		}

		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (!record.dimensionId.equals(level.dimension().location().toString())) {
				record.hotSectionCount = 0;
				continue;
			}

			int hotSections = 0;
			for (int sectionY : record.toSectionYs(level.getMinSection(), level.getMaxSection() - 1)) {
				if (PauCorRendererBridge.isSectionReady(level, record.chunkPos.x, sectionY, record.chunkPos.z)) {
					hotSections++;
				}
			}

			record.hotSectionCount = hotSections;
			lastKnownHotMeshSections += hotSections;
		}
	}

	private static void runVisualRecovery(
		ClientLevel level,
		PauCClientChunkPriorityScorer.PriorityFrame frame,
		PauCorRendererBridge.RendererStats stats
	) {
		if (!stats.available()) {
			zeroVisibleChunkStreak = 0;
			return;
		}

		if (stats.visibleChunkCount() <= 1) {
			zeroVisibleChunkStreak++;
		} else {
			zeroVisibleChunkStreak = 0;
		}

		if (zeroVisibleChunkStreak < 4) {
			return;
		}

		int emergencyBudget = Math.max(12, Math.min(96, lastBudget.maxQueuedMeshSections() / 4));
		int grantedBudget = PauCClientUploadBudgetController.acquireSectionBudget(emergencyBudget, true);
		lastVisualRecoveryBudget = emergencyBudget;
		lastVisualRecoveryGrantedBudget = grantedBudget;
		if (grantedBudget <= 0) {
			lastVisualRecoveryScheduledSections = 0;
			return;
		}

		int scheduled = PauCorRendererBridge.forceResubmitNeighborhood(
			level,
			frame.playerChunkX(),
			frame.playerChunkZ(),
			frame.minSectionY(),
			frame.maxSectionY(),
			grantedBudget
		);
		if (scheduled > 0) {
			zeroVisibleChunkStreak = 0;
		}
		lastVisualRecoveryScheduledSections = scheduled;
	}

	private static void drainCompletedPlans(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		boolean acceleratedWarmup = frame.snapMode() || frame.fastTravel() || frame.movementCatchup();
		boolean directFill = isDirectHorizonFillActive(frame.warmRadiusChunks());
		boolean hotRestore = isHotRestoreActive();
		double backlogPressure = PauCEmbeddedLodRuntimeDiagnostics.backlogPressure();
		boolean catchupRecovery = acceleratedWarmup && backlogPressure < 0.18D && !PauCClientFpsGovernor.isUnderPressure();
		int queuedMeshBudget = Math.max(0, lastBudget.maxQueuedMeshSections() - lastKnownQueuedMeshSections);
		int hotMeshBudget = Math.max(0, lastBudget.maxHotMeshSections() - lastKnownHotMeshSections);
		int vramMeshBudget = Math.max(0, lastBudget.maxVramMeshSections() - lastKnownHotMeshSections);
		int baseBudgetCeiling = Math.min(queuedMeshBudget, Math.min(hotMeshBudget, vramMeshBudget));
		int baseSectionBudget = baseBudgetCeiling;
		baseSectionBudget = Math.max(
			PLAN_COMPLETION_BUDGET_MIN,
			Math.min(
				baseSectionBudget,
				acceleratedWarmup ? PLAN_COMPLETION_BUDGET_PER_TICK * 4 : PLAN_COMPLETION_BUDGET_PER_TICK * 3
			)
		);
		if (hotRestore) {
			baseSectionBudget = Math.min(baseBudgetCeiling, Math.max(baseSectionBudget, baseSectionBudget + (PLAN_COMPLETION_BUDGET_PER_TICK * 2)));
		}
		if (catchupRecovery) {
			baseSectionBudget = Math.min(baseBudgetCeiling, baseSectionBudget + PLAN_COMPLETION_BUDGET_PER_TICK);
		}
		if (directFill) {
			int directFillFloor = acceleratedWarmup ? PLAN_COMPLETION_BUDGET_PER_TICK * 4 : (PLAN_COMPLETION_BUDGET_PER_TICK * 2) + 4;
			if (isActiveTravelFill()) {
				directFillFloor += PLAN_COMPLETION_BUDGET_PER_TICK;
			}
			baseSectionBudget = Math.min(baseBudgetCeiling, Math.max(baseSectionBudget, directFillFloor));
		}
		baseSectionBudget = scaleWarmupBudget(baseSectionBudget, PauCClientFpsGovernor.warmupAggressionScale(), PLAN_COMPLETION_BUDGET_MIN);
		lastWarmupBaseSectionBudget = baseSectionBudget;
		int limitedSectionBudget = PauCClientRenderPrep.limitWarmupSectionBudget(baseSectionBudget, acceleratedWarmup);
		if (directFill) {
			int directFillLimitedFloor = Math.min(baseSectionBudget, acceleratedWarmup ? 24 : 16);
			limitedSectionBudget = Math.max(limitedSectionBudget, directFillLimitedFloor);
		}
		lastWarmupLimitedSectionBudget = limitedSectionBudget;
		int sectionBudget = PauCClientUploadBudgetController.acquireSectionBudget(limitedSectionBudget, acceleratedWarmup);
		lastWarmupGrantedSectionBudget = sectionBudget;
		if (sectionBudget <= 0) {
			lastWarmupCompletionBudget = 0;
			lastWarmupAppliedPlans = 0;
			lastWarmupScheduledSections = 0;
			return;
		}

		int completionBudget = scaleWarmupBudget(
			acceleratedWarmup ? PLAN_COMPLETION_BUDGET_PER_TICK * 2 : PLAN_COMPLETION_BUDGET_PER_TICK,
			PauCClientFpsGovernor.warmupAggressionScale(),
			PLAN_COMPLETION_BUDGET_MIN
		);
		if (hotRestore) {
			completionBudget = Math.max(completionBudget, completionBudget + PLAN_COMPLETION_BUDGET_PER_TICK);
		}
		if (catchupRecovery) {
			completionBudget += Math.max(2, PLAN_COMPLETION_BUDGET_PER_TICK / 2);
		}
		if (directFill) {
			completionBudget = Math.max(completionBudget, acceleratedWarmup ? 40 : 28);
			if (isActiveTravelFill()) {
				completionBudget = Math.max(completionBudget, 48);
			}
		}
		lastWarmupCompletionBudget = completionBudget;
		int appliedPlans = 0;
		int scheduledSectionsTotal = 0;
		for (int i = 0; i < completionBudget && sectionBudget > 0; i++) {
			PreparedWarmPlan plan = COMPLETED_PLANS.pollFirst();
			if (plan == null) {
				lastWarmupAppliedPlans = appliedPlans;
				lastWarmupScheduledSections = scheduledSectionsTotal;
				return;
			}

			if (plan.sessionGeneration() != SESSION_GENERATION.get()) {
				continue;
			}

			int scheduledSections = PauCorRendererBridge.applyWarmPlan(level, plan, sectionBudget);
			sectionBudget -= scheduledSections;
			appliedPlans++;
			scheduledSectionsTotal += scheduledSections;
			WarmChunkRecord record = TRACKED_CHUNKS.get(plan.chunkPos().toLong());
			if (record != null) {
				record.lastPreparedAtMillis = System.currentTimeMillis();
				record.lastScheduledMeshSections = scheduledSections;
			}
		}
		lastWarmupAppliedPlans = appliedPlans;
		lastWarmupScheduledSections = scheduledSectionsTotal;
	}

	private static void schedulePlans(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		boolean directFill = isDirectHorizonFillActive(frame.warmRadiusChunks());
		boolean hotRestore = isHotRestoreActive();
		double backlogPressure = PauCEmbeddedLodRuntimeDiagnostics.backlogPressure();
		int planSlots = Math.max(0, lastBudget.maxPendingPlans() - PENDING_PLANS.size() - COMPLETED_PLANS.size());
		if (planSlots <= 0) {
			return;
		}
		if (frame.snapMode()) {
			planSlots = Math.max(2, (planSlots * 2) / 3);
		} else if (frame.fastTravel() || frame.movementCatchup()) {
			planSlots = Math.max(2, (planSlots * 3) / 4);
		}
		if (backlogPressure > 0.22D) {
			planSlots = Math.max(1, (planSlots * 2) / 3);
		}
		if (COMPLETED_PLANS.size() > Math.max(8, planSlots * 2)) {
			planSlots = Math.max(1, planSlots / 2);
		}
		planSlots = scaleWarmupBudget(planSlots, PauCClientFpsGovernor.warmupAggressionScale(), 1);
		if (hotRestore) {
			planSlots = Math.max(planSlots, Math.min(lastBudget.maxPendingPlans(), planSlots + 6));
		}
		if (directFill) {
			int directFillFloor = frame.warmRadiusChunks() >= 48 ? 48 : 32;
			if (isActiveTravelFill()) {
				directFillFloor = Math.max(directFillFloor, frame.warmRadiusChunks() >= 48 ? 72 : 48);
			}
			planSlots = Math.max(planSlots, Math.min(lastBudget.maxPendingPlans(), directFillFloor));
		}

		long now = System.currentTimeMillis();
		double minScore = frame.snapMode() ? 0.34D : (frame.movementCatchup() ? 0.36D : (frame.fastTravel() ? 0.40D : 0.45D));
		if (PauCClientFpsGovernor.isUnderPressure() && !frame.movementCatchup()) {
			minScore += 0.06D;
		} else if (frame.movementCatchup() && backlogPressure < 0.12D) {
			minScore = Math.max(0.28D, minScore - 0.06D);
		} else if (PauCClientFpsGovernor.warmupAggressionScale() > 1.0D) {
			minScore = Math.max(0.30D, minScore - 0.05D);
		}
		if (hotRestore) {
			minScore = Math.max(0.20D, minScore - 0.16D);
		}
		if (directFill) {
			minScore = Math.min(minScore, isActiveTravelFill() ? 0.10D : (frame.movementCatchup() ? 0.14D : 0.18D));
		}
		double adjustedMinScore = minScore;
		String dimensionId = level.dimension().location().toString();
		List<WarmChunkRecord> candidates = new ArrayList<>(Math.min(planSlots, 64));
		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			boolean restoreCandidate = hotRestore && (record.diskSeeded || record.lodCacheState >= LOD_CACHE_STATE_CPU_PREPARED);
			if (!record.dimensionId.equals(dimensionId)
				|| record.nonEmptySectionCount <= 0
				|| record.currentDistance > frame.warmRadiusChunks()
				|| (!restoreCandidate && record.lastPriorityScore < adjustedMinScore)
				|| (!record.live && !record.retained && !restoreCandidate)
				|| PENDING_PLANS.contains(record.chunkPos.toLong())
				|| now - record.lastPlannedAtMillis < PLAN_RESCHEDULE_COOLDOWN_MS) {
				continue;
			}
			addBoundedCandidate(candidates, record, planSlots, PLAN_CANDIDATE_ORDER);
		}
		candidates.sort(PLAN_CANDIDATE_ORDER);

		for (WarmChunkRecord candidate : candidates) {
			schedulePlan(level, frame, candidate);
		}
	}

	private static void drainDiskSeedResults(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int fillRadius = backgroundFillRadiusChunks(frame.warmRadiusChunks());
		boolean hotRestore = isHotRestoreActive();
		int batchBudget = readInt(DISK_SEED_DRAIN_BATCHES_PROPERTY, defaultDiskSeedDrainBatches(fillRadius, frame), 1, 32);
		int chunkBudget = readInt(DISK_SEED_DRAIN_CHUNKS_PROPERTY, defaultDiskSeedDrainChunks(fillRadius, frame), 16, 8192);
		if (hotRestore) {
			batchBudget = Math.min(32, Math.max(batchBudget, batchBudget + 4));
			chunkBudget = Math.min(8192, Math.max(chunkBudget, (int) Math.ceil(chunkBudget * 2.0D)));
		}
		int drainedChunks = 0;
		long now = System.currentTimeMillis();
		String dimensionId = level.dimension().location().toString();

		for (int batch = 0; batch < batchBudget && drainedChunks < chunkBudget; batch++) {
			List<WarmChunkMetadata> metadataBatch = COMPLETED_DISK_SEEDS.pollFirst();
			if (metadataBatch == null) {
				return;
			}

			for (int index = 0; index < metadataBatch.size(); index++) {
				WarmChunkMetadata metadata = metadataBatch.get(index);
				if (drainedChunks >= chunkBudget) {
					COMPLETED_DISK_SEEDS.addFirst(metadataBatch.subList(index, metadataBatch.size()));
					return;
				}
				if (!metadata.dimensionId().equals(dimensionId)) {
					continue;
				}
				int distance = Math.max(Math.abs(metadata.chunkPos().x - frame.playerChunkX()), Math.abs(metadata.chunkPos().z - frame.playerChunkZ()));
				if (distance > frame.warmRadiusChunks()) {
					continue;
				}

				TRACKED_CHUNKS.compute(metadata.chunkPos().toLong(), (chunkKey, existing) -> {
					WarmChunkRecord record = existing != null ? existing : WarmChunkRecord.placeholder(metadata.dimensionId(), metadata.chunkPos());
					if (!record.live) {
						int previousState = record.lodCacheState;
						record.applyMetadata(metadata);
						record.diskSeeded = true;
						record.lastSeenAtMillis = now;
						if (hotRestore && previousState < LOD_CACHE_STATE_CPU_PREPARED && record.lodCacheState >= LOD_CACHE_STATE_CPU_PREPARED) {
							onHotRestoreQueued();
						}
						if (hotRestore && previousState < LOD_CACHE_STATE_RENDER_READY && record.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
							onHotRestoreApplied();
						}
					}
					return record;
				});
				drainedChunks++;
			}
		}
	}

	private static void scheduleDiskSeedScan(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		if (!readBoolean(DISK_SEED_ENABLED_PROPERTY, true)) {
			return;
		}

		long now = System.currentTimeMillis();
		boolean hotRestore = isHotRestoreActive();
		long intervalMillis = readLong(DISK_SEED_INTERVAL_MS_PROPERTY, 450L, 50L, 10_000L);
		if (hotRestore) {
			intervalMillis = Math.max(50L, intervalMillis / 3L);
		}
		if (now - lastDiskSeedScheduleAtMillis < intervalMillis) {
			return;
		}
		lastDiskSeedScheduleAtMillis = now;

		int fillRadius = backgroundFillRadiusChunks(frame.warmRadiusChunks());
		int maxPendingRegions = readInt(DISK_SEED_MAX_PENDING_REGIONS_PROPERTY, defaultDiskSeedPendingRegions(fillRadius, frame), 1, 32);
		if (hotRestore) {
			maxPendingRegions = Math.min(32, Math.max(maxPendingRegions, maxPendingRegions + 6));
		}
		if (PENDING_DISK_SEED_REGIONS.size() >= maxPendingRegions) {
			return;
		}

		int radius = Math.max(0, Math.min(frame.warmRadiusChunks(), fillRadius));
		int minRegionX = (frame.playerChunkX() - radius) >> 5;
		int maxRegionX = (frame.playerChunkX() + radius) >> 5;
		int minRegionZ = (frame.playerChunkZ() - radius) >> 5;
		int maxRegionZ = (frame.playerChunkZ() + radius) >> 5;
		int playerRegionX = frame.playerChunkX() >> 5;
		int playerRegionZ = frame.playerChunkZ() >> 5;
		long regionTtlMillis = readLong(DISK_SEED_REGION_TTL_MS_PROPERTY, 12_000L, 500L, 120_000L);
		List<RegionCandidate> candidates = new ArrayList<>();

		for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
			for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
				String regionKey = diskSeedRegionKey(frame.dimensionId(), regionX, regionZ);
				if (PENDING_DISK_SEED_REGIONS.contains(regionKey)) {
					continue;
				}
				Long lastScan = DISK_SEED_REGION_SCAN_TIMES.get(regionKey);
				if (lastScan != null && now - lastScan < regionTtlMillis) {
					continue;
				}
				int distance = Math.max(Math.abs(regionX - playerRegionX), Math.abs(regionZ - playerRegionZ));
				candidates.add(new RegionCandidate(regionKey, regionX, regionZ, distance));
			}
		}

		if (candidates.isEmpty()) {
			return;
		}

		candidates.sort(Comparator.comparingInt(RegionCandidate::distance));
		int maxRegions = Math.min(
			readInt(DISK_SEED_MAX_REGIONS_PER_TICK_PROPERTY, defaultDiskSeedRegionsPerTick(fillRadius, frame), 1, 24),
			Math.max(0, maxPendingRegions - PENDING_DISK_SEED_REGIONS.size())
		);
		int chunkLimit = readInt(DISK_SEED_MAX_CHUNKS_PER_REGION_PROPERTY, defaultDiskSeedChunkLimit(fillRadius, frame), 8, 2048);
		if (hotRestore) {
			maxRegions = Math.min(24, Math.max(maxRegions, maxRegions + 4));
			chunkLimit = Math.min(2048, Math.max(chunkLimit, (int) Math.ceil(chunkLimit * 2.0D)));
		}
		final int regionChunkLimit = chunkLimit;
		Path sessionRoot = PauCClientWarmChunkDiskCache.resolveSessionRoot(level);

		for (int index = 0; index < maxRegions && index < candidates.size(); index++) {
			RegionCandidate candidate = candidates.get(index);
			if (!PENDING_DISK_SEED_REGIONS.add(candidate.key())) {
				continue;
			}
			DISK_SEED_REGION_SCAN_TIMES.put(candidate.key(), now);
			String description = "client warm cache seed " + frame.dimensionId() + " region " + candidate.regionX() + "," + candidate.regionZ();
			CompletableFuture<List<WarmChunkMetadata>> future = PauCScheduler.submitIo(
				PauCTaskPriority.BACKGROUND,
				description,
				() -> PauCClientWarmChunkDiskCache.readRegion(
					sessionRoot,
					frame.dimensionId(),
					candidate.regionX(),
					candidate.regionZ(),
					frame.playerChunkX(),
					frame.playerChunkZ(),
					frame.warmRadiusChunks(),
					regionChunkLimit
				)
			);
			future.whenComplete((metadata, throwable) -> {
				PENDING_DISK_SEED_REGIONS.remove(candidate.key());
				if (throwable != null || metadata == null || metadata.isEmpty()) {
					return;
				}
				COMPLETED_DISK_SEEDS.addLast(metadata);
			});
		}
	}

	private static int defaultDiskSeedPendingRegions(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int base = fillRadius >= 192 ? 16 : fillRadius >= 128 ? 12 : fillRadius >= 96 ? 8 : 5;
		return (frame.fastTravel() || frame.snapMode() || frame.movementCatchup()) ? Math.min(24, base + 4) : base;
	}

	private static int defaultDiskSeedRegionsPerTick(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int base = fillRadius >= 192 ? 8 : fillRadius >= 128 ? 6 : fillRadius >= 64 ? 4 : 2;
		return (frame.fastTravel() || frame.snapMode() || frame.movementCatchup()) ? Math.min(12, base + 3) : base;
	}

	private static int defaultDiskSeedChunkLimit(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int base = fillRadius >= 192 ? 640 : fillRadius >= 128 ? 448 : fillRadius >= 64 ? 224 : 128;
		return (frame.fastTravel() || frame.snapMode() || frame.movementCatchup()) ? Math.min(960, base + 192) : base;
	}

	private static int defaultDiskSeedDrainBatches(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int base = fillRadius >= 192 ? 8 : fillRadius >= 128 ? 6 : fillRadius >= 64 ? 4 : 3;
		return (frame.fastTravel() || frame.snapMode() || frame.movementCatchup()) ? Math.min(12, base + 2) : base;
	}

	private static int defaultDiskSeedDrainChunks(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int base = fillRadius >= 192 ? 2048 : fillRadius >= 128 ? 1536 : fillRadius >= 64 ? 768 : 384;
		return (frame.fastTravel() || frame.snapMode() || frame.movementCatchup()) ? Math.min(4096, base + 768) : base;
	}

	private static void drainCompletedCudaPreparations(ClientLevel level) {
		boolean hotRestore = isHotRestoreActive();
		int drainBudget = readInt(CUDA_WORLD_CACHE_DRAIN_CELLS_PROPERTY, defaultCudaDrainCells(), 16, 4096);
		int persistBudget = readInt(CUDA_WORLD_CACHE_PERSIST_BATCH_CELLS_PROPERTY, defaultCudaPersistCells(drainBudget), 16, 4096);
		if (hotRestore) {
			drainBudget = Math.min(4096, Math.max(drainBudget, (int) Math.ceil(drainBudget * 1.5D)));
			persistBudget = Math.min(4096, Math.max(persistBudget, (int) Math.ceil(persistBudget * 1.5D)));
		}
		int consumed = 0;
		int applied = 0;
		int renderReady = 0;
		int stale = 0;
		long now = System.currentTimeMillis();
		List<WarmChunkMetadata> metadataBatch = new ArrayList<>(Math.min(drainBudget, persistBudget));

		while (consumed < drainBudget) {
			CudaPreparedBatch batch = COMPLETED_CUDA_PREPARATIONS.pollFirst();
			if (batch == null) {
				break;
			}
			List<CudaPreparedChunk> chunks = batch.chunks();
			if (batch.sessionGeneration() != SESSION_GENERATION.get()) {
				stale += chunks.size();
				consumed += chunks.size();
				continue;
			}

			int index = 0;
			for (; index < chunks.size() && consumed < drainBudget; index++) {
				CudaPreparedChunk prepared = chunks.get(index);
				consumed++;
				WarmChunkRecord record = TRACKED_CHUNKS.get(prepared.chunkPos().toLong());
				if (record == null || !record.dimensionId.equals(prepared.dimensionId()) || record.sourceFingerprint != prepared.sourceFingerprint()) {
					stale++;
					continue;
				}
				int previousState = record.lodCacheState;
				record.applyCudaPreparation(prepared);
				if (record.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
					renderReady++;
				}
				if (hotRestore && previousState < LOD_CACHE_STATE_RENDER_READY && record.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
					onHotRestoreApplied();
				}
				if (metadataBatch.size() < persistBudget) {
					metadataBatch.add(record.toMetadata(now));
				}
				applied++;
			}

			if (index < chunks.size()) {
				COMPLETED_CUDA_PREPARATIONS.addFirst(new CudaPreparedBatch(
					batch.sessionGeneration(),
					batch.shaderRuntime(),
					batch.shaderFallback(),
					batch.cudaAvailable(),
					batch.cudaProfile(),
					batch.preparedAtMillis(),
					new ArrayList<>(chunks.subList(index, chunks.size()))
				));
				break;
			}
		}

		if (!metadataBatch.isEmpty()) {
			PauCClientWarmChunkDiskCache.persistAll(level, metadataBatch);
		}
		if (applied > 0) {
			lastCudaCompletedCells += applied;
			requestCudaReadyRenderRefreshIfNeeded(renderReady);
			logCudaPreparationStatus("drained:" + applied + "/ready=" + renderReady + "/stale=" + stale);
		}
	}

	private static void scheduleCudaPreparations(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		if (!readBoolean(CUDA_WORLD_CACHE_ENABLED_PROPERTY, true)) {
			lastCudaPreparationStatus = "disabled";
			return;
		}
		boolean hotRestore = isHotRestoreActive();
		int priorityRadius = activeFillRadiusChunks(frame.warmRadiusChunks());
		int requestRadius = requestedFillRadiusChunks(frame.warmRadiusChunks());
		int fillRadius = backgroundFillRadiusChunks(frame.warmRadiusChunks());
		int backgroundExtra = readInt(CUDA_WORLD_CACHE_BACKGROUND_EXTRA_PROPERTY, defaultCudaBackgroundExtra(requestRadius, frame), 0, 128);
		int cudaRadius = Math.min(frame.warmRadiusChunks(), fillRadius + backgroundExtra);
		boolean shaderFallback = shaderFallbackFillActive();
		boolean aggressiveVanillaPrefill = !shaderFallback && frame.fpsFirstVanilla() && isDirectHorizonFillActive(requestRadius);
		int maxPendingCap = shaderFallback ? 24 : aggressiveVanillaPrefill ? 20 : 14;
		int maxPending = readInt(CUDA_WORLD_CACHE_MAX_PENDING_PROPERTY, defaultCudaMaxPending(requestRadius, frame), 1, maxPendingCap);
		if (hotRestore) {
			maxPending = Math.min(maxPendingCap, maxPending + (aggressiveVanillaPrefill ? 4 : 2));
		}
		if (countPendingCudaBatches() >= maxPending) {
			lastCudaPreparationStatus = "pending-full:" + PENDING_CUDA_PREP.size() + "/" + maxPending;
			return;
		}

		long now = System.currentTimeMillis();
		long intervalMillis = readLong(CUDA_WORLD_CACHE_INTERVAL_MS_PROPERTY, defaultCudaIntervalMillis(requestRadius, frame), 20L, 10_000L);
		if (hotRestore) {
			intervalMillis = Math.max(20L, intervalMillis / 2L);
		}
		if (now - lastCudaPreparationScheduleAtMillis < intervalMillis) {
			return;
		}

		int samplesPerFeature = 3;
		int minimumFeatures = Math.max(1, (PauCCudaWorker.preferredTerrainBatchSize() + samplesPerFeature - 1) / samplesPerFeature);
		int batchFeatures = readInt(CUDA_WORLD_CACHE_BATCH_FEATURES_PROPERTY, defaultCudaBatchFeatures(minimumFeatures, requestRadius, frame), 8, 2048);
		batchFeatures = PauCCudaWorker.coalescedWorldCacheBatchFeatures(batchFeatures, requestRadius, hotRestore);
		int minimumCandidateCount = minimumCudaCandidateCount(minimumFeatures, batchFeatures, requestRadius, frame);
		if (hotRestore) {
			minimumCandidateCount = Math.max(1, minimumCandidateCount / 2);
		}
		long retryMillis = readLong(CUDA_WORLD_CACHE_RETRY_MS_PROPERTY, 5_000L, 500L, 120_000L);
		double minScore = readDouble(CUDA_WORLD_CACHE_MIN_SCORE_PROPERTY, shouldPreferCoarseFill() || shaderFallback ? 0.0D : 0.04D, 0.0D, 1.0D);
		if (hotRestore) {
			minScore = Math.max(0.0D, minScore - 0.04D);
		}
		Comparator<WarmChunkRecord> cudaCandidateOrder = (left, right) -> compareCudaCandidate(left, right, priorityRadius);
		String dimensionId = level.dimension().location().toString();
		List<WarmChunkRecord> candidates = new ArrayList<>(Math.min(batchFeatures, 128));
		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (!record.dimensionId.equals(dimensionId)
				|| record.nonEmptySectionCount <= 0
				|| record.currentDistance > cudaRadius
				|| !(record.lastPriorityScore >= minScore
					|| record.currentDistance <= frame.renderDistanceChunks() + 2
					|| record.currentDistance <= priorityRadius
					|| record.currentDistance <= requestRadius
					|| (shaderFallback && record.currentDistance <= fillRadius)
					|| record.ahead)
				|| PENDING_CUDA_PREP.contains(record.chunkPos.toLong())
				|| !record.needsCudaPreparation(now, retryMillis)) {
				continue;
			}
			addBoundedCandidate(candidates, record, batchFeatures, cudaCandidateOrder);
		}
		candidates.sort(cudaCandidateOrder);

		if (candidates.size() < minimumCandidateCount) {
			lastCudaPreparationStatus = "waiting:candidates=" + candidates.size() + "/" + minimumCandidateCount + ", radius=" + cudaRadius + ", request=" + requestRadius;
			logCudaPreparationStatus(lastCudaPreparationStatus);
			return;
		}

		List<CudaPrepRecord> workRecords = new ArrayList<>(candidates.size());
		for (WarmChunkRecord candidate : candidates) {
			long chunkKey = candidate.chunkPos.toLong();
			if (!PENDING_CUDA_PREP.add(chunkKey)) {
				continue;
			}
			candidate.lastCudaAttemptAtMillis = now;
			workRecords.add(candidate.toCudaPrepRecord(requestRadius, fillRadius));
		}
		if (workRecords.isEmpty()) {
			lastCudaPreparationStatus = "waiting:dedupe-empty";
			return;
		}

		lastCudaPreparationScheduleAtMillis = now;
		lastCudaScheduledCells += workRecords.size();
		lastCudaPreparationStatus = "scheduled:cells=" + workRecords.size() + ", radius=" + cudaRadius + ", band=" + priorityRadius + ", request=" + requestRadius;
		logCudaPreparationStatus(lastCudaPreparationStatus);
		CudaPrepWorkItem workItem = new CudaPrepWorkItem(
			SESSION_GENERATION.get(),
			frame.minSectionY(),
			samplesPerFeature,
			PauCLodShaderContext.isShaderPackInUse(),
			shaderFallback,
			workRecords
		);
		String description = "client world LOD CUDA cache " + workRecords.size() + " cells";
		PauCTaskPriority taskPriority = shaderFallback || shouldPreferCoarseFill() || isActiveTravelFill()
			? PauCTaskPriority.ACTIVE
			: PauCTaskPriority.BACKGROUND;
		if (hotRestore) {
			taskPriority = PauCTaskPriority.ACTIVE;
		}
		CompletableFuture<CudaPreparedBatch> future = PauCScheduler.submitChunkMesh(taskPriority, description, () -> prepareCudaBatch(workItem));
		future.whenComplete((preparedBatch, throwable) -> {
			for (CudaPrepRecord record : workRecords) {
				PENDING_CUDA_PREP.remove(record.chunkPos().toLong());
			}
			if (throwable != null) {
				lastCudaPreparationStatus = "error:" + throwable.getClass().getSimpleName();
				logCudaPreparationStatus(lastCudaPreparationStatus);
				LOGGER.debug("PauC world LOD CUDA cache preparation failed.", throwable);
				return;
			}
			if (preparedBatch == null || preparedBatch.chunks().isEmpty() || workItem.sessionGeneration() != SESSION_GENERATION.get()) {
				return;
			}
			COMPLETED_CUDA_PREPARATIONS.addLast(preparedBatch);
			lastCudaPreparationStatus = "ready:" + preparedBatch.chunks().size() + " cells";
			logCudaPreparationStatus(lastCudaPreparationStatus);
		});
	}

	private static CudaPreparedBatch prepareCudaBatch(CudaPrepWorkItem workItem) {
		List<CudaPrepRecord> records = workItem.records();
		if (records.isEmpty()) {
			return new CudaPreparedBatch(
				workItem.sessionGeneration(),
				workItem.shaderRuntime(),
				workItem.shaderFallback(),
				false,
				"empty",
				System.currentTimeMillis(),
				List.of()
			);
		}

		int samplesPerFeature = workItem.samplesPerFeature();
		int[] sums = new int[records.size() * samplesPerFeature];
		int[] counts = new int[sums.length];
		float[] cpuFallback = new float[records.size()];
		long cpuStarted = System.nanoTime();
		for (int index = 0; index < records.size(); index++) {
			CudaPrepRecord record = records.get(index);
			int minSectionY = sectionIndexToY(workItem.minSectionY(), record.terrainMinSectionIndex(), record.surfaceSectionIndex());
			int surfaceSectionY = sectionIndexToY(workItem.minSectionY(), record.surfaceSectionIndex(), record.terrainMaxSectionIndex());
			int maxSectionY = sectionIndexToY(workItem.minSectionY(), record.terrainMaxSectionIndex(), record.surfaceSectionIndex());
			int offset = index * samplesPerFeature;
			sums[offset] = minSectionY;
			counts[offset] = 1;
			sums[offset + 1] = surfaceSectionY;
			counts[offset + 1] = 1;
			sums[offset + 2] = maxSectionY;
			counts[offset + 2] = 1;
			cpuFallback[index] = (minSectionY + surfaceSectionY + maxSectionY) / 3.0F;
		}
		long cpuMicros = Math.max(1L, (System.nanoTime() - cpuStarted) / 1_000L);
		PauCLodCudaBridge.Result result = PauCCudaWorker.averageSeamHeights(sums, counts, samplesPerFeature, cpuFallback, cpuMicros);
		float[] preparedHeights = result.heights() != null && result.heights().length == records.size() ? result.heights() : cpuFallback;
		boolean cudaAvailable = result.available();
		long preparedAtMillis = System.currentTimeMillis();
		lastCudaPreparationStatus = (cudaAvailable ? "cuda" : "fallback") + ":" + result.status() + "/cells=" + records.size();

		List<CudaPreparedChunk> prepared = new ArrayList<>(records.size());
		for (int index = 0; index < records.size(); index++) {
			CudaPrepRecord record = records.get(index);
			int fineRadius = Math.max(8, record.requestRadius() / 3);
			int qualityTier = !cudaAvailable
				? LOD_QUALITY_COARSE
				: record.currentDistance() <= fineRadius
					? LOD_QUALITY_FINE
					: record.currentDistance() <= record.requestRadius()
						? LOD_QUALITY_MEDIUM
						: LOD_QUALITY_COARSE;
			int cacheState = cudaAvailable && Float.isFinite(preparedHeights[index])
				? LOD_CACHE_STATE_RENDER_READY
				: LOD_CACHE_STATE_CPU_PREPARED;
			prepared.add(new CudaPreparedChunk(
				record.dimensionId(),
				record.chunkPos(),
				record.sourceFingerprint(),
				preparedHeights[index],
				cudaAvailable,
				result.status(),
				qualityTier,
				cacheState,
				record.currentDistance(),
				record.priorityScore(),
				record.ahead(),
				workItem.shaderRuntime(),
				workItem.shaderFallback(),
				preparedAtMillis
			));
		}
		return new CudaPreparedBatch(
			workItem.sessionGeneration(),
			workItem.shaderRuntime(),
			workItem.shaderFallback(),
			cudaAvailable,
			result.status(),
			preparedAtMillis,
			prepared
		);
	}

	private static int sectionIndexToY(int minSectionY, int preferredIndex, int fallbackIndex) {
		int index = preferredIndex >= 0 ? preferredIndex : fallbackIndex;
		return minSectionY + Math.max(0, index);
	}

	private static int countPendingCudaBatches() {
		int preferredBatchSize = Math.max(1, PauCCudaWorker.preferredTerrainBatchSize() / 3);
		return Math.max(0, (PENDING_CUDA_PREP.size() + preferredBatchSize - 1) / preferredBatchSize);
	}

	private static boolean useAggressiveVanillaCudaPrefill(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		return !shaderFallbackFillActive()
			&& frame.fpsFirstVanilla()
			&& isDirectHorizonFillActive(fillRadius);
	}

	private static boolean isCudaTravelFillActive(PauCClientChunkPriorityScorer.PriorityFrame frame) {
		return frame.fastTravel() || frame.snapMode() || frame.movementCatchup() || isActiveTravelFill();
	}

	private static int defaultCudaMaxPending(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		boolean shaderFallback = shaderFallbackFillActive();
		boolean aggressiveVanillaPrefill = useAggressiveVanillaCudaPrefill(fillRadius, frame);
		boolean travelFill = isCudaTravelFillActive(frame);
		int base = shaderFallback ? (fillRadius >= 192 ? 16 : fillRadius >= 128 ? 12 : 7) : fillRadius >= 192 ? 8 : fillRadius >= 128 ? 6 : 3;
		int travelCap = shaderFallback ? 24 : aggressiveVanillaPrefill ? 20 : 14;
		if (aggressiveVanillaPrefill) {
			base += travelFill ? 4 : 2;
		} else if (frame.fpsFirstVanilla() && !shaderFallback) {
			base = Math.max(2, base - (travelFill ? 1 : 2));
			travelCap = Math.min(travelCap, 8);
		}
		return travelFill ? Math.min(travelCap, base + (aggressiveVanillaPrefill ? 5 : 3)) : base;
	}

	private static long defaultCudaIntervalMillis(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		boolean travelFill = isCudaTravelFillActive(frame);
		if (shaderFallbackFillActive()) {
			return travelFill ? 20L : 30L;
		}
		if (useAggressiveVanillaCudaPrefill(fillRadius, frame)) {
			return travelFill ? 24L : fillRadius >= 128 ? 38L : 52L;
		}
		if (frame.fpsFirstVanilla()) {
			if (travelFill) {
				return 40L;
			}
			return fillRadius >= 128 ? 70L : 95L;
		}
		if (travelFill) {
			return 30L;
		}
		return fillRadius >= 128 ? 45L : 70L;
	}

	private static int defaultCudaBatchFeatures(int minimumFeatures, int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		boolean aggressiveVanillaPrefill = useAggressiveVanillaCudaPrefill(fillRadius, frame);
		boolean travelFill = isCudaTravelFillActive(frame);
		int base = fillRadius >= 192 ? 448 : fillRadius >= 128 ? 320 : fillRadius >= 64 ? 160 : 96;
		if (shaderFallbackFillActive()) {
			base = Math.max(base, fillRadius >= 192 ? 1152 : fillRadius >= 128 ? 896 : 320);
		}
		if (travelFill) {
			base = Math.min(shaderFallbackFillActive() ? 1536 : 768, base + 224);
		}
		if (aggressiveVanillaPrefill) {
			double scale = travelFill ? 1.18D : 1.08D;
			int ceiling = fillRadius >= 192 ? 1024 : fillRadius >= 128 ? 896 : 640;
			base = Math.min(ceiling, Math.max(minimumFeatures, (int) Math.ceil(base * scale)));
		} else if (frame.fpsFirstVanilla() && !shaderFallbackFillActive()) {
			double scale = (frame.fastTravel() || frame.snapMode() || frame.movementCatchup()) ? 0.82D : 0.65D;
			base = Math.max(minimumFeatures, (int) Math.floor(base * scale));
		}
		return Math.max(minimumFeatures, base);
	}

	private static int minimumCudaCandidateCount(
		int minimumFeatures,
		int batchFeatures,
		int requestRadius,
		PauCClientChunkPriorityScorer.PriorityFrame frame
	) {
		int normalMinimum = Math.min(minimumFeatures, batchFeatures);
		if ((frame.fpsFirstVanilla() && !shaderFallbackFillActive() && isDirectHorizonFillActive(requestRadius))
			|| requestRadius <= FILL_BANDS[0]
			|| shouldPreferCoarseFill()
			|| frame.fastTravel()
			|| frame.snapMode()
			|| frame.movementCatchup()) {
			return 1;
		}
		return normalMinimum;
	}

	private static int defaultCudaBackgroundExtra(int fillRadius, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		boolean travelFill = isCudaTravelFillActive(frame);
		if (shaderFallbackFillActive()) {
			return fillRadius >= 192 ? 128 : fillRadius >= 128 ? 96 : 64;
		}
		if (useAggressiveVanillaCudaPrefill(fillRadius, frame)) {
			return travelFill ? (fillRadius >= 128 ? 96 : 64) : (fillRadius >= 128 ? 64 : 40);
		}
		if (frame.fpsFirstVanilla()) {
			if (travelFill) {
				return fillRadius >= 128 ? 48 : 24;
			}
			return fillRadius >= 128 ? 24 : 12;
		}
		if (travelFill) {
			return fillRadius >= 128 ? 96 : 48;
		}
		return fillRadius >= 128 ? 48 : 24;
	}

	private static int defaultCudaDrainCells() {
		CoverageSnapshot snapshot = lastCoverageSnapshot;
		boolean shaderFallback = shaderFallbackFillActive();
		boolean fpsFirstVanilla = PauCClientChunkPriorityScorer.isFpsFirstVanillaMode();
		boolean aggressiveVanillaPrefill = !shaderFallback && fpsFirstVanilla && isDirectHorizonFillActive();
		int base = shaderFallback ? 1536 : aggressiveVanillaPrefill ? 768 : fpsFirstVanilla ? 320 : 512;
		if (snapshot.available() && snapshot.activeBandRatio() < 0.35D) {
			base += shaderFallback ? 1024 : aggressiveVanillaPrefill ? 512 : fpsFirstVanilla ? 192 : 512;
		}
		if (isActiveTravelFill()) {
			base += shaderFallback ? 1024 : aggressiveVanillaPrefill ? 768 : fpsFirstVanilla ? 256 : 512;
		}
		return Math.min(shaderFallback ? 4096 : aggressiveVanillaPrefill ? 2048 : fpsFirstVanilla ? 1024 : 2048, base);
	}

	private static int defaultCudaPersistCells(int drainBudget) {
		int fallback;
		if (shaderFallbackFillActive()) {
			fallback = 1536;
		} else if (PauCClientChunkPriorityScorer.isFpsFirstVanillaMode() && isDirectHorizonFillActive()) {
			fallback = isActiveTravelFill() ? 1280 : 1024;
		} else {
			fallback = 768;
		}
		return Math.min(drainBudget, fallback);
	}

	private static int firstFillBandEnd(int targetDistance) {
		int sanitizedTarget = sanitizeFillRadiusChunks(targetDistance);
		return Math.min(sanitizedTarget, FILL_BANDS[0]);
	}

	private static int sanitizeFillRadiusChunks(int targetDistance) {
		PauCLodRange range = PauCClientLodGovernor.currentRange();
		int radiusCap = PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS;
		if (range != null && range.enabled()) {
			radiusCap = Math.max(radiusCap, range.roundHorizonEndChunk());
		}
		return Math.max(0, Math.min(radiusCap, targetDistance));
	}

	private static int requestedFillBandLead(CoverageSnapshot snapshot) {
		int lead = isActiveTravelFill() ? 3 : 2;
		if (shaderFallbackFillActive()) {
			lead++;
		}
		if (snapshot.activeBandRatio() >= 0.08D) {
			lead++;
		}
		if (snapshot.activeBandRatio() >= 0.22D || snapshot.ratio() >= 0.10D) {
			lead++;
		}
		if (snapshot.activeBandRatio() >= 0.45D || snapshot.ratio() >= 0.24D) {
			lead++;
		}
		if (PauCClientChunkPriorityScorer.isFpsFirstVanillaMode() && !shaderFallbackFillActive()) {
			lead -= isActiveTravelFill() ? 1 : 2;
			if (snapshot.activeBandRatio() < 0.30D && snapshot.ratio() < 0.18D) {
				lead = Math.min(lead, isActiveTravelFill() ? 2 : 1);
			}
			lead = Math.min(lead, 3);
		}
		return Math.max(1, Math.min(FILL_BANDS.length, lead));
	}

	private static int advanceFillBandEnd(int currentRadius, int targetRadius, int bandLead) {
		int radius = Math.max(0, Math.min(targetRadius, currentRadius));
		if (radius >= targetRadius || bandLead <= 0) {
			return radius;
		}
		int remaining = bandLead;
		for (int band : FILL_BANDS) {
			if (band <= radius) {
				continue;
			}
			radius = Math.min(targetRadius, band);
			remaining--;
			if (remaining <= 0 || radius >= targetRadius) {
				return radius;
			}
		}
		return targetRadius;
	}

	private static int fillBandRank(int distanceChunks) {
		for (int index = 0; index < FILL_BANDS.length; index++) {
			if (distanceChunks <= FILL_BANDS[index]) {
				return index;
			}
		}
		return FILL_BANDS.length;
	}

	private static int normalizeCacheState(int lodCacheState, float cudaTerrainSectionY) {
		if (lodCacheState >= LOD_CACHE_STATE_CUDA_PREPARED && Float.isFinite(cudaTerrainSectionY)) {
			return LOD_CACHE_STATE_RENDER_READY;
		}
		return Math.max(LOD_CACHE_STATE_DIRTY, Math.min(LOD_CACHE_STATE_RENDER_READY, lodCacheState));
	}

	private static void logCudaPreparationStatus(String status) {
		long now = System.currentTimeMillis();
		long intervalMs = readLong(CUDA_WORLD_CACHE_LOG_INTERVAL_MS_PROPERTY, 5_000L, 1_000L, 60_000L);
		if (now - lastCudaPreparationLogAtMillis < intervalMs) {
			return;
		}
		lastCudaPreparationLogAtMillis = now;
		LOGGER.info(
			"PauC world LOD CUDA cache: status={}, scheduledCells={}, completedCells={}, pendingCells={}, readyCells={}, {}.",
			status,
			lastCudaScheduledCells,
			lastCudaCompletedCells,
			PENDING_CUDA_PREP.size(),
			countReadyCudaCells(),
			PauCCudaWorker.describeMetrics()
		);
	}

	private static int countReadyCudaCells() {
		int cells = 0;
		for (CudaPreparedBatch batch : COMPLETED_CUDA_PREPARATIONS) {
			cells += batch.chunks().size();
		}
		return cells;
	}

	private static String diskSeedRegionKey(String dimensionId, int regionX, int regionZ) {
		return dimensionId + ":" + regionX + ":" + regionZ;
	}

	private static String describeWorldCacheState() {
		int diskSeeded = 0;
		int metadataClean = 0;
		int cpuPrepared = 0;
		int cudaPrepared = 0;
		int renderReady = 0;
		int dirty = 0;
		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (record.diskSeeded) {
				diskSeeded++;
			}
			if (record.lodCacheState == LOD_CACHE_STATE_DIRTY) {
				dirty++;
			} else if (record.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
				renderReady++;
			} else if (record.lodCacheState == LOD_CACHE_STATE_CUDA_PREPARED) {
				cudaPrepared++;
			} else if (record.lodCacheState == LOD_CACHE_STATE_CPU_PREPARED) {
				cpuPrepared++;
			} else if (record.lodCacheState == LOD_CACHE_STATE_METADATA_CLEAN) {
				metadataClean++;
			}
		}
		return "worldCache[diskSeeded="
			+ diskSeeded
			+ ", clean="
			+ metadataClean
			+ ", cpuPrepared="
			+ cpuPrepared
			+ ", cudaPrepared="
			+ cudaPrepared
			+ ", renderReady="
			+ renderReady
			+ ", dirty="
			+ dirty
			+ ", diskPending="
			+ PENDING_DISK_SEED_REGIONS.size()
			+ ", diskReady="
			+ COMPLETED_DISK_SEEDS.size()
			+ ", cudaPending="
			+ PENDING_CUDA_PREP.size()
			+ ", cudaReady="
			+ countReadyCudaCells()
			+ ", cudaScheduled="
			+ lastCudaScheduledCells
			+ ", cudaCompleted="
			+ lastCudaCompletedCells
			+ ", lastCuda="
			+ lastCudaPreparationStatus
			+ "]";
	}

	private static void schedulePlan(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame, WarmChunkRecord record) {
		record.lastPlannedAtMillis = System.currentTimeMillis();
		long chunkKey = record.chunkPos.toLong();
		if (!PENDING_PLANS.add(chunkKey)) {
			return;
		}

		Path sessionRoot = PauCClientWarmChunkDiskCache.resolveSessionRoot(level);
		WarmChunkWorkItem workItem = new WarmChunkWorkItem(
			SESSION_GENERATION.get(),
			record.dimensionId,
			record.chunkPos,
			record.nonEmptySectionMask,
			record.nonEmptySectionCount,
			record.structureSectionMask,
			record.fluidSectionMask,
			record.surfaceSectionIndex,
			record.lastPriorityScore,
			frame.playerSectionY(),
			frame.minSectionY(),
			frame.maxSectionY(),
			sessionRoot
		);
		PauCTaskPriority priority = frame.snapMode() || frame.fastTravel() || frame.movementCatchup() || record.lastPriorityScore >= 0.75D ? PauCTaskPriority.FOV : PauCTaskPriority.ACTIVE;
		String description = "client warm " + record.chunkPos + " " + record.dimensionId;
		CompletableFuture<PreparedWarmPlan> future = PauCScheduler.submitChunkMesh(priority, description, () -> preparePlan(workItem));
		future.whenComplete((plan, throwable) -> {
			PENDING_PLANS.remove(chunkKey);
			if (throwable != null) {
				LOGGER.debug("PauC frontier warmup planning failed for {} in {}.", record.chunkPos, record.dimensionId, throwable);
				return;
			}

			if (plan != null && plan.sessionGeneration() == SESSION_GENERATION.get() && !plan.sectionYs().isEmpty()) {
				COMPLETED_PLANS.addLast(plan);
			}
		});
	}

	private static PreparedWarmPlan preparePlan(WarmChunkWorkItem workItem) {
		int sectionMask = workItem.nonEmptySectionMask();
		int nonEmptySections = workItem.nonEmptySectionCount();
		int structureSectionMask = workItem.structureSectionMask();
		int fluidSectionMask = workItem.fluidSectionMask();
		int surfaceSectionIndex = workItem.surfaceSectionIndex();
		if (sectionMask == 0 || nonEmptySections <= 0) {
			WarmChunkMetadata diskMetadata = PauCClientWarmChunkDiskCache.read(workItem.sessionRoot(), workItem.dimensionId(), workItem.chunkPos());
			if (diskMetadata != null) {
				sectionMask = diskMetadata.nonEmptySectionMask();
				nonEmptySections = diskMetadata.nonEmptySectionCount();
				structureSectionMask = diskMetadata.structureSectionMask();
				fluidSectionMask = diskMetadata.fluidSectionMask();
				surfaceSectionIndex = diskMetadata.surfaceSectionIndex();
			}
		}

		List<Integer> orderedSections = new ArrayList<>();
		for (int index = 0; index < 32; index++) {
			if ((sectionMask & (1 << index)) == 0) {
				continue;
			}

			int sectionY = workItem.minSectionY() + index;
			if (sectionY >= workItem.minSectionY() && sectionY <= workItem.maxSectionY()) {
				orderedSections.add(sectionY);
			}
		}

		int playerSectionY = workItem.playerSectionY();
		int minSectionY = workItem.minSectionY();
		int surfaceSectionY = surfaceSectionIndex >= 0 ? minSectionY + surfaceSectionIndex : playerSectionY;
		int finalStructureSectionMask = structureSectionMask;
		int finalFluidSectionMask = fluidSectionMask;
		orderedSections.sort(Comparator
			.comparingInt((Integer sectionY) -> preloadRank(sectionY, minSectionY, surfaceSectionY, playerSectionY, finalStructureSectionMask, finalFluidSectionMask))
			.thenComparingInt(sectionY -> Math.abs(sectionY - playerSectionY))
			.thenComparingInt(sectionY -> Math.abs(sectionY - surfaceSectionY)));
		return new PreparedWarmPlan(
			workItem.sessionGeneration(),
			workItem.dimensionId(),
			workItem.chunkPos(),
			orderedSections,
			structureSectionMask,
			fluidSectionMask,
			surfaceSectionY,
			workItem.priorityScore(),
			PauCClientMemoryBudgetController.estimateRamBytes(nonEmptySections),
			PauCClientMemoryBudgetController.estimateGpuSectionCost(nonEmptySections)
		);
	}

	private static int preloadRank(int sectionY, int minSectionY, int surfaceSectionY, int playerSectionY, int structureSectionMask, int fluidSectionMask) {
		int sectionIndex = sectionY - minSectionY;
		boolean structureSection = sectionIndex >= 0 && sectionIndex < 32 && (structureSectionMask & (1 << sectionIndex)) != 0;
		boolean fluidSection = sectionIndex >= 0 && sectionIndex < 32 && (fluidSectionMask & (1 << sectionIndex)) != 0;
		if (sectionY == surfaceSectionY || structureSection) {
			return 0;
		}
		if (fluidSection) {
			return 1;
		}
		if (Math.abs(sectionY - playerSectionY) <= 1) {
			return 2;
		}
		return 3;
	}

	private static int scaleWarmupBudget(int budget, double scale, int minimum) {
		if (budget <= 0) {
			return 0;
		}

		return Math.max(minimum, (int) Math.floor(budget * Math.max(0.10D, Math.min(1.50D, scale))));
	}

	private static void pruneTrackedRecords() {
		long now = System.currentTimeMillis();
		if (TRACKED_CHUNKS.size() <= lastBudget.maxTrackedChunks()) {
			TRACKED_CHUNKS.entrySet().removeIf(entry -> !entry.getValue().live
				&& !entry.getValue().retained
				&& entry.getValue().lodCacheState < LOD_CACHE_STATE_CUDA_PREPARED
				&& now - entry.getValue().lastSeenAtMillis > STALE_RECORD_TTL_MS);
			return;
		}

		int toRemove = TRACKED_CHUNKS.size() - lastBudget.maxTrackedChunks();
		List<Map.Entry<Long, WarmChunkRecord>> candidates = new ArrayList<>(Math.min(toRemove, 256));
		for (Map.Entry<Long, WarmChunkRecord> entry : TRACKED_CHUNKS.entrySet()) {
			WarmChunkRecord record = entry.getValue();
			if (record.live || record.retained) {
				continue;
			}
			addBoundedCandidate(candidates, entry, toRemove, TRACKED_RECORD_PRUNE_ORDER);
		}
		candidates.sort(TRACKED_RECORD_PRUNE_ORDER);

		for (Map.Entry<Long, WarmChunkRecord> candidate : candidates) {
			TRACKED_CHUNKS.remove(candidate.getKey(), candidate.getValue());
		}
	}

	private static void resetState(boolean enableHotRestore) {
		SESSION_GENERATION.incrementAndGet();
		PauCClientChunkPriorityScorer.resetRuntimeState();
		TRACKED_CHUNKS.clear();
		PENDING_PLANS.clear();
		COMPLETED_PLANS.clear();
		PauCClientRenderPrep.reset();
		PauCClientUploadBudgetController.reset();
		PENDING_DISK_SEED_REGIONS.clear();
		DISK_SEED_REGION_SCAN_TIMES.clear();
		COMPLETED_DISK_SEEDS.clear();
		PENDING_CUDA_PREP.clear();
		COMPLETED_CUDA_PREPARATIONS.clear();
		lastKnownDimension = null;
		lastKnownRetainedRamBytes = 0L;
		lastKnownRetainedChunks = 0;
		lastKnownHotMeshSections = 0;
		lastKnownQueuedMeshSections = 0;
		zeroVisibleChunkStreak = 0;
		lastSnapMode = false;
		lastFastTravel = false;
		lastMovementCatchup = false;
		lastCoverageSnapshot = CoverageSnapshot.unavailable();
		PauCLodNearClipOverride.setTerrainContinuityHold(false, "session-reset");
		lastCoveragePresentationHoldUntilMillis = 0L;
		lastCoverageHoldDemandAtMillis = 0L;
		lastConfiguredTargetChangeAtMillis = 0L;
		lastDiskSeedScheduleAtMillis = 0L;
		lastCudaPreparationScheduleAtMillis = 0L;
		lastCudaPreparationLogAtMillis = 0L;
		lastCudaPreparationStatus = "not-run";
		lastCudaScheduledCells = 0;
		lastCudaCompletedCells = 0;
		hotRestoreActive = enableHotRestore;
		sessionResumedAtMillis = enableHotRestore ? System.currentTimeMillis() : 0L;
		lastHotRestoreQueued = 0;
		lastHotRestoreApplied = 0;
		lastHotRestoreRenderReady = 0;
		lastHotRestoreCompletionAtMillis = 0L;
		lastWarmupBaseSectionBudget = 0;
		lastWarmupLimitedSectionBudget = 0;
		lastWarmupGrantedSectionBudget = 0;
		lastWarmupCompletionBudget = 0;
		lastWarmupAppliedPlans = 0;
		lastWarmupScheduledSections = 0;
		lastVisualRecoveryBudget = 0;
		lastVisualRecoveryGrantedBudget = 0;
		lastVisualRecoveryScheduledSections = 0;
	}

	public record WarmChunkMetadata(
		String dimensionId,
		ChunkPos chunkPos,
		int nonEmptySectionMask,
		int nonEmptySectionCount,
		int structureSectionMask,
		int fluidSectionMask,
		int surfaceSectionIndex,
		int terrainMinSectionIndex,
		int terrainMaxSectionIndex,
		int cacheVersion,
		long sourceFingerprint,
		int lodCacheState,
		int lodQualityTier,
		float cudaTerrainSectionY,
		long cudaPreparedAtMillis,
		String cudaProfile,
		long savedAtMillis
	) {
		public static WarmChunkMetadata capture(ClientLevel level, LevelChunk chunk) {
			LevelChunkSection[] sections = chunk.getSections();
			int sectionMask = 0;
			int fluidSectionMask = 0;
			int nonEmptySections = 0;
			int surfaceSectionIndex = -1;
			int minSectionIndex = -1;
			int maxSectionIndex = -1;
			for (int index = 0; index < sections.length && index < 32; index++) {
				LevelChunkSection section = sections[index];
				if (section != null && !section.hasOnlyAir()) {
					sectionMask |= 1 << index;
					nonEmptySections++;
					if (minSectionIndex < 0) {
						minSectionIndex = index;
					}
					maxSectionIndex = index;
					surfaceSectionIndex = index;
					if (section.maybeHas(blockState -> !blockState.getFluidState().isEmpty())) {
						fluidSectionMask |= 1 << index;
					}
				}
			}
			int structureSectionMask = 0;
			for (BlockPos blockPos : chunk.getBlockEntities().keySet()) {
				int sectionIndex = (blockPos.getY() >> 4) - level.getMinSection();
				if (sectionIndex >= 0 && sectionIndex < 32) {
					structureSectionMask |= 1 << sectionIndex;
				}
			}
			return new WarmChunkMetadata(
				level.dimension().location().toString(),
				chunk.getPos(),
				sectionMask,
				nonEmptySections,
				structureSectionMask,
				fluidSectionMask,
				surfaceSectionIndex,
				minSectionIndex,
				maxSectionIndex,
				WARM_CACHE_SCHEMA_VERSION,
				computeSourceFingerprint(sectionMask, nonEmptySections, structureSectionMask, fluidSectionMask, surfaceSectionIndex, minSectionIndex, maxSectionIndex),
				LOD_CACHE_STATE_METADATA_CLEAN,
				LOD_QUALITY_COARSE,
				Float.NaN,
				0L,
				"",
				System.currentTimeMillis()
			);
		}

		public static int minFilledSectionIndex(int sectionMask) {
			if (sectionMask == 0) {
				return -1;
			}
			return Integer.numberOfTrailingZeros(sectionMask);
		}

		public static int maxFilledSectionIndex(int sectionMask) {
			if (sectionMask == 0) {
				return -1;
			}
			return 31 - Integer.numberOfLeadingZeros(sectionMask);
		}

		public static long computeSourceFingerprint(
			int sectionMask,
			int nonEmptySections,
			int structureSectionMask,
			int fluidSectionMask,
			int surfaceSectionIndex,
			int terrainMinSectionIndex,
			int terrainMaxSectionIndex
		) {
			long fingerprint = 0xcbf29ce484222325L;
			fingerprint = mixFingerprint(fingerprint, sectionMask);
			fingerprint = mixFingerprint(fingerprint, nonEmptySections);
			fingerprint = mixFingerprint(fingerprint, structureSectionMask);
			fingerprint = mixFingerprint(fingerprint, fluidSectionMask);
			fingerprint = mixFingerprint(fingerprint, surfaceSectionIndex);
			fingerprint = mixFingerprint(fingerprint, terrainMinSectionIndex);
			fingerprint = mixFingerprint(fingerprint, terrainMaxSectionIndex);
			return fingerprint;
		}

		private static long mixFingerprint(long fingerprint, int value) {
			long mixed = fingerprint ^ (value & 0xFFFFFFFFL);
			return mixed * 0x100000001b3L;
		}
	}

	public record PreparedWarmPlan(
		int sessionGeneration,
		String dimensionId,
		ChunkPos chunkPos,
		List<Integer> sectionYs,
		int structureSectionMask,
		int fluidSectionMask,
		int surfaceSectionY,
		double priorityScore,
		long estimatedRamBytes,
		int estimatedGpuSections
	) {
	}

	public record ProxyRenderCell(
		int chunkX,
		int chunkZ,
		float terrainSectionY,
		int currentDistance,
		double radialDistance,
		int qualityTier,
		boolean hasFluid,
		boolean hasStructure,
		boolean ahead,
		long preparedAtMillis
	) {
	}

	public static List<ProxyRenderCell> collectProxyRenderCells(ClientLevel level, int maxCells) {
		if (level == null || maxCells <= 0) {
			return List.of();
		}
		PauCLodRange range = PauCClientLodGovernor.currentRange();
		if (range == null || !range.enabled()) {
			range = PauCLodHorizonState.currentRange();
		}
		if (range == null || !range.enabled()) {
			return List.of();
		}

		String dimensionId = level.dimension().location().toString();
		int startDistance = range.lodStartChunk();
		int endDistance = Math.max(range.lodEndChunk(), range.roundHorizonEndChunk());
		List<WarmChunkRecord> candidates = new ArrayList<>(Math.min(maxCells, 256));
		for (WarmChunkRecord record : TRACKED_CHUNKS.values()) {
			if (!record.dimensionId.equals(dimensionId)
				|| record.lodCacheState < LOD_CACHE_STATE_RENDER_READY
				|| !Float.isFinite(record.cudaTerrainSectionY)
				|| record.currentDistance < startDistance
				|| record.currentDistance > endDistance) {
				continue;
			}
			addBoundedCandidate(candidates, record, maxCells, PROXY_CELL_ORDER);
		}
		candidates.sort(PROXY_CELL_ORDER);

		List<ProxyRenderCell> cells = new ArrayList<>(candidates.size());
		for (WarmChunkRecord record : candidates) {
			cells.add(new ProxyRenderCell(
				record.chunkPos.x,
				record.chunkPos.z,
				record.cudaTerrainSectionY,
				record.currentDistance,
				record.currentRadialDistance,
				record.lodQualityTier,
				record.fluidSectionMask != 0,
				record.structureSectionMask != 0,
				record.ahead,
				record.cudaPreparedAtMillis
			));
		}
		return List.copyOf(cells);
	}

	private static int compareCudaCandidate(WarmChunkRecord left, WarmChunkRecord right, int priorityRadius) {
		int bandCompare = Integer.compare(fillBandRank(left.currentDistance), fillBandRank(right.currentDistance));
		if (bandCompare != 0) {
			return bandCompare;
		}
		int priorityCompare = Integer.compare(left.currentDistance > priorityRadius ? 1 : 0, right.currentDistance > priorityRadius ? 1 : 0);
		if (priorityCompare != 0) {
			return priorityCompare;
		}
		int preparedCompare = Integer.compare(left.lodCacheState >= LOD_CACHE_STATE_CUDA_PREPARED ? 1 : 0, right.lodCacheState >= LOD_CACHE_STATE_CUDA_PREPARED ? 1 : 0);
		if (preparedCompare != 0) {
			return preparedCompare;
		}
		int scoreCompare = Double.compare(right.lastPriorityScore, left.lastPriorityScore);
		if (scoreCompare != 0) {
			return scoreCompare;
		}
		int distanceCompare = Integer.compare(left.currentDistance, right.currentDistance);
		if (distanceCompare != 0) {
			return distanceCompare;
		}
		return Double.compare(left.currentRadialDistance, right.currentRadialDistance);
	}

	private static int hotRestorePriority(WarmChunkRecord record) {
		if (!hotRestoreActive) {
			return 4;
		}
		if (record.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
			return 0;
		}
		if (record.lodCacheState >= LOD_CACHE_STATE_CUDA_PREPARED) {
			return 1;
		}
		if (record.lodCacheState >= LOD_CACHE_STATE_CPU_PREPARED) {
			return 2;
		}
		return record.diskSeeded ? 3 : 4;
	}

	private static <T> void addBoundedCandidate(List<T> candidates, T candidate, int limit, Comparator<? super T> order) {
		if (limit <= 0) {
			return;
		}
		if (candidates.size() < limit) {
			candidates.add(candidate);
			return;
		}

		int worstIndex = 0;
		for (int index = 1; index < candidates.size(); index++) {
			if (order.compare(candidates.get(index), candidates.get(worstIndex)) > 0) {
				worstIndex = index;
			}
		}
		if (order.compare(candidate, candidates.get(worstIndex)) < 0) {
			candidates.set(worstIndex, candidate);
		}
	}

	private record WarmChunkWorkItem(
		int sessionGeneration,
		String dimensionId,
		ChunkPos chunkPos,
		int nonEmptySectionMask,
		int nonEmptySectionCount,
		int structureSectionMask,
		int fluidSectionMask,
		int surfaceSectionIndex,
		double priorityScore,
		int playerSectionY,
		int minSectionY,
		int maxSectionY,
		Path sessionRoot
	) {
	}

	private record RetentionCandidate(
		long chunkKey,
		double score,
		long retainedAtMillis,
		long estimatedRamBytes,
		boolean stale
	) {
	}

	private record RegionCandidate(
		String key,
		int regionX,
		int regionZ,
		int distance
	) {
	}

	private record CudaPrepWorkItem(
		int sessionGeneration,
		int minSectionY,
		int samplesPerFeature,
		boolean shaderRuntime,
		boolean shaderFallback,
		List<CudaPrepRecord> records
	) {
	}

	private record CudaPrepRecord(
		String dimensionId,
		ChunkPos chunkPos,
		long sourceFingerprint,
		int surfaceSectionIndex,
		int terrainMinSectionIndex,
		int terrainMaxSectionIndex,
		int currentDistance,
		double radialDistance,
		double priorityScore,
		boolean ahead,
		int requestRadius,
		int fillRadius
	) {
	}

	private record CudaPreparedBatch(
		int sessionGeneration,
		boolean shaderRuntime,
		boolean shaderFallback,
		boolean cudaAvailable,
		String cudaProfile,
		long preparedAtMillis,
		List<CudaPreparedChunk> chunks
	) {
	}

	private record CudaPreparedChunk(
		String dimensionId,
		ChunkPos chunkPos,
		long sourceFingerprint,
		float terrainSectionY,
		boolean cudaAvailable,
		String cudaProfile,
		int lodQualityTier,
		int lodCacheState,
		int currentDistance,
		double priorityScore,
		boolean ahead,
		boolean shaderRuntime,
		boolean shaderFallback,
		long preparedAtMillis
	) {
	}

	private record CoverageSnapshot(
		boolean available,
		boolean rendererAvailable,
		int expected,
		int covered,
		int live,
		int retained,
		double ratio,
		int nearBandEnd,
		int nearExpected,
		int nearCovered,
		double nearRatio,
		boolean nearDebt,
		int activeBandEnd,
		int activeBandExpected,
		int activeBandCovered,
		double activeBandRatio,
		boolean preferCoarseFill
	) {
		private static CoverageSnapshot unavailable() {
			return new CoverageSnapshot(false, false, 0, 0, 0, 0, 1.0D, 0, 0, 0, 1.0D, false, 0, 0, 0, 1.0D, false);
		}

		private static CoverageSnapshot capture(
			ClientLevel level,
			PauCClientChunkPriorityScorer.PriorityFrame frame,
			boolean rendererAvailable,
			Collection<WarmChunkRecord> records
		) {
			PauCLodRange range = PauCClientLodGovernor.currentRange();
			if (range == null || !range.enabled()) {
				return unavailable();
			}

			String dimensionId = level.dimension().location().toString();
			int firstDistance = Math.max(range.lodStartChunk(), frame.renderDistanceChunks() + 1);
			int lastDistance = Math.min(Math.min(range.lodEndChunk(), frame.warmRadiusChunks()), range.roundHorizonEndChunk());
			if (firstDistance > lastDistance) {
				return unavailable();
			}

			int expected = countSquareRingCells(firstDistance, lastDistance);
			int covered = 0;
			int live = 0;
			int retained = 0;
			for (WarmChunkRecord record : records) {
				if (!record.dimensionId.equals(dimensionId)) {
					continue;
				}
				if (record.currentDistance < firstDistance || record.currentDistance > lastDistance) {
					continue;
				}
				if (record.live) {
					live++;
				}
				if (record.retained) {
					retained++;
				}
				if (record.live || record.retained || record.nonEmptySectionCount > 0) {
					covered++;
				}
			}

			double ratio = expected > 0 ? Math.min(1.0D, (double) covered / (double) expected) : 1.0D;
			boolean catchup = frame.fastTravel() || frame.snapMode() || frame.movementCatchup();
			int nearRingChunks = PauCClientChunkPriorityScorer.vanillaSealRingChunks(catchup);
			int nearBandEnd = Math.min(lastDistance, firstDistance + Math.max(1, nearRingChunks) - 1);
			CoverageBand nearBand = CoverageBand.empty(nearBandEnd);
			if (nearBandEnd >= firstDistance) {
				nearBand = captureBand(firstDistance, nearBandEnd, dimensionId, records);
			}
			double defaultNearThreshold = rendererAvailable ? 0.92D : 0.86D;
			double nearThreshold = readDouble(
				catchup ? "pauc.lod.nearCoverageCatchupRatio" : "pauc.lod.nearCoverageRatio",
				catchup ? Math.max(defaultNearThreshold, 0.94D) : defaultNearThreshold,
				0.50D,
				1.0D
			);
			boolean nearDebt = nearBand.expected() > 0 && nearBand.ratio() < nearThreshold;
			double defaultCoverageThreshold = catchup || !rendererAvailable ? 0.78D : 0.68D;
			double coverageThreshold = catchup
				? readDouble(COARSE_FILL_CATCHUP_COVERAGE_RATIO_PROPERTY, defaultCoverageThreshold, 0.20D, 0.98D)
				: readDouble(COARSE_FILL_COVERAGE_RATIO_PROPERTY, defaultCoverageThreshold, 0.20D, 0.98D);
			CoverageBand activeBand = activeCoverageBand(firstDistance, lastDistance, dimensionId, records, coverageThreshold);
			boolean preferCoarseFill = nearDebt || (activeBand.expected() > 0 && activeBand.ratio() < coverageThreshold);
			return new CoverageSnapshot(
				true,
				rendererAvailable,
				expected,
				covered,
				live,
				retained,
				ratio,
				nearBandEnd,
				nearBand.expected(),
				nearBand.covered(),
				nearBand.ratio(),
				nearDebt,
				activeBand.endDistance(),
				activeBand.expected(),
				activeBand.covered(),
				activeBand.ratio(),
				preferCoarseFill
			);
		}

		private static CoverageBand activeCoverageBand(
			int firstDistance,
			int lastDistance,
			String dimensionId,
			Collection<WarmChunkRecord> records,
			double coverageThreshold
		) {
			CoverageBand lastBand = CoverageBand.empty(Math.max(firstDistance, lastDistance));
			for (int band : FILL_BANDS) {
				int bandEnd = Math.min(lastDistance, band);
				if (bandEnd < firstDistance) {
					continue;
				}
				CoverageBand snapshot = captureBand(firstDistance, bandEnd, dimensionId, records);
				lastBand = snapshot;
				if (snapshot.expected() > 0 && snapshot.ratio() < coverageThreshold) {
					return snapshot;
				}
				if (band >= lastDistance) {
					break;
				}
			}
			return lastBand;
		}

		private static CoverageBand captureBand(
			int firstDistance,
			int endDistance,
			String dimensionId,
			Collection<WarmChunkRecord> records
		) {
			int expected = countSquareRingCells(firstDistance, endDistance);
			int covered = 0;
			for (WarmChunkRecord record : records) {
				if (!record.dimensionId.equals(dimensionId)) {
					continue;
				}
				if (record.currentDistance < firstDistance || record.currentDistance > endDistance) {
					continue;
				}
				if (record.live || record.retained || record.nonEmptySectionCount > 0) {
					covered++;
				}
			}
			double ratio = expected > 0 ? Math.min(1.0D, (double) covered / (double) expected) : 1.0D;
			return new CoverageBand(endDistance, expected, covered, ratio);
		}

		private String describe() {
			if (!available) {
				return "coverage[unavailable]";
			}
			return "coverage[covered="
				+ covered
				+ "/"
				+ expected
				+ ", ratio="
				+ String.format(java.util.Locale.ROOT, "%.2f", ratio)
				+ ", near="
				+ nearBandEnd
				+ ":"
				+ nearCovered
				+ "/"
				+ nearExpected
				+ "@"
				+ String.format(java.util.Locale.ROOT, "%.2f", nearRatio)
				+ ", nearDebt="
				+ nearDebt
				+ ", activeBand="
				+ activeBandEnd
				+ ":"
				+ activeBandCovered
				+ "/"
				+ activeBandExpected
				+ "@"
				+ String.format(java.util.Locale.ROOT, "%.2f", activeBandRatio)
				+ ", live="
				+ live
				+ ", retained="
				+ retained
				+ ", coarseFill="
				+ preferCoarseFill
				+ ", renderer="
				+ rendererAvailable
				+ "]";
		}
	}

	private record CoverageBand(
		int endDistance,
		int expected,
		int covered,
		double ratio
	) {
		private static CoverageBand empty(int endDistance) {
			return new CoverageBand(endDistance, 0, 0, 1.0D);
		}
	}

	private static int countSquareRingCells(int firstDistance, int lastDistance) {
		if (firstDistance <= 0) {
			return ((lastDistance * 2) + 1) * ((lastDistance * 2) + 1);
		}
		int outer = ((lastDistance * 2) + 1) * ((lastDistance * 2) + 1);
		int innerRadius = firstDistance - 1;
		int inner = ((innerRadius * 2) + 1) * ((innerRadius * 2) + 1);
		return Math.max(0, outer - inner);
	}

	private static boolean hasRecoveredPresentationCoverage(CoverageSnapshot snapshot) {
		if (!snapshot.available() || snapshot.preferCoarseFill()) {
			return false;
		}
		if (snapshot.nearDebt()) {
			return false;
		}
		boolean queueDrained = isPauCQueueDrained();
		double recoveredRatio = readDouble(
			queueDrained ? "pauc.lod.presentationRecoveredCoverageRatioIdle" : "pauc.lod.presentationRecoveredCoverageRatioBusy",
			queueDrained ? 0.50D : 0.60D,
			0.20D,
			0.98D
		);
		if (snapshot.ratio() < recoveredRatio) {
			return false;
		}
		if (snapshot.activeBandExpected() <= 0) {
			return true;
		}
		double recoveredActiveRatio = readDouble(
			queueDrained ? "pauc.lod.presentationRecoveredActiveBandRatioIdle" : "pauc.lod.presentationRecoveredActiveBandRatioBusy",
			queueDrained ? 0.54D : 0.62D,
			0.20D,
			0.99D
		);
		return snapshot.activeBandRatio() >= recoveredActiveRatio;
	}

	private static boolean hasQueueDrainedReleaseCoverage(CoverageSnapshot snapshot) {
		if (!snapshot.available() || snapshot.preferCoarseFill()) {
			return false;
		}
		if (snapshot.nearDebt()) {
			return false;
		}
		double recoveredRatio = readDouble(
			isActiveTravelFill() ? "pauc.lod.presentationReleaseCoverageRatioTravel" : "pauc.lod.presentationReleaseCoverageRatioIdle",
			isActiveTravelFill() ? 0.46D : 0.42D,
			0.20D,
			0.98D
		);
		if (snapshot.ratio() < recoveredRatio) {
			return false;
		}
		if (snapshot.activeBandExpected() <= 0) {
			return true;
		}
		double recoveredActiveRatio = readDouble(
			isActiveTravelFill() ? "pauc.lod.presentationReleaseActiveBandRatioTravel" : "pauc.lod.presentationReleaseActiveBandRatioIdle",
			isActiveTravelFill() ? 0.50D : 0.46D,
			0.20D,
			0.99D
		);
		return snapshot.activeBandRatio() >= recoveredActiveRatio;
	}

	private static boolean hasStaleQueueDrainedReleaseCoverage(CoverageSnapshot snapshot, long now) {
		if (!snapshot.available() || snapshot.preferCoarseFill() || lastCoverageHoldDemandAtMillis <= 0L) {
			return false;
		}
		if (snapshot.nearDebt()) {
			return false;
		}
		long staleMs = readLong("pauc.lod.staleCoverageHoldReleaseMs", 900L, 0L, 15_000L);
		if (now - lastCoverageHoldDemandAtMillis < staleMs) {
			return false;
		}
		double recoveredRatio = readDouble("pauc.lod.staleCoverageHoldReleaseRatio", 0.34D, 0.20D, 0.98D);
		if (snapshot.ratio() < recoveredRatio) {
			return false;
		}
		if (snapshot.activeBandExpected() <= 0) {
			return true;
		}
		double recoveredActiveRatio = readDouble("pauc.lod.staleCoverageHoldReleaseActiveBandRatio", 0.38D, 0.20D, 0.99D);
		return snapshot.activeBandRatio() >= recoveredActiveRatio;
	}

	private static boolean isTargetDistanceChangeGraceActive(long now) {
		long graceMs = readLong("pauc.lod.targetDistanceChangeCoverageReleaseMs", 750L, 0L, 10_000L);
		return lastConfiguredTargetChangeAtMillis > 0L && now - lastConfiguredTargetChangeAtMillis <= graceMs;
	}

	private static boolean isPauCQueueDrained() {
		if (!PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()) {
			return false;
		}
		return PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() <= readInt("pauc.lod.drainedQueueBacklogTasks", 0, 0, 64)
			&& PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() <= readInt("pauc.lod.drainedQueuePendingChunks", 0, 0, 256)
			&& PauCEmbeddedLodRuntimeDiagnostics.backlogPressure() <= readDouble("pauc.lod.drainedQueuePressure", 0.03D, 0.0D, 0.20D);
	}

	private static boolean isPauCQueueFullyDrained() {
		return isPauCQueueDrained()
			&& PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() <= 0
			&& PauCEmbeddedLodRuntimeDiagnostics.pendingTasks() <= 0
			&& PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() <= 0;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static long readLong(String key, long fallback, long min, long max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Long.parseLong(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static final class WarmChunkRecord {
		private final String dimensionId;
		private final ChunkPos chunkPos;
		private volatile boolean live;
		private volatile boolean retained;
		private volatile boolean diskSeeded;
		private volatile int nonEmptySectionMask;
		private volatile int nonEmptySectionCount;
		private volatile int structureSectionMask;
		private volatile int fluidSectionMask;
		private volatile int surfaceSectionIndex = -1;
		private volatile int terrainMinSectionIndex = -1;
		private volatile int terrainMaxSectionIndex = -1;
		private volatile int cacheVersion = WARM_CACHE_SCHEMA_VERSION;
		private volatile long sourceFingerprint;
		private volatile int lodCacheState = LOD_CACHE_STATE_DIRTY;
		private volatile int lodQualityTier = LOD_QUALITY_UNKNOWN;
		private volatile float cudaTerrainSectionY = Float.NaN;
		private volatile long cudaPreparedAtMillis;
		private volatile String cudaProfile = "";
		private volatile int currentDistance = Integer.MAX_VALUE;
		private volatile double currentRadialDistance = Double.MAX_VALUE;
		private volatile int hotSectionCount;
		private volatile int lastScheduledMeshSections;
		private volatile long estimatedRamBytes;
		private volatile double lastPriorityScore = Double.NEGATIVE_INFINITY;
		private volatile boolean ahead;
		private volatile long lastSeenAtMillis = System.currentTimeMillis();
		private volatile long lastPlannedAtMillis;
		private volatile long lastPreparedAtMillis;
		private volatile long lastCudaAttemptAtMillis;
		private volatile long lastRenderReadyAtMillis;

		private WarmChunkRecord(String dimensionId, ChunkPos chunkPos) {
			this.dimensionId = dimensionId;
			this.chunkPos = chunkPos;
		}

		private static WarmChunkRecord placeholder(String dimensionId, ChunkPos chunkPos) {
			return new WarmChunkRecord(dimensionId, chunkPos);
		}

		private void applyMetadata(WarmChunkMetadata metadata) {
			int previousState = this.lodCacheState;
			boolean sameSource = this.sourceFingerprint != 0L && this.sourceFingerprint == metadata.sourceFingerprint();
			boolean keepPreparedData = sameSource
				&& this.lodCacheState >= LOD_CACHE_STATE_CPU_PREPARED
				&& metadata.lodCacheState() <= LOD_CACHE_STATE_METADATA_CLEAN;
			this.nonEmptySectionMask = metadata.nonEmptySectionMask();
			this.nonEmptySectionCount = metadata.nonEmptySectionCount();
			this.structureSectionMask = metadata.structureSectionMask();
			this.fluidSectionMask = metadata.fluidSectionMask();
			this.surfaceSectionIndex = metadata.surfaceSectionIndex();
			this.terrainMinSectionIndex = metadata.terrainMinSectionIndex();
			this.terrainMaxSectionIndex = metadata.terrainMaxSectionIndex();
			this.cacheVersion = Math.max(WARM_CACHE_SCHEMA_VERSION, metadata.cacheVersion());
			this.sourceFingerprint = metadata.sourceFingerprint();
			if (!keepPreparedData) {
				this.lodCacheState = normalizeCacheState(metadata.lodCacheState(), metadata.cudaTerrainSectionY());
				this.lodQualityTier = metadata.lodQualityTier();
				this.cudaTerrainSectionY = metadata.cudaTerrainSectionY();
				this.cudaPreparedAtMillis = metadata.cudaPreparedAtMillis();
				this.cudaProfile = metadata.cudaProfile();
				if (this.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
					this.lastRenderReadyAtMillis = Math.max(this.lastRenderReadyAtMillis, metadata.cudaPreparedAtMillis());
				}
			}
			this.estimatedRamBytes = PauCClientMemoryBudgetController.estimateRamBytes(metadata.nonEmptySectionCount());
			if (previousState < LOD_CACHE_STATE_RENDER_READY && this.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
				PauCLodReloadDiagnostics.onSwap();
			}
		}

		private void markRetained(double priorityScore, long now) {
			this.retained = true;
			this.lastPriorityScore = priorityScore;
			this.lastSeenAtMillis = now;
		}

		private boolean needsCudaPreparation(long now, long retryMillis) {
			if (sourceFingerprint == 0L || nonEmptySectionCount <= 0) {
				return false;
			}
			if (lodCacheState >= LOD_CACHE_STATE_CUDA_PREPARED && cudaPreparedAtMillis > 0L && Float.isFinite(cudaTerrainSectionY)) {
				return false;
			}
			return now - lastCudaAttemptAtMillis >= retryMillis;
		}

		private void applyCudaPreparation(CudaPreparedChunk prepared) {
			int previousState = this.lodCacheState;
			this.lastCudaAttemptAtMillis = prepared.preparedAtMillis();
			this.cudaTerrainSectionY = prepared.terrainSectionY();
			this.cudaProfile = prepared.cudaProfile();
			if (prepared.cudaAvailable()) {
				this.lodCacheState = Math.max(LOD_CACHE_STATE_CUDA_PREPARED, prepared.lodCacheState());
				this.lodQualityTier = Math.max(this.lodQualityTier, prepared.lodQualityTier());
				this.cudaPreparedAtMillis = prepared.preparedAtMillis();
				if (this.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
					this.lastRenderReadyAtMillis = prepared.preparedAtMillis();
				}
			} else {
				this.lodCacheState = LOD_CACHE_STATE_CPU_PREPARED;
				this.lodQualityTier = Math.max(this.lodQualityTier, LOD_QUALITY_COARSE);
				this.cudaPreparedAtMillis = 0L;
			}
			if (previousState < LOD_CACHE_STATE_RENDER_READY && this.lodCacheState >= LOD_CACHE_STATE_RENDER_READY) {
				PauCLodReloadDiagnostics.onSwap();
			}
		}

		private WarmChunkMetadata toMetadata(long savedAtMillis) {
			return new WarmChunkMetadata(
				dimensionId,
				chunkPos,
				nonEmptySectionMask,
				nonEmptySectionCount,
				structureSectionMask,
				fluidSectionMask,
				surfaceSectionIndex,
				terrainMinSectionIndex,
				terrainMaxSectionIndex,
				cacheVersion,
				sourceFingerprint,
				lodCacheState,
				lodQualityTier,
				cudaTerrainSectionY,
				cudaPreparedAtMillis,
				cudaProfile != null ? cudaProfile : "",
				savedAtMillis
			);
		}

		private CudaPrepRecord toCudaPrepRecord(int requestRadius, int fillRadius) {
			return new CudaPrepRecord(
				dimensionId,
				chunkPos,
				sourceFingerprint,
				surfaceSectionIndex,
				terrainMinSectionIndex,
				terrainMaxSectionIndex,
				currentDistance,
				currentRadialDistance,
				lastPriorityScore,
				ahead,
				requestRadius,
				fillRadius
			);
		}

		private Collection<Integer> toSectionYs(int minSectionY, int maxSectionY) {
			List<Integer> sectionYs = new ArrayList<>();
			for (int index = 0; index < 32; index++) {
				if ((nonEmptySectionMask & (1 << index)) == 0) {
					continue;
				}

				int sectionY = minSectionY + index;
				if (sectionY >= minSectionY && sectionY <= maxSectionY) {
					sectionYs.add(sectionY);
				}
			}
			return sectionYs;
		}
	}
}
