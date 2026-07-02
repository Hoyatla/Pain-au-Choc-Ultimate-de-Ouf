package fr.hoyatla.pauc;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reader for PauC tunable system properties.
 *
 * <p>{@code System.getProperty} delegates to a globally synchronized {@link java.util.Properties}
 * (Hashtable). PauC hot paths read tunables per frame, per entity, per particle and per mob tick —
 * from the render thread AND server threads at once — so every read contends on that single lock.
 * This cache re-reads each key at most once per ~268ms time bucket and serves lock-free
 * {@link ConcurrentHashMap} hits in between, preserving live tunability (a property changed at
 * runtime is picked up on the next bucket) at zero quality impact.</p>
 */
public final class PauCTunables {
	private static final ConcurrentHashMap<String, Holder> CACHE = new ConcurrentHashMap<>();
	// ~268ms buckets; nanoTime is monotonic and ~25ns per call.
	private static final int BUCKET_SHIFT = 28;

	private PauCTunables() {
	}

	/**
	 * Returns the raw system-property value for {@code key} (or {@code null} when unset), sampled at
	 * most once per time bucket. Callers keep their own default/parse/clamp logic.
	 */
	public static String raw(String key) {
		long bucket = System.nanoTime() >>> BUCKET_SHIFT;
		Holder holder = CACHE.get(key);
		if (holder != null && holder.bucket == bucket) {
			return holder.value;
		}

		String value = System.getProperty(key);
		// Benign race: concurrent refreshes of the same key read the same source value.
		CACHE.put(key, new Holder(bucket, value));
		return value;
	}

	/** Cached boolean tunable with default. */
	public static boolean readBoolean(String key, boolean fallback) {
		String value = raw(key);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	private record Holder(long bucket, String value) {
	}
}
