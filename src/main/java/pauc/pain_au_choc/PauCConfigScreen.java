package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

public final class PauCConfigScreen extends Screen {
    private final Screen parent;
    private Button toggleButton;
    private Button presetButton;
    private Button presetApplyButton;
    private Button recoveryButton;
    private Button adaptiveQualityButton;
    private Button frameTimeStabilizerButton;
    private Button gpuBottleneckButton;
    private Button advancedSharpeningButton;
    private Button shaderModeButton;
    private Button shaderReloadButton;
    private Button shaderFolderButton;
    private Button deferredPackButton;
    private Button deferredCompatButton;
    private Button deferredReloadButton;
    private Button deferredFolderButton;
    private QualitySlider qualitySlider;
    private CpuInvolvementSlider cpuInvolvementSlider;
    private SharpenStrengthSlider sharpenStrengthSlider;

    public PauCConfigScreen(Screen parent) {
        super(Component.translatable("screen.pauc.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 100;
        int top = Math.max(150, this.height / 5 + 10);

        this.toggleButton = this.addRenderableWidget(
                Button.builder(buildToggleMessage(), button -> {
                    PauCClient.setEnabled(!PauCClient.isEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.qualitySlider = this.addRenderableWidget(new QualitySlider(left, top, 200, 20));

        top += 24;
        this.cpuInvolvementSlider = this.addRenderableWidget(new CpuInvolvementSlider(left, top, 200, 20));

        top += 24;
        this.adaptiveQualityButton = this.addRenderableWidget(
                Button.builder(buildAdaptiveQualityMessage(), button -> {
                    PauCClient.setAdaptiveQualityEnabled(!PauCClient.isAdaptiveQualityEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.presetButton = this.addRenderableWidget(
                Button.builder(buildPresetMessage(), button -> {
                    PauCClient.cycleUserPreset();
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.presetApplyButton = this.addRenderableWidget(
                Button.builder(Component.literal("Apply Preset"), button -> {
                    PauCClient.applySelectedPreset();
                    refreshButtonLabels();
                }).bounds(left, top, 98, 20).build()
        );
        this.recoveryButton = this.addRenderableWidget(
                Button.builder(Component.literal("Recovery"), button -> {
                    PauCClient.activateRecoveryMode();
                    refreshButtonLabels();
                }).bounds(left + 102, top, 98, 20).build()
        );

        top += 24;
        this.frameTimeStabilizerButton = this.addRenderableWidget(
                Button.builder(buildFrameTimeStabilizerMessage(), button -> {
                    PauCClient.setFrameTimeStabilizerEnabled(!PauCClient.isFrameTimeStabilizerEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.gpuBottleneckButton = this.addRenderableWidget(
                Button.builder(buildGpuBottleneckMessage(), button -> {
                    PauCClient.setGpuBottleneckDetectorEnabled(!PauCClient.isGpuBottleneckDetectorEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.advancedSharpeningButton = this.addRenderableWidget(
                Button.builder(buildAdvancedSharpeningMessage(), button -> {
                    PauCClient.setAdvancedSharpeningEnabled(!PauCClient.isAdvancedSharpeningEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 32;
        this.sharpenStrengthSlider = this.addRenderableWidget(new SharpenStrengthSlider(left, top, 200, 20));

        top += 28;
        this.shaderModeButton = this.addRenderableWidget(
                Button.builder(buildShaderModeMessage(), button -> {
                    PauCShaderManager.cycleShaderMode();
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.shaderReloadButton = this.addRenderableWidget(
                Button.builder(Component.literal("Reload Shaders"), button -> {
                    PauCShaderManager.reloadExternalShaders();
                    refreshButtonLabels();
                }).bounds(left, top, 98, 20).build()
        );
        this.shaderFolderButton = this.addRenderableWidget(
                Button.builder(Component.literal("Open Folder"), button -> PauCShaderManager.openShaderFolder()).bounds(left + 102, top, 98, 20).build()
        );

        // ---- Deferred Shader Pipeline (OptiFine packs) ----
        top += 28;
        this.deferredPackButton = this.addRenderableWidget(
                Button.builder(buildDeferredPackMessage(), button -> {
                    PauCDeferredShaderController.cycleShaderPack();
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.deferredCompatButton = this.addRenderableWidget(
                Button.builder(buildDeferredCompatMessage(), button -> {
                    PauCDeferredShaderController.cycleCompatibilityMode();
                    refreshButtonLabels();
                }).bounds(left, top, 200, 20).build()
        );

        top += 24;
        this.deferredReloadButton = this.addRenderableWidget(
                Button.builder(Component.literal("Reload Pack"), button -> {
                    PauCDeferredShaderController.reloadCurrentPack();
                    refreshButtonLabels();
                }).bounds(left, top, 98, 20).build()
        );
        this.deferredFolderButton = this.addRenderableWidget(
                Button.builder(Component.literal("Packs Folder"), button -> PauCDeferredShaderController.openShaderPackFolder()).bounds(left + 102, top, 98, 20).build()
        );

        top += 32;
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).bounds(left, top, 200, 20).build()
        );

        this.advancedSharpeningButton.active = !CompatibilityGuards.shouldDisableAdvancedSharpening();
        if (this.sharpenStrengthSlider != null) {
            this.sharpenStrengthSlider.refresh();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("text.pauc.quality_hint"),
                this.width / 2,
                34,
                0xA0A0A0
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Authority: " + AuthoritativeRuntimeController.getStatusLabel()),
                this.width / 2,
                46,
                resolveAuthorityColor()
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(AuthoritativeRuntimeController.getDomainSummary()),
                this.width / 2,
                58,
                0x909090
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Managed radius: " + ManagedChunkRadiusController.getRadiusSummary()),
                this.width / 2,
                70,
                0x88BBD6
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(StructureStreamingController.getStatusLine()),
                this.width / 2,
                82,
                0x7EA4B8
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(TerrainProxyController.getStatusLine()),
                this.width / 2,
                94,
                0x7A9E7E
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Shader: " + PauCShaderManager.getActiveShaderLabel() + " | ext=" + PauCShaderManager.getExternalShaderCount() + " packs=" + PauCShaderManager.getExternalShaderPackCount()),
                this.width / 2,
                106,
                0xD5D5D5
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Deferred: " + PauCDeferredShaderController.getShortLabel()
                        + " [" + PauCDeferredShaderController.getCompatibilityLabel() + "]"
                        + " | " + PauCDeferredShaderController.getPackCount() + " packs"
                        + " | warn=" + getDeferredWarningCount()),
                this.width / 2,
                118,
                resolveDeferredLineColor()
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(buildDiagnosticLine()),
                this.width / 2,
                130,
                resolveDiagnosticColor()
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(buildActionLine()),
                this.width / 2,
                142,
                resolveActionLineColor()
        );
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        PauCClient.saveConfig();
        Minecraft minecraft = this.minecraft;
        if (minecraft != null) {
            minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refreshButtonLabels() {
        this.toggleButton.setMessage(buildToggleMessage());
        if (this.presetButton != null) {
            this.presetButton.setMessage(buildPresetMessage());
        }
        if (this.adaptiveQualityButton != null) {
            this.adaptiveQualityButton.setMessage(buildAdaptiveQualityMessage());
        }
        this.frameTimeStabilizerButton.setMessage(buildFrameTimeStabilizerMessage());
        this.gpuBottleneckButton.setMessage(buildGpuBottleneckMessage());
        this.advancedSharpeningButton.setMessage(buildAdvancedSharpeningMessage());
        if (this.shaderModeButton != null) {
            this.shaderModeButton.setMessage(buildShaderModeMessage());
        }
        if (this.deferredPackButton != null) {
            this.deferredPackButton.setMessage(buildDeferredPackMessage());
        }
        if (this.deferredCompatButton != null) {
            this.deferredCompatButton.setMessage(buildDeferredCompatMessage());
        }
        this.advancedSharpeningButton.active = !CompatibilityGuards.shouldDisableAdvancedSharpening();
        if (this.qualitySlider != null) {
            this.qualitySlider.refresh();
        }
        if (this.cpuInvolvementSlider != null) {
            this.cpuInvolvementSlider.refresh();
        }
        if (this.sharpenStrengthSlider != null) {
            this.sharpenStrengthSlider.refresh();
        }
        PauCClient.saveConfig();
    }

    private Component buildToggleMessage() {
        return Component.translatable("option.pauc.enabled", PauCClient.isEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    private Component buildFrameTimeStabilizerMessage() {
        return Component.translatable(
                "option.pauc.frame_time_stabilizer",
                PauCClient.isFrameTimeStabilizerEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildAdaptiveQualityMessage() {
        return Component.translatable(
                "option.pauc.adaptive_quality",
                PauCClient.isAdaptiveQualityEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildPresetMessage() {
        return Component.literal("Preset: " + PauCClient.getSelectedPreset().getDisplayLabel());
    }

    private Component buildGpuBottleneckMessage() {
        return Component.translatable(
                "option.pauc.gpu_bottleneck",
                PauCClient.isGpuBottleneckDetectorEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildAdvancedSharpeningMessage() {
        if (CompatibilityGuards.shouldDisableAdvancedSharpening()) {
            return Component.translatable("option.pauc.advanced_sharpening_blocked");
        }

        return Component.translatable(
                "option.pauc.advanced_sharpening",
                PauCClient.isAdvancedSharpeningEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildShaderModeMessage() {
        return Component.literal("Shader: " + PauCShaderManager.getActiveShaderLabel());
    }

    private Component buildDeferredPackMessage() {
        return Component.literal("Deferred Pack: " + PauCDeferredShaderController.getShortLabel());
    }

    private Component buildDeferredCompatMessage() {
        return Component.literal("Deferred Mode: " + PauCDeferredShaderController.getCompatibilityLabel());
    }

    private int resolveAuthorityColor() {
        return switch (AuthoritativeRuntimeController.getStatus()) {
            case SOVEREIGN -> 0x63D471;
            case CONTESTED -> 0xE4C86A;
            case DEGRADED -> 0xE06C75;
        };
    }

    private int resolveDeferredLineColor() {
        if (!PauCDeferredShaderController.isPipelineActive()) {
            return 0x909090;
        }
        return getDeferredWarningCount() > 0 ? 0xE4C86A : 0x82D8F5;
    }

    private static String buildDiagnosticLine() {
        return "Diag: governor=" + GlobalPerformanceGovernor.getMode().name()
                + " pressure=" + GlobalPerformanceGovernor.getGlobalPressure()
                + " latency=" + LatencyController.getPressureLevel()
                + " serverTier=" + IntegratedServerLoadController.getMitigationTier()
                + " simDist=" + AdaptiveSimulationDistanceController.getAppliedSimulationDistance()
                + "/" + AdaptiveSimulationDistanceController.getBaseSimulationDistance()
                + " mobCadence=" + ServerMobCadenceController.getLastSelectorCadence()
                + "/" + ServerMobCadenceController.getLastNavigationCadence()
                + " autoQ=" + (PauCClient.isAdaptiveQualityEnabled() ? "on" : "off")
                + " target=" + PauCClient.getAdaptiveQualityTargetLevel()
                + " score=" + AdaptiveQualityController.getLastPressureScore()
                + " cd=" + AdaptiveQualityController.getCooldownTicks();
    }

    private static String buildActionLine() {
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED
                || IntegratedServerLoadController.isEmergencyMitigationActive()) {
            return "Action: Recovery recommande (profil Safe).";
        }
        if (IntegratedServerLoadController.getMitigationTier() >= 2) {
            return "Action: appliquer Safe ou Competitive 240.";
        }
        if (LatencyController.getPressureLevel() >= 2 || GlobalPerformanceGovernor.getGlobalPressure() >= 2) {
            return "Action: utiliser Competitive 240 puis verifier backlog.";
        }
        if (getDeferredWarningCount() > 0) {
            return "Action: passer Deferred en FAST ou recharger le pack.";
        }
        return "Action: stable (Balanced/Cinematic possibles).";
    }

    private static int resolveDiagnosticColor() {
        int maxPressure = Math.max(
                GlobalPerformanceGovernor.getGlobalPressure(),
                Math.max(LatencyController.getPressureLevel(), IntegratedServerLoadController.getMitigationTier())
        );
        if (maxPressure >= 3) {
            return 0xE06C75;
        }
        if (maxPressure >= 2) {
            return 0xE4C86A;
        }
        return 0x8ED1C3;
    }

    private static int resolveActionLineColor() {
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED
                || IntegratedServerLoadController.isEmergencyMitigationActive()) {
            return 0xE06C75;
        }
        if (IntegratedServerLoadController.getMitigationTier() >= 2
                || LatencyController.getPressureLevel() >= 2
                || GlobalPerformanceGovernor.getGlobalPressure() >= 2) {
            return 0xE4C86A;
        }
        if (getDeferredWarningCount() > 0) {
            return 0xE2B26F;
        }
        return 0x79D295;
    }

    private static int getDeferredWarningCount() {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline == null || pipeline.getShaderPack() == null) {
            return 0;
        }
        return pipeline.getShaderPack().warnings.size();
    }

    private static final class QualitySlider extends AbstractSliderButton {
        private QualitySlider(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY, toSliderValue(PauCClient.getQualityLevel()));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("option.pauc.quality", PauCClient.getQualityLabel()));
        }

        @Override
        protected void applyValue() {
            int qualityLevel = fromSliderValue(this.value);
            PauCClient.setQualityLevel(qualityLevel);
            this.updateMessage();
        }

        private void refresh() {
            this.value = toSliderValue(PauCClient.getQualityLevel());
            this.updateMessage();
        }

        private static double toSliderValue(int qualityLevel) {
            int min = PauCClient.getMinQualityLevel();
            int max = PauCClient.getMaxQualityLevel();
            return (double) (qualityLevel - min) / (double) (max - min);
        }

        private static int fromSliderValue(double sliderValue) {
            int min = PauCClient.getMinQualityLevel();
            int max = PauCClient.getMaxQualityLevel();
            return min + (int) Math.round(sliderValue * (max - min));
        }
    }

    private static final class CpuInvolvementSlider extends AbstractSliderButton {
        private CpuInvolvementSlider(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY, toSliderValue(PauCClient.getCpuInvolvementLevel()));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("option.pauc.cpu_involvement", Integer.toString(PauCClient.getCpuInvolvementLevel())));
        }

        @Override
        protected void applyValue() {
            int level = fromSliderValue(this.value);
            PauCClient.setCpuInvolvementLevel(level);
            this.updateMessage();
        }

        private void refresh() {
            this.value = toSliderValue(PauCClient.getCpuInvolvementLevel());
            this.updateMessage();
        }

        private static double toSliderValue(int level) {
            int min = PauCClient.getMinCpuInvolvementLevel();
            int max = PauCClient.getMaxCpuInvolvementLevel();
            return (double) (level - min) / (double) (max - min);
        }

        private static int fromSliderValue(double sliderValue) {
            int min = PauCClient.getMinCpuInvolvementLevel();
            int max = PauCClient.getMaxCpuInvolvementLevel();
            return min + (int) Math.round(sliderValue * (max - min));
        }
    }

    private static final class SharpenStrengthSlider extends AbstractSliderButton {
        private SharpenStrengthSlider(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY, PauCClient.getAdvancedSharpeningStrength());
            this.refresh();
        }

        @Override
        protected void updateMessage() {
            int percent = (int) Math.round(PauCClient.getAdvancedSharpeningStrength() * 100.0D);
            this.setMessage(Component.translatable("option.pauc.sharpen_strength", Integer.toString(percent)));
        }

        @Override
        protected void applyValue() {
            PauCClient.setAdvancedSharpeningStrength(this.value);
            this.updateMessage();
        }

        private void refresh() {
            this.value = PauCClient.getAdvancedSharpeningStrength();
            this.active = PauCClient.isAdvancedSharpeningEnabled() && !CompatibilityGuards.shouldDisableAdvancedSharpening();
            this.updateMessage();
        }
    }

}
