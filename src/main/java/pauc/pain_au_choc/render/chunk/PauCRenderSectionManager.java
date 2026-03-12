package pauc.pain_au_choc.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.render.occlusion.GraphDirection;
import pauc.pain_au_choc.render.occlusion.PauCOcclusionCuller;
import pauc.pain_au_choc.render.region.PauCRenderRegion;
import pauc.pain_au_choc.render.terrain.DefaultTerrainRenderPasses;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of all PauCRenderSections and coordinates
 * chunk mesh building, visibility culling, and GPU upload.
 *
 * This is the core orchestrator of PAUC's chunk rendering pipeline,
 * integrating Embeddium-style optimizations with PAUC's performance governor.
 *
 * Adapted from Embeddium's RenderSectionManager.
 */
public class PauCRenderSectionManager {

    private final Minecraft client;
    private ClientLevel world;
    private int renderDistance;

    /** All render sections indexed by packed position. */
    private final Long2ReferenceMap<PauCRenderSection> sectionByPosition = new Long2ReferenceOpenHashMap<>();

    /** Render regions indexed by region key. */
    private final Long2ReferenceMap<PauCRenderRegion> regions = new Long2ReferenceOpenHashMap<>();

    /** Occlusion culler for visibility determination. */
    private PauCOcclusionCuller occlusionCuller;

    /** Sections with global block entities (always rendered). */
    private final Set<PauCRenderSection> sectionsWithGlobalEntities = new LinkedHashSet<>();

    /** Lists of visible sections per render pass (rebuilt each frame). */
    private final List<PauCRenderSection> visibleSections = new ArrayList<>(4096);

    /** Queues for pending updates, keyed by priority. */
    private final Map<ChunkUpdateType, ArrayDeque<PauCRenderSection>> rebuildLists = new EnumMap<>(ChunkUpdateType.class);

    /** Results from async chunk build tasks. */
    private final ConcurrentLinkedDeque<Object> buildResults = new ConcurrentLinkedDeque<>();

    /** Current camera state. */
    private Vec3 cameraPosition = Vec3.ZERO;
    private BlockPos lastCameraBlockPos = BlockPos.ZERO;

    /** Frame tracking. */
    private int currentFrame = 0;
    private boolean needsUpdate = true;

    /** PAUC integration: reference to governor for quality-driven decisions. */
    private GlobalPerformanceGovernor governor;

    public PauCRenderSectionManager(Minecraft client, ClientLevel world, int renderDistance) {
        this.client = client;
        this.world = world;
        this.renderDistance = renderDistance;
        this.occlusionCuller = new PauCOcclusionCuller(this.sectionByPosition, world);

        // Initialize rebuild queues
        for (ChunkUpdateType type : ChunkUpdateType.values()) {
            this.rebuildLists.put(type, new ArrayDeque<>());
        }
    }

    /** Set the PAUC performance governor for quality-driven chunk management. */
    public void setGovernor(GlobalPerformanceGovernor governor) {
        this.governor = governor;
    }

    // ---- Section lifecycle ----

    /**
     * Register a new render section at the given chunk coordinates.
     * Called when the server sends a chunk section to the client.
     */
    public void onSectionAdded(int chunkX, int chunkY, int chunkZ) {
        long key = sectionKey(chunkX, chunkY, chunkZ);

        // Don't add duplicates
        if (this.sectionByPosition.containsKey(key)) {
            return;
        }

        // Get or create region
        PauCRenderRegion region = getOrCreateRegion(chunkX, chunkY, chunkZ);

        // Create section
        PauCRenderSection section = new PauCRenderSection(region, chunkX, chunkY, chunkZ);
        region.addSection(section);
        this.sectionByPosition.put(key, section);

        // Connect adjacency graph
        connectAdjacentSections(section);

        // Schedule initial build
        section.setPendingUpdate(ChunkUpdateType.INITIAL);
        this.rebuildLists.get(ChunkUpdateType.INITIAL).add(section);

        this.needsUpdate = true;
    }

    /**
     * Remove a render section at the given chunk coordinates.
     * Called when a chunk section is unloaded.
     */
    public void onSectionRemoved(int chunkX, int chunkY, int chunkZ) {
        long key = sectionKey(chunkX, chunkY, chunkZ);
        PauCRenderSection section = this.sectionByPosition.remove(key);

        if (section == null) return;

        PauCRenderRegion region = section.getRegion();
        region.removeSection(section);

        // Remove from entity tracking
        this.sectionsWithGlobalEntities.remove(section);

        // Cleanup
        section.delete();

        // Remove empty regions
        if (region.isEmpty()) {
            long regionKey = PauCRenderRegion.key(chunkX, chunkY, chunkZ);
            this.regions.remove(regionKey);
            region.delete();
        }

        this.needsUpdate = true;
    }

