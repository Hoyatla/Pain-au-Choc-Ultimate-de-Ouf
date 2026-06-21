package net.irisshaders.iris.gui.element;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;

public class IrisObjectSelectionList<E extends AbstractSelectionList.Entry<E>> extends AbstractSelectionList<E> {
	public IrisObjectSelectionList(Minecraft client, int width, int height, int top, int bottom, int left, int right, int itemHeight) {
		super(client, width, height, top, bottom, itemHeight);

		this.x0 = left;
		this.x1 = right;
	}

	@Override
	protected int getScrollbarPosition() {
		// Position the scrollbar at the rightmost edge of the screen.
		// By default, the scrollbar is positioned moderately offset from the center.
		return this.x1 - 6;
	}

	@Override
	public int getRowLeft() {
		return this.x0 + Math.max(0, ((this.x1 - this.x0) - this.getRowWidth()) / 2);
	}

	@Override
	public int getRowRight() {
		return this.getRowLeft() + this.getRowWidth();
	}

	public void select(int entry) {
		setSelected(this.getEntry(entry));
	}

	@Override
	public void updateNarration(NarrationElementOutput p0) {

	}
}
