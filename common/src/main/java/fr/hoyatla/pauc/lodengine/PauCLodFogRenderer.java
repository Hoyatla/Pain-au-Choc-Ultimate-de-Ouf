package fr.hoyatla.pauc.lodengine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Dual-pass fog post-processor for PauC LOD terrain.
 * Pass 1: renders fog intensity to an RGBA16 texture using depth-reconstructed world position.
 * Pass 2: composites fog onto the LOD framebuffer.
 *
 * <p>Ported from Distant Horizons GlDhFogRenderer/GlDhFogShader (LGPL v3).</p>
 */
public final class PauCLodFogRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PauCLodFogRenderer INSTANCE = new PauCLodFogRenderer();
    public static PauCLodFogRenderer getInstance() { return INSTANCE; }

    private int fogFbo;
    private int fogColorTex;
    private int fogDepthTex;
    private int fogShader;
    private int applyShader;
    private int quadVao;
    private int quadVbo;
    private boolean initialized;
    private int lastWidth;
    private int lastHeight;

    // Fog config
    private boolean enabled = true;
    private float fogScale = 1.0f;
    private float fogVerticalScale = 1.0f;
    private float farFogStart = 0.4f;
    private float farFogLength = 0.6f;
    private float farFogMin = 0.0f;
    private float farFogRange = 1.0f;
    private float farFogDensity = 2.5f;
    private int farFogFalloffType = 2; // EXP2
    private boolean heightFogEnabled = false;
    private float heightFogStart = 0.0f;
    private float heightFogLength = 1.0f;
    private float heightFogMin = 0.0f;
    private float heightFogRange = 1.0f;
    private float heightFogDensity = 1.0f;
    private int heightFogFalloffType = 2;
    private boolean heightBasedOnCamera = true;
    private float heightFogBaseHeight = 64.0f;
    private boolean heightFogAppliesUp = false;
    private boolean heightFogAppliesDown = true;
    private int heightFogMixingMode = 0;
    private boolean useSphericalFog = false;
    private float[] fogColor = new float[]{0.5f, 0.6f, 0.8f, 1.0f};

    private PauCLodFogRenderer() {}

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    public void configureFog(float farStart, float farEnd, float density, int falloffType,
                              boolean heightEnabled, float heightDensity, int heightMixMode) {
        this.farFogStart = farStart;
        this.farFogLength = Math.max(farEnd - farStart, 0.01f);
        this.farFogDensity = density;
        this.farFogFalloffType = falloffType;
        this.heightFogEnabled = heightEnabled;
        this.heightFogDensity = heightDensity;
        this.heightFogMixingMode = heightMixMode;
    }

    public void setFogColor(float r, float g, float b) {
        fogColor[0] = r; fogColor[1] = g; fogColor[2] = b;
    }

    public void init(int width, int height) {
        if (initialized && width == lastWidth && height == lastHeight) return;
        destroy();

        this.lastWidth = width;
        this.lastHeight = height;
        this.fogShader = compileShader("pauc/fog/quad_apply.vert", "pauc/fog/fog.frag");
        this.applyShader = compileShader("pauc/fog/quad_apply.vert", "pauc/fog/apply.frag");

        this.fogFbo = GL32.glGenFramebuffers();
        this.fogColorTex = createColorTexture(width, height, GL32.GL_RGBA16, GL32.GL_RGBA, GL32.GL_UNSIGNED_SHORT);
        this.fogDepthTex = createColorTexture(width, height, GL32.GL_DEPTH_COMPONENT32F, GL32.GL_DEPTH_COMPONENT, GL32.GL_FLOAT);

        GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, fogFbo);
        GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D, fogColorTex, 0);
        GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_DEPTH_ATTACHMENT, GL32.GL_TEXTURE_2D, fogDepthTex, 0);
        GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, 0);

        this.quadVao = GL32.glGenVertexArrays();
        this.quadVbo = GL32.glGenBuffers();
        GL32.glBindVertexArray(quadVao);
        FloatBuffer quadVerts = org.lwjgl.system.MemoryUtil.memAllocFloat(8);
        quadVerts.put(new float[]{-1,-1, 1,-1, 1,1, -1,-1, 1,1, -1,1}).flip();
        GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, quadVbo);
        GL32.glBufferData(GL32.GL_ARRAY_BUFFER, quadVerts, GL32.GL_STATIC_DRAW);
        GL32.glEnableVertexAttribArray(0);
        GL32.glVertexAttribPointer(0, 2, GL32.GL_FLOAT, false, 0, 0);
        GL32.glBindVertexArray(0);
        org.lwjgl.system.MemoryUtil.memFree(quadVerts);

        this.initialized = true;
    }

    /**
     * Render fog onto the given color texture using the given depth texture.
     * Assumes the caller has already bound their LOD framebuffer.
     */
    public void renderFog(int lodColorTex, int lodDepthTex, int mcDepthTex,
                           float[] projMatrix, float[] invMvpProjMatrix,
                           float renderDistance, float cameraBlockY) {
        if (!enabled || !initialized) return;

        float scaledStart = farFogStart * renderDistance;
        float scaledEnd = farFogLength * renderDistance;

        // Pass 1: render fog to fog FBO
        try (PauCGLState ignored = new PauCGLState()) {
            GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, fogFbo);
            GL32.glViewport(0, 0, lastWidth, lastHeight);
            GL32.glClear(GL32.GL_COLOR_BUFFER_BIT);

            GL32.glUseProgram(fogShader);
            setUniform1i(fogShader, "uDepthMap", 0);
            setUniform4f(fogShader, "uFogColor", fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            setUniform1f(fogShader, "uFogScale", fogScale);
            setUniform1f(fogShader, "uFogVerticalScale", fogVerticalScale);
            setUniformMatrix4f(fogShader, "uInvMvmProj", invMvpProjMatrix);
            setUniform1f(fogShader, "uFarFogStart", scaledStart);
            setUniform1f(fogShader, "uFarFogLength", Math.max(scaledEnd - scaledStart, 0.01f));
            setUniform1f(fogShader, "uFarFogMin", farFogMin);
            setUniform1f(fogShader, "uFarFogRange", farFogRange);
            setUniform1f(fogShader, "uFarFogDensity", farFogDensity);
            setUniform1i(fogShader, "uFarFogFalloffType", farFogFalloffType);
            setUniform1i(fogShader, "uHeightFogEnabled", heightFogEnabled ? 1 : 0);
            setUniform1f(fogShader, "uHeightFogStart", heightFogStart);
            setUniform1f(fogShader, "uHeightFogLength", heightFogLength);
            setUniform1f(fogShader, "uHeightFogMin", heightFogMin);
            setUniform1f(fogShader, "uHeightFogRange", heightFogRange);
            setUniform1f(fogShader, "uHeightFogDensity", heightFogDensity);
            setUniform1i(fogShader, "uHeightFogFalloffType", heightFogFalloffType);
            setUniform1i(fogShader, "uHeightBasedOnCamera", heightBasedOnCamera ? 1 : 0);
            setUniform1f(fogShader, "uHeightFogBaseHeight", heightFogBaseHeight);
            setUniform1i(fogShader, "uHeightFogAppliesUp", heightFogAppliesUp ? 1 : 0);
            setUniform1i(fogShader, "uHeightFogAppliesDown", heightFogAppliesDown ? 1 : 0);
            setUniform1i(fogShader, "uHeightFogMixingMode", heightFogMixingMode);
            setUniform1f(fogShader, "uCameraBlockYPos", cameraBlockY);
            setUniform1i(fogShader, "uUseSphericalFog", useSphericalFog ? 1 : 0);

            GL32.glActiveTexture(GL32.GL_TEXTURE0);
            GL32.glBindTexture(GL32.GL_TEXTURE_2D, lodDepthTex);
            drawQuad();
        }

        // Pass 2: composite fog onto LOD color
        try (PauCGLState ignored = new PauCGLState()) {
            GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, 0);
            GL32.glEnable(GL32.GL_BLEND);
            GL32.glBlendFunc(GL32.GL_SRC_ALPHA, GL32.GL_ONE_MINUS_SRC_ALPHA);

            GL32.glUseProgram(applyShader);
            setUniform1i(applyShader, "uFogMap", 0);
            setUniform1i(applyShader, "uColorMap", 1);

            GL32.glActiveTexture(GL32.GL_TEXTURE0);
            GL32.glBindTexture(GL32.GL_TEXTURE_2D, fogColorTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE1);
            GL32.glBindTexture(GL32.GL_TEXTURE_2D, lodColorTex);
            drawQuad();

            GL32.glDisable(GL32.GL_BLEND);
        }
    }

    private void drawQuad() {
        GL32.glBindVertexArray(quadVao);
        GL32.glDrawArrays(GL32.GL_TRIANGLES, 0, 6);
        GL32.glBindVertexArray(0);
    }

    public void destroy() {
        if (!initialized) return;
        GL32.glDeleteFramebuffers(fogFbo);
        GL32.glDeleteTextures(fogColorTex);
        GL32.glDeleteTextures(fogDepthTex);
        GL32.glDeleteProgram(fogShader);
        GL32.glDeleteProgram(applyShader);
        GL32.glDeleteVertexArrays(quadVao);
        GL32.glDeleteBuffers(quadVbo);
        initialized = false;
    }

    // ---- Shader compilation helpers ----

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
                String log = GL32.glGetProgramInfoLog(program);
                LOGGER.error("PauC fog shader link error: {}", log);
            }
            GL32.glDeleteShader(vert);
            GL32.glDeleteShader(frag);
            return program;
        } catch (Exception e) {
            LOGGER.error("Failed to compile fog shader: {}", e.getMessage());
            return 0;
        }
    }

    private static int compileStage(int type, String source) {
        int shader = GL32.glCreateShader(type);
        GL32.glShaderSource(shader, source);
        GL32.glCompileShader(shader);
        if (GL32.glGetShaderi(shader, GL32.GL_COMPILE_STATUS) == 0) {
            String log = GL32.glGetShaderInfoLog(shader);
            LOGGER.error("PauC shader compile error ({}): {}", type == GL32.GL_VERTEX_SHADER ? "vert" : "frag", log);
        }
        return shader;
    }

    private static String loadResource(String path) {
        String fullPath = "/assets/paucultimate/shaders/" + path;
        try (var is = PauCLodFogRenderer.class.getResourceAsStream(fullPath)) {
            if (is == null) throw new RuntimeException("Shader not found: " + fullPath);
            return new String(is.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private static void setUniform1i(int program, String name, int value) {
        int loc = GL32.glGetUniformLocation(program, name);
        if (loc >= 0) GL32.glUniform1i(loc, value);
    }
    private static void setUniform1f(int program, String name, float value) {
        int loc = GL32.glGetUniformLocation(program, name);
        if (loc >= 0) GL32.glUniform1f(loc, value);
    }
    private static void setUniform4f(int program, String name, float x, float y, float z, float w) {
        int loc = GL32.glGetUniformLocation(program, name);
        if (loc >= 0) GL32.glUniform4f(loc, x, y, z, w);
    }
    private static void setUniformMatrix4f(int program, String name, float[] matrix) {
        int loc = GL32.glGetUniformLocation(program, name);
        if (loc >= 0) GL32.glUniformMatrix4fv(loc, false, matrix);
    }

    private static int createColorTexture(int w, int h, int internalFormat, int format, int type) {
        int tex = GL32.glGenTextures();
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, tex);
        GL32.glTexImage2D(GL32.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, 0L);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MIN_FILTER, GL32.GL_LINEAR);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_MAG_FILTER, GL32.GL_LINEAR);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_WRAP_S, GL32.GL_CLAMP_TO_EDGE);
        GL32.glTexParameteri(GL32.GL_TEXTURE_2D, GL32.GL_TEXTURE_WRAP_T, GL32.GL_CLAMP_TO_EDGE);
        return tex;
    }
}
