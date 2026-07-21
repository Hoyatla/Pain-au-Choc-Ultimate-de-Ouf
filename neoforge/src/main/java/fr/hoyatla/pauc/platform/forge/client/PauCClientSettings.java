package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistent store for the PLAYER-FACING PauC toggles (the Video Settings buttons). Keys are plain
 * system properties (the tunables the renderer already reads); values persist in
 * {@code config/paucultimate-client.properties} and load into system properties at startup - an
 * explicit -D JVM argument still wins.
 */
public final class PauCClientSettings {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile boolean loaded;

	private PauCClientSettings() {
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("paucultimate-client.properties");
	}

	public static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path path = file();
			if (Files.exists(path)) {
				Properties props = new Properties();
				try (InputStream in = Files.newInputStream(path)) {
					props.load(in);
				}
				for (String key : props.stringPropertyNames()) {
					if (System.getProperty(key) == null) {
						System.setProperty(key, props.getProperty(key));
					}
				}
				LOGGER.info("PauC client settings loaded ({} keys).", props.size());
			}
		} catch (IOException exception) {
			LOGGER.warn("PauC client settings could not be read (defaults keep applying).", exception);
		}
	}

	public static boolean readBoolean(String key, boolean fallback) {
		ensureLoaded();
		String value = System.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	public static void setBoolean(String key, boolean value) {
		ensureLoaded();
		System.setProperty(key, Boolean.toString(value));
		try {
			Properties props = new Properties();
			Path path = file();
			if (Files.exists(path)) {
				try (InputStream in = Files.newInputStream(path)) {
					props.load(in);
				}
			}
			props.setProperty(key, Boolean.toString(value));
			try (OutputStream out = Files.newOutputStream(path)) {
				props.store(out, "PauC player-facing settings (Video Settings buttons)");
			}
		} catch (IOException exception) {
			LOGGER.warn("PauC client settings could not be saved (value applies this session only).", exception);
		}
	}
}
