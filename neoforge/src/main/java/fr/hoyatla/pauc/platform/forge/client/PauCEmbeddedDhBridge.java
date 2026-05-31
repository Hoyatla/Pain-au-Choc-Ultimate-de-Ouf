package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.api.v0.IrisApi;
import org.slf4j.Logger;

public final class PauCEmbeddedDhBridge {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile BridgeState state = BridgeState.unavailable("not-configured");
	private static volatile int lastConfiguredTarget = -1;
	private static volatile LodRenderGeometrySignature lastRenderGeometrySignature;
	private static volatile boolean loggedWaitingForDh;
	private static volatile boolean loggedFarClipFadeOverride;
	private static volatile boolean loggedDitherFadeOverride;
	private static volatile boolean loggedVanillaGraphicsOverride;
	private static volatile boolean loggedSeamlessTransitionOverride;
	private static volatile boolean loggedReliefGeometryOverride;
	private static volatile boolean loggedReliefCompressionOverride;
	private static volatile boolean loggedDhFogConfigOverride;
	private static volatile boolean loggedRoundHorizonGenerationPolicy;
	private static volatile Boolean lastLoggedDistantCloudPolicy;
	private static volatile boolean loggedMissingCoreConfigOverride;
	private static volatile boolean disabledRenderingApplied;
	private static final String FAST_THREADS_PROPERTY = "pauc.lod.generationThreads";
	private static final String FAST_RUNTIME_RATIO_PROPERTY = "pauc.lod.generationRuntimeRatio";
	private static final String FAST_GENERATOR_MODE_PROPERTY = "pauc.lod.generationMode";
	private static final String FAST_MAX_RESOLUTION_PROPERTY = "pauc.lod.maxHorizontalResolution";
	private static final String FAST_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.horizontalQuality";
	private static final String FAST_VERTICAL_QUALITY_PROPERTY = "pauc.lod.verticalQuality";
	private static final String DYNAMIC_MAX_RESOLUTION_PROPERTY = "pauc.lod.dynamicMaxHorizontalResolution";
	private static final String DYNAMIC_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.dynamicHorizontalQuality";
	private static final String DYNAMIC_VERTICAL_QUALITY_PROPERTY = "pauc.lod.dynamicVerticalQuality";
	private static final String FAST_BIOME_BLEND_PROPERTY = "pauc.lod.biomeBlending";
	private static final String FAST_TRANSPARENCY_PROPERTY = "pauc.lod.transparency";
	private static final String DISTANT_STRUCTURES_PROPERTY = "pauc.lod.structures";
	private static final String RELIEF_CAVE_CULLING_PROPERTY = "pauc.lod.reliefCaveCulling";
	private static final String RELIEF_CAVE_CULLING_HEIGHT_PROPERTY = "pauc.lod.reliefCaveCullingHeight";
	private static final String RELIEF_SURFACE_CAVE_CULLING_HEIGHT_PROPERTY = "pauc.lod.reliefSurfaceCaveCullingHeight";
	private static final String RELIEF_UNDERGROUND_CAVE_CULLING_HEIGHT_PROPERTY = "pauc.lod.reliefUndergroundCaveCullingHeight";
	private static final String RELIEF_SURFACE_PLAYER_Y_PROPERTY = "pauc.lod.reliefSurfacePlayerY";
	private static final String RELIEF_OVERDRAW_PREVENTION_PROPERTY = "pauc.lod.reliefOverdrawPrevention";
	private static final String RELIEF_SHADER_OVERDRAW_PREVENTION_PROPERTY = "pauc.lod.shaderOverdrawPrevention";
	private static final String RELIEF_FAST_MOVEMENT_OVERDRAW_PROPERTY = "pauc.lod.reduceOverdrawWithFastMovement";
	private static final String RELIEF_WORLD_COMPRESSION_PROPERTY = "pauc.lod.reliefWorldCompression";
	private static final String CLEAR_RENDER_CACHE_ON_GEOMETRY_CHANGE_PROPERTY = "pauc.lod.clearRenderCacheOnGeometryChange";
	private static final String ROUND_HORIZON_FOG_PROPERTY = "pauc.lod.roundHorizonFog";
	private static final String ROUND_HORIZON_FOG_START_RATIO_PROPERTY = "pauc.lod.roundHorizonFogStartRatio";
	private static final String ROUND_HORIZON_FOG_MIN_PROPERTY = "pauc.lod.roundHorizonFogMin";
	private static final String ROUND_HORIZON_FOG_MAX_PROPERTY = "pauc.lod.roundHorizonFogMax";
	private static final String ROUND_HORIZON_FOG_DENSITY_PROPERTY = "pauc.lod.roundHorizonFogDensity";
	private static final float DEFAULT_ROUND_HORIZON_FOG_START_RATIO = 0.82F;
	private static final float DEFAULT_ROUND_HORIZON_FOG_MIN = 0.0F;
	private static final float DEFAULT_ROUND_HORIZON_FOG_MAX = 1.0F;
	private static final float DEFAULT_ROUND_HORIZON_FOG_DENSITY = 1.0F;
	private static final String DH_GRAPHICS_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics";
	private static final String DH_DEBUGGING_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Debugging";
	private static final String DH_FOG_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Fog";
	private static final String DH_QUALITY_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Quality";
	private static final String DH_CULLING_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Culling";
	private static final String DH_GENERIC_RENDERING_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$GenericRendering";
	private static final String DH_WORLD_GENERATOR_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator";
	private static final String DH_MULTI_THREADING_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$MultiThreading";
	private static final String DH_SERVER_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Server";
	private static final String DH_SERVER_EXPERIMENTAL_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Server$Experimental";
	private static final String DH_LOD_BUILDING_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$LodBuilding";
	private static final String DH_LOD_BUILDING_EXPERIMENTAL_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$LodBuilding$Experimental";
	private static final String DH_OVERRIDE_VANILLA_GRAPHICS_FIELD = "overrideVanillaGraphicsSettings";
	private static final String DH_RENDERER_MODE_FIELD = "rendererMode";
	private static final String DH_LOD_CHUNK_RENDER_DISTANCE_FIELD = "lodChunkRenderDistanceRadius";
	private static final String DH_MAX_HORIZONTAL_RESOLUTION_FIELD = "maxHorizontalResolution";
	private static final String DH_HORIZONTAL_QUALITY_FIELD = "horizontalQuality";
	private static final String DH_VERTICAL_QUALITY_FIELD = "verticalQuality";
	private static final String DH_LOD_SHADING_FIELD = "lodShading";
	private static final String DH_TRANSPARENCY_FIELD = "transparency";
	private static final String DH_DITHER_FADE_FIELD = "ditherDhFade";
	private static final String DH_VANILLA_FADE_MODE_FIELD = "vanillaFadeMode";
	private static final String DH_LOD_BIOME_BLENDING_FIELD = "lodBiomeBlending";
	private static final String DH_ENABLE_DH_FOG_FIELD = "enableDhFog";
	private static final String DH_FAR_FOG_START_FIELD = "farFogStart";
	private static final String DH_FAR_FOG_END_FIELD = "farFogEnd";
	private static final String DH_FAR_FOG_MIN_FIELD = "farFogMin";
	private static final String DH_FAR_FOG_MAX_FIELD = "farFogMax";
	private static final String DH_FAR_FOG_DENSITY_FIELD = "farFogDensity";
	private static final String DH_FAR_FOG_FALLOFF_FIELD = "farFogFalloff";
	private static final String DH_FAR_CLIP_FADE_FIELD = "dhFadeFarClipPlane";
	private static final String DH_ENABLE_CLOUD_RENDERING_FIELD = "enableCloudRendering";
	private static final String DH_ENABLE_GENERIC_RENDERING_FIELD = "enableGenericRendering";
	private static final String DH_ENABLE_DISTANT_GENERATION_FIELD = "enableDistantGeneration";
	private static final String DH_DISTANT_GENERATOR_MODE_FIELD = "distantGeneratorMode";
	private static final String DH_GENERATION_REQUEST_RATE_LIMIT_FIELD = "generationRequestRateLimit";
	private static final String DH_MAX_GENERATION_REQUEST_DISTANCE_FIELD = "maxGenerationRequestDistance";
	private static final String DH_ENABLE_N_SIZE_GENERATION_FIELD = "enableNSizedGeneration";
	private static final String DH_UPSAMPLE_LOWER_DETAIL_LODS_FIELD = "upsampleLowerDetailLodsToFillHoles";
	private static final String DH_NUMBER_OF_THREADS_FIELD = "numberOfThreads";
	private static final String DH_THREAD_RUN_TIME_RATIO_FIELD = "threadRunTimeRatio";
	private static final String DH_OVERDRAW_PREVENTION_FIELD = "overdrawPrevention";
	private static final String DH_REDUCE_OVERDRAW_FAST_MOVEMENT_FIELD = "reduceOverdrawWithFastMovement";
	private static final String DH_DISABLE_FRUSTUM_CULLING_FIELD = "disableFrustumCulling";
	private static final String DH_ENABLE_CAVE_CULLING_FIELD = "enableCaveCulling";
	private static final String DH_CAVE_CULLING_HEIGHT_FIELD = "caveCullingHeight";
	private static final String DH_WORLD_COMPRESSION_FIELD = "worldCompression";
	private static final Map<String, SavedCoreConfigValue> SAVED_CORE_CONFIG_VALUES = new ConcurrentHashMap<>();

