package pauc.pain_au_choc.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import pauc.pain_au_choc.AuthoritativeRuntimeController;
import pauc.pain_au_choc.ChunkBuildQueueController;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.GlobalPerformanceMode;
import pauc.pain_au_choc.ManagedChunkRadiusController;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.StructureStreamingController;
import pauc.pain_au_choc.render.occlusion.GraphDirection;
import pauc.pain_au_choc.render.occlusion.PauCOcclusionCuller;
import pauc.pain_au_choc.render.region.PauCRenderRegion;
import pauc.pain_au_choc.render.terrain.DefaultTerrainRenderPasses;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import pauc.pain_au_choc.render.compile.PauCChunkBuildOutput;
import pauc.pain_au_choc.render.compile.PauCChunkBuilder;

import java.util.*;
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

    /** Multi-threaded chunk mesh builder. */
    private PauCChunkBuilder chunkBuilder;

    /** GPU upload manager. */
    private final PauCUploadManager uploadManager = new PauCUploadManager();

    /** GPU chunk renderer. */
    private PauCChunkRenderer chunkRenderer;

    /** Current camera state. */
    private Vec3 cameraPosition = Vec3.ZERO;
    private BlockPos lastCameraBlockPos = BlockPos.ZERO;

    /** Frame tracking. */
    private int currentFrame = 0;
    private boolean needsUpdate = true;
    private int lastFullDetailVisibleSections = 0;
    private int lastStreamingVisibleSections = 0;
    private int lastDeferredVisibleSections = 0;
    private int lastBudgetCulledVisibleSections = 0;
    private int lastVisibleCulledBlockEntities = 0;
    private int lastGlobalBlockEntities = 0;

    /** PAUC integration: reference to governor for quality-driven decisions. */
    private GlobalPerformanceGovernor governor;

    public PauCRenderSectionManager(Minecraft client, ClientLevel world, int renderDistance) {
        this.client = client;
        this.world = world;
        this.renderDistance = renderDistance;
        this.occlusionCuller = new PauCOcclusionCuller(this.sectionByPosition, world);
        this.chunkBuilder = new PauCChunkBuilder(world);
        this.chunkRenderer = new PauCChunkRenderer(this.uploadManager);

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
        applyManagedRingFlags(cameraBlockPos);

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

                // Submit to the multi-threaded chunk builder
                boolean accepted = this.chunkBuilder.submitBuild(section, this.currentFrame, type);
                if (accepted) {
                    section.setPendingUpdate(null); // Clear pending flag
                    budget--;
                } else {
                    // Back-pressure: re-enqueue and stop this priority level
                    queue.addFirst(section);
                    break;
                }
            }
        }
    }

    /**
     * Upload completed mesh data to GPU buffers.
     * Called after updateChunks on the render thread.
     */
    public void uploadChunks() {
        // Drain completed build results from the worker threads
        List<PauCChunkBuildOutput> results = this.chunkBuilder.drainResults();
        if (!results.isEmpty()) {
            List<PauCChunkBuildOutput> normalizedResults = new ArrayList<>(results.size());
            for (PauCChunkBuildOutput output : results) {
                PauCChunkBuildOutput normalized = normalizeBlockEntityClassification(output);
                normalizedResults.add(normalized);
                syncGlobalBlockEntityTracking(normalized);
            }
            results = normalizedResults;
        }
        // Upload results plus any backlog pending in the upload manager.
        this.uploadManager.upload(results);
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
     * Render a terrain pass for all visible sections.
     * Delegates to PauCChunkRenderer for GPU draw calls.
     *
     * @param pass           The terrain render pass (SOLID, CUTOUT, TRANSLUCENT)
     * @param modelViewMatrix Current model-view matrix
     * @param cameraX        Camera world X position
     * @param cameraY        Camera world Y position
     * @param cameraZ        Camera world Z position
     */
    public void renderLayer(PauCTerrainRenderPass pass, org.joml.Matrix4f modelViewMatrix,
                             double cameraX, double cameraY, double cameraZ) {
        this.chunkRenderer.renderPass(this.visibleSections, pass, modelViewMatrix, cameraX, cameraY, cameraZ);
    }

    /** Get the chunk renderer for statistics. */
    public PauCChunkRenderer getChunkRenderer() { return this.chunkRenderer; }

    /** Get the upload manager. */
    public PauCUploadManager getUploadManager() { return this.uploadManager; }

    /** Get the chunk builder for back-pressure info. */
    public PauCChunkBuilder getChunkBuilder() { return this.chunkBuilder; }

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
    public int getLastFullDetailVisibleSections() { return this.lastFullDetailVisibleSections; }
    public int getLastStreamingVisibleSections() { return this.lastStreamingVisibleSections; }
    public int getLastDeferredVisibleSections() { return this.lastDeferredVisibleSections; }
    public int getLastBudgetCulledVisibleSections() { return this.lastBudgetCulledVisibleSections; }
    public int getLastVisibleCulledBlockEntityCount() { return this.lastVisibleCulledBlockEntities; }
    public int getLastGlobalBlockEntityCount() { return this.lastGlobalBlockEntities; }

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
            this.chunkBuilder = new PauCChunkBuilder(world);
            this.chunkRenderer = new PauCChunkRenderer(this.uploadManager);
        }
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistance = renderDistance;
        this.needsUpdate = true;
    }

    // ---- Cleanup ----

    public void destroy() {
        // Shutdown chunk builder threads
        if (this.chunkBuilder != null) {
            this.chunkBuilder.destroy();
        }

        // Release GPU resources
        if (this.chunkRenderer != null) {
            this.chunkRenderer.destroy();
        }
        if (this.uploadManager != null) {
            this.uploadManager.destroy();
        }

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
        lines.add("Pending builds: " + pending + ", Builder: " + this.chunkBuilder.toString());
        lines.add("Upload: " + this.uploadManager.getLastUploadPassSections() + "/" + this.uploadManager.getLastUploadSectionBudget()
                + " sections, "
                + formatMegabytes(this.uploadManager.getLastUploadPassBytes()) + "/"
                + formatMegabytes(this.uploadManager.getLastUploadByteBudget())
                + " MB, backlog=" + this.uploadManager.getPendingUploadCount());
        lines.add("Rings: full=" + this.lastFullDetailVisibleSections
                + " stream=" + this.lastStreamingVisibleSections
                + " deferred=" + this.lastDeferredVisibleSections
                + " culled=" + this.lastBudgetCulledVisibleSections);
        lines.add("Block entities: visibleCulled=" + this.lastVisibleCulledBlockEntities
                + " global=" + this.lastGlobalBlockEntities);
        lines.add(this.chunkRenderer.getDebugString());

        // Governor integration info
        if (this.governor != null) {
            lines.add("Governor: " + GlobalPerformanceGovernor.getMode()
                    + " pressure=" + GlobalPerformanceGovernor.getGlobalPressure()
                    + " distMul=" + String.format("%.2f", getGovernorDistanceMultiplier())
                    + " buildBudget=" + getFrameBuildBudget());
        }
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

    private PauCChunkBuildOutput normalizeBlockEntityClassification(PauCChunkBuildOutput output) {
        BlockEntity[] inputGlobal = output.getGlobalBlockEntities();
        BlockEntity[] inputCulled = output.getCulledBlockEntities();
        int totalEntities = inputGlobal.length + inputCulled.length;
        if (totalEntities == 0) {
            return output;
        }

        BlockEntityRenderDispatcher dispatcher = this.client.getBlockEntityRenderDispatcher();
        if (dispatcher == null) {
            return output;
        }

        List<BlockEntity> global = new ArrayList<>(totalEntities);
        List<BlockEntity> culled = new ArrayList<>(totalEntities);
        for (BlockEntity entity : inputGlobal) {
            if (entity == null) {
                continue;
            }
            if (shouldRenderBlockEntityOffScreen(entity, dispatcher)) {
                global.add(entity);
            } else {
                culled.add(entity);
            }
        }
        for (BlockEntity entity : inputCulled) {
            if (entity == null) {
                continue;
            }
            if (shouldRenderBlockEntityOffScreen(entity, dispatcher)) {
                global.add(entity);
            } else {
                culled.add(entity);
            }
        }

        int flags = output.getFlags();
        boolean hasAnyBlockEntity = !global.isEmpty() || !culled.isEmpty();
        flags = RenderSectionFlags.setFlag(flags, RenderSectionFlags.HAS_BLOCK_ENTITIES, hasAnyBlockEntity);

        return new PauCChunkBuildOutput(
                output.getSection(),
                output.getBuildFrame(),
                output.getMeshes(),
                flags,
                global.toArray(new BlockEntity[0]),
                culled.toArray(new BlockEntity[0]),
                output.getAnimatedSprites()
        );
    }

    @SuppressWarnings("unchecked")
    private static boolean shouldRenderBlockEntityOffScreen(BlockEntity entity, BlockEntityRenderDispatcher dispatcher) {
        try {
            BlockEntityRenderer<BlockEntity> renderer = (BlockEntityRenderer<BlockEntity>) dispatcher.getRenderer(entity);
            return renderer != null && renderer.shouldRenderOffScreen(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void syncGlobalBlockEntityTracking(PauCChunkBuildOutput output) {
        PauCRenderSection section = output.getSection();
        if (section == null || section.isDisposed()) {
            return;
        }
        if (output.getGlobalBlockEntities().length > 0) {
            this.sectionsWithGlobalEntities.add(section);
        } else {
            this.sectionsWithGlobalEntities.remove(section);
        }
    }

    private int getFrameBuildBudget() {
        // If PAUC budget system is not active, use generous default
        if (!PauCClient.isBudgetActive()) {
            return 12;
        }

        // Use a non-mutating budget preview, so we don't interfere with
        // vanilla compile pass accounting in LevelRendererMixin.
        int controllerBudget = ChunkBuildQueueController.previewCompileBudget();
        if (controllerBudget == Integer.MAX_VALUE) {
            return 16; // Uncapped
        }

        // controllerBudget already includes governor mode multiplier in ChunkBuildQueueController.
        int budget = controllerBudget;

        // Also factor in the ChunkBuilder's own back-pressure
        float builderPressure = this.chunkBuilder.getBackPressureRatio();
        if (builderPressure > 0.8f) {
            budget = Math.max(2, budget / 2); // Reduce budget under heavy load
        }

        return Math.max(2, Math.min(24, budget));
    }

    private float getGovernorDistanceMultiplier() {
        if (!PauCClient.isBudgetActive()) {
            return 1.0f;
        }

        // Use ManagedChunkRadiusController to get the effective chunk radius
        int managedRadius = ManagedChunkRadiusController.getManagedRadiusChunks();
        if (managedRadius <= 0 || this.renderDistance <= 0) {
            return 1.0f;
        }

        // Ratio of managed radius to configured render distance
        float multiplier = (float) managedRadius / this.renderDistance;

        // Clamp: never go below 50% or above 100% of configured distance
        return Math.max(0.5f, Math.min(1.0f, multiplier));
    }

    private void applyManagedRingFlags(BlockPos cameraBlockPos) {
        int visibleCulledEntities = 0;
        int globalEntities = 0;
        for (PauCRenderSection section : this.sectionsWithGlobalEntities) {
            if (section != null && !section.isDisposed()) {
                globalEntities += section.getGlobalBlockEntities().length;
            }
        }
        this.lastGlobalBlockEntities = globalEntities;

        if (this.visibleSections.isEmpty()) {
            this.lastFullDetailVisibleSections = 0;
            this.lastStreamingVisibleSections = 0;
            this.lastDeferredVisibleSections = 0;
            this.lastBudgetCulledVisibleSections = 0;
            this.lastVisibleCulledBlockEntities = 0;
            return;
        }

        int playerChunkX = cameraBlockPos.getX() >> 4;
        int playerChunkZ = cameraBlockPos.getZ() >> 4;
        int fullDetailRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int streamingRadius = ManagedChunkRadiusController.getStreamingRadiusChunks();
        boolean throttled = AuthoritativeRuntimeController.shouldThrottleChunkStreaming();
        int pressure = GlobalPerformanceGovernor.getGlobalPressure();
        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();

        int fullCount = 0;
        int streamingCount = 0;
        int deferredCount = 0;
        int culledCount = 0;

        for (PauCRenderSection section : this.visibleSections) {
            visibleCulledEntities += section.getCulledBlockEntities().length;
            int distanceChunks = Math.max(
                    Math.abs(section.getChunkX() - playerChunkX),
                    Math.abs(section.getChunkZ() - playerChunkZ)
            );
            boolean inFullDetailRing = distanceChunks <= fullDetailRadius;
            boolean inStreamingRing = distanceChunks <= streamingRadius;
            boolean deferred = !inFullDetailRing && StructureStreamingController.isChunkDeferred(section.getOriginX(), section.getOriginZ());
            boolean budgetCulled = !inStreamingRing || shouldCullDeferredSection(mode, throttled, pressure, deferred);

            section.setFlag(RenderSectionFlags.IN_FULL_DETAIL_RING, inFullDetailRing);
            section.setFlag(RenderSectionFlags.IN_STREAMING_RING, inStreamingRing);
            section.setFlag(RenderSectionFlags.BUDGET_CULLED, budgetCulled);

            if (inFullDetailRing) {
                fullCount++;
            }
            if (inStreamingRing) {
                streamingCount++;
            }
            if (deferred) {
                deferredCount++;
            }
            if (budgetCulled) {
                culledCount++;
            }
        }

        this.lastFullDetailVisibleSections = fullCount;
        this.lastStreamingVisibleSections = streamingCount;
        this.lastDeferredVisibleSections = deferredCount;
        this.lastBudgetCulledVisibleSections = culledCount;
        this.lastVisibleCulledBlockEntities = visibleCulledEntities;
    }

    private static boolean shouldCullDeferredSection(
            GlobalPerformanceMode mode,
            boolean throttled,
            int pressure,
            boolean deferred
    ) {
        if (!deferred) {
            return false;
        }

        return switch (mode) {
            case CRISIS -> true;
            case BASE -> throttled || pressure >= 2;
            case COMBAT -> throttled && pressure >= 2;
            case TRANSIT -> throttled && pressure >= 3;
            case EXPLORATION -> throttled && pressure >= 3;
        };
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }

    private static String formatMegabytes(long bytes) {
        return String.format("%.2f", bytes / (1024.0D * 1024.0D));
    }
}
