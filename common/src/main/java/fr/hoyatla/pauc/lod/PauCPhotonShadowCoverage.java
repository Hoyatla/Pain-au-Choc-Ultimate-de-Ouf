package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;

public final class PauCPhotonShadowCoverage {
	private static final int MIN_RADIUS_CHUNKS = 2;
	private static final int JUNCTION_MARGIN_CHUNKS = 2;

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
