package pauc.pain_au_choc.render.terrain;

import net.minecraft.client.renderer.RenderType;

/**
 * Defines a terrain render pass with its associated GL state configuration.
 * Each pass corresponds to a vanilla RenderType (SOLID, CUTOUT, TRANSLUCENT)
 * but with PAUC-specific optimizations.
 *
 * Adapted from Embeddium's TerrainRenderPass.
 */
public class PauCTerrainRenderPass {

    private final RenderType renderType;
    private final boolean reverseOrder;
    private final boolean fragmentDiscard;
    private final boolean translucencySorting;

    public PauCTerrainRenderPass(RenderType renderType, boolean reverseOrder,
                                  boolean fragmentDiscard, boolean translucencySorting) {
        this.renderType = renderType;
        this.reverseOrder = reverseOrder;
        this.fragmentDiscard = fragmentDiscard;
        this.translucencySorting = translucencySorting;
    }

    /** The vanilla RenderType used to set up GL state (blending, depth, etc.). */
    public RenderType getRenderType() {
        return this.renderType;
    }

    /** Whether to render back-to-front (for translucent geometry). */
    public boolean isReverseOrder() {
        return this.reverseOrder;
    }

    /** Whether the fragment shader should discard fragments below alpha threshold. */
    public boolean supportsFragmentDiscard() {
        return this.fragmentDiscard;
    }

    /** Whether translucency sorting is supported and enabled for this pass. */
    public boolean isSorted() {
        return this.translucencySorting;
    }

    /** Set up GL state before drawing this pass. */
    public void startDrawing() {
        this.renderType.setupRenderState();
    }

    /** Restore GL state after drawing this pass. */
    public void endDrawing() {
        this.renderType.clearRenderState();
    }

    @Override
    public String toString() {
        return "PauCTerrainRenderPass{" + this.renderType + "}";
    }
}
