package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCTaskPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PauCClientRenderPrep {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_PENDING_FRAMES = 2;
	private static final int MAX_READY_PER_STAGE = 4;
	private static final int MAX_STAGE_DRAIN_PER_EVENT = 2;
	private static final long FRAME_RESCHEDULE_COOLDOWN_MS = 33L;
	private static final AtomicInteger SESSION_GENERATION = new AtomicInteger();
	private static final ConcurrentMap<Long, Boolean> PENDING_FRAMES = new ConcurrentHashMap<>();
	private static final Map<RenderPrepStage, ConcurrentLinkedDeque<PreparedStage>> READY_STAGES = new EnumMap<>(RenderPrepStage.class);
	private static volatile long nextFrameId;
	private static volatile long lastSubmittedAtMillis;
	private static volatile PreparedFrame lastPreparedFrame = PreparedFrame.unavailable();
	private static volatile PreparedFrame lastAppliedFrame = PreparedFrame.unavailable();
	private static volatile int submittedFrames;
	private static volatile int rejectedFrames;
	private static volatile int failedFrames;
	private static volatile int appliedStages;

	static {
		for (RenderPrepStage stage : RenderPrepStage.values()) {
			READY_STAGES.put(stage, new ConcurrentLinkedDeque<>());
		}
	}

	private PauCClientRenderPrep() {
	}

	public static void onClientTick(
		ClientLevel level,
		PauCClientChunkPriorityScorer.PriorityFrame priorityFrame,
		PauCClientMemoryBudgetController.BudgetSnapshot budgetSnapshot,
		PauCorRendererBridge.RendererStats rendererStats
	) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_RENDER_PREP_PIPELINE)) {
			return;
		}
		if (level == null || priorityFrame == null || budgetSnapshot == null || rendererStats == null) {
			return;
		}
		if (PENDING_FRAMES.size() >= MAX_PENDING_FRAMES || readyStageCount() >= MAX_READY_PER_STAGE * RenderPrepStage.values().length) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastSubmittedAtMillis < FRAME_RESCHEDULE_COOLDOWN_MS) {
			return;
		}
		lastSubmittedAtMillis = now;

		long frameId = ++nextFrameId;
		PENDING_FRAMES.put(frameId, Boolean.TRUE);
		RenderPrepSnapshot snapshot = new RenderPrepSnapshot(
			SESSION_GENERATION.get(),
			frameId,
			level.dimension().location().toString(),
			PauCClientTargetFps.effectiveTargetFps(Minecraft.getInstance()),
			priorityFrame.renderDistanceChunks(),
			priorityFrame.warmRadiusChunks(),
			priorityFrame.snapMode(),
			priorityFrame.fastTravel(),
			priorityFrame.movementCatchup(),
			budgetSnapshot.maxQueuedMeshSections(),
			budgetSnapshot.maxHotMeshSections(),
			budgetSnapshot.maxVramMeshSections(),
			rendererStats.builderAvailable(),
			rendererStats.scheduledJobs(),
			rendererStats.scheduledEffort(),
			rendererStats.busyThreads(),
			rendererStats.totalThreads(),
			rendererStats.visibleChunkCount(),
			rendererStats.meshReady(),
			rendererStats.meshActive(),
			rendererStats.residentMeshSections(),
			PauCClientGpuPathController.getLastSnapshot().renderPath().id()
		);

		PauCTaskPriority priority = priorityFrame.snapMode() || priorityFrame.fastTravel() || priorityFrame.movementCatchup()
			? PauCTaskPriority.FOV
			: PauCTaskPriority.ACTIVE;
		CompletableFuture<PreparedFrame> future = PauCScheduler.submitClientPrepare(
			priority,
			"client render prep " + snapshot.dimensionId() + "#" + frameId,
			() -> prepare(snapshot)
		);
		submittedFrames++;
		future.whenComplete((preparedFrame, throwable) -> {
			PENDING_FRAMES.remove(frameId);
			if (throwable != null) {
				failedFrames++;
				LOGGER.debug("PauC client render prep failed for frame {} in {}.", frameId, snapshot.dimensionId(), throwable);
				return;
			}
			if (preparedFrame == null || preparedFrame.sessionGeneration() != SESSION_GENERATION.get()) {
				return;
			}

			lastPreparedFrame = preparedFrame;
			for (PreparedStage preparedStage : preparedFrame.stages()) {
				ConcurrentLinkedDeque<PreparedStage> queue = READY_STAGES.get(preparedStage.stage());
				if (queue == null) {
					continue;
				}
				while (queue.size() >= MAX_READY_PER_STAGE) {
					queue.pollFirst();
				}
				queue.addLast(preparedStage);
			}
		});
		if (future.isCompletedExceptionally()) {
			PENDING_FRAMES.remove(frameId);
			rejectedFrames++;
		}
	}

	public static int limitWarmupSectionBudget(int requestedSections, boolean snapMode) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_RENDER_PREP_PIPELINE) || requestedSections <= 0) {
			return Math.max(0, requestedSections);
		}

		PreparedFrame preparedFrame = lastPreparedFrame;
		if (!preparedFrame.available()) {
			return requestedSections;
		}

		int hint = preparedFrame.warmupSectionHint();
		if (hint <= 0) {
			return Math.min(requestedSections, snapMode ? 2 : 1);
		}
		if (PauCClientFpsGovernor.isBacklogResolved() && !PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage()) {
			hint = Math.min(requestedSections, hint + (snapMode ? 4 : 2));
		}
		if (snapMode) {
			hint = Math.max(hint, Math.min(requestedSections, hint + 2));
		}
		return Math.min(requestedSections, Math.max(1, (int) Math.floor(hint * PauCLodShaderRuntime.uploadBudgetScale())));
	}

	public static void onRenderStage(RenderLevelStageEvent.Stage stage) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_RENDER_PREP_PIPELINE)) {
			return;
		}

		RenderPrepStage prepStage = RenderPrepStage.fromForgeStage(stage);
		if (prepStage == null) {
			return;
		}

		ConcurrentLinkedDeque<PreparedStage> queue = READY_STAGES.get(prepStage);
		if (queue == null) {
			return;
		}

		for (int drained = 0; drained < MAX_STAGE_DRAIN_PER_EVENT; drained++) {
			PreparedStage preparedStage = queue.pollFirst();
			if (preparedStage == null) {
				return;
			}
			if (preparedStage.sessionGeneration() != SESSION_GENERATION.get()) {
				continue;
			}

			lastAppliedFrame = preparedStage.frame();
			appliedStages++;
		}
	}

	public static void reset() {
		SESSION_GENERATION.incrementAndGet();
		PENDING_FRAMES.clear();
		for (ConcurrentLinkedDeque<PreparedStage> queue : READY_STAGES.values()) {
			queue.clear();
		}
		lastPreparedFrame = PreparedFrame.unavailable();
		lastAppliedFrame = PreparedFrame.unavailable();
		lastSubmittedAtMillis = 0L;
	}

	public static String describeState() {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_RENDER_PREP_PIPELINE)) {
			return "renderPrep[disabled]";
		}

		return "renderPrep[pending="
			+ PENDING_FRAMES.size()
			+ ", ready="
			+ readyStageCount()
			+ ", submitted="
			+ submittedFrames
			+ ", rejected="
			+ rejectedFrames
			+ ", failed="
			+ failedFrames
			+ ", applied="
			+ appliedStages
			+ ", hint="
			+ lastPreparedFrame.warmupSectionHint()
			+ ", last="
			+ (lastAppliedFrame.available() ? lastAppliedFrame.dimensionId() + "#" + lastAppliedFrame.frameId() : "-")
			+ "]";
	}

	private static PreparedFrame prepare(RenderPrepSnapshot snapshot) {
		int backlog = snapshot.builderAvailable() ? Math.max(0, snapshot.scheduledJobs() - Math.max(2, snapshot.totalThreads() * 2)) : 0;
		int busyThreads = snapshot.totalThreads() > 0 ? snapshot.busyThreads() : 0;
		double busyPressure = snapshot.totalThreads() > 0 ? (double) busyThreads / (double) snapshot.totalThreads() : 0.0D;
		boolean backlogResolved = PauCClientFpsGovernor.isBacklogResolved();
		double fastScale = snapshot.snapMode() ? 1.55D : (snapshot.movementCatchup() ? 1.45D : (snapshot.fastTravel() ? 1.30D : 1.0D));
		double backlogScale = Math.max(0.35D, 1.0D - Math.min(0.65D, backlog * 0.06D));
		if (backlogResolved && backlog <= 0) {
			backlogScale = Math.max(backlogScale, 1.18D);
		}
		double busyScale = Math.max(0.45D, 1.0D - Math.min(0.55D, busyPressure * 0.45D));
		int meshHeadroom = Math.max(0, Math.min(snapshot.maxQueuedMeshSections(), Math.min(snapshot.maxHotMeshSections(), snapshot.maxVramMeshSections())) - snapshot.scheduledJobs());
		boolean acceleratedWarmup = snapshot.snapMode() || snapshot.movementCatchup();
		int warmupHint = clamp((int) Math.floor((meshHeadroom / 10.0D) * fastScale * backlogScale * busyScale * PauCLodShaderRuntime.uploadBudgetScale()), acceleratedWarmup ? 3 : 1, snapshot.snapMode() ? 28 : (snapshot.movementCatchup() ? 24 : 16));
		if (backlogResolved && meshHeadroom > 0) {
			warmupHint = clamp(warmupHint + (snapshot.snapMode() ? 4 : (snapshot.movementCatchup() ? 3 : 2)), acceleratedWarmup ? 3 : 1, snapshot.snapMode() ? 28 : (snapshot.movementCatchup() ? 24 : 16));
		}
		if (PauCClientChunkPriorityScorer.isFpsFirstVanillaMode(snapshot.targetFps())) {
			double fpsFirstScale = acceleratedWarmup ? 0.82D : 0.68D;
			warmupHint = clamp((int) Math.floor(warmupHint * fpsFirstScale), acceleratedWarmup ? 2 : 1, snapshot.snapMode() ? 18 : 10);
		}
		if (!snapshot.builderAvailable()) {
			warmupHint = Math.min(warmupHint, 2);
		}

		List<PreparedStage> stages = new ArrayList<>(RenderPrepStage.values().length);
		PreparedFrame frame = new PreparedFrame(
			true,
			snapshot.sessionGeneration(),
			snapshot.frameId(),
			snapshot.dimensionId(),
			warmupHint,
			snapshot.renderPath(),
			stages
		);
		stages.add(new PreparedStage(frame, RenderPrepStage.SKY, 0, "gpu=" + snapshot.renderPath()));
		stages.add(new PreparedStage(frame, RenderPrepStage.SOLID, warmupHint, "meshHeadroom=" + meshHeadroom + ", meshActive=" + snapshot.meshActive()));
		stages.add(new PreparedStage(frame, RenderPrepStage.TRANSLUCENT, Math.max(1, warmupHint / 3), "visible=" + snapshot.visibleChunkCount()));
		return frame;
	}

	private static int readyStageCount() {
		int count = 0;
		for (ConcurrentLinkedDeque<PreparedStage> queue : READY_STAGES.values()) {
			count += queue.size();
		}
		return count;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private enum RenderPrepStage {
		SKY,
		SOLID,
		TRANSLUCENT;

		private static RenderPrepStage fromForgeStage(RenderLevelStageEvent.Stage stage) {
			if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
				return SKY;
			}
			if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
				return SOLID;
			}
			if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
				return TRANSLUCENT;
			}
			return null;
		}
	}

	private record RenderPrepSnapshot(
		int sessionGeneration,
		long frameId,
		String dimensionId,
		int targetFps,
		int renderDistanceChunks,
		int warmRadiusChunks,
		boolean snapMode,
		boolean fastTravel,
		boolean movementCatchup,
		int maxQueuedMeshSections,
		int maxHotMeshSections,
		int maxVramMeshSections,
		boolean builderAvailable,
		int scheduledJobs,
		int scheduledEffort,
		int busyThreads,
		int totalThreads,
		int visibleChunkCount,
		boolean meshReady,
		boolean meshActive,
		int residentMeshSections,
		String renderPath
	) {
	}

	private record PreparedFrame(
		boolean available,
		int sessionGeneration,
		long frameId,
		String dimensionId,
		int warmupSectionHint,
		String renderPath,
		List<PreparedStage> stages
	) {
		private static PreparedFrame unavailable() {
			return new PreparedFrame(false, -1, -1L, "-", 0, "unknown", List.of());
		}
	}

	private record PreparedStage(
		PreparedFrame frame,
		RenderPrepStage stage,
		int uploadBudgetHint,
		String note
	) {
		private int sessionGeneration() {
			return frame.sessionGeneration();
		}
	}
}
