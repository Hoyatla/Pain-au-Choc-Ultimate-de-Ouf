package fr.hoyatla.pauc.platform.forge.runtime;

public final class PauCRuntimeSwitches {
	private PauCRuntimeSwitches() {
	}

	public static boolean enabled(String key, boolean fallback) {
		String value = System.getProperty(property(key));
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	public static int readInt(String key, int fallback, int min, int max) {
		String value = System.getProperty(property(key));
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
		String value = System.getProperty(property(key));
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
		String value = System.getProperty(property(key));
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
		String value = System.getProperty(property(key));
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String property(String key) {
		return "pauc.runtime." + key;
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
