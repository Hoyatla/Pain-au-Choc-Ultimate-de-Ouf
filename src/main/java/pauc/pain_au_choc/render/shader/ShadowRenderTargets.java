package pauc.pain_au_choc.render.shader;

import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/**
 * Shadow map render targets for the deferred pipeline.
 * Provides two depth textures (shadowtex0, shadowtex1) and a color
 * texture (shadowcolor0) for colored shadow support.
 *
 * shadowtex0: Depth from light's perspective (includes translucent occluders)
 * shadowtex1: Depth without translucent occluders
 * shadowcolor0: RGBA8 for colored shadow effects (stained glass, etc.)
 *
 * Adapted from Oculus/Iris ShadowRenderTargets.
 */
public class ShadowRenderTargets implements AutoCloseable {

    private int framebuffer;
    private int shadowDepth0;   // shadowtex0
    private int shadowDepth1;   // shadowtex1
    private int shadowColor0;   // shadowcolor0
    private int resolution;
    private boolean initialized = false;

    /**
     * Create shadow map targets at the specified resolution.
     *
     * @param resolution Shadow map width and height (square)
     */
    public void create(int resolution) {
        if (this.initialized) {
            destroy();
        }

        this.resolution = resolution;

        // Create FBO
        this.framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);

        // Create depth textures
        this.shadowDepth0 = createDepthTexture(resolution);
        this.shadowDepth1 = createDepthTexture(resolution);

        // Create color texture for colored shadows
        this.shadowColor0 = createColorTexture(resolution);

        // Attach
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.shadowDepth0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.shadowColor0, 0);

        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});

        // Check completeness
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[PAUC Shadow] Framebuffer incomplete! Status: 0x"
                    + Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        this.initialized = true;
    }

    /**
     * Bind the shadow FBO for rendering.
     */
    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL11.glViewport(0, 0, this.resolution, this.resolution);
    }

    /**
     * Unbind the shadow FBO.
     */
    public static void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Bind shadow textures for reading in shader programs.
     *
     * @param baseUnit Starting texture unit (shadowtex0 = baseUnit, shadowtex1 = baseUnit+1, etc.)
     */
    public void bindTextures(int baseUnit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + baseUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepth0);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + baseUnit + 1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepth1);

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + baseUnit + 2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowColor0);
    }

    public int getShadowDepth0() { return shadowDepth0; }
    public int getShadowDepth1() { return shadowDepth1; }
    public int getShadowColor0() { return shadowColor0; }
    public int getResolution() { return resolution; }
    public boolean isInitialized() { return initialized; }

    private void destroy() {
        if (this.shadowDepth0 != 0) GL11.glDeleteTextures(this.shadowDepth0);
        if (this.shadowDepth1 != 0) GL11.glDeleteTextures(this.shadowDepth1);
        if (this.shadowColor0 != 0) GL11.glDeleteTextures(this.shadowColor0);
        if (this.framebuffer != 0) GL30.glDeleteFramebuffers(this.framebuffer);
        this.shadowDepth0 = 0;
        this.shadowDepth1 = 0;
        this.shadowColor0 = 0;
        this.framebuffer = 0;
        this.initialized = false;
    }

    @Override
    public void close() {
        destroy();
    }

    private static int createDepthTexture(int resolution) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT,
                resolution, resolution, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        // Enable comparison mode for hardware shadow sampling
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        return texture;
    }

    private static int createColorTexture(int resolution) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                resolution, resolution, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        return texture;
    }
}
