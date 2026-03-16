package pauc.pain_au_choc.render.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Wrapper around an OpenGL Framebuffer Object (FBO).
 * Used for off-screen rendering (shadow maps, GBuffers, post-process, DRS).
 */
public class PauCGlFramebuffer implements AutoCloseable {

    private int handle;
    private int width, height;
    private boolean deleted = false;

    /** Color attachment texture IDs (up to 8). */
    private final int[] colorAttachments = new int[8];
    private int colorAttachmentCount = 0;

    /** Depth attachment texture ID. */
    private int depthAttachment = 0;

    public PauCGlFramebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.handle = GL30.glGenFramebuffers();
    }

    /** Bind this FBO as the active framebuffer. */
    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.handle);
        GL11.glViewport(0, 0, this.width, this.height);
    }

    /** Bind the default framebuffer (screen). */
    public static void bindDefault() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Attach a 2D texture as a color attachment.
     * @param index       Attachment index (0-7)
     * @param textureId   OpenGL texture ID
     */
    public void attachColorTexture(int index, int textureId) {
        bind();
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0 + index, GL11.GL_TEXTURE_2D, textureId, 0);
        this.colorAttachments[index] = textureId;
        this.colorAttachmentCount = Math.max(this.colorAttachmentCount, index + 1);
        bindDefault();
    }

    /**
     * Attach a 2D texture as the depth attachment.
     */
    public void attachDepthTexture(int textureId) {
        bind();
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, textureId, 0);
        this.depthAttachment = textureId;
        bindDefault();
    }

    /**
     * Attach a depth-stencil renderbuffer or texture.
     */
    public void attachDepthStencilTexture(int textureId) {
        bind();
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, textureId, 0);
        this.depthAttachment = textureId;
        bindDefault();
    }

    /**
     * Set which color attachments to draw to.
     */
    public void setDrawBuffers() {
        bind();
        if (this.colorAttachmentCount == 0) {
            GL20.glDrawBuffers(GL11.GL_NONE);
        } else {
            int[] buffers = new int[this.colorAttachmentCount];
            for (int i = 0; i < this.colorAttachmentCount; i++) {
                buffers[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
            }
            GL20.glDrawBuffers(buffers);
        }
        bindDefault();
    }

    /** Check if the FBO is complete (all required attachments present). */
    public boolean isComplete() {
        bind();
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        bindDefault();
        return status == GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    /** Resize (requires re-creating textures externally). */
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getHandle() { return handle; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getColorAttachment(int index) { return colorAttachments[index]; }
    public int getDepthAttachment() { return depthAttachment; }

    @Override
    public void close() {
        if (!deleted) {
            GL30.glDeleteFramebuffers(this.handle);
            this.handle = 0;
            this.deleted = true;
        }
    }
}
