package pauc.pain_au_choc.render.compile;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.entity.BlockEntity;
import pauc.pain_au_choc.render.chunk.PauCRenderSection;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Result of a chunk mesh compilation task.
 * Contains the vertex data per render pass, plus metadata about block entities and sprites.
 *
 * Created on a worker thread, consumed on the render thread for GPU upload.
 */
public class PauCChunkBuildOutput {

    private final PauCRenderSection section;
    private final int buildFrame;

    /** Compiled vertex data per render pass. Null entry = no geometry for that pass. */
    private final Map<PauCTerrainRenderPass, MeshData> meshes;

    /** Render flags to apply to the section. */
    private final int flags;

    /** Block entities that should always be rendered (beacons, end portals, etc.). */
    private final BlockEntity[] globalBlockEntities;

    /** Block entities with culling volumes (chests, signs, etc.). */
    private final BlockEntity[] culledBlockEntities;

    /** Animated texture sprites found in this section. */
    private final TextureAtlasSprite[] animatedSprites;

    public PauCChunkBuildOutput(PauCRenderSection section, int buildFrame,
                                 Map<PauCTerrainRenderPass, MeshData> meshes,
                                 int flags,
                                 BlockEntity[] globalBlockEntities,
                                 BlockEntity[] culledBlockEntities,
                                 TextureAtlasSprite[] animatedSprites) {
        this.section = section;
        this.buildFrame = buildFrame;
        this.meshes = meshes;
        this.flags = flags;
        this.globalBlockEntities = globalBlockEntities;
        this.culledBlockEntities = culledBlockEntities;
        this.animatedSprites = animatedSprites;
    }

    public PauCRenderSection getSection() { return section; }
    public int getBuildFrame() { return buildFrame; }
    public Map<PauCTerrainRenderPass, MeshData> getMeshes() { return meshes; }
    public int getFlags() { return flags; }
    public BlockEntity[] getGlobalBlockEntities() { return globalBlockEntities; }
    public BlockEntity[] getCulledBlockEntities() { return culledBlockEntities; }
    public TextureAtlasSprite[] getAnimatedSprites() { return animatedSprites; }

    /**
     * Per-pass mesh data: vertex buffer + vertex count.
     */
    public static class MeshData {
        private final ByteBuffer vertexData;
        private final int vertexCount;

        public MeshData(ByteBuffer vertexData, int vertexCount) {
            this.vertexData = vertexData;
            this.vertexCount = vertexCount;
        }

        public ByteBuffer getVertexData() { return vertexData; }
        public int getVertexCount() { return vertexCount; }

        /** Size in bytes. */
        public int getSize() { return vertexData.remaining(); }
    }
}
