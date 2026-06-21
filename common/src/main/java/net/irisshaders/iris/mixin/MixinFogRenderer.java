package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodFogState;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderPresentation;
import fr.hoyatla.pauc.shader.PauCShaders;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
	@Unique
	private static final Logger PAUC_FOG_LOGGER = LogUtils.getLogger();
	@Unique
	private static long pauc$lastExtendedFogLogMs;
	@Unique
	private static final float PAUC_DISABLED_FOG_START = 5_000_000.0F;
	@Unique
	private static final float PAUC_DISABLED_FOG_END = 10_000_000.0F;

	@Inject(method = "setupFog", at = @At("HEAD"))
	private static void iris$setupLegacyWaterFog(Camera camera, FogRenderer.FogMode $$1, float $$2, boolean $$3, float $$4, CallbackInfo ci) {
		if (camera.getFluidInCamera() == FogType.WATER) {
			Entity entity = camera.getEntity();

			float density = 0.05F;

			if (entity instanceof LocalPlayer localPlayer) {
				density -= localPlayer.getWaterVision() * localPlayer.getWaterVision() * 0.03F;
				Holder<Biome> biome = localPlayer.level().getBiome(localPlayer.blockPosition());

				if (biome.is(BiomeTags.HAS_CLOSER_WATER_FOG)) {
					density += 0.005F;
				}
			}

			CapturedRenderingState.INSTANCE.setFogDensity(density);
		} else {
			CapturedRenderingState.INSTANCE.setFogDensity(-1.0F);
		}
	}

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
		if (!PauCLodClientSettings.isVanillaFogEnabled() && !PauCShaders.isShaderPackInUse()) {
			RenderSystem.setShaderFogStart(PAUC_DISABLED_FOG_START);
			RenderSystem.setShaderFogEnd(PAUC_DISABLED_FOG_END);
			PauCLodFogState.reset();
			return;
		}
		if (!PauCLodHorizonState.shouldExtendVanillaFog()) {
			PauCLodFogState.reset();
			return;
		}

		boolean shaderManagedFog = PauCShaders.isShaderPackInUse();
		if (shaderManagedFog && PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
			PauCLodFogState.reset();
			return;
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		PauCLodFogState.capture(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), range, shaderManagedFog);
		if (shaderManagedFog && !PauCLodShaderContext.shouldApplyFallbackFog()) {
			return;
		}

		PauCLodFogState.Snapshot snapshot = PauCLodFogState.latest();
		RenderSystem.setShaderFogStart(snapshot.extendedFogStartBlocks());
		RenderSystem.setShaderFogEnd(snapshot.extendedFogEndBlocks());
		pauc$logExtendedFog(snapshot, range);
	}

	@Inject(method = "setupColor", at = @At("TAIL"))
	private static void render(Camera camera, float tickDelta, ClientLevel level, int i, float f, CallbackInfo ci) {
		float[] fogColor = RenderSystem.getShaderFogColor();
		CapturedRenderingState.INSTANCE.setFogColor(fogColor[0], fogColor[1], fogColor[2]);
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
