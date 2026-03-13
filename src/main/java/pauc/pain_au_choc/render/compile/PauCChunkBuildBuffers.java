package pauc.pain_au_choc.render.compile;

import pauc.pain_au_choc.render.terrain.DefaultTerrainRenderPasses;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;
import pauc.pain_au_choc.render.vertex.PauCVertexEncoder;
import pauc.pain_au_choc.render.vertex.PauCVertexFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-thread vertex buffer collection for chunk mesh compilation.
 * Each render pass gets its own buffer that grows as needed during compilation.
 *
 * NOT thread-safe — one instance per worker thread.
 *
 * Adapted from Embeddium's ChunkBuildBuffers.
 */
public class PauCChunkBuildBuffers {

    /** Initial buffer capacity per pass (enough for ~1000 quads). */
    private static final int INITIAL_CAPACITY = PauCVertexEncoder.requiredBytesForQuads(1024); // ~80KB

    /** Maximum buffer capacity per pass. */
    private static final int MAX_CAPACITY = PauCVertexEncoder.requiredBytesForQuads(65536); // ~5MB

    /** Buffers keyed by render pass. */
    private final Map<PauCTerrainRenderPass, ByteBuffer> buffers = new HashMap<>();

    /** Vertex counts per pass. */
    private final Map<PauCTerrainRenderPass, Integer> vertexCounts = new HashMap<>();

    public PauCChunkBuildBuffers() {
        // Pre-allocate buffers for standard passes
        for (PauCTerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            ByteBuffer buf = ByteBuffer.allocateDirect(INITIAL_CAPACITY)
                    .order(ByteOrder.nativeOrder());
            this.buffers.put(pass, buf);
            this.vertexCounts.put(pass, 0);
        }
    }

    /**
     * Get the buffer for a specific render pass.
     * Ensures the buffer has capacity for at least one more vertex.
     */
    public ByteBuffer getBuffer(PauCTerrainRenderPass pass) {
        ByteBuffer buf = this.buffers.get(pass);
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(INITIAL_CAPACITY).order(ByteOrder.nativeOrder());
            this.buffers.put(pass, buf);
            this.vertexCounts.put(pass, 0);
        }

        // Grow if needed
        if (buf.remaining() < PauCVertexFormat.STRIDE) {
            buf = grow(pass, buf);
        }

        return buf;
    }

    /**
     * Record that a vertex was written to the pass buffer.
     */
    public void vertexWritten(PauCTerrainRenderPass pass) {
        this.vertexCounts.merge(pass, 1, Integer::sum);
    }

    /**
     * Write a complete vertex to the given pass.
     */
    public void writeVertex(PauCTerrainRenderPass pass,
                             float x, float y, float z,
                             int r, int g, int b, int a,
                             float u, float v,
                             int blockLight, int skyLight,
                             float nx, float ny, float nz) {
        ByteBuffer buf = getBuffer(pass);
        PauCVertexEncoder.encode(buf, x, y, z, r, g, b, a, u, v, blockLight, skyLight, nx, ny, nz);
        vertexWritten(pass);
    }

    /**
     * Get the number of vertices written to a pass.
     */
    public int getVertexCount(PauCTerrainRenderPass pass) {
        return this.vertexCounts.getOrDefault(pass, 0);
    }

    /**
     * Check if a pass has any geometry.
     */
    public boolean hasGeometry(PauCTerrainRenderPass pass) {
        return getVertexCount(pass) > 0;
    }

    /**
     * Get the raw vertex data for a pass, flipped for reading.
     * Returns null if no geometry was written.
     */
    public ByteBuffer getFinishedBuffer(PauCTerrainRenderPass pass) {
        if (!hasGeometry(pass)) return null;

        ByteBuffer buf = this.buffers.get(pass);
        if (buf == null) return null;

        // Create a read-ready slice
        ByteBuffer copy = ByteBuffer.allocateDirect(buf.position()).order(ByteOrder.nativeOrder());
        buf.flip();
        copy.put(buf);
        copy.flip();
        return copy;
    }

    /**
     * Reset all buffers for reuse (new chunk compilation).
     */
    public void clear() {
        for (Map.Entry<PauCTerrainRenderPass, ByteBuffer> entry : this.buffers.entrySet()) {
            entry.getValue().clear();
            this.vertexCounts.put(entry.getKey(), 0);
        }
    }

    /**
     * Release all buffers.
     */
    public void destroy() {
        this.buffers.clear();
        this.vertexCounts.clear();
    }

    private ByteBuffer grow(PauCTerrainRenderPass pass, ByteBuffer old) {
        int newCapacity = Math.min(old.capacity() * 2, MAX_CAPACITY);
        if (newCapacity <= old.capacity()) {
            throw new RuntimeException("Chunk build buffer overflow for pass " + pass
                    + " (max " + MAX_CAPACITY + " bytes)");
        }

        ByteBuffer newBuf = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
        old.flip();
        newBuf.put(old);
        this.buffers.put(pass, newBuf);
        return newBuf;
    }
}
