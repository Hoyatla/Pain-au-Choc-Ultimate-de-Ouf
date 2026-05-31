package fr.hoyatla.pauc.compat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PauCRenderLifecycle {
	private static final AtomicInteger CLIENT_LOGOUT_PIPELINE_DESTROY_DEPTH = new AtomicInteger();
	private static final AtomicBoolean CLIENT_LOGOUT_IN_PROGRESS = new AtomicBoolean();

	private PauCRenderLifecycle() {
	}

	public static void beginClientLogoutPipelineDestroy() {
		CLIENT_LOGOUT_PIPELINE_DESTROY_DEPTH.incrementAndGet();
	}

	public static void endClientLogoutPipelineDestroy() {
		CLIENT_LOGOUT_PIPELINE_DESTROY_DEPTH.updateAndGet(current -> Math.max(0, current - 1));
	}

	public static boolean isClientLogoutPipelineDestroyActive() {
		return CLIENT_LOGOUT_PIPELINE_DESTROY_DEPTH.get() > 0;
	}

	public static void onClientLogoutStarted() {
		CLIENT_LOGOUT_IN_PROGRESS.set(true);
	}

	public static void onClientSessionResumed() {
		CLIENT_LOGOUT_IN_PROGRESS.set(false);
	}

	public static boolean isClientLogoutInProgress() {
		return CLIENT_LOGOUT_IN_PROGRESS.get();
	}
}
