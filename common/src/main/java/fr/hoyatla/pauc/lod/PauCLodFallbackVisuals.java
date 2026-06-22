package fr.hoyatla.pauc.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.slf4j.Logger;

public final class PauCLodFallbackVisuals {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.fallbackVisuals";
	private static final String STRENGTH_PROPERTY = "pauc.lod.fallbackVisualStrength";
	private static final String FOG_INTENSITY_PROPERTY = "pauc.lod.fallbackVisualFogIntensity";
	private static final String FOG_FLOOR_PROPERTY = "pauc.lod.fallbackVisualFogFloor";
	private static final String FOG_START_OFFSET_PROPERTY = "pauc.lod.fallbackVisualFogStartOffsetChunks";
	private static final String FOG_END_MARGIN_PROPERTY = "pauc.lod.fallbackVisualFogEndMargin";
	private static final String BRIGHTNESS_PROPERTY = "pauc.lod.fallbackVisualBrightness";
	private static final String SHADOW_LIFT_PROPERTY = "pauc.lod.fallbackVisualShadowLift";
	private static final String SATURATION_PROPERTY = "pauc.lod.fallbackVisualSaturation";
	private static final String CONTRAST_PROPERTY = "pauc.lod.fallbackVisualContrast";
	private static final String GAMMA_PROPERTY = "pauc.lod.fallbackVisualGamma";
	private static final String DIRECTIONAL_LIGHT_PROPERTY = "pauc.lod.fallbackVisualDirectionalLight";
	private static final String WATER_BLEND_PROPERTY = "pauc.lod.fallbackVisualWaterBlend";
	private static final String FAR_DESATURATION_PROPERTY = "pauc.lod.fallbackVisualFarDesaturation";
	private static final String EMISSIVE_BOOST_PROPERTY = "pauc.lod.fallbackVisualEmissiveBoost";
	private static final String MAX_RESCUE_STRENGTH_PROPERTY = "pauc.lod.fallbackVisualMaxRescueStrength";
	private static final String SEAM_MORPH_PROPERTY = "pauc.lod.shaderOffSeamMorph";
	private static final String SEAM_MORPH_FULL_SPEED_PROPERTY = "pauc.lod.shaderOffSeamMorphFullMotionStrength";
	private static final String SEAM_MORPH_PAUSE_SPEED_PROPERTY = "pauc.lod.shaderOffSeamMorphPauseMotionStrength";
	private static final String SEAM_MORPH_WIDTH_PROPERTY = "pauc.lod.shaderOffSeamMorphWidth";
	private static final String SEAM_MORPH_Y_LIFT_PROPERTY = "pauc.lod.shaderOffSeamMorphYLift";
	private static final String HORIZON_VEIL_START_BEFORE_END_PROPERTY = "pauc.lod.horizonVeilStartBeforeEndChunks";
	private static final String UNDERWATER_VISUALS_PROPERTY = "pauc.lod.underwaterFallbackVisuals";
	private static final String NATIVE_SHADER_UNDERWATER_VISUALS_PROPERTY = "pauc.lod.nativeShaderUnderwaterFallbackVisuals";
	private static final String UNDERWATER_FOG_START_CHUNKS_PROPERTY = "pauc.lod.underwaterFallbackFogStartChunks";
	private static final String UNDERWATER_FOG_END_CHUNKS_PROPERTY = "pauc.lod.underwaterFallbackFogEndChunks";
	private static final String DH_FLAT_SHADER = "assets/distanthorizons/shaders/shared/gl/flat_shaded.frag";
	private static final String DH_STANDARD_VERTEX_SHADER = "assets/distanthorizons/shaders/shared/gl/standard.vert";
	private static final int STANDARD_HORIZON_VEIL_START_BEFORE_END_CHUNKS = 18;
	private static final int LATE_HORIZON_VEIL_START_BEFORE_END_CHUNKS = 6;
	private static final int STANDARD_FOG_END_MARGIN_CHUNKS = 0;
	private static final float STANDARD_FOG_INTENSITY = 1.0F;
	private static final float STANDARD_BRIGHTNESS = 1.0F;
	private static final float STANDARD_SHADOW_LIFT = 0.0F;
	private static final float STANDARD_SATURATION = 1.0F;
	private static final float STANDARD_CONTRAST = 1.0F;
	private static final float STANDARD_GAMMA = 1.0F;
	private static final float STANDARD_FOG_FLOOR = 0.0F;
	private static final float STANDARD_DIRECTIONAL_LIGHT = 0.14F;
	private static final float STANDARD_WATER_BLEND = 0.18F;
	private static final float STANDARD_FAR_DESATURATION = 0.0F;
	private static final float STANDARD_EMISSIVE_BOOST = 0.0F;
	private static final float STANDARD_SEAM_MORPH_WIDTH_BLOCKS = 112.0F;
	private static final float STANDARD_SEAM_MORPH_Y_LIFT = 0.0F;
	private static final int LATE_FOG_END_MARGIN_CHUNKS = 0;
	private static final float LATE_CLEAR_FOG_INTENSITY = 0.56F;
	private static final float LATE_DARK_FOG_INTENSITY = 0.68F;
	private static final float LATE_CLEAR_FOG_FLOOR = 0.0F;
	private static final float LATE_DARK_FOG_FLOOR = 0.0F;
	private static final float LATE_CLEAR_BRIGHTNESS = 1.02F;
	private static final float LATE_DARK_BRIGHTNESS = 1.08F;
	private static final float LATE_CLEAR_SHADOW_LIFT = 0.04F;
	private static final float LATE_DARK_SHADOW_LIFT = 0.08F;
	private static final float LATE_CLEAR_SATURATION = 0.90F;
	private static final float LATE_DARK_SATURATION = 0.72F;
	private static final float LATE_CLEAR_CONTRAST = 0.92F;
	private static final float LATE_DARK_CONTRAST = 0.78F;
	private static final float LATE_CLEAR_GAMMA = 0.98F;
	private static final float LATE_DARK_GAMMA = 0.9F;
	private static final float LATE_CLEAR_DIRECTIONAL_LIGHT = 0.22F;
	private static final float LATE_DARK_DIRECTIONAL_LIGHT = 0.14F;
	private static final float LATE_CLEAR_WATER_BLEND = 0.12F;
	private static final float LATE_DARK_WATER_BLEND = 0.06F;
	private static final float LATE_CLEAR_FAR_DESATURATION = 0.0F;
	private static final float LATE_DARK_FAR_DESATURATION = 0.0F;
	private static final float LATE_CLEAR_EMISSIVE_BOOST = 0.10F;
	private static final float LATE_DARK_EMISSIVE_BOOST = 0.18F;
	private static final int UNDERWATER_FOG_START_CHUNKS = 2;
	private static final int UNDERWATER_FOG_END_CHUNKS = 9;
	private static final float UNDERWATER_FOG_INTENSITY = 0.92F;
	private static final float UNDERWATER_FOG_FLOOR = 0.18F;
	private static final float UNDERWATER_BRIGHTNESS = 0.72F;
	private static final float UNDERWATER_SHADOW_LIFT = 0.0F;
	private static final float UNDERWATER_SATURATION = 0.56F;
	private static final float UNDERWATER_CONTRAST = 0.78F;
	private static final float UNDERWATER_GAMMA = 1.08F;
	private static final float UNDERWATER_DIRECTIONAL_LIGHT = 0.0F;
	private static final float UNDERWATER_WATER_BLEND = 0.58F;
	private static final float UNDERWATER_FAR_DESATURATION = 0.22F;
	private static final float UNDERWATER_EMISSIVE_BOOST = 0.0F;
	private static long lastDiagnosticLogMs;
	private static boolean nativeShaderUnderwaterBypassLogged;
	private static boolean shaderPatchLogged;
	private static boolean shaderPatchFailureLogged;
	private static volatile long stateCacheToken;
	private static volatile long cachedStateToken = Long.MIN_VALUE;
	private static volatile boolean cachedStateHasSeamUpdate;
	private static volatile State cachedState;

