package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class PauCConfigScreen extends Screen {
    private final Screen parent;
    private Button toggleButton;
    private Button frameTimeStabilizerButton;
    private Button gpuBottleneckButton;
    private Button advancedSharpeningButton;
    private Button shaderModeButton;
    private Button shaderReloadButton;
    private Button shaderFolderButton;
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
        int top = Math.max(118, this.height / 5 + 10);

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
                Component.literal(TerrainProxyController.getStatusLine()),
                this.width / 2,
                82,
                0x7A9E7E
        );
        guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Shader: " + PauCShaderManager.getActiveShaderLabel() + " | ext=" + PauCShaderManager.getExternalShaderCount() + " packs=" + PauCShaderManager.getExternalShaderPackCount()),
                this.width / 2,
                94,
                0xD5D5D5
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
        this.frameTimeStabilizerButton.setMessage(buildFrameTimeStabilizerMessage());
        this.gpuBottleneckButton.setMessage(buildGpuBottleneckMessage());
        this.advancedSharpeningButton.setMessage(buildAdvancedSharpeningMessage());
        if (this.shaderModeButton != null) {
            this.shaderModeButton.setMessage(buildShaderModeMessage());
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

    private int resolveAuthorityColor() {
        return switch (AuthoritativeRuntimeController.getStatus()) {
            case SOVEREIGN -> 0x63D471;
            case CONTESTED -> 0xE4C86A;
            case DEGRADED -> 0xE06C75;
        };
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


