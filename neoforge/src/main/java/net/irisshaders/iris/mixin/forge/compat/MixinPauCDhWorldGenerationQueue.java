package net.irisshaders.iris.mixin.forge.compat;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import fr.hoyatla.pauc.platform.forge.compat.PauCClientRenderShutdownGuard;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldGenerationQueue.class, remap = false)
public abstract class MixinPauCDhWorldGenerationQueue {
	@Unique
	private static final Logger PAUC_LOGGER = LogUtils.getLogger();
	@Unique
	private static boolean pauc$loggedLogoutInterrupt;

	@Inject(method = "close", at = @At("HEAD"), remap = false)
	private void pauc$interruptEmbeddedDhWorldGenOnLogout(CallbackInfo ci) {
		if (!PauCRenderLifecycle.isClientLogoutInProgress() && !PauCClientRenderShutdownGuard.isShutdownInProgress()) {
			return;
		}

		try {
			Config.Common.WorldGenerator.enableDistantGeneration.set(false);
		} catch (Throwable ignored) {
		}

		PriorityTaskPicker.Executor worldGenExecutor = ThreadPoolUtil.getWorldGenExecutor();
		if (worldGenExecutor == null || worldGenExecutor.isShutdown()) {
			return;
		}

		worldGenExecutor.clearQueue();
		worldGenExecutor.shutdownNow();
		if (!pauc$loggedLogoutInterrupt) {
			pauc$loggedLogoutInterrupt = true;
			PAUC_LOGGER.info("PauC interrupted embedded DH world generation during client logout to let the world close cleanly.");
		}
	}
}
