package fr.hoyatla.pauc.shader;

import fr.hoyatla.pauc.compat.PauCRenderLifecycle;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;

import java.io.IOException;

public final class PauCShaders {
	public static final String MAIN_SCREEN_KEY = "options.pauc.shaderPackSelection";
	public static final String MAIN_SCREEN_TITLE_KEY = MAIN_SCREEN_KEY + ".title";
	public static final String MAIN_SCREEN_TOOLTIP_KEY = MAIN_SCREEN_KEY + ".tooltip";

	private PauCShaders() {
	}

	public static boolean isShaderPackInUse() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		return pipeline != null && !(pipeline instanceof VanillaRenderingPipeline);
	}

	public static boolean areShadersEnabledConfigured() {
		return Iris.getIrisConfig().areShadersEnabled();
	}

	public static void setShadersEnabledAndApply(boolean enabled) {
		IrisConfig config = Iris.getIrisConfig();
		config.setShadersEnabled(enabled);

		try {
			config.save();
		} catch (IOException e) {
			Iris.logger.error("Error saving PauC shader configuration file!", e);
		}

		if (PauCRenderLifecycle.isClientLogoutInProgress()) {
			Iris.logger.info("Skipping PauC shader reload while client logout is in progress.");
			return;
		}

		try {
			Iris.reload();
		} catch (IOException e) {
			Iris.logger.error("Error reloading PauC shader runtime while applying changes!", e);
		} catch (Throwable t) {
			Iris.logger.error("PauC shader reload failed while applying changes. Reverting to shaders OFF to keep the client alive.", t);
			config.setShadersEnabled(false);
			try {
				config.save();
			} catch (IOException saveFailure) {
				Iris.logger.error("Error saving fallback PauC shader-disabled configuration!", saveFailure);
			}

			try {
				Iris.reload();
			} catch (Throwable fallbackReloadFailure) {
				Iris.logger.error("Fallback PauC shader reload (shaders OFF) also failed.", fallbackReloadFailure);
			}
		}
	}

	public static Screen createShaderConfigScreen(Screen parent) {
		return new VideoSettingsScreen(parent, Minecraft.getInstance().options);
	}

	public static String mainScreenLanguageKey() {
		return MAIN_SCREEN_KEY;
	}

	public static String mainScreenTitleLanguageKey() {
		return MAIN_SCREEN_TITLE_KEY;
	}

	public static String mainScreenTooltipLanguageKey() {
		return MAIN_SCREEN_TOOLTIP_KEY;
	}
}
