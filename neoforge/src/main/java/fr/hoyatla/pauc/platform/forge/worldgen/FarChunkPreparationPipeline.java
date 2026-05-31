package fr.hoyatla.pauc.platform.forge.worldgen;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhase;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhaseBudgetController;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCTaskPriority;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class FarChunkPreparationPipeline {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int COMPLETION_BUDGET_PER_TICK = 4;
	private static final int COMPLETION_BUDGET_MIN = 1;
	private static final Map<String, PipelineState> STATES = new ConcurrentHashMap<>();

	private FarChunkPreparationPipeline() {
	}

	public static void request(ServerLevel level, ChunkPos chunkPos, PreparedChunkSidecarStorage storage) {
		List<FarChunkPreparationPlanner> planners = FarChunkPreparationRegistry.planners();
		if (planners.isEmpty()) {
			return;
		}

		PipelineState pipelineState = stateFor(level);
		PreparedChunkPlannerSavedData plannerState = PreparedChunkPlannerSavedData.get(level);

		for (FarChunkPreparationPlanner planner : planners) {
			FarChunkPreparationContext context = FarChunkPreparationContext.around(level, chunkPos, level.getSeed(), planner.preparationRadiusChunks());
			long preparationKey = planner.preparationKey(context);
			PlannerWindowKey windowKey = new PlannerWindowKey(planner.id(), preparationKey);

			if (plannerState.isPlannerPrepared(planner.id(), preparationKey) || !pipelineState.pending.add(windowKey)) {
				continue;
			}

			if (planner instanceof AsyncFarChunkPreparationPlanner<?> asyncPlanner) {
				scheduleAsyncPlanner(level, context, storage, pipelineState, plannerState, windowKey, asyncPlanner);
			} else {
				runSyncPlanner(level, context, storage, pipelineState, plannerState, windowKey, planner);
			}
		}
	}

	public static void tick(ServerLevel level) {
		PipelineState state = STATES.get(levelKey(level));
		if (state == null) {
			return;
		}

		PreparedChunkPlannerSavedData plannerState = PreparedChunkPlannerSavedData.get(level);
		PreparedChunkSidecarStorage storage = PreparedChunkSidecarStorage.get(level);
		int completionBudget = PauCServerPhaseBudgetController.scaledBudget(
			level.getServer(),
			PauCServerPhase.CHUNK_POST_LOAD,
			COMPLETION_BUDGET_PER_TICK,
			COMPLETION_BUDGET_MIN
		);

		for (int i = 0; i < completionBudget; i++) {
			PreparedBatch batch = state.completed.pollFirst();
			if (batch == null) {
				return;
			}

			if (!plannerState.isPlannerPrepared(batch.plannerId(), batch.preparationKey())) {
				if (!batch.placements().isEmpty()) {
					storage.enqueueAll(level, batch.placements());
				}
				plannerState.markPlannerPrepared(batch.plannerId(), batch.preparationKey());
			}
		}
	}

	public static void shutdownLevel(ServerLevel level) {
		PipelineState removed = STATES.remove(levelKey(level));
		if (removed == null || removed.pending.isEmpty() && removed.completed.isEmpty()) {
			return;
		}

		LOGGER.debug(
			"PauC dropped {} in-flight and {} completed async preparation batch(es) for {} during shutdown.",
			removed.pending.size(),
			removed.completed.size(),
			level.dimension().location()
		);
	}

	public static String describeState(ServerLevel level) {
		PipelineState state = STATES.get(levelKey(level));
		if (state == null) {
			return "asyncPrep[pending=0, completed=0]";
		}

		return "asyncPrep[pending=" + state.pending.size() + ", completed=" + state.completed.size() + "]";
	}

	private static void runSyncPlanner(
		ServerLevel level,
		FarChunkPreparationContext context,
		PreparedChunkSidecarStorage storage,
		PipelineState pipelineState,
		PreparedChunkPlannerSavedData plannerState,
		PlannerWindowKey windowKey,
		FarChunkPreparationPlanner planner
	) {
		try {
			Collection<PendingChunkPlacement> placements = planner.prepare(context);
			if (placements != null && !placements.isEmpty()) {
				storage.enqueueAll(level, placements);
			}
			plannerState.markPlannerPrepared(planner.id(), windowKey.preparationKey());
		} catch (RuntimeException exception) {
			LOGGER.warn(
				"PauC planner {} failed while preparing deferred placements for window anchored at {} in {}.",
				planner.id(),
				context.windowAnchorChunkPos(),
				level.dimension().location(),
				exception
			);
		} finally {
			pipelineState.pending.remove(windowKey);
		}
	}

	@SuppressWarnings("unchecked")
	private static <S> void scheduleAsyncPlanner(
		ServerLevel level,
		FarChunkPreparationContext context,
		PreparedChunkSidecarStorage storage,
		PipelineState pipelineState,
		PreparedChunkPlannerSavedData plannerState,
		PlannerWindowKey windowKey,
		AsyncFarChunkPreparationPlanner<?> asyncPlanner
	) {
		AsyncFarChunkPreparationPlanner<S> planner = (AsyncFarChunkPreparationPlanner<S>) asyncPlanner;
		S snapshot;

		try {
			snapshot = planner.capturePreparationSnapshot(context);
		} catch (RuntimeException exception) {
			pipelineState.pending.remove(windowKey);
			LOGGER.warn(
				"PauC planner {} failed while capturing an async preparation snapshot for window anchored at {} in {}.",
				planner.id(),
				context.windowAnchorChunkPos(),
				level.dimension().location(),
				exception
			);
			return;
		}

		if (snapshot == null) {
			pipelineState.pending.remove(windowKey);
			return;
		}

		String description = "planner " + planner.id() + " " + level.dimension().location() + " window " + context.windowAnchorChunkPos();
		CompletableFuture<Collection<PendingChunkPlacement>> future = PauCScheduler.submitServerPrepare(
			PauCTaskPriority.ACTIVE,
			description,
			() -> {
				Collection<PendingChunkPlacement> placements = planner.prepareAsync(snapshot);
				return placements != null ? placements : List.of();
			}
		);

		future.whenComplete((placements, throwable) -> {
			pipelineState.pending.remove(windowKey);

			if (throwable != null) {
				LOGGER.warn(
					"PauC planner {} failed while computing async deferred placements for window anchored at {} in {}.",
					planner.id(),
					context.windowAnchorChunkPos(),
					level.dimension().location(),
					throwable
				);
				return;
			}

			pipelineState.completed.addLast(
				new PreparedBatch(
					planner.id(),
					windowKey.preparationKey(),
					new ArrayList<>(Objects.requireNonNullElse(placements, List.of()))
				)
			);
		});
	}

	private static PipelineState stateFor(ServerLevel level) {
		return STATES.computeIfAbsent(levelKey(level), ignored -> new PipelineState());
	}

	private static String levelKey(ServerLevel level) {
		return level.dimension().location().toString() + "@" + level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
	}

	private record PlannerWindowKey(ResourceLocation plannerId, long preparationKey) {
	}

	private record PreparedBatch(ResourceLocation plannerId, long preparationKey, List<PendingChunkPlacement> placements) {
	}

	private static final class PipelineState {
		private final Set<PlannerWindowKey> pending = ConcurrentHashMap.newKeySet();
		private final ConcurrentLinkedDeque<PreparedBatch> completed = new ConcurrentLinkedDeque<>();
	}
}
