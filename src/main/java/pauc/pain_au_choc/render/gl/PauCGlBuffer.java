package pauc.pain_au_choc.render.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/**
 * Lightweight wrapper around an OpenGL buffer object (VBO / IBO).
 * Manages lifecycle and provides typed access.
 */
public class PauCGlBuffer implements AutoCloseable {

    public enum Type {
        VERTEX(GL15.GL_ARRAY_BUFFER),
        INDEX(GL15.GL_ELEMENT_ARRAY_BUFFER),
        UNIFORM(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER);

        public final int glTarget;

        Type(int glTarget) {
            this.glTarget = glTarget;
        }
    }

    public enum Usage {
        STATIC(GL15.GL_STATIC_DRAW),
        DYNAMIC(GL15.GL_DYNAMIC_DRAW),
        STREAM(GL15.GL_STREAM_DRAW);

        public final int glUsage;

        Usage(int glUsage) {
            this.glUsage = glUsage;
        }
    }

    private int handle;
    private final Type type;
    private long allocatedSize = 0;
    private boolean deleted = false;

    public PauCGlBuffer(Type type) {
        this.type = type;
        this.handle = GL15.glGenBuffers();
    }

    /** Bind this buffer to its target. */
    public void bind() {
        GL15.glBindBuffer(this.type.glTarget, this.handle);
    }

    /** Unbind the target. */
    public void unbind() {
        GL15.glBindBuffer(this.type.glTarget, 0);
    }

    /**
     * Allocate storage and optionally upload data.
     * @param size Size in bytes
     * @param usage Expected usage pattern
     */
    public void allocate(long size, Usage usage) {
        bind();
        GL15.glBufferData(this.type.glTarget, size, usage.glUsage);
        this.allocatedSize = size;
        unbind();
    }

    /**
     * Upload data to the buffer.
     * @param data   Direct ByteBuffer containing the data
     * @param usage  Usage hint
     */
    public void upload(java.nio.ByteBuffer data, Usage usage) {
        bind();
        GL15.glBufferData(this.type.glTarget, data, usage.glUsage);
        this.allocatedSize = data.remaining();
        unbind();
    }

    /**
     * Upload a sub-region of data.
     */
    public void uploadSub(long offset, java.nio.ByteBuffer data) {
        bind();
        GL15.glBufferSubData(this.type.glTarget, offset, data);
        unbind();
    }

    public int getHandle() { return handle; }
    public Type getType() { return type; }
    public long getAllocatedSize() { return allocatedSize; }
    public boolean isDeleted() { return deleted; }

    @Override
    public void close() {
        if (!deleted) {
            GL15.glDeleteBuffers(this.handle);
            this.handle = 0;
            this.deleted = true;
        }
    }

    @Override
    public String toString() {
        return "PauCGlBuffer[" + handle + " type=" + type + " size=" + allocatedSize + "]";
    }
}
