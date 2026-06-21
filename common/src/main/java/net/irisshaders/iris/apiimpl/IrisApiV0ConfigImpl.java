package net.irisshaders.iris.apiimpl;

import fr.hoyatla.pauc.shader.PauCShaders;
import net.irisshaders.iris.api.v0.IrisApiConfig;

public class IrisApiV0ConfigImpl implements IrisApiConfig {
	@Override
	public boolean areShadersEnabled() {
		return PauCShaders.areShadersEnabledConfigured();
	}

	@Override
	public void setShadersEnabledAndApply(boolean enabled) {
		PauCShaders.setShadersEnabledAndApply(enabled);
	}
}
