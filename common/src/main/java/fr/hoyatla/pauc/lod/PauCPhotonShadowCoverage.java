package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;

public final class PauCPhotonShadowCoverage {
	private static final int MIN_RADIUS_CHUNKS = 2;
	private static final int JUNCTION_MARGIN_CHUNKS = 2;
	private static final int TRANSITION_SHADOW_EXTENSION_CHUNKS = 3;

	private PauCPhotonShadowCoverage() {
	}

	public static boolean usesFallbackPhotonShadowCoverage() {
		return PauCLodShaderContext.isShaderPackInUse()
			&& PauCLodShaderProfiles.currentFamily() == PauCLodShaderProfiles.Family.PHOTON
			&& !PauCLodShaderContext.hasScannedDhShadowProgram();
	}

	public static boolean isPhotonShadowCoverageActive() {
		return PauCLodShaderContext.isShaderPackInUse()
			&& PauCLodShaderProfiles.currentFamily() == PauCLodShaderProfiles.Family.PHOTON;
	}

	public static boolean shouldExtendNativeShadowCoverage() {
		return isPhotonShadowCoverageActive() && PauCLodShaderContext.hasScannedDhShadowProgram();
	}

	public static int requestedShadowDistanceChunks() {
		return Math.max(0, ShadowRenderingState.getRequestedShadowTerrainDistanceChunks());
	}

	public static int requestedShadowCoverageRadiusChunks() {
		if (!isPhotonShadowCoverageActive()) {
			return 0;
		}

		int requestedShadowDistanceChunks = requestedShadowDistanceChunks();
		if (requestedShadowDistanceChunks <= 0) {
			return vanillaRenderDistanceChunks();
		}

		return clamp(requestedShadowDistanceChunks, MIN_RADIUS_CHUNKS, maximumShadowCoverageRadiusChunks());
	}

	public static int requestedShadowCoverageDistanceBlocks() {
		return requestedShadowCoverageRadiusChunks() * 16;
	}

	public static int vanillaRenderDistanceChunks() {
		Minecraft minecraft = Minecraft.getInstance();
		int renderDistanceChunks = minecraft != null && minecraft.options != null
			? minecraft.options.getEffectiveRenderDistance()
			: 8;
		return Math.max(MIN_RADIUS_CHUNKS, renderDistanceChunks);
	}

	public static int vanillaShadowCoverageRadiusChunks() {
		return vanillaRenderDistanceChunks() + JUNCTION_MARGIN_CHUNKS;
	}

	public static int minimumUsefulShadowCoverageRadiusChunks() {
		int minimumRadiusChunks = vanillaShadowCoverageRadiusChunks();
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range != null && range.enabled()) {
			// Keep true shadow-map coverage alive a few chunks past the vanilla edge so the first
			// LOD transition band still samples real projected shadows instead of falling straight
			// into Photon SSRT.
			minimumRadiusChunks = Math.max(minimumRadiusChunks, range.lodStartChunk() + TRANSITION_SHADOW_EXTENSION_CHUNKS);
		}
		return clamp(minimumRadiusChunks, MIN_RADIUS_CHUNKS, maximumShadowCoverageRadiusChunks());
	}

	public static int extendShadowTerrainDistanceChunks(int requestedDistanceChunks) {
		if (requestedDistanceChunks <= 0 || !shouldExtendNativeShadowCoverage()) {
			return Math.max(0, requestedDistanceChunks);
		}
		return clamp(
			Math.max(requestedDistanceChunks, minimumUsefulShadowCoverageRadiusChunks()),
			MIN_RADIUS_CHUNKS,
			maximumShadowCoverageRadiusChunks()
		);
	}

	public static double extendShadowTerrainDistanceBlocks(double requestedDistanceBlocks) {
		if (requestedDistanceBlocks <= 0.0D || !shouldExtendNativeShadowCoverage()) {
			return Math.max(0.0D, requestedDistanceBlocks);
		}
		return Math.max(requestedDistanceBlocks, minimumUsefulShadowCoverageRadiusChunks() * 16.0D);
	}

	public static double shadowTerrainDistanceCapBlocks(double vanillaRenderDistanceBlocks) {
		if (!shouldExtendNativeShadowCoverage()) {
			return vanillaRenderDistanceBlocks;
		}
		return Math.max(vanillaRenderDistanceBlocks, minimumUsefulShadowCoverageRadiusChunks() * 16.0D);
	}

	public static int effectiveShadowCoverageRadiusChunks() {
		if (!isPhotonShadowCoverageActive()) {
			return 0;
		}

		int effectiveShadowDistanceChunks = ShadowRenderingState.getEffectiveShadowTerrainDistanceChunks();
		if (effectiveShadowDistanceChunks > 0) {
			return clamp(effectiveShadowDistanceChunks, MIN_RADIUS_CHUNKS, maximumShadowCoverageRadiusChunks());
		}

		int requestedShadowDistanceChunks = requestedShadowDistanceChunks();
		if (requestedShadowDistanceChunks <= 0) {
			return vanillaShadowCoverageRadiusChunks();
		}

		return clamp(requestedShadowDistanceChunks, MIN_RADIUS_CHUNKS, maximumShadowCoverageRadiusChunks());
	}

	public static int effectiveShadowCoverageDistanceBlocks() {
		return effectiveShadowCoverageRadiusChunks() * 16;
	}

	private static int maximumShadowCoverageRadiusChunks() {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range != null && range.enabled()) {
			return Math.max(vanillaShadowCoverageRadiusChunks(), range.roundHorizonEndChunk());
		}
		return vanillaShadowCoverageRadiusChunks();
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
