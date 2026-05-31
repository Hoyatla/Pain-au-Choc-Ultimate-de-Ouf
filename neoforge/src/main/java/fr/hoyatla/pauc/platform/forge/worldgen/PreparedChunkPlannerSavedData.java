package fr.hoyatla.pauc.platform.forge.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PreparedChunkPlannerSavedData extends SavedData {
	private static final String DATA_NAME = "pauc_prepared_chunk_planners";
	private static final String KEY_PREPARED_PLANNERS = "prepared_planners";

	private final Map<String, Set<Long>> preparedChunksByPlanner = new HashMap<>();

	public static PreparedChunkPlannerSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(
			PreparedChunkPlannerSavedData::load,
			PreparedChunkPlannerSavedData::new,
			DATA_NAME
		);
	}

	private static PreparedChunkPlannerSavedData load(CompoundTag tag) {
		PreparedChunkPlannerSavedData data = new PreparedChunkPlannerSavedData();
		CompoundTag preparedTag = tag.getCompound(KEY_PREPARED_PLANNERS);

		for (String plannerId : preparedTag.getAllKeys()) {
			Set<Long> preparedChunks = new HashSet<>();

			for (long chunkKey : preparedTag.getLongArray(plannerId)) {
				preparedChunks.add(chunkKey);
			}

			if (!preparedChunks.isEmpty()) {
				data.preparedChunksByPlanner.put(plannerId, preparedChunks);
			}
		}

		return data;
	}

	public boolean isPlannerPrepared(ResourceLocation plannerId, long preparationKey) {
		Set<Long> preparedChunks = preparedChunksByPlanner.get(plannerId.toString());
		return preparedChunks != null && preparedChunks.contains(preparationKey);
	}

	public boolean markPlannerPrepared(ResourceLocation plannerId, long preparationKey) {
		Set<Long> preparedChunks = preparedChunksByPlanner.computeIfAbsent(plannerId.toString(), ignored -> new HashSet<>());
		boolean added = preparedChunks.add(preparationKey);

		if (added) {
			setDirty();
		}

		return added;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		CompoundTag preparedTag = new CompoundTag();

		for (Map.Entry<String, Set<Long>> entry : preparedChunksByPlanner.entrySet()) {
			long[] chunkKeys = entry.getValue().stream().mapToLong(Long::longValue).toArray();
			preparedTag.putLongArray(entry.getKey(), chunkKeys);
		}

		tag.put(KEY_PREPARED_PLANNERS, preparedTag);
		return tag;
	}
}
