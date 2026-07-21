package fr.hoyatla.pauc.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodFogState;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderPresentation;
import fr.hoyatla.pauc.shadercompat.PauCShaderCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PauC vanilla-fog management (extracted from the vendored shader tree — pure PauC, shader mods
 * queried only through {@link PauCShaderCompat}): the player's vanilla-fog toggle, the LOD-engine fog
 * relocation to the horizon fade band (TERRAIN fog only — the SKY fog dome overhead is a video
 * setting the player owns), and the legacy DH-era extended-fog path.
 */
@Mixin(FogRenderer.class)
public class MixinPauCFogExtension {
	@Unique
	private static final Logger PAUC_FOG_LOGGER = LogUtils.getLogger();
	@Unique
	private static long pauc$lastExtendedFogLogMs;
	@Unique
	private static final float PAUC_DISABLED_FOG_START = 5_000_000.0F;
	@Unique
	private static final float PAUC_DISABLED_FOG_END = 10_000_000.0F;

	@Inject(method = "setupFog", at = @At("TAIL"))
	private static void pauc$extendVanillaFogForLodHorizon(Camera camera, FogRenderer.FogMode fogMode, float viewDistance, boolean thickFog, float tickDelta, CallbackInfo ci) {
		// Never touch underwater/lava fog — that fog is a gameplay/safety cue, not the distance dome.
		if (camera.getFluidInCamera() != FogType.NONE) {
			PauCLodFogState.reset();
			return;
		}
		// Optional toggle: disable the vanilla distance fog "dome" by pushing the fog distances far beyond the
		// view. IMPORTANT: only when NO shader pack is active. A shader pack owns its own atmospheric fog and
		// reads these same fog uniforms, so we must never touch them while a shader is in use — the toggle is
		// strictly a vanilla/shaderless fog control and must not remove the shader's fog.
		if (!PauCLodClientSettings.isVanillaFogEnabled() && !PauCShaderCompat.isShaderPackInUse()) {
			RenderSystem.setShaderFogStart(PAUC_DISABLED_FOG_START);
			RenderSystem.setShaderFogEnd(PAUC_DISABLED_FOG_END);
			PauCLodFogState.reset();
			return;
		}
		// PauC LOD ENGINE horizon: while the engine is drawing terrain far beyond vanilla, the TERRAIN fog
		// RELOCATES to the LOD field's outer fade band (it is NOT removed): the dome still closes the view
		// at the true horizon and still follows the render-distance gauge. Keeping it at the vanilla edge
		// would fade chunks in the MIDDLE of the visible field (the sky-coloured "moat"). STRICTLY
		// FOG_TERRAIN: the SKY fog pass draws the dome overhead, a video-settings visual the player owns.
		// Player keeps control: pauc.lodengine.extendVanillaFog=false restores stock vanilla fog, the
		// vanilla-fog toggle above still wins, band width via pauc.lodengine.fogWidthChunks.
		if (fogMode == FogRenderer.FogMode.FOG_TERRAIN
			&& !PauCShaderCompat.isShaderPackInUse()
			&& fr.hoyatla.pauc.PauCTunables.readBoolean("pauc.lodengine.extendVanillaFog", true)) {
			float lodEngineFogStart = fr.hoyatla.pauc.lodengine.PauCSurfaceWitnessRenderer.vanillaFogStartBlocksForLodEngine();
			if (lodEngineFogStart > 0.0F) {
				RenderSystem.setShaderFogStart(lodEngineFogStart);
				RenderSystem.setShaderFogEnd(fr.hoyatla.pauc.lodengine.PauCSurfaceWitnessRenderer.vanillaFogEndBlocksForLodEngine());
				PauCLodFogState.reset();
				return;
			}
		}
		if (!PauCLodHorizonState.shouldExtendVanillaFog()) {
			PauCLodFogState.reset();
			return;
		}

		boolean shaderManagedFog = PauCShaderCompat.isShaderPackInUse();
		if (shaderManagedFog && PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
			PauCLodFogState.reset();
			return;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		PauCLodFogState.capture(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), range, shaderManagedFog);
		if (shaderManagedFog
			&& !PauCLodShaderContext.shouldApplyFallbackFog()
			&& !PauCLodShaderContext.shouldApplyLodHorizonFogForNoDhFogPack()) {
			return;
		}

		PauCLodFogState.Snapshot snapshot = PauCLodFogState.latest();
		RenderSystem.setShaderFogStart(snapshot.extendedFogStartBlocks());
		RenderSystem.setShaderFogEnd(snapshot.extendedFogEndBlocks());
		pauc$logExtendedFog(snapshot, range);
	}

	@Unique
	private static void pauc$logExtendedFog(PauCLodFogState.Snapshot snapshot, PauCLodRange range) {
		long now = System.currentTimeMillis();
		if (now - pauc$lastExtendedFogLogMs < 5000L) {
			return;
		}

		pauc$lastExtendedFogLogMs = now;
		PAUC_FOG_LOGGER.info(
			"PauC extended fog horizon: vanilla={}..{} chunks, applied={}..{} chunks, shaderManaged={}, fallback={}, {}",
			pauc$blocksToChunks(snapshot.vanillaStartBlocks()),
			pauc$blocksToChunks(snapshot.vanillaEndBlocks()),
			pauc$blocksToChunks(snapshot.extendedFogStartBlocks()),
			pauc$blocksToChunks(snapshot.extendedFogEndBlocks()),
			snapshot.shaderManaged(),
			snapshot.fallbackFog(),
			range.describe()
		);
	}

	@Unique
	private static int pauc$blocksToChunks(float blocks) {
		return Math.round(blocks / 16.0F);
	}
}
