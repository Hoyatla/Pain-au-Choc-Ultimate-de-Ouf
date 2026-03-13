package pauc.pain_au_choc.render.gl;

import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * Shared index buffer for quad rendering (two triangles per quad).
 * Pre-generates indices for a large number of quads so individual sections
 * don't need their own index buffers.
 *
 * Quad vertex order: 0-1-2, 0-2-3 (two triangles per quad).
 *
 * Adapted from Embeddium's SharedQuadIndexBuffer concept.
 */
public class PauCSharedQuadIndexBuffer implements AutoCloseable {

    /** Default capacity: enough for 65536 quads (262144 vertices, 393216 indices). */
    private static final int DEFAULT_MAX_QUADS = 65536;

    private int handle;
    private int maxQuads;
    private int indexCount;
    private boolean deleted = false;

    /** Singleton instance. */
    private static PauCSharedQuadIndexBuffer instance;

    public static PauCSharedQuadIndexBuffer getInstance() {
        if (instance == null || instance.deleted) {
            instance = new PauCSharedQuadIndexBuffer(DEFAULT_MAX_QUADS);
        }
        return instance;
    }

    public static void destroyInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public PauCSharedQuadIndexBuffer(int maxQuads) {
        this.maxQuads = maxQuads;
        this.indexCount = maxQuads * 6; // 6 indices per quad (2 triangles)
        this.handle = GL15.glGenBuffers();

        // Generate index data
        IntBuffer indices = MemoryUtil.memAllocInt(this.indexCount);
        try {
            for (int quad = 0; quad < maxQuads; quad++) {
                int base = quad * 4; // 4 vertices per quad
                // Triangle 1: 0, 1, 2
                indices.put(base);
                indices.put(base + 1);
                indices.put(base + 2);
                // Triangle 2: 0, 2, 3
                indices.put(base);
                indices.put(base + 2);
                indices.put(base + 3);
            }
            indices.flip();

            // Upload to GPU
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.handle);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    /** Bind this index buffer. */
    public void bind() {
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.handle);
    }

    /** Unbind the element array buffer. */
    public static void unbind() {
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * Get the number of indices needed for a given number of quads.
     */
    public static int getIndexCount(int quadCount) {
        return quadCount * 6;
    }

    /**
     * Get the number of indices needed for a given vertex count (assuming quads).
     */
    public static int getIndexCountForVertices(int vertexCount) {
        return getIndexCount(vertexCount / 4);
    }

    public int getHandle() { return handle; }
    public int getMaxQuads() { return maxQuads; }
    public int getIndexCount() { return indexCount; }

    @Override
    public void close() {
        if (!deleted) {
            GL15.glDeleteBuffers(this.handle);
            this.handle = 0;
            this.deleted = true;
        }
    }
}
