package fr.hoyatla.pauc.platform.forge.worldgen;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhase;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhaseBudgetController;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

public final class PreparedChunkSidecarStorage {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int MAX_PENDING_DELTA_SIZE_BEFORE_SPILL = 128;
	private static final String ROOT_DIRECTORY = "pauc_prepared";
	private static final String REGION_DIRECTORY = "region";
	private static final String KEY_CHUNK = "chunk";
	private static final String KEY_PLACEMENTS = "placements";
	private static final Comparator<PendingChunkPlacement> PLACEMENT_ORDER = Comparator
		.comparingLong(PendingChunkPlacement::queuedGameTime)
		.thenComparingLong(placement -> placement.pos().asLong());
	private static final ConcurrentMap<String, PreparedChunkSidecarStorage> STORES = new ConcurrentHashMap<>();

	private final ResourceLocation dimensionId;
	private final Path dimensionRoot;
	private final ConcurrentMap<Long, ChunkDelta> pendingDeltas = new ConcurrentHashMap<>();
	private final Deque<Long> dirtyQueue = new ConcurrentLinkedDeque<>();
	private final Set<Long> dirtyQueued = ConcurrentHashMap.newKeySet();
	private final Deque<Long> materializationQueue = new ConcurrentLinkedDeque<>();
	private final Set<Long> materializationQueued = ConcurrentHashMap.newKeySet();
	private final Deque<Long> forceLoadQueue = new ConcurrentLinkedDeque<>();
	private final Set<Long> forceLoadQueued = ConcurrentHashMap.newKeySet();

	private PreparedChunkSidecarStorage(ResourceLocation dimensionId, Path dimensionRoot) {
		this.dimensionId = dimensionId;
		this.dimensionRoot = dimensionRoot;
	}

	public static PreparedChunkSidecarStorage get(ServerLevel level) {
		Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
		ResourceLocation dimensionId = level.dimension().location();
		return STORES.computeIfAbsent(storeKey(worldRoot, dimensionId), ignored -> new PreparedChunkSidecarStorage(dimensionId, resolveDimensionRoot(worldRoot, dimensionId)));
	}

	@Nullable
	public static PreparedChunkSidecarStorage peek(ServerLevel level) {
		Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
		ResourceLocation dimensionId = level.dimension().location();
		return STORES.get(storeKey(worldRoot, dimensionId));
	}

	public static void release(ServerLevel level) {
		Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
		ResourceLocation dimensionId = level.dimension().location();
		STORES.remove(storeKey(worldRoot, dimensionId));
	}

	public void enqueue(ServerLevel level, PendingChunkPlacement placement) {
		long chunkKey = placement.chunkKey();
		int deltaSize = pendingDeltas.computeIfAbsent(chunkKey, ignored -> new ChunkDelta()).put(placement);
		scheduleDirty(chunkKey);
		scheduleMaterialization(chunkKey);

		if (placement.shouldForceLoad()) {
			scheduleForceLoad(chunkKey);
		}

		if (deltaSize >= MAX_PENDING_DELTA_SIZE_BEFORE_SPILL) {
			flushChunkDelta(level, chunkKey);
		}
	}

	public void enqueueAll(ServerLevel level, Collection<PendingChunkPlacement> placements) {
		for (PendingChunkPlacement placement : placements) {
			enqueue(level, placement);
		}
	}

	public void flushDirty(ServerLevel level, int budget) {
		for (int i = 0; i < budget; i++) {
			if (!PauCServerPhaseBudgetController.tryConsume(level.getServer(), PauCServerPhase.SAVE_FLUSH, 1.0D)) {
				return;
			}

			Long chunkKey = dirtyQueue.pollFirst();
			if (chunkKey == null) {
				return;
			}

			dirtyQueued.remove(chunkKey);
			flushChunkDelta(level, chunkKey);
		}
	}

	public void flushAll(ServerLevel level) {
		while (true) {
			Long chunkKey = dirtyQueue.pollFirst();
			if (chunkKey == null) {
				return;
			}

			dirtyQueued.remove(chunkKey);
			flushChunkDelta(level, chunkKey);
		}
	}

