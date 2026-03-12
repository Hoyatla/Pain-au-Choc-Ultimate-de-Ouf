package pauc.pain_au_choc.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full deferred world rendering pipeline with OptiFine-compatible shader support.
 * Orchestrates the complete render flow:
 *
 * 1. Shadow Pass       - Render scene from light's perspective into shadow maps
 * 2. GBuffer Pass      - Render scene geometry into GBuffer attachments
 * 3. Deferred Passes   - Process GBuffers (lighting, SSAO, SSR, etc.)
 * 4. Composite Passes  - Combine deferred results (bloom, volumetrics, etc.)
 * 5. Final Pass        - Tone mapping, color grading, output to screen
 *
 * Adapted from Oculus/Iris DeferredWorldRenderingPipeline.
 */
public class DeferredWorldRenderingPipeline implements AutoCloseable {

    /** Currently active pipeline (null = vanilla rendering). */
    private static DeferredWorldRenderingPipeline activePipeline;

    /** The loaded shaderpack. */
    private ShaderPackLoader.ShaderPack shaderPack;

    /** Compiled shader programs, keyed by program name. */
    private final Map<String, PauCShaderProgram> compiledPrograms = new LinkedHashMap<>();

    /** GBuffer render targets. */
    private GBufferTargets gbuffers;

    /** Shadow map render targets. */
    private ShadowRenderTargets shadowTargets;

    /** Standard uniform manager. */
    private final ShaderUniforms uniforms = new ShaderUniforms();

    /** Current rendering phase. */
    private WorldRenderingPhase currentPhase = WorldRenderingPhase.NONE;

    /** Currently bound program. */
    private PauCShaderProgram activeProgram;

    /** Whether the pipeline is fully initialized. */
    private boolean initialized = false;

    /** Shadow map resolution. */
    private int shadowMapResolution = 1024;

    /**
     * Create and initialize the deferred pipeline from a shaderpack.
     *
     * @param packPath Path to the shaderpack
     */
    public DeferredWorldRenderingPipeline(Path packPath) {
        this.shaderPack = ShaderPackLoader.load(packPath);
        if (this.shaderPack == null) {
            System.err.println("[PAUC Pipeline] Failed to load shaderpack from: " + packPath);
            return;
        }

        // Parse shadow resolution from properties
        String shadowRes = this.shaderPack.properties.get("shadow.resolution");
        if (shadowRes != null) {
            try {
                this.shadowMapResolution = Integer.parseInt(shadowRes.trim());
            } catch (NumberFormatException ignored) {}
        }

        initialize();
    }

    private void initialize() {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getWidth();
        int screenHeight = mc.getWindow().getHeight();

        // Determine how many color targets the shaderpack needs
        int colorTargets = 4; // Default: albedo, normal, specular, composite
        String customTargets = this.shaderPack.properties.get("colortex.count");
        if (customTargets != null) {
            try {
                colorTargets = Math.min(8, Integer.parseInt(customTargets.trim()));
            } catch (NumberFormatException ignored) {}
        }

        // Create GBuffer targets
        this.gbuffers = new GBufferTargets();
        this.gbuffers.create(screenWidth, screenHeight, colorTargets);

        // Create shadow targets if the shaderpack uses shadows
        if (this.shaderPack.hasShadow) {
            this.shadowTargets = new ShadowRenderTargets();
            this.shadowTargets.create(this.shadowMapResolution);
        }

        // Compile all shader programs
        for (Map.Entry<String, ShaderPackLoader.ProgramSource> entry : this.shaderPack.programs.entrySet()) {
            ShaderPackLoader.ProgramSource source = entry.getValue();
            PauCShaderProgram program = new PauCShaderProgram(
                    source.name, source.vertexSource, source.fragmentSource, source.geometrySource);

            if (program.isValid()) {
                this.compiledPrograms.put(entry.getKey(), program);
            } else {
                program.close();
            }
        }

        this.initialized = true;
        System.out.println("[PAUC Pipeline] Initialized deferred pipeline: "
                + this.compiledPrograms.size() + " programs compiled, "
                + colorTargets + " GBuffer targets, "
                + "shadow=" + (this.shadowTargets != null));
    }

    // ============================
    // Pipeline activation
    // ============================

    public static DeferredWorldRenderingPipeline getActivePipeline() {
        return activePipeline;
    }

    public static boolean isShaderActive() {
        return activePipeline != null && activePipeline.initialized;
    }

    /**
     * Activate this pipeline as the current renderer.
     */
    public void activate() {
        activePipeline = this;
    }

