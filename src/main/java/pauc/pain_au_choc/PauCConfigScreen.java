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

import java.util.Locale;

public final class PauCConfigScreen extends Screen {
    private static final int CONTROL_WIDTH = 220;
    private static final int CONTROL_HEIGHT = 20;
    private static final int CONTROL_GAP = 24;
    private static final int INLINE_GAP = 4;
    private static final int TAB_WIDTH = 74;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 4;
    private static final int TAB_ROW_Y = 112;
    private static final int CONTENT_TOP = 138;

    private final Screen parent;
    private ConfigTab selectedTab = ConfigTab.CORE;

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
    private Button deferredRescanButton;
    private Button authoritativeRuntimeButton;
    private Button dynamicResolutionButton;
    private Button adaptiveSimulationDistanceButton;
    private Button reloadAllPipelinesButton;
    private QualitySlider qualitySlider;
    private CpuInvolvementSlider cpuInvolvementSlider;
    private SharpenStrengthSlider sharpenStrengthSlider;
    private DynamicResolutionMinScaleSlider dynamicResolutionMinScaleSlider;

    public PauCConfigScreen(Screen parent) {
        super(Component.translatable("screen.pauc.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuildUi();
    }

    private void rebuildUi() {
        clearWidgets();
        resetWidgetReferences();
        buildTabButtons();
        buildTabContent();
        addDoneButton();
        refreshButtonLabels();
    }

    private void resetWidgetReferences() {
        this.toggleButton = null;
        this.presetButton = null;
        this.presetApplyButton = null;
        this.recoveryButton = null;
        this.adaptiveQualityButton = null;
        this.frameTimeStabilizerButton = null;
        this.gpuBottleneckButton = null;
        this.advancedSharpeningButton = null;
        this.shaderModeButton = null;
        this.shaderReloadButton = null;
        this.shaderFolderButton = null;
        this.deferredPackButton = null;
        this.deferredCompatButton = null;
        this.deferredReloadButton = null;
        this.deferredFolderButton = null;
        this.deferredRescanButton = null;
        this.authoritativeRuntimeButton = null;
        this.dynamicResolutionButton = null;
        this.adaptiveSimulationDistanceButton = null;
        this.reloadAllPipelinesButton = null;
        this.qualitySlider = null;
        this.cpuInvolvementSlider = null;
        this.sharpenStrengthSlider = null;
        this.dynamicResolutionMinScaleSlider = null;
    }

    private void buildTabButtons() {
        ConfigTab[] tabs = ConfigTab.values();
        int totalWidth = tabs.length * TAB_WIDTH + (tabs.length - 1) * TAB_GAP;
        int left = this.width / 2 - totalWidth / 2;

        for (ConfigTab tab : tabs) {
            Button button = this.addRenderableWidget(
                    Button.builder(buildTabMessage(tab), value -> {
                        this.selectedTab = tab;
                        rebuildUi();
                    }).bounds(left, TAB_ROW_Y, TAB_WIDTH, TAB_HEIGHT).build()
            );
            button.active = this.selectedTab != tab;
            left += TAB_WIDTH + TAB_GAP;
        }
    }

    private Component buildTabMessage(ConfigTab tab) {
        return this.selectedTab == tab
                ? Component.literal("[" + tab.label + "]")
                : Component.literal(tab.label);
    }

    private void buildTabContent() {
        int left = this.width / 2 - CONTROL_WIDTH / 2;
        int top = CONTENT_TOP;
        switch (this.selectedTab) {
            case CORE -> buildCoreTab(left, top);
            case RUNTIME -> buildRuntimeTab(left, top);
            case UPSCALE -> buildUpscaleTab(left, top);
            case DEFERRED -> buildDeferredTab(left, top);
            case INTEGRATION -> buildIntegrationTab(left, top);
        }
    }

    private void buildCoreTab(int left, int top) {
        this.toggleButton = this.addRenderableWidget(
                Button.builder(buildToggleMessage(), button -> {
                    PauCClient.setEnabled(!PauCClient.isEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.qualitySlider = this.addRenderableWidget(new QualitySlider(left, top, CONTROL_WIDTH, CONTROL_HEIGHT));

        top += CONTROL_GAP;
        this.adaptiveQualityButton = this.addRenderableWidget(
                Button.builder(buildAdaptiveQualityMessage(), button -> {
                    PauCClient.setAdaptiveQualityEnabled(!PauCClient.isAdaptiveQualityEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.cpuInvolvementSlider = this.addRenderableWidget(new CpuInvolvementSlider(left, top, CONTROL_WIDTH, CONTROL_HEIGHT));

        top += CONTROL_GAP;
        this.presetButton = this.addRenderableWidget(
                Button.builder(buildPresetMessage(), button -> {
                    PauCClient.cycleUserPreset();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        int halfWidth = (CONTROL_WIDTH - INLINE_GAP) / 2;
        this.presetApplyButton = this.addRenderableWidget(
                Button.builder(Component.literal("Apply Preset"), button -> {
                    PauCClient.applySelectedPreset();
                    refreshButtonLabels();
                }).bounds(left, top, halfWidth, CONTROL_HEIGHT).build()
        );
        this.recoveryButton = this.addRenderableWidget(
                Button.builder(Component.literal("Recovery"), button -> {
                    PauCClient.activateRecoveryMode();
                    refreshButtonLabels();
                }).bounds(left + halfWidth + INLINE_GAP, top, halfWidth, CONTROL_HEIGHT).build()
        );
    }

    private void buildRuntimeTab(int left, int top) {
        this.authoritativeRuntimeButton = this.addRenderableWidget(
                Button.builder(buildAuthoritativeRuntimeMessage(), button -> {
                    PauCClient.setAuthoritativeRuntimeEnabled(!PauCClient.isAuthoritativeRuntimeEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.dynamicResolutionButton = this.addRenderableWidget(
                Button.builder(buildDynamicResolutionMessage(), button -> {
                    PauCClient.setDynamicResolutionEnabled(!PauCClient.isDynamicResolutionSettingEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.dynamicResolutionMinScaleSlider = this.addRenderableWidget(
                new DynamicResolutionMinScaleSlider(left, top, CONTROL_WIDTH, CONTROL_HEIGHT)
        );

        top += CONTROL_GAP;
        this.adaptiveSimulationDistanceButton = this.addRenderableWidget(
                Button.builder(buildAdaptiveSimulationDistanceMessage(), button -> {
                    PauCClient.setAdaptiveSimulationDistanceEnabled(!PauCClient.isAdaptiveSimulationDistanceSettingEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.frameTimeStabilizerButton = this.addRenderableWidget(
                Button.builder(buildFrameTimeStabilizerMessage(), button -> {
                    PauCClient.setFrameTimeStabilizerEnabled(!PauCClient.isFrameTimeStabilizerEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.gpuBottleneckButton = this.addRenderableWidget(
                Button.builder(buildGpuBottleneckMessage(), button -> {
                    PauCClient.setGpuBottleneckDetectorEnabled(!PauCClient.isGpuBottleneckDetectorEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );
    }

    private void buildUpscaleTab(int left, int top) {
        this.shaderModeButton = this.addRenderableWidget(
                Button.builder(buildShaderModeMessage(), button -> {
                    PauCShaderManager.cycleShaderMode();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        int halfWidth = (CONTROL_WIDTH - INLINE_GAP) / 2;
        this.shaderReloadButton = this.addRenderableWidget(
                Button.builder(Component.literal("Reload Shaders"), button -> {
                    PauCShaderManager.reloadExternalShaders();
                    refreshButtonLabels();
                }).bounds(left, top, halfWidth, CONTROL_HEIGHT).build()
        );
        this.shaderFolderButton = this.addRenderableWidget(
                Button.builder(Component.literal("Open Folder"), button -> PauCShaderManager.openShaderFolder())
                        .bounds(left + halfWidth + INLINE_GAP, top, halfWidth, CONTROL_HEIGHT)
                        .build()
        );

        top += CONTROL_GAP;
        this.advancedSharpeningButton = this.addRenderableWidget(
                Button.builder(buildAdvancedSharpeningMessage(), button -> {
                    PauCClient.setAdvancedSharpeningEnabled(!PauCClient.isAdvancedSharpeningEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.sharpenStrengthSlider = this.addRenderableWidget(new SharpenStrengthSlider(left, top, CONTROL_WIDTH, CONTROL_HEIGHT));
    }

    private void buildDeferredTab(int left, int top) {
        this.deferredPackButton = this.addRenderableWidget(
                Button.builder(buildDeferredPackMessage(), button -> {
                    PauCDeferredShaderController.cycleShaderPack();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.deferredCompatButton = this.addRenderableWidget(
                Button.builder(buildDeferredCompatMessage(), button -> {
                    PauCDeferredShaderController.cycleCompatibilityMode();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        int halfWidth = (CONTROL_WIDTH - INLINE_GAP) / 2;
        this.deferredReloadButton = this.addRenderableWidget(
                Button.builder(Component.literal("Reload Pack"), button -> {
                    PauCDeferredShaderController.reloadCurrentPack();
                    refreshButtonLabels();
                }).bounds(left, top, halfWidth, CONTROL_HEIGHT).build()
        );
        this.deferredFolderButton = this.addRenderableWidget(
                Button.builder(Component.literal("Packs Folder"), button -> PauCDeferredShaderController.openShaderPackFolder())
                        .bounds(left + halfWidth + INLINE_GAP, top, halfWidth, CONTROL_HEIGHT)
                        .build()
        );

        top += CONTROL_GAP;
        this.deferredRescanButton = this.addRenderableWidget(
                Button.builder(Component.literal("Rescan Packs"), button -> {
                    PauCDeferredShaderController.refreshAvailablePacks();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );
    }

    private void buildIntegrationTab(int left, int top) {
        this.authoritativeRuntimeButton = this.addRenderableWidget(
                Button.builder(buildAuthoritativeRuntimeMessage(), button -> {
                    PauCClient.setAuthoritativeRuntimeEnabled(!PauCClient.isAuthoritativeRuntimeEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.dynamicResolutionButton = this.addRenderableWidget(
                Button.builder(buildDynamicResolutionMessage(), button -> {
                    PauCClient.setDynamicResolutionEnabled(!PauCClient.isDynamicResolutionSettingEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.advancedSharpeningButton = this.addRenderableWidget(
                Button.builder(buildAdvancedSharpeningMessage(), button -> {
                    PauCClient.setAdvancedSharpeningEnabled(!PauCClient.isAdvancedSharpeningEnabled());
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.deferredCompatButton = this.addRenderableWidget(
                Button.builder(buildDeferredCompatMessage(), button -> {
                    PauCDeferredShaderController.cycleCompatibilityMode();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        this.reloadAllPipelinesButton = this.addRenderableWidget(
                Button.builder(Component.literal("Reload All Pipelines"), button -> {
                    PauCShaderManager.reloadExternalShaders();
                    PauCDeferredShaderController.reloadCurrentPack();
                    refreshButtonLabels();
                }).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );

        top += CONTROL_GAP;
        int halfWidth = (CONTROL_WIDTH - INLINE_GAP) / 2;
        this.shaderFolderButton = this.addRenderableWidget(
                Button.builder(Component.literal("Upscale Folder"), button -> PauCShaderManager.openShaderFolder())
                        .bounds(left, top, halfWidth, CONTROL_HEIGHT)
                        .build()
        );
        this.deferredFolderButton = this.addRenderableWidget(
                Button.builder(Component.literal("Shaderpacks"), button -> PauCDeferredShaderController.openShaderPackFolder())
                        .bounds(left + halfWidth + INLINE_GAP, top, halfWidth, CONTROL_HEIGHT)
                        .build()
        );
    }

    private void addDoneButton() {
        int left = this.width / 2 - CONTROL_WIDTH / 2;
        int top = this.height - 28;
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).bounds(left, top, CONTROL_WIDTH, CONTROL_HEIGHT).build()
        );
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.translatable("text.pauc.quality_hint"), this.width / 2, 26, 0xA0A0A0);
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Tab: " + this.selectedTab.label + " | Preset: " + PauCClient.getSelectedPreset().getDisplayLabel()),
                this.width / 2,
                38,
                0xB8C6D4
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Authority: " + AuthoritativeRuntimeController.getStatusLabel()),
                this.width / 2,
                50,
                resolveAuthorityColor()
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(AuthoritativeRuntimeController.getDomainSummary()),
                this.width / 2,
                62,
                0x909090
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(buildRuntimeSummaryLine()),
                this.width / 2,
                74,
                0x88BBD6
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(buildDeferredSummaryLine()),
                this.width / 2,
                86,
                resolveDeferredLineColor()
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal(buildIntegrationSummaryLine()),
                this.width / 2,
                98,
                resolveIntegrationColor()
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
        if (this.toggleButton != null) {
            this.toggleButton.setMessage(buildToggleMessage());
        }
        if (this.presetButton != null) {
            this.presetButton.setMessage(buildPresetMessage());
        }
        if (this.adaptiveQualityButton != null) {
            this.adaptiveQualityButton.setMessage(buildAdaptiveQualityMessage());
        }
        if (this.frameTimeStabilizerButton != null) {
            this.frameTimeStabilizerButton.setMessage(buildFrameTimeStabilizerMessage());
        }
        if (this.gpuBottleneckButton != null) {
            this.gpuBottleneckButton.setMessage(buildGpuBottleneckMessage());
        }
        if (this.advancedSharpeningButton != null) {
            this.advancedSharpeningButton.setMessage(buildAdvancedSharpeningMessage());
            this.advancedSharpeningButton.active = !CompatibilityGuards.shouldDisableAdvancedSharpening();
        }
        if (this.shaderModeButton != null) {
            this.shaderModeButton.setMessage(buildShaderModeMessage());
        }
        if (this.deferredPackButton != null) {
            this.deferredPackButton.setMessage(buildDeferredPackMessage());
        }
        if (this.deferredCompatButton != null) {
            this.deferredCompatButton.setMessage(buildDeferredCompatMessage());
        }
        if (this.authoritativeRuntimeButton != null) {
            this.authoritativeRuntimeButton.setMessage(buildAuthoritativeRuntimeMessage());
        }
        if (this.dynamicResolutionButton != null) {
            this.dynamicResolutionButton.setMessage(buildDynamicResolutionMessage());
        }
        if (this.adaptiveSimulationDistanceButton != null) {
            this.adaptiveSimulationDistanceButton.setMessage(buildAdaptiveSimulationDistanceMessage());
        }
        if (this.qualitySlider != null) {
            this.qualitySlider.refresh();
        }
        if (this.cpuInvolvementSlider != null) {
            this.cpuInvolvementSlider.refresh();
        }
        if (this.dynamicResolutionMinScaleSlider != null) {
            this.dynamicResolutionMinScaleSlider.refresh();
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

    private Component buildDynamicResolutionMessage() {
        return Component.translatable(
                "option.pauc.dynamic_resolution",
                PauCClient.isDynamicResolutionSettingEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildAdaptiveSimulationDistanceMessage() {
        return Component.translatable(
                "option.pauc.adaptive_simulation_distance",
                PauCClient.isAdaptiveSimulationDistanceSettingEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildAuthoritativeRuntimeMessage() {
        return Component.translatable(
                "option.pauc.authoritative_runtime",
                PauCClient.isAuthoritativeRuntimeEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
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

    private int resolveIntegrationColor() {
        if (CompatibilityGuards.shouldDisableDynamicResolution()
                || CompatibilityGuards.shouldDisableAdvancedSharpening()
                || AuthoritativeRuntimeController.getStatus() != AuthoritativeRuntimeStatus.SOVEREIGN) {
            return 0xE4C86A;
        }
        return 0x79D295;
    }

    private static String buildRuntimeSummaryLine() {
        return "Runtime: quality=" + PauCClient.getQualityLabel()
                + " drs=" + (PauCClient.isDynamicResolutionSettingEnabled() ? "on" : "off")
                + " min=" + formatScale(PauCClient.getConfiguredDynamicResolutionMinScale())
                + " live=" + formatScale(DynamicResolutionController.getCurrentScale())
                + " simDist=" + (PauCClient.isAdaptiveSimulationDistanceSettingEnabled() ? "on" : "off")
                + " authority=" + (PauCClient.isAuthoritativeRuntimeEnabled() ? "on" : "off");
    }

    private static String buildDeferredSummaryLine() {
        return "Deferred: " + PauCDeferredShaderController.getShortLabel()
                + " [" + PauCDeferredShaderController.getCompatibilityLabel() + "]"
                + " packs=" + PauCDeferredShaderController.getPackCount()
                + " warn=" + getDeferredWarningCount();
    }

    private static String buildIntegrationSummaryLine() {
        return "Integration: embeddium=" + formatPresence(CompatibilityGuards.isEmbeddiumLoaded())
                + " oculus=" + formatPresence(CompatibilityGuards.isOculusLoaded())
                + " replay=" + formatPresence(CompatibilityGuards.isReplayStackLoaded())
                + " drsYield=" + formatPresence(CompatibilityGuards.shouldDisableDynamicResolution());
    }

    private static String formatPresence(boolean value) {
        return value ? "yes" : "no";
    }

    private static String formatScale(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int getDeferredWarningCount() {
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        if (pipeline == null || pipeline.getShaderPack() == null) {
            return 0;
        }
        return pipeline.getShaderPack().warnings.size();
    }

    private enum ConfigTab {
        CORE("Core"),
        RUNTIME("Runtime"),
        UPSCALE("Upscale"),
        DEFERRED("Deferred"),
        INTEGRATION("Integration");

        private final String label;

        ConfigTab(String label) {
            this.label = label;
        }
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

    private static final class DynamicResolutionMinScaleSlider extends AbstractSliderButton {
        private static final double MIN_SCALE = 0.35D;
        private static final double MAX_SCALE = 1.00D;

        private DynamicResolutionMinScaleSlider(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY, toSliderValue(PauCClient.getConfiguredDynamicResolutionMinScale()));
            this.refresh();
        }

        @Override
        protected void updateMessage() {
            int percent = (int) Math.round(PauCClient.getConfiguredDynamicResolutionMinScale() * 100.0D);
            this.setMessage(Component.translatable("option.pauc.dynamic_resolution_min_scale", Integer.toString(percent)));
        }

        @Override
        protected void applyValue() {
            PauCClient.setDynamicResolutionMinScale(fromSliderValue(this.value));
            this.updateMessage();
        }

        private void refresh() {
            this.value = toSliderValue(PauCClient.getConfiguredDynamicResolutionMinScale());
            this.active = PauCClient.isDynamicResolutionSettingEnabled();
            this.updateMessage();
        }

        private static double toSliderValue(double scale) {
            return (scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
        }

        private static double fromSliderValue(double sliderValue) {
            return MIN_SCALE + sliderValue * (MAX_SCALE - MIN_SCALE);
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
