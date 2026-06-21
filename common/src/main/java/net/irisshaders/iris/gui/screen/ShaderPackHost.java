package net.irisshaders.iris.gui.screen;

import net.irisshaders.iris.gui.element.widget.AbstractElementWidget;
import net.minecraft.network.chat.Component;

public interface ShaderPackHost {
	void applyChanges();

	void displayNotification(Component component);

	void setElementHoveredStatus(AbstractElementWidget<?> widget, boolean hovered);

	boolean isDisplayingComment();

	void queueTopLayerRender(Runnable render);

	default boolean shouldApplyImmediately() {
		return false;
	}
}
