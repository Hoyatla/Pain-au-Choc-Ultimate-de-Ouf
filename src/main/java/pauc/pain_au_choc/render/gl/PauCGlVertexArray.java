package pauc.pain_au_choc.render.gl;

import org.lwjgl.opengl.GL30;

/**
 * Wrapper around an OpenGL Vertex Array Object (VAO).
 * Encapsulates vertex attribute configuration for efficient switching.
 */
public class PauCGlVertexArray implements AutoCloseable {

    private int handle;
    private boolean deleted = false;

    public PauCGlVertexArray() {
        this.handle = GL30.glGenVertexArrays();
    }

    /** Bind this VAO as the active vertex array. */
    public void bind() {
        GL30.glBindVertexArray(this.handle);
    }

    /** Unbind any active VAO. */
    public static void unbind() {
        GL30.glBindVertexArray(0);
    }

    public int getHandle() { return handle; }
    public boolean isDeleted() { return deleted; }

    @Override
    public void close() {
        if (!deleted) {
            GL30.glDeleteVertexArrays(this.handle);
            this.handle = 0;
            this.deleted = true;
        }
    }
}
