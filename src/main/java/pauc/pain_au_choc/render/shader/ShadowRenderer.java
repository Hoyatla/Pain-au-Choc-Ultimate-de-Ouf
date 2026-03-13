package pauc.pain_au_choc.render.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import pauc.pain_au_choc.render.PauCWorldRenderer;
import pauc.pain_au_choc.render.chunk.PauCRenderSection;
import pauc.pain_au_choc.render.chunk.PauCRenderSectionManager;
import pauc.pain_au_choc.render.terrain.DefaultTerrainRenderPasses;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import pauc.pain_au_choc.GlobalPerformanceGovernor;

import java.util.List;

/**
 * Renders the scene from the sun/moon perspective into shadow maps.
 *
 * Determines which chunks are visible from the light source and renders
 * them using the shadow shader program. Supports configurable shadow
 * distance and resolution.
 *
 * Integrates with PAUC's performance governor to dynamically adjust
 * shadow quality based on system load.
 */
public class ShadowRenderer {

    /** Default shadow render distance in chunks. */
    private static final int DEFAULT_SHADOW_DISTANCE_CHUNKS = 8;

    /** Maximum entities to render in shadow pass per frame. */
    private static final int MAX_SHADOW_ENTITIES = 128;

    private final DeferredWorldRenderingPipeline pipeline;
    private int shadowDistanceChunks = DEFAULT_SHADOW_DISTANCE_CHUNKS;

    /** Shadow matrices computed for the current frame. */
    private Matrix4f shadowModelView;
    private Matrix4f shadowProjection;

    /** Performance tracking. */
    private int lastShadowChunksRendered;
    private int lastShadowEntitiesRendered;
    private float lastShadowPassTimeMs;

    public ShadowRenderer(DeferredWorldRenderingPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Execute the full shadow rendering pass.
     *
     * @param camera    The player camera (for determining nearby chunks)
     * @param partialTick Partial tick for entity interpolation
     */
    public void renderShadowPass(Camera camera, float partialTick) {
        ShadowRenderTargets targets = this.pipeline.getShadowTargets();
        if (targets == null || !targets.isInitialized()) return;

        long startTime = System.nanoTime();
        this.lastShadowChunksRendered = 0;
        this.lastShadowEntitiesRendered = 0;

        // Compute shadow matrices from light direction
        computeShadowMatrices(camera);

        // Update uniform matrices
        this.pipeline.getUniforms().setShadowModelView(this.shadowModelView);
        this.pipeline.getUniforms().setShadowProjection(this.shadowProjection);

        // Bind shadow FBO
        targets.bind();
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Enable front-face culling to reduce shadow acne
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_FRONT);

        // Bind shadow shader program
        PauCShaderProgram shadowProgram = getShadowProgram();
        if (shadowProgram != null) {
            shadowProgram.bind();
            this.pipeline.getUniforms().apply(shadowProgram);

            // Set shadow-specific uniforms
            shadowProgram.setUniformMatrix4f("shadowModelView", false,
                    matrixToFloats(this.shadowModelView));
            shadowProgram.setUniformMatrix4f("shadowProjection", false,
                    matrixToFloats(this.shadowProjection));
        }

        // Render terrain into shadow map
        renderShadowTerrain(camera);

        // Render entities into shadow map
        renderShadowEntities(camera, partialTick);

        // Restore state
        if (shadowProgram != null) {
            PauCShaderProgram.unbind();
        }
        GL11.glCullFace(GL11.GL_BACK);
        ShadowRenderTargets.unbind();

        // Restore viewport to screen size
        Minecraft mc = Minecraft.getInstance();
        GL11.glViewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());

