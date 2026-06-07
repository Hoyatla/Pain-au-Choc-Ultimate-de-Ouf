package fr.hoyatla.pauc.platform.forge.worldgen;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import fr.hoyatla.pauc.platform.forge.compat.PauCClientRenderShutdownGuard;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhase;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhaseBudgetController;
import fr.hoyatla.pauc.platform.forge.runtime.PauCStallGovernor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Collection;

public final class FarChunkPlacementBroker {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int DIRTY_FLUSH_BUDGET_PER_TICK = 32;
	private static final int DIRTY_FLUSH_BUDGET_MIN = 4;
	private static final int MATERIALIZATION_BUDGET_PER_TICK = 8;
	private static final int MATERIALIZATION_BUDGET_MIN = 1;
	private static final int FORCED_MATERIALIZATION_BUDGET_PER_TICK = 1;
	private static final int FORCED_MATERIALIZATION_BUDGET_MIN = 1;
	private static final int SHUTDOWN_DIRTY_SPILL_BUDGET = 8;
	private static final int SHUTDOWN_DIRTY_SPILL_BUDGET_MIN = 2;

	private FarChunkPlacementBroker() {
	}

	public static SubmissionResult submitWorldGenPlacement(
		ServerLevel level,
		ChunkStatus generatingStatus,
		@Nullable String generationHint,
		BlockPosSnapshot snapshot
	) {
		if (shouldSuspendWorldMutation(level)) {
			return SubmissionResult.HANDLED_TRUE;
		}
		if (level.isOutsideBuildHeight(snapshot.pos())) {
			return SubmissionResult.HANDLED_FALSE;
		}

		FarChunkPlacementSource source = FarChunkPlacementSource.capture(generationHint);
		if (!PauCStallGovernor.allow(level, PauCServerPhase.WORLDGEN_APPLY, source.packageName())) {
			return SubmissionResult.HANDLED_TRUE;
		}

		FarChunkPlacementMode mode = FarChunkPlacementPolicyMatrix.resolve(source, snapshot.state(), snapshot.flags());
		PendingChunkPlacement placement = new PendingChunkPlacement(
			snapshot.pos(),
			snapshot.state(),
			snapshot.flags(),
			snapshot.recursionDepth(),
			level.getGameTime(),
			mode,
			source.className(),
			source.packageName(),
			describeGenerationContext(generatingStatus, generationHint),
			null
		);

		PreparedChunkSidecarStorage.get(level).enqueue(level, placement);
		return mode.shouldReportSuccess() ? SubmissionResult.HANDLED_TRUE : SubmissionResult.HANDLED_FALSE;
	}

	public static int flushChunk(ServerLevel level, ChunkPos chunkPos) {
		if (shouldSuspendWorldMutation(level)) {
			return 0;
		}

		PreparedChunkSidecarStorage storage = PreparedChunkSidecarStorage.get(level);
		runPlanners(level, chunkPos, storage);
		int appliedPlacements = storage.materializeChunk(level, chunkPos, FarChunkPlacementBroker::applyNow);

		if (appliedPlacements > 0) {
			LOGGER.debug("PauC applied {} deferred far-chunk placements in {} for chunk {}.", appliedPlacements, level.dimension().location(), chunkPos);
		}

		return appliedPlacements;
	}

	public static void tick(ServerLevel level) {
		if (shouldSuspendWorldMutation(level)) {
			FarChunkPreparationPipeline.shutdownLevel(level);
			return;
		}

		PreparedChunkSidecarStorage storage = PreparedChunkSidecarStorage.get(level);
		int dirtyBudget = PauCServerPhaseBudgetController.scaledBudget(
			level.getServer(),
			PauCServerPhase.SAVE_FLUSH,
			DIRTY_FLUSH_BUDGET_PER_TICK,
			DIRTY_FLUSH_BUDGET_MIN
		);
		int applyBudget = PauCServerPhaseBudgetController.scaledBudget(
			level.getServer(),
			PauCServerPhase.WORLDGEN_APPLY,
			MATERIALIZATION_BUDGET_PER_TICK,
			MATERIALIZATION_BUDGET_MIN
		);
		int forceBudget = PauCServerPhaseBudgetController.scaledBudget(
			level.getServer(),
			PauCServerPhase.WORLDGEN_FORCE_LOAD,
			FORCED_MATERIALIZATION_BUDGET_PER_TICK,
			FORCED_MATERIALIZATION_BUDGET_MIN
		);
		FarChunkPreparationPipeline.tick(level);
		storage.flushDirty(level, dirtyBudget);
		storage.processLoadedMaterializations(level, applyBudget, FarChunkPlacementBroker::applyNow);
		storage.processForcedMaterializations(level, forceBudget, FarChunkPlacementBroker::applyNow);
	}

