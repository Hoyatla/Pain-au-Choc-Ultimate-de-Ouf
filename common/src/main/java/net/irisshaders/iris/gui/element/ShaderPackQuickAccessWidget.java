package net.irisshaders.iris.gui.element;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
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

public class ShaderPackQuickAccessWidget extends AbstractWidget {
	public static final int HEIGHT = 50;
	public static final int RESERVED_HEIGHT = 58;

	private static final int BUTTON_HEIGHT = 18;
	private static final int BUTTON_GAP = 4;
	private static final int MIN_BUTTON_WIDTH = 76;
	private static final int MAX_BUTTON_WIDTH = 170;
	private static final String SHADER_OFF_SENTINEL = "__pauc_shader_off__";
	private static final Component TITLE = Component.translatable("options.iris.shaderPackSelection");
	private static final Component EMPTY = Component.translatable("options.iris.shaders.nonePresent").withStyle(ChatFormatting.GRAY);
	private static final Component SHADER_OFF = Component.translatable("options.iris.shaderQuickAccess.off");
	private static final Component PREVIOUS = Component.literal("<");
	private static final Component NEXT = Component.literal(">");

	private final Screen parent;
	private final List<String> packNames;
	private final List<PackButton> visibleButtons = new ArrayList<>();
	private int firstVisiblePack = 0;
	private @Nullable PageButton previousButton;
	private @Nullable PageButton nextButton;

	public ShaderPackQuickAccessWidget(Screen parent, int x, int y, int width) {
		super(x, y, width, HEIGHT, TITLE);
		this.parent = parent;
		this.packNames = readShaderPackNames();
		centerOnCurrentPack();
	}

	private static List<String> readShaderPackNames() {
		List<String> entries = new ArrayList<>();
		entries.add(SHADER_OFF_SENTINEL);
		try {
			entries.addAll(Iris.getShaderpacksDirectoryManager().enumerate());
		} catch (Throwable e) {
			Iris.logger.error("Error reading shaderpacks while constructing video settings quick access", e);
		}
		return entries;
	}

	private void centerOnCurrentPack() {
		if (!Iris.getIrisConfig().areShadersEnabled()) {
			this.firstVisiblePack = 0;
			return;
		}

		String currentPackName = Iris.getIrisConfig().getShaderPackName().orElse(null);
		if (currentPackName == null) {
			return;
		}

		int index = this.packNames.indexOf(currentPackName);
		if (index >= 0) {
			this.firstVisiblePack = index;
		}
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
		Font font = Minecraft.getInstance().font;
		int x = this.getX();
		int y = this.getY();

		GuiUtil.drawPanel(guiGraphics, x, y, this.width, this.height);
		guiGraphics.drawString(font, TITLE.copy().withStyle(ChatFormatting.GRAY), x + 6, y + 6, 0xFFFFFF);

		if (this.packNames.isEmpty()) {
			guiGraphics.drawCenteredString(font, EMPTY, x + this.width / 2, y + 27, 0xA0A0A0);
			this.visibleButtons.clear();
			this.previousButton = null;
			this.nextButton = null;
			return;
		}

		rebuildVisibleButtons(font);

		for (PackButton button : this.visibleButtons) {
			button.render(guiGraphics, font, mouseX, mouseY);
		}

		if (this.previousButton != null) {
			this.previousButton.render(guiGraphics, font, mouseX, mouseY);
		}

		if (this.nextButton != null) {
			this.nextButton.render(guiGraphics, font, mouseX, mouseY);
		}

		for (PackButton button : this.visibleButtons) {
			if (button.hovered(mouseX, mouseY) && button.truncated) {
				guiGraphics.renderTooltip(font, Component.literal(button.packName), mouseX, mouseY);
				break;
			}
		}
	}

	private void rebuildVisibleButtons(Font font) {
		this.visibleButtons.clear();
		this.previousButton = null;
		this.nextButton = null;

		int x = this.getX();
		int y = this.getY();
		int arrowWidth = 18;
		int left = x + 6;
		int right = x + this.width - 6;
		int buttonY = y + 25;
		int cursor = left;

		if (this.firstVisiblePack > 0) {
			this.previousButton = new PageButton(cursor, buttonY, arrowWidth, BUTTON_HEIGHT, false);
			cursor += arrowWidth + BUTTON_GAP;
		}

		int reserveRight = hasHiddenPacksAfter(this.firstVisiblePack) ? arrowWidth + BUTTON_GAP : 0;

		for (int i = this.firstVisiblePack; i < this.packNames.size(); i++) {
			String packName = this.packNames.get(i);
			int packWidth = Math.min(MAX_BUTTON_WIDTH, Math.max(MIN_BUTTON_WIDTH, font.width(packName) + 18));

			if (cursor + packWidth > right - reserveRight) {
				break;
			}

			this.visibleButtons.add(new PackButton(packName, cursor, buttonY, packWidth, BUTTON_HEIGHT));
			cursor += packWidth + BUTTON_GAP;
		}

		if (this.visibleButtons.isEmpty() && this.firstVisiblePack > 0) {
			this.firstVisiblePack = Math.max(0, this.firstVisiblePack - 1);
			rebuildVisibleButtons(font);
			return;
		}

		if (hasHiddenPacksAfter(lastVisiblePackIndex())) {
			this.nextButton = new PageButton(right - arrowWidth, buttonY, arrowWidth, BUTTON_HEIGHT, true);
		}
	}

