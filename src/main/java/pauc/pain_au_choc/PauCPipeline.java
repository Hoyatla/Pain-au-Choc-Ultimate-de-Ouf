package pauc.pain_au_choc;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;

public final class PauCPipeline {
    private PauCPipeline() {
    }

    public static void dispose() {
        LatencyController.reset();
        BottleneckController.reset();
        StructureStreamingController.reset();
        ChunkBuildQueueController.reset();
        EntitySpatialIndex.reset();
        EntityLodController.reset();
        IntegratedServerLoadController.reset();
        GlobalPerformanceGovernor.reset();
        AuthoritativeRuntimeController.resetRuntimeState();
        ShadowDistanceGovernor.reset();
        TerrainProxyController.reset();
        PauCShaderManager.releaseTransientTargets();
    }

    public static boolean shouldRenderSkyMesh() {
        return RenderBudgetManager.shouldRenderSkyMesh();
    }

    public static boolean shouldRenderClouds() {
        return RenderBudgetManager.shouldRenderClouds();
    }

    public static boolean shouldRenderWeather() {
        return RenderBudgetManager.shouldRenderWeather();
    }

    public static boolean shouldRenderChunkLayer(RenderType renderType) {
        return RenderBudgetManager.shouldRenderChunkLayer(renderType);
    }

    public static boolean shouldRenderEntity(Entity entity) {
        return RenderBudgetManager.shouldRenderEntity(entity);
    }

    public static boolean shouldRenderEntityThisFrame(Entity entity) {
        return EntityLodController.shouldRenderEntityThisFrame(entity);
    }

    public static boolean shouldRenderEntityAsBillboard(Entity entity) {
        return EntityLodController.shouldRenderAsBillboard(entity);
    }

    public static float remapEntityPartialTick(Entity entity, float partialTick) {
        return EntityLodController.remapPartialTick(entity, partialTick);
    }
}

