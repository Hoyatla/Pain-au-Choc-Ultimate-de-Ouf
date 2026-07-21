package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.FloatBuffer;

/**
 * Dual-pass LOD fade renderer for smooth depth-aware transitions between MC vanilla and PauC LOD.
 * Pass 1 (VanillaFade): blends MC near-field into DH LOD based on depth-reconstructed distance.
 * Pass 2 (DHFade): fades LOD to fog at far clip distance.
 *
 * <p>Ported from Distant Horizons fade shaders (LGPL v3).</p>
 */
public final class PauCLodFadeRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PauCLodFadeRenderer INSTANCE = new PauCLodFadeRenderer();
    public static PauCLodFadeRenderer getInstance() { return INSTANCE; }

    private int vanillaFadeShader;
    private int dhFadeShader;
    private int applyShader;
    private int quadVao;
    private int quadVbo;
    private boolean initialized;

    private boolean enabled = true;
    private float vanillaFadeStart = 0.0f;
    private float vanillaFadeEnd = 24.0f;
    private float dhFadeStart = 0.0f;
    private float dhFadeEnd = 48.0f;
    private float maxLevelHeight = 320.0f;
    private boolean onlyRenderLods = false;

    private PauCLodFadeRenderer() {}

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    public void configure(float vanillaStart, float vanillaEnd, float dhStart, float dhEnd, float maxHeight) {
        this.vanillaFadeStart = vanillaStart;
        this.vanillaFadeEnd = vanillaEnd;
        this.dhFadeStart = dhStart;
        this.dhFadeEnd = dhEnd;
        this.maxLevelHeight = maxHeight;
    }

    public void init() {
        if (initialized) return;
        this.vanillaFadeShader = compileShader("pauc/fade/quad_apply.vert", "pauc/fade/vanilla_fade.frag");
        this.dhFadeShader = compileShader("pauc/fade/quad_apply.vert", "pauc/fade/dh_fade.frag");
        this.applyShader = compileShader("pauc/fade/quad_apply.vert", "pauc/fade/apply.frag");

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
     * Render the vanilla fade pass: blends MC depth-aware into LOD.
     */
    public void renderVanillaFade(int combinedColorTex, int dhColorTex, int mcDepthTex, int dhDepthTex,
                                   float[] dhInvMvp, float[] mcInvMvp) {
        if (!enabled || !initialized) return;
        try (PauCGLState ignored = new PauCGLState()) {
            GL32.glEnable(GL32.GL_BLEND);
            GL32.glBlendFunc(GL32.GL_SRC_ALPHA, GL32.GL_ONE_MINUS_SRC_ALPHA);

            GL32.glUseProgram(vanillaFadeShader);
            setUniformMatrix4f(vanillaFadeShader, "uDhInvMvpProj", dhInvMvp);
            setUniformMatrix4f(vanillaFadeShader, "uMcInvMvpProj", mcInvMvp);
            setUniform1i(vanillaFadeShader, "uMcDepthTexture", 0);
            setUniform1i(vanillaFadeShader, "uDhDepthTexture", 1);
            setUniform1i(vanillaFadeShader, "uCombinedMcDhColorTexture", 2);
            setUniform1i(vanillaFadeShader, "uDhColorTexture", 3);
            setUniform1f(vanillaFadeShader, "uStartFadeBlockDistance", vanillaFadeStart);
            setUniform1f(vanillaFadeShader, "uEndFadeBlockDistance", vanillaFadeEnd);
            setUniform1f(vanillaFadeShader, "uMaxLevelHeight", maxLevelHeight);
            setUniform1i(vanillaFadeShader, "uOnlyRenderLods", onlyRenderLods ? 1 : 0);

            GL32.glActiveTexture(GL32.GL_TEXTURE0); GL32.glBindTexture(GL32.GL_TEXTURE_2D, mcDepthTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE1); GL32.glBindTexture(GL32.GL_TEXTURE_2D, dhDepthTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE2); GL32.glBindTexture(GL32.GL_TEXTURE_2D, combinedColorTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE3); GL32.glBindTexture(GL32.GL_TEXTURE_2D, dhColorTex);
            drawQuad();

            GL32.glDisable(GL32.GL_BLEND);
        }
    }

    /**
     * Render the DH fade pass: fades LODs as they approach the far clip plane.
     */
    public void renderDhFade(int mcColorTex, int dhColorTex, int dhDepthTex, float[] dhInvMvp) {
        if (!enabled || !initialized) return;
        try (PauCGLState ignored = new PauCGLState()) {
            GL32.glEnable(GL32.GL_BLEND);
            GL32.glBlendFunc(GL32.GL_SRC_ALPHA, GL32.GL_ONE_MINUS_SRC_ALPHA);

            GL32.glUseProgram(dhFadeShader);
            setUniformMatrix4f(dhFadeShader, "uDhInvMvpProj", dhInvMvp);
            setUniform1i(dhFadeShader, "uDhDepthTexture", 0);
            setUniform1i(dhFadeShader, "uMcColorTexture", 1);
            setUniform1i(dhFadeShader, "uDhColorTexture", 2);
            setUniform1f(dhFadeShader, "uStartFadeBlockDistance", dhFadeStart);
            setUniform1f(dhFadeShader, "uEndFadeBlockDistance", dhFadeEnd);

            GL32.glActiveTexture(GL32.GL_TEXTURE0); GL32.glBindTexture(GL32.GL_TEXTURE_2D, dhDepthTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE1); GL32.glBindTexture(GL32.GL_TEXTURE_2D, mcColorTex);
            GL32.glActiveTexture(GL32.GL_TEXTURE2); GL32.glBindTexture(GL32.GL_TEXTURE_2D, dhColorTex);
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
        GL32.glDeleteProgram(vanillaFadeShader);
        GL32.glDeleteProgram(dhFadeShader);
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
                LOGGER.error("PauC fade shader link error: {}", GL32.glGetProgramInfoLog(program));
            }
            GL32.glDeleteShader(vert);
            GL32.glDeleteShader(frag);
            return program;
        } catch (Exception e) {
            LOGGER.error("Failed to compile fade shader: {}", e.getMessage());
            return 0;
        }
    }

    private static int compileStage(int type, String source) {
        int shader = GL32.glCreateShader(type);
        GL32.glShaderSource(shader, source);
        GL32.glCompileShader(shader);
        if (GL32.glGetShaderi(shader, GL32.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("PauC fade {} compile error: {}", type == GL32.GL_VERTEX_SHADER ? "vert" : "frag", GL32.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static String loadResource(String path) {
        String fullPath = "/assets/paucultimate/shaders/" + path;
        try (var is = PauCLodFadeRenderer.class.getResourceAsStream(fullPath)) {
            if (is == null) throw new RuntimeException("Shader not found: " + fullPath);
            return new String(is.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private static void setUniform1i(int p, String n, int v) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniform1i(l, v); }
    private static void setUniform1f(int p, String n, float v) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniform1f(l, v); }
    private static void setUniformMatrix4f(int p, String n, float[] m) { int l = GL32.glGetUniformLocation(p, n); if (l >= 0) GL32.glUniformMatrix4fv(l, false, m); }
}
