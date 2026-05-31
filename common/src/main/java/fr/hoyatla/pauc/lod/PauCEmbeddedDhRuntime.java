package fr.hoyatla.pauc.lod;

public final class PauCEmbeddedDhRuntime {
	private static final String DH_API_CLASS = "com.seibel.distanthorizons.api.DhApi";
	private static final String DH_CLIENT_API_CLASS = "com.seibel.distanthorizons.core.api.internal.ClientApi";
	private static volatile Boolean embeddedClassesAvailable;
	private static volatile boolean bootstrapStarted;
	private static volatile boolean initialized;
	private static volatile boolean failed;

	private PauCEmbeddedDhRuntime() {
	}

	public static void markBootstrapStarted() {
		bootstrapStarted = true;
		failed = false;
	}

	public static void markInitialized() {
		bootstrapStarted = true;
		initialized = true;
		failed = false;
	}

	public static void markUnavailable() {
		initialized = false;
		failed = true;
	}

	public static boolean shouldExposeToShaderBridge() {
		return !failed && embeddedClassesAreAvailable();
	}

	public static boolean isInitialized() {
		return initialized;
	}

	public static String describe() {
		return "embeddedDhRuntime[classes=" + embeddedClassesAreAvailable()
			+ ", bootstrapStarted=" + bootstrapStarted
			+ ", initialized=" + initialized
			+ ", failed=" + failed
			+ "]";
	}

	private static boolean embeddedClassesAreAvailable() {
		Boolean cached = embeddedClassesAvailable;
		if (cached != null) {
			return cached;
		}

		boolean available = classExists(DH_API_CLASS) && classExists(DH_CLIENT_API_CLASS);
		embeddedClassesAvailable = available;
		return available;
	}

	private static boolean classExists(String className) {
		try {
			Class.forName(className, false, PauCEmbeddedDhRuntime.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
	}
}
