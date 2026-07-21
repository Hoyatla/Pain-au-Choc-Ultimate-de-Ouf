package fr.hoyatla.pauc.platform.forge;

import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.platform.PauCPlatformServices;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;

import java.nio.file.Path;

public final class PauCForgePlatformServices implements PauCPlatformServices {
	@Override
	public boolean isModLoaded(String modId) {
		if (modId == null || modId.isBlank()) {
			return false;
		}

		try {
			if (ModList.get().isLoaded(modId)) {
				return true;
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Fall back to the loading mod list below; it is available earlier in startup.
		}

		try {
			return LoadingModList.get().getModFileById(modId) != null;
		} catch (RuntimeException | LinkageError ignored) {
			return false;
		}
	}

	@Override
	public int loadedModCount() {
		try {
			return ModList.get().getMods().size();
		} catch (RuntimeException | LinkageError ignored) {
			try {
				return LoadingModList.get().getMods().size();
			} catch (RuntimeException | LinkageError ignoredAgain) {
				return -1;
			}
		}
	}

	@Override
	public String getModVersion() {
		try {
			var activeMod = LoadingModList.get().getModFileById(PauCIdentity.MOD_ID);
			if (activeMod != null) {
				return activeMod.versionString();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Keep startup resilient if Forge metadata is temporarily unavailable.
		}

		// P4 (iris-removal): fall back to PauC's OWN build identity, not the vendored Iris version
		// (a relocated-BuildConfig artifact). PauCIdentity reads PauCBuildConfig since P3, so this
		// removes the last HARD fr.hoyatla → net.irisshaders dependency outside the reflective facade.
		return PauCIdentity.runtimeVersion();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.isProduction();
	}

	@Override
	public Path getGameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public Path getConfigDir() {
		return FMLPaths.CONFIGDIR.get();
	}
}
