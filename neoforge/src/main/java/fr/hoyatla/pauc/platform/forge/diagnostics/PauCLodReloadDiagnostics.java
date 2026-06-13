package fr.hoyatla.pauc.platform.forge.diagnostics;

import java.util.concurrent.atomic.AtomicLong;

public final class PauCLodReloadDiagnostics {
	private static final AtomicLong signatureChanges = new AtomicLong();
	private static final AtomicLong shaderRuntimeChanges = new AtomicLong();
	private static final AtomicLong qualityOnlyChanges = new AtomicLong();
	private static final AtomicLong presentationOnlyChanges = new AtomicLong();
	private static final AtomicLong cacheClears = new AtomicLong();
	private static final AtomicLong cacheClearAvoided = new AtomicLong();
	private static final AtomicLong cacheClearDeferred = new AtomicLong();
	private static final AtomicLong cacheClearDisabled = new AtomicLong();
	private static final AtomicLong coarseRefreshes = new AtomicLong();
	private static final AtomicLong coarseRefreshSkips = new AtomicLong();
	private static final AtomicLong restoresQueued = new AtomicLong();
	private static final AtomicLong restoresApplied = new AtomicLong();
	private static final AtomicLong swaps = new AtomicLong();

	private PauCLodReloadDiagnostics() {
	}

	public static void reset() {
		signatureChanges.set(0L);
		shaderRuntimeChanges.set(0L);
		qualityOnlyChanges.set(0L);
		presentationOnlyChanges.set(0L);
		cacheClears.set(0L);
		cacheClearAvoided.set(0L);
		cacheClearDeferred.set(0L);
		cacheClearDisabled.set(0L);
		coarseRefreshes.set(0L);
		coarseRefreshSkips.set(0L);
		restoresQueued.set(0L);
		restoresApplied.set(0L);
		swaps.set(0L);
	}

	public static void onSignatureChange(boolean shaderRuntimeChange, boolean qualityOnlyChange, boolean presentationOnlyChange) {
		signatureChanges.incrementAndGet();
		if (shaderRuntimeChange) {
			shaderRuntimeChanges.incrementAndGet();
		}
		if (qualityOnlyChange) {
			qualityOnlyChanges.incrementAndGet();
		}
		if (presentationOnlyChange) {
			presentationOnlyChanges.incrementAndGet();
		}
	}

	public static void onCacheClearExecuted() {
		cacheClears.incrementAndGet();
	}

	public static void onCacheClearAvoided() {
		cacheClearAvoided.incrementAndGet();
	}

	public static void onCacheClearDeferred() {
		cacheClearDeferred.incrementAndGet();
	}

	public static void onCacheClearDisabled() {
		cacheClearDisabled.incrementAndGet();
	}

	public static void onCoarseRefreshRequested(boolean executed) {
		if (executed) {
			coarseRefreshes.incrementAndGet();
		} else {
			coarseRefreshSkips.incrementAndGet();
		}
	}

	public static void onRestoreQueued() {
		restoresQueued.incrementAndGet();
	}

	public static void onRestoreApplied() {
		restoresApplied.incrementAndGet();
	}

	public static void onSwap() {
		swaps.incrementAndGet();
	}

	public static long signatureChanges() {
		return signatureChanges.get();
	}

	public static long shaderRuntimeChanges() {
		return shaderRuntimeChanges.get();
	}

	public static long qualityOnlyChanges() {
		return qualityOnlyChanges.get();
	}

	public static long presentationOnlyChanges() {
		return presentationOnlyChanges.get();
	}

	public static long cacheClears() {
		return cacheClears.get();
	}

	public static long cacheClearAvoided() {
		return cacheClearAvoided.get();
	}

	public static long cacheClearDeferred() {
		return cacheClearDeferred.get();
	}

	public static long cacheClearDisabled() {
		return cacheClearDisabled.get();
	}

	public static long coarseRefreshes() {
		return coarseRefreshes.get();
	}

	public static long coarseRefreshSkips() {
		return coarseRefreshSkips.get();
	}

	public static long restoresQueued() {
		return restoresQueued.get();
	}

	public static long restoresApplied() {
		return restoresApplied.get();
	}

	public static long restores() {
		return restoresApplied();
	}

	public static long swaps() {
		return swaps.get();
	}

	public static String describeState() {
		return "lodReload[changes="
			+ signatureChanges()
			+ ", shaderRuntime="
			+ shaderRuntimeChanges()
			+ ", qualityOnly="
			+ qualityOnlyChanges()
			+ ", presentationOnly="
			+ presentationOnlyChanges()
			+ ", clears="
			+ cacheClears()
			+ ", avoided="
			+ cacheClearAvoided()
			+ ", deferred="
			+ cacheClearDeferred()
			+ ", disabled="
			+ cacheClearDisabled()
			+ ", restoresQueued="
			+ restoresQueued()
			+ ", restores="
			+ restores()
			+ ", swaps="
			+ swaps()
			+ ", coarseRefreshes="
			+ coarseRefreshes()
			+ ", coarseSkips="
			+ coarseRefreshSkips()
			+ "]";
	}
}
