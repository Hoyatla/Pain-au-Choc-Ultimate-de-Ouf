package pauc.pain_au_choc.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin on BlockBehaviour to optimize leaves block rendering by culling interior faces.
 * When two adjacent leaves blocks are the same type, the shared face is skipped.
 * This significantly reduces vertex count in dense forest areas.
 *
 * Adapted from Embeddium's LeavesBlockMixin.
 *
 * skipRendering is defined in BlockBehaviour, not in LeavesBlock,
 * so we must target BlockBehaviour and filter to LeavesBlock instances.
 */
@Mixin(BlockBehaviour.class)
public abstract class LeavesBlockMixin {

    /**
     * Inject into skipRendering to cull faces between matching leaves blocks.
     * Only activates when both blocks are LeavesBlock of the same type.
     */
    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void pauc$smartLeavesCulling(BlockState state, BlockState neighborState, Direction direction,
                                          CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof LeavesBlock) {
            if (neighborState.getBlock() instanceof LeavesBlock
                    && state.getBlock() == neighborState.getBlock()) {
                cir.setReturnValue(true);
            }
        }
    }
}
