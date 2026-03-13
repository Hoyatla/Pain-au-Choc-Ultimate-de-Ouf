package pauc.pain_au_choc.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pauc.pain_au_choc.AuthoritativeRuntimeController;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.PauCClient;
import pauc.pain_au_choc.PauCShaderManager;
import pauc.pain_au_choc.render.PauCWorldRenderer;
import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

import java.util.List;

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

        // Authority
        lines.add("[PauC] authority=" + AuthoritativeRuntimeController.getStatusLabel()
                + " " + AuthoritativeRuntimeController.getDomainSummary());

        // Chunk renderer
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null && renderer.getSectionManager() != null) {
            lines.add("[PauC] chunks: visible=" + renderer.getSectionManager().getVisibleChunkCount()
                    + " total=" + renderer.getSectionManager().getTotalSections());
        }

        // Post-process shaders
        lines.add("[PauC] shader=" + PauCShaderManager.getActiveShaderLabel()
                + " ext=" + PauCShaderManager.getExternalShaderCount()
                + " packs=" + PauCShaderManager.getExternalShaderPackCount());

        // Deferred pipeline
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline != null && pipeline.isInitialized()) {
            lines.add("[PauC] deferred=" + PauCDeferredShaderController.getSelectedPack()
                    + " (" + pipeline.getDebugString() + ")");
        } else {
            lines.add("[PauC] deferred=OFF"
                    + " (" + PauCDeferredShaderController.getPackCount() + " packs available)");
        }
    }
}
