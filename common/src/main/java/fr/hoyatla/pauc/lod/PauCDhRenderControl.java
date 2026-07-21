package fr.hoyatla.pauc.lod;

import com.seibel.distanthorizons.api.DhApi;

/**
 * Toggles Distant Horizons' OWN LOD rendering on/off through the public DH config API.
 *
 * <p>WHY: when both DH and PauC's witness renderer are active, DH draws its opaque LOD terrain and
 * writes depth, OCCLUDING PauC's witness terrain + tree/structure imposters — so the user sees DH's
 * bare LOD (no trees, no structures, DH water/shores) while PauC's richer render is hidden behind it.
 * The project goal is "LODs 100% PauC, DH = transitional reference": when PauC owns the LOD, DH must
 * NOT render its terrain. DH keeps generating (data pipeline untouched) — only its RENDER is silenced.
 *
 * <p>EAGER-CLASSLOAD LAW: this class references {@code com.seibel} types, so it must be invoked ONLY
 * when DH is present (gate at the call site with {@link PauCEmbeddedDhRuntime#isDistantHorizonsPresent()}).
 * Never call these methods without that guard, or a DH-absent pack crashes on classload.
 */
public final class PauCDhRenderControl {

	private PauCDhRenderControl() {
	}

	/** @return DH's current renderingEnabled value, or {@code null} if DH's config is not ready yet. */
	public static Boolean isRenderingEnabled() {
		try {
			if (DhApi.Delayed.configs == null) {
				return null;
			}
			return DhApi.Delayed.configs.graphics().renderingEnabled().getValue();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** Sets DH's renderingEnabled. Safe no-op if DH's config is not ready. @return true if applied. */
	public static boolean setRenderingEnabled(boolean enabled) {
		try {
			if (DhApi.Delayed.configs == null) {
				return false;
			}
			DhApi.Delayed.configs.graphics().renderingEnabled().setValue(enabled);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}
}
