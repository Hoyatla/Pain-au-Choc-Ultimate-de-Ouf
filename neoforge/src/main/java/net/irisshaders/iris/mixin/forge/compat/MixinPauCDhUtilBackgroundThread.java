package net.irisshaders.iris.mixin.forge.compat;

import com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment_forge;
import com.seibel.distanthorizons.core.util.objects.RunOnThisThreadExecutorService;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

@Mixin(Util.class)
public abstract class MixinPauCDhUtilBackgroundThread {
	@Inject(method = "backgroundExecutor", at = @At("HEAD"), cancellable = true)
	private static void pauc$runDhBackgroundTasksInline(CallbackInfoReturnable<ExecutorService> cir) {
		if (BatchGenerationEnvironment_forge.isThisDhWorldGenThread()) {
			cir.setReturnValue(new RunOnThisThreadExecutorService());
		}
	}

	@Inject(method = "wrapThreadWithTaskName(Ljava/lang/String;Ljava/lang/Runnable;)Ljava/lang/Runnable;", at = @At("HEAD"), cancellable = true)
	private static void pauc$keepDhRunnableOnGeneratorThread(String name, Runnable runnable, CallbackInfoReturnable<Runnable> cir) {
		if (BatchGenerationEnvironment_forge.isThisDhWorldGenThread()) {
			cir.setReturnValue(runnable);
		}
	}

	@Inject(method = "wrapThreadWithTaskName(Ljava/lang/String;Ljava/util/function/Supplier;)Ljava/util/function/Supplier;", at = @At("HEAD"), cancellable = true)
	private static void pauc$keepDhSupplierOnGeneratorThread(String name, Supplier<?> supplier, CallbackInfoReturnable<Supplier<?>> cir) {
		if (BatchGenerationEnvironment_forge.isThisDhWorldGenThread()) {
			cir.setReturnValue(supplier);
		}
	}
}
