package net.irisshaders.iris.mixin.vertices.block_rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.BlockRenderingContext;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.irisshaders.iris.vertices.ExtendedDataHelper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBlockRenderer.class)
public class MixinModelBlockRenderer {
	@Unique
	private static boolean pauc$reportedModelHook;

	@Unique
	private static short pauc$resolveBlockId(BlockState blockState) {
		Object2IntMap<BlockState> blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		if (blockStateIds == null) {
			return -1;
		}

		return (short) blockStateIds.getOrDefault(blockState, -1);
	}

	@Unique
	private static void pauc$begin(VertexConsumer vertexConsumer, BlockState blockState, BlockPos blockPos) {
		short blockId = pauc$resolveBlockId(blockState);
		int localX = blockPos.getX() & 15;
		int localY = blockPos.getY() & 15;
		int localZ = blockPos.getZ() & 15;

		BlockRenderingContext.begin(blockId, ExtendedDataHelper.BLOCK_RENDER_TYPE, localX, localY, localZ);

		if (vertexConsumer instanceof BlockSensitiveBufferBuilder blockSensitiveBufferBuilder) {
			blockSensitiveBufferBuilder.beginBlock(blockId, ExtendedDataHelper.BLOCK_RENDER_TYPE, localX, localY, localZ);
		}
	}

	@Unique
	private static void pauc$end(VertexConsumer vertexConsumer) {
		if (vertexConsumer instanceof BlockSensitiveBufferBuilder blockSensitiveBufferBuilder) {
			blockSensitiveBufferBuilder.endBlock();
		}

		BlockRenderingContext.end();
	}

	@Inject(method = "m_234379_", at = @At("HEAD"), remap = false, require = 0)
	private void pauc$beforeTesselateBlock(
		BlockAndTintGetter level, BakedModel model, BlockState blockState, BlockPos blockPos, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, long seed, int overlay, CallbackInfo ci
	) {
		if (!pauc$reportedModelHook) {
			pauc$reportedModelHook = true;
			Iris.logger.info("PauC vanilla model block render context hook is active with {}.",
				vertexConsumer instanceof BlockSensitiveBufferBuilder ? "direct buffer" : vertexConsumer.getClass().getName());
		}

		pauc$begin(vertexConsumer, blockState, blockPos);
	}

	@Inject(method = "m_234379_", at = @At("RETURN"), remap = false, require = 0)
	private void pauc$afterTesselateBlock(
		BlockAndTintGetter level, BakedModel model, BlockState blockState, BlockPos blockPos, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, long seed, int overlay, CallbackInfo ci
	) {
		pauc$end(vertexConsumer);
	}

	@Inject(
		method = "tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
		at = @At("HEAD"),
		remap = false,
		require = 0
	)
	private void pauc$beforeForgeTesselateBlock(
		BlockAndTintGetter level, BakedModel model, BlockState blockState, BlockPos blockPos, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, long seed, int overlay,
		ModelData modelData, RenderType renderType, CallbackInfo ci
	) {
		if (!pauc$reportedModelHook) {
			pauc$reportedModelHook = true;
			Iris.logger.info("PauC Forge model block render context hook is active with {}.",
				vertexConsumer instanceof BlockSensitiveBufferBuilder ? "direct buffer" : vertexConsumer.getClass().getName());
		}

		pauc$begin(vertexConsumer, blockState, blockPos);
	}

	@Inject(
		method = "tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
		at = @At("RETURN"),
		remap = false,
		require = 0
	)
	private void pauc$afterForgeTesselateBlock(
		BlockAndTintGetter level, BakedModel model, BlockState blockState, BlockPos blockPos, PoseStack poseStack,
		VertexConsumer vertexConsumer, boolean checkSides, RandomSource randomSource, long seed, int overlay,
		ModelData modelData, RenderType renderType, CallbackInfo ci
	) {
		pauc$end(vertexConsumer);
	}
}
