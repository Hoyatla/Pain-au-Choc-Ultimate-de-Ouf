package fr.hoyatla.pauc.platform;

public final class PauCPortabilityDiagnostics {
	private PauCPortabilityDiagnostics() {
	}

	public static String describeState() {
		PauCPlatformServices platform = PauCPlatformServices.getInstance();
		boolean forgeRuntime = platform.isClassPresent("net.minecraftforge.fml.loading.FMLLoader");
		boolean neoForgeRuntime = platform.isClassPresent("net.neoforged.fml.loading.FMLLoader");
		String loader = neoForgeRuntime ? "neoforge" : forgeRuntime ? "forge" : "unknown";
		return "portability[loader="
			+ loader
			+ ", provider="
			+ platform.getClass().getName()
			+ ", serviceLoader=true"
			+ ", forgeRuntime="
			+ forgeRuntime
			+ ", neoForgeRuntime="
			+ neoForgeRuntime
			+ ", releaseSafety=scan-enforced"
			+ "]";
	}
}
