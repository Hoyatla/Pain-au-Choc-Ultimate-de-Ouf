package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;

import java.util.Locale;

public final class PauCConfigScreen extends Screen {
    private static final int CONTROL_WIDTH = 220;
    private static final int CONTROL_HEIGHT = 20;
    private static final int DEFAULT_CONTROL_GAP = 24;
    private static final int MIN_CONTROL_STEP = CONTROL_HEIGHT + 2;
    private static final int INLINE_GAP = 4;
    private static final int TAB_WIDTH = 74;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 4;

    private final Screen parent;
    private final int[] tabPageByIndex = new int[ConfigTab.values().length];
    private ConfigTab selectedTab = ConfigTab.CORE;
    private int controlWidth = CONTROL_WIDTH;
    private int controlGap = DEFAULT_CONTROL_GAP;
    private int tabWidth = TAB_WIDTH;
    private int tabRowY = 112;
    private int contentTop = 138;
    private int doneButtonY;
    private int rowsPerPage = 1;
    private int pageStartRow;
    private int pageEndRowExclusive;
    private int totalPages = 1;
    private boolean compactLayout;

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
    private Button previousPageButton;
    private Button nextPageButton;
    private Button dynamicResolutionRuntimeInfoButton;
    private Button sharpeningRuntimeInfoButton;
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
        computeLayoutMetrics();
        clearWidgets();
        resetWidgetReferences();
        buildTabButtons();
        buildTabContent();
        addDoneButton();
        refreshButtonLabels();
    }

    private void computeLayoutMetrics() {
        this.compactLayout = this.height <= 320;
        this.controlWidth = Math.max(140, Math.min(CONTROL_WIDTH, this.width - 24));

        int tabs = ConfigTab.values().length;
        int availableTabsWidth = Math.max(180, this.width - 24 - (tabs - 1) * TAB_GAP);
        this.tabWidth = Math.max(48, Math.min(TAB_WIDTH, availableTabsWidth / tabs));

        this.tabRowY = this.compactLayout ? 58 : 112;
        this.contentTop = this.compactLayout ? 82 : 138;
        this.doneButtonY = this.height - (this.compactLayout ? 24 : 28);

        int maxRows = 6;
        int contentBottom = this.doneButtonY - 8;
        int availableHeight = Math.max(CONTROL_HEIGHT, contentBottom - this.contentTop);
        int rawGap = (availableHeight - CONTROL_HEIGHT) / Math.max(1, maxRows - 1);
        this.controlGap = Math.max(MIN_CONTROL_STEP, Math.min(DEFAULT_CONTROL_GAP, rawGap));
        this.rowsPerPage = Math.max(1, 1 + Math.max(0, availableHeight - CONTROL_HEIGHT) / this.controlGap);
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
        this.previousPageButton = null;
        this.nextPageButton = null;
        this.dynamicResolutionRuntimeInfoButton = null;
        this.sharpeningRuntimeInfoButton = null;
        this.qualitySlider = null;
        this.cpuInvolvementSlider = null;
        this.sharpenStrengthSlider = null;
        this.dynamicResolutionMinScaleSlider = null;
    }

    private void buildTabButtons() {
        ConfigTab[] tabs = ConfigTab.values();
        int totalWidth = tabs.length * this.tabWidth + (tabs.length - 1) * TAB_GAP;
        int left = this.width / 2 - totalWidth / 2;

        for (ConfigTab tab : tabs) {
            Button button = this.addRenderableWidget(
                    Button.builder(buildTabMessage(tab), value -> {
                        this.selectedTab = tab;
                        rebuildUi();
                    }).bounds(left, this.tabRowY, this.tabWidth, TAB_HEIGHT).build()
            );
            button.active = this.selectedTab != tab;
            left += this.tabWidth + TAB_GAP;
        }
    }

    private Component buildTabMessage(ConfigTab tab) {
        return this.selectedTab == tab
                ? Component.literal("[" + tab.label + "]")
                : Component.literal(tab.label);
    }

    private void buildTabContent() {
        int left = this.width / 2 - this.controlWidth / 2;
        prepareTabPagination(this.selectedTab);
        switch (this.selectedTab) {
            case CORE -> buildCoreTab(left);
            case RUNTIME -> buildRuntimeTab(left);
            case UPSCALE -> buildUpscaleTab(left);
            case DEFERRED -> buildDeferredTab(left);
            case INTEGRATION -> buildIntegrationTab(left);
        }
    }

    private void buildCoreTab(int left) {
        int row = 0;
        if (shouldRenderRow(row)) {
            this.toggleButton = this.addRenderableWidget(
                    Button.builder(buildToggleMessage(), button -> {
                        PauCClient.setEnabled(!PauCClient.isEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.qualitySlider = this.addRenderableWidget(new QualitySlider(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT));
        }

        row++;
        if (shouldRenderRow(row)) {
            this.adaptiveQualityButton = this.addRenderableWidget(
                    Button.builder(buildAdaptiveQualityMessage(), button -> {
                        PauCClient.setAdaptiveQualityEnabled(!PauCClient.isAdaptiveQualityEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.cpuInvolvementSlider = this.addRenderableWidget(new CpuInvolvementSlider(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT));
        }

        row++;
        if (shouldRenderRow(row)) {
            this.presetButton = this.addRenderableWidget(
                    Button.builder(buildPresetMessage(), button -> {
                        PauCClient.cycleUserPreset();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            int y = resolveRowY(row);
            int halfWidth = (this.controlWidth - INLINE_GAP) / 2;
            this.presetApplyButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Apply Preset"), button -> {
                        PauCClient.applySelectedPreset();
                        refreshButtonLabels();
                    }).bounds(left, y, halfWidth, CONTROL_HEIGHT).build()
            );
            this.recoveryButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Recovery"), button -> {
                        PauCClient.activateRecoveryMode();
                        refreshButtonLabels();
                    }).bounds(left + halfWidth + INLINE_GAP, y, halfWidth, CONTROL_HEIGHT).build()
            );
        }
    }

    private void buildRuntimeTab(int left) {
        boolean dynamicResolutionBlocked = isDynamicResolutionRuntimeBlocked();
        int row = 0;
        if (shouldRenderRow(row)) {
            this.authoritativeRuntimeButton = this.addRenderableWidget(
                    Button.builder(buildAuthoritativeRuntimeMessage(), button -> {
                        PauCClient.setAuthoritativeRuntimeEnabled(!PauCClient.isAuthoritativeRuntimeEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.dynamicResolutionButton = this.addRenderableWidget(
                    Button.builder(buildDynamicResolutionMessage(), button -> {
                        PauCClient.setDynamicResolutionEnabled(!PauCClient.isDynamicResolutionSettingEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
            this.dynamicResolutionButton.active = !dynamicResolutionBlocked;
        }

        row++;
        if (shouldRenderRow(row)) {
            this.dynamicResolutionMinScaleSlider = this.addRenderableWidget(
                    new DynamicResolutionMinScaleSlider(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT)
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.adaptiveSimulationDistanceButton = this.addRenderableWidget(
                    Button.builder(buildAdaptiveSimulationDistanceMessage(), button -> {
                        PauCClient.setAdaptiveSimulationDistanceEnabled(!PauCClient.isAdaptiveSimulationDistanceSettingEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.frameTimeStabilizerButton = this.addRenderableWidget(
                    Button.builder(buildFrameTimeStabilizerMessage(), button -> {
                        PauCClient.setFrameTimeStabilizerEnabled(!PauCClient.isFrameTimeStabilizerEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.gpuBottleneckButton = this.addRenderableWidget(
                    Button.builder(buildGpuBottleneckMessage(), button -> {
                        PauCClient.setGpuBottleneckDetectorEnabled(!PauCClient.isGpuBottleneckDetectorEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }
    }

    private void buildUpscaleTab(int left) {
        int row = 0;
        if (shouldRenderRow(row)) {
            this.shaderModeButton = this.addRenderableWidget(
                    Button.builder(buildShaderModeMessage(), button -> {
                        PauCShaderManager.cycleShaderMode();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            int y = resolveRowY(row);
            int halfWidth = (this.controlWidth - INLINE_GAP) / 2;
            this.shaderReloadButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Reload Shaders"), button -> {
                        PauCShaderManager.reloadExternalShaders();
                        refreshButtonLabels();
                    }).bounds(left, y, halfWidth, CONTROL_HEIGHT).build()
            );
            this.shaderFolderButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Open Folder"), button -> PauCShaderManager.openShaderFolder())
                            .bounds(left + halfWidth + INLINE_GAP, y, halfWidth, CONTROL_HEIGHT)
                            .build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.advancedSharpeningButton = this.addRenderableWidget(
                    Button.builder(buildAdvancedSharpeningMessage(), button -> {
                        PauCClient.setAdvancedSharpeningEnabled(!PauCClient.isAdvancedSharpeningEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
            this.advancedSharpeningButton.active = !CompatibilityGuards.shouldDisableAdvancedSharpening();
        }

        row++;
        if (shouldRenderRow(row)) {
            this.sharpenStrengthSlider = this.addRenderableWidget(new SharpenStrengthSlider(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT));
        }
    }

    private void buildDeferredTab(int left) {
        int row = 0;
        if (shouldRenderRow(row)) {
            this.deferredPackButton = this.addRenderableWidget(
                    Button.builder(buildDeferredPackMessage(), button -> {
                        PauCDeferredShaderController.cycleShaderPack();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.deferredCompatButton = this.addRenderableWidget(
                    Button.builder(buildDeferredCompatMessage(), button -> {
                        PauCDeferredShaderController.cycleCompatibilityMode();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            int y = resolveRowY(row);
            int halfWidth = (this.controlWidth - INLINE_GAP) / 2;
            this.deferredReloadButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Reload Pack"), button -> {
                        PauCDeferredShaderController.reloadCurrentPack();
                        refreshButtonLabels();
                    }).bounds(left, y, halfWidth, CONTROL_HEIGHT).build()
            );
            this.deferredFolderButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Packs Folder"), button -> PauCDeferredShaderController.openShaderPackFolder())
                            .bounds(left + halfWidth + INLINE_GAP, y, halfWidth, CONTROL_HEIGHT)
                            .build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.deferredRescanButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Rescan Packs"), button -> {
                        PauCDeferredShaderController.refreshAvailablePacks();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }
    }

    private void buildIntegrationTab(int left) {
        boolean dynamicResolutionBlocked = isDynamicResolutionRuntimeBlocked();
        int row = 0;
        if (shouldRenderRow(row)) {
            this.authoritativeRuntimeButton = this.addRenderableWidget(
                    Button.builder(buildAuthoritativeRuntimeMessage(), button -> {
                        PauCClient.setAuthoritativeRuntimeEnabled(!PauCClient.isAuthoritativeRuntimeEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.dynamicResolutionButton = this.addRenderableWidget(
                    Button.builder(buildDynamicResolutionMessage(), button -> {
                        PauCClient.setDynamicResolutionEnabled(!PauCClient.isDynamicResolutionSettingEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
            this.dynamicResolutionButton.active = !dynamicResolutionBlocked;
        }

        row++;
        if (shouldRenderRow(row)) {
            this.dynamicResolutionMinScaleSlider = this.addRenderableWidget(
                    new DynamicResolutionMinScaleSlider(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT)
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.advancedSharpeningButton = this.addRenderableWidget(
                    Button.builder(buildAdvancedSharpeningMessage(), button -> {
                        PauCClient.setAdvancedSharpeningEnabled(!PauCClient.isAdvancedSharpeningEnabled());
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
            this.advancedSharpeningButton.active = !CompatibilityGuards.shouldDisableAdvancedSharpening();
        }

        row++;
        if (shouldRenderRow(row)) {
            this.deferredCompatButton = this.addRenderableWidget(
                    Button.builder(buildDeferredCompatMessage(), button -> {
                        PauCDeferredShaderController.cycleCompatibilityMode();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.reloadAllPipelinesButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Reload All Pipelines"), button -> {
                        PauCShaderManager.reloadExternalShaders();
                        PauCDeferredShaderController.reloadCurrentPack();
                        refreshButtonLabels();
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            int y = resolveRowY(row);
            int halfWidth = (this.controlWidth - INLINE_GAP) / 2;
            this.shaderFolderButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Upscale Folder"), button -> PauCShaderManager.openShaderFolder())
                            .bounds(left, y, halfWidth, CONTROL_HEIGHT)
                            .build()
            );
            this.deferredFolderButton = this.addRenderableWidget(
                    Button.builder(Component.literal("Shaderpacks"), button -> PauCDeferredShaderController.openShaderPackFolder())
                            .bounds(left + halfWidth + INLINE_GAP, y, halfWidth, CONTROL_HEIGHT)
                            .build()
            );
        }

        row++;
        if (shouldRenderRow(row)) {
            this.dynamicResolutionRuntimeInfoButton = this.addRenderableWidget(
                    Button.builder(buildDynamicResolutionRuntimeMessage(), button -> {
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
            this.dynamicResolutionRuntimeInfoButton.active = false;
        }

        row++;
        if (shouldRenderRow(row)) {
            this.sharpeningRuntimeInfoButton = this.addRenderableWidget(
                    Button.builder(buildSharpeningRuntimeMessage(), button -> {
                    }).bounds(left, resolveRowY(row), this.controlWidth, CONTROL_HEIGHT).build()
            );
            this.sharpeningRuntimeInfoButton.active = false;
        }
    }

    private void addDoneButton() {
        int left = this.width / 2 - this.controlWidth / 2;
        int top = this.doneButtonY;
        if (this.totalPages <= 1) {
            this.addRenderableWidget(
                    Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).bounds(left, top, this.controlWidth, CONTROL_HEIGHT).build()
            );
            return;
        }

        int navWidth = Math.max(26, Math.min(54, (this.controlWidth - 90 - INLINE_GAP * 2) / 2));
        int doneWidth = Math.max(70, this.controlWidth - navWidth * 2 - INLINE_GAP * 2);
        this.previousPageButton = this.addRenderableWidget(
                Button.builder(Component.literal("<"), button -> changePage(-1)).bounds(left, top, navWidth, CONTROL_HEIGHT).build()
        );
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                        .bounds(left + navWidth + INLINE_GAP, top, doneWidth, CONTROL_HEIGHT)
                        .build()
        );
        this.nextPageButton = this.addRenderableWidget(
                Button.builder(Component.literal(">"), button -> changePage(1))
                        .bounds(left + navWidth + INLINE_GAP + doneWidth + INLINE_GAP, top, navWidth, CONTROL_HEIGHT)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        if (this.compactLayout) {
            guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal("Tab: " + this.selectedTab.label + " | Preset: " + PauCClient.getSelectedPreset().getDisplayLabel()),
                    this.width / 2,
                    20,
                    0xB8C6D4
            );
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal("Authority: " + AuthoritativeRuntimeController.getStatusLabel() + " | " + AuthoritativeRuntimeController.getDomainSummary()),
                    this.width / 2,
                    31,
                    resolveAuthorityColor()
            );
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal(buildCompactRuntimeDeferredLine()),
                    this.width / 2,
                    42,
                    0x88BBD6
            );
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal(buildCompactIntegrationLine()),
                    this.width / 2,
                    53,
                    resolveIntegrationColor()
            );
        } else {
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
        }
        if (this.totalPages > 1) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal("Page " + (getCurrentPage() + 1) + "/" + this.totalPages + " (mouse wheel)"),
                    this.width / 2,
                    this.doneButtonY - 11,
                    0x8EA7BD
            );
        }
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.totalPages > 1) {
            if (delta < 0.0D && changePage(1)) {
                return true;
            }
            if (delta > 0.0D && changePage(-1)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.totalPages > 1) {
            if ((keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) && changePage(1)) {
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_PAGE_UP) && changePage(-1)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean changePage(int delta) {
        int current = getCurrentPage();
        int next = Math.max(0, Math.min(this.totalPages - 1, current + delta));
        if (next == current) {
            return false;
        }
        setCurrentPage(next);
        rebuildUi();
        return true;
    }

    private void prepareTabPagination(ConfigTab tab) {
        int totalRows = resolveTabRowCount(tab);
        this.totalPages = Math.max(1, (totalRows + this.rowsPerPage - 1) / this.rowsPerPage);
        int currentPage = Math.max(0, Math.min(this.totalPages - 1, this.tabPageByIndex[tab.ordinal()]));
        this.tabPageByIndex[tab.ordinal()] = currentPage;
        this.pageStartRow = currentPage * this.rowsPerPage;
        this.pageEndRowExclusive = Math.min(totalRows, this.pageStartRow + this.rowsPerPage);
    }

    private int resolveTabRowCount(ConfigTab tab) {
        return switch (tab) {
            case CORE -> 6;
            case RUNTIME -> 6;
            case UPSCALE -> 4;
            case DEFERRED -> 4;
            case INTEGRATION -> 9;
        };
    }

    private int getCurrentPage() {
        return this.tabPageByIndex[this.selectedTab.ordinal()];
    }

    private void setCurrentPage(int page) {
        this.tabPageByIndex[this.selectedTab.ordinal()] = page;
    }

    private boolean shouldRenderRow(int rowIndex) {
        return rowIndex >= this.pageStartRow && rowIndex < this.pageEndRowExclusive;
    }

    private int resolveRowY(int rowIndex) {
        return this.contentTop + (rowIndex - this.pageStartRow) * this.controlGap;
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
            this.dynamicResolutionButton.active = !isDynamicResolutionRuntimeBlocked();
        }
        if (this.adaptiveSimulationDistanceButton != null) {
            this.adaptiveSimulationDistanceButton.setMessage(buildAdaptiveSimulationDistanceMessage());
        }
        if (this.dynamicResolutionRuntimeInfoButton != null) {
            this.dynamicResolutionRuntimeInfoButton.setMessage(buildDynamicResolutionRuntimeMessage());
            this.dynamicResolutionRuntimeInfoButton.active = false;
        }
        if (this.sharpeningRuntimeInfoButton != null) {
            this.sharpeningRuntimeInfoButton.setMessage(buildSharpeningRuntimeMessage());
            this.sharpeningRuntimeInfoButton.active = false;
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
        if (this.previousPageButton != null) {
            this.previousPageButton.active = getCurrentPage() > 0;
        }
        if (this.nextPageButton != null) {
            this.nextPageButton.active = getCurrentPage() + 1 < this.totalPages;
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
        String runtimeReason = PauCClient.getDynamicResolutionRuntimeReason();
        String state = PauCClient.isDynamicResolutionSettingEnabled() ? "ON" : "OFF";
        if ("ready".equals(runtimeReason) || "setting off".equals(runtimeReason)) {
            return Component.literal("Dynamic Resolution: " + state);
        }
        return Component.literal("Dynamic Resolution: " + state + " [" + runtimeReason + "]");
    }

    private Component buildDynamicResolutionRuntimeMessage() {
        return Component.literal("DRS Runtime: " + PauCClient.getDynamicResolutionRuntimeReason());
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
            return Component.literal("Advanced Sharpening: BLOCKED [external shader pipeline]");
        }

        return Component.translatable(
                "option.pauc.advanced_sharpening",
                PauCClient.isAdvancedSharpeningEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
        );
    }

    private Component buildSharpeningRuntimeMessage() {
        return Component.literal("Sharpen Runtime: " + resolveSharpeningRuntimeReason());
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

    private static boolean isDynamicResolutionRuntimeBlocked() {
        return AuthoritativeRuntimeController.shouldForceDisableDynamicResolution()
                || AuthoritativeRuntimeController.shouldForceDisableDynamicResolutionForDeferredPipeline()
                || AuthoritativeRuntimeController.shouldYieldDynamicResolutionToExternalPipeline();
    }

    private static String resolveSharpeningRuntimeReason() {
        if (!PauCClient.isEnabled()) {
            return "PauC off";
        }
        if (!PauCClient.isBudgetActive()) {
            return "runtime off";
        }
        if (!PauCClient.isAdvancedSharpeningEnabled()) {
            return "setting off";
        }
        if (CompatibilityGuards.shouldDisableAdvancedSharpening()) {
            return "external shader pipeline";
        }
        return "ready";
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

    private static String buildCompactRuntimeDeferredLine() {
        return "Q=" + PauCClient.getQualityLevel()
                + " DRS=" + (PauCClient.isDynamicResolutionSettingEnabled() ? "on" : "off")
                + " Deferred=" + PauCDeferredShaderController.getShortLabel()
                + " warn=" + getDeferredWarningCount();
    }

    private static String buildCompactIntegrationLine() {
        return "Embeddium=" + formatPresence(CompatibilityGuards.isEmbeddiumLoaded())
                + " Oculus=" + formatPresence(CompatibilityGuards.isOculusLoaded())
                + " Replay=" + formatPresence(CompatibilityGuards.isReplayStackLoaded());
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
            this.active = PauCClient.isDynamicResolutionSettingEnabled() && !isDynamicResolutionRuntimeBlocked();
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
