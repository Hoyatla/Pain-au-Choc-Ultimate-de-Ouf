package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import fr.hoyatla.pauc.lod.PauCFrameSpikeAbsorber;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodNearClipOverride;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodRenderCulling;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import fr.hoyatla.pauc.lod.PauCTerrainGeneratorDetector;
import fr.hoyatla.pauc.lodstore.PauCLodSwapGuard;
import fr.hoyatla.pauc.platform.forge.diagnostics.PauCLodReloadDiagnostics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
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
	private static volatile boolean loggedDirectGpuRendererOverride;
	private static volatile boolean loggedRoundHorizonGenerationPolicy;
	private static volatile String lastLoggedGenerationPolicy = "";
	private static volatile String lastLoggedSeamlessTransitionPolicy = "";
	private static volatile Boolean lastLoggedDistantCloudPolicy;
	private static volatile Boolean lastLoggedDistantCloudShaderFallback;
	private static volatile boolean loggedMissingCoreConfigOverride;
	private static volatile boolean disabledRenderingApplied;
	private static volatile DhGpuUploadState cachedGpuUploadState;
	private static volatile long cachedGpuUploadStateAtMillis;
	private static final String FAST_THREADS_PROPERTY = "pauc.lod.generationThreads";
	private static final String FAST_RUNTIME_RATIO_PROPERTY = "pauc.lod.generationRuntimeRatio";
	private static final String FAST_GENERATOR_MODE_PROPERTY = "pauc.lod.generationMode";
	private static final String FILL_GENERATOR_MODE_PROPERTY = "pauc.lod.fillGenerationMode";
	private static final String FAST_MAX_RESOLUTION_PROPERTY = "pauc.lod.maxHorizontalResolution";
	private static final String FAST_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.horizontalQuality";
	private static final String FAST_VERTICAL_QUALITY_PROPERTY = "pauc.lod.verticalQuality";
	private static final String DYNAMIC_MAX_RESOLUTION_PROPERTY = "pauc.lod.dynamicMaxHorizontalResolution";
	private static final String DYNAMIC_HORIZONTAL_QUALITY_PROPERTY = "pauc.lod.dynamicHorizontalQuality";
	private static final String DYNAMIC_VERTICAL_QUALITY_PROPERTY = "pauc.lod.dynamicVerticalQuality";
	private static final String FAST_BIOME_BLEND_PROPERTY = "pauc.lod.biomeBlending";
	private static final String FAST_TRANSPARENCY_PROPERTY = "pauc.lod.transparency";
	private static final String DISTANT_STRUCTURES_PROPERTY = "pauc.lod.structures";
	private static final String ADAPTIVE_DISTANT_STRUCTURES_PROPERTY = "pauc.lod.adaptiveStructures";
	private static final String GENERIC_RENDERING_PROPERTY = "pauc.lod.genericRendering";
	private static final String SHADER_FALLBACK_GENERIC_RENDERING_PROPERTY = "pauc.lod.shaderFallbackGenericRendering";
	private static final String RELIEF_CAVE_CULLING_PROPERTY = "pauc.lod.reliefCaveCulling";
	private static final String RELIEF_CAVE_CULLING_HEIGHT_PROPERTY = "pauc.lod.reliefCaveCullingHeight";
	private static final String RELIEF_SURFACE_CAVE_CULLING_HEIGHT_PROPERTY = "pauc.lod.reliefSurfaceCaveCullingHeight";
	private static final String RELIEF_UNDERGROUND_CAVE_CULLING_HEIGHT_PROPERTY = "pauc.lod.reliefUndergroundCaveCullingHeight";
	private static final String RELIEF_SURFACE_PLAYER_Y_PROPERTY = "pauc.lod.reliefSurfacePlayerY";
	private static final String RELIEF_OVERDRAW_PREVENTION_PROPERTY = "pauc.lod.reliefOverdrawPrevention";
	private static final String SHADER_OVERDRAW_PREVENTION_PROPERTY = "pauc.lod.shaderOverdrawPrevention";
	private static final String SHADER_FALLBACK_OVERDRAW_PREVENTION_PROPERTY = "pauc.lod.shaderFallbackOverdrawPrevention";
	private static final String SHADER_VANILLA_FADE_MODE_PROPERTY = "pauc.lod.shaderVanillaFadeMode";
	private static final String SHADER_FALLBACK_VANILLA_FADE_MODE_PROPERTY = "pauc.lod.shaderFallbackVanillaFadeMode";
	private static final String RELIEF_WORLD_COMPRESSION_PROPERTY = "pauc.lod.reliefWorldCompression";
	private static final String CLEAR_RENDER_CACHE_ON_GEOMETRY_CHANGE_PROPERTY = "pauc.lod.clearRenderCacheOnGeometryChange";
	private static final String ROUND_HORIZON_FOG_PROPERTY = "pauc.lod.roundHorizonFog";
	private static final String ROUND_HORIZON_FOG_START_RATIO_PROPERTY = "pauc.lod.roundHorizonFogStartRatio";
	private static final String ROUND_HORIZON_FOG_MIN_PROPERTY = "pauc.lod.roundHorizonFogMin";
	private static final String ROUND_HORIZON_FOG_MAX_PROPERTY = "pauc.lod.roundHorizonFogMax";
	private static final String ROUND_HORIZON_FOG_DENSITY_PROPERTY = "pauc.lod.roundHorizonFogDensity";
	private static final String DIRECT_GPU_OPENGL_RENDERER_PROPERTY = "pauc.lod.directGpuOpenGlRenderer";
	private static final String DIRECT_GPU_UPLOAD_STATE_CACHE_MS_PROPERTY = "pauc.lod.directGpuUploadStateCacheMs";
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
	private static final String DH_GRAPHICS_EXPERIMENTAL_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Experimental";
	private static final String DH_WORLD_GENERATOR_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator";
	private static final String DH_WARNING_CONFIG_CLASS = "com.seibel.distanthorizons.core.config.Config$Common$Logging$Warning";
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
	private static final String DH_SHOW_UPDATE_QUEUE_OVERLOADED_CHAT_WARNING_FIELD = "showUpdateQueueOverloadedChatWarning";
	private static final String DH_SHOW_SLOW_WORLDGEN_SETTING_WARNINGS_FIELD = "showSlowWorldGenSettingWarnings";
	private static final String DH_GENERATION_REQUEST_RATE_LIMIT_FIELD = "generationRequestRateLimit";
	private static final String DH_MAX_GENERATION_REQUEST_DISTANCE_FIELD = "maxGenerationRequestDistance";
	private static final String DH_ENABLE_N_SIZE_GENERATION_FIELD = "enableNSizedGeneration";
	private static final String DH_UPSAMPLE_LOWER_DETAIL_LODS_FIELD = "upsampleLowerDetailLodsToFillHoles";
	private static final String DH_NUMBER_OF_THREADS_FIELD = "numberOfThreads";
	private static final String DH_THREAD_RUN_TIME_RATIO_FIELD = "threadRunTimeRatio";
	private static final String DH_OVERDRAW_PREVENTION_FIELD = "overdrawPrevention";
	private static final String DH_REDUCE_OVERDRAW_FAST_MOVEMENT_FIELD = "reduceOverdrawWithFastMovement";
	private static final String DH_ENABLE_CAVE_CULLING_FIELD = "enableCaveCulling";
	private static final String DH_CAVE_CULLING_HEIGHT_FIELD = "caveCullingHeight";
	private static final String DH_WORLD_COMPRESSION_FIELD = "worldCompression";
	private static final String DH_RENDERING_API_FIELD = "renderingApi";
	private static final String COARSE_FIRST_FILL_PROPERTY = "pauc.lod.coarseFirstFill";
	private static final String COARSE_FILL_REQUEST_RATE_PROPERTY = "pauc.lod.coarseFillRequestRate";
	private static final String FOG_PRELOAD_EXTRA_CHUNKS_PROPERTY = "pauc.lod.fogPreloadGenerationExtraChunks";
	private static final String MAX_GENERATION_REQUEST_BLOCKS_PROPERTY = "pauc.lod.maxGenerationRequestBlocks";
	private static final String COARSE_FILL_RENDER_REFRESH_PROPERTY = "pauc.lod.coarseFillRenderRefresh";
	private static final String COARSE_FILL_REFRESH_DURING_TRAVEL_PROPERTY = "pauc.lod.coarseFillRefreshDuringTravel";
	private static final String COARSE_FILL_REFRESH_COOLDOWN_PROPERTY = "pauc.lod.coarseFillRefreshCooldownMs";
	private static final String COARSE_FILL_REFRESH_MIN_COVERAGE_PROPERTY = "pauc.lod.coarseFillRefreshMinCoverageRatio";
	private static final String COARSE_FILL_ALLOW_GLOBAL_CACHE_CLEAR_PROPERTY = "pauc.lod.coarseFillAllowGlobalRenderCacheClear";
	private static final String DEFER_RENDER_CACHE_CLEAR_DURING_FILL_PROPERTY = "pauc.lod.deferRenderCacheClearDuringFill";
	private static final String DEFER_RENDER_CACHE_CLEAR_LOG_COOLDOWN_PROPERTY = "pauc.lod.deferRenderCacheClearLogCooldownMs";
	private static final String KEEP_RENDER_CACHE_ON_QUALITY_CHANGE_PROPERTY = "pauc.lod.keepRenderCacheOnQualityChange";
	private static final String KEEP_RENDER_CACHE_ON_SHADER_PRESENTATION_CHANGE_PROPERTY = "pauc.lod.keepRenderCacheOnShaderPresentationChange";
	private static final String CLEAR_RENDER_CACHE_ON_SHADER_RUNTIME_CHANGE_PROPERTY = "pauc.lod.clearRenderCacheOnShaderRuntimeChange";
	private static final String PRESENTATION_SIGNATURE_STABLE_TICKS_PROPERTY = "pauc.lod.presentationSignatureStableTicks";
	private static final String FPS_PRESSURE_WORKER_YIELD_PROPERTY = "pauc.lod.fpsPressureWorkerYield";
	private static final String FPS_PRESSURE_WORKER_YIELD_FLOOR_PROPERTY = "pauc.lod.fpsPressureWorkerYieldFloor";
	private static final String FPS_PRESSURE_WORKER_YIELD_QUIET_MS_PROPERTY = "pauc.lod.fpsPressureWorkerYieldQuietMs";
	private static final String VANILLA_FIDELITY_RING_PROPERTY = "pauc.lod.vanillaFidelityRing";
	private static final String VANILLA_FIDELITY_MIN_TARGET_PROPERTY = "pauc.lod.vanillaFidelityMinTarget";
	private static final String MENU_PREGEN_BOOST_PROPERTY = "pauc.lod.menuPregenBoost";
	private static final String PRESENTATION_SIGNATURE_HOLD_MS_PROPERTY = "pauc.lod.presentationSignatureHoldMs";
	private static final Map<String, SavedCoreConfigValue> SAVED_CORE_CONFIG_VALUES = new ConcurrentHashMap<>();
	private static volatile long lastCoarseFillRenderRefreshAtMillis;
	private static volatile long lastDeferredRenderCacheClearLogAtMillis;
	private static volatile int coarseFillRenderRefreshes;
	private static volatile boolean workerYieldEngaged;
	private static volatile long workerYieldLastAbsorbMs;
	private static volatile double workerYieldLastScale = 1.0D;
	private static volatile long workerYieldLastLogMs;
	private static volatile boolean vanillaFidelityRingLogged;
	private static volatile long menuPregenBoostLastLogMs;
	private static volatile RuntimeLodSettings lastStableRuntimeSettings;
	private static volatile RuntimeLodSettings pendingRuntimeSettings;
	private static volatile int pendingRuntimeSettingsTicks;
	private static volatile long pendingRuntimeSettingsSinceMillis;

	private PauCEmbeddedDhBridge() {
	}

	public static void applyLodRange(PauCLodRange range) {
		// Standalone safety: without the external DH mod, no code path below may run (it resolves com.seibel).
		if (!fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			state = BridgeState.unavailable("dh-not-installed");
			return;
		}
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
			RuntimeLodSettings runtimeSettings = stabilizePresentationRuntimeSettings(RuntimeLodSettings.fastDefaults());
			boolean shaderPackInUse = isShaderPackRuntimeInUse();
			boolean nativeShaderFog = shouldTreatShaderFogAsAuthoritative(shaderPackInUse);
			boolean dhDistanceFog = shouldUseDhDistanceFog(nativeShaderFog);
			GenerationFillPolicyState fillPolicyState = configureCoreRuntimeSettings(targetDistance, runtimeSettings);
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
					"PauC embedded DH bridge configured round LOD horizon to {} chunks from player extra target {} (absolute {}) with quality horizontal={}, vertical={}, resolution={}.",
					targetDistance,
					range.configuredExtraDistanceChunks(),
					range.lodEndChunk(),
					runtimeSettings.horizontalQuality(),
					runtimeSettings.verticalQuality(),
					runtimeSettings.maxHorizontalResolution()
				);
				LOGGER.info(
					"PauC embedded DH bridge configured fast LOD generation: threads={}, runtimeRatio={}, generator={}, terrain={}, biomeBlend={}, transparency={}.",
					runtimeSettings.threadCount(),
					roundTwoDecimals(runtimeSettings.threadRuntimeRatio()),
					runtimeSettings.generatorMode(),
					PauCTerrainGeneratorDetector.currentClientKind().id(),
					runtimeSettings.biomeBlending(),
					runtimeSettings.transparency()
				);
				LOGGER.info("PauC embedded DH bridge active loading policy: {}.", PauCLodClientSettings.describePerformancePolicy());
				LOGGER.info("PauC embedded DH bridge forced DH LOD side shading to {} for shader shadow continuity.", runtimeSettings.lodShading());
				lastConfiguredTarget = targetDistance;
			}
			state = new BridgeState(
				true,
				"configured",
				targetDistance,
				fillPolicyState.activeFillRadiusChunks(),
				fillPolicyState.requestedFillRadiusChunks(),
				fillPolicyState.backgroundFillRadiusChunks(),
				fillPolicyState.requestDistanceBlocks(),
				fillPolicyState.requestRateLimit(),
				fillPolicyState.coarseFillCatchup(),
				fillPolicyState.shaderFallback()
			);
		} catch (Throwable throwable) {
			state = BridgeState.unavailable("configure-error:" + throwable.getClass().getSimpleName());
			LOGGER.debug("PauC embedded DH bridge failed to configure Distant Horizons.", throwable);
		}
	}

	public static void reset() {
		state = BridgeState.unavailable("reset");
		lastConfiguredTarget = -1;
		lastRenderGeometrySignature = null;
		lastStableRuntimeSettings = null;
		pendingRuntimeSettings = null;
		pendingRuntimeSettingsTicks = 0;
		pendingRuntimeSettingsSinceMillis = 0L;
		lastLoggedDistantCloudPolicy = null;
		lastLoggedDistantCloudShaderFallback = null;
		lastLoggedGenerationPolicy = "";
		disabledRenderingApplied = false;
	}

	public static void resetPresentationStability(String reason) {
		lastStableRuntimeSettings = null;
		pendingRuntimeSettings = null;
		pendingRuntimeSettingsTicks = 0;
		pendingRuntimeSettingsSinceMillis = 0L;
		LOGGER.debug("PauC embedded DH bridge reset presentation stability ({}).", reason);
	}

	public static void applyStartupDirectGpuPolicy() {
		configureDirectGpuRendererPath("startup");
	}

	/**
	 * Wires the DH-free facade used by always-on callers. Called from the bootstrap ONLY when the
	 * external Distant Horizons mod is present — without DH the facade keeps returning safe defaults
	 * and this class is never referenced from hot code.
	 */
	public static void registerFacadeHooks() {
		fr.hoyatla.pauc.lod.PauCLodBridgeAccess.registerHooks(
			PauCEmbeddedDhBridge::isDirectGpuUploadActive,
			PauCEmbeddedDhBridge::describeGpuUploadState,
			PauCEmbeddedDhBridge::describeActuationState,
			PauCEmbeddedDhBridge::resetPresentationStability,
			PauCEmbeddedDhBridge::refreshRenderCacheForCoarseFill
		);
		// DH-as-data-source (approach 1): let PauC's distant generator read DH's LOD columns.
		fr.hoyatla.pauc.lod.PauCLodBridgeAccess.registerDhChunkFiller(PauCEmbeddedDhBridge::fillChunkFromDh);
	}

	private static boolean dhFillFailureLogged;

	/**
	 * Fills PauC surface spans for a chunk from Distant Horizons' terrain-data repo (faithful mapping:
	 * surface + water-floor / under-canopy ground reconstructed from DH's vertical segments). Runs on
	 * PauC generator daemon threads → colours use {@code getMapColor} (thread-safe, no GL/atlas access).
	 * Best-effort: any failure leaves the column as {@link Short#MIN_VALUE} so the caller regenerates it.
	 */
	private static int fillChunkFromDh(int chunkX, int chunkZ, int step,
			short[] ys, int[] colors, short[] bottoms, int[] bottomColors) {
		try {
			com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo repo = com.seibel.distanthorizons.api.DhApi.Delayed.terrainRepo;
			com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy worldProxy = com.seibel.distanthorizons.api.DhApi.Delayed.worldProxy;
			if (repo == null || worldProxy == null) {
				return 0;
			}
			net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
			if (level == null) {
				return 0;
			}
			com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper levelWrapper = worldProxy.getSinglePlayerLevel();
			if (levelWrapper == null) {
				return 0;
			}
			int minX = chunkX << 4;
			int minZ = chunkZ << 4;
			int filled = 0;
			for (int tz = 0; tz < 16; tz += step) {
				for (int tx = 0; tx < 16; tx += step) {
					int wx = minX + tx;
					int wz = minZ + tz;
					com.seibel.distanthorizons.api.objects.DhApiResult<com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint[]> result =
						repo.getColumnDataAtBlockPos(levelWrapper, wx, wz, null);
					if (result == null || !result.success || result.payload == null || result.payload.length == 0) {
						continue;
					}
					if (fillOneColumn(level, result.payload, wx, wz, (tz << 4) | tx, ys, colors, bottoms, bottomColors)) {
						filled++;
					}
				}
			}
			return filled;
		} catch (Throwable throwable) {
			if (!dhFillFailureLogged) {
				dhFillFailureLogged = true;
				org.slf4j.LoggerFactory.getLogger(PauCEmbeddedDhBridge.class)
					.warn("PauC DH-data-source fill failed; falling back to PauC generation.", throwable);
			}
			return 0;
		}
	}

	/** Maps one DH column (vertical segments) to PauC spans: surface + water-floor / tree-ground. */
	private static boolean fillOneColumn(net.minecraft.client.multiplayer.ClientLevel level,
			com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint[] column,
			int wx, int wz, int idx, short[] ys, int[] colors, short[] bottoms, int[] bottomColors) {
		// Surface = segment with the highest top Y.
		com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint surface = null;
		for (com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint p : column) {
			if (p != null && (surface == null || p.topYBlockPos > surface.topYBlockPos)) {
				surface = p;
			}
		}
		if (surface == null) {
			return false;
		}
		String serial = surface.blockStateWrapper != null ? surface.blockStateWrapper.getSerialString() : null;
		net.minecraft.world.level.block.state.BlockState state = resolveState(serial);
		if (state == null || state.isAir()) {
			return false;
		}
		boolean water = !state.getFluidState().isEmpty() || serialContains(serial, "water");
		boolean tree = serialContains(serial, "leaves") || serialContains(serial, "_log") || serialContains(serial, "log_");
		int topY = surface.topYBlockPos;
		int surfaceColor = mapColor(level, state, wx, topY, wz);
		if (water) {
			ys[idx] = (short) topY;
			colors[idx] = (fr.hoyatla.pauc.lodengine.PauCSurfaceColumnStore.WATER_ALPHA << 24) | (surfaceColor & 0x00ffffff);
			// Floor = highest non-water segment below the surface.
			com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint floor = highestBelow(column, topY, "water", null);
			if (floor != null) {
				net.minecraft.world.level.block.state.BlockState fs = resolveState(floor.blockStateWrapper != null ? floor.blockStateWrapper.getSerialString() : null);
				bottoms[idx] = (short) floor.topYBlockPos;
				bottomColors[idx] = 0xff000000 | (fs != null ? (mapColor(level, fs, wx, floor.topYBlockPos, wz) & 0x00ffffff) : 0x333333);
			}
			return true;
		}
		if (tree) {
			ys[idx] = (short) topY;
			colors[idx] = (fr.hoyatla.pauc.lodengine.PauCSurfaceColumnStore.TREE_ALPHA << 24) | (surfaceColor & 0x00ffffff);
			// Ground under the canopy = highest non-leaf/non-log segment below.
			com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint ground = highestBelow(column, topY, "leaves", "log");
			if (ground != null) {
				net.minecraft.world.level.block.state.BlockState gs = resolveState(ground.blockStateWrapper != null ? ground.blockStateWrapper.getSerialString() : null);
				bottoms[idx] = (short) ground.topYBlockPos;
				bottomColors[idx] = 0xff000000 | (gs != null ? (mapColor(level, gs, wx, ground.topYBlockPos, wz) & 0x00ffffff) : 0x5E4A33);
			} else {
				bottoms[idx] = (short) (topY - 6);
				bottomColors[idx] = 0xff5E4A33;
			}
			return true;
		}
		// Plain land: surface only; the renderer derives soil/stone strata on walls.
		ys[idx] = (short) topY;
		colors[idx] = (fr.hoyatla.pauc.lodengine.PauCSurfaceColumnStore.SOIL_ALPHA << 24) | (surfaceColor & 0x00ffffff);
		return true;
	}

	/** Highest segment strictly below {@code aboveY} whose serial contains NEITHER exclude token. */
	private static com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint highestBelow(
			com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint[] column, int aboveY, String excl1, String excl2) {
		com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint best = null;
		for (com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint p : column) {
			if (p == null || p.topYBlockPos >= aboveY) {
				continue;
			}
			String s = p.blockStateWrapper != null ? p.blockStateWrapper.getSerialString() : null;
			if (serialContains(s, excl1) || (excl2 != null && serialContains(s, excl2))) {
				continue;
			}
			if (best == null || p.topYBlockPos > best.topYBlockPos) {
				best = p;
			}
		}
		return best;
	}

	private static boolean serialContains(String serial, String token) {
		return serial != null && token != null && serial.toLowerCase(java.util.Locale.ROOT).contains(token);
	}

	/** Resolves a DH serial string ("minecraft:grass_block[...]") to a BlockState (default state). */
	private static net.minecraft.world.level.block.state.BlockState resolveState(String serial) {
		if (serial == null || serial.isEmpty()) {
			return null;
		}
		int i = 0;
		while (i < serial.length()) {
			char c = serial.charAt(i);
			if (c == ':' || c == '_' || c == '/' || c == '.' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				i++;
			} else {
				break;
			}
		}
		String id = serial.substring(0, i);
		net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
		if (rl == null) {
			return null;
		}
		net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
		return block == null ? null : block.defaultBlockState();
	}

	/** Thread-safe ARGB from a block's map colour (no GL/atlas access — safe on the generator threads). */
	private static int mapColor(net.minecraft.client.multiplayer.ClientLevel level,
			net.minecraft.world.level.block.state.BlockState state, int x, int y, int z) {
		try {
			net.minecraft.world.level.material.MapColor mc = state.getMapColor(level, new net.minecraft.core.BlockPos(x, y, z));
			return mc == null ? 0xff808080 : (0xff000000 | (mc.calculateRGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL) & 0x00ffffff));
		} catch (Throwable ignored) {
			return 0xff808080;
		}
	}

	public static String describeState() {
		return "embeddedDh[available="
			+ state.available()
			+ ", status="
			+ state.status()
			+ ", target="
			+ state.targetDistanceChunks()
			+ ", requestRate="
			+ state.requestRateLimit()
			+ ", requestDistanceBlocks="
			+ state.requestDistanceBlocks()
			+ ", fill="
			+ state.activeFillRadiusChunks()
			+ "/"
			+ state.requestedFillRadiusChunks()
			+ "/"
			+ state.backgroundFillRadiusChunks()
			+ ", catchup="
			+ state.coarseFillCatchup()
			+ ", coarseFill="
			+ PauCClientFrontierWarmupManager.shouldPreferCoarseFill()
			+ ", coarseRefreshes="
			+ coarseFillRenderRefreshes
			+ ", "
			+ describeGpuUploadState()
			+ ", "
			+ PauCLodSwapGuard.describeState()
			+ "]";
	}

	public static String describeActuationState() {
		return "embeddedDhAct[status="
			+ state.status()
			+ ", target="
			+ state.targetDistanceChunks()
			+ ", requestRate="
			+ state.requestRateLimit()
			+ ", requestDistanceBlocks="
			+ state.requestDistanceBlocks()
			+ ", activeFill="
			+ state.activeFillRadiusChunks()
			+ ", requestedFill="
			+ state.requestedFillRadiusChunks()
			+ ", backgroundFill="
			+ state.backgroundFillRadiusChunks()
			+ ", coarseCatchup="
			+ state.coarseFillCatchup()
			+ ", shaderFallback="
			+ state.shaderFallback()
			+ "]";
	}

	public static boolean isDirectGpuUploadActive() {
		// Standalone safety: the GPU upload state probes DH render internals (nested DhGpuUploadState).
		if (!fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			return false;
		}
		return PauCLodClientSettings.isDirectGpuUploadEnabled() && captureCachedGpuUploadState().direct();
	}

	public static String describeGpuUploadState() {
		if (!fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			return "dhGpu[dh-not-installed]";
		}
		return captureCachedGpuUploadState().describe();
	}

	public static void refreshRenderCacheForCoarseFill(double coverageRatio, int expectedCells, int coveredCells) {
		if (!fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			return;
		}
		if (!readBoolean(COARSE_FILL_RENDER_REFRESH_PROPERTY, false)) {
			return;
		}
		if (!PauCClientFrontierWarmupManager.shouldPreferCoarseFill()) {
			return;
		}
		if (PauCClientFrontierWarmupManager.isActiveTravelFill() && !readBoolean(COARSE_FILL_REFRESH_DURING_TRAVEL_PROPERTY, false)) {
			return;
		}
		if (expectedCells < 384) {
			return;
		}
		double minimumCoverageRatio = readDouble(COARSE_FILL_REFRESH_MIN_COVERAGE_PROPERTY, 0.46D, 0.05D, 0.95D);
		if (coverageRatio >= minimumCoverageRatio) {
			return;
		}

		long now = System.currentTimeMillis();
		long cooldownMs = readInt(COARSE_FILL_REFRESH_COOLDOWN_PROPERTY, 30_000, 5_000, 120_000);
		if (now - lastCoarseFillRenderRefreshAtMillis < cooldownMs) {
			return;
		}
		if (DhApi.Delayed.renderProxy == null) {
			return;
		}
		if (!readBoolean(COARSE_FILL_ALLOW_GLOBAL_CACHE_CLEAR_PROPERTY, false)) {
			lastCoarseFillRenderRefreshAtMillis = now;
			PauCLodReloadDiagnostics.onCoarseRefreshRequested(false);
			LOGGER.info(
				"PauC skipped global PL render cache clear during coarse LOD fill: coverage={}/{}, ratio={}, {}.",
				coveredCells,
				expectedCells,
				roundTwoDecimals(coverageRatio),
				PauCEmbeddedLodRuntimeDiagnostics.describeState()
			);
			return;
		}

		try {
			DhApi.Delayed.renderProxy.clearRenderDataCache();
			lastCoarseFillRenderRefreshAtMillis = now;
			coarseFillRenderRefreshes++;
			PauCLodReloadDiagnostics.onCoarseRefreshRequested(true);
			LOGGER.info(
				"PauC requested PL render cache refresh for coarse LOD fill: coverage={}/{}, ratio={}, refreshes={}, {}.",
				coveredCells,
				expectedCells,
				roundTwoDecimals(coverageRatio),
				coarseFillRenderRefreshes,
				PauCEmbeddedLodRuntimeDiagnostics.describeState()
			);
		} catch (Throwable throwable) {
			LOGGER.debug("PauC could not refresh PL render cache for coarse LOD fill.", throwable);
		}
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

	private static GenerationFillPolicyState configureCoreRuntimeSettings(int targetDistance, RuntimeLodSettings runtimeSettings) {
		configureDirectGpuRendererPath("runtime");
		setDhCoreConfigValueWithoutSaving(DH_DEBUGGING_CONFIG_CLASS, DH_RENDERER_MODE_FIELD, EDhApiRendererMode.DEFAULT);
		setDhCoreConfigValueWithoutSaving(DH_WARNING_CONFIG_CLASS, DH_SHOW_UPDATE_QUEUE_OVERLOADED_CHAT_WARNING_FIELD, Boolean.FALSE);
		setDhCoreConfigValueWithoutSaving(DH_WARNING_CONFIG_CLASS, DH_SHOW_SLOW_WORLDGEN_SETTING_WARNINGS_FIELD, Boolean.FALSE);
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
		GenerationFillPolicyState fillPolicyState = configureGenerationFillPolicy(targetDistance);
		setDhCoreConfigValueWithoutSaving(DH_MULTI_THREADING_CONFIG_CLASS, DH_NUMBER_OF_THREADS_FIELD, runtimeSettings.threadCount());
		setDhCoreConfigValueWithoutSaving(DH_MULTI_THREADING_CONFIG_CLASS, DH_THREAD_RUN_TIME_RATIO_FIELD, runtimeSettings.threadRuntimeRatio());
		clearRenderCacheIfGeometryChanged(runtimeSettings);
		return fillPolicyState;
	}

	private static void configureDirectGpuRendererPath(String phase) {
		if (!PauCLodClientSettings.isDirectGpuUploadEnabled() || !readBoolean(DIRECT_GPU_OPENGL_RENDERER_PROPERTY, true)) {
			return;
		}

		boolean configured = setDhCoreConfigValueWithoutSaving(DH_GRAPHICS_EXPERIMENTAL_CONFIG_CLASS, DH_RENDERING_API_FIELD, EDhApiRenderApi.OPEN_GL);
		if (configured && !loggedDirectGpuRendererOverride) {
			cachedGpuUploadState = null;
			cachedGpuUploadStateAtMillis = 0L;
			loggedDirectGpuRendererOverride = true;
			LOGGER.info("PauC direct GPU path selected DH OpenGL renderer during {} phase; {}.", phase, describeGpuUploadState());
		}
	}

	private static void clearRenderCacheIfGeometryChanged(RuntimeLodSettings runtimeSettings) {
		LodRenderGeometrySignature signature = LodRenderGeometrySignature.from(runtimeSettings);
		LodRenderGeometrySignature previousSignature = lastRenderGeometrySignature;
		if (previousSignature == null) {
			lastRenderGeometrySignature = signature;
			return;
		}
		if (previousSignature.equals(signature)) {
			return;
		}
		boolean shaderRuntimeChange = previousSignature.isShaderRuntimeChange(signature);
		boolean qualityOnlyChange = previousSignature.isQualityOnlyChange(signature);
		boolean presentationOnlyChange = !previousSignature.requiresMeshCacheClear(signature) && previousSignature.isShaderPresentationChange(signature);
		PauCLodReloadDiagnostics.onSignatureChange(shaderRuntimeChange, qualityOnlyChange, presentationOnlyChange);
		if (DhApi.Delayed.renderProxy == null) {
			lastRenderGeometrySignature = signature;
			return;
		}
		if (shaderRuntimeChange
			&& readBoolean(CLEAR_RENDER_CACHE_ON_SHADER_RUNTIME_CHANGE_PROPERTY, false)) {
			clearRenderDataCacheForSignatureChange(previousSignature, signature, "shader runtime changed");
			return;
		}
		if (!previousSignature.requiresMeshCacheClear(signature)) {
			lastRenderGeometrySignature = signature;
			PauCLodReloadDiagnostics.onCacheClearAvoided();
			LOGGER.info(
				"PauC kept the existing LOD render cache across a presentation-only change: {} -> {}.",
				previousSignature.describe(),
				signature.describe()
			);
			return;
		}
		if (qualityOnlyChange
			&& signature.maxHorizontalResolution() == EDhApiMaxHorizontalResolution.BLOCK
			&& PauCLodClientSettings.isVanillaFidelityRingActive()) {
			// Vanilla fidelity ring: realize the BLOCK upgrade NOW. Keeping the old meshes defers the re-mesh
			// to lazy section reloads — invisible while the player stands still (measured: 26 min at
			// TWO_BLOCKS after the latch engaged). The clear is a one-time cost, health-latched upstream and
			// absorbed by the worker yield / menu pre-generation boost.
			clearRenderDataCacheForSignatureChange(previousSignature, signature, "vanilla fidelity ring upgrade");
			return;
		}
		if (qualityOnlyChange && readBoolean(KEEP_RENDER_CACHE_ON_QUALITY_CHANGE_PROPERTY, true)) {
			lastRenderGeometrySignature = signature;
			PauCLodReloadDiagnostics.onCacheClearAvoided();
			LOGGER.info(
				"PauC kept existing LOD meshes visible while quality changes from coarse fill to refinement: {} -> {}.",
				previousSignature.describe(),
				signature.describe()
			);
			return;
		}
		if (previousSignature.isShaderPresentationChange(signature) && readBoolean(KEEP_RENDER_CACHE_ON_SHADER_PRESENTATION_CHANGE_PROPERTY, true)) {
			lastRenderGeometrySignature = signature;
			PauCLodReloadDiagnostics.onCacheClearAvoided();
			LOGGER.info(
				"PauC kept existing LOD meshes visible across shader/fallback presentation change: {} -> {}.",
				previousSignature.describe(),
				signature.describe()
			);
			return;
		}
		if (shouldDeferRenderCacheClear(previousSignature, signature)) {
			PauCLodReloadDiagnostics.onCacheClearDeferred();
			logDeferredRenderCacheClear(previousSignature, signature);
			return;
		}
		if (!readBoolean(CLEAR_RENDER_CACHE_ON_GEOMETRY_CHANGE_PROPERTY, true)) {
			lastRenderGeometrySignature = signature;
			PauCLodReloadDiagnostics.onCacheClearDisabled();
			LOGGER.debug(
				"PauC detected a LOD geometry mode change without clearing DH render cache: {} -> {}.",
				previousSignature.describe(),
				signature.describe()
			);
			return;
		}

		PauCLodSwapGuard.Decision swapDecision = PauCLodSwapGuard.evaluateRenderCacheClear(
			"geometry-change",
			previousSignature.requiresMeshCacheClear(signature),
			previousSignature.isShaderRuntimeChange(signature),
			PauCClientFrontierWarmupManager.hasRecoveredPresentationCoverage(),
			PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage(),
			PauCClientFrontierWarmupManager.isHotRestoreActive()
		);
		if (!swapDecision.allowed()) {
			PauCLodReloadDiagnostics.onCacheClearDeferred();
			LOGGER.info(
				"PauC SwapGuard deferred DH render cache clear ({}): {} -> {}.",
				swapDecision.reason(),
				previousSignature.describe(),
				signature.describe()
			);
			return;
		}
		clearRenderDataCacheForSignatureChange(previousSignature, signature, "LOD geometry mode changed");
	}

	private static void clearRenderDataCacheForSignatureChange(
		LodRenderGeometrySignature previousSignature,
		LodRenderGeometrySignature signature,
		String reason
	) {
		try {
			DhApi.Delayed.renderProxy.clearRenderDataCache();
			lastRenderGeometrySignature = signature;
			PauCLodReloadDiagnostics.onCacheClearExecuted();
			LOGGER.info(
				"PauC embedded DH bridge cleared DH render cache after {}: {} -> {}.",
				reason,
				previousSignature.describe(),
				signature.describe()
			);
		} catch (Throwable throwable) {
			LOGGER.debug("PauC embedded DH bridge could not clear DH render cache after a LOD geometry mode change.", throwable);
		}
	}

	private static boolean shouldDeferRenderCacheClear(LodRenderGeometrySignature previousSignature, LodRenderGeometrySignature signature) {
		boolean presentationSensitive = previousSignature.isQualityOnlyChange(signature)
			|| previousSignature.isShaderPresentationChange(signature)
			|| PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage();
		boolean recoveredCoverage = PauCClientFrontierWarmupManager.hasRecoveredPresentationCoverage();
		return readBoolean(DEFER_RENDER_CACHE_CLEAR_DURING_FILL_PROPERTY, true)
			&& !previousSignature.isShaderRuntimeChange(signature)
			&& presentationSensitive
			&& !recoveredCoverage
			&& (PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation() || PauCClientFrontierWarmupManager.isHotRestoreActive());
	}

	private static void logDeferredRenderCacheClear(LodRenderGeometrySignature previousSignature, LodRenderGeometrySignature signature) {
		long now = System.currentTimeMillis();
		long cooldownMs = readInt(DEFER_RENDER_CACHE_CLEAR_LOG_COOLDOWN_PROPERTY, 15_000, 1_000, 120_000);
		if (now - lastDeferredRenderCacheClearLogAtMillis < cooldownMs) {
			return;
		}
		lastDeferredRenderCacheClearLogAtMillis = now;
		LOGGER.info(
			"PauC deferred a quality-only PL render cache clear while LOD coverage is still filling: {} -> {}.",
			previousSignature.describe(),
			signature.describe()
		);
	}

	private static GenerationFillPolicyState configureGenerationFillPolicy(int targetDistance) {
		int requestRateLimit = PauCLodClientSettings.generationRequestRateLimit();
		boolean directFill = PauCClientFrontierWarmupManager.isDirectHorizonFillActive(targetDistance);
		boolean activeTravelFill = PauCClientFrontierWarmupManager.isActiveTravelFill();
		boolean fpsFirstVanilla = PauCClientChunkPriorityScorer.isFpsFirstVanillaMode();
		boolean shaderRuntime = isShaderPackRuntimeInUse();
		boolean shaderFallback = shaderRuntime && PauCLodShaderContext.isFallbackActive();
		boolean queueBackedUp = isDirectFillQueueBackedUp();
		boolean coarseFillCatchup = shaderFallback
			|| directFill
			|| PauCClientChunkPriorityScorer.isMovementCatchupActive()
			|| PauCClientFrontierWarmupManager.shouldPreferCoarseFill();
		int preloadExtraChunks = readInt(
			FOG_PRELOAD_EXTRA_CHUNKS_PROPERTY,
			directFill
				? fpsFirstVanilla
					? (activeTravelFill ? 56 : 40)
				: (activeTravelFill ? 48 : 36)
				: coarseFillCatchup ? 32 : 12,
			0,
			128
		);
		if (directFill && fpsFirstVanilla) {
			int visibleLeadChunks = readInt(
				queueBackedUp
					? "pauc.lod.directHorizonBacklogPreloadLeadChunks"
					: "pauc.lod.directHorizonVisiblePreloadLeadChunks",
				queueBackedUp ? (activeTravelFill ? 12 : 6) : (activeTravelFill ? 24 : 12),
				0,
				48
			);
			preloadExtraChunks = Math.min(preloadExtraChunks, visibleLeadChunks);
		}
		int maxRequestBlocks = readInt(
			MAX_GENERATION_REQUEST_BLOCKS_PROPERTY,
			directFill
				? fpsFirstVanilla
					? 16384
					: 14336
				: 8192,
			4096,
			32768
		);
		int activeFillRadius = PauCClientFrontierWarmupManager.activeFillRadiusChunks(targetDistance);
		int requestedFillRadius = PauCClientFrontierWarmupManager.requestedFillRadiusChunks(targetDistance);
		int backgroundFillRadius = PauCClientFrontierWarmupManager.backgroundFillRadiusChunks(targetDistance);
		int requestRadiusChunks = directFill
			? Math.max(activeFillRadius, requestedFillRadius)
			: Math.min(targetDistance, Math.max(requestedFillRadius, PauCLodHorizonState.currentRange().lodStartChunk()));
		int requestDistanceBlocks = clampInt((requestRadiusChunks + preloadExtraChunks) * 16, 256, maxRequestBlocks);
		if (directFill && fpsFirstVanilla && queueBackedUp) {
			int backlogLeadChunks = readInt("pauc.lod.directHorizonBacklogRequestLeadChunks", activeTravelFill ? 12 : 6, 0, 48);
			requestDistanceBlocks = Math.min(requestDistanceBlocks, clampInt((activeFillRadius + backlogLeadChunks) * 16, 256, maxRequestBlocks));
		}
		if (coarseFillCatchup) {
			int coarseFillDefault = shaderFallback ? 768 : shaderRuntime ? 512 : fpsFirstVanilla ? 1024 : 768;
			int coarseFillCeiling = shaderFallback ? 1024 : shaderRuntime ? 768 : fpsFirstVanilla ? 1536 : 1024;
			if (PauCLodShaderRuntime.pressure() == PauCLodShaderRuntime.Pressure.RELIEF) {
				coarseFillCeiling = Math.min(coarseFillCeiling, shaderFallback ? 768 : shaderRuntime ? 512 : fpsFirstVanilla ? 1024 : 768);
			}
			int coarseFillRate = readInt(COARSE_FILL_REQUEST_RATE_PROPERTY, coarseFillDefault, 20, 4096);
			requestRateLimit = Math.max(requestRateLimit, Math.min(coarseFillRate, coarseFillCeiling));
		}
		if (directFill) {
			int directFillRate = readInt(
				"pauc.lod.directHorizonGenerationRequestRate",
				fpsFirstVanilla
					? (activeTravelFill ? 1536 : 1024)
					: (activeTravelFill ? 1152 : 768),
				20,
				4096
			);
			requestRateLimit = Math.max(requestRateLimit, directFillRate);
		}
		boolean coarseFirstFill = readBoolean(COARSE_FIRST_FILL_PROPERTY, true);
		boolean fillHoles = PauCLodClientSettings.fillLodHoles();
		boolean nSizedGeneration = PauCLodClientSettings.enableNSizeGeneration();
		setDhCoreConfigValueWithoutSaving(DH_SERVER_CONFIG_CLASS, DH_GENERATION_REQUEST_RATE_LIMIT_FIELD, requestRateLimit);
		setDhCoreConfigValueWithoutSaving(DH_SERVER_CONFIG_CLASS, DH_MAX_GENERATION_REQUEST_DISTANCE_FIELD, requestDistanceBlocks);
		setDhCoreConfigValueWithoutSaving(DH_SERVER_EXPERIMENTAL_CONFIG_CLASS, DH_ENABLE_N_SIZE_GENERATION_FIELD, nSizedGeneration);
		setDhCoreConfigValueWithoutSaving(DH_LOD_BUILDING_EXPERIMENTAL_CONFIG_CLASS, DH_UPSAMPLE_LOWER_DETAIL_LODS_FIELD, fillHoles);
		String generationPolicySignature = targetDistance
			+ ":"
			+ activeFillRadius
			+ ":"
			+ requestRadiusChunks
			+ ":"
			+ backgroundFillRadius
			+ ":"
			+ requestDistanceBlocks
			+ ":"
			+ requestRateLimit
			+ ":"
			+ shaderFallback
			+ ":"
			+ coarseFillCatchup
			+ ":"
			+ directFill;
		if (!loggedRoundHorizonGenerationPolicy || !generationPolicySignature.equals(lastLoggedGenerationPolicy)) {
			loggedRoundHorizonGenerationPolicy = true;
			lastLoggedGenerationPolicy = generationPolicySignature;
			LOGGER.info(
				"PauC embedded DH bridge keeps the round LOD horizon filled: renderRadius={} chunks, activeFillBand={} chunks, generationRequest={} blocks, preloadExtra={} chunks, requestRate={}/s, nSized={}, fillHoles={}, coarseFirst={}, queueBackedUp={}.",
				targetDistance,
				activeFillRadius,
				requestDistanceBlocks,
				preloadExtraChunks,
				requestRateLimit,
				nSizedGeneration,
				fillHoles,
				coarseFirstFill,
				queueBackedUp
			);
			LOGGER.info(
				"PauC embedded DH bridge fill expansion: requestedRadius={} chunks, backgroundRadius={} chunks, shaderFallback={}, catchup={}, directFill={}.",
				requestRadiusChunks,
				backgroundFillRadius,
				shaderFallback,
				coarseFillCatchup,
				directFill
			);
		}
		configureReliefCompression();
		return new GenerationFillPolicyState(
			activeFillRadius,
			requestRadiusChunks,
			backgroundFillRadius,
			requestDistanceBlocks,
			requestRateLimit,
			coarseFillCatchup,
			shaderFallback
		);
	}

	private static void configureDistantCloudRendering() {
		boolean shaderFallback = isShaderPackRuntimeInUse() && PauCLodShaderContext.isFallbackActive();
		boolean genericLods = readBoolean(GENERIC_RENDERING_PROPERTY, true)
			&& (!shaderFallback || readBoolean(SHADER_FALLBACK_GENERIC_RENDERING_PROPERTY, true));
		boolean cloudLods = genericLods
			&& !shaderFallback
			&& PauCLodRenderCulling.shouldEnableLodCloudRendering(PauCLodClientSettings.isLodCloudsEnabled());
		setDhCoreConfigValueWithoutSaving(DH_GENERIC_RENDERING_CONFIG_CLASS, DH_ENABLE_GENERIC_RENDERING_FIELD, genericLods);
		setDhCoreConfigValueWithoutSaving(DH_GENERIC_RENDERING_CONFIG_CLASS, DH_ENABLE_CLOUD_RENDERING_FIELD, cloudLods);
		Boolean cloudPolicy = cloudLods;
		Boolean shaderFallbackPolicy = shaderFallback;
		if (!cloudPolicy.equals(lastLoggedDistantCloudPolicy) || !shaderFallbackPolicy.equals(lastLoggedDistantCloudShaderFallback)) {
			lastLoggedDistantCloudPolicy = cloudLods;
			lastLoggedDistantCloudShaderFallback = shaderFallback;
			LOGGER.info("PauC embedded DH bridge configured distant generic/cloud LOD rendering: generic={}, clouds={}, shaderFallback={}.", genericLods, cloudLods, shaderFallback);
		}
	}

	private static void configureSeamlessLodTransition() {
		boolean shaderRuntime = isShaderPackRuntimeInUse();
		boolean shaderFallback = shaderRuntime && PauCLodShaderContext.isFallbackActive();
		boolean keepUnderVanilla = PauCLodNearClipOverride.shouldKeepLodsUnderVanilla();
		boolean coverageRecovery = !shaderRuntime
			&& !keepUnderVanilla
			&& (PauCClientFrontierWarmupManager.hasNearCoverageDebt()
				|| PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage());
		float overdrawPrevention = (float) readDouble(
			shaderFallback ? SHADER_FALLBACK_OVERDRAW_PREVENTION_PROPERTY : shaderRuntime ? SHADER_OVERDRAW_PREVENTION_PROPERTY : RELIEF_OVERDRAW_PREVENTION_PROPERTY,
			keepUnderVanilla || shaderFallback ? -1.0D : shaderRuntime ? 0.80D : 0.88D,
			-1.0D,
			1.0D
		);
		if (coverageRecovery) {
			// Coverage first: let LOD terrain overlap while the vanilla/LOD junction is still refilling.
			overdrawPrevention = -1.0F;
		}
		EDhApiMcRenderingFadeMode fadeMode = shaderRuntime
			? readEnum(
				shaderFallback ? SHADER_FALLBACK_VANILLA_FADE_MODE_PROPERTY : SHADER_VANILLA_FADE_MODE_PROPERTY,
				EDhApiMcRenderingFadeMode.class,
				keepUnderVanilla || shaderFallback ? EDhApiMcRenderingFadeMode.NONE : EDhApiMcRenderingFadeMode.SINGLE_PASS
			)
			: EDhApiMcRenderingFadeMode.NONE;
		boolean configured = setDhCoreConfigValueWithoutSaving(DH_QUALITY_CONFIG_CLASS, DH_VANILLA_FADE_MODE_FIELD, fadeMode)
			& setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_OVERDRAW_PREVENTION_FIELD, overdrawPrevention)
			& setDhCoreConfigValueWithoutSaving(DH_CULLING_CONFIG_CLASS, DH_REDUCE_OVERDRAW_FAST_MOVEMENT_FIELD, Boolean.FALSE);

		String transitionSignature = shaderRuntime + ":" + shaderFallback + ":" + keepUnderVanilla + ":" + coverageRecovery + ":" + fadeMode + ":" + overdrawPrevention;
		if (configured && (!loggedSeamlessTransitionOverride || !transitionSignature.equals(lastLoggedSeamlessTransitionPolicy))) {
			loggedSeamlessTransitionOverride = true;
			lastLoggedSeamlessTransitionPolicy = transitionSignature;
			LOGGER.info("PauC embedded DH bridge configured vanilla-to-LOD boundary: shaderRuntime={}, shaderFallback={}, keepUnderVanilla={}, coverageRecovery={}, vanillaFade={}, overdraw={}, fastMovementOverdraw=false.", shaderRuntime, shaderFallback, keepUnderVanilla, coverageRecovery, fadeMode, overdrawPrevention);
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

	private static boolean shouldTreatShaderFogAsAuthoritative(boolean shaderPackInUse) {
		if (!shaderPackInUse || PauCLodShaderContext.isFallbackActive()) {
			return false;
		}
		return true;
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
		lastLoggedSeamlessTransitionPolicy = "";
		lastStableRuntimeSettings = null;
		pendingRuntimeSettings = null;
		pendingRuntimeSettingsTicks = 0;
		pendingRuntimeSettingsSinceMillis = 0L;
	}

	private static RuntimeLodSettings stabilizePresentationRuntimeSettings(RuntimeLodSettings candidate) {
		RuntimeLodSettings stable = lastStableRuntimeSettings;
		if (stable == null) {
			lastStableRuntimeSettings = candidate;
			pendingRuntimeSettings = null;
			pendingRuntimeSettingsTicks = 0;
			pendingRuntimeSettingsSinceMillis = 0L;
			return candidate;
		}

		LodRenderGeometrySignature stableSignature = LodRenderGeometrySignature.from(stable);
		LodRenderGeometrySignature candidateSignature = LodRenderGeometrySignature.from(candidate);
		if (stableSignature.equals(candidateSignature)) {
			lastStableRuntimeSettings = candidate;
			pendingRuntimeSettings = null;
			pendingRuntimeSettingsTicks = 0;
			pendingRuntimeSettingsSinceMillis = 0L;
			return candidate;
		}
		if (stableSignature.isShaderRuntimeChange(candidateSignature)) {
			lastStableRuntimeSettings = candidate;
			pendingRuntimeSettings = null;
			pendingRuntimeSettingsTicks = 0;
			pendingRuntimeSettingsSinceMillis = 0L;
			return candidate;
		}

		long now = System.currentTimeMillis();
		boolean sensitivePhase = PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage()
			|| PauCClientFrontierWarmupManager.isHotRestoreActive()
			|| PauCClientChunkPriorityScorer.isMovementCatchupActive()
			|| PauCClientSurfaceLodMode.prefersAccurateFeatureLods();
		int highTargetBonus = PauCLodClientSettings.configuredTargetDistanceChunks() >= 48 ? 1 : 0;
		int requiredTicks = readInt(PRESENTATION_SIGNATURE_STABLE_TICKS_PROPERTY, 3, 1, 20)
			+ (sensitivePhase ? 2 : 0)
			+ highTargetBonus;
		long requiredHoldMs = readInt(PRESENTATION_SIGNATURE_HOLD_MS_PROPERTY, sensitivePhase ? 180 : 120, 0, 2_000);
		if (candidate.equals(pendingRuntimeSettings)) {
			pendingRuntimeSettingsTicks++;
		} else {
			pendingRuntimeSettings = candidate;
			pendingRuntimeSettingsTicks = 1;
			pendingRuntimeSettingsSinceMillis = now;
		}
		if (pendingRuntimeSettingsTicks >= requiredTicks || now - pendingRuntimeSettingsSinceMillis >= requiredHoldMs) {
			lastStableRuntimeSettings = candidate;
			pendingRuntimeSettings = null;
			pendingRuntimeSettingsTicks = 0;
			pendingRuntimeSettingsSinceMillis = 0L;
			return candidate;
		}
		return candidate.withPresentationFrom(stable);
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

	private static DhGpuUploadState captureGpuUploadState() {
		try {
			Class<?> glProxyClass = Class.forName("com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy");
			java.lang.reflect.Field instanceField = glProxyClass.getDeclaredField("instance");
			instanceField.setAccessible(true);
			Object proxy = instanceField.get(null);
			if (proxy == null) {
				return DhGpuUploadState.waiting();
			}

			Object uploadMethod = proxy.getClass().getMethod("getGpuUploadMethod").invoke(proxy);
			boolean bufferStorageSupported = readBooleanField(proxy, "bufferStorageSupported");
			boolean namedObjectSupported = readBooleanField(proxy, "namedObjectSupported");
			String method = uploadMethod != null ? uploadMethod.toString() : "unknown";
			return new DhGpuUploadState(true, method, bufferStorageSupported, namedObjectSupported, "BUFFER_STORAGE".equals(method), "ready");
		} catch (ReflectiveOperationException | LinkageError throwable) {
			return DhGpuUploadState.unavailable(throwable.getClass().getSimpleName());
		}
	}

	private static DhGpuUploadState captureCachedGpuUploadState() {
		long now = System.currentTimeMillis();
		DhGpuUploadState cached = cachedGpuUploadState;
		long cacheMillis = readInt(DIRECT_GPU_UPLOAD_STATE_CACHE_MS_PROPERTY, 250, 50, 5_000);
		if (cached != null && now - cachedGpuUploadStateAtMillis <= cacheMillis) {
			return cached;
		}

		DhGpuUploadState fresh = captureGpuUploadState();
		cachedGpuUploadState = fresh;
		cachedGpuUploadStateAtMillis = now;
		return fresh;
	}

	private static boolean readBooleanField(Object target, String fieldName) throws ReflectiveOperationException {
		java.lang.reflect.Field field = target.getClass().getField(fieldName);
		return field.getBoolean(target);
	}

	private static boolean valuesEqual(Object currentValue, Object newValue) {
		if (currentValue instanceof Number currentNumber && newValue instanceof Number newNumber) {
			return Math.abs(currentNumber.doubleValue() - newNumber.doubleValue()) < 0.0001D;
		}
		return java.util.Objects.equals(currentValue, newValue);
	}

	private static boolean isShaderPackRuntimeInUse() {
		try {
			return fr.hoyatla.pauc.shadercompat.PauCShaderCompat.isShaderPackInUse();
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

	/**
	 * "Vanilla fidelity ring": at high LOD gauges (perceived-vanilla horizon project, up to 94 chunks) the
	 * DEFAULT DH resolution moves to BLOCK (nearest LOD ring geometrically identical to vanilla terrain)
	 * with a gentler horizontal dropoff. Explicit properties and the governor's dynamic quality tiers still
	 * override this default, so the existing fps protection keeps the last word under pressure.
	 */
	private static boolean vanillaFidelityRingActive() {
		boolean active = PauCLodClientSettings.isVanillaFidelityRingActive();
		if (active && !vanillaFidelityRingLogged) {
			vanillaFidelityRingLogged = true;
			LOGGER.info(
				"PauC vanilla fidelity ring active (LOD gauge {}): governor will move DH resolution to BLOCK once the backlog drains and frames are healthy.",
				PauCLodClientSettings.targetDistanceChunks()
			);
		}
		return active;
	}

	/**
	 * Menu/pause pre-generation boost: while a screen is open fps is not being judged (player rule), so if
	 * LOD build backlog remains the worker pool runs at its ceiling to swallow first-generation of large
	 * gauges (94 chunks = ~27k LOD chunks). Complements the fps-pressure worker yield, which protects
	 * gameplay frames; the two are mutually exclusive by construction (yield is inert while a screen is open).
	 */
	private static boolean isMenuPregenBoostActive() {
		if (!readBoolean(MENU_PREGEN_BOOST_PROPERTY, true)) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.screen == null) {
			return false;
		}
		boolean backlog = PauCEmbeddedLodRuntimeDiagnostics.pendingTasks() > 0
			|| PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() > 0
			|| PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() > 0;
		if (backlog) {
			long now = System.currentTimeMillis();
			if (now - menuPregenBoostLastLogMs > 60_000L) {
				menuPregenBoostLastLogMs = now;
				LOGGER.info(
					"PauC menu pre-generation boost engaged: screen open with LOD backlog (pending={}, backlog={}, chunks={}); workers at ceiling.",
					PauCEmbeddedLodRuntimeDiagnostics.pendingTasks(),
					PauCEmbeddedLodRuntimeDiagnostics.backlogTasks(),
					PauCEmbeddedLodRuntimeDiagnostics.pendingChunks()
				);
			}
		}
		return backlog;
	}

	/**
	 * Yield factor applied to the LOD build worker pool (thread count + DH duty ratio) when the frame
	 * spike absorber measures the render thread struggling against its own baseline. Bamboo-jungle-style
	 * dimension returns schedule thousands of expensive chunk builds; without this coupling the workers
	 * saturate every core at up to 92% duty and starve the render thread (measured: fps 53-78 with
	 * queue=60%, avgChunkMs=317). Full speed is kept whenever a screen is open (loading screens, menus):
	 * fps is not being judged there and dimension/map loads should fill as fast as possible. Distance and
	 * LOD quality are untouched - the backlog just drains a little later.
	 */
	private static double fpsPressureWorkerYield() {
		if (!readBoolean(FPS_PRESSURE_WORKER_YIELD_PROPERTY, true)) {
			return 1.0D;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
			return 1.0D;
		}
		long now = System.currentTimeMillis();
		if (PauCFrameSpikeAbsorber.isAbsorbing()) {
			double floor = readDouble(FPS_PRESSURE_WORKER_YIELD_FLOOR_PROPERTY, 0.35D, 0.10D, 1.0D);
			double yield = Math.max(floor, PauCFrameSpikeAbsorber.workScale());
			workerYieldLastAbsorbMs = now;
			workerYieldLastScale = yield;
			if (!workerYieldEngaged) {
				workerYieldEngaged = true;
				if (now - workerYieldLastLogMs > 60_000L) {
					workerYieldLastLogMs = now;
					LOGGER.info(
						"PauC LOD worker yield engaged: scaling build workers to {} of normal while the frame spike absorber is active.",
						String.format(java.util.Locale.ROOT, "%.2f", yield)
					);
				}
			}
			return yield;
		}
		if (workerYieldEngaged) {
			// Hysteresis: releasing the workers the instant the absorber calms down re-spikes the frame and
			// flaps engage/release every ~2s (measured: 128 cycles in 5 min). Hold the reduced pool through a
			// quiet window before restoring full speed.
			int quietMs = readInt(FPS_PRESSURE_WORKER_YIELD_QUIET_MS_PROPERTY, 4000, 500, 60_000);
			if (now - workerYieldLastAbsorbMs < quietMs) {
				return workerYieldLastScale;
			}
			workerYieldEngaged = false;
			if (now - workerYieldLastLogMs > 60_000L) {
				workerYieldLastLogMs = now;
				LOGGER.info("PauC LOD worker yield released: build workers back to full speed.");
			}
		}
		return 1.0D;
	}

	private static int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clampDouble(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static boolean isDirectFillQueueBackedUp() {
		if (!PauCEmbeddedLodRuntimeDiagnostics.queueAvailable()) {
			return false;
		}
		return PauCEmbeddedLodRuntimeDiagnostics.pendingChunks() >= readInt("pauc.lod.directHorizonBacklogPendingChunks", 1536, 128, 32768)
			|| PauCEmbeddedLodRuntimeDiagnostics.backlogTasks() >= readInt("pauc.lod.directHorizonBacklogTasks", 128, 8, 8192)
			|| PauCEmbeddedLodRuntimeDiagnostics.backlogPressure() >= readDouble("pauc.lod.directHorizonBacklogQueuePressure", 0.28D, 0.0D, 1.0D);
	}

	private record BridgeState(
		boolean available,
		String status,
		int targetDistanceChunks,
		int activeFillRadiusChunks,
		int requestedFillRadiusChunks,
		int backgroundFillRadiusChunks,
		int requestDistanceBlocks,
		int requestRateLimit,
		boolean coarseFillCatchup,
		boolean shaderFallback
	) {
		private static BridgeState unavailable(String status) {
			return new BridgeState(false, status, -1, -1, -1, -1, -1, -1, false, false);
		}
	}

	private record GenerationFillPolicyState(
		int activeFillRadiusChunks,
		int requestedFillRadiusChunks,
		int backgroundFillRadiusChunks,
		int requestDistanceBlocks,
		int requestRateLimit,
		boolean coarseFillCatchup,
		boolean shaderFallback
	) {
	}

	private record DhGpuUploadState(
		boolean proxyReady,
		String method,
		boolean bufferStorageSupported,
		boolean namedObjectSupported,
		boolean direct,
		String status
	) {
		private static DhGpuUploadState waiting() {
			return new DhGpuUploadState(false, "-", false, false, false, "proxy-waiting");
		}

		private static DhGpuUploadState unavailable(String status) {
			return new DhGpuUploadState(false, "-", false, false, false, status);
		}

		private String describe() {
			return "dhGpu[proxy="
				+ (proxyReady ? "ready" : "not-ready")
				+ ", method="
				+ method
				+ ", direct="
				+ direct
				+ ", bufferStorage="
				+ bufferStorageSupported
				+ ", namedObject="
				+ namedObjectSupported
				+ ", status="
				+ status
				+ "]";
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
			PauCTerrainGeneratorDetector.GeneratorKind terrainGenerator = PauCTerrainGeneratorDetector.currentClientKind();
			PauCTerrainGeneratorDetector.ModpackClass modpackClass = PauCTerrainGeneratorDetector.currentModpackClass();
			int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
			boolean coarseFill = PauCClientFrontierWarmupManager.shouldPreferCoarseFill() || PauCClientChunkPriorityScorer.isMovementCatchupActive();
			boolean directFill = PauCClientFrontierWarmupManager.isDirectHorizonFillActive(PauCLodClientSettings.targetDistanceChunks());
			boolean activeTravelFill = PauCClientFrontierWarmupManager.isActiveTravelFill();
			boolean aggressiveFill = coarseFill || directFill;
			boolean shaderRuntime = isShaderPackRuntimeInUse();
			boolean fpsFirstVanilla = PauCClientChunkPriorityScorer.isFpsFirstVanillaMode();
			int modpackThreadBoost = switch (modpackClass) {
				case EXTREME -> 4;
				case HEAVY -> 3;
				case MEDIUM -> 1;
				case LIGHT -> 0;
			};
			int threadCeiling = Math.max(2, Math.min((shaderRuntime ? 18 : fpsFirstVanilla ? 20 : 28) + modpackThreadBoost, processors));
			double modpackShareBoost = switch (modpackClass) {
				case EXTREME -> 0.08D;
				case HEAVY -> 0.06D;
				case MEDIUM -> 0.03D;
				case LIGHT -> 0.0D;
			};
			double defaultThreadShare = clampDouble((coarseFill
				? (shaderRuntime ? 0.76D : fpsFirstVanilla ? 0.58D : 0.92D)
				: (shaderRuntime ? 0.54D : fpsFirstVanilla ? 0.46D : 0.68D)) + modpackShareBoost, 0.25D, 0.92D);
			if (directFill) {
				defaultThreadShare = Math.max(defaultThreadShare, shaderRuntime ? 0.68D : fpsFirstVanilla ? 0.58D : 0.76D);
				if (fpsFirstVanilla && !shaderRuntime) {
					defaultThreadShare = Math.max(defaultThreadShare, activeTravelFill ? 0.82D : 0.74D);
				}
			}
			int defaultThreads = clampInt((int) Math.ceil(processors * defaultThreadShare), 2, threadCeiling);
			double defaultRuntimeRatio = clampDouble((aggressiveFill
				? (shaderRuntime ? 0.72D : fpsFirstVanilla ? 0.56D : 0.90D)
				: (shaderRuntime ? 0.52D : fpsFirstVanilla ? 0.42D : 0.68D)) + modpackShareBoost * 0.50D, 0.05D, 0.92D);
			if (directFill) {
				defaultRuntimeRatio = Math.max(defaultRuntimeRatio, shaderRuntime ? 0.66D : fpsFirstVanilla ? 0.56D : 0.90D);
				if (fpsFirstVanilla && !shaderRuntime) {
					defaultRuntimeRatio = Math.max(defaultRuntimeRatio, activeTravelFill ? 0.76D : 0.68D);
				}
			}
			if (isMenuPregenBoostActive()) {
				defaultThreads = threadCeiling;
				defaultRuntimeRatio = 0.92D;
			}
			double workerYield = fpsPressureWorkerYield();
			if (workerYield < 1.0D) {
				defaultThreads = clampInt((int) Math.ceil(defaultThreads * workerYield), 2, threadCeiling);
				defaultRuntimeRatio = clampDouble(defaultRuntimeRatio * workerYield, 0.05D, 0.92D);
			}
			return new RuntimeLodSettings(
				defaultMaxHorizontalResolution(),
				defaultHorizontalQuality(),
				defaultVerticalQuality(terrainGenerator, modpackClass),
				EDhApiLodShading.ENABLED,
				readEnum(FAST_TRANSPARENCY_PROPERTY, EDhApiTransparency.class, defaultTransparency()),
				defaultRuntimeGeneratorMode(),
				readInt(FAST_THREADS_PROPERTY, defaultThreads, 1, threadCeiling),
				readDouble(FAST_RUNTIME_RATIO_PROPERTY, PauCLodShaderRuntime.generationThreadRuntimeRatio(defaultRuntimeRatio), 0.05D, 1.0D),
				readInt(FAST_BIOME_BLEND_PROPERTY, defaultBiomeBlending(terrainGenerator, modpackClass, shaderRuntime, fpsFirstVanilla), 0, 3)
			);
		}

		private RuntimeLodSettings withPresentationFrom(RuntimeLodSettings other) {
			return new RuntimeLodSettings(
				other.maxHorizontalResolution(),
				other.horizontalQuality(),
				other.verticalQuality(),
				other.lodShading(),
				other.transparency(),
				generatorMode,
				threadCount,
				threadRuntimeRatio,
				other.biomeBlending()
			);
		}

		private static EDhApiMaxHorizontalResolution defaultMaxHorizontalResolution() {
			EDhApiMaxHorizontalResolution configured = readEnum(
				FAST_MAX_RESOLUTION_PROPERTY,
				EDhApiMaxHorizontalResolution.class,
				vanillaFidelityRingActive() ? EDhApiMaxHorizontalResolution.BLOCK : EDhApiMaxHorizontalResolution.TWO_BLOCKS
			);
			EDhApiMaxHorizontalResolution dynamic = readEnum(DYNAMIC_MAX_RESOLUTION_PROPERTY, EDhApiMaxHorizontalResolution.class, configured);
			return PauCClientSurfaceLodMode.adjustMaxHorizontalResolution(dynamic);
		}

		private static EDhApiVerticalQuality defaultVerticalQuality(
			PauCTerrainGeneratorDetector.GeneratorKind terrainGenerator,
			PauCTerrainGeneratorDetector.ModpackClass modpackClass
		) {
			EDhApiVerticalQuality configured = readEnum(
				FAST_VERTICAL_QUALITY_PROPERTY,
				EDhApiVerticalQuality.class,
				!isShaderPackRuntimeInUse() ? EDhApiVerticalQuality.MEDIUM : EDhApiVerticalQuality.LOW
			);
			EDhApiVerticalQuality dynamic = readEnum(DYNAMIC_VERTICAL_QUALITY_PROPERTY, EDhApiVerticalQuality.class, configured);
			return terrainAwareVerticalQuality(PauCClientSurfaceLodMode.adjustVerticalQuality(dynamic), terrainGenerator, modpackClass);
		}

		private static EDhApiVerticalQuality terrainAwareVerticalQuality(
			EDhApiVerticalQuality requestedQuality,
			PauCTerrainGeneratorDetector.GeneratorKind terrainGenerator,
			PauCTerrainGeneratorDetector.ModpackClass modpackClass
		) {
			if (!readBoolean("pauc.lod.terrainAwareVerticalQuality", true)) {
				return requestedQuality;
			}
			boolean shaderRuntime = isShaderPackRuntimeInUse();
			boolean heavyModpack = modpackClass == PauCTerrainGeneratorDetector.ModpackClass.HEAVY
				|| modpackClass == PauCTerrainGeneratorDetector.ModpackClass.EXTREME;
			return switch (terrainGenerator) {
				case TECTONIC, STRATOSPHERIC, WILDER_WILDS, JJTHUNDER -> raiseVerticalQuality(
					requestedQuality,
					(shaderRuntime || heavyModpack) ? EDhApiVerticalQuality.MEDIUM : EDhApiVerticalQuality.HIGH
				);
				case TERRALITH, CONTINENTS, BOP, BYG -> raiseVerticalQuality(requestedQuality, EDhApiVerticalQuality.MEDIUM);
				default -> requestedQuality;
			};
		}

		private static EDhApiVerticalQuality raiseVerticalQuality(EDhApiVerticalQuality current, EDhApiVerticalQuality minimum) {
			if (current == null || current == EDhApiVerticalQuality.HIGH) {
				return current == null ? minimum : current;
			}
			if (minimum == EDhApiVerticalQuality.HIGH) {
				return switch (current) {
					case LOW, MEDIUM, HEIGHT_MAP -> EDhApiVerticalQuality.HIGH;
					default -> current;
				};
			}
			return switch (current) {
				case LOW, HEIGHT_MAP -> EDhApiVerticalQuality.MEDIUM;
				default -> current;
			};
		}

		private static EDhApiHorizontalQuality defaultHorizontalQuality() {
			EDhApiHorizontalQuality configured = readEnum(
				FAST_HORIZONTAL_QUALITY_PROPERTY,
				EDhApiHorizontalQuality.class,
				vanillaFidelityRingActive() ? EDhApiHorizontalQuality.MEDIUM : EDhApiHorizontalQuality.LOW
			);
			EDhApiHorizontalQuality dynamic = readEnum(DYNAMIC_HORIZONTAL_QUALITY_PROPERTY, EDhApiHorizontalQuality.class, configured);
			return PauCClientSurfaceLodMode.adjustHorizontalQuality(dynamic);
		}

		private static int defaultBiomeBlending(
			PauCTerrainGeneratorDetector.GeneratorKind terrainGenerator,
			PauCTerrainGeneratorDetector.ModpackClass modpackClass,
			boolean shaderRuntime,
			boolean fpsFirstVanilla
		) {
			int fallback = terrainGenerator.wideBiomeTransitions() ? 3 : 2;
			if (fpsFirstVanilla || shaderRuntime) {
				fallback = Math.min(fallback, 2);
			}
			if (modpackClass == PauCTerrainGeneratorDetector.ModpackClass.HEAVY
				|| modpackClass == PauCTerrainGeneratorDetector.ModpackClass.EXTREME) {
				fallback = Math.min(fallback, 2);
			}
			return fallback;
		}

		private static EDhApiDistantGeneratorMode defaultGeneratorMode() {
			if (Boolean.parseBoolean(System.getProperty(DISTANT_STRUCTURES_PROPERTY, "false"))) {
				return EDhApiDistantGeneratorMode.INTERNAL_SERVER;
			}
			boolean shaderFallback = isShaderPackRuntimeInUse() && PauCLodShaderContext.isFallbackActive();
			boolean stableFill = !PauCClientFrontierWarmupManager.shouldStabilizeLodPresentation()
				&& !PauCClientChunkPriorityScorer.isMovementCatchupActive();
			if (readBoolean(ADAPTIVE_DISTANT_STRUCTURES_PROPERTY, true) && stableFill && !shaderFallback) {
				return EDhApiDistantGeneratorMode.INTERNAL_SERVER;
			}

			return EDhApiDistantGeneratorMode.SURFACE;
		}

		private static EDhApiDistantGeneratorMode defaultRuntimeGeneratorMode() {
			boolean directFill = PauCClientFrontierWarmupManager.isDirectHorizonFillActive(PauCLodClientSettings.targetDistanceChunks());
			EDhApiDistantGeneratorMode fallback = (directFill || PauCClientFrontierWarmupManager.shouldPreferCoarseFill() || PauCClientChunkPriorityScorer.isMovementCatchupActive())
				? readEnum(FILL_GENERATOR_MODE_PROPERTY, EDhApiDistantGeneratorMode.class, defaultGeneratorMode())
				: defaultGeneratorMode();
			EDhApiDistantGeneratorMode configured = readEnum(FAST_GENERATOR_MODE_PROPERTY, EDhApiDistantGeneratorMode.class, fallback);
			return PauCClientSurfaceLodMode.adjustGeneratorMode(configured);
		}

		private static EDhApiTransparency defaultTransparency() {
			if (!isShaderPackRuntimeInUse()) {
				return PauCClientSurfaceLodMode.prefersAccurateFeatureLods() ? EDhApiTransparency.COMPLETE : EDhApiTransparency.FAKE;
			}
			if (PauCLodShaderContext.isFallbackActive()) {
				return EDhApiTransparency.COMPLETE;
			}

			return switch (PauCLodShaderProfiles.currentFamily()) {
				case BLISS, BSL, COMPLEMENTARY, PHOTON, RETHINKING, SOLAS, SILDURS_ENHANCED, SILDURS_VIBRANT -> EDhApiTransparency.COMPLETE;
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
		EDhApiTransparency transparency,
		boolean shaderRuntime,
		PauCLodShaderProfiles.Family shaderFamily,
		boolean shaderFallback
	) {
		private static LodRenderGeometrySignature from(RuntimeLodSettings settings) {
			boolean shaderRuntime = isShaderPackRuntimeInUse();
			return new LodRenderGeometrySignature(
				settings.maxHorizontalResolution(),
				settings.horizontalQuality(),
				settings.verticalQuality(),
				settings.lodShading(),
				settings.transparency(),
				shaderRuntime,
				shaderRuntime ? PauCLodShaderProfiles.currentFamily() : PauCLodShaderProfiles.Family.GENERIC,
				shaderRuntime && PauCLodShaderContext.isFallbackActive()
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
				+ transparency
				+ ", shaderRuntime="
				+ shaderRuntime
				+ ", shaderFamily="
				+ shaderFamily
				+ ", shaderFallback="
				+ shaderFallback;
		}

		private boolean isQualityOnlyChange(LodRenderGeometrySignature next) {
			return lodShading == next.lodShading
				&& transparency == next.transparency
				&& shaderRuntime == next.shaderRuntime
				&& shaderFamily == next.shaderFamily
				&& shaderFallback == next.shaderFallback;
		}

		private boolean isShaderPresentationChange(LodRenderGeometrySignature next) {
			return lodShading != next.lodShading
				|| transparency != next.transparency
				|| shaderRuntime != next.shaderRuntime
				|| shaderFamily != next.shaderFamily
				|| shaderFallback != next.shaderFallback;
		}

		private boolean isShaderRuntimeChange(LodRenderGeometrySignature next) {
			return shaderRuntime != next.shaderRuntime
				|| shaderFamily != next.shaderFamily
				|| shaderFallback != next.shaderFallback;
		}

		private boolean requiresMeshCacheClear(LodRenderGeometrySignature next) {
			return maxHorizontalResolution != next.maxHorizontalResolution
				|| horizontalQuality != next.horizontalQuality
				|| verticalQuality != next.verticalQuality;
		}
	}

	private record SavedCoreConfigValue(String configClassName, String fieldName, Object value) {
	}
}
