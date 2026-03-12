package pauc.pain_au_choc.render.chunk;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import pauc.pain_au_choc.render.occlusion.GraphDirection;
import pauc.pain_au_choc.render.region.PauCRenderRegion;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.util.Map;

/**
 * Represents the render state for a single 16x16x16 chunk section.
 * This is the core unit of PAUC's optimized chunk rendering pipeline.
 *
 * Each section tracks:
 * - Position and adjacency graph (for occlusion traversal)
 * - Visibility data (encoded 6x6 direction matrix)
 * - Build state (pending updates, build results)
 * - Render data (geometry, block entities, animated sprites)
 * - PAUC integration flags (budget culling, detail ring membership)
 *
 * Adapted from Embeddium's RenderSection with PAUC-specific extensions.
 */
public class PauCRenderSection {

    // ---- Position ----
    private final PauCRenderRegion region;
    private final int sectionIndex; // local index within region
    private final int chunkX, chunkY, chunkZ;

    // ---- Adjacency graph ----
    private final PauCRenderSection[] adjacent = new PauCRenderSection[GraphDirection.COUNT];
    private int adjacentMask = 0;

    // ---- Occlusion / visibility ----
    private long visibilityData = 0L;
    private int incomingDirections = 0;
    private int lastVisibleFrame = -1;

    // ---- Build state ----
    private boolean built = false;
    private ChunkUpdateType pendingUpdateType = null;
    private int lastBuiltFrame = -1;
    private int lastSubmittedFrame = -1;
    private boolean disposed = false;

    // ---- Render flags (combined bitfield) ----
    private int flags = 0;

    // ---- Render content ----
    private BlockEntity[] globalBlockEntities = new BlockEntity[0];
    private BlockEntity[] culledBlockEntities = new BlockEntity[0];
    private TextureAtlasSprite[] animatedSprites = new TextureAtlasSprite[0];

    // ---- Translucency sorting ----
    private Map<PauCTerrainRenderPass, Object> translucencySortStates;
    private boolean needsDynamicTranslucencySorting = false;

    // ---- Camera tracking for translucency re-sort ----
    private double lastCameraX, lastCameraY, lastCameraZ;

    // ---- PAUC integration ----
    /** Quality-adjusted render priority (set by GlobalPerformanceGovernor). */
    private float renderPriority = 1.0f;

    public PauCRenderSection(PauCRenderRegion region, int chunkX, int chunkY, int chunkZ) {
        this.region = region;
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;
        this.sectionIndex = PauCRenderRegion.localIndex(chunkX, chunkY, chunkZ);
    }

    // ---- Position accessors ----

    public int getChunkX() { return chunkX; }
    public int getChunkY() { return chunkY; }
    public int getChunkZ() { return chunkZ; }

    public int getOriginX() { return chunkX << 4; }
    public int getOriginY() { return chunkY << 4; }
    public int getOriginZ() { return chunkZ << 4; }

    public int getCenterX() { return getOriginX() + 8; }
    public int getCenterY() { return getOriginY() + 8; }
    public int getCenterZ() { return getOriginZ() + 8; }

    public SectionPos getPosition() {
        return SectionPos.of(chunkX, chunkY, chunkZ);
    }

    public PauCRenderRegion getRegion() { return region; }
    public int getSectionIndex() { return sectionIndex; }

    // ---- Distance ----

