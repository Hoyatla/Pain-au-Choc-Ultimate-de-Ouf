package net.irisshaders.iris.gui.element;

import fr.hoyatla.pauc.shader.PauCShaders;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.NavigationController;
import net.irisshaders.iris.gui.element.widget.AbstractElementWidget;
import net.irisshaders.iris.gui.screen.ShaderPackHost;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.discovery.BundledShaderpackInstaller;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class EmbeddedShaderPackWidget extends AbstractWidget implements ShaderPackHost {
	public static final int HEIGHT = 164;
	public static final int RESERVED_HEIGHT = HEIGHT + 10;

	private static final int PANEL_PADDING = 8;
	private static final int PACK_BUTTON_HEIGHT = 20;
	private static final int PACK_BUTTON_GAP = 4;
	private static final int PACK_COLUMN_WIDTH = 128;
	private static final Component TITLE = Component.translatable("options.pauc.shaderPackSelection.title");

	private final Screen parent;
	private final Runnable onStateChanged;
	private final List<String> packIds = new ArrayList<>();
	private final List<PackButton> packButtons = new ArrayList<>();
	private final List<Runnable> topLayerRenderQueue = new ArrayList<>();

	private @Nullable ShaderPackOptionList optionList;
	private @Nullable NavigationController navigation;
	private @Nullable String selectedPackId;
	private Component statusLine = Component.empty();
	// When true, the selected pack has loaded and its settings can be opened in the dedicated config screen.
	private boolean canConfigure;

	public EmbeddedShaderPackWidget(Screen parent, int x, int y, int width, Runnable onStateChanged) {
		super(x, y, width, HEIGHT, TITLE);
		this.parent = parent;
		this.onStateChanged = onStateChanged;
		rebuild();
	}

	public void rebuild() {
		this.packIds.clear();
		this.packIds.addAll(BundledShaderpackInstaller.bundledPackIds());

		String configuredPack = BundledShaderpackInstaller.canonicalizePackName(Iris.getIrisConfig().getShaderPackName().orElse(null));
		if (configuredPack != null && !configuredPack.isBlank()) {
			this.selectedPackId = configuredPack;
		} else if (!this.packIds.isEmpty()) {
			this.selectedPackId = this.packIds.get(0);
		} else {
			this.selectedPackId = null;
		}

		rebuildPackButtons();
		rebuildOptionList();
	}

	private void rebuildPackButtons() {
		this.packButtons.clear();

		int buttonX = this.getX() + PANEL_PADDING;
		int buttonY = this.getY() + 26;
		int buttonWidth = Math.min(PACK_COLUMN_WIDTH, this.width - PANEL_PADDING * 2);

		for (String packId : this.packIds) {
			this.packButtons.add(new PackButton(packId, buttonX, buttonY, buttonWidth, PACK_BUTTON_HEIGHT));
			buttonY += PACK_BUTTON_HEIGHT + PACK_BUTTON_GAP;
		}
	}

	// The pack's settings are no longer shown inline in this panel. Selecting a pack here only enables/applies it; its
	// configuration opens in a dedicated screen via the Configure button (rebuildOptionList just refreshes the status).
	private void rebuildOptionList() {
		this.navigation = null;
		this.optionList = null;
		this.canConfigure = false;

		if (!PauCShaders.areShadersEnabledConfigured()) {
			this.statusLine = Component.translatable("options.pauc.shaders.disabled").withStyle(ChatFormatting.GRAY);
			return;
		}

		if (this.selectedPackId == null) {
			this.statusLine = Component.translatable("options.pauc.shaders.nonePresent").withStyle(ChatFormatting.GRAY);
			return;
		}

		if (Iris.getCurrentPack().isEmpty()) {
			this.statusLine = Component.translatable("options.pauc.shaderPackEmbedded.loadFailed").withStyle(ChatFormatting.RED);
			return;
		}

		this.canConfigure = true;
		this.statusLine = Component.literal(BundledShaderpackInstaller.displayPackName(this.selectedPackId)).withStyle(ChatFormatting.GRAY);
	}

	private int configureButtonX() {
		return this.getX() + PACK_COLUMN_WIDTH + PANEL_PADDING * 2;
	}

	private int configureButtonWidth() {
		return Math.max(80, (this.getX() + this.width - PANEL_PADDING) - configureButtonX());
	}

	private int configureButtonY() {
		return this.getY() + (this.height - PACK_BUTTON_HEIGHT) / 2 + 6;
	}

	private boolean configureButtonHovered(double mouseX, double mouseY) {
		int bx = configureButtonX();
		int by = configureButtonY();
		int bw = configureButtonWidth();
		return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + PACK_BUTTON_HEIGHT;
	}

	private void openConfigScreen() {
		if (this.selectedPackId == null) {
			return;
		}
		// Apply the selection first so the config screen opens the options for the pack shown here, then hand off to the
		// dedicated direct-options screen which returns to this video-settings parent when closed.
		applyChanges();
		Minecraft.getInstance().setScreen(ShaderPackScreen.openShaderPackOptionsDirectly(this.parent, this.selectedPackId));
	}

	private void selectPack(String packId) {
		this.selectedPackId = packId;
		Iris.getIrisConfig().setShaderPackName(packId);
		PauCShaders.setShadersEnabledAndApply(true);
		this.onStateChanged.run();
		rebuildOptionList();
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		Font font = Minecraft.getInstance().font;
		GuiUtil.drawPanel(guiGraphics, this.getX(), this.getY(), this.width, this.height);
		guiGraphics.drawString(font, TITLE.copy().withStyle(ChatFormatting.GRAY), this.getX() + PANEL_PADDING, this.getY() + 7, 0xFFFFFF);
		if (!this.statusLine.getString().isBlank()) {
			int statusX = this.getX() + PANEL_PADDING + 110;
			int statusWidth = Math.max(0, (this.getX() + this.width - PANEL_PADDING) - statusX);
			Component clippedStatus = this.statusLine;
			if (statusWidth > 0 && font.width(this.statusLine) > statusWidth) {
				String clipped = font.plainSubstrByWidth(this.statusLine.getString(), Math.max(0, statusWidth - font.width("...")));
				clippedStatus = Component.literal(clipped + "...");
			}
			guiGraphics.drawString(font, clippedStatus, statusX, this.getY() + 7, 0xFFFFFF);
		}

		for (PackButton button : this.packButtons) {
			button.render(guiGraphics, font, mouseX, mouseY, packIdSelected(button.packId));
		}

		int rightCenterX = this.getX() + PACK_COLUMN_WIDTH + PANEL_PADDING + (this.width - PACK_COLUMN_WIDTH - PANEL_PADDING) / 2;
		if (this.canConfigure) {
			guiGraphics.drawCenteredString(font, this.statusLine, rightCenterX, this.getY() + 30, 0xFFFFFF);
			int bx = configureButtonX();
			int by = configureButtonY();
			int bw = configureButtonWidth();
			boolean hovered = configureButtonHovered(mouseX, mouseY);
			GuiUtil.drawButton(guiGraphics, bx, by, bw, PACK_BUTTON_HEIGHT, hovered, false);
			MutableComponent label = Component.translatable("options.pauc.shaderPackSettings");
			if (hovered) {
				label = label.withStyle(ChatFormatting.BOLD);
			}
			guiGraphics.drawCenteredString(font, label, bx + bw / 2, by + 6, 0xFFFFFF);
		} else {
			Component message = PauCShaders.areShadersEnabledConfigured()
				? Component.translatable("options.pauc.shaderPackEmbedded.select")
				: Component.translatable("options.pauc.shaders.disabled");
			guiGraphics.drawCenteredString(font, message, this.getX() + PACK_COLUMN_WIDTH + (this.width - PACK_COLUMN_WIDTH) / 2, this.getY() + this.height / 2, 0xC0C0C0);
		}

		for (Runnable render : this.topLayerRenderQueue) {
			render.run();
		}
		this.topLayerRenderQueue.clear();
	}

	private boolean packIdSelected(String packId) {
		return this.selectedPackId != null && this.selectedPackId.equals(packId);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0 || !this.isMouseOver(mouseX, mouseY)) {
			return false;
		}

		for (PackButton packButton : this.packButtons) {
			if (packButton.hovered(mouseX, mouseY)) {
				GuiUtil.playButtonClickSound();
				selectPack(packButton.packId);
				return true;
			}
		}

		if (this.canConfigure && configureButtonHovered(mouseX, mouseY)) {
			GuiUtil.playButtonClickSound();
			openConfigScreen();
			return true;
		}

		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return this.optionList != null && this.optionList.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return this.optionList != null && this.optionList.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return this.optionList != null && this.optionList.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		narrationElementOutput.add(NarratedElementType.TITLE, TITLE);
	}

	@Override
	public void applyChanges() {
		if (this.selectedPackId == null) {
			return;
		}

		Iris.getIrisConfig().setShaderPackName(this.selectedPackId);
		PauCShaders.setShadersEnabledAndApply(PauCShaders.areShadersEnabledConfigured());
		this.onStateChanged.run();
		rebuildOptionList();
	}

	@Override
	public void displayNotification(Component component) {
		this.statusLine = component;
	}

	@Override
	public void setElementHoveredStatus(AbstractElementWidget<?> widget, boolean hovered) {
	}

	@Override
	public boolean isDisplayingComment() {
		return false;
	}

	@Override
	public void queueTopLayerRender(Runnable render) {
		this.topLayerRenderQueue.add(render);
	}

	@Override
	public boolean shouldApplyImmediately() {
		return true;
	}

	private static final class PackButton {
		private final String packId;
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private PackButton(String packId, int x, int y, int width, int height) {
			this.packId = packId;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		private void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY, boolean selected) {
			boolean hovered = hovered(mouseX, mouseY);
			GuiUtil.drawButton(guiGraphics, this.x, this.y, this.width, this.height, hovered, false);
			MutableComponent label = Component.literal(BundledShaderpackInstaller.displayPackName(this.packId));
			if (hovered) {
				label = label.withStyle(ChatFormatting.BOLD);
			}
			int color = selected ? 0xFFF263 : 0xFFFFFF;
			guiGraphics.drawCenteredString(font, label, this.x + this.width / 2, this.y + 6, color);
		}

		private boolean hovered(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
		}
	}
}
