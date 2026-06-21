package net.irisshaders.iris.gui.option;

import fr.hoyatla.pauc.shader.PauCShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ShaderPackSelectionButtonOption extends OptionInstance<Boolean> {
	private static final Component LABEL = Component.translatable(PauCShaders.mainScreenLanguageKey());
	private static final Tooltip TOOLTIP = Tooltip.create(Component.translatable(PauCShaders.mainScreenTooltipLanguageKey()));
	private final Screen parent;

	public ShaderPackSelectionButtonOption(Screen parent) {
		super(
			PauCShaders.mainScreenLanguageKey(),
			minecraft -> TOOLTIP,
			(option, value) -> LABEL,
			OptionInstance.BOOLEAN_VALUES,
			false,
			value -> {
			}
		);
		this.parent = parent;
	}

	@Override
	public AbstractWidget createButton(Options options, int x, int y, int width) {
		Button button = Button.builder(LABEL, press -> Minecraft.getInstance().setScreen(PauCShaders.createShaderConfigScreen(this.parent)))
			.bounds(x, y, width, 20)
			.build();
		button.setTooltip(TOOLTIP);
		return button;
	}
}
