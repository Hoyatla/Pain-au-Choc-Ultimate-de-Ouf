package net.irisshaders.iris.mixin.forge.compat;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import fr.hoyatla.pauc.platform.forge.client.PauCEmbeddedLodRuntimeDiagnostics;
import fr.hoyatla.pauc.platform.forge.compat.PauCClientRenderShutdownGuard;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = WorldGenerationQueue.class, remap = false)
public abstract class MixinPauCDhWorldGenerationQueue {
	@Unique
	private static final Logger PAUC_LOGGER = LogUtils.getLogger();
	@Unique
	private static boolean pauc$loggedLogoutInterrupt;

	@Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$resetEmbeddedQueueDiagnostics(IDhApiWorldGenerator generator, IDhServerLevel level, CallbackInfo ci) {
		PauCEmbeddedLodRuntimeDiagnostics.resetSession();
		PauCEmbeddedLodRuntimeDiagnostics.captureQueue((WorldGenerationQueue) (Object) this);
	}

	@ModifyVariable(method = "submitRetrievalTask", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 0)
	private byte pauc$preferCoarseDetailWhileFilling(byte requiredDataDetail) {
		return PauCEmbeddedLodRuntimeDiagnostics.adjustRequiredDetailForCoarseFill((WorldGenerationQueue) (Object) this, requiredDataDetail);
	}

	@Inject(method = "submitRetrievalTask", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$trackEmbeddedRetrievalSubmit(long pos, byte requiredDataDetail, CallbackInfoReturnable<CompletableFuture<DataSourceRetrievalResult>> cir) {
		CompletableFuture<DataSourceRetrievalResult> future = cir.getReturnValue();
		if (future == null) {
			return;
		}
		PauCEmbeddedLodRuntimeDiagnostics.onTaskSubmitted((WorldGenerationQueue) (Object) this, pos, requiredDataDetail, future);
	}

	@Inject(method = "startWorldGenTaskGroup", at = @At("HEAD"), remap = false, require = 0)
	private void pauc$trackEmbeddedRetrievalStart(DataSourceRetrievalTask task, CallbackInfo ci) {
		PauCEmbeddedLodRuntimeDiagnostics.onTaskStarted((WorldGenerationQueue) (Object) this, task);
	}

	@Inject(method = "tryQueueNewWorldGenRequestsAsync", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$trackEmbeddedRetrievalQueueTick(CallbackInfo ci) {
		PauCEmbeddedLodRuntimeDiagnostics.captureQueue((WorldGenerationQueue) (Object) this);
	}

	@Inject(method = "close", at = @At("HEAD"), remap = false)
	private void pauc$interruptEmbeddedDhWorldGenOnLogout(CallbackInfo ci) {
		PauCEmbeddedLodRuntimeDiagnostics.captureQueue((WorldGenerationQueue) (Object) this);
		PAUC_LOGGER.info("PauC embedded PL queue diagnostics before close: {}.", PauCEmbeddedLodRuntimeDiagnostics.describeState());

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
		if (!pauc$loggedLogoutInterrupt) {
			pauc$loggedLogoutInterrupt = true;
			PAUC_LOGGER.info("PauC cleared pending embedded DH world generation during client logout to let the world close cleanly.");
		}
	}
}
