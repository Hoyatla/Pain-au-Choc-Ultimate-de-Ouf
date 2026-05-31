package fr.hoyatla.pauc.platform.forge;

import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatEventBridge;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatibilityGuards;
import fr.hoyatla.pauc.platform.forge.client.PauCEmbeddedDhBootstrap;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.worldgen.PauCWorldgenEventBridge;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
		PauCCompatibilityGuards.applyEarlyRuntimeGuards();
		PauCCompatManager.bootstrap();
		PauCScheduler.bootstrap();
		PauCEmbeddedDhBootstrap.bootstrapClient();
		bootstrapOptionalPauCorBridge(modVersion);
		ModLoadingContext.get().registerExtensionPoint(
			ConfigScreenHandler.ConfigScreenFactory.class,
			() -> new ConfigScreenHandler.ConfigScreenFactory((mc, screen) -> new ShaderPackScreen(screen))
		);
		FMLJavaModLoadingContext.get().getModEventBus().addListener(PauCForgeBootstrap::registerKeys);
		MinecraftForge.EVENT_BUS.register(COMPAT_EVENT_BRIDGE);
		MinecraftForge.EVENT_BUS.register(WORLDGEN_EVENT_BRIDGE);
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
