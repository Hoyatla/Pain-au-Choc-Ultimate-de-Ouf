package net.irisshaders.iris.apiimpl;

import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.irisshaders.iris.config.IrisConfig;

import java.io.IOException;

public class IrisApiV0ConfigImpl implements IrisApiConfig {
	@Override
	public boolean areShadersEnabled() {
		return Iris.getIrisConfig().areShadersEnabled();
	}

	@Override
	public void setShadersEnabledAndApply(boolean enabled) {
		IrisConfig config = Iris.getIrisConfig();

		config.setShadersEnabled(enabled);

		try {
			config.save();
		} catch (IOException e) {
			Iris.logger.error("Error saving configuration file!", e);
		}

		if (PauCRenderLifecycle.isClientLogoutInProgress()) {
			Iris.logger.info("Skipping shader reload while client logout is in progress.");
			return;
		}

		try {
			Iris.reload();
		} catch (IOException e) {
			Iris.logger.error("Error reloading shader pack while applying changes!", e);
		} catch (Throwable t) {
			Iris.logger.error("Shader reload failed with a runtime error while applying changes. Reverting to shaders OFF to keep the client alive.", t);
			config.setShadersEnabled(false);
			try {
				config.save();
			} catch (IOException saveFailure) {
				Iris.logger.error("Error saving fallback shader-disabled configuration!", saveFailure);
			}

			try {
				Iris.reload();
			} catch (Throwable fallbackReloadFailure) {
				Iris.logger.error("Fallback reload (shaders OFF) also failed.", fallbackReloadFailure);
			}
		}
	}
}