	private boolean hasHiddenPacksAfter(int index) {
		return index < this.packNames.size() - 1;
	}

	private int lastVisiblePackIndex() {
		if (this.visibleButtons.isEmpty()) {
			return this.firstVisiblePack - 1;
		}

		PackButton last = this.visibleButtons.get(this.visibleButtons.size() - 1);
		return this.packNames.indexOf(last.packName);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0 || !this.isMouseOver(mouseX, mouseY)) {
			return false;
		}

		if (this.previousButton != null && this.previousButton.hovered(mouseX, mouseY)) {
			this.firstVisiblePack = Math.max(0, this.firstVisiblePack - Math.max(1, this.visibleButtons.size()));
			GuiUtil.playButtonClickSound();
			return true;
		}

		if (this.nextButton != null && this.nextButton.hovered(mouseX, mouseY)) {
			this.firstVisiblePack = Math.min(this.packNames.size() - 1, lastVisiblePackIndex() + 1);
			GuiUtil.playButtonClickSound();
			return true;
		}

		for (PackButton packButton : this.visibleButtons) {
			if (packButton.hovered(mouseX, mouseY)) {
				GuiUtil.playButtonClickSound();
				if (isShaderOffEntry(packButton.packName)) {
					if (Iris.getIrisConfig().areShadersEnabled()) {
						IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
					}
				} else {
					Minecraft.getInstance().setScreen(ShaderPackScreen.openShaderPackOptionsDirectly(this.parent, packButton.packName));
				}
				return true;
			}
		}

		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (!this.isMouseOver(mouseX, mouseY) || this.packNames.size() <= this.visibleButtons.size()) {
			return false;
		}

		if (delta < 0.0) {
			this.firstVisiblePack = Math.min(this.packNames.size() - 1, this.firstVisiblePack + 1);
		} else if (delta > 0.0) {
			this.firstVisiblePack = Math.max(0, this.firstVisiblePack - 1);
		}

		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		narrationElementOutput.add(NarratedElementType.TITLE, TITLE);
	}

	private static boolean isShaderOffEntry(String packName) {
		return SHADER_OFF_SENTINEL.equals(packName);
	}

	private static class PackButton {
		private final String packName;
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final boolean truncated;
		private final MutableComponent label;

		private PackButton(String packName, int x, int y, int width, int height) {
			this.packName = packName;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			Font font = Minecraft.getInstance().font;
			String displayName = isShaderOffEntry(packName) ? SHADER_OFF.getString() : packName;
			boolean shortened = false;

			if (font.width(displayName) > width - 12) {
				displayName = font.plainSubstrByWidth(displayName, width - 18) + "...";
				shortened = true;
			}

			this.truncated = shortened;
			this.label = Component.literal(displayName);
		}

		private void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
			boolean hovered = hovered(mouseX, mouseY);
			boolean active = isShaderOffEntry(packName)
				? !Iris.getIrisConfig().areShadersEnabled()
				: Iris.getIrisConfig().areShadersEnabled() && packName.equals(Iris.getCurrentPackName());
			GuiUtil.drawButton(guiGraphics, x, y, width, height, hovered, false);
			MutableComponent text = this.label.copy();
			int color = active ? 0xFFF263 : 0xFFFFFF;

			if (hovered) {
				text = text.withStyle(ChatFormatting.BOLD);
			}

			guiGraphics.drawCenteredString(font, text, x + width / 2, y + 5, color);
		}

		private boolean hovered(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}

	private static class PageButton {
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final boolean next;

		private PageButton(int x, int y, int width, int height, boolean next) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.next = next;
		}

		private void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
			GuiUtil.drawButton(guiGraphics, x, y, width, height, hovered(mouseX, mouseY), false);
			guiGraphics.drawCenteredString(font, next ? NEXT : PREVIOUS, x + width / 2, y + 5, 0xFFFFFF);
		}

		private boolean hovered(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}
}
