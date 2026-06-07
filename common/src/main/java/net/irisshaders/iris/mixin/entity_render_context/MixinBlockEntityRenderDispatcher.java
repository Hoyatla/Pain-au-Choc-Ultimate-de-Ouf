package net.irisshaders.iris.mixin.entity_render_context;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.batchedentityrendering.impl.Groupable;
import net.irisshaders.batchedentityrendering.impl.wrappers.TaggingRenderTypeWrapper;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.layer.BlockEntityRenderStateShard;
import net.irisshaders.iris.layer.BufferSourceWrapper;
import net.irisshaders.iris.layer.OuterWrappedRenderType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Wraps block entity rendering functions in order to create additional render layers
 * that provide context to shaders about what block entity is currently being
 * rendered.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class MixinBlockEntityRenderDispatcher {
	private static final String RUN_REPORTED =
		"Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/lang/Runnable;)V";
	@Unique
	private static final Map<BlockState, Integer> PAUC_BLOCK_ENTITY_STATE_ID_CACHE = new HashMap<>();
	@Unique
	private static Object2IntMap<BlockState> pauc$lastBlockStateIds;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void pauc$cullFarBlockEntity(BlockEntity blockEntity, float tickDelta, PoseStack matrix,
										 MultiBufferSource bufferSource, CallbackInfo ci) {
		if (PauCLodRenderCulling.shouldCullBlockEntity(blockEntity)) {
			PauCVillagePerformanceDiagnostics.recordBlockEntityCull(blockEntity);
			ci.cancel();
			return;
		}
		PauCVillagePerformanceDiagnostics.recordBlockEntityRender(blockEntity);
	}

	// I inject here in the method so that:
	//
	// 1. we can know that some checks we need have already been done
	// 2. if someone cancels this method hopefully it gets cancelled before this point, so we
	//    aren't running any redundant computations.
	//
	// NOTE: This is the last location that we can inject at, because the MultiBufferSource variable gets
	// captured by the lambda shortly afterwards, and therefore our ModifyVariable call becomes ineffective!
	@ModifyVariable(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/entity/BlockEntityType;isValid(Lnet/minecraft/world/level/block/state/BlockState;)Z"),
		allow = 1, require = 1, argsOnly = true)
	private MultiBufferSource iris$wrapBufferSource(MultiBufferSource bufferSource, BlockEntity blockEntity) {
		BlockState state = blockEntity.getBlockState();

		Object2IntMap<BlockState> blockStateIds = WorldRenderingSettings.INSTANCE.getBlockStateIds();

		if (blockStateIds == null) {
			return bufferSource;
		}

		int intId = pauc$getBlockEntityRenderId(blockStateIds, state);

		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(intId);

		return new BufferSourceWrapper(bufferSource, (renderType) -> OuterWrappedRenderType.wrapExactlyOnce("iris:block_entity", renderType, BlockEntityRenderStateShard.INSTANCE));
	}


	@Inject(method = "render", at = @At(value = "INVOKE", target = RUN_REPORTED, shift = At.Shift.AFTER))
	private void iris$afterRender(BlockEntity blockEntity, float tickDelta, PoseStack matrix,
								  MultiBufferSource bufferSource, CallbackInfo ci) {
		CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
	}

	@Unique
	private static int pauc$getBlockEntityRenderId(Object2IntMap<BlockState> blockStateIds, BlockState state) {
		if (blockStateIds != pauc$lastBlockStateIds) {
			PAUC_BLOCK_ENTITY_STATE_ID_CACHE.clear();
			pauc$lastBlockStateIds = blockStateIds;
		}

		Integer cached = PAUC_BLOCK_ENTITY_STATE_ID_CACHE.get(state);
		if (cached != null) {
			PauCVillagePerformanceDiagnostics.recordBlockEntityIdCache(true);
			return cached;
		}

		PauCVillagePerformanceDiagnostics.recordBlockEntityIdCache(false);
		int computed = blockStateIds.getOrDefault(state, -1);
		PAUC_BLOCK_ENTITY_STATE_ID_CACHE.put(state, computed);
		return computed;
	}
}
