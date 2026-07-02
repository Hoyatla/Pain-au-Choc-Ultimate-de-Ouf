package fr.hoyatla.pauc.platform.forge.runtime;

import fr.hoyatla.pauc.PauCTunables;

import java.util.concurrent.ConcurrentHashMap;

public final class PauCRuntimeSwitches {
	// Avoids re-allocating the "pauc.runtime." + key concatenation on every per-mob/per-tick read.
	private static final ConcurrentHashMap<String, String> PREFIXED_KEYS = new ConcurrentHashMap<>();

	private PauCRuntimeSwitches() {
	}

	public static boolean enabled(String key, boolean fallback) {
		String value = PauCTunables.raw(property(key));
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	public static int readInt(String key, int fallback, int min, int max) {
		String value = PauCTunables.raw(property(key));
		if (value == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Integer.parseInt(value), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	public static long readLong(String key, long fallback, long min, long max) {
		String value = PauCTunables.raw(property(key));
		if (value == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Long.parseLong(value), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	public static double readDouble(String key, double fallback, double min, double max) {
		String value = PauCTunables.raw(property(key));
		if (value == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Double.parseDouble(value), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	public static String readString(String key, String fallback) {
		String value = PauCTunables.raw(property(key));
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String property(String key) {
		String prefixed = PREFIXED_KEYS.get(key);
		if (prefixed == null) {
			prefixed = "pauc.runtime." + key;
			PREFIXED_KEYS.put(key, prefixed);
		}
		return prefixed;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