    /**
     * Deactivate the shader pipeline (revert to vanilla).
     */
    public static void deactivate() {
        if (activePipeline != null) {
            activePipeline.close();
            activePipeline = null;
        }
    }

    // ============================
    // Per-frame rendering flow
    // ============================

    /**
     * Called at the start of world rendering to update uniforms.
     */
    public void beginWorldRendering(Camera camera, Matrix4f modelView, Matrix4f projection, float partialTick) {
        if (!this.initialized) return;

        this.uniforms.update(camera, modelView, projection, partialTick);

        // Check for screen resize
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (w != this.gbuffers.getWidth() || h != this.gbuffers.getHeight()) {
            this.gbuffers.resize(w, h);
        }
    }

    /**
     * Execute the shadow pass.
     * Renders the scene from the sun/moon perspective into shadow maps.
     */
    public void renderShadows(Runnable sceneRenderer) {
        if (!this.initialized || this.shadowTargets == null) return;

        this.currentPhase = WorldRenderingPhase.SHADOW;

        // Set up shadow matrices
        Matrix4f shadowView = computeShadowModelView();
        Matrix4f shadowProj = computeShadowProjection();
        this.uniforms.setShadowModelView(shadowView);
        this.uniforms.setShadowProjection(shadowProj);

        // Bind shadow FBO
        this.shadowTargets.bind();
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Use shadow program
        PauCShaderProgram shadowProg = this.compiledPrograms.get("shadow");
        if (shadowProg != null) {
            shadowProg.bind();
            this.uniforms.apply(shadowProg);
        }

        // Render scene into shadow map
        sceneRenderer.run();

        PauCShaderProgram.unbind();
        ShadowRenderTargets.unbind();
        this.currentPhase = WorldRenderingPhase.NONE;
    }

    /**
     * Begin the GBuffer geometry pass.
     * All subsequent geometry rendering will write to GBuffers.
     */
    public void beginGBufferPass() {
        if (!this.initialized) return;

        this.gbuffers.clear();
        this.gbuffers.bind();
    }

    /**
     * Set the current rendering phase and bind the appropriate program.
     */
    public void setPhase(WorldRenderingPhase phase) {
        if (!this.initialized) return;

        this.currentPhase = phase;

        String programName = phase.getProgramName();
        if (programName == null) {
            if (this.activeProgram != null) {
                PauCShaderProgram.unbind();
                this.activeProgram = null;
            }
            return;
        }

        // Look up the program (with fallback chain)
        PauCShaderProgram program = getCompiledProgram(programName);
        if (program != null && program != this.activeProgram) {
            program.bind();
            this.uniforms.apply(program);
            this.uniforms.bindGBufferSamplers(program, this.gbuffers);

            // Bind shadow textures if available
            if (this.shadowTargets != null) {
                this.shadowTargets.bindTextures(12);
            }

            this.activeProgram = program;
        }
    }

    /**
     * End the GBuffer geometry pass.
     */
    public void endGBufferPass() {
        if (!this.initialized) return;

        PauCShaderProgram.unbind();
        GBufferTargets.unbind();
        this.activeProgram = null;
        this.currentPhase = WorldRenderingPhase.NONE;
    }

    /**
     * Execute deferred passes (deferred, deferred1, ..., deferred15).
     */
    public void runDeferredPasses() {
        if (!this.initialized) return;

        this.currentPhase = WorldRenderingPhase.DEFERRED;

        for (int i = 0; i < this.shaderPack.deferredPassCount; i++) {
            String name = i == 0 ? "deferred" : "deferred" + i;
            PauCShaderProgram program = this.compiledPrograms.get(name);
            if (program == null) continue;

            // Bind GBuffer textures for reading
            bindGBufferTextures();

            // Render full-screen quad with this program
            renderFullScreenPass(program);
        }

        this.currentPhase = WorldRenderingPhase.NONE;
    }

    /**
     * Execute composite passes (composite, composite1, ..., composite15).
     */
    public void runCompositePasses() {
        if (!this.initialized) return;

        this.currentPhase = WorldRenderingPhase.COMPOSITE;

        for (int i = 0; i < this.shaderPack.compositePassCount; i++) {
            String name = i == 0 ? "composite" : "composite" + i;
            PauCShaderProgram program = this.compiledPrograms.get(name);
            if (program == null) continue;

            // Bind GBuffer textures for reading
            bindGBufferTextures();

            // Render full-screen quad
            renderFullScreenPass(program);
        }

        this.currentPhase = WorldRenderingPhase.NONE;
    }

