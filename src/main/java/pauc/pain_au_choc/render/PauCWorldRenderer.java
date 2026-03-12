package pauc.pain_au_choc.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.render.chunk.PauCRenderSection;
import pauc.pain_au_choc.render.chunk.PauCRenderSectionManager;
import pauc.pain_au_choc.render.terrain.DefaultTerrainRenderPasses;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main world renderer replacing vanilla's chunk rendering with PAUC's optimized pipeline.
 * This is the public API entry point that integrates with Minecraft's LevelRenderer via mixins.
 *
 * Coordinates:
 * - Visibility culling (occlusion + frustum)
 * - Chunk mesh building and upload
 * - Terrain drawing per render pass
 * - Block entity iteration
 * - Entity visibility culling
 *
 * Adapted from Embeddium's EmbeddiumWorldRenderer with full PAUC governor integration.
 */
public class PauCWorldRenderer {

    // ---- Singleton ----
    private static PauCWorldRenderer instance;

    public static PauCWorldRenderer instance() {
        return instance;
    }

    public static PauCWorldRenderer instanceNullable() {
        return instance;
    }

    // ---- State ----
    private final Minecraft client;
    private ClientLevel world;
    private int renderDistance;

    private PauCRenderSectionManager sectionManager;

    /** Camera state from last setupTerrain call. */
    private double lastCameraX, lastCameraY, lastCameraZ;
    private double lastCameraPitch, lastCameraYaw;
    private float lastFogDistance;

    /** Current viewport/frustum from last frame. */
    private Frustum currentFrustum;

    /** Whether entity culling is enabled. */
    private boolean useEntityCulling = true;

    /** Frame counter for visibility tracking. */
    private int frameCounter = 0;

    /** Whether any block entity requested an outline this frame. */
    private boolean blockEntityRequestedOutline = false;

    public PauCWorldRenderer(Minecraft client) {
        this.client = client;
        instance = this;
    }

    // ---- World lifecycle ----

    /**
     * Set the active world. null = unload.
     */
    public void setWorld(ClientLevel world) {
        if (this.world != null && this.sectionManager != null) {
            this.sectionManager.destroy();
        }

        this.world = world;

        if (world != null) {
            this.renderDistance = this.client.options.renderDistance().get();
            this.sectionManager = new PauCRenderSectionManager(this.client, world, this.renderDistance);

            // Connect to PAUC governor if available
            GlobalPerformanceGovernor governor = PauCClient.getGovernor();
            if (governor != null) {
                this.sectionManager.setGovernor(governor);
            }
        } else {
            this.sectionManager = null;
        }
    }

    /**
     * Reload the renderer (e.g., after resource pack change or render distance change).
     */
    public void reload() {
        if (this.world != null) {
            setWorld(this.world);
        }
    }

    // ---- Per-frame setup ----

    /**
     * Main per-frame setup: update camera, determine visibility, schedule builds.
     * Called from LevelRenderer.setupRender() via mixin.
     *
     * @param camera      Active camera
     * @param frustum     View frustum
     * @param frame       Frame number
     * @param spectator   Whether the player is in spectator mode
     * @param updateNow   Whether to force immediate chunk updates
     */
    public void setupTerrain(Camera camera, Frustum frustum, int frame, boolean spectator, boolean updateNow) {
        if (this.sectionManager == null) return;

        this.frameCounter = frame;
        this.currentFrustum = frustum;

        Vec3 pos = camera.getPosition();
        this.lastCameraX = pos.x;
        this.lastCameraY = pos.y;
        this.lastCameraZ = pos.z;

        // Check render distance change
        int newRenderDistance = this.client.options.renderDistance().get();
        if (newRenderDistance != this.renderDistance) {
            this.renderDistance = newRenderDistance;
            this.sectionManager.setRenderDistance(newRenderDistance);
        }

        // Update visibility
        this.sectionManager.update(camera, frustum, frame, spectator);

        // Schedule mesh builds
        this.sectionManager.updateChunks(updateNow);

        // Upload completed builds to GPU
        this.sectionManager.uploadChunks();

        this.blockEntityRequestedOutline = false;
    }

    // ---- Terrain drawing ----

