package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import fr.hoyatla.pauc.lod.PauCDhRenderControl;
import fr.hoyatla.pauc.lodengine.PauCSurfaceWitnessRenderer;
import fr.hoyatla.pauc.shadercompat.PauCShaderCompat;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Decides who OWNS the distant LOD each tick and silences the loser's renderer.
 *
 * <p>When PauC's witness renderer is the active LOD (LODs on, no shaderpack, not deferring), it must be
 * the ONLY one drawing distant terrain: otherwise Distant Horizons draws its opaque LOD on top and
 * OCCLUDES PauC's terrain + tree/structure imposters (the "no trees / no structures / DH-looking LOD"
 * symptom). Goal: "LODs 100% PauC, DH = transitional reference". DH keeps generating — only its RENDER
 * is toggled off while PauC owns the view, and restored when PauC steps aside (shaders / LODs off).
 *
 * <p>EAGER-CLASSLOAD LAW: {@link PauCDhRenderControl} references com.seibel types. Every call into this
 * coordinator MUST be guarded by {@code PauCEmbeddedDhRuntime.isInitialized()} at the call site.
 */
public final class PauCDhRenderCoordinator {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String PAUC_OWNS_LOD_PROPERTY = "pauc.lodengine.paucOwnsLod";
	private static Boolean lastAppliedDhRender; // null = never applied this session

	private PauCDhRenderCoordinator() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (minecraft == null || minecraft.options == null) {
			return;
		}
		boolean paucOwns = PauCTunables.readBoolean(PAUC_OWNS_LOD_PROPERTY, true)
			&& PauCTunables.readBoolean("pauc.lodengine.witnessRenderer", true)
			&& !PauCTunables.readBoolean("pauc.lodengine.deferTerrainToDh", false)
			&& !PauCShaderCompat.isShaderPackInUse()
			&& PauCSurfaceWitnessRenderer.lodRadiusChunks(minecraft.options.getEffectiveRenderDistance()) > 0;
		boolean desiredDhRender = !paucOwns; // PauC owns the LOD => DH must NOT render its terrain
		if (lastAppliedDhRender != null && lastAppliedDhRender == desiredDhRender) {
			return;
		}
		if (PauCDhRenderControl.setRenderingEnabled(desiredDhRender)) {
			lastAppliedDhRender = desiredDhRender;
			LOGGER.info("PauC LOD ownership: DH terrain rendering {} (paucOwnsLod={}).",
				desiredDhRender ? "ENABLED (DH draws)" : "DISABLED (PauC owns the LOD)", paucOwns);
		}
	}

	/** Restore DH's own rendering when the session ends, so DH is left clean for a PauC-less run. */
	public static void onSessionEnd() {
		if (lastAppliedDhRender != null && !lastAppliedDhRender) {
			PauCDhRenderControl.setRenderingEnabled(true);
		}
		lastAppliedDhRender = null;
	}
}
