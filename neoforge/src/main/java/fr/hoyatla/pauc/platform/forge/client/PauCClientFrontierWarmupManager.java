package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCTaskPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
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
	private static final int PLAN_COMPLETION_BUDGET_PER_TICK = 6;
	private static final int PLAN_COMPLETION_BUDGET_MIN = 1;
	private static final long PLAN_RESCHEDULE_COOLDOWN_MS = 300L;
	private static final long STALE_RECORD_TTL_MS = 120_000L;
	private static final ConcurrentMap<Long, WarmChunkRecord> TRACKED_CHUNKS = new ConcurrentHashMap<>();
	private static final Set<Long> PENDING_PLANS = ConcurrentHashMap.newKeySet();
	private static final ConcurrentLinkedDeque<PreparedWarmPlan> COMPLETED_PLANS = new ConcurrentLinkedDeque<>();
	private static final AtomicInteger SESSION_GENERATION = new AtomicInteger();
	@Nullable
	private static volatile Field clientChunkStorageField;
	@Nullable
	private static volatile Field clientChunkStorageChunksField;
	private static volatile boolean storageReflectionResolved;
	private static volatile boolean storageReflectionFailureLogged;
	@Nullable
	private static volatile String lastKnownDimension;
	private static volatile PauCClientMemoryBudgetController.BudgetSnapshot lastBudget = PauCClientMemoryBudgetController.capture(12, 3);
	private static volatile long lastKnownRetainedRamBytes;
	private static volatile int lastKnownRetainedChunks;
	private static volatile int lastKnownHotMeshSections;
	private static volatile int lastKnownQueuedMeshSections;
	private static volatile int zeroVisibleChunkStreak;
	private static volatile boolean lastSnapMode;
	private static volatile boolean lastFastTravel;

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
		if (PauCorRendererBridge.isAvailable()) {
			PauCClientWarmChunkDiskCache.persist(level, metadata);
		}
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

		lastBudget = PauCClientMemoryBudgetController.capture(frame.renderDistanceChunks(), PauCClientChunkRetentionManager.getRetentionMarginChunks());
		PauCorRendererBridge.RendererStats rendererStats = PauCorRendererBridge.getStats(level);
		if (!rendererStats.available()) {
			PauCCompatManager.logActionOnce(
				PauCCompatModule.CLIENT_FRONTIER_WARMUP_PIPELINE,
				"paucor-unavailable-passive",
				"PauC keeps client frontier warmup in passive mode while the PauCor renderer bridge is unavailable."
			);
			lastKnownQueuedMeshSections = 0;
			lastKnownHotMeshSections = 0;
			zeroVisibleChunkStreak = 0;
			PENDING_PLANS.clear();
			COMPLETED_PLANS.clear();
			PauCClientUploadBudgetController.reset();
			PauCClientRenderPrep.reset();
			pruneTrackedRecords();
			return;
		}

		seedTrackedChunksFromLiveStorage(level);
		refreshPriorities(frame, retainedChunks);
		refreshHotMeshCounts(level, rendererStats);
		PauCClientUploadBudgetController.onClientTick(
			Minecraft.getInstance(),
			rendererStats,
			lastBudget,
			frame.fastTravel() || frame.snapMode()
		);
		PauCClientRenderPrep.onClientTick(level, frame, lastBudget, rendererStats);

		runVisualRecovery(level, frame, rendererStats);
		lastSnapMode = frame.snapMode();
		lastFastTravel = frame.fastTravel();
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
			boolean protectFastAhead = lastFastTravel && record.ahead && record.currentDistance <= PauCClientChunkRetentionManager.getRetentionRadiusChunks();
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
			return candidates.stream()
				.filter(RetentionCandidate::stale)
				.sorted(Comparator.comparingDouble(RetentionCandidate::score).thenComparingLong(RetentionCandidate::retainedAtMillis))
				.limit(8)
				.map(RetentionCandidate::chunkKey)
				.toList();
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
		resetState();
	}

	public static void onClientLogoutStarted() {
		resetState();
	}

	public static void onClientLevelUnload() {
		resetState();
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
			+ ", dimension="
			+ (lastKnownDimension != null ? lastKnownDimension : "-")
			+ ", "
			+ lastBudget.describe()
			+ ", "
			+ PauCorRendererBridge.getStats(Minecraft.getInstance().level).describe()
			+ ", "
			+ PauCClientUploadBudgetController.describeState()
			+ ", "
			+ PauCClientRenderPrep.describeState()
			+ "]";
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
		if (grantedBudget <= 0) {
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
	}

	private static void drainCompletedPlans(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int queuedMeshBudget = Math.max(0, lastBudget.maxQueuedMeshSections() - lastKnownQueuedMeshSections);
		int hotMeshBudget = Math.max(0, lastBudget.maxHotMeshSections() - lastKnownHotMeshSections);
		int vramMeshBudget = Math.max(0, lastBudget.maxVramMeshSections() - lastKnownHotMeshSections);
		int baseSectionBudget = Math.min(queuedMeshBudget, Math.min(hotMeshBudget, vramMeshBudget));
		baseSectionBudget = Math.max(
			PLAN_COMPLETION_BUDGET_MIN,
			Math.min(
				baseSectionBudget,
				frame.snapMode() ? PLAN_COMPLETION_BUDGET_PER_TICK * 3 : PLAN_COMPLETION_BUDGET_PER_TICK * 2
			)
		);
		baseSectionBudget = scaleWarmupBudget(baseSectionBudget, PauCClientFpsGovernor.warmupAggressionScale(), PLAN_COMPLETION_BUDGET_MIN);
		baseSectionBudget = PauCClientRenderPrep.limitWarmupSectionBudget(baseSectionBudget, frame.snapMode());
		int sectionBudget = PauCClientUploadBudgetController.acquireSectionBudget(baseSectionBudget, frame.snapMode());
		if (sectionBudget <= 0) {
			return;
		}

		int completionBudget = scaleWarmupBudget(
			frame.snapMode() ? PLAN_COMPLETION_BUDGET_PER_TICK * 2 : PLAN_COMPLETION_BUDGET_PER_TICK,
			PauCClientFpsGovernor.warmupAggressionScale(),
			PLAN_COMPLETION_BUDGET_MIN
		);
		for (int i = 0; i < completionBudget && sectionBudget > 0; i++) {
			PreparedWarmPlan plan = COMPLETED_PLANS.pollFirst();
			if (plan == null) {
				return;
			}

			if (plan.sessionGeneration() != SESSION_GENERATION.get()) {
				continue;
			}

			int scheduledSections = PauCorRendererBridge.applyWarmPlan(level, plan, sectionBudget);
			sectionBudget -= scheduledSections;
			WarmChunkRecord record = TRACKED_CHUNKS.get(plan.chunkPos().toLong());
			if (record != null) {
				record.lastPreparedAtMillis = System.currentTimeMillis();
				record.lastScheduledMeshSections = scheduledSections;
			}
		}
	}

	private static void schedulePlans(ClientLevel level, PauCClientChunkPriorityScorer.PriorityFrame frame) {
		int planSlots = Math.max(0, lastBudget.maxPendingPlans() - PENDING_PLANS.size() - COMPLETED_PLANS.size());
		if (planSlots <= 0) {
			return;
		}
		if (frame.snapMode()) {
			planSlots = Math.max(2, (planSlots * 2) / 3);
		}
		planSlots = scaleWarmupBudget(planSlots, PauCClientFpsGovernor.warmupAggressionScale(), 1);

		long now = System.currentTimeMillis();
		double minScore = frame.snapMode() ? 0.34D : (frame.fastTravel() ? 0.40D : 0.45D);
		if (PauCClientFpsGovernor.isUnderPressure()) {
			minScore += 0.12D;
		} else if (PauCClientFpsGovernor.warmupAggressionScale() > 1.0D) {
			minScore = Math.max(0.30D, minScore - 0.05D);
		}
		double adjustedMinScore = minScore;
		List<WarmChunkRecord> candidates = TRACKED_CHUNKS.values().stream()
			.filter(record -> record.dimensionId.equals(level.dimension().location().toString()))
			.filter(record -> record.nonEmptySectionCount > 0)
			.filter(record -> record.currentDistance <= frame.warmRadiusChunks())
			.filter(record -> record.lastPriorityScore >= adjustedMinScore)
			.filter(record -> record.live || record.retained)
			.filter(record -> !PENDING_PLANS.contains(record.chunkPos.toLong()))
			.filter(record -> now - record.lastPlannedAtMillis >= PLAN_RESCHEDULE_COOLDOWN_MS)
			.sorted(Comparator.comparingDouble((WarmChunkRecord record) -> record.lastPriorityScore).reversed()
				.thenComparingDouble(record -> record.currentRadialDistance)
				.thenComparingInt(record -> record.currentDistance))
			.limit(planSlots)
			.toList();

		for (WarmChunkRecord candidate : candidates) {
			schedulePlan(level, frame, candidate);
		}
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
			record.lastPriorityScore,
			frame.playerSectionY(),
			frame.minSectionY(),
			frame.maxSectionY(),
			sessionRoot
		);
		PauCTaskPriority priority = record.lastPriorityScore >= 0.75D ? PauCTaskPriority.FOV : PauCTaskPriority.ACTIVE;
		String description = "client warm " + record.chunkPos + " " + record.dimensionId;
		CompletableFuture<PreparedWarmPlan> future = PauCScheduler.submitClientPrepare(priority, description, () -> preparePlan(workItem));
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
		if (sectionMask == 0 || nonEmptySections <= 0) {
			WarmChunkMetadata diskMetadata = PauCClientWarmChunkDiskCache.read(workItem.sessionRoot(), workItem.dimensionId(), workItem.chunkPos());
			if (diskMetadata != null) {
				sectionMask = diskMetadata.nonEmptySectionMask();
				nonEmptySections = diskMetadata.nonEmptySectionCount();
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
		orderedSections.sort(Comparator.comparingInt(sectionY -> Math.abs(sectionY - playerSectionY)));
		return new PreparedWarmPlan(
			workItem.sessionGeneration(),
			workItem.dimensionId(),
			workItem.chunkPos(),
			orderedSections,
			workItem.priorityScore(),
			PauCClientMemoryBudgetController.estimateRamBytes(nonEmptySections),
			PauCClientMemoryBudgetController.estimateGpuSectionCost(nonEmptySections)
		);
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
			TRACKED_CHUNKS.entrySet().removeIf(entry -> !entry.getValue().live && !entry.getValue().retained && now - entry.getValue().lastSeenAtMillis > STALE_RECORD_TTL_MS);
			return;
		}

		List<Map.Entry<Long, WarmChunkRecord>> candidates = TRACKED_CHUNKS.entrySet().stream()
			.filter(entry -> !entry.getValue().live && !entry.getValue().retained)
			.sorted(Comparator
				.comparingDouble((Map.Entry<Long, WarmChunkRecord> entry) -> entry.getValue().lastPriorityScore)
				.thenComparingLong(entry -> entry.getValue().lastSeenAtMillis))
			.toList();

		int toRemove = TRACKED_CHUNKS.size() - lastBudget.maxTrackedChunks();
		for (int i = 0; i < toRemove && i < candidates.size(); i++) {
			TRACKED_CHUNKS.remove(candidates.get(i).getKey());
		}
	}

	private static void resetState() {
		SESSION_GENERATION.incrementAndGet();
		PauCClientChunkPriorityScorer.resetRuntimeState();
		TRACKED_CHUNKS.clear();
		PENDING_PLANS.clear();
		COMPLETED_PLANS.clear();
		PauCClientRenderPrep.reset();
		PauCClientUploadBudgetController.reset();
		lastKnownDimension = null;
		lastKnownRetainedRamBytes = 0L;
		lastKnownRetainedChunks = 0;
		lastKnownHotMeshSections = 0;
		lastKnownQueuedMeshSections = 0;
		zeroVisibleChunkStreak = 0;
		lastSnapMode = false;
		lastFastTravel = false;
	}

	public record WarmChunkMetadata(
		String dimensionId,
		ChunkPos chunkPos,
		int nonEmptySectionMask,
		int nonEmptySectionCount,
		long savedAtMillis
	) {
		public static WarmChunkMetadata capture(ClientLevel level, LevelChunk chunk) {
			LevelChunkSection[] sections = chunk.getSections();
			int sectionMask = 0;
			int nonEmptySections = 0;
			for (int index = 0; index < sections.length && index < 32; index++) {
				LevelChunkSection section = sections[index];
				if (section != null && !section.hasOnlyAir()) {
					sectionMask |= 1 << index;
					nonEmptySections++;
				}
			}
			return new WarmChunkMetadata(
				level.dimension().location().toString(),
				chunk.getPos(),
				sectionMask,
				nonEmptySections,
				System.currentTimeMillis()
			);
		}
	}

	public record PreparedWarmPlan(
		int sessionGeneration,
		String dimensionId,
		ChunkPos chunkPos,
		List<Integer> sectionYs,
		double priorityScore,
		long estimatedRamBytes,
		int estimatedGpuSections
	) {
	}

	private record WarmChunkWorkItem(
		int sessionGeneration,
		String dimensionId,
		ChunkPos chunkPos,
		int nonEmptySectionMask,
		int nonEmptySectionCount,
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

	private static final class WarmChunkRecord {
		private final String dimensionId;
		private final ChunkPos chunkPos;
		private volatile boolean live;
		private volatile boolean retained;
		private volatile int nonEmptySectionMask;
		private volatile int nonEmptySectionCount;
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

		private WarmChunkRecord(String dimensionId, ChunkPos chunkPos) {
			this.dimensionId = dimensionId;
			this.chunkPos = chunkPos;
		}

		private static WarmChunkRecord placeholder(String dimensionId, ChunkPos chunkPos) {
			return new WarmChunkRecord(dimensionId, chunkPos);
		}

		private void applyMetadata(WarmChunkMetadata metadata) {
			this.nonEmptySectionMask = metadata.nonEmptySectionMask();
			this.nonEmptySectionCount = metadata.nonEmptySectionCount();
			this.estimatedRamBytes = PauCClientMemoryBudgetController.estimateRamBytes(metadata.nonEmptySectionCount());
		}

		private void markRetained(double priorityScore, long now) {
			this.retained = true;
			this.lastPriorityScore = priorityScore;
			this.lastSeenAtMillis = now;
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
