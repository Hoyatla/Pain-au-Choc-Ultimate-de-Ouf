package net.irisshaders.iris.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft_PipelineManagement {
	@Unique
	private boolean iris$pipelineResetPending;
	@Unique
	@Nullable
	private NamespacedId iris$pendingPreviousDimension;
	@Unique
	@Nullable
	private NamespacedId iris$pendingTargetDimension;

	/**
	 * Should run before the Minecraft.level field is updated after disconnecting from a server or leaving a singleplayer world
	 */
	@Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
	public void iris$trackLastDimensionOnLeave(Screen arg, CallbackInfo ci) {
		Iris.lastDimension = Iris.getCurrentDimension();
		iris$pipelineResetPending = true;
		iris$pendingPreviousDimension = Iris.lastDimension;
		iris$pendingTargetDimension = null;
	}

	/**
	 * Should run before the Minecraft.level field is updated after receiving a login or respawn packet
	 * NB: Not on leave, another inject is used for that
	 */
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void iris$trackLastDimensionOnLevelChange(ClientLevel clientLevel, CallbackInfo ci) {
		Iris.lastDimension = Iris.getCurrentDimension();
		iris$pipelineResetPending = true;
		iris$pendingPreviousDimension = Iris.lastDimension;
		iris$pendingTargetDimension = clientLevel != null
			? new NamespacedId(clientLevel.dimension().location().getNamespace(), clientLevel.dimension().location().getPath())
			: null;
	}

	/**
	 * Injects before LevelRenderer receives the new level, or is notified of the level unload.
	 * <p>
	 * We destroy any pipelines here to guard against potential memory leaks related to pipelines for
	 * other dimensions never being unloaded.
	 * <p>
	 * This injection point is needed so that we can reload the Iris shader pipeline before Sodium starts trying
	 * to reload its world renderer. Otherwise, there will be inconsistent state since Sodium might initialize and
	 * use the non-extended vertex format (since we do it based on whether the pipeline is available,
	 * then Iris will switch on its pipeline, then code will assume that the extended vertex format
	 * is used everywhere.
	 * <p>
	 * See: <a href="https://github.com/IrisShaders/Iris/issues/1330">Issue 1330</a>
	 */
	@Inject(method = "updateLevelInEngines", at = @At("HEAD"))
	private void iris$resetPipeline(@Nullable ClientLevel level, CallbackInfo ci) {
		if (!iris$pipelineResetPending) {
			return;
		}

		NamespacedId previousDimension = iris$pendingPreviousDimension;
		NamespacedId targetDimension = iris$pendingTargetDimension != null
			? iris$pendingTargetDimension
			: level != null
				? new NamespacedId(level.dimension().location().getNamespace(), level.dimension().location().getPath())
				: null;

		if (targetDimension == null) {
			if (Iris.getPipelineManager().hasActivePipelines()) {
				Iris.logger.info("Destroying pipeline on client level unload: {}", previousDimension);
				Iris.getPipelineManager().destroyPipelineForClientLogout();
			}
		} else {
			String transitionDescription = previousDimension != null && previousDimension.equals(targetDimension)
				? "client level swap within " + targetDimension
				: String.valueOf(previousDimension) + " => " + targetDimension;
			Iris.logger.info("Reloading pipeline on level update: {}", transitionDescription);
			Iris.getPipelineManager().destroyPipeline();

			// NB: We need create the pipeline immediately, so that it is ready by the time that Sodium starts trying to
			// initialize its world renderer.
			Iris.getPipelineManager().preparePipeline(targetDimension);
		}

		iris$pipelineResetPending = false;
		iris$pendingPreviousDimension = targetDimension;
		iris$pendingTargetDimension = null;
	}
}
