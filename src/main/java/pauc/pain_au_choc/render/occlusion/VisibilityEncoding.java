package pauc.pain_au_choc.render.occlusion;

import net.minecraft.core.Direction;

/**
 * Encodes a 6x6 direction-to-direction visibility matrix in a single long (64-bit).
 * Each bit at position (from * 6 + to) indicates whether entering from 'from' can exit via 'to'.
 *
 * Adapted from Embeddium's VisibilityEncoding for PAUC's occlusion culling system.
 */
public final class VisibilityEncoding {

    /** No visibility at all. */
    public static final long NULL = 0L;

    /** Full visibility (all directions see all directions). */
    public static final long ALL;

    static {
        long all = 0L;
        for (int from = 0; from < GraphDirection.COUNT; from++) {
            for (int to = 0; to < GraphDirection.COUNT; to++) {
                all |= 1L << (from * GraphDirection.COUNT + to);
            }
        }
        ALL = all;
    }

    private VisibilityEncoding() {}

    /**
     * Encode vanilla Minecraft VisibilitySet (from LevelRenderer occlusion culling) into our compact format.
     * VisibilitySet.visibilityBetween(Direction from, Direction to) -> boolean
     */
    public static long encode(Object visibilitySet) {
        // We'll use reflection or direct access depending on mapping
        // For now, encode from Minecraft's Direction-based connectivity
        long encoded = 0L;
        try {
            var method = visibilitySet.getClass().getMethod("visibilityBetween", Direction.class, Direction.class);
            Direction[] dirs = Direction.values();
            for (int from = 0; from < 6; from++) {
                for (int to = 0; to < 6; to++) {
                    boolean visible = (boolean) method.invoke(visibilitySet, dirs[from], dirs[to]);
                    if (visible) {
                        encoded |= 1L << (from * 6 + to);
                    }
                }
            }
        } catch (Exception e) {
            // Fallback: assume full visibility
            return ALL;
        }
        return encoded;
    }

    /**
     * Encode directly from a boolean visibility array [6][6].
     */
    public static long encode(boolean[][] visibility) {
        long encoded = 0L;
        for (int from = 0; from < 6; from++) {
            for (int to = 0; to < 6; to++) {
                if (visibility[from][to]) {
                    encoded |= 1L << (from * 6 + to);
                }
            }
        }
        return encoded;
    }

    /**
     * Given a set of incoming directions (bitmask), compute the union of all reachable
     * outgoing directions through this section's visibility data.
     */
    public static int getConnections(long visibilityData, int incomingDirections) {
        int outgoing = 0;
        for (int from = 0; from < GraphDirection.COUNT; from++) {
            if ((incomingDirections & (1 << from)) != 0) {
                // Extract the 6-bit row for this 'from' direction
                int row = (int) ((visibilityData >>> (from * 6)) & 0x3F);
                outgoing |= row;
            }
        }
        return outgoing;
    }

    /**
     * Get all possible outgoing directions regardless of incoming direction.
     */
    public static int getConnections(long visibilityData) {
        return getConnections(visibilityData, GraphDirection.ALL);
    }
}
