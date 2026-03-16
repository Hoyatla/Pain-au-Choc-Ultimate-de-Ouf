package pauc.pain_au_choc.render.vertex;

/**
 * Defines the vertex attributes used in chunk mesh rendering.
 * Each attribute has a size in bytes and an OpenGL type.
 *
 * The compact format reduces per-vertex size from vanilla's 32 bytes
 * to approximately 20 bytes, significantly reducing GPU memory bandwidth.
 */
public enum PauCChunkMeshAttribute {
    /** Block-local position encoded as 3 unsigned shorts (6 bytes). */
    POSITION(6),

    /** RGBA color packed as 4 unsigned bytes (4 bytes). */
    COLOR(4),

    /** Texture UV coordinates as 2 unsigned shorts (4 bytes). */
    TEXTURE(4),

    /** Block light + sky light packed as 2 unsigned bytes (2 bytes). */
    LIGHT(2),

    /** Normal vector packed as 1 int (4 bytes, 10-10-10-2 format). */
    NORMAL(4);

    /** Size of this attribute in bytes. */
    private final int size;

    PauCChunkMeshAttribute(int size) {
        this.size = size;
    }

    public int getSize() {
        return this.size;
    }

    /** Total stride of all attributes combined. */
    public static int getTotalStride() {
        int stride = 0;
        for (PauCChunkMeshAttribute attr : values()) {
            stride += attr.size;
        }
        return stride; // Should be 20 bytes
    }
}
