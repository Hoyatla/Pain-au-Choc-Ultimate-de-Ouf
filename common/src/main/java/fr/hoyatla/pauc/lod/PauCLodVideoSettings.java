package fr.hoyatla.pauc.lod;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public final class PauCLodVideoSettings {
	private static AbstractWidget lodDistanceWidget;
	private static AbstractWidget lodCloudsWidget;
	private static AbstractWidget nvidiaAccelerationWidget;
	private static AbstractWidget terrainMorphingWidget;
	private static AbstractWidget dynamicResolutionWidget;

	public static final OptionInstance<Boolean> VANILLA_FOG = new PauCLodToggleOption(
		"options.pauc.vanillaFog",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.vanillaFog.tooltip")),
		(option, enabled) -> Component.translatable(enabled ? "options.pauc.vanillaFog.enabled" : "options.pauc.vanillaFog.disabled"),
		OptionInstance.BOOLEAN_VALUES,
		PauCLodClientSettings.isVanillaFogEnabled(),
		PauCLodVideoSettings::setVanillaFogEnabled
	);

	public static final OptionInstance<Integer> LOD_RENDER_DISTANCE = new PauCLodDistanceOption(
		"options.pauc.lodDistance",
		minecraft -> Tooltip.create(Component.translatable("options.pauc.lodDistance.tooltip")),
		PauCLodVideoSettings::distanceCaption,
		new OptionInstance.IntRange(PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS, PauCLodRange.MAX_TARGET_DISTANCE_CHUNKS),
		PauCLodClientSettings.targetDistanceChunks(),
		PauCLodClientSettings::setTargetDistanceChunks
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
		LOD_RENDER_DISTANCE.set(PauCLodClientSettings.configuredTargetDistanceChunks());
		LOD_CLOUDS.set(PauCLodClientSettings.isLodCloudsEnabled());
		NVIDIA_ACCELERATION.set(PauCLodClientSettings.isNvidiaAccelerationEnabled());
		TERRAIN_MORPHING.set(PauCLodClientSettings.isTerrainMorphingEnabled());
		DYNAMIC_RESOLUTION.set(PauCLodClientSettings.dynamicResolutionMode().index());
		updateLinkedWidgets();
	}

	private static void setVanillaFogEnabled(boolean enabled) {
		PauCLodClientSettings.setVanillaFogEnabled(enabled);
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

	private static Component distanceCaption(Component option, int chunks) {
		if (!PauCLodClientSettings.isLodsEnabled()) {
			return Component.translatable("options.pauc.lodDistance.disabled");
		}

		Component extraDistance = Component.literal("+").append(
			Component.translatable("options.chunks", PauCLodClientSettings.sanitizeTargetDistanceChunks(chunks))
		);
		return Component.translatable(
			"options.generic_value",
			option,
			extraDistance
		);
	}

	private static Component cloudsCaption(Component option, boolean enabled) {
		if (!PauCLodClientSettings.isLodsEnabled()) {
			return Component.translatable("options.pauc.lodClouds.disabledWithLods");
		}

		return Component.translatable(enabled ? "options.pauc.lodClouds.enabled" : "options.pauc.lodClouds.disabled");
	}

	private static Component nvidiaAccelerationCaption(Component option, boolean enabled) {
		if (!enabled) {
			return Component.translatable("options.pauc.nvidiaAcceleration.disabled");
		}
		if (PauCLodClientSettings.isNvidiaAccelerationReady()) {
			return Component.translatable("options.pauc.nvidiaAcceleration.enabled");
		}
		if (PauCLodClientSettings.isNvidiaCudaDriverAvailable()) {
			return Component.translatable("options.pauc.nvidiaAcceleration.driverReady");
		}
		return Component.translatable("options.pauc.nvidiaAcceleration.waiting");
	}

	private static Component terrainMorphingCaption(Component option, boolean enabled) {
		if (!PauCLodClientSettings.isLodsEnabled()) {
			return Component.translatable("options.pauc.terrainMorphing.disabledWithLods");
		}

		return Component.translatable(enabled ? "options.pauc.terrainMorphing.enabled" : "options.pauc.terrainMorphing.disabled");
	}

	private static Component dynamicResolutionCaption(Component option, int modeIndex) {
		PauCDynamicResolutionMode mode = PauCDynamicResolutionMode.byIndex(modeIndex);
		return Component.translatable("options.generic_value", option, Component.translatable("options.pauc.dynamicResolution." + mode.id()));
	}

	private static void updateLinkedWidgets() {
		boolean enabled = PauCLodClientSettings.isLodsEnabled();
		if (lodDistanceWidget != null) {
			lodDistanceWidget.active = enabled;
			lodDistanceWidget.setMessage(enabled
				? distanceCaption(Component.translatable("options.pauc.lodDistance"), PauCLodClientSettings.configuredTargetDistanceChunks())
				: Component.translatable("options.pauc.lodDistance.disabled"));
		}
		if (lodCloudsWidget != null) {
			lodCloudsWidget.active = enabled;
			lodCloudsWidget.setMessage(cloudsCaption(Component.translatable("options.pauc.lodClouds"), PauCLodClientSettings.isLodCloudsEnabled()));
		}
		if (nvidiaAccelerationWidget != null) {
			nvidiaAccelerationWidget.setMessage(nvidiaAccelerationCaption(Component.translatable("options.pauc.nvidiaAcceleration"), PauCLodClientSettings.isNvidiaAccelerationEnabled()));
		}
		if (terrainMorphingWidget != null) {
			terrainMorphingWidget.active = enabled;
			terrainMorphingWidget.setMessage(terrainMorphingCaption(Component.translatable("options.pauc.terrainMorphing"), PauCLodClientSettings.isTerrainMorphingEnabled()));
		}
		if (dynamicResolutionWidget != null) {
			dynamicResolutionWidget.setMessage(dynamicResolutionCaption(Component.translatable("options.pauc.dynamicResolution"), PauCLodClientSettings.dynamicResolutionMode().index()));
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
