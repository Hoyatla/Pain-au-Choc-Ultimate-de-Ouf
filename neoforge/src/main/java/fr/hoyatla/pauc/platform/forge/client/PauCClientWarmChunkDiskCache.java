package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCTaskPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

public final class PauCClientWarmChunkDiskCache {
	private static final String ROOT_DIRECTORY = "pauc_client_cache";
	private static final String REGION_DIRECTORY = "region";
	private static final String KEY_CHUNK = "chunk";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_SECTION_MASK = "sectionMask";
	private static final String KEY_NON_EMPTY_SECTIONS = "nonEmptySections";
	private static final String KEY_STRUCTURE_SECTION_MASK = "structureSectionMask";
	private static final String KEY_FLUID_SECTION_MASK = "fluidSectionMask";
	private static final String KEY_SURFACE_SECTION_INDEX = "surfaceSectionIndex";
	private static final String KEY_CACHE_VERSION = "cacheVersion";
	private static final String KEY_SOURCE_FINGERPRINT = "sourceFingerprint";
	private static final String KEY_TERRAIN_MIN_SECTION_INDEX = "terrainMinSectionIndex";
	private static final String KEY_TERRAIN_MAX_SECTION_INDEX = "terrainMaxSectionIndex";
	private static final String KEY_LOD_CACHE_STATE = "lodCacheState";
	private static final String KEY_LOD_QUALITY_TIER = "lodQualityTier";
	private static final String KEY_CUDA_TERRAIN_SECTION_Y = "cudaTerrainSectionY";
	private static final String KEY_CUDA_PREPARED_AT = "cudaPreparedAt";
	private static final String KEY_CUDA_PROFILE = "cudaProfile";
	private static final String KEY_SAVED_AT = "savedAt";
	private static final String MAX_PENDING_WRITES_PROPERTY = "pauc.client.cache.maxPendingDiskWrites";
	private static final int DEFAULT_MAX_PENDING_WRITES = 768;
	private static final Set<String> PENDING_WRITES = ConcurrentHashMap.newKeySet();
	private static final ConcurrentMap<String, PendingMetadataWrite> DEFERRED_WRITES = new ConcurrentHashMap<>();

	private PauCClientWarmChunkDiskCache() {
	}

	public static void persist(ClientLevel level, PauCClientFrontierWarmupManager.WarmChunkMetadata metadata) {
		if (metadata.nonEmptySectionCount() <= 0) {
			return;
		}

		Path sessionRoot = resolveSessionRoot(level);
		String pendingKey = level.dimension().location() + ":" + metadata.chunkPos().toLong();
		String description = "client warm cache " + level.dimension().location() + " chunk " + metadata.chunkPos();
		PendingMetadataWrite write = new PendingMetadataWrite(sessionRoot, metadata, description);
		if (PENDING_WRITES.contains(pendingKey)) {
			DEFERRED_WRITES.put(pendingKey, write);
			return;
		}
		if (PENDING_WRITES.size() >= maxPendingWrites()) {
			return;
		}
		if (!PENDING_WRITES.add(pendingKey)) {
			DEFERRED_WRITES.put(pendingKey, write);
			return;
		}
		if (PENDING_WRITES.size() > maxPendingWrites()) {
			PENDING_WRITES.remove(pendingKey);
			return;
		}
		scheduleWrite(pendingKey, write);
	}