	private PauCLodFallbackVisuals() {
	}

	public static State currentState() {
		return currentState(false);
	}

	public static State currentStateWithUpdatedSeam() {
		return currentState(true);
	}

	public static void beginFrameSnapshot() {
		stateCacheToken++;
	}

	private static State currentState(boolean updateSeam) {
		long cacheToken = stateCacheToken;
		State frameCachedState = cachedState;
		if (frameCachedState != null
			&& cachedStateToken == cacheToken
			&& (!updateSeam || cachedStateHasSeamUpdate)) {
			return frameCachedState;
		}

		State computedState = computeState(updateSeam);
		cachedState = computedState;
		cachedStateToken = cacheToken;
		cachedStateHasSeamUpdate = updateSeam;
		return computedState;
	}

	private static State computeState(boolean updateSeam) {
		boolean underwater = shouldUseUnderwaterFallbackVisuals();
		boolean shaderNative = PauCLodShaderContext.isShaderPackInUse() && !PauCLodShaderContext.isFallbackActive();
		if (shaderNative && underwater && !readBoolean(NATIVE_SHADER_UNDERWATER_VISUALS_PROPERTY, false)) {
			underwater = false;
			logNativeShaderUnderwaterBypass();
		}
		boolean shaderNativeOnly = shaderNative && !underwater;
		if (!readBoolean(ENABLED_PROPERTY, true) && !shaderNativeOnly) {
			return State.disabled();
		}

		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			return State.disabled();
		}
		if (shaderNativeOnly) {
			float seamClipDistance = range.lodStartChunk() * 16.0F;
			float seamMorphWidth = readFloat(SEAM_MORPH_WIDTH_PROPERTY, STANDARD_SEAM_MORPH_WIDTH_BLOCKS, 0.0F, 256.0F);
			if (updateSeam) {
				PauCLodSeamState.update(seamClipDistance, seamMorphWidth);
			}
			return State.seamSamplingOnly(seamClipDistance, seamMorphWidth);
		}

		boolean lateRender = PauCLodShaderPresentation.shouldLateRenderFallbackLods();
		float[] fogColor = RenderSystem.getShaderFogColor();
		float[] presentationFogColor = lateRender ? PauCLodScreenFogColor.currentOrFallback(fogColor) : fogColor;
		float rescueStrength = lateRender
			? Math.min(PauCLodScreenFogColor.rescueStrength(presentationFogColor), maxLateRescueStrength())
			: 0.0F;
		int visualEndChunk = PauCLodHorizonState.visualEndChunk();
		int defaultVeilBeforeEndChunks = lateRender
			? LATE_HORIZON_VEIL_START_BEFORE_END_CHUNKS
			: STANDARD_HORIZON_VEIL_START_BEFORE_END_CHUNKS;
		int defaultVeilStartChunk = Math.max(
			range.lodStartChunk(),
			visualEndChunk - readInt(HORIZON_VEIL_START_BEFORE_END_PROPERTY, defaultVeilBeforeEndChunks, 1, 64)
		);
		int defaultFogStartOffset = Math.max(0, defaultVeilStartChunk - range.lodStartChunk());
		int offsetStartChunk = range.lodStartChunk() + readInt(FOG_START_OFFSET_PROPERTY, defaultFogStartOffset, 0, 64);
		float fogStart = Math.max(range.lodStartChunk(), offsetStartChunk) * 16.0F;
		int defaultFogEndMargin = lateRender
			? LATE_FOG_END_MARGIN_CHUNKS
			: STANDARD_FOG_END_MARGIN_CHUNKS;
		float fogEnd = (visualEndChunk + readInt(FOG_END_MARGIN_PROPERTY, defaultFogEndMargin, 0, 64)) * 16.0F;
		if (fogEnd <= fogStart + 16.0F) {
			fogStart = Math.max(range.lodStartChunk() * 16.0F, fogEnd - 16.0F);
		}