	public int processLoadedMaterializations(ServerLevel level, int budget, PlacementApplier applier) {
		int appliedPlacements = 0;

		for (int i = 0; i < budget; i++) {
			if (!PauCServerPhaseBudgetController.tryConsume(level.getServer(), PauCServerPhase.WORLDGEN_APPLY, 1.0D)) {
				return appliedPlacements;
			}

			Long chunkKey = materializationQueue.pollFirst();
			if (chunkKey == null) {
				return appliedPlacements;
			}

			materializationQueued.remove(chunkKey);
			ChunkPos chunkPos = new ChunkPos(chunkKey);

			if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) == null) {
				continue;
			}

			appliedPlacements += materializeChunk(level, chunkPos, applier);
		}

		return appliedPlacements;
	}

	public ShutdownSnapshot prepareForShutdown(ServerLevel level, int dirtyBudget) {
		int dirtyBefore = dirtyQueued.size();
		int materializationBefore = materializationQueued.size();
		int forceLoadBefore = forceLoadQueued.size();

		if (dirtyBudget > 0) {
			flushDirty(level, dirtyBudget);
		}

		int dirtyAfter = dirtyQueued.size();
		clearRuntimeState();
		return new ShutdownSnapshot(
			dirtyBefore - dirtyAfter,
			dirtyAfter,
			materializationBefore,
			forceLoadBefore
		);
	}

	public RuntimeSnapshot snapshot() {
		return new RuntimeSnapshot(
			pendingDeltas.size(),
			dirtyQueued.size(),
			materializationQueued.size(),
			forceLoadQueued.size()
		);
	}

	public int processForcedMaterializations(ServerLevel level, int budget, PlacementApplier applier) {
		int appliedPlacements = 0;

		for (int i = 0; i < budget; i++) {
			Long chunkKey = forceLoadQueue.pollFirst();
			if (chunkKey == null) {
				return appliedPlacements;
			}

			forceLoadQueued.remove(chunkKey);

			if (!hasPreparedData(chunkKey)) {
				continue;
			}

			ChunkPos chunkPos = new ChunkPos(chunkKey);
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);

			if (chunk == null) {
				if (!PauCServerPhaseBudgetController.tryConsume(level.getServer(), PauCServerPhase.WORLDGEN_FORCE_LOAD, 1.0D)) {
					scheduleForceLoad(chunkKey);
					continue;
				}
				chunk = tryForceLoad(level, chunkPos);
			}

			if (chunk == null) {
				scheduleForceLoad(chunkKey);
				continue;
			}

			appliedPlacements += materializeChunk(level, chunkPos, applier);
		}

		return appliedPlacements;
	}

	public int materializeChunk(ServerLevel level, ChunkPos chunkPos, PlacementApplier applier) {
		long chunkKey = chunkPos.toLong();
		List<PendingChunkPlacement> placements = collectPlacements(level, chunkKey);
		if (placements.isEmpty()) {
			cleanupChunkState(chunkKey);
			return 0;
		}

		List<PendingChunkPlacement> remainingPlacements = new ArrayList<>();
		int appliedPlacements = 0;

		for (PendingChunkPlacement placement : placements) {
			try {
				if (applier.apply(level, placement)) {
					appliedPlacements++;
				} else {
					remainingPlacements.add(placement);
				}
			} catch (RuntimeException exception) {
				remainingPlacements.add(placement);
				LOGGER.warn("PauC could not materialize prepared chunk placement at {} in {}.", placement.pos(), level.dimension().location(), exception);
			}
		}

		if (remainingPlacements.isEmpty()) {
			deleteChunkFile(chunkKey);
			cleanupChunkState(chunkKey);
		} else {
			writeChunkPlacements(chunkKey, remainingPlacements);
			scheduleMaterialization(chunkKey);
		}

		return appliedPlacements;
	}

	private void flushChunkDelta(ServerLevel level, long chunkKey) {
		ChunkDelta delta = pendingDeltas.get(chunkKey);
		Map<Long, PendingChunkPlacement> pendingPlacements = delta != null ? delta.drainAll() : Map.of();
		discardEmptyDelta(chunkKey, delta);

		if (pendingPlacements.isEmpty()) {
			return;
		}

		Map<Long, PendingChunkPlacement> combinedPlacements = readChunkPlacements(level, chunkKey);
		combinedPlacements.putAll(pendingPlacements);
		writeChunkPlacements(chunkKey, combinedPlacements.values());
	}

	private List<PendingChunkPlacement> collectPlacements(ServerLevel level, long chunkKey) {
		Map<Long, PendingChunkPlacement> combinedPlacements = readChunkPlacements(level, chunkKey);
		ChunkDelta delta = pendingDeltas.get(chunkKey);

		if (delta != null) {
			combinedPlacements.putAll(delta.drainAll());
			discardEmptyDelta(chunkKey, delta);
		}

		if (combinedPlacements.isEmpty()) {
			return List.of();
		}

		List<PendingChunkPlacement> orderedPlacements = new ArrayList<>(combinedPlacements.values());
		orderedPlacements.sort(PLACEMENT_ORDER);
		return orderedPlacements;
	}

	private boolean hasPreparedData(long chunkKey) {
		ChunkDelta delta = pendingDeltas.get(chunkKey);
		if (delta != null && !delta.isEmpty()) {
			return true;
		}

		return Files.exists(chunkFile(chunkKey));
	}

	private void scheduleDirty(long chunkKey) {
		if (dirtyQueued.add(chunkKey)) {
			dirtyQueue.addLast(chunkKey);
		}
	}

	private void scheduleMaterialization(long chunkKey) {
		if (materializationQueued.add(chunkKey)) {
			materializationQueue.addLast(chunkKey);
		}
	}

	private void scheduleForceLoad(long chunkKey) {
		if (forceLoadQueued.add(chunkKey)) {
			forceLoadQueue.addLast(chunkKey);
		}
	}

	private void cleanupChunkState(long chunkKey) {
		dirtyQueued.remove(chunkKey);
		dirtyQueue.removeIf(queuedChunkKey -> queuedChunkKey == chunkKey);
		materializationQueued.remove(chunkKey);
		materializationQueue.removeIf(queuedChunkKey -> queuedChunkKey == chunkKey);
		forceLoadQueued.remove(chunkKey);
		forceLoadQueue.removeIf(queuedChunkKey -> queuedChunkKey == chunkKey);

		ChunkDelta delta = pendingDeltas.get(chunkKey);
		discardEmptyDelta(chunkKey, delta);
	}

	private void clearRuntimeState() {
		pendingDeltas.clear();
		dirtyQueue.clear();
		dirtyQueued.clear();
		materializationQueue.clear();
		materializationQueued.clear();
		forceLoadQueue.clear();
		forceLoadQueued.clear();
	}

	private void discardEmptyDelta(long chunkKey, @Nullable ChunkDelta delta) {
		if (delta != null && delta.isEmpty()) {
			pendingDeltas.remove(chunkKey, delta);
		}
	}

	private Path chunkFile(long chunkKey) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		int regionX = chunkPos.x >> 5;
		int regionZ = chunkPos.z >> 5;
		return dimensionRoot
			.resolve(REGION_DIRECTORY)
			.resolve("r." + regionX + "." + regionZ)
			.resolve("c." + chunkPos.x + "." + chunkPos.z + ".nbt");
	}

	private Map<Long, PendingChunkPlacement> readChunkPlacements(@Nullable ServerLevel level, long chunkKey) {
		Path file = chunkFile(chunkKey);
		if (!Files.exists(file)) {
			return new LinkedHashMap<>();
		}

		try (InputStream input = Files.newInputStream(file)) {
			CompoundTag tag = NbtIo.readCompressed(input);
			ListTag placementTags = tag.getList(KEY_PLACEMENTS, Tag.TAG_COMPOUND);
			Map<Long, PendingChunkPlacement> placements = new LinkedHashMap<>(placementTags.size());

			for (Tag placementTagEntry : placementTags) {
				PendingChunkPlacement placement = PendingChunkPlacement.load(level != null ? level.registryAccess() : null, (CompoundTag) placementTagEntry);
				placements.put(placement.pos().asLong(), placement);
			}

			return placements;
		} catch (IOException exception) {
			LOGGER.warn("PauC could not read prepared chunk sidecar {} for dimension {}.", file, dimensionId, exception);
			return new LinkedHashMap<>();
		}
	}

	private void writeChunkPlacements(long chunkKey, Collection<PendingChunkPlacement> placements) {
		Path file = chunkFile(chunkKey);

		if (placements.isEmpty()) {
			deleteChunkFile(chunkKey);
			return;
		}

		try {
			Files.createDirectories(file.getParent());
			CompoundTag tag = new CompoundTag();
			ListTag placementTags = new ListTag();
			List<PendingChunkPlacement> orderedPlacements = new ArrayList<>(placements);
			orderedPlacements.sort(PLACEMENT_ORDER);

			for (PendingChunkPlacement placement : orderedPlacements) {
				placementTags.add(placement.save());
			}

			tag.putLong(KEY_CHUNK, chunkKey);
			tag.put(KEY_PLACEMENTS, placementTags);

			Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
			try (OutputStream output = Files.newOutputStream(tempFile)) {
				NbtIo.writeCompressed(tag, output);
			}

			try {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			LOGGER.warn("PauC could not write prepared chunk sidecar {} for dimension {}.", file, dimensionId, exception);
			restorePlacements(chunkKey, placements);
		}
	}

	private void deleteChunkFile(long chunkKey) {
		Path file = chunkFile(chunkKey);

		try {
			Files.deleteIfExists(file);
		} catch (IOException exception) {
			LOGGER.warn("PauC could not delete prepared chunk sidecar {} for dimension {}.", file, dimensionId, exception);
		}
	}

	@Nullable
	private LevelChunk tryForceLoad(ServerLevel level, ChunkPos chunkPos) {
		level.setChunkForced(chunkPos.x, chunkPos.z, true);
		try {
			ChunkAccess chunkAccess = level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
			if (chunkAccess instanceof LevelChunk levelChunk) {
				return levelChunk;
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("PauC could not force-load prepared chunk {} in {}.", chunkPos, level.dimension().location(), exception);
		} finally {
			level.setChunkForced(chunkPos.x, chunkPos.z, false);
		}

		return null;
	}

	private static Path resolveDimensionRoot(Path worldRoot, ResourceLocation dimensionId) {
		return worldRoot
			.resolve(ROOT_DIRECTORY)
			.resolve(dimensionId.getNamespace())
			.resolve(dimensionId.getPath());
	}

	private static String storeKey(Path worldRoot, ResourceLocation dimensionId) {
		return worldRoot.toAbsolutePath().normalize() + "|" + dimensionId;
	}

	@FunctionalInterface
	public interface PlacementApplier {
		boolean apply(ServerLevel level, PendingChunkPlacement placement);
	}

	public record ShutdownSnapshot(
		int spilledDirtyChunks,
		int droppedDirtyChunks,
		int droppedMaterializationChunks,
		int droppedForceLoadChunks
	) {
		public boolean isEmpty() {
			return spilledDirtyChunks == 0
				&& droppedDirtyChunks == 0
				&& droppedMaterializationChunks == 0
				&& droppedForceLoadChunks == 0;
		}
	}

	public record RuntimeSnapshot(
		int pendingDeltas,
		int dirtyChunks,
		int materializationChunks,
		int forceLoadChunks
	) {
		public boolean isEmpty() {
			return pendingDeltas == 0
				&& dirtyChunks == 0
				&& materializationChunks == 0
				&& forceLoadChunks == 0;
		}

		public String describe() {
			return "sidecar[pendingDeltas="
				+ pendingDeltas
				+ ", dirty="
				+ dirtyChunks
				+ ", materialize="
				+ materializationChunks
				+ ", forceLoad="
				+ forceLoadChunks
				+ "]";
		}
	}

	private static final class ChunkDelta {
		private final Map<Long, PendingChunkPlacement> placements = new LinkedHashMap<>();

		public synchronized int put(PendingChunkPlacement placement) {
			placements.put(placement.pos().asLong(), placement);
			return placements.size();
		}

		public synchronized Map<Long, PendingChunkPlacement> drainAll() {
			if (placements.isEmpty()) {
				return Map.of();
			}

			Map<Long, PendingChunkPlacement> snapshot = new LinkedHashMap<>(placements);
			placements.clear();
			return snapshot;
		}

		public synchronized boolean isEmpty() {
			return placements.isEmpty();
		}
	}

	private void restorePlacements(long chunkKey, Collection<PendingChunkPlacement> placements) {
		ChunkDelta delta = pendingDeltas.computeIfAbsent(chunkKey, ignored -> new ChunkDelta());
		boolean shouldForceLoad = false;

		for (PendingChunkPlacement placement : placements) {
			delta.put(placement);
			shouldForceLoad |= placement.shouldForceLoad();
		}

		scheduleDirty(chunkKey);
		scheduleMaterialization(chunkKey);
		if (shouldForceLoad) {
			scheduleForceLoad(chunkKey);
		}
	}
}
