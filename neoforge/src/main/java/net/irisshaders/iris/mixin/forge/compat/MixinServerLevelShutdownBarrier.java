package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCShutdownBarrier;
import fr.hoyatla.pauc.platform.forge.compat.PauCShutdownTiming;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevelShutdownBarrier {
	@Inject(method = "save", at = @At("HEAD"))
	private void pauc$markLevelSaveStart(ProgressListener progressListener, boolean flush, boolean skipSave, CallbackInfo ci) {
		PauCShutdownTiming.pushLevelSaveStart();
		PauCShutdownBarrier.onLevelSaveStart((ServerLevel) (Object) this, flush, skipSave);
	}

	@Inject(method = "save", at = @At("RETURN"))
	private void pauc$markLevelSaveEnd(ProgressListener progressListener, boolean flush, boolean skipSave, CallbackInfo ci) {
		Long startedAt = PauCShutdownTiming.popLevelSaveStart();
		if (startedAt != null) {
			PauCShutdownBarrier.onLevelSaveEnd((ServerLevel) (Object) this, System.currentTimeMillis() - startedAt);
		}
	}
}
