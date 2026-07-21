package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * LOD runtime attachment. Until 0.5.x PauC embedded a rebranded Distant Horizons runtime and replicated
 * its Forge initialization here. Since 0.6.0 Distant Horizons is an EXTERNAL mod (CurseForge third-party
 * policy): when present it initializes itself and PauC integrates through the DH API + mixins; when
 * absent the LOD subsystem stays disabled while shaders and the performance core remain fully active.
 */
public final class PauCEmbeddedDhBootstrap {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile boolean initialized;

	private PauCEmbeddedDhBootstrap() {
	}

	public static synchronized void bootstrapClient() {
		if (initialized || !FMLEnvironment.dist.isClient()) {
			return;
		}

		if (ModList.get().isLoaded("distanthorizons")) {
			initialized = true;
			PauCEmbeddedDhRuntime.markBootstrapStarted();
			PauCEmbeddedDhRuntime.markInitialized();
			LOGGER.info("PauC detected the external Distant Horizons mod; using it for the LOD subsystem.");
			PauCEmbeddedDhBridge.registerFacadeHooks();
			PauCEmbeddedDhBridge.applyStartupDirectGpuPolicy();
			return;
		}

		PauCEmbeddedDhRuntime.markUnavailable();
		LOGGER.warn(
			"PauC: Distant Horizons is not installed - the LOD subsystem stays disabled. "
				+ "Install Distant Horizons 3.0.x alongside PauC to enable distant LODs (shaders and the performance core remain active).");
	}
}
