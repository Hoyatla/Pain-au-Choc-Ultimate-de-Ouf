package fr.hoyatla.pauc.lodengine;

import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Global shared quad index buffer for LOD rendering. All LOD VBOs share this single IBO,
 * eliminating per-VBO index allocation and upload. Each quad = 4 vertices = 6 indices (2 triangles).
 *
 * <p>Ported from Distant Horizons {@code GlQuadIndexBuffer.java} (LGPL v3).</p>
 */
public final class PauCQuadIndexBuffer implements AutoCloseable {
    private final PauCGpuBufferTracker.TrackedBuffer buffer;
    private int maxQuadCount;
    private boolean uploaded;

    public PauCQuadIndexBuffer() {
        this.buffer = PauCGpuBufferTracker.getInstance().createElementBuffer();
        this.maxQuadCount = 0;
        this.uploaded = false;
    }

    /**
     * Upload the shared index pattern for up to {@code maxQuadCount} quads.
     * Pattern: [0,1,2, 0,2,3, 4,5,6, 4,6,7, ...] — each quad emits 6 indices.
     */
    public void upload(int maxQuads) {
        if (maxQuads <= this.maxQuadCount && this.uploaded) return;
        this.maxQuadCount = maxQuads;

        ByteBuffer bb = MemoryUtil.memAlloc(maxQuads * 6 * 4);
        try {
            IntBuffer ib = bb.asIntBuffer();
            for (int q = 0; q < maxQuads; q++) {
                int base = q * 4;
                ib.put(base).put(base + 1).put(base + 2);
                ib.put(base).put(base + 2).put(base + 3);
            }
            bb.flip();
            buffer.uploadFull(bb, GL32.GL_STATIC_DRAW);
            this.uploaded = true;
        } finally {
            MemoryUtil.memFree(bb);
        }
    }

    public void bind() { buffer.bind(); }
    public void unbind() { buffer.unbind(); }
    public int getMaxQuadCount() { return maxQuadCount; }

    /** Index count for N quads. */
    public static int indexCountForQuads(int quadCount) { return quadCount * 6; }

    @Override
    public void close() {
        buffer.close();
        this.uploaded = false;
    }
}
