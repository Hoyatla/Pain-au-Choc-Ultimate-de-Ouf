package fr.hoyatla.pauc.platform.forge.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public record PendingChunkPlacement(
	BlockPos pos,
	BlockState state,
	int flags,
	int recursionDepth,
	long queuedGameTime,
	FarChunkPlacementMode mode,
	String sourceClassName,
	String sourcePackageName,
	@Nullable String generationHint,
	@Nullable CompoundTag blockEntityTag
) {
	private static final String KEY_POS = "pos";
	private static final String KEY_STATE = "state";
	private static final String KEY_FLAGS = "flags";
	private static final String KEY_RECURSION = "recursion";
	private static final String KEY_QUEUED_TIME = "queued_time";
	private static final String KEY_MODE = "mode";
	private static final String KEY_SOURCE_CLASS = "source_class";
	private static final String KEY_SOURCE_PACKAGE = "source_package";
	private static final String KEY_GENERATION_HINT = "generation_hint";
	private static final String KEY_BLOCK_ENTITY = "block_entity";

	public long chunkKey() {
		return ChunkPos.asLong(SectionCoords.chunkX(pos), SectionCoords.chunkZ(pos));
	}

	public boolean shouldForceLoad() {
		return mode.shouldForceLoad();
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putLong(KEY_POS, pos.asLong());
		tag.put(KEY_STATE, NbtUtils.writeBlockState(state));
		tag.putInt(KEY_FLAGS, flags);
		tag.putInt(KEY_RECURSION, recursionDepth);
		tag.putLong(KEY_QUEUED_TIME, queuedGameTime);
		tag.putString(KEY_MODE, mode.name());
		tag.putString(KEY_SOURCE_CLASS, sourceClassName);
		tag.putString(KEY_SOURCE_PACKAGE, sourcePackageName);

		if (generationHint != null && !generationHint.isEmpty()) {
			tag.putString(KEY_GENERATION_HINT, generationHint);
		}

		if (blockEntityTag != null) {
			tag.put(KEY_BLOCK_ENTITY, blockEntityTag.copy());
		}

		return tag;
	}

	public static PendingChunkPlacement load(RegistryAccess registryAccess, CompoundTag tag) {
		HolderGetter<Block> blocks = registryAccess.lookupOrThrow(Registries.BLOCK);
		BlockPos pos = BlockPos.of(tag.getLong(KEY_POS));
		BlockState state = NbtUtils.readBlockState(blocks, tag.getCompound(KEY_STATE));
		FarChunkPlacementMode mode = tag.contains(KEY_MODE) ? FarChunkPlacementMode.valueOf(tag.getString(KEY_MODE)) : FarChunkPlacementMode.DEFER;
		CompoundTag blockEntityTag = tag.contains(KEY_BLOCK_ENTITY) ? tag.getCompound(KEY_BLOCK_ENTITY).copy() : null;

		return new PendingChunkPlacement(
			pos,
			state,
			tag.getInt(KEY_FLAGS),
			tag.getInt(KEY_RECURSION),
			tag.getLong(KEY_QUEUED_TIME),
			mode,
			tag.getString(KEY_SOURCE_CLASS),
			tag.getString(KEY_SOURCE_PACKAGE),
			tag.contains(KEY_GENERATION_HINT) ? tag.getString(KEY_GENERATION_HINT) : null,
			blockEntityTag
		);
	}

	private static final class SectionCoords {
		private SectionCoords() {
		}

		private static int chunkX(BlockPos pos) {
			return pos.getX() >> 4;
		}

		private static int chunkZ(BlockPos pos) {
			return pos.getZ() >> 4;
		}
	}
}
