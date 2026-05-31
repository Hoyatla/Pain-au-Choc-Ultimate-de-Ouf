package fr.hoyatla.pauc.platform.forge.compat;

public final class PauCShutdownTiming {
	private static final ThreadLocal<Long> SERVER_SAVE_START = new ThreadLocal<>();
	private static final ThreadLocal<Long> LEVEL_SAVE_START = new ThreadLocal<>();

	private PauCShutdownTiming() {
	}

	public static void pushServerSaveStart() {
		SERVER_SAVE_START.set(System.currentTimeMillis());
	}

	public static Long popServerSaveStart() {
		Long startedAt = SERVER_SAVE_START.get();
		SERVER_SAVE_START.remove();
		return startedAt;
	}

	public static void pushLevelSaveStart() {
		LEVEL_SAVE_START.set(System.currentTimeMillis());
	}

	public static Long popLevelSaveStart() {
		Long startedAt = LEVEL_SAVE_START.get();
		LEVEL_SAVE_START.remove();
		return startedAt;
	}
}