		if (underwater) {
			int underwaterStartChunk = readInt(UNDERWATER_FOG_START_CHUNKS_PROPERTY, UNDERWATER_FOG_START_CHUNKS, 0, visualEndChunk);
			int underwaterEndChunk = readInt(UNDERWATER_FOG_END_CHUNKS_PROPERTY, UNDERWATER_FOG_END_CHUNKS, underwaterStartChunk + 1, 64);
			fogStart = underwaterStartChunk * 16.0F;
			fogEnd = Math.max(fogStart + 16.0F, underwaterEndChunk * 16.0F);
		}

		float defaultFogIntensity = lateRender ? lerp(LATE_CLEAR_FOG_INTENSITY, LATE_DARK_FOG_INTENSITY, rescueStrength) : STANDARD_FOG_INTENSITY;
		float defaultFogFloor = lateRender ? lerp(LATE_CLEAR_FOG_FLOOR, LATE_DARK_FOG_FLOOR, rescueStrength) : STANDARD_FOG_FLOOR;
		float defaultBrightness = lateRender ? lerp(LATE_CLEAR_BRIGHTNESS, LATE_DARK_BRIGHTNESS, rescueStrength) : STANDARD_BRIGHTNESS;
		float defaultShadowLift = lateRender ? lerp(LATE_CLEAR_SHADOW_LIFT, LATE_DARK_SHADOW_LIFT, rescueStrength) : STANDARD_SHADOW_LIFT;
		float defaultSaturation = lateRender ? lerp(LATE_CLEAR_SATURATION, LATE_DARK_SATURATION, rescueStrength) : STANDARD_SATURATION;
		float defaultContrast = lateRender ? lerp(LATE_CLEAR_CONTRAST, LATE_DARK_CONTRAST, rescueStrength) : STANDARD_CONTRAST;
		float defaultGamma = lateRender ? lerp(LATE_CLEAR_GAMMA, LATE_DARK_GAMMA, rescueStrength) : STANDARD_GAMMA;
		float defaultDirectionalLight = lateRender ? lerp(LATE_CLEAR_DIRECTIONAL_LIGHT, LATE_DARK_DIRECTIONAL_LIGHT, rescueStrength) : STANDARD_DIRECTIONAL_LIGHT;
		float defaultWaterBlend = lateRender ? lerp(LATE_CLEAR_WATER_BLEND, LATE_DARK_WATER_BLEND, rescueStrength) : STANDARD_WATER_BLEND;
		float defaultFarDesaturation = lateRender ? lerp(LATE_CLEAR_FAR_DESATURATION, LATE_DARK_FAR_DESATURATION, rescueStrength) : STANDARD_FAR_DESATURATION;
		float defaultEmissiveBoost = lateRender ? lerp(LATE_CLEAR_EMISSIVE_BOOST, LATE_DARK_EMISSIVE_BOOST, rescueStrength) : STANDARD_EMISSIVE_BOOST;
		if (underwater) {
			defaultFogIntensity = UNDERWATER_FOG_INTENSITY;
			defaultFogFloor = UNDERWATER_FOG_FLOOR;
			defaultBrightness = UNDERWATER_BRIGHTNESS;
			defaultShadowLift = UNDERWATER_SHADOW_LIFT;
			defaultSaturation = UNDERWATER_SATURATION;
			defaultContrast = UNDERWATER_CONTRAST;
			defaultGamma = UNDERWATER_GAMMA;
			defaultDirectionalLight = UNDERWATER_DIRECTIONAL_LIGHT;
			defaultWaterBlend = UNDERWATER_WATER_BLEND;
			defaultFarDesaturation = UNDERWATER_FAR_DESATURATION;
			defaultEmissiveBoost = UNDERWATER_EMISSIVE_BOOST;
		}

		// The vanilla-fog toggle also governs PauC's own distant LOD fog veil AND the far-distance haze
		// (far desaturation) it applies to the embedded LOD terrain. When the player turns vanilla fog off
		// they expect a fully clear distance, so we drop both — otherwise the far desaturation washes out
		// distant LOD colours just above the horizon and reads as a residual "false fog". Underwater is kept.
		if (!underwater && !PauCLodClientSettings.isVanillaFogEnabled()) {
			defaultFogIntensity = 0.0F;
			defaultFogFloor = 0.0F;
			defaultFarDesaturation = 0.0F;
		}

