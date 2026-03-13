package pauc.pain_au_choc.render.chunk;

/**
 * Bit flags for render section state.
 * Combined into a single int field on PauCRenderSection for cache-friendly access.
 */
public final class RenderSectionFlags {

    /** Section has at least one non-empty render pass. */
    public static final int HAS_GEOMETRY         = 1;

    /** Section contains block entities that require per-frame rendering. */
    public static final int HAS_BLOCK_ENTITIES   = 1 << 1;

    /** Section contains animated texture sprites. */
    public static final int HAS_ANIMATED_SPRITES = 1 << 2;

    /** Section contains translucent geometry that needs sorting. */
    public static final int HAS_TRANSLUCENT      = 1 << 3;

    /** Section is within the player's full-detail ring (PAUC integration). */
    public static final int IN_FULL_DETAIL_RING  = 1 << 4;

    /** Section is within the streaming ring (PAUC integration). */
    public static final int IN_STREAMING_RING    = 1 << 5;

    /** Section has been culled by the PAUC budget system. */
    public static final int BUDGET_CULLED        = 1 << 6;

    private RenderSectionFlags() {}

    public static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }

    public static int setFlag(int flags, int flag, boolean value) {
        if (value) {
            return flags | flag;
        } else {
            return flags & ~flag;
        }
    }
}
