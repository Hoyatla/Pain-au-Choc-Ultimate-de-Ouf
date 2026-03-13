package pauc.pain_au_choc.render.occlusion;

/**
 * Represents the 6 cardinal directions for occlusion graph traversal.
 * Adapted from Embeddium's GraphDirection for PAUC's visibility system.
 */
public final class GraphDirection {

    public static final int DOWN  = 0;
    public static final int UP    = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST  = 4;
    public static final int EAST  = 5;

    public static final int COUNT = 6;

    /** All directions as a bitmask. */
    public static final int ALL = (1 << COUNT) - 1;
    public static final int NONE = 0;

    private static final int[] OPPOSITES = { UP, DOWN, SOUTH, NORTH, EAST, WEST };

    /** X offsets per direction (DOWN=0, UP=0, NORTH=0, SOUTH=0, WEST=-1, EAST=+1). */
    private static final int[] OFFSET_X = { 0, 0, 0, 0, -1, 1 };
    /** Y offsets per direction. */
    private static final int[] OFFSET_Y = { -1, 1, 0, 0, 0, 0 };
    /** Z offsets per direction. */
    private static final int[] OFFSET_Z = { 0, 0, -1, 1, 0, 0 };

    private GraphDirection() {}

    public static int opposite(int dir) {
        return OPPOSITES[dir];
    }

    public static int offsetX(int dir) {
        return OFFSET_X[dir];
    }

    public static int offsetY(int dir) {
        return OFFSET_Y[dir];
    }

    public static int offsetZ(int dir) {
        return OFFSET_Z[dir];
    }

    /** Convert a direction index to a single-bit flag. */
    public static int flag(int dir) {
        return 1 << dir;
    }

    /** Check if a direction set contains a specific direction. */
    public static boolean contains(int set, int dir) {
        return (set & flag(dir)) != 0;
    }
}
