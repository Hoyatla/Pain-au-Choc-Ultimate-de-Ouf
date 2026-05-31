package fr.hoyatla.pauc.lod;

public final class PauCLodFogState {
	private static volatile Snapshot latest = Snapshot.empty();

	private PauCLodFogState() {
	}

	public static void capture(float vanillaStartBlocks, float vanillaEndBlocks, PauCLodRange lodRange, boolean shaderManaged) {
		if (lodRange == null || !lodRange.enabled()) {
			latest = Snapshot.empty();
			return;
		}

		latest = new Snapshot(
			vanillaStartBlocks,
			vanillaEndBlocks,
			PauCLodHorizonState.vanillaFogStartBlocks(),
			PauCLodHorizonState.vanillaFogEndBlocks(),
			shaderManaged,
			PauCLodShaderContext.shouldApplyFallbackFog(),
			true
		);
	}

	public static void reset() {
		latest = Snapshot.empty();
	}

	public static Snapshot latest() {
		return latest;
	}

	public record Snapshot(
		float vanillaStartBlocks,
		float vanillaEndBlocks,
		float extendedFogStartBlocks,
		float extendedFogEndBlocks,
		boolean shaderManaged,
		boolean fallbackFog,
		boolean active
	) {
		private static Snapshot empty() {
			return new Snapshot(0.0F, 0.0F, 0.0F, 0.0F, false, false, false);
		}
	}
}