	public static void flushAll(ServerLevel level) {
		PreparedChunkSidecarStorage.get(level).flushAll(level);
	}

	public static void shutdownLevel(ServerLevel level) {
		FarChunkPreparationPipeline.shutdownLevel(level);
		PreparedChunkSidecarStorage storage = PreparedChunkSidecarStorage.peek(level);
		if (storage == null) {
			return;
		}

		int shutdownBudget = PauCServerPhaseBudgetController.scaledBudget(
			level.getServer(),
			PauCServerPhase.SAVE_FLUSH,
			SHUTDOWN_DIRTY_SPILL_BUDGET,
			SHUTDOWN_DIRTY_SPILL_BUDGET_MIN
		);
		PreparedChunkSidecarStorage.ShutdownSnapshot snapshot = storage.prepareForShutdown(level, shutdownBudget);
		PreparedChunkSidecarStorage.release(level);

		if (!snapshot.isEmpty()) {
			LOGGER.debug(
				"PauC closed prepared-chunk state for {} after spilling {} dirty chunk(s) and dropping {} dirty, {} materialization, {} force-load queued chunk(s).",
				level.dimension().location(),
				snapshot.spilledDirtyChunks(),
				snapshot.droppedDirtyChunks(),
				snapshot.droppedMaterializationChunks(),
				snapshot.droppedForceLoadChunks()
			);
		}
	}

	private static void runPlanners(ServerLevel level, ChunkPos chunkPos, PreparedChunkSidecarStorage storage) {
		FarChunkPreparationPipeline.request(level, chunkPos, storage);
	}

	private static boolean shouldSuspendWorldMutation(ServerLevel level) {
		if (level == null) {
			return true;
		}

		return PauCCompatManager.isServerStopping()
			|| PauCRenderLifecycle.isClientLogoutInProgress()
			|| PauCClientRenderShutdownGuard.isShutdownInProgress();
	}

	public static String describeState(ServerLevel level) {
		PreparedChunkSidecarStorage storage = PreparedChunkSidecarStorage.peek(level);
		String sidecarState = storage != null ? storage.snapshot().describe() : "sidecar[pendingDeltas=0, dirty=0, materialize=0, forceLoad=0]";
		return sidecarState + ", " + FarChunkPreparationPipeline.describeState(level);
	}

	private static boolean applyNow(ServerLevel level, PendingChunkPlacement placement) {
		if (level.isOutsideBuildHeight(placement.pos())) {
			return true;
		}

		BlockState previousState = level.getBlockState(placement.pos());
		if (previousState.equals(placement.state()) && placement.blockEntityTag() == null) {
			return true;
		}

		boolean changed = level.setBlock(placement.pos(), placement.state(), placement.flags());
		if (!changed) {
			return previousState.equals(placement.state());
		}

		CompoundTag blockEntityTag = placement.blockEntityTag();
		if (blockEntityTag != null) {
			BlockEntity blockEntity = level.getBlockEntity(placement.pos());
			if (blockEntity != null) {
				CompoundTag tag = blockEntityTag.copy();
				tag.putInt("x", placement.pos().getX());
				tag.putInt("y", placement.pos().getY());
				tag.putInt("z", placement.pos().getZ());
				blockEntity.load(tag);
				blockEntity.setChanged();
				level.sendBlockUpdated(placement.pos(), previousState, placement.state(), placement.flags());
			} else {
				return false;
			}
		}

		return true;
	}

	private static String describeGenerationContext(ChunkStatus generatingStatus, @Nullable String generationHint) {
		String statusName = generatingStatus.toString();

		if (generationHint == null || generationHint.isEmpty()) {
			return statusName;
		}

		return statusName + ":" + generationHint;
	}

	public enum SubmissionResult {
		HANDLED_FALSE(false),
		HANDLED_TRUE(true);

		private final boolean reportedSuccess;

		SubmissionResult(boolean reportedSuccess) {
			this.reportedSuccess = reportedSuccess;
		}

		public boolean reportedSuccess() {
			return reportedSuccess;
		}
	}

	public record BlockPosSnapshot(
		net.minecraft.core.BlockPos pos,
		net.minecraft.world.level.block.state.BlockState state,
		int flags,
		int recursionDepth
	) {
		public int chunkX() {
			return pos.getX() >> 4;
		}

		public int chunkZ() {
			return pos.getZ() >> 4;
		}
	}
}
