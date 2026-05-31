package fr.hoyatla.pauc.platform.forge;

import fr.hoyatla.pauc.PauCIdentity;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod(PauCIdentity.MOD_ID)
public class PauCUltimateForgeMod {
	public PauCUltimateForgeMod() {
		String version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();
		PauCForgeBootstrap.bootstrap(version);
	}
}
