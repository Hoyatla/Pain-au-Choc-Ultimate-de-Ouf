package pauc.pain_au_choc.render.chunk;

import pauc.pain_au_choc.AuthoritativeRuntimeController;
import pauc.pain_au_choc.BottleneckController;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.GlobalPerformanceMode;
import pauc.pain_au_choc.IntegratedServerLoadController;
import pauc.pain_au_choc.LatencyController;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.render.compile.PauCChunkBuildOutput;
import pauc.pain_au_choc.render.gl.PauCGlBuffer;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
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
    private static final int MIN_UPLOAD_SECTION_BUDGET = 1;
    private static final int MAX_UPLOAD_SECTION_BUDGET = 24;
    private static final int MIN_UPLOAD_BYTE_BUDGET = 384 * 1024;
    private static final int MAX_UPLOAD_BYTE_BUDGET = 8 * 1024 * 1024;

    /** Per-pass, per-section GPU data tracking. */
    public static class SectionGpuData {
        /** Offset into the region's VBO (in bytes). */
        public long vertexOffset;
        /** Number of vertices in this section's mesh for this pass. */
        public int vertexCount;
        /** The region VBO handle this data lives in. */
        public int vboHandle;
        /** Size in bytes tracked for memory budgeting. */
        public int byteSize;

        public SectionGpuData(long vertexOffset, int vertexCount, int vboHandle, int byteSize) {
            this.vertexOffset = vertexOffset;
            this.vertexCount = vertexCount;
            this.vboHandle = vboHandle;
            this.byteSize = byteSize;
        }
    }

    /** Per-section GPU data, keyed by section + pass. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Map<PauCTerrainRenderPass, SectionGpuData>>
            sectionData = new java.util.concurrent.ConcurrentHashMap<>();

    /** VBO pool: one VBO per section per pass (safe baseline, no cross-section overwrite). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Map<PauCTerrainRenderPass, PauCGlBuffer>>
            sectionBuffers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracks total GPU memory usage in bytes. */
    private long totalGpuBytes = 0;
    /** Pending uploads waiting for the next render passes. */
    private final ArrayDeque<PauCChunkBuildOutput> pendingUploads = new ArrayDeque<>();
    /** Last upload pass stats for telemetry/debug. */
    private int lastUploadPassSections = 0;
    private long lastUploadPassBytes = 0L;
    private int lastUploadSectionBudget = 0;
    private int lastUploadByteBudget = 0;

    /**
     * Process a batch of completed build results.
     * Uploads vertex data to GPU and updates section tracking.
     *
     * @param results Completed build outputs from PauCChunkBuilder
     */
    public void upload(List<PauCChunkBuildOutput> results) {
        if (results != null && !results.isEmpty()) {
            this.pendingUploads.addAll(results);
        }

        this.lastUploadSectionBudget = resolveUploadSectionBudget();
        this.lastUploadByteBudget = resolveUploadByteBudget();
        this.lastUploadPassSections = 0;
        this.lastUploadPassBytes = 0L;
        if (this.pendingUploads.isEmpty()) {
            return;
        }

        long uploadedBytes = 0L;
        while (!this.pendingUploads.isEmpty() && this.lastUploadPassSections < this.lastUploadSectionBudget) {
            PauCChunkBuildOutput output = this.pendingUploads.peekFirst();
            if (output == null) {
                break;
            }

            int expectedBytes = estimateOutputBytes(output);
            if (this.lastUploadPassSections > 0 && uploadedBytes + expectedBytes > this.lastUploadByteBudget) {
                break;
            }

            this.pendingUploads.pollFirst();
            uploadSection(output);
            this.lastUploadPassSections++;
            uploadedBytes += expectedBytes;
        }
        this.lastUploadPassBytes = uploadedBytes;
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

        // Keep storage isolated per section to avoid overwriting meshes from neighboring sections.
        long bufferKey = sectionKey;
        Map<PauCTerrainRenderPass, PauCGlBuffer> passBuffers =
                this.sectionBuffers.computeIfAbsent(bufferKey, k -> new java.util.concurrent.ConcurrentHashMap<>());

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

            int meshSizeBytes = mesh.getSize();
            SectionGpuData gpuData = new SectionGpuData(0, mesh.getVertexCount(), vbo.getHandle(), meshSizeBytes);
            gpuDataMap.put(pass, gpuData);

            this.totalGpuBytes += meshSizeBytes;
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
                this.totalGpuBytes -= data.byteSize;
            }
        }

        Map<PauCTerrainRenderPass, PauCGlBuffer> passBuffers = this.sectionBuffers.remove(sectionKey);
        if (passBuffers != null) {
            for (PauCGlBuffer vbo : passBuffers.values()) {
                vbo.close();
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

    public int getPendingUploadCount() {
        return this.pendingUploads.size();
    }

    public int getLastUploadPassSections() {
        return this.lastUploadPassSections;
    }

    public long getLastUploadPassBytes() {
        return this.lastUploadPassBytes;
    }

    public int getLastUploadSectionBudget() {
        return this.lastUploadSectionBudget;
    }

    public int getLastUploadByteBudget() {
        return this.lastUploadByteBudget;
    }

    /**
     * Release all GPU resources.
     */
    public void destroy() {
        for (Map<PauCTerrainRenderPass, PauCGlBuffer> passBuffers : this.sectionBuffers.values()) {
            for (PauCGlBuffer vbo : passBuffers.values()) {
                vbo.close();
            }
        }
        this.sectionBuffers.clear();
        this.sectionData.clear();
        this.pendingUploads.clear();
        this.totalGpuBytes = 0;
        this.lastUploadPassSections = 0;
        this.lastUploadPassBytes = 0L;
        this.lastUploadSectionBudget = 0;
        this.lastUploadByteBudget = 0;
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }

    private static int estimateOutputBytes(PauCChunkBuildOutput output) {
        int total = 0;
        for (PauCChunkBuildOutput.MeshData meshData : output.getMeshes().values()) {
            if (meshData != null && meshData.getVertexData() != null) {
                total += Math.max(0, meshData.getSize());
            }
        }
        return total;
    }

    private static int resolveUploadSectionBudget() {
        if (!PauCClient.isBudgetActive()) {
            return MAX_UPLOAD_SECTION_BUDGET;
        }

        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        int baseBudget = switch (cpuLevel) {
            case 1 -> 5;
            case 3 -> 12;
            default -> 8;
        };
        double modeMultiplier = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 1.25D;
            case EXPLORATION -> 1.00D;
            case COMBAT -> 0.92D;
            case BASE -> 0.88D;
            case CRISIS -> 0.72D;
        };
        int pressure = LatencyController.getPressureLevel() + IntegratedServerLoadController.getPressureLevel();
        double pressurePenalty = 1.0D - Math.min(0.55D, pressure * 0.10D);
        if (BottleneckController.isGpuBound()) {
            pressurePenalty *= 0.90D;
        }
        if (AuthoritativeRuntimeController.shouldThrottleChunkStreaming()) {
            pressurePenalty *= 0.80D;
        }

        int resolved = (int) Math.round(baseBudget * modeMultiplier * pressurePenalty);
        return Math.max(MIN_UPLOAD_SECTION_BUDGET, Math.min(MAX_UPLOAD_SECTION_BUDGET, resolved));
    }

    private static int resolveUploadByteBudget() {
        if (!PauCClient.isBudgetActive()) {
            return MAX_UPLOAD_BYTE_BUDGET;
        }

        int quality = PauCClient.getQualityLevel();
        int baseBudget = (768 + quality * 192) * 1024;
        double modeMultiplier = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT, EXPLORATION -> 1.00D;
            case COMBAT -> 0.88D;
            case BASE -> 0.82D;
            case CRISIS -> 0.64D;
        };
        int pressure = LatencyController.getPressureLevel() + IntegratedServerLoadController.getPressureLevel();
        double pressurePenalty = 1.0D - Math.min(0.60D, pressure * 0.09D);
        if (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS) {
            pressurePenalty *= 0.85D;
        }
        if (AuthoritativeRuntimeController.shouldThrottleChunkStreaming()) {
            pressurePenalty *= 0.85D;
        }

        int resolved = (int) Math.round(baseBudget * modeMultiplier * pressurePenalty);
        return Math.max(MIN_UPLOAD_BYTE_BUDGET, Math.min(MAX_UPLOAD_BYTE_BUDGET, resolved));
    }
}
