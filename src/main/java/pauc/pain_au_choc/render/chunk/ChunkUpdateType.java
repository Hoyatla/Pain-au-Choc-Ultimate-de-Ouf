package pauc.pain_au_choc.render.chunk;

/**
 * Types of chunk section updates that can be scheduled.
 * Adapted from Embeddium for PAUC's chunk build pipeline.
 */
public enum ChunkUpdateType {
    /** Initial build of a section that has never been meshed. */
    INITIAL(true),

    /** Rebuild due to block change or neighbor update. */
    REBUILD(true),

    /** Important rebuild that should be prioritized (e.g., block the player broke). */
    IMPORTANT_REBUILD(true),

    /** Re-sort translucent geometry due to camera movement. */
    SORT(false),

    /** Re-sort translucent geometry (important, closer to player). */
    IMPORTANT_SORT(false);

    private final boolean requiresMeshing;

    ChunkUpdateType(boolean requiresMeshing) {
        this.requiresMeshing = requiresMeshing;
    }

    /** Whether this update requires full mesh re-compilation (vs. just re-sorting). */
    public boolean requiresMeshing() {
        return this.requiresMeshing;
    }

    /** Whether this update should be prioritized over others of the same kind. */
    public boolean isImportant() {
        return this == IMPORTANT_REBUILD || this == IMPORTANT_SORT;
    }

    /**
     * Resolve the higher-priority update type when two overlap.
     * Important > normal, rebuild > sort, initial > rebuild.
     */
    public static ChunkUpdateType merge(ChunkUpdateType a, ChunkUpdateType b) {
        if (a == null) return b;
        if (b == null) return a;
        // INITIAL always wins
        if (a == INITIAL || b == INITIAL) return INITIAL;
        // Meshing wins over sorting
        boolean needsMesh = a.requiresMeshing || b.requiresMeshing;
        boolean important = a.isImportant() || b.isImportant();
        if (needsMesh) {
            return important ? IMPORTANT_REBUILD : REBUILD;
        } else {
            return important ? IMPORTANT_SORT : SORT;
        }
    }
}
