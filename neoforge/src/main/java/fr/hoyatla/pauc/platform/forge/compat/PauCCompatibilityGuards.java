package fr.hoyatla.pauc.platform.forge.compat;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.PauCPortabilityDiagnostics;
import fr.hoyatla.pauc.platform.PauCPlatformServices;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class PauCCompatibilityGuards {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile String lastSummary = "compatGuards[not-run]";

	private PauCCompatibilityGuards() {
	}

	public static void applyEarlyRuntimeGuards() {
		List<String> signals = new ArrayList<>();
		boolean distantHorizons = isLoaded("distanthorizons");
		boolean embeddium = isLoaded("embeddium");
		boolean paucor = isLoaded("paucor")
			|| classPresent("net.caffeinemc.mods.paucor.client.render.PauCorWorldRenderer")
			|| classPresent("fr.hoyatla.paucor.client.render.PauCorWorldRenderer")
			|| classPresent("fr.hoyatla.paucor.client.renderer.PauCorWorldRenderer")
			|| classPresent("net.paucor.client.render.PauCorWorldRenderer");
		boolean rubidium = isLoaded("rubidium");
		boolean oculus = isLoaded("oculus");
		boolean iris = isLoaded("iris") || classPresent("net.irisshaders.iris.Iris");
		boolean optifine = isLoaded("optifine") || isLoaded("optiforge") || classPresent("optifine.Installer");

		addSignal(signals, "dh", distantHorizons);
		addSignal(signals, "embeddium", embeddium);
		addSignal(signals, "paucor", paucor);
		addSignal(signals, "rubidium", rubidium);
		addSignal(signals, "oculus", oculus);
		addSignal(signals, "iris", iris);
		addSignal(signals, "optifine", optifine);

		if (oculus) {
			setDefaultProperty("pauc.lod.conservativeEmbeddedShaderFallback", "true");
			setDefaultProperty("pauc.lod.stickyShaderCompatibility", "false");
			LOGGER.warn("PauC detected Oculus next to the embedded shader stack; enabling conservative DH shader fallback guards.");
		}

		if (optifine) {
			setDefaultProperty("pauc.lod.conservativeEmbeddedShaderFallback", "true");
			setDefaultProperty("pauc.lod.shaderFallbackLateRender", "false");
			LOGGER.warn("PauC detected OptiFine/OptiForge; using conservative LOD shader presentation guards.");
		}

		if (!distantHorizons) {
			LOGGER.info("PauC did not detect Distant Horizons as a loaded mod; LOD bridge paths will stay passive until DH APIs are present.");
		}

		if (!paucor) {
			LOGGER.info("PauC did not detect a PauCor renderer; client frontier warmup will remain passive.");
		}

		lastSummary = "compatGuards[" + String.join(", ", signals) + ", " + PauCPortabilityDiagnostics.describeState() + "]";
		LOGGER.info("PauC compatibility guard summary: {}", lastSummary);
	}

	public static String describeState() {
		return lastSummary;
	}

	private static boolean isLoaded(String modId) {
		return PauCPlatformServices.getInstance().isModLoaded(modId);
	}

	private static boolean classPresent(String className) {
		return PauCPlatformServices.getInstance().isClassPresent(className);
	}

	private static void setDefaultProperty(String key, String value) {
		if (System.getProperty(key) == null) {
			System.setProperty(key, value);
		}
	}

	private static void addSignal(List<String> signals, String name, boolean present) {
		signals.add(name + "=" + present);
	}
}
