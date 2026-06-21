package fr.hoyatla.pauc.platform.forge;

import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.lod.PauCLodCudaBridge;
import fr.hoyatla.pauc.platform.forge.client.PauCCudaWorker;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatEventBridge;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatibilityGuards;
import fr.hoyatla.pauc.platform.forge.client.PauCEmbeddedDhBootstrap;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.worldgen.PauCWorldgenEventBridge;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class PauCForgeBootstrap {
	private static final Logger LOGGER = LoggerFactory.getLogger(PauCForgeBootstrap.class);
	private static final String[] PAUCOR_FORGE_BOOTSTRAP_CLASSES = {
		"net.caffeinemc.mods.paucor.neoforge.PauCorForgeMod",
		"fr.hoyatla.paucor.neoforge.PauCorForgeMod",
		"net.paucor.neoforge.PauCorForgeMod"
	};
	private static final String PAUCOR_FORGE_BOOTSTRAP_METHOD = "bootstrap";
	private static final List<KeyMapping> KEY_MAPPINGS = new ArrayList<>();
	private static final PauCCompatEventBridge COMPAT_EVENT_BRIDGE = new PauCCompatEventBridge();
	private static final PauCWorldgenEventBridge WORLDGEN_EVENT_BRIDGE = new PauCWorldgenEventBridge();
	private static boolean initialized;
	private static boolean paucorBootstrapUnavailableLogged;

	private PauCForgeBootstrap() {
	}

	public static void bootstrap(String modVersion) {
		if (initialized) {
			return;
		}

		initialized = true;
		PauCIdentity.setRuntimeVersion(modVersion);
		loadDevPropertyOverrides();
		LOGGER.info(
			"PauC bootstrap: modVersion={}, buildId={}, gitHash={}.",
			PauCIdentity.runtimeVersion(),
			PauCIdentity.buildId(),
			PauCIdentity.buildGitHash()
		);
		PauCCompatibilityGuards.applyEarlyRuntimeGuards();
		PauCCompatManager.bootstrap();
		PauCScheduler.bootstrap();
		PauCEmbeddedDhBootstrap.bootstrapClient();
		PauCLodCudaBridge.registerSeamHeightAverager(PauCCudaWorker::averageSeamHeights);
		bootstrapOptionalPauCorBridge(modVersion);
		FMLJavaModLoadingContext.get().getModEventBus().addListener(PauCForgeBootstrap::registerKeys);
		MinecraftForge.EVENT_BUS.register(COMPAT_EVENT_BRIDGE);
		MinecraftForge.EVENT_BUS.register(WORLDGEN_EVENT_BRIDGE);
	}

	// Loads config/paucultimate-dev.properties at startup and pushes its "pauc.*" keys into the system properties, so dev
	// toggles documented in that file actually take effect. Real -D JVM args win (we only set keys not already present).
	// Called before the compat guards and the shader pipeline so overrides are visible everywhere downstream.
	private static void loadDevPropertyOverrides() {
		Path devFile = FMLPaths.CONFIGDIR.get().resolve(PauCIdentity.MOD_ID + "-dev.properties");
		if (!Files.isRegularFile(devFile)) {
			return;
		}

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(devFile, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException exception) {
			LOGGER.warn("PauC could not read dev overrides from {}.", devFile, exception);
			return;
		}

		int applied = 0;
		for (String key : properties.stringPropertyNames()) {
			if (!key.startsWith("pauc.") || System.getProperty(key) != null) {
				continue;
			}
			System.setProperty(key, properties.getProperty(key));
			applied++;
		}

		if (applied > 0) {
			LOGGER.info("PauC loaded {} dev override(s) from {} into system properties.", applied, devFile.getFileName());
		}
	}

	public static KeyMapping registerKeyBinding(KeyMapping keyMapping) {
		KEY_MAPPINGS.add(keyMapping);
		return keyMapping;
	}

	private static void registerKeys(RegisterKeyMappingsEvent event) {
		KEY_MAPPINGS.forEach(event::register);
		KEY_MAPPINGS.clear();
	}

	private static void bootstrapOptionalPauCorBridge(String modVersion) {
		for (String bootstrapClassName : PAUCOR_FORGE_BOOTSTRAP_CLASSES) {
			try {
				Class<?> bootstrapClass = Class.forName(bootstrapClassName);
				bootstrapClass.getMethod(PAUCOR_FORGE_BOOTSTRAP_METHOD, String.class).invoke(null, modVersion);
				return;
			} catch (ClassNotFoundException | NoClassDefFoundError ignored) {
				// Try the next PauCor bootstrap candidate.
			} catch (ReflectiveOperationException | LinkageError error) {
				LOGGER.warn("Optional PauCor bridge failed to initialize; continuing without external bridge bootstrap.", error);
				return;
			}
		}

		if (!paucorBootstrapUnavailableLogged) {
			paucorBootstrapUnavailableLogged = true;
			LOGGER.info("Optional PauCor bridge was not found; continuing without external bridge bootstrap.");
		}
	}
}
