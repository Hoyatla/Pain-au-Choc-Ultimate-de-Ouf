package pauc.pain_au_choc.render.region;

import pauc.pain_au_choc.render.chunk.PauCRenderSection;
import pauc.pain_au_choc.render.terrain.PauCTerrainRenderPass;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Groups render sections into 8x4x8 regions (256 sections per region).
 * Reduces GPU resource overhead by sharing vertex/index buffers within a region.
 *
 * Adapted from Embeddium's RenderRegion, simplified for PAUC integration.
 */
public class PauCRenderRegion {

    /** Region dimensions in chunk sections. */
    public static final int REGION_WIDTH  = 8;
    public static final int REGION_HEIGHT = 4;
    public static final int REGION_LENGTH = 8;

    /** Total section capacity per region. */
    public static final int REGION_SIZE = REGION_WIDTH * REGION_HEIGHT * REGION_LENGTH; // 256

    /** Bit shifts for local index computation. */
    private static final int SHIFT_Y = 0;
    private static final int SHIFT_X = Integer.numberOfTrailingZeros(REGION_HEIGHT);
    private static final int SHIFT_Z = SHIFT_X + Integer.numberOfTrailingZeros(REGION_WIDTH);

    /** Region origin in chunk coordinates. */
    private final int regionX, regionY, regionZ;

    /** Active sections in this region (null = empty slot). */
    private final PauCRenderSection[] sections = new PauCRenderSection[REGION_SIZE];

    /** Number of active (non-null) sections. */
    private int sectionCount = 0;

    /** Per-pass GPU storage (vertex buffers, index buffers). Will be populated in Phase 1.5. */
    private final Map<PauCTerrainRenderPass, Object> passStorage = new HashMap<>();

    /** Whether GPU resources need a refresh. */
    private boolean needsRefresh = false;

    public PauCRenderRegion(int regionX, int regionY, int regionZ) {
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionZ = regionZ;
    }

    // ---- Position ----

    /** Create a unique key for a region at the given chunk coordinates. */
    public static long key(int chunkX, int chunkY, int chunkZ) {
        int rx = chunkX >> 3; // divide by REGION_WIDTH
        int ry = chunkY >> 2; // divide by REGION_HEIGHT
        int rz = chunkZ >> 3; // divide by REGION_LENGTH
        return ((long) rx & 0x1FFFFF) | (((long) ry & 0xFFF) << 21) | (((long) rz & 0x1FFFFF) << 33);
    }

    /** Region-local section index from chunk coordinates. */
    public static int localIndex(int chunkX, int chunkY, int chunkZ) {
        int lx = chunkX & (REGION_WIDTH - 1);
        int ly = chunkY & (REGION_HEIGHT - 1);
        int lz = chunkZ & (REGION_LENGTH - 1);
        return (ly << SHIFT_Y) | (lx << SHIFT_X) | (lz << SHIFT_Z);
    }

    public int getRegionX() { return this.regionX; }
    public int getRegionY() { return this.regionY; }
    public int getRegionZ() { return this.regionZ; }

    /** Origin in block coordinates. */
    public int getOriginX() { return this.regionX * REGION_WIDTH * 16; }
    public int getOriginY() { return this.regionY * REGION_HEIGHT * 16; }
    public int getOriginZ() { return this.regionZ * REGION_LENGTH * 16; }

    /** Center in block coordinates. */
    public int getCenterX() { return getOriginX() + (REGION_WIDTH * 16) / 2; }
    public int getCenterY() { return getOriginY() + (REGION_HEIGHT * 16) / 2; }
    public int getCenterZ() { return getOriginZ() + (REGION_LENGTH * 16) / 2; }

    // ---- Section management ----

    public void addSection(PauCRenderSection section) {
        int idx = section.getSectionIndex();
        if (this.sections[idx] == null) {
            this.sectionCount++;
        }
        this.sections[idx] = section;
        this.needsRefresh = true;
    }

    public void removeSection(PauCRenderSection section) {
        int idx = section.getSectionIndex();
        if (this.sections[idx] != null) {
            this.sectionCount--;
        }
        this.sections[idx] = null;
        this.needsRefresh = true;
    }

    public PauCRenderSection getSection(int localIndex) {
        return this.sections[localIndex];
    }

    public boolean isEmpty() {
        return this.sectionCount == 0;
    }

    public int getSectionCount() {
        return this.sectionCount;
    }

    // ---- GPU resources (stubs for Phase 1.5) ----

    public boolean needsRefresh() {
        return this.needsRefresh;
    }

    public void markRefreshed() {
        this.needsRefresh = false;
    }

    /** Release all GPU resources held by this region. */
    public void delete() {
        this.passStorage.clear();
        for (int i = 0; i < REGION_SIZE; i++) {
            this.sections[i] = null;
        }
        this.sectionCount = 0;
    }

    @Override
    public String toString() {
        return "PauCRenderRegion[" + regionX + "," + regionY + "," + regionZ
                + " sections=" + sectionCount + "]";
    }
}
