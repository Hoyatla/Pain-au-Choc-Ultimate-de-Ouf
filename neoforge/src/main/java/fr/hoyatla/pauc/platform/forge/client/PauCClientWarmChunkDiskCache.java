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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCClientWarmChunkDiskCache {
	private static final String ROOT_DIRECTORY = "pauc_client_cache";
	private static final String REGION_DIRECTORY = "region";
	private static final String KEY_CHUNK = "chunk";
	private static final String KEY_DIMENSION = "dimension";
	private static final String KEY_SECTION_MASK = "sectionMask";
	private static final String KEY_NON_EMPTY_SECTIONS = "nonEmptySections";
	private static final String KEY_SAVED_AT = "savedAt";
	private static final Set<String> PENDING_WRITES = ConcurrentHashMap.newKeySet();

	private PauCClientWarmChunkDiskCache() {
	}

	public static void persist(ClientLevel level, PauCClientFrontierWarmupManager.WarmChunkMetadata metadata) {
		if (!PauCorRendererBridge.isAvailable()) {
			return;
		}

		Path sessionRoot = resolveSessionRoot(level);
		String pendingKey = level.dimension().location() + ":" + metadata.chunkPos().toLong();
		if (!PENDING_WRITES.add(pendingKey)) {
			return;
		}
		String description = "client warm cache " + level.dimension().location() + " chunk " + metadata.chunkPos();
		CompletableFuture<Void> future = PauCScheduler.submitIo(PauCTaskPriority.BACKGROUND, description, () -> writeMetadata(sessionRoot, metadata));
		future.whenComplete((ignored, throwable) -> PENDING_WRITES.remove(pendingKey));
	}

	@Nullable
	public static PauCClientFrontierWarmupManager.WarmChunkMetadata read(Path sessionRoot, String dimensionId, ChunkPos chunkPos) {
		Path file = metadataFile(sessionRoot, dimensionId, chunkPos.toLong());
		if (!Files.exists(file)) {
			return null;
		}

		try (InputStream input = Files.newInputStream(file)) {
			CompoundTag tag = NbtIo.readCompressed(input);
			return new PauCClientFrontierWarmupManager.WarmChunkMetadata(
				dimensionId,
				chunkPos,
				tag.getInt(KEY_SECTION_MASK),
				tag.getInt(KEY_NON_EMPTY_SECTIONS),
				tag.getLong(KEY_SAVED_AT)
			);
		} catch (IOException ignored) {
			return null;
		}
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

	private static void writeMetadata(Path sessionRoot, PauCClientFrontierWarmupManager.WarmChunkMetadata metadata) {
		Path file = metadataFile(sessionRoot, metadata.dimensionId(), metadata.chunkPos().toLong());

		try {
			Files.createDirectories(file.getParent());
			CompoundTag tag = new CompoundTag();
			tag.putLong(KEY_CHUNK, metadata.chunkPos().toLong());
			tag.putString(KEY_DIMENSION, metadata.dimensionId());
			tag.putInt(KEY_SECTION_MASK, metadata.nonEmptySectionMask());
			tag.putInt(KEY_NON_EMPTY_SECTIONS, metadata.nonEmptySectionCount());
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
		return sessionRoot
			.resolve(dimensionId.replace(':', '_'))
			.resolve(REGION_DIRECTORY)
			.resolve("r." + (chunkPos.x >> 5) + "." + (chunkPos.z >> 5))
			.resolve("c." + chunkPos.x + "." + chunkPos.z + ".nbt");
	}

	private static String sanitize(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}
}