	public static void persistAll(ClientLevel level, Collection<PauCClientFrontierWarmupManager.WarmChunkMetadata> metadataBatch) {
		if (metadataBatch.isEmpty()) {
			return;
		}

		Path sessionRoot = resolveSessionRoot(level);
		String dimensionId = level.dimension().location().toString();
		int maxPending = maxPendingWrites();
		List<String> pendingKeys = new ArrayList<>();
		List<PendingMetadataWrite> writes = new ArrayList<>();
		for (PauCClientFrontierWarmupManager.WarmChunkMetadata metadata : metadataBatch) {
			if (metadata.nonEmptySectionCount() <= 0) {
				continue;
			}
			String pendingKey = dimensionId + ":" + metadata.chunkPos().toLong();
			String description = "client warm cache " + dimensionId + " chunk " + metadata.chunkPos();
			PendingMetadataWrite write = new PendingMetadataWrite(sessionRoot, metadata, description);
			if (PENDING_WRITES.contains(pendingKey)) {
				DEFERRED_WRITES.put(pendingKey, write);
				continue;
			}
			if (PENDING_WRITES.size() >= maxPending) {
				break;
			}
			if (!PENDING_WRITES.add(pendingKey)) {
				DEFERRED_WRITES.put(pendingKey, write);
				continue;
			}
			pendingKeys.add(pendingKey);
			writes.add(write);
		}

		if (!writes.isEmpty()) {
			scheduleBatchWrite(pendingKeys, writes, "client warm cache batch " + dimensionId + " x" + writes.size());
		}
	}

	@Nullable
	public static PauCClientFrontierWarmupManager.WarmChunkMetadata read(Path sessionRoot, String dimensionId, ChunkPos chunkPos) {
		Path file = metadataFile(sessionRoot, dimensionId, chunkPos.toLong());
		if (!Files.exists(file)) {
			return null;
		}

		try (InputStream input = Files.newInputStream(file)) {
			CompoundTag tag = NbtIo.readCompressed(input);
			return decodeMetadata(tag, dimensionId, chunkPos);
		} catch (IOException ignored) {
			return null;
		}
	}

