package net.irisshaders.iris.mixin.shadows;

import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.CullingDataCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Comparator;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer implements CullingDataCache {
	@Shadow
	@Final
	@Mutable
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> renderChunksInFrustum;

	@Shadow
	private double prevCamRotX;

	@Shadow
	private double prevCamRotY;

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$savedRenderChunks = new ObjectArrayList<>(69696);

	@Unique
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> pauc$mainRenderChunksSnapshot = new ObjectArrayList<>(69696);

	@Unique
	private double pauc$savedLastCameraPitch;

	@Unique
	private double pauc$savedLastCameraYaw;

	@Unique
	private static boolean pauc$reportedShadowCullingSwap;

	@Unique
	private static boolean pauc$reportedShadowCullingFallback;

	@Override
	public void saveState() {
		pauc$mainRenderChunksSnapshot = new ObjectArrayList<>(renderChunksInFrustum);
		swap();
	}

	@Override
	public void restoreState() {
		swap();
		pauc$mainRenderChunksSnapshot.clear();
	}

	@Override
	public void useMainCameraChunksIfShadowSetupFailed() {
		if (!renderChunksInFrustum.isEmpty() || pauc$mainRenderChunksSnapshot.isEmpty()) {
			return;
		}

		int availableChunks = pauc$mainRenderChunksSnapshot.size();
		int budget = PauCLodShaderRuntime.shadowFallbackChunkBudget(availableChunks);
		if (budget <= 0) {
			return;
		}

		ObjectArrayList<LevelRenderer.RenderChunkInfo> selectedChunks;
		if (budget >= availableChunks) {
			selectedChunks = new ObjectArrayList<>(pauc$mainRenderChunksSnapshot);
		} else {
			ObjectArrayList<LevelRenderer.RenderChunkInfo> orderedChunks = new ObjectArrayList<>(pauc$mainRenderChunksSnapshot);
			orderedChunks.sort(Comparator.comparingDouble(this::pauc$shadowFallbackDistanceScore));
			selectedChunks = new ObjectArrayList<>(budget);
			for (int index = 0; index < budget && index < orderedChunks.size(); index++) {
				selectedChunks.add(orderedChunks.get(index));
			}
		}

		renderChunksInFrustum = selectedChunks;
		if (!pauc$reportedShadowCullingFallback) {
			pauc$reportedShadowCullingFallback = true;
			Iris.logger.info(
				"PauC restored {} of {} main-camera chunks for the shader shadow pass after shadow terrain setup returned empty; {}",
				renderChunksInFrustum.size(),
				availableChunks,
				PauCLodShaderRuntime.describe()
			);
		}
	}

	@Unique
	private double pauc$shadowFallbackDistanceScore(LevelRenderer.RenderChunkInfo chunkInfo) {
		if (chunkInfo == null || chunkInfo.chunk == null) {
			return Double.MAX_VALUE;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Vec3 cameraPosition = minecraft != null && minecraft.gameRenderer != null
			? minecraft.gameRenderer.getMainCamera().getPosition()
			: Vec3.ZERO;
		BlockPos origin = chunkInfo.chunk.getOrigin();
		double centerX = origin.getX() + 8.0D;
		double centerZ = origin.getZ() + 8.0D;
		return Math.max(Math.abs(centerX - cameraPosition.x), Math.abs(centerZ - cameraPosition.z));
	}

	@Unique
	private void swap() {
		ObjectArrayList<LevelRenderer.RenderChunkInfo> temporaryChunks = renderChunksInFrustum;
		renderChunksInFrustum = pauc$savedRenderChunks;
		pauc$savedRenderChunks = temporaryChunks;

		double temporaryRotation = prevCamRotX;
		prevCamRotX = pauc$savedLastCameraPitch;
		pauc$savedLastCameraPitch = temporaryRotation;

		temporaryRotation = prevCamRotY;
		prevCamRotY = pauc$savedLastCameraYaw;
		pauc$savedLastCameraYaw = temporaryRotation;

		if (!pauc$reportedShadowCullingSwap) {
			pauc$reportedShadowCullingSwap = true;
			Iris.logger.info("PauC preserves the main terrain visibility list across shader shadow passes.");
		}
	}
}