        this.lastShadowPassTimeMs = (System.nanoTime() - startTime) / 1_000_000f;
    }

    /**
     * Render terrain chunks visible from the light source.
     */
    private void renderShadowTerrain(Camera camera) {
        PauCWorldRenderer worldRenderer = PauCWorldRenderer.instanceNullable();
        if (worldRenderer == null) return;

        PauCRenderSectionManager sectionManager = worldRenderer.getSectionManager();
        if (sectionManager == null) return;

        List<PauCRenderSection> visibleSections = sectionManager.getVisibleSections();
        Vec3 camPos = camera.getPosition();

        // Filter sections within shadow distance
        float shadowDistBlocks = getGovernorAdjustedShadowDistance() * 16.0f;
        float shadowDistSqr = shadowDistBlocks * shadowDistBlocks;

        // Use the existing chunk renderer but with shadow matrices
        // For each visible section within shadow distance, draw it
        for (PauCRenderSection section : visibleSections) {
            float dx = (float) (section.getOriginX() + 8 - camPos.x);
            float dy = (float) (section.getOriginY() + 8 - camPos.y);
            float dz = (float) (section.getOriginZ() + 8 - camPos.z);
            float distSqr = dx * dx + dy * dy + dz * dz;

            if (distSqr > shadowDistSqr) continue;
            if (!section.isBuilt()) continue;

            this.lastShadowChunksRendered++;
        }

        // Render solid and cutout passes (skip translucent for shadow performance)
        if (sectionManager.getChunkRenderer() != null) {
            sectionManager.renderLayer(DefaultTerrainRenderPasses.SOLID,
                    this.shadowModelView,
                    camPos.x, camPos.y, camPos.z);
            sectionManager.renderLayer(DefaultTerrainRenderPasses.CUTOUT,
                    this.shadowModelView,
                    camPos.x, camPos.y, camPos.z);
        }
    }

    /**
     * Render entities into the shadow map.
     */
    private void renderShadowEntities(Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Vec3 camPos = camera.getPosition();
        float shadowDistBlocks = getGovernorAdjustedShadowDistance() * 16.0f;
        float shadowDistSqr = shadowDistBlocks * shadowDistBlocks;
        int entityCount = 0;

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || entity.isRemoved()) continue;
            if (entityCount >= MAX_SHADOW_ENTITIES) break;

            double distSqr = entity.distanceToSqr(camPos);
            if (distSqr > shadowDistSqr) continue;

            // Entity shadow rendering will be handled by vanilla's entity renderer
            // when we redirect through the pipeline in Phase 3.2
            entityCount++;
        }

        this.lastShadowEntitiesRendered = entityCount;
    }

    /**
     * Compute shadow view and projection matrices from the sun/moon direction.
     */
    private void computeShadowMatrices(Camera camera) {
        float[] lightPos = this.pipeline.getUniforms().getShadowLightPosition();
        Vec3 camPos = camera.getPosition();

        // Light direction (normalized)
        float len = (float) Math.sqrt(lightPos[0] * lightPos[0] +
                lightPos[1] * lightPos[1] + lightPos[2] * lightPos[2]);
        if (len < 0.001f) len = 1f;
        float lx = lightPos[0] / len;
        float ly = lightPos[1] / len;
        float lz = lightPos[2] / len;

        // Shadow view matrix: look from light position toward camera
        this.shadowModelView = new Matrix4f().lookAt(
                (float) camPos.x + lx * 128f,
                (float) camPos.y + ly * 128f,
                (float) camPos.z + lz * 128f,
                (float) camPos.x,
                (float) camPos.y,
                (float) camPos.z,
                0f, 1f, 0f
        );

        // Orthographic projection for directional light
        float halfSize = getGovernorAdjustedShadowDistance() * 16.0f;
        this.shadowProjection = new Matrix4f().ortho(
                -halfSize, halfSize,
                -halfSize, halfSize,
                0.05f, 512f
        );
    }

    private PauCShaderProgram getShadowProgram() {
        if (this.pipeline.getShaderPack() == null) return null;
        // Look up compiled shadow program through the pipeline's fallback chain
        // OptiFine spec: shadow → gbuffers_basic fallback
        PauCShaderProgram prog = this.pipeline.getCompiledProgram("shadow");
        if (prog == null) {
            prog = this.pipeline.getCompiledProgram("gbuffers_basic");
        }
        return prog;
    }

    private static float[] matrixToFloats(Matrix4f matrix) {
        float[] result = new float[16];
        matrix.get(result);
        return result;
    }

    // ---- Governor integration ----

    /**
     * Get effective shadow distance considering performance governor mode.
     * CRISIS mode halves shadow distance, TRANSIT/EXPLORATION are normal,
     * COMBAT reduces slightly, BASE increases slightly.
     */
    private int getGovernorAdjustedShadowDistance() {
        double multiplier = GlobalPerformanceGovernor.getShadowDistanceMultiplier();
        return Math.max(2, Math.min(32, (int) Math.round(this.shadowDistanceChunks * multiplier)));
    }

    // ---- Configuration ----

    public void setShadowDistanceChunks(int distance) {
        this.shadowDistanceChunks = Math.max(2, Math.min(32, distance));
    }

    public int getShadowDistanceChunks() { return shadowDistanceChunks; }

    // ---- Debug ----

    public String getDebugString() {
        int effectiveDist = getGovernorAdjustedShadowDistance();
        return String.format("Shadow: %d/%d chunks, %d entities, %.1fms",
                lastShadowChunksRendered, effectiveDist, lastShadowEntitiesRendered, lastShadowPassTimeMs);
    }
}