    /**
     * Bulk add all sections in a chunk column.
     */
    public void onChunkAdded(int chunkX, int chunkZ) {
        int minY = this.world.getMinSection();
        int maxY = this.world.getMaxSection();
        for (int y = minY; y < maxY; y++) {
            onSectionAdded(chunkX, y, chunkZ);
        }
    }

    /**
     * Bulk remove all sections in a chunk column.
     */
    public void onChunkRemoved(int chunkX, int chunkZ) {
        int minY = this.world.getMinSection();
        int maxY = this.world.getMaxSection();
        for (int y = minY; y < maxY; y++) {
            onSectionRemoved(chunkX, y, chunkZ);
        }
    }

    // ---- Per-frame update ----

    /**
     * Main per-frame update: determine visible sections and schedule builds.
     * Called from the render thread before drawing.
     */
    public void update(Camera camera, Frustum frustum, int frame, boolean spectator) {
        this.currentFrame = frame;
        this.cameraPosition = camera.getPosition();
        BlockPos cameraBlockPos = camera.getBlockPosition();

        // Check if camera moved to a different block
        boolean cameraMoved = !cameraBlockPos.equals(this.lastCameraBlockPos);
        this.lastCameraBlockPos = cameraBlockPos;

        if (!this.needsUpdate && !cameraMoved) {
            return;
        }

        // Clear previous frame's visible list
        this.visibleSections.clear();

        // Calculate search distance (PAUC quality-adjusted)
        float searchDist = this.renderDistance * 16.0f;
        if (this.governor != null) {
            // Governor can expand or restrict search distance
            searchDist *= getGovernorDistanceMultiplier();
        }

        // Run occlusion culling
        boolean useOcclusion = !spectator; // Spectators see through walls
        this.occlusionCuller.findVisible(
                (section, visible) -> {
                    if (visible) {
                        this.visibleSections.add(section);
                    }
                },
                this.cameraPosition.x, this.cameraPosition.y, this.cameraPosition.z,
                frustum, searchDist, useOcclusion, frame
        );

        // Sort visible sections by distance for optimal rendering
        float cx = (float) this.cameraPosition.x;
        float cy = (float) this.cameraPosition.y;
        float cz = (float) this.cameraPosition.z;
        this.visibleSections.sort((a, b) -> {
            float da = a.getSquaredDistance(cx, cy, cz);
            float db = b.getSquaredDistance(cx, cy, cz);
            return Float.compare(da, db);
        });

        this.needsUpdate = false;
    }

    /**
     * Schedule chunk mesh builds for pending sections.
     * Respects PAUC's build budget per frame.
     */
    public void updateChunks(boolean updateImmediately) {
        // Process important rebuilds first
        int budget = getFrameBuildBudget();

        for (ChunkUpdateType type : new ChunkUpdateType[]{
                ChunkUpdateType.IMPORTANT_REBUILD, ChunkUpdateType.IMPORTANT_SORT,
                ChunkUpdateType.INITIAL, ChunkUpdateType.REBUILD, ChunkUpdateType.SORT}) {

            ArrayDeque<PauCRenderSection> queue = this.rebuildLists.get(type);
            while (!queue.isEmpty() && budget > 0) {
                PauCRenderSection section = queue.poll();
                if (section.isDisposed()) continue;

                // TODO Phase 1.3: Submit to PauCChunkBuilder thread pool
                // For now, mark as needs build
                budget--;
            }
        }
    }

    /**
     * Upload completed mesh data to GPU buffers.
     * Called after updateChunks.
     */
    public void uploadChunks() {
        // TODO Phase 1.5: Process buildResults queue and upload to GPU
        // Drain build results
        while (!this.buildResults.isEmpty()) {
            Object result = this.buildResults.poll();
            // Process result...
        }
    }

    // ---- Rendering ----

    /**
     * Get the list of visible sections for the current frame.
     * Used by PauCWorldRenderer to draw terrain passes.
     */
    public List<PauCRenderSection> getVisibleSections() {
        return this.visibleSections;
    }

    /**
     * Iterate over all visible block entities.
     */
    public void forEachVisibleBlockEntity(Consumer<BlockEntity> consumer) {
        for (PauCRenderSection section : this.visibleSections) {
            for (BlockEntity be : section.getCulledBlockEntities()) {
                consumer.accept(be);
            }
        }
        // Global block entities are always rendered
        for (PauCRenderSection section : this.sectionsWithGlobalEntities) {
            for (BlockEntity be : section.getGlobalBlockEntities()) {
                consumer.accept(be);
            }
        }
    }

    // ---- Rebuild scheduling ----