	private PauCEmbeddedDhBridge() {
	}

	public static void applyLodRange(PauCLodRange range) {
		if (range == null || !range.enabled()) {
			disableLodRendering("lod-inactive");
			return;
		}

		IDhApiConfig configs = DhApi.Delayed.configs;
		if (configs == null) {
			state = BridgeState.unavailable("dh-configs-not-ready");
			if (!loggedWaitingForDh) {
				loggedWaitingForDh = true;
				LOGGER.info("PauC embedded DH bridge is waiting for Distant Horizons config initialization.");
			}
			return;
		}

		try {
			int targetDistance = range.roundHorizonEndChunk();
			RuntimeLodSettings runtimeSettings = RuntimeLodSettings.fastDefaults();
			boolean shaderPackInUse = isShaderPackRuntimeInUse();
			boolean nativeShaderFog = shaderPackInUse && !PauCLodShaderContext.isFallbackActive();
			boolean dhDistanceFog = shouldUseDhDistanceFog(nativeShaderFog);
			configureCoreRuntimeSettings(targetDistance, runtimeSettings);
			clearApiValueIfSet(configs.graphics().fog().drawMode());
			setApiValueIfChanged(configs.graphics().fog().enableDhFog(), dhDistanceFog);
			setApiValueIfChanged(configs.graphics().fog().enableVanillaFog(), !nativeShaderFog);
			setApiValueIfChanged(configs.graphics().fog().disableVanillaFog(), false);
			configureDhFogForPaucRange(range, nativeShaderFog, dhDistanceFog);
			disableDhVanillaGraphicsOverride();
			enableDhFarClipFade();
			enableDhDitherFade();
			disabledRenderingApplied = false;
			if (lastConfiguredTarget != targetDistance) {
				LOGGER.info(
					"PauC embedded DH bridge configured round LOD horizon to {} chunks from player target {} with quality horizontal={}, vertical={}, resolution={}.",
					targetDistance,
					range.lodEndChunk(),
					runtimeSettings.horizontalQuality(),
					runtimeSettings.verticalQuality(),
					runtimeSettings.maxHorizontalResolution()
				);
				LOGGER.info(
					"PauC embedded DH bridge configured fast LOD generation: threads={}, runtimeRatio={}, generator={}, biomeBlend={}, transparency={}.",
					runtimeSettings.threadCount(),
					roundTwoDecimals(runtimeSettings.threadRuntimeRatio()),
					runtimeSettings.generatorMode(),
					runtimeSettings.biomeBlending(),
					runtimeSettings.transparency()
				);
				LOGGER.info("PauC embedded DH bridge active loading policy: {}.", PauCLodClientSettings.describePerformancePolicy());
				LOGGER.info("PauC embedded DH bridge forced DH LOD side shading to {} for shader shadow continuity.", runtimeSettings.lodShading());
				lastConfiguredTarget = targetDistance;
			}
			state = new BridgeState(true, "configured", targetDistance);
		} catch (Throwable throwable) {
			state = BridgeState.unavailable("configure-error:" + throwable.getClass().getSimpleName());
			LOGGER.debug("PauC embedded DH bridge failed to configure Distant Horizons.", throwable);
		}
	}

