package fr.hoyatla.pauc.platform.forge.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public record FarChunkPreparationContext(
	ServerLevel level,
	ChunkPos centerChunkPos,
	long seed,
	int windowRadiusChunks,
	ChunkPos windowAnchorChunkPos
) {
	public static FarChunkPreparationContext around(ServerLevel level, ChunkPos centerChunkPos, long seed, int windowRadiusChunks) {
		int sanitizedRadius = Math.max(0, windowRadiusChunks);
		int windowSize = sanitizedRadius * 2 + 1;
		int anchorChunkX = Math.floorDiv(centerChunkPos.x, windowSize) * windowSize;
		int anchorChunkZ = Math.floorDiv(centerChunkPos.z, windowSize) * windowSize;
		return new FarChunkPreparationContext(level, centerChunkPos, seed, sanitizedRadius, new ChunkPos(anchorChunkX, anchorChunkZ));
	}

	public int windowSizeChunks() {
		return windowRadiusChunks * 2 + 1;
	}

	public int minChunkX() {
		return windowAnchorChunkPos.x;
	}

	public int minChunkZ() {
		return windowAnchorChunkPos.z;
	}

	public int maxChunkX() {
		return windowAnchorChunkPos.x + windowSizeChunks() - 1;
	}

	public int maxChunkZ() {
		return windowAnchorChunkPos.z + windowSizeChunks() - 1;
	}

	public boolean containsChunk(int chunkX, int chunkZ) {
		return chunkX >= minChunkX()
			&& chunkX <= maxChunkX()
			&& chunkZ >= minChunkZ()
			&& chunkZ <= maxChunkZ();
	}

	public long windowAnchorKey() {
		return windowAnchorChunkPos.toLong();
	}

	public List<ChunkPos> windowChunks() {
		int size = windowSizeChunks();
		List<ChunkPos> chunks = new ArrayList<>(size * size);

		for (int chunkX = minChunkX(); chunkX <= maxChunkX(); chunkX++) {
			for (int chunkZ = minChunkZ(); chunkZ <= maxChunkZ(); chunkZ++) {
				chunks.add(new ChunkPos(chunkX, chunkZ));
			}
		}

		return chunks;
	}
}
