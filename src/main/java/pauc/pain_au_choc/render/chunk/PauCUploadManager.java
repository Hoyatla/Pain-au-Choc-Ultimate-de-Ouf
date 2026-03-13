package pauc.pain_au_choc.render.chunk;

import pauc.pain_au_choc.render.compile.PauCChunkBuildOutput;
import pauc.pain_au_choc.render.gl.PauCGlBuffer;
import pauc.pain_au_choc.render.region.PauCRenderRegion;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Manages uploading compiled chunk mesh data to GPU buffers.
 * Processes PauCChunkBuildOutput results from the worker threads
 * and streams vertex data into region-based VBOs.
 *
 * Called on the render thread after chunk compilation completes.
 */
public class PauCUploadManager {

    /** Per-pass, per-section GPU data tracking. */
    public static class SectionGpuData {
        /** Offset into the region's VBO (in bytes). */
        public long vertexOffset;
        /** Number of vertices in this section's mesh for this pass. */
        public int vertexCount;
        /** The region VBO handle this data lives in. */
        public int vboHandle;

        public SectionGpuData(long vertexOffset, int vertexCount, int vboHandle) {
            this.vertexOffset = vertexOffset;
            this.vertexCount = vertexCount;
            this.vboHandle = vboHandle;
        }
    }

    /** Per-section GPU data, keyed by section + pass. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Map<PauCTerrainRenderPass, SectionGpuData>>
            sectionData = new java.util.concurrent.ConcurrentHashMap<>();

    /** Simple VBO pool: one VBO per region per pass. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Map<PauCTerrainRenderPass, PauCGlBuffer>>
            regionBuffers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracks total GPU memory usage in bytes. */
    private long totalGpuBytes = 0;

    /**
     * Process a batch of completed build results.
     * Uploads vertex data to GPU and updates section tracking.
     *
     * @param results Completed build outputs from PauCChunkBuilder
     */
    public void upload(List<PauCChunkBuildOutput> results) {
        for (PauCChunkBuildOutput output : results) {
            uploadSection(output);
        }
    }

    /**
     * Upload a single section's mesh data to GPU.
     */
    private void uploadSection(PauCChunkBuildOutput output) {
        PauCRenderSection section = output.getSection();
        if (section.isDisposed()) return;

        long sectionKey = packSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());

        // Remove old GPU data for this section
        removeSectionData(sectionKey);

        // Apply build info to the section
        section.setBuiltInfo(
                output.getFlags(),
                output.getGlobalBlockEntities(),
                output.getCulledBlockEntities(),
                output.getAnimatedSprites()
        );

        Map<PauCTerrainRenderPass, PauCChunkBuildOutput.MeshData> meshes = output.getMeshes();
        if (meshes.isEmpty()) return;

        // Get region key for buffer grouping
        long regionKey = PauCRenderRegion.key(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        Map<PauCTerrainRenderPass, PauCGlBuffer> passBuffers =
                this.regionBuffers.computeIfAbsent(regionKey, k -> new java.util.concurrent.ConcurrentHashMap<>());

        Map<PauCTerrainRenderPass, SectionGpuData> gpuDataMap = new java.util.concurrent.ConcurrentHashMap<>();

        for (Map.Entry<PauCTerrainRenderPass, PauCChunkBuildOutput.MeshData> entry : meshes.entrySet()) {
            PauCTerrainRenderPass pass = entry.getKey();
            PauCChunkBuildOutput.MeshData mesh = entry.getValue();
            ByteBuffer vertexData = mesh.getVertexData();

            if (vertexData == null || mesh.getVertexCount() == 0) continue;

            // Get or create VBO for this region + pass
            PauCGlBuffer vbo = passBuffers.computeIfAbsent(pass, p ->
                    new PauCGlBuffer(PauCGlBuffer.Type.VERTEX));

            // Upload vertex data
            // For simplicity, we re-upload the entire buffer each time
            // TODO: Use sub-allocation with arena allocator for better performance
            vbo.upload(vertexData, PauCGlBuffer.Usage.DYNAMIC);

            SectionGpuData gpuData = new SectionGpuData(0, mesh.getVertexCount(), vbo.getHandle());
            gpuDataMap.put(pass, gpuData);

            this.totalGpuBytes += mesh.getSize();
        }

        if (!gpuDataMap.isEmpty()) {
            this.sectionData.put(sectionKey, gpuDataMap);
        }
    }

    /**
     * Get the GPU data for a section and render pass.
     * Returns null if the section has no uploaded geometry for that pass.
     */
    public SectionGpuData getSectionGpuData(PauCRenderSection section, PauCTerrainRenderPass pass) {
        long key = packSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        Map<PauCTerrainRenderPass, SectionGpuData> passMap = this.sectionData.get(key);
        if (passMap == null) return null;
        return passMap.get(pass);
    }

    /**
     * Check if a section has any uploaded GPU data.
     */
    public boolean hasSectionData(PauCRenderSection section) {
        long key = packSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        return this.sectionData.containsKey(key);
    }

    /**
     * Remove GPU data for a section (e.g., when unloaded or rebuilt).
     */
    public void removeSectionData(long sectionKey) {
        Map<PauCTerrainRenderPass, SectionGpuData> old = this.sectionData.remove(sectionKey);
        if (old != null) {
            for (SectionGpuData data : old.values()) {
                this.totalGpuBytes -= data.vertexCount * pauc.pain_au_choc.render.vertex.PauCVertexFormat.STRIDE;
            }
        }
    }

    /**
     * Remove all GPU data for a section by coordinates.
     */
    public void removeSectionData(int chunkX, int chunkY, int chunkZ) {
        removeSectionData(packSectionKey(chunkX, chunkY, chunkZ));
    }

    /** Get total GPU memory used in bytes. */
    public long getTotalGpuBytes() {
        return Math.max(0, this.totalGpuBytes);
    }

    /** Get total GPU memory used in megabytes. */
    public float getTotalGpuMB() {
        return getTotalGpuBytes() / (1024.0f * 1024.0f);
    }

    /**
     * Release all GPU resources.
     */
    public void destroy() {
        for (Map<PauCTerrainRenderPass, PauCGlBuffer> passBuffers : this.regionBuffers.values()) {
            for (PauCGlBuffer vbo : passBuffers.values()) {
                vbo.close();
            }
        }
        this.regionBuffers.clear();
        this.sectionData.clear();
        this.totalGpuBytes = 0;
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }
}
