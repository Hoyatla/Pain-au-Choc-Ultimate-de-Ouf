package pauc.pain_au_choc.render.compile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import pauc.pain_au_choc.render.chunk.ChunkUpdateType;
import pauc.pain_au_choc.render.chunk.PauCRenderSection;
import pauc.pain_au_choc.render.chunk.RenderSectionFlags;
import pauc.pain_au_choc.render.terrain.DefaultTerrainRenderPasses;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-threaded chunk mesh compiler.
 * Manages a pool of worker threads that compile chunk section geometry in parallel.
 *
 * Each worker has its own PauCChunkBuildContext to avoid contention.
 * Build results are returned via a concurrent queue for the render thread to process.
 *
 * Adapted from Embeddium's ChunkBuilder with PAUC governor integration.
 */
public class PauCChunkBuilder {

    /** Default number of worker threads (auto-detected). */
    private static final int DEFAULT_WORKERS = Math.max(1,
            Math.min(Runtime.getRuntime().availableProcessors() - 2, 6));

    /** Maximum pending tasks per worker. */
    private static final int MAX_TASKS_PER_WORKER = 4;

    private final ExecutorService executor;
    private final int workerCount;

    /** Per-worker build contexts. */
    private final PauCChunkBuildContext[] contexts;

    /** Pending task count for back-pressure. */
    private final AtomicInteger pendingTasks = new AtomicInteger(0);

    /** Maximum total pending tasks before we start dropping. */
    private final int maxPendingTasks;

    /** Queue for completed build results. */
    private final ConcurrentLinkedDeque<PauCChunkBuildOutput> completedResults = new ConcurrentLinkedDeque<>();

    /** Shutdown flag. */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /** Reference to the world for block data access. */
    private ClientLevel world;

    public PauCChunkBuilder(ClientLevel world) {
        this(world, DEFAULT_WORKERS);
    }

