package net.irisshaders.iris.mixin.forge.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HangingEntity.class)
public abstract class MixinHangingEntityInvalidPosition {
	@Unique private static final Logger pauc$LOGGER = LogUtils.getLogger();
	@Unique private static final String pauc$KEY_TILE_X = "TileX";
	@Unique private static final String pauc$KEY_TILE_Y = "TileY";
	@Unique private static final String pauc$KEY_TILE_Z = "TileZ";
	@Unique private static final double pauc$MAX_VALID_TILE_DISTANCE = 16.0D;

	@Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
	private void pauc$sanitizeInvalidHangingEntityTile(CompoundTag tag, CallbackInfo ci) {
		HangingEntity self = (HangingEntity) (Object) this;
		BlockPos entityPos = self.blockPosition();
		BlockPos tilePos = pauc$readTilePosOrFallback(tag, entityPos);

		if (tilePos.closerThan(entityPos, pauc$MAX_VALID_TILE_DISTANCE)) {
			return;
		}

		tag.putInt(pauc$KEY_TILE_X, entityPos.getX());
		tag.putInt(pauc$KEY_TILE_Y, entityPos.getY());
		tag.putInt(pauc$KEY_TILE_Z, entityPos.getZ());
		pauc$LOGGER.debug("PauCUltimate repaired hanging entity tile position {} -> {} during structure/chunk load.", tilePos, entityPos);
	}

	@Unique
	private static BlockPos pauc$readTilePosOrFallback(CompoundTag tag, BlockPos fallback) {
		if (!tag.contains(pauc$KEY_TILE_X, Tag.TAG_INT)
			|| !tag.contains(pauc$KEY_TILE_Y, Tag.TAG_INT)
			|| !tag.contains(pauc$KEY_TILE_Z, Tag.TAG_INT)) {
			return fallback;
		}

		return new BlockPos(tag.getInt(pauc$KEY_TILE_X), tag.getInt(pauc$KEY_TILE_Y), tag.getInt(pauc$KEY_TILE_Z));
	}
}