		float seamClipDistance = PauCLodNearClipOverride.currentBoundaryClipBlocks(range.lodStartChunk() * 16.0F);
		float seamMorphWidth = readFloat(SEAM_MORPH_WIDTH_PROPERTY, STANDARD_SEAM_MORPH_WIDTH_BLOCKS, 0.0F, 256.0F);
		float configuredSeamMorphStrength = configuredShaderOffSeamMorphStrength();
		if (updateSeam) {
			PauCLodSeamState.update(configuredSeamMorphStrength > 0.0F ? seamClipDistance : 0.0F, seamMorphWidth);
		}
		PauCLodSeamState.Snapshot seam = PauCLodSeamState.current();
		State state = new State(
			readFloat(STRENGTH_PROPERTY, 1.0F, 0.0F, 1.0F),
			lateRender,
			underwater,
			color(presentationFogColor, 0),
			color(presentationFogColor, 1),
			color(presentationFogColor, 2),
			1.0F,
			fogStart,
			fogEnd,
			readFloat(FOG_INTENSITY_PROPERTY, defaultFogIntensity, 0.0F, 1.0F),
			readFloat(FOG_FLOOR_PROPERTY, defaultFogFloor, 0.0F, 0.95F),
			readFloat(BRIGHTNESS_PROPERTY, defaultBrightness, 0.5F, 2.0F),
			readFloat(SHADOW_LIFT_PROPERTY, defaultShadowLift, 0.0F, 0.55F),
			readFloat(SATURATION_PROPERTY, defaultSaturation, 0.0F, 1.5F),
			readFloat(CONTRAST_PROPERTY, defaultContrast, 0.0F, 1.5F),
			readFloat(GAMMA_PROPERTY, defaultGamma, 0.25F, 2.0F),
			readFloat(DIRECTIONAL_LIGHT_PROPERTY, defaultDirectionalLight, 0.0F, 0.75F),
			readFloat(WATER_BLEND_PROPERTY, defaultWaterBlend, 0.0F, 0.75F),
			readFloat(FAR_DESATURATION_PROPERTY, defaultFarDesaturation, 0.0F, 1.0F),
			readFloat(EMISSIVE_BOOST_PROPERTY, defaultEmissiveBoost, 0.0F, 0.75F),
			rescueStrength,
			shaderOffSeamMorphStrength(seam, configuredSeamMorphStrength),
			seamClipDistance,
			seamMorphWidth,
			readFloat(SEAM_MORPH_Y_LIFT_PROPERTY, STANDARD_SEAM_MORPH_Y_LIFT, -4.0F, 4.0F),
			seam.cameraX(),
			seam.cameraY(),
			seam.cameraZ(),
			seam.motionX(),
			seam.motionZ(),
			seam.motionStrength(),
			seam.motionWidth(),
			seam.westHeight(),
			seam.eastHeight(),
			seam.northHeight(),
			seam.southHeight(),
			seam.northWestHeight(),
			seam.northEastHeight(),
			seam.southWestHeight(),
			seam.southEastHeight(),
			seam.heightStrength(),
			seam.maxVerticalStep()
		);
		logDiagnostic(state, range);
		return state;
	}

	public static String patchDhShaderSource(String path, String source) {
		if (path == null || source == null) {
			return source;
		}

		if (path.equals(DH_STANDARD_VERTEX_SHADER)) {
			return patchDhVertexShaderSource(path, source);
		}

		if (!path.equals(DH_FLAT_SHADER) || source.contains("uPaucFallbackVisualStrength")) {
			return source;
		}

		String patched = source.replace("\r\n", "\n").replace('\r', '\n');
		if (!patched.contains("uniform bool uDitherDhRendering;") || !patched.contains("void main()")) {
			logShaderPatchFailure(path);
			return source;
		}

		patched = patched.replace(
			"float viewDist = length(vertexWorldPos);",
			"float viewDist = length(vertexWorldPos.xz);"
		);

		patched = patched.replace(
			"uniform bool uDitherDhRendering;\n",
			"uniform bool uDitherDhRendering;\n"
				+ "\n"
				+ "// PauC fallback/vanilla presentation for embedded DH terrain.\n"
				+ "uniform float uPaucFallbackVisualStrength;\n"
				+ "uniform vec4 uPaucFallbackFogColor;\n"
				+ "uniform float uPaucFallbackFogStart;\n"
				+ "uniform float uPaucFallbackFogEnd;\n"
				+ "uniform float uPaucFallbackFogIntensity;\n"
				+ "uniform float uPaucFallbackFogFloor;\n"
				+ "uniform float uPaucFallbackRescueStrength;\n"
				+ "uniform float uPaucFallbackBrightness;\n"
				+ "uniform float uPaucFallbackShadowLift;\n"
				+ "uniform float uPaucFallbackSaturation;\n"
				+ "uniform float uPaucFallbackContrast;\n"
				+ "uniform float uPaucFallbackGamma;\n"
				+ "uniform float uPaucFallbackDirectionalLight;\n"
				+ "uniform float uPaucFallbackWaterBlend;\n"
				+ "uniform float uPaucFallbackFarDesaturation;\n"
				+ "uniform float uPaucFallbackEmissiveBoost;\n"
				+ "uniform float uPaucSeamClipDistance;\n"
				+ "uniform float uPaucSeamMorphWidth;\n"
		);
		if (!patched.contains("uniform float uPaucFallbackVisualStrength;")) {
			logShaderPatchFailure(path);
			return source;
		}

		String withVisualFunction = patched.replace(
			"\n\nvoid main()\n",
			"\n\nvoid applyPaucFallbackVisuals(inout vec4 fragColor, const in float viewDist)\n"
				+ "{\n"
				+ "    float fogBlend = 0.0;\n"
				+ "    if (uPaucFallbackFogIntensity > 0.0 || uPaucFallbackFogFloor > 0.0)\n"
				+ "    {\n"
				+ "        float fogLength = max(uPaucFallbackFogEnd - uPaucFallbackFogStart, 1.0);\n"
				+ "        float fogBase = clamp((viewDist - uPaucFallbackFogStart) / fogLength, 0.0, 1.0);\n"
				+ "        float fogAmount = smoothstep(0.0, 1.0, fogBase);\n"
				+ "        fogBlend = clamp(max(uPaucFallbackFogFloor, fogAmount * uPaucFallbackFogIntensity), 0.0, 1.0);\n"
				+ "    }\n"
				+ "    vec3 color = fragColor.rgb;\n"
				+ "    vec3 originalColor = color;\n"
				+ "    vec3 surfaceNormalRaw = cross(dFdx(vPos.xyz), dFdy(vPos.xyz));\n"
				+ "    vec3 surfaceNormal = surfaceNormalRaw / max(length(surfaceNormalRaw), 0.0001);\n"
				+ "    surfaceNormal = surfaceNormal.y < 0.0 ? -surfaceNormal : surfaceNormal;\n"
				+ "    float sunFacing = clamp(dot(surfaceNormal, normalize(vec3(-0.45, 0.82, -0.35))), 0.0, 1.0);\n"
				+ "    float sideShade = 1.0 - 0.16 * (1.0 - abs(surfaceNormal.y));\n"
				+ "    color *= mix(1.0, sideShade + sunFacing * 0.20, clamp(uPaucFallbackDirectionalLight, 0.0, 1.0));\n"
				+ "    float seamBlendWidth = max(uPaucSeamMorphWidth, 16.0);\n"
				+ "    float joinShadowStart = max(uPaucSeamClipDistance - seamBlendWidth * 0.5, 0.0);\n"
				+ "    float joinShadowEnd = uPaucSeamClipDistance + max(seamBlendWidth * 2.5, 128.0);\n"
				+ "    float joinShadowBand = 1.0 - smoothstep(joinShadowStart, joinShadowEnd, max(abs(vPos.x), abs(vPos.z)));\n"
				+ "    float joinShadowFacing = pow(1.0 - sunFacing, 1.20);\n"
				+ "    float joinShadowStrength = clamp((0.05 + 0.16 * joinShadowFacing) * clamp(uPaucFallbackDirectionalLight * 3.0, 0.0, 1.0), 0.0, 0.24);\n"
				+ "    color *= 1.0 - joinShadowStrength * joinShadowBand;\n"
				+ "    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));\n"
				+ "    color = mix(vec3(luminance), color, uPaucFallbackSaturation);\n"
				+ "    color = (color - vec3(0.5)) * uPaucFallbackContrast + vec3(0.5);\n"
				+ "    color = color * uPaucFallbackBrightness + vec3(uPaucFallbackShadowLift);\n"
				+ "    color = pow(clamp(color, 0.0, 1.0), vec3(max(uPaucFallbackGamma, 0.001)));\n"
				+ "    float waterBlue = smoothstep(0.07, 0.28, max(color.b, originalColor.b) - max(max(color.r, color.g), max(originalColor.r, originalColor.g)));\n"
				+ "    float waterCyan = smoothstep(0.04, 0.20, min(max(color.g, originalColor.g), max(color.b, originalColor.b)) - max(color.r, originalColor.r));\n"
				+ "    float waterPlane = smoothstep(0.66, 0.94, abs(surfaceNormal.y));\n"
				+ "    float waterRange = smoothstep(0.16, 0.42, max(color.b, originalColor.b));\n"
				+ "    float waterLike = clamp(max(waterBlue, waterCyan * waterPlane * waterRange), 0.0, 1.0);\n"
				+ "    float verticalFace = smoothstep(0.18, 0.72, 1.0 - abs(surfaceNormal.y));\n"
				+ "    float seamDistance = max(abs(vPos.x), abs(vPos.z));\n"
				+ "    float seamBand = 1.0 - smoothstep(uPaucSeamClipDistance, uPaucSeamClipDistance + max(uPaucSeamMorphWidth, 16.0), seamDistance);\n"
				+ "    seamBand *= smoothstep(max(uPaucSeamClipDistance - max(uPaucSeamMorphWidth, 16.0), 0.0), uPaucSeamClipDistance + 8.0, seamDistance);\n"
				+ "    float waterWall = clamp(waterLike * verticalFace * seamBand, 0.0, 1.0);\n"
				+ "    float waterWallCut = waterWall * smoothstep(uPaucSeamClipDistance - max(uPaucSeamMorphWidth, 16.0), uPaucSeamClipDistance + 24.0, seamDistance);\n"
				+ "    waterWall *= 1.0 - clamp(waterWallCut * 0.55, 0.0, 0.55);\n"
				+ "    float waterNear = smoothstep(max(uPaucFallbackFogStart - 64.0, 0.0), uPaucFallbackFogStart + 96.0, viewDist);\n"
				+ "    float waterMid = smoothstep(uPaucFallbackFogStart + 48.0, max(uPaucFallbackFogEnd - 160.0, uPaucFallbackFogStart + 96.0), viewDist);\n"
				+ "    float waterFar = smoothstep(max(uPaucFallbackFogEnd - 192.0, uPaucFallbackFogStart + 128.0), uPaucFallbackFogEnd, viewDist);\n"
				+ "    vec3 nearWater = color * vec3(0.74, 0.85, 0.93);\n"
				+ "    vec3 midWater = color * vec3(0.62, 0.77, 0.88);\n"
				+ "    vec3 farWater = color * vec3(0.50, 0.66, 0.80);\n"
				+ "    vec3 gradedWater = mix(color, min(nearWater, color), 0.35 * waterNear);\n"
				+ "    gradedWater = mix(gradedWater, min(midWater, gradedWater), 0.55 * waterMid);\n"
				+ "    gradedWater = mix(gradedWater, min(farWater, gradedWater), 0.70 * waterFar);\n"
				+ "    gradedWater = mix(gradedWater, mix(gradedWater, uPaucFallbackFogColor.rgb, 0.18), waterWall);\n"
				+ "    color = mix(color, gradedWater, clamp(uPaucFallbackWaterBlend, 0.0, 1.0) * waterLike);\n"
				+ "    color = mix(color, mix(color, uPaucFallbackFogColor.rgb, 0.12), waterWall * clamp(uPaucFallbackWaterBlend + 0.18, 0.0, 1.0));\n"
				+ "    float farTone = smoothstep(max(uPaucFallbackFogStart - 192.0, 0.0), uPaucFallbackFogEnd, viewDist);\n"
				+ "    float farLum = dot(color, vec3(0.2126, 0.7152, 0.0722));\n"
				+ "    color = mix(color, mix(vec3(farLum), color, 0.72), clamp(uPaucFallbackFarDesaturation, 0.0, 1.0) * farTone);\n"
				+ "    float greyVeilStrength = mix(0.012, 0.055, clamp(uPaucFallbackRescueStrength, 0.0, 1.0));\n"
				+ "    float greyVeil = greyVeilStrength * farTone * (1.0 - 0.45 * waterLike);\n"
				+ "    color = mix(color, mix(vec3(farLum), vec3(0.46, 0.48, 0.50), 0.32), greyVeil);\n"
				+ "    float warmLight = smoothstep(0.58, 0.92, dot(originalColor, vec3(0.333))) * smoothstep(-0.03, 0.18, originalColor.r - originalColor.b);\n"
				+ "    color = mix(color, max(color, originalColor * 1.10), clamp(uPaucFallbackEmissiveBoost, 0.0, 1.0) * warmLight);\n"
				+ "    color = mix(color, uPaucFallbackFogColor.rgb, fogBlend);\n"
				+ "    fragColor.rgb = mix(fragColor.rgb, clamp(color, 0.0, 1.0), clamp(uPaucFallbackVisualStrength, 0.0, 1.0));\n"
				+ "    fragColor.a *= 1.0 - clamp(waterWall * 0.55, 0.0, 0.55);\n"
				+ "}\n"
				+ "\n"
				+ "void main()\n"
		);
		if (withVisualFunction.equals(patched)) {
			logShaderPatchFailure(path);
			return source;
		}
		patched = withVisualFunction;

		String patchedMain = patched.replace(
			"    if (uNoiseEnabled)\n"
				+ "    {\n"
				+ "        applyNoise(fragColor, viewDist);\n"
				+ "    }\n"
				+ "}\n",
			"    if (uNoiseEnabled)\n"
				+ "    {\n"
				+ "        applyNoise(fragColor, viewDist);\n"
				+ "    }\n"
				+ "\n"
				+ "    if (uPaucFallbackVisualStrength > 0.0)\n"
				+ "    {\n"
				+ "        applyPaucFallbackVisuals(fragColor, viewDist);\n"
				+ "    }\n"
				+ "}\n"
		);
		if (patchedMain.equals(patched)) {
			int mainEnd = patched.lastIndexOf("\n}");
			if (mainEnd < 0) {
				logShaderPatchFailure(path);
				return source;
			}
			patchedMain = patched.substring(0, mainEnd)
				+ "\n"
				+ "    if (uPaucFallbackVisualStrength > 0.0)\n"
				+ "    {\n"
				+ "        applyPaucFallbackVisuals(fragColor, viewDist);\n"
				+ "    }\n"
				+ patched.substring(mainEnd);
		}
		logShaderPatchApplied(path);
		return patchedMain;
	}

	private static String patchDhVertexShaderSource(String path, String source) {
		if (source.contains("uPaucSeamMorphStrength")) {
			return source;
		}

		String patched = source.replace("\r\n", "\n").replace('\r', '\n');
		if (!patched.contains("uniform float uEarthRadius;") || !patched.contains("gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);")) {
			logShaderPatchFailure(path);
			return source;
		}

		patched = patched.replace(
			"uniform float uEarthRadius;\n",
			"uniform float uEarthRadius;\n"
				+ "\n"
				+ "// PauC shader-off seam morphing for the vanilla/LOD boundary.\n"
				+ "uniform float uPaucSeamMorphStrength;\n"
				+ "uniform float uPaucSeamClipDistance;\n"
				+ "uniform float uPaucSeamMorphWidth;\n"
				+ "uniform float uPaucSeamYLift;\n"
				+ "uniform vec3 uPaucSeamCameraPos;\n"
				+ "uniform vec2 uPaucSeamMotion;\n"
				+ "uniform float uPaucSeamMotionStrength;\n"
				+ "uniform float uPaucSeamMotionWidth;\n"
				+ "uniform vec4 uPaucSeamEdgeHeights;\n"
				+ "uniform vec4 uPaucSeamCornerHeights;\n"
				+ "uniform float uPaucSeamHeightStrength;\n"
				+ "uniform float uPaucSeamMaxVerticalStep;\n"
	);

		patched = patched.replace(
			"    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);\n",
			"    if (uPaucSeamMorphStrength > 0.0 && uPaucSeamMorphWidth > 0.0 && uPaucSeamClipDistance > 0.0)\n"
				+ "    {\n"
				+ "        float seamDistance = max(abs(vertexWorldPos.x), abs(vertexWorldPos.z));\n"
				+ "        float effectiveWidth = uPaucSeamMorphWidth + max(uPaucSeamMotionWidth, 0.0);\n"
				+ "        float seamFactor = 1.0 - smoothstep(uPaucSeamClipDistance, uPaucSeamClipDistance + effectiveWidth, seamDistance);\n"
				+ "        seamFactor *= clamp(uPaucSeamMorphStrength, 0.0, 1.0);\n"
				+ "        float xEdge = 1.0 - smoothstep(uPaucSeamClipDistance - effectiveWidth, uPaucSeamClipDistance + effectiveWidth, abs(vertexWorldPos.x));\n"
				+ "        float zEdge = 1.0 - smoothstep(uPaucSeamClipDistance - effectiveWidth, uPaucSeamClipDistance + effectiveWidth, abs(vertexWorldPos.z));\n"
				+ "        float cornerFactor = clamp(min(xEdge, zEdge) * 1.35, 0.0, 1.0);\n"
				+ "        vec2 sideAxis = abs(vertexWorldPos.x) > abs(vertexWorldPos.z) ? vec2(sign(vertexWorldPos.x), 0.0) : vec2(0.0, sign(vertexWorldPos.z));\n"
				+ "        vec2 cornerAxis = normalize(vec2(sign(vertexWorldPos.x), sign(vertexWorldPos.z)));\n"
				+ "        vec2 axis = mix(sideAxis, cornerAxis, cornerFactor);\n"
				+ "        float aheadFactor = max(dot(axis, uPaucSeamMotion), 0.0) * clamp(uPaucSeamMotionStrength, 0.0, 1.0);\n"
				+ "        seamFactor = clamp(seamFactor * (1.0 + aheadFactor * 0.85), 0.0, 1.0);\n"
				+ "        vertexWorldPos.xz += axis * seamFactor * (0.18 + aheadFactor * 0.32);\n"
				+ "        vertexWorldPos.y += seamFactor * uPaucSeamYLift;\n"
				+ "        vec2 seamUv = clamp(vertexWorldPos.xz / max(uPaucSeamClipDistance, 1.0) * 0.5 + 0.5, vec2(0.0), vec2(1.0));\n"
				+ "        float northCornerLine = mix(uPaucSeamCornerHeights.x, uPaucSeamCornerHeights.y, seamUv.x);\n"
				+ "        float southCornerLine = mix(uPaucSeamCornerHeights.z, uPaucSeamCornerHeights.w, seamUv.x);\n"
				+ "        float westCornerLine = mix(uPaucSeamCornerHeights.x, uPaucSeamCornerHeights.z, seamUv.y);\n"
				+ "        float eastCornerLine = mix(uPaucSeamCornerHeights.y, uPaucSeamCornerHeights.w, seamUv.y);\n"
				+ "        float northCurve = northCornerLine + (uPaucSeamEdgeHeights.z - northCornerLine) * 4.0 * seamUv.x * (1.0 - seamUv.x);\n"
				+ "        float southCurve = southCornerLine + (uPaucSeamEdgeHeights.w - southCornerLine) * 4.0 * seamUv.x * (1.0 - seamUv.x);\n"
				+ "        float westCurve = westCornerLine + (uPaucSeamEdgeHeights.x - westCornerLine) * 4.0 * seamUv.y * (1.0 - seamUv.y);\n"
				+ "        float eastCurve = eastCornerLine + (uPaucSeamEdgeHeights.y - eastCornerLine) * 4.0 * seamUv.y * (1.0 - seamUv.y);\n"
				+ "        float heightNorthSouth = mix(northCurve, southCurve, seamUv.y);\n"
				+ "        float heightWestEast = mix(westCurve, eastCurve, seamUv.x);\n"
				+ "        float cornerBilinear = mix(northCornerLine, southCornerLine, seamUv.y);\n"
				+ "        float targetHeight = heightNorthSouth + heightWestEast - cornerBilinear;\n"
				+ "        float targetRelativeY = targetHeight - uPaucSeamCameraPos.y;\n"
				+ "        float surfaceDelta = targetRelativeY - vertexWorldPos.y;\n"
				+ "        float surfaceMask = 1.0 - smoothstep(8.0, max(16.0, uPaucSeamMaxVerticalStep * 5.0), abs(surfaceDelta));\n"
				+ "        float verticalDelta = clamp(surfaceDelta, -uPaucSeamMaxVerticalStep, uPaucSeamMaxVerticalStep);\n"
				+ "        vertexWorldPos.y += verticalDelta * seamFactor * surfaceMask * clamp(uPaucSeamHeightStrength, 0.0, 1.0);\n"
				+ "    }\n"
				+ "\n"
				+ "    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);\n"
		);

		logShaderPatchApplied(path);
		return patched;
	}

	private static void logShaderPatchApplied(String path) {
		if (!shaderPatchLogged) {
			shaderPatchLogged = true;
			LOGGER.info("PauC patched DH fallback terrain shader for visible late LOD presentation: {}", path);
		}
	}

	private static void logShaderPatchFailure(String path) {
		if (!shaderPatchFailureLogged) {
			shaderPatchFailureLogged = true;
			LOGGER.warn("PauC could not patch DH fallback terrain shader; late LOD visual uniforms will be unavailable: {}", path);
		}
	}

	private static void logNativeShaderUnderwaterBypass() {
		if (!nativeShaderUnderwaterBypassLogged) {
			nativeShaderUnderwaterBypassLogged = true;
			LOGGER.info("PauC keeps native shader underwater/fog presentation for DH LOD terrain; fallback underwater visuals bypassed.");
		}
	}

	private static float color(float[] fogColor, int index) {
		if (fogColor == null || fogColor.length <= index) {
			return 1.0F;
		}
		return clamp(fogColor[index], 0.0F, 1.0F);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}

		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue)));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}

		try {
			return clamp(Float.parseFloat(rawValue), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float lerp(float start, float end, float factor) {
		return start + (end - start) * clamp(factor, 0.0F, 1.0F);
	}

	private static boolean shouldUseUnderwaterFallbackVisuals() {
		if (!readBoolean(UNDERWATER_VISUALS_PROPERTY, true)) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null
			&& minecraft.gameRenderer != null
			&& minecraft.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER;
	}

	private static float maxLateRescueStrength() {
		float fallback = switch (PauCLodShaderProfiles.currentFamily()) {
			case PHOTON -> 0.40F;
			case SOLAS -> 0.48F;
			case BSL, COMPLEMENTARY, RETHINKING -> 0.36F;
			case BLISS -> 0.44F;
			case PAUC -> 0.42F;
			case GENERIC -> 0.55F;
		};
		return readFloat(MAX_RESCUE_STRENGTH_PROPERTY, fallback, 0.0F, 1.0F);
	}

	private static float shaderOffSeamMorphStrength(PauCLodSeamState.Snapshot seam, float configuredStrength) {
		if (PauCLodShaderContext.isShaderPackInUse()) {
			return 0.0F;
		}
		if (configuredStrength <= 0.0F) {
			return 0.0F;
		}
		float fullSpeed = readFloat(SEAM_MORPH_FULL_SPEED_PROPERTY, 0.34F, 0.0F, 1.0F);
		float pauseSpeed = readFloat(SEAM_MORPH_PAUSE_SPEED_PROPERTY, 0.58F, fullSpeed, 1.0F);
		float motionStrength = seam.motionStrength();
		if (motionStrength <= fullSpeed) {
			return configuredStrength;
		}
		if (motionStrength >= pauseSpeed) {
			return 0.0F;
		}
		float fade = (motionStrength - fullSpeed) / Math.max(0.001F, pauseSpeed - fullSpeed);
		return configuredStrength * (1.0F - fade);
	}

	private static float configuredShaderOffSeamMorphStrength() {
		String rawValue = System.getProperty(SEAM_MORPH_PROPERTY);
		if (rawValue == null) {
			return 1.0F;
		}
		String normalized = rawValue.trim();
		if (normalized.equalsIgnoreCase("true")) {
			return 1.0F;
		}
		if (normalized.equalsIgnoreCase("false")) {
			return 0.0F;
		}
		try {
			return clamp(Float.parseFloat(normalized), 0.0F, 1.0F);
		} catch (NumberFormatException ignored) {
			return 1.0F;
		}
	}

	private static void logDiagnostic(State state, PauCLodRange range) {
		long now = System.currentTimeMillis();
		if (now - lastDiagnosticLogMs < 5000L) {
			return;
		}

		lastDiagnosticLogMs = now;
		LOGGER.info(
			"PauC fallback LOD visuals: mode={}, fog={}..{} chunks, intensity={}, floor={}, rescue={}, brightness={}, shadowLift={}, saturation={}, contrast={}, gamma={}, direction={}, water={}, farDesat={}, emissive={}, {}",
			state.underwater() ? "underwater" : state.lateRender() ? "late" : "inline",
			blocksToChunks(state.fogStartBlocks()),
			blocksToChunks(state.fogEndBlocks()),
			state.fogIntensity(),
			state.fogFloor(),
			state.rescueStrength(),
			state.brightness(),
			state.shadowLift(),
			state.saturation(),
			state.contrast(),
			state.gamma(),
			state.directionalLight(),
			state.waterBlend(),
			state.farDesaturation(),
			state.emissiveBoost(),
			range.describe()
		);
	}

	private static int blocksToChunks(float blocks) {
		return Math.round(blocks / 16.0F);
	}

	public record State(
		float strength,
		boolean lateRender,
		boolean underwater,
		float fogRed,
		float fogGreen,
		float fogBlue,
		float fogAlpha,
		float fogStartBlocks,
		float fogEndBlocks,
		float fogIntensity,
		float fogFloor,
		float brightness,
		float shadowLift,
		float saturation,
		float contrast,
		float gamma,
		float directionalLight,
		float waterBlend,
		float farDesaturation,
		float emissiveBoost,
		float rescueStrength,
		float seamMorphStrength,
		float seamClipDistance,
		float seamMorphWidth,
		float seamYLift,
		float seamCameraX,
		float seamCameraY,
		float seamCameraZ,
		float seamMotionX,
		float seamMotionZ,
		float seamMotionStrength,
		float seamMotionWidth,
		float seamWestHeight,
		float seamEastHeight,
		float seamNorthHeight,
		float seamSouthHeight,
		float seamNorthWestHeight,
		float seamNorthEastHeight,
		float seamSouthWestHeight,
		float seamSouthEastHeight,
		float seamHeightStrength,
		float seamMaxVerticalStep
	) {
		private static final State DISABLED = new State(
			0.0F,
			false,
			false,
			1.0F,
			1.0F,
			1.0F,
			1.0F,
			0.0F,
			1.0F,
			0.0F,
			0.0F,
			1.0F,
			0.0F,
			1.0F,
			1.0F,
			1.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F
		);

		private static State seamSamplingOnly(float seamClipDistance, float seamMorphWidth) {
			PauCLodSeamState.Snapshot seam = PauCLodSeamState.current();
			return new State(
				0.0F,
				false,
				false,
				1.0F,
				1.0F,
				1.0F,
				1.0F,
				0.0F,
				1.0F,
				0.0F,
				0.0F,
				1.0F,
				0.0F,
				1.0F,
				1.0F,
				1.0F,
				0.0F,
				0.0F,
				0.0F,
				0.0F,
				0.0F,
				0.0F,
				seamClipDistance,
				seamMorphWidth,
				0.0F,
				seam.cameraX(),
				seam.cameraY(),
				seam.cameraZ(),
				seam.motionX(),
				seam.motionZ(),
				seam.motionStrength(),
				seam.motionWidth(),
				seam.westHeight(),
				seam.eastHeight(),
				seam.northHeight(),
				seam.southHeight(),
				seam.northWestHeight(),
				seam.northEastHeight(),
				seam.southWestHeight(),
				seam.southEastHeight(),
				seam.heightStrength(),
				seam.maxVerticalStep()
			);
		}

		private static State disabled() {
			return DISABLED;
		}
	}
}
