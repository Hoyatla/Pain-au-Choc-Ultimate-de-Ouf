package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCAsyncPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Runs the vanilla A* search ({@link PathFinder#findPath}) on a worker thread when async pathfinding is enabled.
 * See {@link PauCAsyncPathfinder} for the snapshot/thread-safety rationale. Default-off, opt-in.
 */
@Mixin(PathFinder.class)
public abstract class MixinPauCAsyncPathFinder {
	@Inject(method = "findPath", at = @At("HEAD"), cancellable = true)
	private void pauc$asyncFindPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange,
									int accuracy, float searchDepthMultiplier, CallbackInfoReturnable<Path> cir) {
		// Worker re-entry: let the real search run normally on the worker thread.
		if (PauCAsyncPathfinder.isWorkerThread()) {
			return;
		}
		if (!PauCAsyncPathfinder.enabled() || mob == null || !(mob.level() instanceof ServerLevel)) {
			return;
		}
		cir.setReturnValue(PauCAsyncPathfinder.computeOrCurrent(
			(PathFinder) (Object) this, region, mob, targets, maxRange, accuracy, searchDepthMultiplier));
	}
}
