package fr.hoyatla.pauc.lod;

import fr.hoyatla.pauc.shader.PauCShaders;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public final class PauCLodVideoSettings {
	private static AbstractWidget vanillaFogWidget;
	private static AbstractWidget shadersEnabledWidget;
	private static AbstractWidget lodDistanceWidget;
	private static AbstractWidget lodCloudsWidget;
	private static AbstractWidget nvidiaAccelerationWidget;
	private static AbstractWidget terrainMorphingWidget;
	private static AbstractWidget dynamicResolutionWidget;

	public static final OptionInstance<Boolean> VANILLA_FOG = new PauCLodToggleOption(
		"options.pauc.vanillaFog",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.vanillaFog.tooltip")),
		PauCLodVideoSettings::vanillaFogCaption,
		OptionInstance.BOOLEAN_VALUES,
		PauCLodClientSettings.isVanillaFogEnabled(),
		PauCLodVideoSettings::setVanillaFogEnabled
	);

	public static final OptionInstance<Boolean> SHADERS_ENABLED = new PauCShadersToggleOption(
		"options.pauc.shaders",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.shaders.tooltip")),
		PauCLodVideoSettings::shadersEnabledCaption,
		OptionInstance.BOOLEAN_VALUES,
		PauCShaders.areShadersEnabledConfigured(),
		PauCLodVideoSettings::setShadersEnabled
	);

	// The LOD distance slider doubles as the LODs on/off control: its lowest stop is OFF (disables LODs), then +2..+96
	// chunks. Keeping one always-interactive slider means LODs can be turned back on from the menu (previously, once off,
	// the slider greyed out and there was no way to re-enable from Video Settings).
	private static final int LOD_DISTANCE_OFF = PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS - 1;

	public static final OptionInstance<Integer> LOD_RENDER_DISTANCE = new PauCLodDistanceOption(
		"options.pauc.lodDistance",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.lodDistance.tooltip")),
		PauCLodVideoSettings::distanceCaption,
		new OptionInstance.IntRange(LOD_DISTANCE_OFF, PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS),
		lodDistanceSliderValue(),
		PauCLodVideoSettings::setLodDistanceOrDisable
	);

	public static final OptionInstance<Boolean> LOD_CLOUDS = new PauCLodCloudsOption(
		"options.pauc.lodClouds",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.lodClouds.tooltip")),
		PauCLodVideoSettings::cloudsCaption,
		OptionInstance.BOOLEAN_VALUES,
		PauCLodClientSettings.isLodCloudsEnabled(),
		PauCLodVideoSettings::setLodCloudsEnabled
	);

	public static final OptionInstance<Boolean> NVIDIA_ACCELERATION = new PauCNvidiaAccelerationOption(
		"options.pauc.nvidiaAcceleration",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.nvidiaAcceleration.tooltip")),
		PauCLodVideoSettings::nvidiaAccelerationCaption,
		OptionInstance.BOOLEAN_VALUES,
		PauCLodClientSettings.isNvidiaAccelerationEnabled(),
		PauCLodVideoSettings::setNvidiaAccelerationEnabled
	);

	public static final OptionInstance<Boolean> TERRAIN_MORPHING = new PauCTerrainMorphingOption(
		"options.pauc.terrainMorphing",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.terrainMorphing.tooltip")),
		PauCLodVideoSettings::terrainMorphingCaption,
		OptionInstance.BOOLEAN_VALUES,
		PauCLodClientSettings.isTerrainMorphingEnabled(),
		PauCLodVideoSettings::setTerrainMorphingEnabled
	);

	public static final OptionInstance<Integer> DYNAMIC_RESOLUTION = new PauCDynamicResolutionOption(
		"options.pauc.dynamicResolution",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.dynamicResolution.tooltip")),
		PauCLodVideoSettings::dynamicResolutionCaption,
		new OptionInstance.IntRange(PauCDynamicResolutionMode.OFF.index(), PauCDynamicResolutionMode.PERFORMANCE.index()),
		PauCLodClientSettings.dynamicResolutionMode().index(),
		PauCLodVideoSettings::setDynamicResolutionMode
	);

	private PauCLodVideoSettings() {
	}

	public static void syncFromClientSettings() {
		VANILLA_FOG.set(PauCLodClientSettings.isVanillaFogEnabled());
		SHADERS_ENABLED.set(PauCShaders.areShadersEnabledConfigured());
		LOD_RENDER_DISTANCE.set(lodDistanceSliderValue());
		LOD_CLOUDS.set(PauCLodClientSettings.isLodCloudsEnabled());
		NVIDIA_ACCELERATION.set(PauCLodClientSettings.isNvidiaAccelerationEnabled());
		TERRAIN_MORPHING.set(PauCLodClientSettings.isTerrainMorphingEnabled());
		DYNAMIC_RESOLUTION.set(PauCLodClientSettings.dynamicResolutionMode().index());
		updateLinkedWidgets();
	}

	private static void setVanillaFogEnabled(boolean enabled) {
		PauCLodClientSettings.setVanillaFogEnabled(enabled);
	}

	private static void setShadersEnabled(boolean enabled) {
		PauCShaders.setShadersEnabledAndApply(enabled);
		updateLinkedWidgets();
	}

	private static void setLodCloudsEnabled(boolean enabled) {
		PauCLodClientSettings.setLodCloudsEnabled(enabled);
		updateLinkedWidgets();
	}

	private static void setNvidiaAccelerationEnabled(boolean enabled) {
		PauCLodClientSettings.setNvidiaAccelerationEnabled(enabled);
		updateLinkedWidgets();
	}

	private static void setTerrainMorphingEnabled(boolean enabled) {
		PauCLodClientSettings.setTerrainMorphingEnabled(enabled);
		updateLinkedWidgets();
	}

	private static void setDynamicResolutionMode(int modeIndex) {
		PauCLodClientSettings.setDynamicResolutionMode(PauCDynamicResolutionMode.byIndex(modeIndex));
		updateLinkedWidgets();
	}

	// Slider position for the current state: OFF stop when LODs are disabled, otherwise the configured chunk distance.
	private static int lodDistanceSliderValue() {
		return PauCLodClientSettings.isLodsEnabled()
			? PauCLodClientSettings.configuredTargetDistanceChunks()
			: LOD_DISTANCE_OFF;
	}

	// Dragging to the OFF stop disables LODs; anything at or above the minimum re-enables them and sets the distance.
	private static void setLodDistanceOrDisable(int value) {
		if (value < PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS) {
			PauCLodClientSettings.setLodsEnabled(false);
		} else {
			if (!PauCLodClientSettings.isLodsEnabled()) {
				PauCLodClientSettings.setLodsEnabled(true);
			}
			PauCLodClientSettings.setTargetDistanceChunks(value);
		}
		updateLinkedWidgets();
	}

	private static Component vanillaFogCaption(Component option, boolean enabled) {
		return statusValue(enabled ? "options.pauc.vanillaFog.enabled" : "options.pauc.vanillaFog.disabled");
	}

	private static Component shadersEnabledCaption(Component option, boolean enabled) {
		return statusValue(enabled ? "options.pauc.shaders.enabled" : "options.pauc.shaders.disabled");
	}

	private static Component distanceCaption(Component option, int chunks) {
		if (chunks < PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS || !PauCLodClientSettings.isLodsEnabled()) {
			return CommonComponents.OPTION_OFF;
		}

		return Component.literal("+").append(
			Component.translatable("options.chunks", PauCLodClientSettings.sanitizeTargetDistanceChunks(chunks))
		);
	}

	private static Component cloudsCaption(Component option, boolean enabled) {
		if (!PauCLodClientSettings.isLodsEnabled()) {
			return statusValue("options.pauc.lodClouds.disabledWithLods");
		}

		return statusValue(enabled ? "options.pauc.lodClouds.enabled" : "options.pauc.lodClouds.disabled");
	}

	private static Component nvidiaAccelerationCaption(Component option, boolean enabled) {
		if (!enabled) {
			return statusValue("options.pauc.nvidiaAcceleration.disabled");
		}
		if (PauCLodClientSettings.isNvidiaAccelerationReady()) {
			return statusValue("options.pauc.nvidiaAcceleration.enabled");
		}
		if (PauCLodClientSettings.isNvidiaCudaDriverAvailable()) {
			return statusValue("options.pauc.nvidiaAcceleration.driverReady");
		}
		return statusValue("options.pauc.nvidiaAcceleration.waiting");
	}

	private static Component terrainMorphingCaption(Component option, boolean enabled) {
		if (!PauCLodClientSettings.isLodsEnabled()) {
			return statusValue("options.pauc.terrainMorphing.disabledWithLods");
		}

		return statusValue(enabled ? "options.pauc.terrainMorphing.enabled" : "options.pauc.terrainMorphing.disabled");
	}

	private static Component dynamicResolutionCaption(Component option, int modeIndex) {
		PauCDynamicResolutionMode mode = PauCDynamicResolutionMode.byIndex(modeIndex);
		return Component.translatable("options.pauc.dynamicResolution." + mode.id());
	}

	private static Component statusValue(String translationKey) {
		return Component.translatable(translationKey);
	}

	private static Component fullCaption(String optionTranslationKey, Component value) {
		return Component.translatable("options.generic_value", Component.translatable(optionTranslationKey), value);
	}

	private static void updateLinkedWidgets() {
		boolean enabled = PauCLodClientSettings.isLodsEnabled();
		if (vanillaFogWidget != null) {
			vanillaFogWidget.setMessage(fullCaption("options.pauc.vanillaFog", vanillaFogCaption(Component.empty(), PauCLodClientSettings.isVanillaFogEnabled())));
		}
		if (shadersEnabledWidget != null) {
			shadersEnabledWidget.setMessage(fullCaption("options.pauc.shaders", shadersEnabledCaption(Component.empty(), PauCShaders.areShadersEnabledConfigured())));
		}
		if (lodDistanceWidget != null) {
			// Always interactive: this slider is how LODs get turned back on (its OFF stop disables them).
			lodDistanceWidget.active = true;
			lodDistanceWidget.setMessage(fullCaption("options.pauc.lodDistance", distanceCaption(Component.empty(), lodDistanceSliderValue())));
		}
		if (lodCloudsWidget != null) {
			lodCloudsWidget.active = enabled;
			lodCloudsWidget.setMessage(fullCaption("options.pauc.lodClouds", cloudsCaption(Component.empty(), PauCLodClientSettings.isLodCloudsEnabled())));
		}
		if (nvidiaAccelerationWidget != null) {
			nvidiaAccelerationWidget.setMessage(fullCaption("options.pauc.nvidiaAcceleration", nvidiaAccelerationCaption(Component.empty(), PauCLodClientSettings.isNvidiaAccelerationEnabled())));
		}
		if (terrainMorphingWidget != null) {
			terrainMorphingWidget.active = enabled;
			terrainMorphingWidget.setMessage(fullCaption("options.pauc.terrainMorphing", terrainMorphingCaption(Component.empty(), PauCLodClientSettings.isTerrainMorphingEnabled())));
		}
		if (dynamicResolutionWidget != null) {
			dynamicResolutionWidget.setMessage(fullCaption("options.pauc.dynamicResolution", dynamicResolutionCaption(Component.empty(), PauCLodClientSettings.dynamicResolutionMode().index())));
		}
	}

	private static final class PauCLodToggleOption extends OptionInstance<Boolean> {
		private PauCLodToggleOption(
			String caption,
			TooltipSupplier<Boolean> tooltip,
			CaptionBasedToString<Boolean> captionBasedToString,
			ValueSet<Boolean> values,
			Boolean initialValue,
			Consumer<Boolean> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			vanillaFogWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}

	private static final class PauCShadersToggleOption extends OptionInstance<Boolean> {
		private PauCShadersToggleOption(
			String caption,
			TooltipSupplier<Boolean> tooltip,
			CaptionBasedToString<Boolean> captionBasedToString,
			ValueSet<Boolean> values,
			Boolean initialValue,
			Consumer<Boolean> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			shadersEnabledWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}

	private static final class PauCLodDistanceOption extends OptionInstance<Integer> {
		private PauCLodDistanceOption(
			String caption,
			TooltipSupplier<Integer> tooltip,
			CaptionBasedToString<Integer> captionBasedToString,
			ValueSet<Integer> values,
			Integer initialValue,
			Consumer<Integer> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			lodDistanceWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}

	private static final class PauCLodCloudsOption extends OptionInstance<Boolean> {
		private PauCLodCloudsOption(
			String caption,
			TooltipSupplier<Boolean> tooltip,
			CaptionBasedToString<Boolean> captionBasedToString,
			ValueSet<Boolean> values,
			Boolean initialValue,
			Consumer<Boolean> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			lodCloudsWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}

	private static final class PauCNvidiaAccelerationOption extends OptionInstance<Boolean> {
		private PauCNvidiaAccelerationOption(
			String caption,
			TooltipSupplier<Boolean> tooltip,
			CaptionBasedToString<Boolean> captionBasedToString,
			ValueSet<Boolean> values,
			Boolean initialValue,
			Consumer<Boolean> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			nvidiaAccelerationWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}

	private static final class PauCTerrainMorphingOption extends OptionInstance<Boolean> {
		private PauCTerrainMorphingOption(
			String caption,
			TooltipSupplier<Boolean> tooltip,
			CaptionBasedToString<Boolean> captionBasedToString,
			ValueSet<Boolean> values,
			Boolean initialValue,
			Consumer<Boolean> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			terrainMorphingWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}

	private static final class PauCDynamicResolutionOption extends OptionInstance<Integer> {
		private PauCDynamicResolutionOption(
			String caption,
			TooltipSupplier<Integer> tooltip,
			CaptionBasedToString<Integer> captionBasedToString,
			ValueSet<Integer> values,
			Integer initialValue,
			Consumer<Integer> changeCallback
		) {
			super(caption, tooltip, captionBasedToString, values, initialValue, changeCallback);
		}

		@Override
		public AbstractWidget createButton(Options options, int x, int y, int width) {
			AbstractWidget widget = super.createButton(options, x, y, width);
			dynamicResolutionWidget = widget;
			updateLinkedWidgets();
			return widget;
		}
	}
}
