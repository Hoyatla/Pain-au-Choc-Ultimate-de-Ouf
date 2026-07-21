package fr.hoyatla.pauc.lod;

import fr.hoyatla.pauc.PauCTunables;

/**
 * When a LOD field surrounds the player, the vanilla chunk render footprint should be a SQUARE
 * (Chebyshev distance) instead of Sodium's default cylinder (Euclidean). A square vanilla area aligns
 * with the LOD engine's square region/tile grid, so the vanilla-to-LOD seam is a clean square edge
 * rather than a circle-to-grid mismatch — sparing a lot of costly boundary adjustment downstream.
 *
 * <p>State is recomputed once per client tick and read (lock-free) per render section by the Sodium
 * occlusion culler mixin.</p>
 */
public final class PauCSquareRenderDistance {
	private static final String ENABLED_PROPERTY = "pauc.lod.squareRenderDistance";
	private static volatile boolean active;

	private PauCSquareRenderDistance() {
	}

	/** Recompute the square-footprint state (call once per client tick). */
	public static void update() {
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			active = false;
			return;
		}
		boolean lodEngine = PauCTunables.readBoolean("pauc.lodengine.enabled", true);
		PauCLodRange dhRange = PauCLodHorizonState.currentRange();
		boolean dh = PauCEmbeddedDhRuntime.isInitialized() && dhRange != null && dhRange.enabled();
		active = lodEngine || dh;
	}

	public static boolean isActive() {
		return active;
	}
}