    /**
     * Schedule a rebuild for the section at the given chunk coordinates.
     * Called when blocks change.
     */
    public void scheduleRebuild(int chunkX, int chunkY, int chunkZ, boolean important) {
        long key = sectionKey(chunkX, chunkY, chunkZ);
        PauCRenderSection section = this.sectionByPosition.get(key);
        if (section == null || section.isDisposed()) return;

        ChunkUpdateType type = important ? ChunkUpdateType.IMPORTANT_REBUILD : ChunkUpdateType.REBUILD;
        section.setPendingUpdate(type);
        this.rebuildLists.get(type).add(section);
        this.needsUpdate = true;
    }

    /** Schedule rebuild for a range of blocks. */
    public void scheduleRebuildForBlockArea(int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ, boolean important) {
        int minCX = minX >> 4;
        int minCY = minY >> 4;
        int minCZ = minZ >> 4;
        int maxCX = maxX >> 4;
        int maxCY = maxY >> 4;
        int maxCZ = maxZ >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    scheduleRebuild(cx, cy, cz, important);
                }
            }
        }
    }

    // ---- Query methods ----

    public boolean needsUpdate() { return this.needsUpdate; }
    public void markGraphDirty() { this.needsUpdate = true; }

    public int getTotalSections() { return this.sectionByPosition.size(); }
    public int getVisibleChunkCount() { return this.visibleSections.size(); }

    public boolean isSectionVisible(int chunkX, int chunkY, int chunkZ) {
        long key = sectionKey(chunkX, chunkY, chunkZ);
        PauCRenderSection section = this.sectionByPosition.get(key);
        return section != null && section.getLastVisibleFrame() == this.currentFrame;
    }

    public boolean isSectionBuilt(int chunkX, int chunkY, int chunkZ) {
        long key = sectionKey(chunkX, chunkY, chunkZ);
        PauCRenderSection section = this.sectionByPosition.get(key);
        return section != null && section.isBuilt();
    }

    // ---- World change ----

    public void setWorld(ClientLevel world) {
        this.destroy();
        this.world = world;
        if (world != null) {
            this.occlusionCuller = new PauCOcclusionCuller(this.sectionByPosition, world);
        }
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistance = renderDistance;
        this.needsUpdate = true;
    }

    // ---- Cleanup ----

    public void destroy() {
        for (PauCRenderSection section : this.sectionByPosition.values()) {
            section.delete();
        }
        this.sectionByPosition.clear();

        for (PauCRenderRegion region : this.regions.values()) {
            region.delete();
        }
        this.regions.clear();

        this.visibleSections.clear();
        this.sectionsWithGlobalEntities.clear();
        this.buildResults.clear();
        for (ArrayDeque<PauCRenderSection> queue : this.rebuildLists.values()) {
            queue.clear();
        }
    }

    // ---- Debug ----

    public Collection<String> getDebugStrings() {
        List<String> lines = new ArrayList<>();
        lines.add("PAUC Renderer: " + getTotalSections() + " sections, "
                + getVisibleChunkCount() + " visible, "
                + this.regions.size() + " regions");

        int pending = 0;
        for (ArrayDeque<PauCRenderSection> q : this.rebuildLists.values()) {
            pending += q.size();
        }
        lines.add("Pending builds: " + pending);
        return lines;
    }

    // ---- Internal helpers ----

    private PauCRenderRegion getOrCreateRegion(int chunkX, int chunkY, int chunkZ) {
        long regionKey = PauCRenderRegion.key(chunkX, chunkY, chunkZ);
        PauCRenderRegion region = this.regions.get(regionKey);
        if (region == null) {
            int rx = chunkX >> 3;
            int ry = chunkY >> 2;
            int rz = chunkZ >> 3;
            region = new PauCRenderRegion(rx, ry, rz);
            this.regions.put(regionKey, region);
        }
        return region;
    }

    private void connectAdjacentSections(PauCRenderSection section) {
        for (int dir = 0; dir < GraphDirection.COUNT; dir++) {
            int nx = section.getChunkX() + GraphDirection.offsetX(dir);
            int ny = section.getChunkY() + GraphDirection.offsetY(dir);
            int nz = section.getChunkZ() + GraphDirection.offsetZ(dir);

            long neighborKey = sectionKey(nx, ny, nz);
            PauCRenderSection neighbor = this.sectionByPosition.get(neighborKey);

            if (neighbor != null && !neighbor.isDisposed()) {
                section.setAdjacentNode(dir, neighbor);
                neighbor.setAdjacentNode(GraphDirection.opposite(dir), section);
            }
        }
    }

    private int getFrameBuildBudget() {
        // Base budget, adjusted by PAUC quality
        int base = 8;
        if (this.governor != null) {
            // Governor provides budget multiplier based on mode and pressure
            // TODO Phase 1.7: integrate with ChunkBuildQueueController
        }
        return base;
    }

    private float getGovernorDistanceMultiplier() {
        // TODO Phase 1.7: integrate with ManagedChunkRadiusController
        return 1.0f;
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }
}
