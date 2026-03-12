package pauc.pain_au_choc.render.terrain;

/**
 * Represents a per-quad material with render pass assignment and alpha cutoff.
 * Used during chunk mesh compilation to encode material properties into vertex data.
 *
 * Adapted from Embeddium's Material class.
 */
public class PauCMaterial {

    /** Alpha cutoff parameters for different transparency modes. */
    public enum AlphaCutoff {
        /** No alpha testing. */
        NONE(0.0f),
        /** Standard alpha cutoff at 0.1 (cutout_mipped). */
        TENTH(0.1f),
        /** Half alpha cutoff at 0.5 (cutout). */
        HALF(0.5f);

        private final float threshold;

        AlphaCutoff(float threshold) {
            this.threshold = threshold;
        }

        public float getThreshold() {
            return this.threshold;
        }
    }

    private final PauCTerrainRenderPass pass;
    private final AlphaCutoff alphaCutoff;
    private final boolean mipped;
    private final int packed;

    public PauCMaterial(PauCTerrainRenderPass pass, AlphaCutoff alphaCutoff, boolean mipped) {
        this.pass = pass;
        this.alphaCutoff = alphaCutoff;
        this.mipped = mipped;
        // Pack material properties into a single int for vertex encoding
        int bits = 0;
        bits |= (alphaCutoff.ordinal() & 0x3);         // bits 0-1: alpha mode
        bits |= (mipped ? 1 : 0) << 2;                  // bit 2: mipped
        bits |= (pass.supportsFragmentDiscard() ? 1 : 0) << 3; // bit 3: discard
        this.packed = bits;
    }

    public PauCTerrainRenderPass getPass() {
        return this.pass;
    }

    public AlphaCutoff getAlphaCutoff() {
        return this.alphaCutoff;
    }

    public boolean isMipped() {
        return this.mipped;
    }

    /** Packed integer representation for embedding in vertex data. */
    public int bits() {
        return this.packed;
    }
}
