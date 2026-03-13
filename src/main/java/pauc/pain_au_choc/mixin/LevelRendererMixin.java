package pauc.pain_au_choc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import pauc.pain_au_choc.ChunkBuildQueueController;
import pauc.pain_au_choc.PauCPipeline;
import pauc.pain_au_choc.EntityLodBillboardRenderer;
import pauc.pain_au_choc.TerrainProxyController;
import pauc.pain_au_choc.render.PauCWorldRenderer;
import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;
import pauc.pain_au_choc.render.shader.WorldRenderingPhase;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow private ClientLevel level;
    @Shadow private int ticks;

    @Unique
    private boolean pauc$lastChunkScheduled;

    // ================================================================
    // PAUC Embeddium-like pipeline hooks (Phase 1.6)
    // ================================================================

    /**
     * Initialize PauCWorldRenderer when Minecraft creates the LevelRenderer.
     * This ensures the singleton is ready before any world is loaded.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void pauc$onInit(Minecraft minecraft, net.minecraft.client.renderer.entity.EntityRenderDispatcher entityRenderDispatcher,
                              net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                              net.minecraft.client.renderer.RenderBuffers renderBuffers, CallbackInfo ci) {
        new PauCWorldRenderer(minecraft);
    }

    /**
     * When the world changes, notify PauCWorldRenderer.
     */
    @Inject(method = "setLevel", at = @At("RETURN"))
    private void pauc$onSetLevel(ClientLevel world, CallbackInfo ci) {
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null) {
            renderer.setWorld(world);
        }
    }

    /**
     * When the renderer is reloaded (resource packs, render distance, etc.),
     * notify PauCWorldRenderer.
     */
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void pauc$onAllChanged(CallbackInfo ci) {
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null) {
            renderer.reload();
        }
    }

    /**
     * Hook into setupRender to run PAUC's visibility/culling before vanilla.
     * We inject BEFORE the vanilla setupRender body to run our occlusion culler
     * and schedule chunk builds.
     */
    @Inject(
            method = "setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
            at = @At("HEAD")
    )
    private void pauc$onSetupRender(Camera camera, Frustum frustum, boolean hasForcedFrustum,
                                     boolean isSpectator, CallbackInfo ci) {
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null) {
            renderer.setupTerrain(camera, frustum, this.ticks, isSpectator, false);
        }
    }

    // ================================================================
    // Deferred shader pipeline hooks (Phase 2)
    // ================================================================

    /**
     * Begin the deferred pipeline at the start of world rendering.
     * Initializes uniforms and prepares GBuffer pass if a shaderpack is active.
     */
    @Inject(
            method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD")
    )
    private void pauc$beginDeferredPipeline(PoseStack poseStack, float partialTick, long finishNanoTime,
                                             boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                             LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            pipeline.beginWorldRendering(camera, poseStack.last().pose(), projectionMatrix, partialTick);
            // Execute shadow pass — renders terrain + entities from light's perspective
            pipeline.renderShadows(camera, partialTick);
            // Begin GBuffer geometry pass
            pipeline.beginGBufferPass();
        }
    }

    /**
     * After all geometry is rendered, run deferred + composite + final passes.
     */
    @Inject(
            method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN")
    )
    private void pauc$endDeferredPipeline(PoseStack poseStack, float partialTick, long finishNanoTime,
                                           boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                           LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            // End GBuffer geometry pass
            pipeline.endGBufferPass();

            // Run deferred lighting passes
            pipeline.runDeferredPasses();

            // Run composite post-processing passes
            pipeline.runCompositePasses();

            // Run final tone-mapping pass to screen
            pipeline.runFinalPass();

            pipeline.endWorldRendering();
        }
    }

    /**
     * Set shader rendering phase for terrain chunks.
     */
    @Inject(
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            at = @At("HEAD")
    )
    private void pauc$setTerrainPhase(RenderType renderType, PoseStack poseStack, double camX, double camY,
                                       double camZ, Matrix4f projectionMatrix, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            // Set appropriate GBuffer phase based on render type
            if (renderType == RenderType.solid()) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_SOLID);
            } else if (renderType == RenderType.cutout() || renderType == RenderType.cutoutMipped()) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_CUTOUT);
            } else if (renderType == RenderType.translucent()) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_TRANSLUCENT);
            }
        }
    }

    /**
     * Set entity rendering phase for shader programs.
     * This allows shaderpacks to use gbuffers_entities program.
     */
    @Inject(
            method = "renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD")
    )
    private void pauc$setEntityPhase(Entity entity, double camX, double camY, double camZ,
                                      float partialTick, PoseStack poseStack,
                                      MultiBufferSource multiBufferSource, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            // Glowing entities use a different program in OptiFine spec
            if (entity.isCurrentlyGlowing()) {
                pipeline.setPhase(WorldRenderingPhase.ENTITIES);
            } else {
                pipeline.setPhase(WorldRenderingPhase.ENTITIES);
            }
        }
    }

    /**
     * Set sky rendering phase for shader programs.
     */
    @Inject(
            method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD")
    )
    private void pauc$setSkyPhase(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
                                    Camera camera, boolean foggy, Runnable fogSetup, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            pipeline.setPhase(WorldRenderingPhase.SKY);
        }
    }

    /**
     * Set weather rendering phase for shader programs.
     */
    @Inject(
            method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("HEAD")
    )
    private void pauc$setWeatherPhase(LightTexture lightTexture, float partialTick,
                                        double camX, double camY, double camZ, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            pipeline.setPhase(WorldRenderingPhase.WEATHER);
        }
    }

    /**
     * Set cloud rendering phase for shader programs.
     */
    @Inject(
            method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("HEAD")
    )
    private void pauc$setCloudPhase(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
                                      double camX, double camY, double camZ, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            pipeline.setPhase(WorldRenderingPhase.CLOUDS);
        }
    }

    /**
     * Reset phase after entity rendering completes (return from renderEntity).
     */
    @Inject(
            method = "renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("RETURN")
    )
    private void pauc$resetEntityPhase(Entity entity, double camX, double camY, double camZ,
                                        float partialTick, PoseStack poseStack,
                                        MultiBufferSource multiBufferSource, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            // Reset to the previous terrain phase — entities are rendered interleaved with terrain
            pipeline.setPhase(WorldRenderingPhase.NONE);
        }
    }

    /**
     * Set block entity rendering phase for shader programs.
     * This allows shaderpacks to use gbuffers_block program.
     */
    @Inject(
            method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
            )
    )
    private void pauc$setBlockEntityPhase(PoseStack poseStack, float partialTick, long finishNanoTime,
                                            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                            LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            pipeline.setPhase(WorldRenderingPhase.BLOCK_ENTITIES);
        }
    }

    // ================================================================
    // Original PAUC hooks (sky, clouds, weather, entities, etc.)
    // ================================================================

    @Inject(
            method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$simplifySky(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean foggy, Runnable fogSetup, CallbackInfo callbackInfo) {
        if (PauCPipeline.shouldRenderSkyMesh()) {
            return;
        }

        fogSetup.run();
        callbackInfo.cancel();
    }

    @Inject(
            method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$simplifyClouds(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double camX, double camY, double camZ, CallbackInfo callbackInfo) {
        if (!PauCPipeline.shouldRenderClouds()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$simplifyWeather(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo callbackInfo) {
        if (!PauCPipeline.shouldRenderWeather()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$simplifyChunkLayers(RenderType renderType, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f projectionMatrix, CallbackInfo callbackInfo) {
        // First check: PAUC budget may skip this layer entirely
        if (!PauCPipeline.shouldRenderChunkLayer(renderType)) {
            callbackInfo.cancel();
            return;
        }

        // Second check: redirect through PAUC's Embeddium-like GPU renderer
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null && renderer.getSectionManager() != null) {
            renderer.drawChunkLayer(renderType, poseStack.last().pose(), camX, camY, camZ);
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void pauc$renderTerrainProxy(
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo callbackInfo
    ) {
        TerrainProxyController.render(poseStack, projectionMatrix, camera, partialTick);
    }

    @Inject(
            method = "renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauc$simplifyEntities(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack, MultiBufferSource multiBufferSource, CallbackInfo callbackInfo) {
        if (!PauCPipeline.shouldRenderEntity(entity)) {
            callbackInfo.cancel();
            return;
        }

        if (!PauCPipeline.shouldRenderEntityThisFrame(entity)) {
            callbackInfo.cancel();
            return;
        }

        if (PauCPipeline.shouldRenderEntityAsBillboard(entity)) {
            EntityLodBillboardRenderer.render(entity, camX, camY, camZ, poseStack, multiBufferSource);
            callbackInfo.cancel();
        }
    }

    @Redirect(
            method = "renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
            )
    )
    private void pauc$applyEntityLodPartialTick(EntityRenderDispatcher entityRenderDispatcher, Entity entity, double x, double y, double z, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource multiBufferSource, int packedLight) {
        float lodPartialTick = PauCPipeline.remapEntityPartialTick(entity, partialTick);
        entityRenderDispatcher.render(entity, x, y, z, yaw, lodPartialTick, poseStack, multiBufferSource, packedLight);
    }

    @Inject(
            method = "compileChunks(Lnet/minecraft/client/Camera;)V",
            at = @At("HEAD")
    )
    private void pauc$beginChunkCompileBudget(Camera camera, CallbackInfo callbackInfo) {
        ChunkBuildQueueController.beginCompilePass();
        this.pauc$lastChunkScheduled = false;
    }

    @Redirect(
            method = "compileChunks(Lnet/minecraft/client/Camera;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk;rebuildChunkAsync(Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"
            )
    )
    private void pauc$applyChunkCompileBackPressure(ChunkRenderDispatcher.RenderChunk renderChunk, ChunkRenderDispatcher chunkRenderDispatcher, RenderRegionCache renderRegionCache) {
        if (ChunkBuildQueueController.consumeBuildSlot()) {
            renderChunk.rebuildChunkAsync(chunkRenderDispatcher, renderRegionCache);
            this.pauc$lastChunkScheduled = true;
            return;
        }

        this.pauc$lastChunkScheduled = false;
    }

    @Redirect(
            method = "compileChunks(Lnet/minecraft/client/Camera;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk;setNotDirty()V",
                    ordinal = 1
            )
    )
    private void pauc$preserveDirtyFlagWhenDeferred(ChunkRenderDispatcher.RenderChunk renderChunk) {
        if (this.pauc$lastChunkScheduled) {
            renderChunk.setNotDirty();
        }
        this.pauc$lastChunkScheduled = false;
    }

    @Inject(
            method = "compileChunks(Lnet/minecraft/client/Camera;)V",
            at = @At("RETURN")
    )
    private void pauc$endChunkCompileBudget(Camera camera, CallbackInfo callbackInfo) {
        ChunkBuildQueueController.endCompilePass();
    }
}