	public static void reset() {
		state = BridgeState.unavailable("reset");
		lastConfiguredTarget = -1;
		lastRenderGeometrySignature = null;
		lastLoggedDistantCloudPolicy = null;
		disabledRenderingApplied = false;
	}

	public static String describeState() {
		return "embeddedDh[available=" + state.available() + ", status=" + state.status() + ", target=" + state.targetDistanceChunks() + "]";
	}

	private static void disableLodRendering(String status) {
		IDhApiConfig configs = DhApi.Delayed.configs;
		if (configs != null && !disabledRenderingApplied) {
			try {
				clearApiValueIfSet(configs.graphics().fog().drawMode());
				clearApiValueIfSet(configs.graphics().fog().enableDhFog());
				clearApiValueIfSet(configs.graphics().fog().enableVanillaFog());
				clearApiValueIfSet(configs.graphics().fog().disableVanillaFog());
				restoreDhCoreConfigValues();
				setDhCoreConfigApiValue(DH_GRAPHICS_CONFIG_CLASS, DH_OVERRIDE_VANILLA_GRAPHICS_FIELD, null);
				setDhCoreConfigApiValue(DH_QUALITY_CONFIG_CLASS, DH_FAR_CLIP_FADE_FIELD, null);
				setDhCoreConfigApiValue(DH_QUALITY_CONFIG_CLASS, DH_DITHER_FADE_FIELD, null);
				disabledRenderingApplied = true;
			} catch (Throwable throwable) {
				LOGGER.debug("PauC embedded DH bridge failed to disable Distant Horizons rendering.", throwable);
			}
		}

		if (lastConfiguredTarget != -1) {
			LOGGER.info("PauC embedded DH bridge disabled LOD rendering: {}.", status);
		}
		lastConfiguredTarget = -1;
		state = BridgeState.unavailable(status);
	}

