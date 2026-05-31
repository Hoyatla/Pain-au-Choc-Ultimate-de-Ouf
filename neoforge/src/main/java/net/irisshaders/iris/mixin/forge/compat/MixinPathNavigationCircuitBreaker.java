package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCPathfindingCircuitBreaker;
import fr.hoyatla.pauc.platform.forge.runtime.PauCRuntimeSwitches;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class MixinPathNavigationCircuitBreaker {
	@Unique
	private Mob pauc$mob;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void pauc$captureMob(Mob mob, Level level, CallbackInfo ci) {
		this.pauc$mob = mob;
	}

	@Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
	private void pauc$guardFarPathToBlock(BlockPos target, int distance, CallbackInfoReturnable<Path> cir) {
		Mob mob = this.pauc$mob;
		if (mob == null || !(mob.level() instanceof ServerLevel level)) {
			return;
		}

		if (PauCPathfindingCircuitBreaker.hasCachedFailure(level, mob, target)) {
			cir.setReturnValue(null);
			return;
		}

		if (!PauCRuntimeSwitches.enabled("pathfindingBreaker.enabled", true)) {
			return;
		}

		int chunkX = target.getX() >> 4;
		int chunkZ = target.getZ() >> 4;
		if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
			return;
		}

		if (!PauCPathfindingCircuitBreaker.shouldAllow(level, mob, target)) {
			cir.setReturnValue(null);
		}
	}

	@Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("RETURN"))
	private void pauc$rememberFailedPathToBlock(BlockPos target, int distance, CallbackInfoReturnable<Path> cir) {
		Mob mob = this.pauc$mob;
		if (cir.getReturnValue() != null || mob == null || !(mob.level() instanceof ServerLevel level)) {
			return;
		}

		PauCPathfindingCircuitBreaker.rememberFailure(level, mob, target);
	}

	@Inject(method = "createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
	private void pauc$guardFarPathToEntity(Entity target, int distance, CallbackInfoReturnable<Path> cir) {
		Mob mob = this.pauc$mob;
		if (mob == null || !(mob.level() instanceof ServerLevel level) || target == null) {
			return;
		}

		BlockPos targetPos = target.blockPosition();
		if (PauCPathfindingCircuitBreaker.hasCachedFailure(level, mob, targetPos)) {
			cir.setReturnValue(null);
			return;
		}

		if (!PauCRuntimeSwitches.enabled("pathfindingBreaker.enabled", true)) {
			return;
		}

		int chunkX = targetPos.getX() >> 4;
		int chunkZ = targetPos.getZ() >> 4;
		if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
			return;
		}

		if (!PauCPathfindingCircuitBreaker.shouldAllow(level, mob, targetPos)) {
			cir.setReturnValue(null);
		}
	}

	@Inject(method = "createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("RETURN"))
	private void pauc$rememberFailedPathToEntity(Entity target, int distance, CallbackInfoReturnable<Path> cir) {
		Mob mob = this.pauc$mob;
		if (cir.getReturnValue() != null || mob == null || !(mob.level() instanceof ServerLevel level) || target == null) {
			return;
		}

		PauCPathfindingCircuitBreaker.rememberFailure(level, mob, target.blockPosition());
	}
}
