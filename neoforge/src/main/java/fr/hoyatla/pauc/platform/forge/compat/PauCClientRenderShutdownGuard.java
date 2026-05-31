package fr.hoyatla.pauc.platform.forge.compat;

import fr.hoyatla.pauc.compat.PauCRenderLifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PauCClientRenderShutdownGuard {
	private static final AtomicBoolean CLIENT_LOGOUT_IN_PROGRESS = new AtomicBoolean();
	private static final long DEFAULT_PRE_SHUTDOWN_GUARD_TTL_MS = 45_000L;
	private static final long DEFAULT_LOGOUT_GUARD_TTL_MS = 180_000L;
	private static volatile long lastShutdownRequestAtMillis = -1L;
	private static volatile long autoResetAtMillis = -1L;

	private PauCClientRenderShutdownGuard() {
	}

	public static void onClientLogoutStarted() {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_RENDER_SHUTDOWN_GUARD)) {
			return;
		}

		long now = System.currentTimeMillis();
		CLIENT_LOGOUT_IN_PROGRESS.set(true);
		lastShutdownRequestAtMillis = now;
		long resetDelayMillis = Math.max(10_000L,
			Long.getLong("pauc.compat.clientRenderShutdownMaxMs", DEFAULT_LOGOUT_GUARD_TTL_MS));
		autoResetAtMillis = now + resetDelayMillis;
	}

	public static void onPreShutdownSaveWindowStarted() {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_RENDER_SHUTDOWN_GUARD)) {
			return;
		}
		// Normal integrated-server save/pause windows are frequent and should not arm shutdown mode.
		// We only refresh this guard when a logout/shutdown is already underway.
		if (!CLIENT_LOGOUT_IN_PROGRESS.get() && !PauCRenderLifecycle.isClientLogoutInProgress()) {
			return;
		}

		long now = System.currentTimeMillis();
		CLIENT_LOGOUT_IN_PROGRESS.set(true);
		lastShutdownRequestAtMillis = now;
		long probeDelayMillis = Math.max(5_000L,
			Long.getLong("pauc.compat.clientRenderShutdownProbeMs", DEFAULT_PRE_SHUTDOWN_GUARD_TTL_MS));
		autoResetAtMillis = now + probeDelayMillis;
	}

	public static void onClientSessionResumed() {
		CLIENT_LOGOUT_IN_PROGRESS.set(false);
		lastShutdownRequestAtMillis = -1L;
		autoResetAtMillis = -1L;
	}

	public static boolean isShutdownInProgress() {
		if (!CLIENT_LOGOUT_IN_PROGRESS.get()) {
			return false;
		}

		long autoResetAt = autoResetAtMillis;
		if (autoResetAt > 0L && System.currentTimeMillis() >= autoResetAt) {
			CLIENT_LOGOUT_IN_PROGRESS.set(false);
			lastShutdownRequestAtMillis = -1L;
			autoResetAtMillis = -1L;
			return false;
		}

		return true;
	}

	public static String describeState() {
		boolean active = isShutdownInProgress();
		long requestedAt = lastShutdownRequestAtMillis;
		long activeFor = requestedAt > 0L ? Math.max(0L, System.currentTimeMillis() - requestedAt) : -1L;
		return "clientRenderShutdown[active="
			+ active
			+ ", requestedFor="
			+ (activeFor >= 0L ? activeFor + "ms" : "-")
			+ "]";
	}
}