	public static List<PauCClientFrontierWarmupManager.WarmChunkMetadata> readRegion(
		Path sessionRoot,
		String dimensionId,
		int regionX,
		int regionZ,
		int centerChunkX,
		int centerChunkZ,
		int maxChebyshevDistance,
		int limit
	) {
		Path directory = regionDirectory(sessionRoot, dimensionId, regionX, regionZ);
		if (!Files.isDirectory(directory) || limit <= 0) {
			return List.of();
		}

		List<PauCClientFrontierWarmupManager.WarmChunkMetadata> metadata = new ArrayList<>();
		try (Stream<Path> paths = Files.list(directory)) {
			paths
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".nbt"))
				.forEach(path -> {
					if (metadata.size() >= limit) {
						return;
					}
					PauCClientFrontierWarmupManager.WarmChunkMetadata entry = readMetadataFile(path, dimensionId);
					if (entry == null || !entry.dimensionId().equals(dimensionId)) {
						return;
					}
					int distance = Math.max(Math.abs(entry.chunkPos().x - centerChunkX), Math.abs(entry.chunkPos().z - centerChunkZ));
					if (distance <= maxChebyshevDistance) {
						metadata.add(entry);
					}
				});
		} catch (IOException ignored) {
			return List.of();
		}

		metadata.sort(Comparator.comparingInt(entry -> Math.max(Math.abs(entry.chunkPos().x - centerChunkX), Math.abs(entry.chunkPos().z - centerChunkZ))));
		return metadata;
	}

	public static Path resolveSessionRoot(ClientLevel level) {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer integratedServer = minecraft.getSingleplayerServer();
		if (integratedServer != null) {
			return integratedServer.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIRECTORY);
		}

		ServerData server = minecraft.getCurrentServer();
		String serverKey = server != null && server.ip != null && !server.ip.isBlank()
			? sanitize(server.ip)
			: "unknown-server";
		return minecraft.gameDirectory.toPath().resolve(ROOT_DIRECTORY).resolve(serverKey);
	}

	private static void scheduleWrite(String pendingKey, PendingMetadataWrite write) {
		CompletableFuture<Void> future = PauCScheduler.submitIo(PauCTaskPriority.BACKGROUND, write.description(), () -> writeMetadata(write.sessionRoot(), write.metadata()));
		future.whenComplete((ignored, throwable) -> {
			PendingMetadataWrite deferred = DEFERRED_WRITES.remove(pendingKey);
			if (deferred != null) {
				scheduleWrite(pendingKey, deferred);
			} else {
				PENDING_WRITES.remove(pendingKey);
			}
		});
	}

	private static void scheduleBatchWrite(List<String> pendingKeys, List<PendingMetadataWrite> writes, String description) {
		CompletableFuture<Void> future = PauCScheduler.submitIo(PauCTaskPriority.BACKGROUND, description, () -> {
			for (PendingMetadataWrite write : writes) {
				writeMetadata(write.sessionRoot(), write.metadata());
			}
		});
		future.whenComplete((ignored, throwable) -> {
			for (String pendingKey : pendingKeys) {
				PendingMetadataWrite deferred = DEFERRED_WRITES.remove(pendingKey);
				if (deferred != null) {
					scheduleWrite(pendingKey, deferred);
				} else {
					PENDING_WRITES.remove(pendingKey);
				}
			}
		});
	}

	@Nullable
	private static PauCClientFrontierWarmupManager.WarmChunkMetadata readMetadataFile(Path file, String fallbackDimensionId) {
		try (InputStream input = Files.newInputStream(file)) {
			return decodeMetadata(NbtIo.readCompressed(input), fallbackDimensionId, null);
		} catch (IOException ignored) {
			return null;
		}
	}

	private static PauCClientFrontierWarmupManager.WarmChunkMetadata decodeMetadata(
		CompoundTag tag,
		String fallbackDimensionId,
		@Nullable ChunkPos fallbackChunkPos
	) {
		String dimensionId = tag.contains(KEY_DIMENSION) ? tag.getString(KEY_DIMENSION) : fallbackDimensionId;
		ChunkPos chunkPos = fallbackChunkPos != null
			? fallbackChunkPos
			: new ChunkPos(tag.getLong(KEY_CHUNK));
		int sectionMask = tag.getInt(KEY_SECTION_MASK);
		int nonEmptySections = tag.getInt(KEY_NON_EMPTY_SECTIONS);
		int structureSectionMask = tag.getInt(KEY_STRUCTURE_SECTION_MASK);
		int fluidSectionMask = tag.getInt(KEY_FLUID_SECTION_MASK);
		int surfaceSectionIndex = tag.contains(KEY_SURFACE_SECTION_INDEX) ? tag.getInt(KEY_SURFACE_SECTION_INDEX) : -1;
		int minSectionIndex = tag.contains(KEY_TERRAIN_MIN_SECTION_INDEX)
			? tag.getInt(KEY_TERRAIN_MIN_SECTION_INDEX)
			: PauCClientFrontierWarmupManager.WarmChunkMetadata.minFilledSectionIndex(sectionMask);
		int maxSectionIndex = tag.contains(KEY_TERRAIN_MAX_SECTION_INDEX)
			? tag.getInt(KEY_TERRAIN_MAX_SECTION_INDEX)
			: PauCClientFrontierWarmupManager.WarmChunkMetadata.maxFilledSectionIndex(sectionMask);
		long sourceFingerprint = tag.contains(KEY_SOURCE_FINGERPRINT)
			? tag.getLong(KEY_SOURCE_FINGERPRINT)
			: PauCClientFrontierWarmupManager.WarmChunkMetadata.computeSourceFingerprint(sectionMask, nonEmptySections, structureSectionMask, fluidSectionMask, surfaceSectionIndex, minSectionIndex, maxSectionIndex);
		return new PauCClientFrontierWarmupManager.WarmChunkMetadata(
			dimensionId,
			chunkPos,
			sectionMask,
			nonEmptySections,
			structureSectionMask,
			fluidSectionMask,
			surfaceSectionIndex,
			minSectionIndex,
			maxSectionIndex,
			tag.contains(KEY_CACHE_VERSION) ? tag.getInt(KEY_CACHE_VERSION) : 1,
			sourceFingerprint,
			tag.contains(KEY_LOD_CACHE_STATE) ? tag.getInt(KEY_LOD_CACHE_STATE) : PauCClientFrontierWarmupManager.LOD_CACHE_STATE_METADATA_CLEAN,
			tag.contains(KEY_LOD_QUALITY_TIER) ? tag.getInt(KEY_LOD_QUALITY_TIER) : PauCClientFrontierWarmupManager.LOD_QUALITY_COARSE,
			tag.contains(KEY_CUDA_TERRAIN_SECTION_Y) ? tag.getFloat(KEY_CUDA_TERRAIN_SECTION_Y) : Float.NaN,
			tag.contains(KEY_CUDA_PREPARED_AT) ? tag.getLong(KEY_CUDA_PREPARED_AT) : 0L,
			tag.contains(KEY_CUDA_PROFILE) ? tag.getString(KEY_CUDA_PROFILE) : "",
			tag.getLong(KEY_SAVED_AT)
		);
	}

	private static void writeMetadata(Path sessionRoot, PauCClientFrontierWarmupManager.WarmChunkMetadata metadata) {
		Path file = metadataFile(sessionRoot, metadata.dimensionId(), metadata.chunkPos().toLong());

		try {
			Files.createDirectories(file.getParent());
			CompoundTag tag = new CompoundTag();
			tag.putLong(KEY_CHUNK, metadata.chunkPos().toLong());
			tag.putString(KEY_DIMENSION, metadata.dimensionId());
			tag.putInt(KEY_SECTION_MASK, metadata.nonEmptySectionMask());
			tag.putInt(KEY_NON_EMPTY_SECTIONS, metadata.nonEmptySectionCount());
			tag.putInt(KEY_STRUCTURE_SECTION_MASK, metadata.structureSectionMask());
			tag.putInt(KEY_FLUID_SECTION_MASK, metadata.fluidSectionMask());
			tag.putInt(KEY_SURFACE_SECTION_INDEX, metadata.surfaceSectionIndex());
			tag.putInt(KEY_CACHE_VERSION, metadata.cacheVersion());
			tag.putLong(KEY_SOURCE_FINGERPRINT, metadata.sourceFingerprint());
			tag.putInt(KEY_TERRAIN_MIN_SECTION_INDEX, metadata.terrainMinSectionIndex());
			tag.putInt(KEY_TERRAIN_MAX_SECTION_INDEX, metadata.terrainMaxSectionIndex());
			tag.putInt(KEY_LOD_CACHE_STATE, metadata.lodCacheState());
			tag.putInt(KEY_LOD_QUALITY_TIER, metadata.lodQualityTier());
			if (Float.isFinite(metadata.cudaTerrainSectionY())) {
				tag.putFloat(KEY_CUDA_TERRAIN_SECTION_Y, metadata.cudaTerrainSectionY());
			}
			tag.putLong(KEY_CUDA_PREPARED_AT, metadata.cudaPreparedAtMillis());
			if (!metadata.cudaProfile().isBlank()) {
				tag.putString(KEY_CUDA_PROFILE, metadata.cudaProfile());
			}
			tag.putLong(KEY_SAVED_AT, metadata.savedAtMillis());

			Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
			try (OutputStream output = Files.newOutputStream(tempFile)) {
				NbtIo.writeCompressed(tag, output);
			}

			try {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException ignored) {
		}
	}

	private static Path metadataFile(Path sessionRoot, String dimensionId, long chunkKey) {
		ChunkPos chunkPos = new ChunkPos(chunkKey);
		return regionDirectory(sessionRoot, dimensionId, chunkPos.x >> 5, chunkPos.z >> 5)
			.resolve("c." + chunkPos.x + "." + chunkPos.z + ".nbt");
	}

	private static Path regionDirectory(Path sessionRoot, String dimensionId, int regionX, int regionZ) {
		return sessionRoot
			.resolve(dimensionId.replace(':', '_'))
			.resolve(REGION_DIRECTORY)
			.resolve("r." + regionX + "." + regionZ);
	}

	private static String sanitize(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private static int maxPendingWrites() {
		String value = System.getProperty(MAX_PENDING_WRITES_PROPERTY);
		if (value == null) {
			return DEFAULT_MAX_PENDING_WRITES;
		}

		try {
			return Math.max(8, Math.min(1024, Integer.parseInt(value.trim())));
		} catch (NumberFormatException ignored) {
			return DEFAULT_MAX_PENDING_WRITES;
		}
	}

	private record PendingMetadataWrite(
		Path sessionRoot,
		PauCClientFrontierWarmupManager.WarmChunkMetadata metadata,
		String description
	) {
	}
}
