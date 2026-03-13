package pauc.pain_au_choc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pauc.pain_au_choc.render.compile.PauCBlockRenderOptimizer;

/**
 * Mixin to optimize leaves block rendering by culling interior faces.
 * When two adjacent leaves blocks are the same type, the shared face is skipped.
 * This significantly reduces vertex count in dense forest areas.
 *
 * Adapted from Embeddium's LeavesBlockMixin.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    /**
     * Override skipRendering to cull faces between matching leaves blocks.
     * Vanilla returns false for all leaves faces; we return true when
     * the neighbor is the same leaf type.
     */
    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void pauc$smartLeavesCulling(BlockState state, BlockState neighborState, Direction direction,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (neighborState.getBlock() instanceof LeavesBlock
                && state.getBlock() == neighborState.getBlock()) {
            cir.setReturnValue(true);
        }
    }
}