    public float getSquaredDistance(float x, float y, float z) {
        float dx = getCenterX() - x;
        float dy = getCenterY() - y;
        float dz = getCenterZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public float getSquaredDistanceXZ(float x, float z) {
        float dx = getCenterX() - x;
        float dz = getCenterZ() - z;
        return dx * dx + dz * dz;
    }

    // ---- Adjacency graph ----

    public PauCRenderSection getAdjacent(int direction) {
        return this.adjacent[direction];
    }

    public void setAdjacentNode(int direction, PauCRenderSection node) {
        this.adjacent[direction] = node;
        if (node != null) {
            this.adjacentMask |= GraphDirection.flag(direction);
        } else {
            this.adjacentMask &= ~GraphDirection.flag(direction);
        }
    }

    public int getAdjacentMask() {
        return this.adjacentMask;
    }

    // ---- Occlusion / visibility ----

    public long getVisibilityData() { return visibilityData; }
    public void setVisibilityData(long data) { this.visibilityData = data; }

    public int getIncomingDirections() { return incomingDirections; }
    public void setIncomingDirections(int dirs) { this.incomingDirections = dirs; }
    public void addIncomingDirections(int dirs) { this.incomingDirections |= dirs; }

    public int getLastVisibleFrame() { return lastVisibleFrame; }
    public void setLastVisibleFrame(int frame) { this.lastVisibleFrame = frame; }

    // ---- Build state ----

    public boolean isBuilt() { return built; }
    public void setBuilt(boolean built) { this.built = built; }

    public ChunkUpdateType getPendingUpdate() { return pendingUpdateType; }
    public void setPendingUpdate(ChunkUpdateType type) {
        this.pendingUpdateType = ChunkUpdateType.merge(this.pendingUpdateType, type);
    }
    public void clearPendingUpdate() { this.pendingUpdateType = null; }

    public int getLastBuiltFrame() { return lastBuiltFrame; }
    public void setLastBuiltFrame(int frame) { this.lastBuiltFrame = frame; }

    public int getLastSubmittedFrame() { return lastSubmittedFrame; }
    public void setLastSubmittedFrame(int frame) { this.lastSubmittedFrame = frame; }

    public boolean isDisposed() { return disposed; }

    // ---- Flags ----

    public int getFlags() { return flags; }
    public void setFlags(int flags) { this.flags = flags; }

    public boolean hasFlag(int flag) {
        return RenderSectionFlags.hasFlag(this.flags, flag);
    }

    public void setFlag(int flag, boolean value) {
        this.flags = RenderSectionFlags.setFlag(this.flags, flag, value);
    }

    // ---- Render content ----

    public BlockEntity[] getGlobalBlockEntities() { return globalBlockEntities; }
    public BlockEntity[] getCulledBlockEntities() { return culledBlockEntities; }
    public TextureAtlasSprite[] getAnimatedSprites() { return animatedSprites; }

    /**
     * Apply build results to this section.
     * Called on the main thread after chunk compilation completes.
     */
    public void setBuiltInfo(int flags, BlockEntity[] globalEntities, BlockEntity[] culledEntities,
                              TextureAtlasSprite[] sprites) {
        this.flags = flags;
        this.globalBlockEntities = globalEntities;
        this.culledBlockEntities = culledEntities;
        this.animatedSprites = sprites;
        this.built = true;
    }

    // ---- Translucency sorting ----

    public boolean needsDynamicTranslucencySorting() { return needsDynamicTranslucencySorting; }
    public void setNeedsDynamicTranslucencySorting(boolean needs) { this.needsDynamicTranslucencySorting = needs; }

    public double getLastCameraX() { return lastCameraX; }
    public double getLastCameraY() { return lastCameraY; }
    public double getLastCameraZ() { return lastCameraZ; }

    public void setLastCamera(double x, double y, double z) {
        this.lastCameraX = x;
        this.lastCameraY = y;
        this.lastCameraZ = z;
    }

    // ---- PAUC integration ----

    public float getRenderPriority() { return renderPriority; }
    public void setRenderPriority(float priority) { this.renderPriority = priority; }

    // ---- Lifecycle ----

    /** Check if the section is within the given chunk-aligned grid. */
    public boolean isAlignedWithSectionOnGrid(int x, int y, int z) {
        return this.chunkX == x && this.chunkY == y && this.chunkZ == z;
    }

    /** Release all resources and mark as disposed. */
    public void delete() {
        this.disposed = true;
        this.built = false;
        this.flags = 0;
        this.globalBlockEntities = new BlockEntity[0];
        this.culledBlockEntities = new BlockEntity[0];
        this.animatedSprites = new TextureAtlasSprite[0];
        this.pendingUpdateType = null;
        this.visibilityData = 0L;
        this.incomingDirections = 0;

        for (int i = 0; i < GraphDirection.COUNT; i++) {
            PauCRenderSection adj = this.adjacent[i];
            if (adj != null) {
                adj.setAdjacentNode(GraphDirection.opposite(i), null);
                this.adjacent[i] = null;
            }
        }
        this.adjacentMask = 0;
    }

    @Override
    public String toString() {
        return "PauCRenderSection[" + chunkX + "," + chunkY + "," + chunkZ
                + " built=" + built + " flags=0x" + Integer.toHexString(flags) + "]";
    }
}