    /**
     * Draw a specific terrain render pass (SOLID, CUTOUT, TRANSLUCENT).
     * Called from LevelRenderer.renderChunkLayer() via mixin.
     */
    public void drawChunkLayer(RenderType renderType, Matrix4f modelView, double camX, double camY, double camZ) {
        if (this.sectionManager == null) return;

        PauCTerrainRenderPass pass = DefaultTerrainRenderPasses.fromVanilla(renderType);
        if (pass == null) return;

        pass.startDrawing();

        // TODO Phase 1.5: Use PauCChunkRenderer to draw visible sections for this pass
        // For now, the mixin falls through to vanilla rendering
        List<PauCRenderSection> visible = this.sectionManager.getVisibleSections();
        // Actual draw calls will be added in Phase 1.5

        pass.endDrawing();
    }

    // ---- Block entity rendering ----

    /**
     * Iterate over all visible block entities for rendering.
     */
    public void forEachVisibleBlockEntity(Consumer<BlockEntity> consumer) {
        if (this.sectionManager != null) {
            this.sectionManager.forEachVisibleBlockEntity(consumer);
        }
    }

    /**
     * Get an iterator over all visible block entities.
     */
    public Iterator<BlockEntity> blockEntityIterator() {
        List<BlockEntity> entities = new java.util.ArrayList<>();
        forEachVisibleBlockEntity(entities::add);
        return entities.iterator();
    }

    /**
     * Render block entities with the standard pipeline.
     */
    public void renderBlockEntities(PoseStack poseStack, RenderBuffers buffers,
                                     Camera camera, float tickDelta) {
        // TODO Phase 1.5: Implement optimized block entity rendering
        // with PAUC's RenderBudgetManager integration
    }

    public boolean didBlockEntityRequestOutline() {
        return this.blockEntityRequestedOutline;
    }

    // ---- Entity culling ----

    /**
     * Check if an entity should be rendered based on distance and visibility.
     * Integrates with PAUC's EntityLodController for LOD decisions.
     */
    public boolean isEntityVisible(Entity entity) {
        if (!this.useEntityCulling) return true;
        if (this.currentFrustum == null) return true;

        return this.currentFrustum.isVisible(entity.getBoundingBox());
    }

    /**
     * Check if an arbitrary AABB is visible.
     */
    public boolean isBoxVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        if (this.currentFrustum == null) return true;
        return this.currentFrustum.isVisible(
                new net.minecraft.world.phys.AABB(x1, y1, z1, x2, y2, z2));
    }

    // ---- Section management delegation ----

    public void scheduleTerrainUpdate() {
        if (this.sectionManager != null) {
            this.sectionManager.markGraphDirty();
        }
    }

    public boolean isTerrainRenderComplete() {
        return this.sectionManager != null && !this.sectionManager.needsUpdate();
    }

    public void scheduleRebuildForBlockArea(int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ, boolean important) {
        if (this.sectionManager != null) {
            this.sectionManager.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, important);
        }
    }

    public void scheduleRebuildForChunk(int chunkX, int chunkY, int chunkZ, boolean important) {
        if (this.sectionManager != null) {
            this.sectionManager.scheduleRebuild(chunkX, chunkY, chunkZ, important);
        }
    }

    public void onChunkAdded(int chunkX, int chunkZ) {
        if (this.sectionManager != null) {
            this.sectionManager.onChunkAdded(chunkX, chunkZ);
        }
    }

    public void onChunkRemoved(int chunkX, int chunkZ) {
        if (this.sectionManager != null) {
            this.sectionManager.onChunkRemoved(chunkX, chunkZ);
        }
    }

    public boolean isSectionReady(int chunkX, int chunkY, int chunkZ) {
        return this.sectionManager != null
                && this.sectionManager.isSectionBuilt(chunkX, chunkY, chunkZ);
    }

    // ---- Debug ----

    public int getVisibleChunkCount() {
        return this.sectionManager != null ? this.sectionManager.getVisibleChunkCount() : 0;
    }

    public String getChunksDebugString() {
        if (this.sectionManager == null) return "PAUC: no world";
        return "PAUC: " + this.sectionManager.getVisibleChunkCount() + "/"
                + this.sectionManager.getTotalSections() + " visible";
    }

    public Collection<String> getDebugStrings() {
        if (this.sectionManager == null) {
            return List.of("PAUC WorldRenderer: inactive");
        }
        return this.sectionManager.getDebugStrings();
    }

    // ---- Getters ----

    public ClientLevel getWorld() { return this.world; }
    public int getRenderDistance() { return this.renderDistance; }
    public PauCRenderSectionManager getSectionManager() { return this.sectionManager; }
}
