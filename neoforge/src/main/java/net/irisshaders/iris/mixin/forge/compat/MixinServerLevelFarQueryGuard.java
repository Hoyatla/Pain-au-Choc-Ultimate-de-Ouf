package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCRuntimeSwitches;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerPhase;
import fr.hoyatla.pauc.platform.forge.runtime.PauCStallGovernor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevelFarQueryGuard {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void pauc$guardFarBlockState(BlockPos pos, CallbackInfoReturnable<net.minecraft.world.level.block.state.BlockState> cir) {
		ServerLevel level = (ServerLevel) (Object) this;
		if (!pauc$shouldGuard(level, pos)) {
			return;
		}

		cir.setReturnValue(Blocks.AIR.defaultBlockState());
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void pauc$guardFarFluidState(BlockPos pos, CallbackInfoReturnable<net.minecraft.world.level.material.FluidState> cir) {
		ServerLevel level = (ServerLevel) (Object) this;
		if (!pauc$shouldGuard(level, pos)) {
			return;
		}

		cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
	}

	@Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
	private void pauc$guardFarBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
		ServerLevel level = (ServerLevel) (Object) this;
		if (!pauc$shouldGuard(level, pos)) {
			return;
		}

		cir.setReturnValue(null);
	}

	private static boolean pauc$shouldGuard(ServerLevel level, BlockPos pos) {
		if (!PauCRuntimeSwitches.enabled("farQueryGuard.enabled", false)) {
			return false;
		}

		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;
		if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
			return false;
		}

		return !PauCStallGovernor.allow(level, PauCServerPhase.FAR_QUERY, null);
	}
}
