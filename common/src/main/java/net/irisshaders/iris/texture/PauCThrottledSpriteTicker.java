package net.irisshaders.iris.texture;

import fr.hoyatla.pauc.lod.PauCAnimatedTextureBudget;
import net.minecraft.client.renderer.texture.SpriteTicker;

public final class PauCThrottledSpriteTicker implements SpriteTicker {
	private final SpriteTicker delegate;

	public PauCThrottledSpriteTicker(SpriteTicker delegate) {
		this.delegate = delegate;
	}

	@Override
	public void tickAndUpload(int x, int y) {
		if (PauCAnimatedTextureBudget.shouldAdvanceThisFrame()) {
			delegate.tickAndUpload(x, y);
		}
	}

	@Override
	public void close() {
		delegate.close();
	}
}