    /**
     * Execute the final pass (tone mapping, output to screen).
     */
    public void runFinalPass() {
        if (!this.initialized) return;

        this.currentPhase = WorldRenderingPhase.FINAL;

        PauCShaderProgram finalProg = this.compiledPrograms.get("final");
        if (finalProg != null) {
            // Bind to default framebuffer (screen)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            Minecraft mc = Minecraft.getInstance();
            GL11.glViewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());

            bindGBufferTextures();
            renderFullScreenPass(finalProg);
        }

        this.currentPhase = WorldRenderingPhase.NONE;
    }

    /**
     * Called at the end of world rendering.
     */
    public void endWorldRendering() {
        PauCShaderProgram.unbind();
        this.activeProgram = null;
        this.currentPhase = WorldRenderingPhase.NONE;
    }

    // ============================
    // Helpers
    // ============================

    private PauCShaderProgram getCompiledProgram(String name) {
        PauCShaderProgram prog = this.compiledPrograms.get(name);
        if (prog != null) return prog;

        // Use shaderpack's fallback resolution
        ShaderPackLoader.ProgramSource source = this.shaderPack.getProgram(name);
        if (source != null) {
            return this.compiledPrograms.get(source.name);
        }
        return null;
    }

    private void bindGBufferTextures() {
        // Color textures to units 0-7
        for (int i = 0; i < this.gbuffers.getAllocatedColorTargets(); i++) {
            this.gbuffers.bindColorTexture(i, GL13.GL_TEXTURE0 + i);
        }
        // Depth textures to units 8-10
        for (int i = 0; i < GBufferTargets.MAX_DEPTH_TARGETS; i++) {
            this.gbuffers.bindDepthTexture(i, GL13.GL_TEXTURE0 + 8 + i);
        }
        // Shadow textures to units 12-14
        if (this.shadowTargets != null) {
            this.shadowTargets.bindTextures(12);
        }
    }

    /**
     * Render a full-screen quad with the given shader program.
     * Used for deferred, composite, and final passes.
     */
    private void renderFullScreenPass(PauCShaderProgram program) {
        program.bind();
        this.uniforms.apply(program);
        this.uniforms.bindGBufferSamplers(program, this.gbuffers);

        // Draw full-screen triangle (more efficient than quad)
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(-1, -1, 0).uv(0, 0).endVertex();
        builder.vertex( 1, -1, 0).uv(1, 0).endVertex();
        builder.vertex( 1,  1, 0).uv(1, 1).endVertex();
        builder.vertex(-1,  1, 0).uv(0, 1).endVertex();
        BufferUploader.drawWithShader(builder.end());

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        PauCShaderProgram.unbind();
    }

    private Matrix4f computeShadowModelView() {
        float[] lightPos = this.uniforms.getShadowLightPosition();
        return new Matrix4f().lookAt(
                lightPos[0], lightPos[1], lightPos[2],
                0, 0, 0,
                0, 1, 0
        );
    }

    private Matrix4f computeShadowProjection() {
        float halfSize = this.shadowMapResolution * 0.0078125f; // /128
        return new Matrix4f().ortho(-halfSize, halfSize, -halfSize, halfSize, 0.05f, 256f);
    }

    // ============================
    // Getters
    // ============================

    public WorldRenderingPhase getCurrentPhase() { return currentPhase; }
    public ShaderPackLoader.ShaderPack getShaderPack() { return shaderPack; }
    public GBufferTargets getGBuffers() { return gbuffers; }
    public ShadowRenderTargets getShadowTargets() { return shadowTargets; }
    public ShaderUniforms getUniforms() { return uniforms; }
    public boolean isInitialized() { return initialized; }

    public String getDebugString() {
        if (!initialized) return "PAUC Shader: inactive";
        return "PAUC Shader: " + (shaderPack != null ? shaderPack.name : "?")
                + " phase=" + currentPhase
                + " programs=" + compiledPrograms.size()
                + " shadow=" + (shadowTargets != null);
    }

    // ============================
    // Cleanup
    // ============================

    @Override
    public void close() {
        for (PauCShaderProgram program : this.compiledPrograms.values()) {
            program.close();
        }
        this.compiledPrograms.clear();

        if (this.gbuffers != null) {
            this.gbuffers.close();
            this.gbuffers = null;
        }

        if (this.shadowTargets != null) {
            this.shadowTargets.close();
            this.shadowTargets = null;
        }

        this.initialized = false;
        this.activeProgram = null;
        this.currentPhase = WorldRenderingPhase.NONE;

        if (activePipeline == this) {
            activePipeline = null;
        }
    }
}
