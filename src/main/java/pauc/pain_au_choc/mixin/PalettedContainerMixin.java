package pauc.pain_au_choc.mixin;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pauc.pain_au_choc.Pain_au_Choc;

/**
 * Mixin to optimize PalettedContainer operations.
 * Detects single-value palettes and provides fast-path access.
 *
 * When a chunk section contains only one block type (very common for air-only
 * or stone-only sections), the standard iteration can be bypassed entirely.
 *
 * Adapted from Embeddium's PalettedContainerMixin.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin {

    /**
     * After acquiring the data, check if the container is trivially single-valued.
     * This allows PauCChunkBuilder to skip full iteration for homogeneous sections.
     *
     * NOTE: The actual fast-path check is done in PauCBlockRenderOptimizer.isSingleValueSection().
     * This mixin exists as a hook point for future deeper optimizations
     * (e.g., caching the single value, providing direct palette access).
     */
    @Inject(method = "acquire", at = @At("RETURN"))
    private void pauc$onAcquire(CallbackInfo ci) {
        // Currently a no-op hook — the optimization is in PauCBlockRenderOptimizer.
        // This hook allows future enhancements like single-value caching
        // without requiring additional mixins.
    }
}
