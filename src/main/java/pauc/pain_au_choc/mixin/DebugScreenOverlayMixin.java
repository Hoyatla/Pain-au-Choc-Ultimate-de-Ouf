package pauc.pain_au_choc.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pauc.pain_au_choc.AuthoritativeRuntimeController;
import pauc.pain_au_choc.AdaptiveSimulationDistanceController;
import pauc.pain_au_choc.AdaptiveQualityController;
import pauc.pain_au_choc.DynamicResolutionController;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.IntegratedServerLoadController;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.PauCShaderManager;
import pauc.pain_au_choc.ServerMobCadenceController;
import pauc.pain_au_choc.StructureStreamingController;
import pauc.pain_au_choc.render.PauCWorldRenderer;
import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

import java.util.List;
import java.util.Locale;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {

    /**
     * Append PAUC pipeline info to the left-side F3 debug lines.
     */
    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void pauc$appendDebugInfo(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        if (lines == null) return;

        lines.add("");
        lines.add("[PauC] enabled=" + PauCClient.isEnabled()
                + " quality=" + PauCClient.getQualityLevel()
                + " budget=" + PauCClient.isBudgetActive());

        // Governor mode
        lines.add("[PauC] governor=" + GlobalPerformanceGovernor.getMode().name()
                + " pressure=" + GlobalPerformanceGovernor.getGlobalPressure());
        lines.add("[PauC] governor cooldown=" + GlobalPerformanceGovernor.getModeSwitchCooldownTicks()
                + " transitions=" + GlobalPerformanceGovernor.getModeTransitionCount());

        // Authority
        lines.add("[PauC] authority=" + AuthoritativeRuntimeController.getStatusLabel()
                + " " + AuthoritativeRuntimeController.getDomainSummary());
        lines.add("[PauC] drs=" + (PauCClient.isDynamicResolutionActive() ? "on" : "off")
                + " reason=" + PauCClient.getDynamicResolutionRuntimeReason()
                + " scale=" + String.format(Locale.ROOT, "%.2f", DynamicResolutionController.getCurrentScale())
                + " min=" + String.format(Locale.ROOT, "%.2f", PauCClient.getDynamicResolutionMinScale())
                + " path=" + DynamicResolutionController.getUpscalePathLabel()
                + " fallback=" + DynamicResolutionController.getNativeFallbackFramesRemaining()
                + " fail=" + DynamicResolutionController.getConsecutiveCopyFailures());
        lines.add("[PauC] autoQuality=" + (PauCClient.isAdaptiveQualityEnabled() ? "on" : "off")
                + " target=" + PauCClient.getAdaptiveQualityTargetLevel()
                + " score=" + AdaptiveQualityController.getLastPressureScore()
                + " cooldown=" + AdaptiveQualityController.getCooldownTicks()
                + " adjustments=" + AdaptiveQualityController.getAdjustmentCount()
                + " reason=" + AdaptiveQualityController.getLastAdjustmentReason());
        lines.add("[PauC] server=" + IntegratedServerLoadController.getStatusLine());
        lines.add("[PauC] renderDist=" + Minecraft.getInstance().options.renderDistance().get());
        lines.add("[PauC] " + AdaptiveSimulationDistanceController.getStatusLine());
        lines.add("[PauC] " + ServerMobCadenceController.getStatusLine());
        lines.add("[PauC] " + StructureStreamingController.getStatusLine());

        // Chunk renderer
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null && renderer.getSectionManager() != null) {
            lines.add("[PauC] chunks: visible=" + renderer.getSectionManager().getVisibleChunkCount()
                    + " total=" + renderer.getSectionManager().getTotalSections());
            lines.add("[PauC] rings: full=" + renderer.getSectionManager().getLastFullDetailVisibleSections()
                    + " stream=" + renderer.getSectionManager().getLastStreamingVisibleSections()
                    + " deferred=" + renderer.getSectionManager().getLastDeferredVisibleSections()
                    + " culled=" + renderer.getSectionManager().getLastBudgetCulledVisibleSections());
            lines.add("[PauC] block entities: visible_culled=" + renderer.getSectionManager().getLastVisibleCulledBlockEntityCount()
                    + " global=" + renderer.getSectionManager().getLastGlobalBlockEntityCount());
            var uploadManager = renderer.getSectionManager().getUploadManager();
            lines.add("[PauC] upload: backlog=" + uploadManager.getPendingUploadCount()
                    + " sections=" + uploadManager.getLastUploadPassSections()
                    + "/" + uploadManager.getLastUploadSectionBudget()
                    + " mb=" + String.format(Locale.ROOT, "%.2f/%.2f",
                    uploadManager.getLastUploadPassBytes() / (1024.0D * 1024.0D),
                    uploadManager.getLastUploadByteBudget() / (1024.0D * 1024.0D)));
        } else {
            lines.add("[PauC] chunks/rings unavailable: vanilla fallback active (-Dpauc.experimentalChunkPipeline=true to re-enable)");
        }

        // Post-process shaders
        lines.add("[PauC] shader=" + PauCShaderManager.getActiveShaderLabel()
                + " ext=" + PauCShaderManager.getExternalShaderCount()
                + " packs=" + PauCShaderManager.getExternalShaderPackCount());

        // Deferred pipeline
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            lines.add("[PauC] deferred=" + PauCDeferredShaderController.getSelectedPack()
                    + " mode=" + PauCDeferredShaderController.getCompatibilityLabel()
                    + " (" + pipeline.getDebugString() + ")");
        } else {
            lines.add("[PauC] deferred=OFF"
                    + " mode=" + PauCDeferredShaderController.getCompatibilityLabel()
                    + " (" + PauCDeferredShaderController.getPackCount() + " packs available)");
        }
    }
}
