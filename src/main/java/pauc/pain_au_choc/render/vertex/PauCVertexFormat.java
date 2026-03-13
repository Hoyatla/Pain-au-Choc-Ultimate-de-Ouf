package pauc.pain_au_choc.render.vertex;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Defines the compact vertex format for chunk mesh rendering.
 *
 * Layout (20 bytes per vertex):
 *   Offset 0:  Position   - 3x unsigned short (6 bytes) - block-local coords * scale
 *   Offset 6:  Color      - 4x unsigned byte (4 bytes) - RGBA
 *   Offset 10: TexCoord   - 2x unsigned short (4 bytes) - atlas UV
 *   Offset 14: Light      - 2x unsigned byte (2 bytes) - block light, sky light
 *   Offset 16: Normal     - 1x int (4 bytes) - packed 10-10-10-2 format
 *
 * This is ~37% smaller than vanilla's 32-byte vertex format,
 * significantly reducing GPU memory usage and bandwidth pressure.
 *
 * Adapted from Embeddium's ChunkVertexType concept.
 */
public class PauCVertexFormat {

    /** Bytes per vertex. */
    public static final int STRIDE = PauCChunkMeshAttribute.getTotalStride(); // 20

    /** Scale factor for position encoding (positions are multiplied by this before encoding). */
    public static final float POSITION_SCALE = 2048.0f;

    /** Offset applied to positions before encoding. */
    public static final float POSITION_OFFSET = -8.0f; // centers the section at origin

    /** Scale factor for texture coordinates. */
    public static final float TEXTURE_SCALE = 65536.0f;

    // Byte offsets within the vertex
    public static final int OFFSET_POSITION = 0;
    public static final int OFFSET_COLOR    = 6;
    public static final int OFFSET_TEXTURE  = 10;
    public static final int OFFSET_LIGHT    = 14;
    public static final int OFFSET_NORMAL   = 16;

    // GL attribute indices (matching shader layout locations)
    public static final int ATTRIB_POSITION = 0;
    public static final int ATTRIB_COLOR    = 1;
    public static final int ATTRIB_TEXTURE  = 2;
    public static final int ATTRIB_LIGHT    = 3;
    public static final int ATTRIB_NORMAL   = 4;

    /** Extra attribute indices reserved for shader packs (Phase 2.7). */
    public static final int ATTRIB_BLOCK_ID     = 5;
    public static final int ATTRIB_TANGENT      = 6;
    public static final int ATTRIB_MID_TEXCOORD = 7;
    public static final int ATTRIB_MID_BLOCK    = 8;

    private PauCVertexFormat() {}

    /**
     * Set up vertex attribute pointers for the compact chunk format.
     * Must be called with a VAO bound and the VBO containing chunk data bound.
     */
    public static void setupVertexAttributes() {
        // Position: 3x unsigned short at offset 0
        GL20.glVertexAttribPointer(ATTRIB_POSITION, 3, GL11.GL_UNSIGNED_SHORT,
                false, STRIDE, OFFSET_POSITION);
        GL20.glEnableVertexAttribArray(ATTRIB_POSITION);

        // Color: 4x unsigned byte at offset 6, normalized to [0,1]
        GL20.glVertexAttribPointer(ATTRIB_COLOR, 4, GL11.GL_UNSIGNED_BYTE,
                true, STRIDE, OFFSET_COLOR);
        GL20.glEnableVertexAttribArray(ATTRIB_COLOR);

        // Texture: 2x unsigned short at offset 10
        GL20.glVertexAttribPointer(ATTRIB_TEXTURE, 2, GL11.GL_UNSIGNED_SHORT,
                false, STRIDE, OFFSET_TEXTURE);
        GL20.glEnableVertexAttribArray(ATTRIB_TEXTURE);

        // Light: 2x unsigned byte at offset 14, normalized
        GL20.glVertexAttribPointer(ATTRIB_LIGHT, 2, GL11.GL_UNSIGNED_BYTE,
                true, STRIDE, OFFSET_LIGHT);
        GL20.glEnableVertexAttribArray(ATTRIB_LIGHT);

        // Normal: packed int at offset 16 (read as 4 signed bytes, 10-10-10-2)
        GL30.glVertexAttribIPointer(ATTRIB_NORMAL, 4, GL11.GL_BYTE,
                STRIDE, OFFSET_NORMAL);
        GL20.glEnableVertexAttribArray(ATTRIB_NORMAL);
    }

    /**
     * Disable all vertex attribute arrays for this format.
     */
    public static void disableVertexAttributes() {
        for (int i = 0; i <= ATTRIB_NORMAL; i++) {
            GL20.glDisableVertexAttribArray(i);
        }
    }
}
