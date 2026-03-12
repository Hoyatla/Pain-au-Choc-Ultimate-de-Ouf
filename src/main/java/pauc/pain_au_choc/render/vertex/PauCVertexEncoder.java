package pauc.pain_au_choc.render.vertex;

import java.nio.ByteBuffer;

/**
 * Encodes vertex data into the compact PauC chunk vertex format.
 * Used by the chunk mesh compiler to write vertices into build buffers.
 *
 * All encode methods write directly to a ByteBuffer at the current position.
 * After encoding a vertex, the buffer position advances by STRIDE bytes.
 *
 * Adapted from Embeddium's ChunkVertexEncoder concept.
 */
public class PauCVertexEncoder {

    /**
     * Encode a single vertex into the buffer at its current position.
     *
     * @param buffer   Target byte buffer (must have at least STRIDE bytes remaining)
     * @param x        Block-local X position (0.0 to 16.0)
     * @param y        Block-local Y position (0.0 to 16.0)
     * @param z        Block-local Z position (0.0 to 16.0)
     * @param r        Red   (0-255)
     * @param g        Green (0-255)
     * @param b        Blue  (0-255)
     * @param a        Alpha (0-255)
     * @param u        Texture U coordinate (atlas-space float)
     * @param v        Texture V coordinate (atlas-space float)
     * @param blockLight Block light level (0-15)
     * @param skyLight   Sky light level (0-15)
     * @param normalX  Normal X (-1 to 1)
     * @param normalY  Normal Y (-1 to 1)
     * @param normalZ  Normal Z (-1 to 1)
     */
    public static void encode(ByteBuffer buffer, float x, float y, float z,
                               int r, int g, int b, int a,
                               float u, float v,
                               int blockLight, int skyLight,
                               float normalX, float normalY, float normalZ) {

        // Position: encode as unsigned short with scale and offset
        int px = encodePosition(x);
        int py = encodePosition(y);
        int pz = encodePosition(z);
        buffer.putShort((short) px);
        buffer.putShort((short) py);
        buffer.putShort((short) pz);

        // Color: RGBA as 4 bytes
        buffer.put((byte) (r & 0xFF));
        buffer.put((byte) (g & 0xFF));
        buffer.put((byte) (b & 0xFF));
        buffer.put((byte) (a & 0xFF));

        // Texture: UV as unsigned short
        int eu = encodeTexture(u);
        int ev = encodeTexture(v);
        buffer.putShort((short) eu);
        buffer.putShort((short) ev);

        // Light: block + sky as 2 bytes (scaled to 0-255 range)
        buffer.put((byte) ((blockLight & 0xF) * 17)); // 0-15 -> 0-255
        buffer.put((byte) ((skyLight & 0xF) * 17));

        // Normal: pack into 4 bytes (xyz as signed bytes, w unused)
        buffer.put(encodeNormalComponent(normalX));
        buffer.put(encodeNormalComponent(normalY));
        buffer.put(encodeNormalComponent(normalZ));
        buffer.put((byte) 0); // padding / w
    }

    /**
     * Encode a position component (0-16 block-local range) to unsigned short.
     */
    private static int encodePosition(float pos) {
        return (int) ((pos - PauCVertexFormat.POSITION_OFFSET) * PauCVertexFormat.POSITION_SCALE)
                & 0xFFFF;
    }

    /**
     * Encode a texture coordinate to unsigned short.
     */
    private static int encodeTexture(float texCoord) {
        return (int) (texCoord * PauCVertexFormat.TEXTURE_SCALE) & 0xFFFF;
    }

    /**
     * Encode a normal component (-1 to 1) as a signed byte (-127 to 127).
     */
    private static byte encodeNormalComponent(float n) {
        return (byte) Math.max(-127, Math.min(127, (int) (n * 127.0f)));
    }

    /**
     * Decode a position component from unsigned short back to block-local float.
     */
    public static float decodePosition(short encoded) {
        int unsigned = encoded & 0xFFFF;
        return (unsigned / PauCVertexFormat.POSITION_SCALE) + PauCVertexFormat.POSITION_OFFSET;
    }

    /**
     * Decode a texture coordinate from unsigned short back to float.
     */
    public static float decodeTexture(short encoded) {
        int unsigned = encoded & 0xFFFF;
        return unsigned / PauCVertexFormat.TEXTURE_SCALE;
    }

    /**
     * Calculate the buffer size needed for a given number of vertices.
     */
    public static int requiredBytes(int vertexCount) {
        return vertexCount * PauCVertexFormat.STRIDE;
    }

    /**
     * Calculate the buffer size for a given number of quads (4 vertices each).
     */
    public static int requiredBytesForQuads(int quadCount) {
        return requiredBytes(quadCount * 4);
    }
}
