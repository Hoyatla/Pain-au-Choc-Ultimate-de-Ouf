package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCSpawnThrottler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttles NaturalSpawner's per-chunk natural category sweep under measured server MSPT pressure.
 * See {@link PauCSpawnThrottler} for the gameplay-safety rationale (zero effect on healthy ticks).
 */
@Mixin(NaturalSpawner.class)
public abstract class MixinNaturalSpawnerThrottle {
	@Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true)
	private static void pauc$throttleNaturalSpawn(MobCategory category, ServerLevel level, LevelChunk chunk,
												  NaturalSpawner.SpawnPredicate filter, NaturalSpawner.AfterSpawnCallback callback,
												  CallbackInfo ci) {
		if (PauCSpawnThrottler.shouldSkipSpawnAttempt(level, category)) {
			ci.cancel();
		}
	}
}
