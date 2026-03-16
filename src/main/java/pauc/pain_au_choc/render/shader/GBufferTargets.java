package pauc.pain_au_choc.render.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Manages the GBuffer framebuffer and its multiple color/depth attachments.
 * Implements the OptiFine-compatible colortex0-7 and depthtex0-2 system.
 *
 * GBuffer layout (OptiFine standard):
 * - colortex0 (gcolor):    RGBA8  - Albedo + alpha
 * - colortex1 (gdepth):    RGBA16 - Normal (XYZ) + specular roughness
 * - colortex2 (gnormal):   RGBA16 - Additional normals / data
 * - colortex3 (composite): RGBA8  - Specular map / material ID
 * - colortex4-7:           RGBA8  - Custom shaderpack data
 * - depthtex0:             DEPTH24_STENCIL8 - Full scene depth
 * - depthtex1:             DEPTH24_STENCIL8 - Depth without translucents
 * - depthtex2:             DEPTH24_STENCIL8 - Depth without hand
 *
 * Adapted from Oculus/Iris GbufferRendertargets.
 */
public class GBufferTargets implements AutoCloseable {

    /** Maximum number of color attachments. */
    public static final int MAX_COLOR_TARGETS = 8;

    /** Maximum number of depth attachments. */
    public static final int MAX_DEPTH_TARGETS = 3;

    /** The FBO id. */
    private int framebuffer;

    /** Color texture attachments (colortex0 through colortex7). */
    private final int[] colorTextures = new int[MAX_COLOR_TARGETS];

    /** Depth texture attachments (depthtex0 through depthtex2). */
    private final int[] depthTextures = new int[MAX_DEPTH_TARGETS];

    /** Resolution. */
    private int width;
    private int height;

    /** Which color targets are actually allocated. */
    private int allocatedColorTargets = 0;

    /** Whether the GBuffers are initialized. */
    private boolean initialized = false;

    /**
     * Create GBuffer targets at the specified resolution.
     *
     * @param width            Framebuffer width
     * @param height           Framebuffer height
     * @param numColorTargets  Number of color attachments to allocate (1-8)
     */
    public void create(int width, int height, int numColorTargets) {
        if (this.initialized) {
            destroy();
        }

        this.width = width;
        this.height = height;
        this.allocatedColorTargets = Math.min(numColorTargets, MAX_COLOR_TARGETS);

        // Create FBO
        this.framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);

        // Create color attachments
        for (int i = 0; i < this.allocatedColorTargets; i++) {
            this.colorTextures[i] = createColorTexture(i, width, height);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0 + i,
                    GL11.GL_TEXTURE_2D, this.colorTextures[i], 0);
        }

        // Create depth attachments
        for (int i = 0; i < MAX_DEPTH_TARGETS; i++) {
            this.depthTextures[i] = createDepthTexture(width, height);
        }

        // Attach primary depth
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.depthTextures[0], 0);

        // Set draw buffers
        int[] drawBuffers = new int[this.allocatedColorTargets];
        for (int i = 0; i < this.allocatedColorTargets; i++) {
            drawBuffers[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
        }
        GL20.glDrawBuffers(drawBuffers);

        // Check completeness
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[PAUC GBuffer] Framebuffer incomplete! Status: 0x"
                    + Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        this.initialized = true;
    }

    /**
     * Bind this GBuffer for rendering.
     */
    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL11.glViewport(0, 0, this.width, this.height);
    }

    /**
     * Unbind (back to default framebuffer).
     */
    public static void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Clear all GBuffer attachments.
     */
    public void clear() {
        bind();
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Bind a color texture to a texture unit for reading.
     *
     * @param colorIndex   The colortex index (0-7)
     * @param textureUnit  The GL texture unit (GL_TEXTURE0 + n)
     */
    public void bindColorTexture(int colorIndex, int textureUnit) {
        if (colorIndex < 0 || colorIndex >= this.allocatedColorTargets) return;
        GL13.glActiveTexture(textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.colorTextures[colorIndex]);
    }

    /**
     * Bind a depth texture to a texture unit for reading.
     *
     * @param depthIndex   The depthtex index (0-2)
     * @param textureUnit  The GL texture unit (GL_TEXTURE0 + n)
     */
    public void bindDepthTexture(int depthIndex, int textureUnit) {
        if (depthIndex < 0 || depthIndex >= MAX_DEPTH_TARGETS) return;
        GL13.glActiveTexture(textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.depthTextures[depthIndex]);
    }

    /**
     * Get a color texture handle for shader sampler binding.
     */
    public int getColorTexture(int index) {
        if (index < 0 || index >= this.allocatedColorTargets) return 0;
        return this.colorTextures[index];
    }

    /**
     * Get a depth texture handle.
     */
    public int getDepthTexture(int index) {
        if (index < 0 || index >= MAX_DEPTH_TARGETS) return 0;
        return this.depthTextures[index];
    }

    public int getFramebuffer() { return framebuffer; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getAllocatedColorTargets() { return allocatedColorTargets; }
    public boolean isInitialized() { return initialized; }

    /**
     * Resize the GBuffers. Destroys and recreates all textures.
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == this.width && newHeight == this.height) return;
        create(newWidth, newHeight, this.allocatedColorTargets);
    }

    private void destroy() {
        for (int i = 0; i < MAX_COLOR_TARGETS; i++) {
            if (this.colorTextures[i] != 0) {
                GL11.glDeleteTextures(this.colorTextures[i]);
                this.colorTextures[i] = 0;
            }
        }
        for (int i = 0; i < MAX_DEPTH_TARGETS; i++) {
            if (this.depthTextures[i] != 0) {
                GL11.glDeleteTextures(this.depthTextures[i]);
                this.depthTextures[i] = 0;
            }
        }
        if (this.framebuffer != 0) {
            GL30.glDeleteFramebuffers(this.framebuffer);
            this.framebuffer = 0;
        }
        this.initialized = false;
    }

    @Override
    public void close() {
        destroy();
    }

    // ---- Internal texture creation ----

    private static int createColorTexture(int index, int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);

        // Choose format based on index (OptiFine standard)
        int internalFormat;
        if (index == 0) {
            internalFormat = GL11.GL_RGBA8; // Albedo
        } else if (index <= 2) {
            internalFormat = GL30.GL_RGBA16F; // Normals, specular (16-bit float)
        } else {
            internalFormat = GL11.GL_RGBA8; // Custom data
        }

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat,
                width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);

        return texture;
    }

    private static int createDepthTexture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8,
                width, height, 0, GL30.GL_DEPTH_STENCIL,
                GL30.GL_UNSIGNED_INT_24_8, (java.nio.ByteBuffer) null);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);

        return texture;
    }
}
