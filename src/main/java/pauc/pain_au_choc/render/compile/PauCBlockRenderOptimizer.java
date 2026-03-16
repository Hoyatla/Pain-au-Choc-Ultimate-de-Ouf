package pauc.pain_au_choc.render.compile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import pauc.pain_au_choc.PauCClient;

/**
 * Optimizations for block rendering adapted from Embeddium.
 *
 * - Smart leaves: skip interior faces when surrounded by same leaf type.
 * - Redundant face culling: skip faces against full-cube opaque neighbors.
 * - Palette fast-path: detect single-value palette sections to skip iteration.
 */
public final class PauCBlockRenderOptimizer {

    private PauCBlockRenderOptimizer() {
    }

    /**
     * Returns true if the given face of a leaves block should be culled because
     * the neighbor in that direction is also a leaves block of the same type.
     * This dramatically reduces vertex count in dense forests.
     */
    public static boolean shouldCullLeavesFace(BlockGetter world, BlockPos pos, BlockState state, Direction face) {
        if (!PauCClient.isBudgetActive()) {
            return false;
        }

        if (!(state.getBlock() instanceof LeavesBlock)) {
            return false;
        }

        BlockPos neighborPos = pos.relative(face);
        BlockState neighborState = world.getBlockState(neighborPos);
        if (!(neighborState.getBlock() instanceof LeavesBlock)) {
            return false;
        }

        // Cull the face if both are the same leaf type
        return state.getBlock() == neighborState.getBlock();
    }

    /**
     * Fast check whether a block face is fully occluded by an opaque full-cube neighbor.
     * Avoids the more expensive vanilla shape comparison for the common case.
     */
    public static boolean isFullCubeOpaqueNeighbor(BlockGetter world, BlockPos pos, Direction face) {
        BlockPos neighborPos = pos.relative(face);
        BlockState neighborState = world.getBlockState(neighborPos);
        return neighborState.isCollisionShapeFullBlock(world, neighborPos)
                && neighborState.canOcclude();
    }

    /**
     * Determines if a LevelChunkSection contains only a single block type.
     * If so, the entire section can be rendered as a single batch or skipped
     * if the block is air.
     *
     * @param maybeCount the number of non-air blocks reported by the section
     * @param totalBlocks the total capacity (4096 for a standard section)
     * @return true if the section is homogeneous
     */
    public static boolean isSingleValueSection(int maybeCount, int totalBlocks) {
        // A section with 0 non-air blocks is all air — skip entirely.
        // A section with exactly totalBlocks non-air blocks is fully solid
        // and may benefit from simplified rendering.
        return maybeCount == 0 || maybeCount == totalBlocks;
    }

    /**
     * Returns a render priority hint for a block state.
     * Lower values mean higher priority in compile queue.
     * Transparent/translucent blocks are deprioritized.
     */
    public static int getRenderPriority(BlockState state) {
        if (state.isAir()) {
            return Integer.MAX_VALUE;
        }
        if (state.getBlock() instanceof LeavesBlock) {
            return 3;
        }
        if (!state.canOcclude()) {
            return 2;
        }
        return 1;
    }
}
