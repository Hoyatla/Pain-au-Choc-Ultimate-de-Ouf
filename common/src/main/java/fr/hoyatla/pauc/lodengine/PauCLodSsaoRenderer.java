package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.FloatBuffer;

/**
 * Two-pass SSAO post-processor for PauC LOD terrain.
 * Pass 1: spiral sampling with IGN dithering -> GL_R16F occlusion texture.
 * Pass 2: bilateral Gaussian blur for edge-preserving denoise.
 *
 * <p>Ported from Distant Horizons GlDhSSAORenderer (LGPL v3).</p>
 */
public final class PauCLodSsaoRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PauCLodSsaoRenderer INSTANCE = new PauCLodSsaoRenderer();
    public static PauCLodSsaoRenderer getInstance() { return INSTANCE; }

    private int ssaoFbo;
    private int ssaoTex;
    private int blurFbo;
    private int blurTex;
    private int aoShader;
    private int applyShader;
    private int quadVao;
    private int quadVbo;
    private boolean initialized;
    private int lastWidth;
    private int lastHeight;

    private boolean enabled = true;
    private int sampleCount = 16;
    private float radius = 2.0f;
    private float strength = 1.0f;
    private float minLight = 0.0f;
    private float bias = 0.1f;
    private float fadeDistance = 64.0f;
    private int blurRadius = 2;

    private PauCLodSsaoRenderer() {}

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    public void configure(int sampleCount, float radius, float strength, int blurRadius) {
        this.sampleCount = sampleCount;
        this.radius = radius;
        this.strength = strength;
        this.blurRadius = blurRadius;
    }

    public void init(int width, int height) {
        if (initialized && width == lastWidth && height == lastHeight) return;
        destroy();

        this.lastWidth = width;
        this.lastHeight = height;
        this.aoShader = compileShader("pauc/shared/quad_apply.vert", "pauc/ssao/ao.frag");
        this.applyShader = compileShader("pauc/shared/quad_apply.vert", "pauc/ssao/apply.frag");
        if (aoShader == 0 || applyShader == 0) {
            LOGGER.warn("PauC SSAO renderer disabled: shader compilation failed");
            this.enabled = false;
            return;
        }

        this.ssaoFbo = GL32.glGenFramebuffers();
        this.ssaoTex = createR16FTexture(width, height);
        GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, ssaoFbo);
        GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D, ssaoTex, 0);

        this.blurFbo = GL32.glGenFramebuffers();
        this.blurTex = createR16FTexture(width, height);
        GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, blurFbo);
        GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D, blurTex, 0);
        GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, 0);

        this.quadVao = GL32.glGenVertexArrays();
        this.quadVbo = GL32.glGenBuffers();
        GL32.glBindVertexArray(quadVao);
        FloatBuffer quadVerts = MemoryUtil.memAllocFloat(12);
        quadVerts.put(new float[]{-1,-1, 1,-1, 1,1, -1,-1, 1,1, -1,1}).flip();
        GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, quadVbo);
        GL32.glBufferData(GL32.GL_ARRAY_BUFFER, quadVerts, GL32.GL_STATIC_DRAW);
        GL32.glEnableVertexAttribArray(0);
        GL32.glVertexAttribPointer(0, 2, GL32.GL_FLOAT, false, 0, 0);
        GL32.glBindVertexArray(0);
        MemoryUtil.memFree(quadVerts);

        this.initialized = true;
    }

    /**
     * Render SSAO and apply bilateral blur.
     * @param depthTex the LOD depth texture (DEPTH32F)
     * @param projMatrix the current projection matrix (float[16])
     * @param invProjMatrix the inverse projection matrix (float[16])
     */
    public void render(int depthTex, float[] projMatrix, float[] invProjMatrix) {
        if (!enabled || !initialized) return;

        // Pass 1: compute SSAO
        try (PauCGLState ignored = new PauCGLState()) {
            GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, ssaoFbo);
            GL32.glViewport(0, 0, lastWidth, lastHeight);
            GL32.glClear(GL32.GL_COLOR_BUFFER_BIT);

            GL32.glUseProgram(aoShader);
            setUniform1i(aoShader, "uDepthMap", 0);
            setUniform1i(aoShader, "uSampleCount", sampleCount);
            setUniform1f(aoShader, "uRadius", radius);
            setUniform1f(aoShader, "uStrength", strength);
            setUniform1f(aoShader, "uMinLight", minLight);
            setUniform1f(aoShader, "uBias", bias);
            setUniformMatrix4f(aoShader, "uInvProj", invProjMatrix);
            setUniformMatrix4f(aoShader, "uProj", projMatrix);
            setUniform1f(aoShader, "uFadeDistanceInBlocks", fadeDistance);

            GL32.glActiveTexture(GL32.GL_TEXTURE0);
            GL32.glBindTexture(GL32.GL_TEXTURE_2D, depthTex);
            drawQuad();
        }

        // Pass 2: bilateral blur
        try (PauCGLState ignored = new PauCGLState()) {
            GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, blurFbo);
            GL32.glViewport(0, 0, lastWidth, lastHeight);
            GL32.glClear(GL32.GL_COLOR_BUFFER_BIT);

            GL32.glUseProgram(applyShader);
            setUniform1i(applyShader, "gSSAOMap", 0);
            setUniform1i(applyShader, "gDepthMap", 1);
            setUniform2f(applyShader, "gViewSize", lastWidth, lastHeight);
            setUniform1i(applyShader, "gBlurRadius", blurRadius);
            setUniform1f(applyShader, "gNear", 0.05f);
            setUniform1f(applyShader, "gFar", 1024.0f);

            GL32.glActiveTexture(GL32.GL_TEXTURE0);
            GL32.glBindTexture(GL32.GL_TEXTURE_2D, ssaoTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE1);
            GL32.glBindTexture(GL32.GL_TEXTURE_2D, depthTex);
            drawQuad();
        }
    }

    /** Get the final SSAO texture after blur. Bind to a texture unit before terrain rendering. */
    public int getBlurredSsaoTexture() { return blurTex; }

    private void drawQuad() {
        GL32.glBindVertexArray(quadVao);
        GL32.glDrawArrays(GL32.GL_TRIANGLES, 0, 6);
        GL32.glBindVertexArray(0);
    }

    public void destroy() {
        if (!initialized) return;
        GL32.glDeleteFramebuffers(ssaoFbo);
        GL32.glDeleteTextures(ssaoTex);
        GL32.glDeleteFramebuffers(blurFbo);
        GL32.glDeleteTextures(blurTex);
        GL32.glDeleteProgram(aoShader);
        GL32.glDeleteProgram(applyShader);
        GL32.glDeleteVertexArrays(quadVao);
        GL32.glDeleteBuffers(quadVbo);
        initialized = false;
    }

    // ---- Helpers ----

    private static int compileShader(String vertPath, String fragPath) {
        try {
            int vert = compileStage(GL32.GL_VERTEX_SHADER, loadResource(vertPath));
            int frag = compileStage(GL32.GL_FRAGMENT_SHADER, loadResource(fragPath));
            int program = GL32.glCreateProgram();
            GL32.glAttachShader(program, vert);
            GL32.glAttachShader(program, frag);
            GL32.glBindAttribLocation(program, 0, "vPosition");
            GL32.glLinkProgram(program);
            if (GL32.glGetProgrami(program, GL32.GL_LINK_STATUS) == 0) {
                LOGGER.error("PauC SSAO shader link error: {}", GL32.glGetProgramInfoLog(program));
            }
            GL32.glDeleteShader(vert);
            GL32.glDeleteShader(frag);
            return program;
        } catch (Exception e) {
            LOGGER.error("Failed to compile SSAO shader: {}", e.getMessage());
            return 0;
        }
    }

    private static int compileStage(int type, String source) {
        int shader = GL32.glCreateShader(type);
        GL32.glShaderSource(shader, source);
        GL32.glCompileShader(shader);
        if (GL32.glGetShaderi(shader, GL32.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("PauC SSAO {} compile error: {}", type == GL32.GL_VERTEX_SHADER ? "vert" : "frag", GL32.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static String loadResource(String path) {
        String fullPath = "/assets/paucultimate/shaders/" + path;
        try (var is = PauCLodSsaoRenderer.class.getResourceAsStream(fullPath)) {
            if (is == null) throw new RuntimeException("Shader not found: " + fullPath);
            return new String(is.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private static int createR16FTexture(int w, int h) {
        int tex = GL32.glGenTextures();
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, tex);
        GL32.glTexImage2D(GL32.GL_TEXTURE_2D, 0, GL32.GL_R16F, w, h, 0, GL32.GL_RED, GL32.GL_HALF_FLOAT, 0L);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MIN_FILTER, GL32.GL_LINEAR);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MAG_FILTER, GL32.GL_LINEAR);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_WRAP_S, GL32.GL_CLAMP_TO_EDGE);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_WRAP_T, GL32.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static void setUniform1i(int p, String n, int v) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniform1i(l, v); }
    private static void setUniform1f(int p, String n, float v) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniform1f(l, v); }
    private static void setUniform2f(int p, String n, float x, float y) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniform2f(l, x, y); }
    private static void setUniformMatrix4f(int p, String n, float[] m) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniformMatrix4fv(l, false, m); }
}
