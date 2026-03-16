package pauc.pain_au_choc.render.terrain;

import net.minecraft.client.renderer.RenderType;

/**
 * Pre-defined terrain render passes matching vanilla Minecraft's three main terrain layers.
 */
public final class DefaultTerrainRenderPasses {

    /** Opaque blocks (stone, dirt, etc.) - rendered first, front-to-back. */
    public static final PauCTerrainRenderPass SOLID = new PauCTerrainRenderPass(
            RenderType.solid(),
            false,  // front-to-back
            false,  // no alpha discard
            false   // no translucency sorting
    );

    /** Blocks with cutout transparency (leaves, glass panes, flowers). */
    public static final PauCTerrainRenderPass CUTOUT = new PauCTerrainRenderPass(
            RenderType.cutout(),
            false,  // front-to-back
            true,   // alpha discard enabled
            false   // no translucency sorting
    );

    /** Translucent blocks (water, stained glass, ice). */
    public static final PauCTerrainRenderPass TRANSLUCENT = new PauCTerrainRenderPass(
            RenderType.translucent(),
            true,   // back-to-front
            false,  // no alpha discard (uses blending)
            true    // translucency sorting enabled
    );

    /** All passes in render order. */
    public static final PauCTerrainRenderPass[] ALL = { SOLID, CUTOUT, TRANSLUCENT };

    private DefaultTerrainRenderPasses() {}

    /**
     * Find the PauC terrain pass corresponding to a vanilla RenderType.
     * Returns null if no match found.
     */
    public static PauCTerrainRenderPass fromVanilla(RenderType renderType) {
        if (renderType == RenderType.solid()) return SOLID;
        if (renderType == RenderType.cutout() || renderType == RenderType.cutoutMipped()) return CUTOUT;
        if (renderType == RenderType.translucent()) return TRANSLUCENT;
        return null;
    }
}
