package fr.hoyatla.pauc.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;

public interface PauCPlatformServices {
	PauCPlatformServices INSTANCE = ServiceLoader.load(PauCPlatformServices.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No PauC platform services provider is available."));

	static PauCPlatformServices getInstance() {
		return INSTANCE;
	}

	boolean isModLoaded(String modId);

	default int loadedModCount() {
		return -1;
	}

	String getModVersion();

	boolean isDevelopmentEnvironment();

	Path getGameDir();

	Path getConfigDir();

	default boolean isClassPresent(String className) {
		try {
			Class.forName(className, false, PauCPlatformServices.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
	}
}
