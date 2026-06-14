package net.irisshaders.iris.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Set;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_SkipRendering {
	@Unique
	private static final Logger PAUC_LOGGER = LogUtils.getLogger();
	@Unique
	private static final ObjectArrayList<LevelRenderer.RenderChunkInfo> EMPTY_LIST = new ObjectArrayList<>();
	@Unique
	private static boolean pauc$skipOutsideLogoutWarned;

	@Unique
	private static boolean pauc$shouldSkipWorldRendering(IrisRenderingPipeline pipeline) {
		if (!pipeline.skipAllRendering()) {
			return false;
		}

		if (PauCRenderLifecycle.isClientLogoutInProgress() || PauCRenderLifecycle.isClientLogoutPipelineDestroyActive()) {
			return true;
		}

		if (!pauc$skipOutsideLogoutWarned) {
			pauc$skipOutsideLogoutWarned = true;
			PAUC_LOGGER.warn("PauC ignored skipAllRendering outside client logout to preserve world visibility.");
		}
		return false;
	}

	@WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"))
	private boolean skipSetupRender(LevelRenderer instance, Camera camera, Frustum frustum, boolean bl, boolean bl2) {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			return !pauc$shouldSkipWorldRendering(pipeline);
		} else {
			return true;
		}
	}

	@WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V"))
	private boolean skipRenderChunks(LevelRenderer instance, RenderType renderType, PoseStack poseStack, double d, double e, double f, Matrix4f matrix4f) {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			return !pauc$shouldSkipWorldRendering(pipeline);
		} else {
			return true;
		}
	}

	@WrapOperation(method = "renderChunkLayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunksInFrustum:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> cullVanillaTerrainChunks(LevelRenderer instance, Operation<ObjectArrayList<LevelRenderer.RenderChunkInfo>> original) {
		ObjectArrayList<LevelRenderer.RenderChunkInfo> chunks = original.call(instance);
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return chunks;
		}
		return PauCLodRenderCulling.filterVanillaRenderChunks(chunks);
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
	private Iterable<Entity> skipRenderEntities(ClientLevel instance, Operation<Iterable<Entity>> original) {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline && pauc$shouldSkipWorldRendering(pipeline)) {
			return Collections.emptyList();
		} else {
			return original.call(instance);
		}
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunksInFrustum:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<LevelRenderer.RenderChunkInfo> skipLocalBlockEntities(LevelRenderer instance, Operation<ObjectArrayList<LevelRenderer.RenderChunkInfo>> original) {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline && pauc$shouldSkipWorldRendering(pipeline)) {
			return EMPTY_LIST;
		} else {
			return PauCLodRenderCulling.filterVanillaRenderChunks(original.call(instance));
		}
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;globalBlockEntities:Ljava/util/Set;"))
	private Set<BlockEntity> skipGlobalBlockEntities(LevelRenderer instance, Operation<Set<BlockEntity>> original) {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline && pauc$shouldSkipWorldRendering(pipeline)) {
			return Collections.emptySet();
		} else {
			return original.call(instance);
		}
	}
}
