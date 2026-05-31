package net.irisshaders.iris.platform;

import fr.hoyatla.pauc.PauCIdentity;
import fr.hoyatla.pauc.platform.forge.PauCForgeBootstrap;
import net.irisshaders.iris.Iris;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.nio.file.Path;

public class IrisForgeHelpers implements IrisPlatformHelpers{
	@Override
	public boolean isModLoaded(String modId) {
		var loadingModList = LoadingModList.get();

		if (loadingModList.getModFileById(modId) != null) {
			return true;
		}

		// Never alias external renderer compatibility to this mod itself. Those paths require the PauCor runtime.
		if (PauCIdentity.LEGACY_SODIUM_MOD_ID.equals(modId)) {
			return false;
		}

		return PauCIdentity.isProvidedLegacyModId(modId)
			&& loadingModList.getModFileById(PauCIdentity.MOD_ID) != null;
	}

	@Override
	public String getVersion() {
		var activeMod = LoadingModList.get().getModFileById(PauCIdentity.MOD_ID);
		if (activeMod != null) {
			return activeMod.versionString();
		}

		var legacyMod = LoadingModList.get().getModFileById(PauCIdentity.LEGACY_IRIS_MOD_ID);
		return legacyMod != null ? legacyMod.versionString() : Iris.getVersion();
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

	@Override
	public int compareVersions(String currentVersion, String semanticVersion) throws Exception {
		return new DefaultArtifactVersion(currentVersion).compareTo(new DefaultArtifactVersion(semanticVersion));
	}

	@Override
	public KeyMapping registerKeyBinding(KeyMapping keyMapping) {
		return PauCForgeBootstrap.registerKeyBinding(keyMapping);
	}
}
