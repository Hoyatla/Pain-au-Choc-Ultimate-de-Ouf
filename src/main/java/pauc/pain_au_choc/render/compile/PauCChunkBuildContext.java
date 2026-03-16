package pauc.pain_au_choc.render.compile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-thread working context for chunk mesh compilation.
 * Holds reusable resources to avoid allocations during compilation.
 *
 * NOT thread-safe — one instance per worker thread.
 *
 * Adapted from Embeddium's ChunkBuildContext.
 */
public class PauCChunkBuildContext {

    /** Reusable vertex buffers for this thread. */
    private final PauCChunkBuildBuffers buffers;

    /** Block model resolver. */
    private BlockRenderDispatcher blockRenderer;

    /** Animated sprites encountered during compilation. */
    private final Set<TextureAtlasSprite> capturedSprites = new HashSet<>();

    /** Thread-local ID for debugging. */
    private final int threadIndex;

    public PauCChunkBuildContext(int threadIndex) {
        this.threadIndex = threadIndex;
        this.buffers = new PauCChunkBuildBuffers();
    }

    /**
     * Initialize context for a new compilation task.
     * Must be called before each section compilation.
     */
    public void prepare() {
        this.buffers.clear();
        this.capturedSprites.clear();

        // Get the block renderer on first use (must happen on render thread or use snapshot)
        if (this.blockRenderer == null) {
            this.blockRenderer = Minecraft.getInstance().getBlockRenderer();
        }
    }

    /**
     * Record an animated sprite found during compilation.
     */
    public void captureSprite(TextureAtlasSprite sprite) {
        if (sprite != null) {
            // Any sprite with more than 1 frame in its animation metadata is animated.
            // We capture all sprites; callers filter for animated ones.
            this.capturedSprites.add(sprite);
        }
    }

    public PauCChunkBuildBuffers getBuffers() { return buffers; }
    public BlockRenderDispatcher getBlockRenderer() { return blockRenderer; }
    public Set<TextureAtlasSprite> getCapturedSprites() { return capturedSprites; }
    public int getThreadIndex() { return threadIndex; }

    /**
     * Release all resources.
     */
    public void destroy() {
        this.buffers.destroy();
        this.capturedSprites.clear();
    }
}
