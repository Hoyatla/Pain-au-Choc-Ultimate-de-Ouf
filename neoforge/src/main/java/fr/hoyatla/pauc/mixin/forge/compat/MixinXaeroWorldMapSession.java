package fr.hoyatla.pauc.mixin.forge.compat;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "xaero.map.WorldMapSession", remap = false)
public abstract class MixinXaeroWorldMapSession {
	@Unique
	private static final Logger PAUC_LOGGER = LogUtils.getLogger();

	@Inject(method = "cleanup", at = @At("HEAD"), cancellable = true, remap = false)
	private void pauc$guardCleanup(CallbackInfo ci) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.XAERO_WORLD_MAP_CLEANUP_GUARD)) {
			return;
		}

		try {
			pauc$cleanupWithWatchdog(this);
			ci.cancel();
		} catch (ReflectiveOperationException | RuntimeException exception) {
			PAUC_LOGGER.warn("PauC failed to apply the Xaero World Map cleanup watchdog. Falling back to the original cleanup path.", exception);
		}
	}

	@Unique
	private static void pauc$cleanupWithWatchdog(Object session) throws ReflectiveOperationException {
		Object processor = pauc$getField(session, "mapProcessor");
		boolean usable = (boolean) pauc$getField(session, "usable");

		if (usable && processor != null) {
			pauc$logXaeroSession("Finalizing world map session...");
			pauc$invoke(processor, "stop");

			Thread mapRunnerThread = (Thread) pauc$getStaticField("xaero.map.WorldMap", "mapRunnerThread");
			if (mapRunnerThread != null) {
				mapRunnerThread.interrupt();
			}

			long timeoutMs = PauCCompatManager.getXaeroWorldMapCleanupTimeoutMs();
			long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
			boolean finished = false;

			while (System.nanoTime() < deadline) {
				finished = (boolean) pauc$invoke(processor, "isFinished");
				if (finished) {
					break;
				}

				if (!pauc$drainLoadingWork(processor)) {
					pauc$sleepBriefly();
				}
			}

			if (!finished) {
				pauc$forceClean(processor);
				PAUC_LOGGER.warn(
					"PauC forced Xaero World Map session finalization after {} ms to avoid a shutdown hang.",
					timeoutMs
				);
			}
		}

		pauc$logXaeroSession("World map session finalized.");
		pauc$invokeStatic("xaero.map.WorldMap", "onSessionFinalized");
		pauc$setField(session, "usable", false);
	}

	@Unique
	private static boolean pauc$drainLoadingWork(Object processor) throws ReflectiveOperationException {
		Object loadingSync = pauc$getField(processor, "loadingSync");
		synchronized (loadingSync) {
			boolean isLoading = (boolean) pauc$getField(processor, "isLoading");
			if (!isLoading) {
				return false;
			}

			Object blockStateShortShapeCache = pauc$getField(processor, "blockStateShortShapeCache");
			if (blockStateShortShapeCache != null) {
				pauc$invoke(blockStateShortShapeCache, "supplyForIOThread");
			}

			Object worldDataHandler = pauc$getField(processor, "worldDataHandler");
			if (worldDataHandler != null) {
				pauc$invoke(worldDataHandler, "handleRenderExecutor");
			}

			return true;
		}
	}

	@Unique
	private static void pauc$forceClean(Object processor) throws ReflectiveOperationException {
		Method forceClean = pauc$getDeclaredMethod(processor.getClass(), "forceClean");
		forceClean.invoke(processor);

		Field stateField = pauc$getDeclaredField(processor.getClass(), "state");
		if (stateField.getInt(processor) == 2) {
			stateField.setInt(processor, 3);
		}
	}

	@Unique
	private static Object pauc$invoke(Object target, String methodName) throws ReflectiveOperationException {
		return pauc$getDeclaredMethod(target.getClass(), methodName).invoke(target);
	}

	@Unique
	private static void pauc$invokeStatic(String className, String methodName) throws ReflectiveOperationException {
		Class<?> targetClass = Class.forName(className);
		pauc$getDeclaredMethod(targetClass, methodName).invoke(null);
	}

	@Unique
	private static Object pauc$getStaticField(String className, String fieldName) throws ReflectiveOperationException {
		Class<?> targetClass = Class.forName(className);
		return pauc$getDeclaredField(targetClass, fieldName).get(null);
	}

	@Unique
	private static Object pauc$getField(Object target, String fieldName) throws ReflectiveOperationException {
		return pauc$getDeclaredField(target.getClass(), fieldName).get(target);
	}

	@Unique
	private static void pauc$setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
		pauc$getDeclaredField(target.getClass(), fieldName).set(target, value);
	}

	@Unique
	private static Field pauc$getDeclaredField(Class<?> owner, String fieldName) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field;
	}

	@Unique
	private static Method pauc$getDeclaredMethod(Class<?> owner, String methodName) throws NoSuchMethodException {
		Method method = owner.getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method;
	}

	@Unique
	private static void pauc$logXaeroSession(String message) {
		PAUC_LOGGER.info("Xaero World Map: {}", message);
	}

	@Unique
	private static void pauc$sleepBriefly() {
		try {
			Thread.sleep(20L);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