	private static void configureCoreRuntimeSettings(int targetDistance, RuntimeLodSettings runtimeSettings) {
		setDhCoreConfigValueWithoutSaving(DH_DEBUGGING_CONFIG_CLASS, DH_RENDERER_MODE_FIELD, EDhApiRendererMode.DEFAULT);
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_LOD_CHUNK_RENDER_DISTANCE_FIELD, targetDistance);
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_MAX_HORIZONTAL_RESOLUTION_FIELD, runtimeSettings.maxHorizontalResolution());
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_HORIZONTAL_QUALITY_FIELD, runtimeSettings.horizontalQuality());
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_VERTICAL_QUALITY_FIELD, runtimeSettings.verticalQuality());
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_LOD_SHADING_FIELD, runtimeSettings.lodShading());
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_TRANSPARENCY_FIELD, runtimeSettings.transparency());
		setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_LOD_BIOME_BLENDING_FIELD, runtimeSettings.biomeBlending());
		configureSeamlessLodTransition();
		configureReliefGeometry();
		configureDistantCloudRendering();
		setDhCoreConfigValueWithoutSaving(DH_WORLD_GENERATOR_CONFIG_CLASS, DH_ENABLE_DISTANT_GENERATION_FIELD, Boolean.TRUE);
		setDhCoreConfigValueWithoutSaving(DH_WORLD_GENERATOR_CONFIG_CLASS, DH_DISTANT_GENERATOR_MODE_FIELD, runtimeSettings.generatorMode());
		configureGenerationFillPolicy(targetDistance);
		setDhCoreConfigValueWithoutSaving(DH_MULTI_THREADING_CONFIG_CLASS, DH_NUMBER_OF_THREADS_FIELD, runtimeSettings.threadCount());
		setDhCoreConfigValueWithoutSaving(DH_MULTI_THREADING_CONFIG_CLASS, DH_THREAD_RUN_TIME_RATIO_FIELD, runtimeSettings.threadRuntimeRatio());
		clearRenderCacheIfGeometryChanged(runtimeSettings);
	}

	private static void clearRenderCacheIfGeometryChanged(RuntimeLodSettings runtimeSettings) {
		LodRenderGeometrySignature signature = LodRenderGeometrySignature.from(runtimeSettings);
		LodRenderGeometrySignature previousSignature = lastRenderGeometrySignature;
		lastRenderGeometrySignature = signature;
		if (previousSignature == null || previousSignature.equals(signature) || DhApi.Delayed.renderProxy == null) {
			return;
		}
		if (!Boolean.parseBoolean(System.getProperty(CLEAR_RENDER_CACHE_ON_GEOMETRY_CHANGE_PROPERTY, "false"))) {
			LOGGER.debug(
				"PauC detected a LOD geometry mode change without clearing DH render cache: {} -> {}.",
				previousSignature.describe(),
				signature.describe()
			);
			return;
		}

		try {
			DhApi.Delayed.renderProxy.clearRenderDataCache();
			LOGGER.info(
				"PauC embedded DH bridge cleared DH render cache after LOD geometry mode changed: {} -> {}.",
				previousSignature.describe(),
				signature.describe()
			);
		} catch (Throwable throwable) {
			LOGGER.debug("PauC embedded DH bridge could not clear DH render cache after a LOD geometry mode change.", throwable);
		}
	}

	private static void configureGenerationFillPolicy(int targetDistance) {
		int requestDistanceBlocks = clampInt((targetDistance + 2) * 16, 256, 4096);
		int requestRateLimit = PauCLodClientSettings.generationRequestRateLimit();
		setDhCoreConfigValueWithoutSaving(DH_SERVER_CONFIG_CLASS, DH_GENERATION_REQUEST_RATE_LIMIT_FIELD, requestRateLimit);
		setDhCoreConfigValueWithoutSaving(DH_SERVER_CONFIG_CLASS, DH_MAX_GENERATION_REQUEST_DISTANCE_FIELD, requestDistanceBlocks);
		setDhCoreConfigValueWithoutSaving(DH_SERVER_EXPERIMENTAL_CONFIG_CLASS, DH_ENABLE_N_SIZE_GENERATION_FIELD, PauCLodClientSettings.enableNSizeGeneration());
		setDhCoreConfigValueWithoutSaving(DH_LOD_BUILDING_EXPERIMENTAL_CONFIG_CLASS, DH_UPSAMPLE_LOWER_DETAIL_LODS_FIELD, PauCLodClientSettings.fillLodHoles());
		if (!loggedRoundHorizonGenerationPolicy) {
			loggedRoundHorizonGenerationPolicy = true;
			LOGGER.info(
				"PauC embedded DH bridge keeps the round LOD horizon filled: renderRadius={} chunks, generationRequest={} blocks.",
				targetDistance,
				requestDistanceBlocks
			);
		}
		configureReliefCompression();
	}

	private static void configureDistantCloudRendering() {
		boolean cloudLods = PauCLodRenderCulling.shouldEnableLodCloudRendering(PauCLodClientSettings.isLodCloudsEnabled());
		setDhCoreConfigValueWithoutSaving(DH_GENERIC_RENDERING_CONFIG_CLASS, DH_ENABLE_GENERIC_RENDERING_FIELD, cloudLods);
		setDhCoreConfigValueWithoutSaving(DH_GENERIC_RENDERING_CONFIG_CLASS, DH_ENABLE_CLOUD_RENDERING_FIELD, cloudLods);
		if (!Boolean.valueOf(cloudLods).equals(lastLoggedDistantCloudPolicy)) {
			lastLoggedDistantCloudPolicy = cloudLods;
			LOGGER.info("PauC embedded DH bridge configured distant cloud LOD rendering: enabled={}.", cloudLods);
		}
	}

	private static void configureSeamlessLodTransition() {
		boolean shaderRuntime = isShaderPackRuntimeInUse();
		float overdrawPrevention = shaderRuntime
			? (float) readDouble(RELIEF_SHADER_OVERDRAW_PREVENTION_PROPERTY, 0.96D, -1.0D, 1.0D)
			: (float) readDouble(RELIEF_OVERDRAW_PREVENTION_PROPERTY, 0.88D, -1.0D, 1.0D);
		boolean reduceOverdrawWithFastMovement = readBoolean(RELIEF_FAST_MOVEMENT_OVERDRAW_PROPERTY, shaderRuntime);
		boolean configured = setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_VANILLA_FADE_MODE_FIELD, EDhApiMcRenderingFadeMode.NONE)
			& setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_OVERDRAW_PREVENTION_FIELD, overdrawPrevention)
			& setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_REDUCE_OVERDRAW_FAST_MOVEMENT_FIELD, reduceOverdrawWithFastMovement);
		boolean frustumConfigured = setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_DISABLE_FRUSTUM_CULLING_FIELD, Boolean.FALSE);

		if ((configured || frustumConfigured) && !loggedSeamlessTransitionOverride) {
			loggedSeamlessTransitionOverride = true;
			LOGGER.info(
				"PauC embedded DH bridge configured strict vanilla-to-LOD boundary: vanillaFade=NONE, overdraw={}, fastMovementOverdraw={}, frustumCulling=true, shaderRuntime={}.",
				overdrawPrevention,
				reduceOverdrawWithFastMovement,
				shaderRuntime
			);
		}
	}

	private static void configureReliefGeometry() {
		boolean caveCulling = Boolean.parseBoolean(System.getProperty(RELIEF_CAVE_CULLING_PROPERTY, "true"));
		int caveCullingHeight = readInt(RELIEF_CAVE_CULLING_HEIGHT_PROPERTY, defaultCaveCullingHeight(), -4096, 4096);
		boolean configured = setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_ENABLE_CAVE_CULLING_FIELD, caveCulling)
			& setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_CAVE_CULLING_HEIGHT_FIELD, caveCullingHeight);

		if (configured && !loggedReliefGeometryOverride) {
			loggedReliefGeometryOverride = true;
			LOGGER.info("PauC embedded DH bridge configured relief geometry: caveCulling={}, caveCullingHeight={}, shaderRuntime={}.", caveCulling, caveCullingHeight, isShaderPackRuntimeInUse());
		}
	}

	private static void configureReliefCompression() {
		if (isShaderPackRuntimeInUse()) {
			return;
		}

		EDhApiWorldCompressionMode compressionMode = readEnum(
			RELIEF_WORLD_COMPRESSION_PROPERTY,
			EDhApiWorldCompressionMode.class,
			EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS
		);
		if (setDhCoreConfigValueWithoutSaving(DH_LOD_BUILDING_CONFIG_CLASS, DH_WORLD_COMPRESSION_FIELD, compressionMode) && !loggedReliefCompressionOverride) {
			loggedReliefCompressionOverride = true;
			LOGGER.info("PauC embedded DH bridge configured shader-off relief LOD compression: {}.", compressionMode);
		}
	}

	private static void enableDhFarClipFade() {
		if (setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_FAR_CLIP_FADE_FIELD, Boolean.TRUE) && !loggedFarClipFadeOverride) {
			loggedFarClipFadeOverride = true;
			LOGGER.info("PauC embedded DH bridge enabled DH far-clip fade for a native-looking LOD horizon.");
		}
	}

	private static void enableDhDitherFade() {
		if (setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_DITHER_FADE_FIELD, Boolean.TRUE) && !loggedDitherFadeOverride) {
			loggedDitherFadeOverride = true;
			LOGGER.info("PauC embedded DH bridge enabled DH dither fade for a smoother LOD horizon.");
		}
	}

	private static void disableDhVanillaGraphicsOverride() {
		if (setDhCoreConfigValueWithoutSaving(DH_GRAPHICS_CONFIG_CLASS, DH_OVERRIDE_VANILLA_GRAPHICS_FIELD, Boolean.FALSE) && !loggedVanillaGraphicsOverride) {
			loggedVanillaGraphicsOverride = true;
			LOGGER.info("PauC embedded DH bridge disabled DH vanilla graphics overrides.");
		}
	}

	private static <T> void setApiValueIfChanged(IDhApiConfigValue<T> configValue, T value) {
		T currentValue = configValue.getApiValue();
		if (valuesEqual(currentValue, value)) {
			return;
		}
		configValue.setValue(value);
	}

	private static <T> void clearApiValueIfSet(IDhApiConfigValue<T> configValue) {
		if (configValue.getApiValue() != null) {
			configValue.clearValue();
		}
	}

	private static void configureDhFogForPaucRange(PauCLodRange range, boolean nativeShaderFog, boolean dhDistanceFog) {
		float farFogStart = dhDistanceFog
			? readFloat(ROUND_HORIZON_FOG_START_RATIO_PROPERTY, DEFAULT_ROUND_HORIZON_FOG_START_RATIO, 0.05F, 0.99F)
			: 1.0F;
		float farFogMin = dhDistanceFog
			? readFloat(ROUND_HORIZON_FOG_MIN_PROPERTY, DEFAULT_ROUND_HORIZON_FOG_MIN, 0.0F, 1.0F)
			: 0.0F;
		float farFogMax = dhDistanceFog
			? readFloat(ROUND_HORIZON_FOG_MAX_PROPERTY, DEFAULT_ROUND_HORIZON_FOG_MAX, farFogMin, 1.0F)
			: 0.0F;
		float farFogDensity = dhDistanceFog
			? readFloat(ROUND_HORIZON_FOG_DENSITY_PROPERTY, DEFAULT_ROUND_HORIZON_FOG_DENSITY, 0.0F, 1.0F)
			: 0.0F;
		boolean configured = setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_ENABLE_DH_FOG_FIELD, dhDistanceFog)
			& setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_FAR_FOG_START_FIELD, farFogStart)
			& setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_FAR_FOG_END_FIELD, 1.0F)
			& setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_FAR_FOG_MIN_FIELD, farFogMin)
			& setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_FAR_FOG_MAX_FIELD, farFogMax)
			& setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_FAR_FOG_DENSITY_FIELD, farFogDensity)
			& setDhCoreConfigValueWithoutSaving(DH_FOG_CONFIG_CLASS, DH_FAR_FOG_FALLOFF_FIELD, EDhApiFogFalloff.LINEAR);

		if (configured && !loggedDhFogConfigOverride) {
			loggedDhFogConfigOverride = true;
			LOGGER.info(
				"PauC embedded DH bridge configured round LOD distance fog: enabled={}, startRatio={}, endRatio=1.0, max={}, density={}, shaderManaged={}, {}",
				dhDistanceFog,
				roundThreeDecimals(farFogStart),
				roundThreeDecimals(farFogMax),
				roundThreeDecimals(farFogDensity),
				nativeShaderFog,
				range.describe()
			);
		}
	}

	private static boolean shouldUseDhDistanceFog(boolean nativeShaderFog) {
		return !nativeShaderFog && readBoolean(ROUND_HORIZON_FOG_PROPERTY, true);
	}

	private static boolean setDhCoreConfigApiValue(String configClassName, String fieldName, Object value) {
		try {
			Class<?> configClass = Class.forName(configClassName);
			java.lang.reflect.Field field = configClass.getField(fieldName);
			Object configEntry = field.get(null);
			if (configEntry == null) {
				return false;
			}

			Object currentValue = getDhCoreConfigApiValue(configEntry);
			if (valuesEqual(currentValue, value)) {
				return true;
			}
			configEntry.getClass().getMethod("setApiValue", Object.class).invoke(configEntry, value);
			return true;
		} catch (ReflectiveOperationException | LinkageError throwable) {
			if (!loggedMissingCoreConfigOverride) {
				loggedMissingCoreConfigOverride = true;
				LOGGER.warn("PauC embedded DH bridge could not override DH core config {}.{}; DH version may expose a different config path.", configClassName, fieldName, throwable);
			}
			return false;
		}
	}

	private static boolean setDhCoreConfigValueWithoutSaving(String configClassName, String fieldName, Object value) {
		try {
			Object configEntry = dhCoreConfigEntry(configClassName, fieldName);
			String key = configClassName + "#" + fieldName;
			SAVED_CORE_CONFIG_VALUES.computeIfAbsent(
				key,
				ignored -> new SavedCoreConfigValue(configClassName, fieldName, getDhCoreConfigTrueValue(configEntry))
			);
			Object currentValue = getDhCoreConfigTrueValue(configEntry);
			if (valuesEqual(currentValue, value)) {
				return true;
			}
			configEntry.getClass().getMethod("setWithoutFiringEvents", Object.class).invoke(configEntry, value);
			return true;
		} catch (ReflectiveOperationException | LinkageError throwable) {
			if (!loggedMissingCoreConfigOverride) {
				loggedMissingCoreConfigOverride = true;
				LOGGER.warn("PauC embedded DH bridge could not set DH core config {}.{}; DH version may expose a different config path.", configClassName, fieldName, throwable);
			}
			return false;
		}
	}

	private static void restoreDhCoreConfigValues() {
		for (SavedCoreConfigValue savedValue : SAVED_CORE_CONFIG_VALUES.values()) {
			try {
				Object configEntry = dhCoreConfigEntry(savedValue.configClassName(), savedValue.fieldName());
				configEntry.getClass().getMethod("setWithoutFiringEvents", Object.class).invoke(configEntry, savedValue.value());
			} catch (ReflectiveOperationException | LinkageError throwable) {
				LOGGER.debug("PauC embedded DH bridge could not restore DH core config {}.{}.", savedValue.configClassName(), savedValue.fieldName(), throwable);
			}
		}
		SAVED_CORE_CONFIG_VALUES.clear();
		loggedDhFogConfigOverride = false;
		loggedSeamlessTransitionOverride = false;
	}

	private static Object dhCoreConfigEntry(String configClassName, String fieldName) throws ReflectiveOperationException {
		Class<?> configClass = Class.forName(configClassName);
		java.lang.reflect.Field field = configClass.getField(fieldName);
		Object configEntry = field.get(null);
		if (configEntry == null) {
			throw new NoSuchFieldException(configClassName + "." + fieldName);
		}
		return configEntry;
	}

	private static Object getDhCoreConfigTrueValue(Object configEntry) {
		try {
			return configEntry.getClass().getMethod("getTrueValue").invoke(configEntry);
		} catch (ReflectiveOperationException throwable) {
			return null;
		}
	}

	private static Object getDhCoreConfigApiValue(Object configEntry) {
		try {
			return configEntry.getClass().getMethod("getApiValue").invoke(configEntry);
		} catch (ReflectiveOperationException throwable) {
			return null;
		}
	}

	private static boolean valuesEqual(Object currentValue, Object newValue) {
		if (currentValue instanceof Number currentNumber && newValue instanceof Number newNumber) {
			return Math.abs(currentNumber.doubleValue() - newNumber.doubleValue()) < 0.0001D;
		}
		return java.util.Objects.equals(currentValue, newValue);
	}

	private static boolean isShaderPackRuntimeInUse() {
		try {
			return IrisApi.getInstance().isShaderPackInUse();
		} catch (RuntimeException | LinkageError exception) {
			LOGGER.debug("PauC could not query Iris shader state while configuring embedded DH.", exception);
			return PauCLodShaderContext.isShaderPackInUse();
		}
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float roundThreeDecimals(float value) {
		return Math.round(value * 1000.0F) / 1000.0F;
	}

	private static double roundTwoDecimals(double value) {
		return Math.round(value * 100.0D) / 100.0D;
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clampInt(fallback, min, max);
		}

		try {
			return clampInt(Integer.parseInt(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clampInt(fallback, min, max);
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Float.parseFloat(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clampDouble(fallback, min, max);
		}

		try {
			return clampDouble(Double.parseDouble(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clampDouble(fallback, min, max);
		}
	}

	private static <T extends Enum<T>> T readEnum(String key, Class<T> enumType, T fallback) {
		String rawValue = System.getProperty(key);
		if (rawValue == null || rawValue.isBlank()) {
			return fallback;
		}

		try {
			return Enum.valueOf(enumType, rawValue.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return fallback;
		}
	}

	private static int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clampDouble(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private record BridgeState(boolean available, String status, int targetDistanceChunks) {
		private static BridgeState unavailable(String status) {
			return new BridgeState(false, status, -1);
		}
	}

	private record RuntimeLodSettings(
		EDhApiMaxHorizontalResolution maxHorizontalResolution,
		EDhApiHorizontalQuality horizontalQuality,
		EDhApiVerticalQuality verticalQuality,
		EDhApiLodShading lodShading,
		EDhApiTransparency transparency,
		EDhApiDistantGeneratorMode generatorMode,
		int threadCount,
		double threadRuntimeRatio,
		int biomeBlending
	) {
		private static RuntimeLodSettings fastDefaults() {
			int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
			int defaultThreads = clampInt((int) Math.ceil(processors * 0.375D), 2, Math.max(2, Math.min(6, processors)));
			return new RuntimeLodSettings(
				defaultMaxHorizontalResolution(),
				defaultHorizontalQuality(),
				defaultVerticalQuality(),
				EDhApiLodShading.ENABLED,
				readEnum(FAST_TRANSPARENCY_PROPERTY, EDhApiTransparency.class, defaultTransparency()),
				defaultRuntimeGeneratorMode(),
				readInt(FAST_THREADS_PROPERTY, defaultThreads, 1, Math.max(1, Math.min(8, processors))),
				readDouble(FAST_RUNTIME_RATIO_PROPERTY, PauCLodShaderRuntime.generationThreadRuntimeRatio(0.55D), 0.05D, 1.0D),
				readInt(FAST_BIOME_BLEND_PROPERTY, 0, 0, 3)
			);
		}

		private static EDhApiMaxHorizontalResolution defaultMaxHorizontalResolution() {
			EDhApiMaxHorizontalResolution configured = readEnum(
				FAST_MAX_RESOLUTION_PROPERTY,
				EDhApiMaxHorizontalResolution.class,
				EDhApiMaxHorizontalResolution.TWO_BLOCKS
			);
			return readEnum(DYNAMIC_MAX_RESOLUTION_PROPERTY, EDhApiMaxHorizontalResolution.class, configured);
		}

		private static EDhApiVerticalQuality defaultVerticalQuality() {
			EDhApiVerticalQuality configured = readEnum(
				FAST_VERTICAL_QUALITY_PROPERTY,
				EDhApiVerticalQuality.class,
				!isShaderPackRuntimeInUse() ? EDhApiVerticalQuality.MEDIUM : EDhApiVerticalQuality.LOW
			);
			EDhApiVerticalQuality dynamic = readEnum(DYNAMIC_VERTICAL_QUALITY_PROPERTY, EDhApiVerticalQuality.class, configured);
			return PauCClientSurfaceLodMode.adjustVerticalQuality(dynamic);
		}

		private static EDhApiHorizontalQuality defaultHorizontalQuality() {
			EDhApiHorizontalQuality configured = readEnum(
				FAST_HORIZONTAL_QUALITY_PROPERTY,
				EDhApiHorizontalQuality.class,
				EDhApiHorizontalQuality.LOW
			);
			return readEnum(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY, EDhApiHorizontalQuality.class, configured);
		}

		private static EDhApiDistantGeneratorMode defaultGeneratorMode() {
			if (Boolean.parseBoolean(System.getProperty(DISTANT_STRUCTURES_PROPERTY, "false"))) {
				return EDhApiDistantGeneratorMode.INTERNAL_SERVER;
			}

			return EDhApiDistantGeneratorMode.SURFACE;
		}

		private static EDhApiDistantGeneratorMode defaultRuntimeGeneratorMode() {
			EDhApiDistantGeneratorMode configured = readEnum(FAST_GENERATOR_MODE_PROPERTY, EDhApiDistantGeneratorMode.class, defaultGeneratorMode());
			return PauCClientSurfaceLodMode.adjustGeneratorMode(configured);
		}

		private static EDhApiTransparency defaultTransparency() {
			if (!isShaderPackRuntimeInUse()) {
				return EDhApiTransparency.FAKE;
			}
			if (PauCLodShaderContext.isFallbackActive()) {
				return EDhApiTransparency.COMPLETE;
			}

			return switch (PauCLodShaderProfiles.currentFamily()) {
				case BLISS, BSL, COMPLEMENTARY, PHOTON, RETHINKING, SOLAS -> EDhApiTransparency.COMPLETE;
				case GENERIC -> EDhApiTransparency.FAKE;
			};
		}
	}

	private static int defaultCaveCullingHeight() {
		Minecraft minecraft = Minecraft.getInstance();
		double playerY = minecraft != null && minecraft.player != null ? minecraft.player.getY() : 72.0D;
		int surfacePlayerY = readInt(RELIEF_SURFACE_PLAYER_Y_PROPERTY, 60, -64, 320);
		if (playerY < surfacePlayerY) {
			return readInt(RELIEF_UNDERGROUND_CAVE_CULLING_HEIGHT_PROPERTY, 32, -4096, 4096);
		}
		int fallbackHeight = readInt(RELIEF_SURFACE_CAVE_CULLING_HEIGHT_PROPERTY, 60, -4096, 4096);
		return PauCClientSurfaceLodMode.surfaceCaveCullingHeight(fallbackHeight);
	}

	private record LodRenderGeometrySignature(
		EDhApiMaxHorizontalResolution maxHorizontalResolution,
		EDhApiHorizontalQuality horizontalQuality,
		EDhApiVerticalQuality verticalQuality,
		EDhApiLodShading lodShading,
		EDhApiTransparency transparency
	) {
		private static LodRenderGeometrySignature from(RuntimeLodSettings settings) {
			return new LodRenderGeometrySignature(
				settings.maxHorizontalResolution(),
				settings.horizontalQuality(),
				settings.verticalQuality(),
				settings.lodShading(),
				settings.transparency()
			);
		}

		private String describe() {
			return "resolution="
				+ maxHorizontalResolution
				+ ", horizontal="
				+ horizontalQuality
				+ ", vertical="
				+ verticalQuality
				+ ", shading="
				+ lodShading
				+ ", transparency="
				+ transparency;
		}
	}

	private record SavedCoreConfigValue(String configClassName, String fieldName, Object value) {
	}
}