    public PauCChunkBuilder(ClientLevel world, int workerCount) {
        this.world = world;
        this.workerCount = workerCount;
        this.maxPendingTasks = workerCount * MAX_TASKS_PER_WORKER;

        // Create thread pool with custom thread factory
        this.executor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "PauC-ChunkBuilder");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        // Create per-worker contexts
        this.contexts = new PauCChunkBuildContext[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.contexts[i] = new PauCChunkBuildContext(i);
        }
    }

    /**
     * Submit a section for mesh compilation.
     *
     * @param section    The section to compile
     * @param frame      Current frame number
     * @param updateType The type of update (INITIAL, REBUILD, etc.)
     * @return true if the task was accepted, false if back-pressure limit reached
     */
    public boolean submitBuild(PauCRenderSection section, int frame, ChunkUpdateType updateType) {
        if (this.shutdown.get()) return false;

        // Back-pressure check
        if (this.pendingTasks.get() >= this.maxPendingTasks && !updateType.isImportant()) {
            return false;
        }

        this.pendingTasks.incrementAndGet();

        this.executor.submit(() -> {
            try {
                executeBuild(section, frame);
            } catch (Exception e) {
                // Log error but don't crash
                System.err.println("[PAUC] Chunk build failed for " + section + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                this.pendingTasks.decrementAndGet();
            }
        });

        return true;
    }

    /**
     * Execute a chunk mesh compilation task on a worker thread.
     */
    private void executeBuild(PauCRenderSection section, int frame) {
        if (section.isDisposed() || this.world == null) return;

        // Get thread-local context
        int threadIdx = Math.abs((int) Thread.currentThread().getId()) % this.workerCount;
        PauCChunkBuildContext ctx = this.contexts[threadIdx];
        ctx.prepare();

        int chunkX = section.getChunkX();
        int chunkY = section.getChunkY();
        int chunkZ = section.getChunkZ();

        // Get chunk section data
        var chunk = this.world.getChunk(chunkX, chunkZ);
        if (chunk == null) return;

        int sectionIdx = this.world.getSectionIndexFromSectionY(chunkY);
        if (sectionIdx < 0 || sectionIdx >= chunk.getSections().length) return;

        LevelChunkSection chunkSection = chunk.getSections()[sectionIdx];
        if (chunkSection == null || chunkSection.hasOnlyAir()) {
            // Empty section - no geometry
            PauCChunkBuildOutput output = new PauCChunkBuildOutput(
                    section, frame, Collections.emptyMap(), 0,
                    new BlockEntity[0], new BlockEntity[0], new TextureAtlasSprite[0]);
            this.completedResults.add(output);
            return;
        }

        // Compile block geometry
        PauCChunkBuildBuffers buffers = ctx.getBuffers();
        List<BlockEntity> globalEntities = new ArrayList<>();
        List<BlockEntity> culledEntities = new ArrayList<>();
        int flags = 0;

        int originX = chunkX << 4;
        int originY = chunkY << 4;
        int originZ = chunkZ << 4;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = originX + lx;
                    int wy = originY + ly;
                    int wz = originZ + lz;
                    pos.set(wx, wy, wz);

                    BlockState state = chunkSection.getBlockState(lx, ly, lz);
                    if (state.isAir()) continue;

                    // Check for block entities
                    BlockEntity be = chunk.getBlockEntity(pos);
                    if (be != null) {
                        // TODO: Classify as global vs culled based on renderer type
                        culledEntities.add(be);
                        flags |= RenderSectionFlags.HAS_BLOCK_ENTITIES;
                    }

                    // Determine render pass
                    // TODO Phase 1.3 full: Use BlockRenderer to compile actual mesh
                    // For now, mark flags based on block type
                    FluidState fluidState = state.getFluidState();
                    if (!fluidState.isEmpty()) {
                        flags |= RenderSectionFlags.HAS_GEOMETRY;
                        flags |= RenderSectionFlags.HAS_TRANSLUCENT;
                    } else if (!state.isAir()) {
                        flags |= RenderSectionFlags.HAS_GEOMETRY;
                    }
                }
            }
        }

        // Build mesh data map
        Map<PauCTerrainRenderPass, PauCChunkBuildOutput.MeshData> meshes = new HashMap<>();
        for (PauCTerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            if (buffers.hasGeometry(pass)) {
                java.nio.ByteBuffer vertexData = buffers.getFinishedBuffer(pass);
                if (vertexData != null) {
                    meshes.put(pass, new PauCChunkBuildOutput.MeshData(
                            vertexData, buffers.getVertexCount(pass)));
                }
            }
        }

        // Collect animated sprites
        Set<TextureAtlasSprite> sprites = ctx.getCapturedSprites();
        if (!sprites.isEmpty()) {
            flags |= RenderSectionFlags.HAS_ANIMATED_SPRITES;
        }

        // Create output
        PauCChunkBuildOutput output = new PauCChunkBuildOutput(
                section, frame, meshes, flags,
                globalEntities.toArray(new BlockEntity[0]),
                culledEntities.toArray(new BlockEntity[0]),
                sprites.toArray(new TextureAtlasSprite[0]));

        this.completedResults.add(output);
    }

    /**
     * Drain completed build results.
     * Called on the render thread.
     */
    public List<PauCChunkBuildOutput> drainResults() {
        List<PauCChunkBuildOutput> results = new ArrayList<>();
        PauCChunkBuildOutput result;
        while ((result = this.completedResults.poll()) != null) {
            results.add(result);
        }
        return results;
    }

    /**
     * Get the current back-pressure ratio (0.0 = idle, 1.0 = full).
     * Used by PAUC's ChunkBuildQueueController for adaptive budgeting.
     */
    public float getBackPressureRatio() {
        return (float) this.pendingTasks.get() / this.maxPendingTasks;
    }

    /** Get the number of pending tasks. */
    public int getPendingTaskCount() {
        return this.pendingTasks.get();
    }

    /** Get the number of worker threads. */
    public int getWorkerCount() {
        return this.workerCount;
    }

    /** Update the world reference (e.g., on dimension change). */
    public void setWorld(ClientLevel world) {
        this.world = world;
    }

    /**
     * Shutdown the builder and release all resources.
     */
    public void destroy() {
        this.shutdown.set(true);
        this.executor.shutdownNow();

        for (PauCChunkBuildContext ctx : this.contexts) {
            ctx.destroy();
        }

        this.completedResults.clear();
    }

    @Override
    public String toString() {
        return "PauCChunkBuilder[workers=" + workerCount
                + " pending=" + pendingTasks.get()
                + " completed=" + completedResults.size() + "]";
    }
}
