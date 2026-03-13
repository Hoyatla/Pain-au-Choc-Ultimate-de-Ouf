package pauc.pain_au_choc.render.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import pauc.pain_au_choc.render.gl.PauCGlVertexArray;
import pauc.pain_au_choc.render.gl.PauCSharedQuadIndexBuffer;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;
import pauc.pain_au_choc.render.vertex.PauCVertexFormat;

import java.util.List;

/**
 * GPU chunk renderer that draws visible sections using OpenGL.
 * Renders terrain geometry per render pass using batched draw calls.
 *
 * For each visible section with uploaded GPU data:
 * 1. Binds the section's VBO
 * 2. Sets up vertex attributes (compact 20-byte format)
 * 3. Issues indexed draw calls via the shared quad index buffer
 *
 * Future optimization: Use glMultiDrawElements to batch multiple sections
 * into a single draw call per region.
 *
 * Adapted from Embeddium's DefaultChunkRenderer.
 */
public class PauCChunkRenderer {

    /** VAO used for terrain rendering. */
    private PauCGlVertexArray terrainVao;

    /** Shared quad index buffer. */
    private PauCSharedQuadIndexBuffer indexBuffer;

    /** Upload manager for GPU data access. */
    private final PauCUploadManager uploadManager;

    /** Statistics. */
    private int lastDrawCalls = 0;
    private int lastVerticesDrawn = 0;
    private int lastSectionsDrawn = 0;

    public PauCChunkRenderer(PauCUploadManager uploadManager) {
        this.uploadManager = uploadManager;
    }

    /**
     * Initialize GL resources. Must be called on the render thread.
     */
    public void init() {
        this.terrainVao = new PauCGlVertexArray();
        this.indexBuffer = PauCSharedQuadIndexBuffer.getInstance();
    }

    /**
     * Render a terrain pass for all visible sections.
     *
     * @param visibleSections List of sections determined visible by the occlusion culler
     * @param pass            The terrain render pass (SOLID, CUTOUT, TRANSLUCENT)
     * @param modelViewMatrix Current model-view matrix
     * @param cameraX         Camera world X position
     * @param cameraY         Camera world Y position
     * @param cameraZ         Camera world Z position
     */
    public void renderPass(List<PauCRenderSection> visibleSections, PauCTerrainRenderPass pass,
                            Matrix4f modelViewMatrix, double cameraX, double cameraY, double cameraZ) {

        if (this.terrainVao == null) {
            init();
        }

        this.lastDrawCalls = 0;
        this.lastVerticesDrawn = 0;
        this.lastSectionsDrawn = 0;

        // Set up render state for this pass
        pass.startDrawing();

        // Bind VAO
        this.terrainVao.bind();

        // Bind shared index buffer
        this.indexBuffer.bind();

        // Iterate visible sections and draw
        boolean reverseOrder = pass.isReverseOrder();
        int start = reverseOrder ? visibleSections.size() - 1 : 0;
        int end = reverseOrder ? -1 : visibleSections.size();
        int step = reverseOrder ? -1 : 1;

        for (int i = start; i != end; i += step) {
            PauCRenderSection section = visibleSections.get(i);

            // Skip sections without geometry for this pass
            if (!section.isBuilt()) continue;
            if (section.hasFlag(RenderSectionFlags.BUDGET_CULLED)) continue;

            PauCUploadManager.SectionGpuData gpuData = this.uploadManager.getSectionGpuData(section, pass);
            if (gpuData == null || gpuData.vertexCount == 0) continue;

            // Bind section's VBO
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpuData.vboHandle);

            // Set up vertex attributes for compact format
            PauCVertexFormat.setupVertexAttributes();

            // Calculate section-relative offset for the shader
            float offsetX = (float) (section.getOriginX() - cameraX);
            float offsetY = (float) (section.getOriginY() - cameraY);
            float offsetZ = (float) (section.getOriginZ() - cameraZ);

            // Push section offset to the model-view matrix
            // The shader needs to know the section's position relative to camera
            RenderSystem.getModelViewStack().pushPose();
            RenderSystem.getModelViewStack().translate(offsetX, offsetY, offsetZ);
            RenderSystem.applyModelViewMatrix();

            // Issue draw call
            int indexCount = PauCSharedQuadIndexBuffer.getIndexCountForVertices(gpuData.vertexCount);
            GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT,
                    gpuData.vertexOffset);

            // Pop matrix
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();

            this.lastDrawCalls++;
            this.lastVerticesDrawn += gpuData.vertexCount;
            this.lastSectionsDrawn++;
        }

        // Cleanup
        PauCVertexFormat.disableVertexAttributes();
        PauCSharedQuadIndexBuffer.unbind();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        PauCGlVertexArray.unbind();

        // Restore render state
        pass.endDrawing();
    }

    /**
     * Render all terrain passes for the given visible sections.
     */
    public void renderAllPasses(List<PauCRenderSection> visibleSections,
                                 PauCTerrainRenderPass[] passes,
                                 Matrix4f modelViewMatrix,
                                 double cameraX, double cameraY, double cameraZ) {
        for (PauCTerrainRenderPass pass : passes) {
            renderPass(visibleSections, pass, modelViewMatrix, cameraX, cameraY, cameraZ);
        }
    }

    // ---- Statistics ----

    public int getLastDrawCalls() { return lastDrawCalls; }
    public int getLastVerticesDrawn() { return lastVerticesDrawn; }
    public int getLastSectionsDrawn() { return lastSectionsDrawn; }

    public String getDebugString() {
        return String.format("PauC Render: %d draws, %dk verts, %d sections, %.1f MB GPU",
                lastDrawCalls,
                lastVerticesDrawn / 1000,
                lastSectionsDrawn,
                uploadManager.getTotalGpuMB());
    }

    /**
     * Release all GPU resources.
     */
    public void destroy() {
        if (this.terrainVao != null) {
            this.terrainVao.close();
            this.terrainVao = null;
        }
        // Index buffer is managed by singleton, don't close here
    }
}
