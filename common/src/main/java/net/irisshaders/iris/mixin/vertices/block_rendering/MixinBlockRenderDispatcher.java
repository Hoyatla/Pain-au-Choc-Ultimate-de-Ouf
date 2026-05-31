package net.irisshaders.iris.mixin.vertices.block_rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.BlockRenderingContext;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.irisshaders.iris.vertices.ExtendedDataHelper;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockRenderDispatcher.class)
public class MixinBlockRenderDispatcher {
	@Unique
	private static boolean pauc$reportedBlockHook;
	@Unique
	private static boolean pauc$reportedFluidHook;

	@Unique
	private static short pauc$resolveBlockId(BlockState blockState) {
		Object2IntMap<BlockState> blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		if (blockStateIds == null) {
			return -1;
		}

		return (short) blockStateIds.getOrDefault(blockState, -1);
	}

	@Unique
	private static void pauc$begin(VertexConsumer vertexConsumer, BlockState blockState, short renderType, BlockPos blockPos) {
		short blockId = pauc$resolveBlockId(blockState);
		int localX = blockPos.getX() & 15;
		int localY = blockPos.getY() & 15;
		int localZ = blockPos.getZ() & 15;

		BlockRenderingContext.begin(blockId, renderType, localX, localY, localZ);

		if (vertexConsumer instanceof BlockSensitiveBufferBuilder blockSensitiveBufferBuilder) {
			blockSensitiveBufferBuilder.beginBlock(blockId, renderType, localX, localY, localZ);
		}
	}

	@Unique
	private static void pauc$end(VertexConsumer vertexConsumer) {
		if (vertexConsumer instanceof BlockSensitiveBufferBuilder blockSensitiveBufferBuilder) {
			blockSensitiveBufferBuilder.endBlock();
		}

		BlockRenderingContext.end();
	}

	@Inject(method = "m_234355_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void pauc$cullFarVanillaFoliage(
		BlockState blockState, BlockPos blockPos, BlockAndTintGetter level, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, CallbackInfo ci
	) {
		if (PauCLodRenderCulling.shouldCullVanillaFoliage(blockState, blockPos)) {
			ci.cancel();
		}
	}

	@Inject(method = "m_234355_", at = @At("HEAD"), remap = false, require = 0)
	private void pauc$beforeRenderBlock(
		BlockState blockState, BlockPos blockPos, BlockAndTintGetter level, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, CallbackInfo ci
	) {
		if (!pauc$reportedBlockHook) {
			pauc$reportedBlockHook = true;
			Iris.logger.info("PauC vanilla block render context hook is active with {}.",
				vertexConsumer instanceof BlockSensitiveBufferBuilder ? "direct buffer" : vertexConsumer.getClass().getName());
		}

		pauc$begin(vertexConsumer, blockState, ExtendedDataHelper.BLOCK_RENDER_TYPE, blockPos);
	}

	@Inject(method = "m_234355_", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$afterRenderBlock(
		BlockState blockState, BlockPos blockPos, BlockAndTintGetter level, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, CallbackInfo ci
	) {
		pauc$end(vertexConsumer);
	}

	@Inject(method = "m_234363_", at = @At("HEAD"), remap = false, require = 0)
	private void pauc$beforeRenderFluid(
		BlockPos blockPos, BlockAndTintGetter level, VertexConsumer vertexConsumer,
		BlockState blockState, FluidState fluidState, CallbackInfo ci
	) {
		if (!pauc$reportedFluidHook && vertexConsumer instanceof BlockSensitiveBufferBuilder) {
			pauc$reportedFluidHook = true;
			Iris.logger.info("PauC vanilla fluid render context hook is active.");
		}

		pauc$begin(vertexConsumer, fluidState.createLegacyBlock(), ExtendedDataHelper.FLUID_RENDER_TYPE, blockPos);
	}

	@Inject(method = "m_234363_", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$afterRenderFluid(
		BlockPos blockPos, BlockAndTintGetter level, VertexConsumer vertexConsumer,
		BlockState blockState, FluidState fluidState, CallbackInfo ci
	) {
		pauc$end(vertexConsumer);
	}
}
