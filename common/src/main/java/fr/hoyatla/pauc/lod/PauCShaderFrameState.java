package fr.hoyatla.pauc.lod;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.joml.Vector3f;

public record PauCShaderFrameState(
	int frameIndex,
	PauCShaderProfileId profileId,
	PauCLodShaderProfiles.Family compatibilityFamily,
	PauCLodShaderContext.DhShaderMode dhMode,
	PauCLodShaderRuntime.Pressure pressure,
	boolean shaderPackActive,
	boolean fallbackActive,
	boolean lodsActive,
	PauCShaderCapabilities capabilities,
	int vanillaRenderDistanceBlocks,
	int lodStartDistanceBlocks,
	int lodEndDistanceBlocks,
	int lodRoundHorizonDistanceBlocks,
	int lodTransitionWidthBlocks,
	int vanillaFogStartDistanceBlocks,
	int vanillaFogEndDistanceBlocks,
	int shaderStatusCode,
	int shaderReasonCode,
	int shaderCapabilityStatusCode,
	String shaderStatusReason,
	float profileDoFogMix,
	float profileRgbFogMix,
	float profileCommonFogMix,
	float profileBorderAlphaFogMix,
	float profileBlissBorderFogMix,
	float profileNearBlendEndExtra,
	float profileFarFogWidth,
	float profileFarFogStrength,
	float profileWaterGradientStrength,
	float profileWaterEndFogStrength,
	Vector3f profileWaterDeepTone,
	float profileWaterTransparencyStrength,
	float profileShadowJoinNear,
	float profileShadowJoinFar,
	float profileShadowNearStrength,
	float profileShadowSideStrength,
	float profileShadowMax
) {
	private static volatile PauCShaderFrameState cachedState = empty(-1);

	public static PauCShaderFrameState current() {
		int frameIndex = SystemTimeUniforms.COUNTER.getAsInt();
		PauCShaderProfileId currentProfileId = PauCLodShaderContext.currentProfileId();
		boolean shaderPackActive = PauCLodShaderContext.isShaderPackInUse();
		boolean fallbackActive = PauCLodShaderContext.isFallbackActive();
		PauCShaderFrameState snapshot = cachedState;
		if (snapshot.frameIndex() == frameIndex
			&& snapshot.profileId() == currentProfileId
			&& snapshot.shaderPackActive() == shaderPackActive
			&& snapshot.fallbackActive() == fallbackActive) {
			return snapshot;
		}

		// Publish one coherent PauC shader snapshot per rendered frame so every shader
		// program reads the same runtime state even if multiple uniforms are queried.
		PauCShaderFrameState resolved = capture(frameIndex, currentProfileId, shaderPackActive, fallbackActive);
		cachedState = resolved;
		return resolved;
	}

	public boolean nativeProfileActive() {
		return profileId == PauCShaderProfileId.PAUC_NATIVE;
	}

	public boolean underPressure() {
		return pressure == PauCLodShaderRuntime.Pressure.RELIEF;
	}

	public int profileCode() {
		return switch (profileId == null ? PauCShaderProfileId.SHADER_OFF : profileId) {
			case SHADER_OFF -> 0;
			case GENERIC_COMPAT -> 1;
			case PHOTON_COMPAT -> 2;
			case SOLAS_COMPAT -> 3;
			case PAUC_NATIVE -> 4;
		};
	}

	public int compatibilityFamilyCode() {
		return switch (compatibilityFamily == null ? PauCLodShaderProfiles.Family.GENERIC : compatibilityFamily) {
			case GENERIC -> 0;
			case PHOTON -> 1;
			case SOLAS -> 2;
			case COMPLEMENTARY -> 3;
			case RETHINKING -> 4;
			case BSL -> 5;
			case BLISS -> 6;
			case PAUC -> 7;
		};
	}

	public int dhModeCode() {
		return switch (dhMode == null ? PauCLodShaderContext.DhShaderMode.SHADER_OFF : dhMode) {
			case SHADER_OFF -> 0;
			case PENDING -> 1;
			case EXPLICIT_NATIVE -> 2;
			case SYNTHETIC_NATIVE -> 3;
			case FALLBACK -> 4;
			case INCOMPATIBLE -> 5;
		};
	}

	public int pressureCode() {
		return switch (pressure == null ? PauCLodShaderRuntime.Pressure.OFF : pressure) {
			case OFF -> 0;
			case RELIEF -> 1;
			case BALANCED -> 2;
			case HEADROOM -> 3;
		};
	}

	public static boolean currentShaderPackActive() {
		return current().shaderPackActive();
	}

	public static int currentShaderProfileCode() {
		return current().profileCode();
	}

	public static int currentCompatibilityFamilyCode() {
		return current().compatibilityFamilyCode();
	}

	public static int currentDhModeCode() {
		return current().dhModeCode();
	}

	public static int currentPressureCode() {
		return current().pressureCode();
	}

	public static boolean currentNativeProfileActive() {
		return current().nativeProfileActive();
	}

	public static boolean currentFallbackActive() {
		return current().fallbackActive();
	}

	public static boolean currentLodsActive() {
		return current().lodsActive();
	}

	public static boolean currentUnderPressure() {
		return current().underPressure();
	}

	public static boolean currentSupportsDhTerrain() {
		return current().capabilities().supportsDhTerrain();
	}

	public static boolean currentSupportsDhShadow() {
		return current().capabilities().supportsDhShadow();
	}

	public static boolean currentSupportsTransitionFog() {
		return current().capabilities().supportsTransitionFog();
	}

	public static boolean currentSupportsColoredLights() {
		return current().capabilities().supportsColoredLights();
	}

	public static boolean currentSupportsWeatherFog() {
		return current().capabilities().supportsWeatherFog();
	}

	public static int currentRoundHorizonDistanceBlocks() {
		return current().lodRoundHorizonDistanceBlocks();
	}

	public static int currentTransitionWidthBlocks() {
		return current().lodTransitionWidthBlocks();
	}

	public static int currentVanillaFogStartDistanceBlocks() {
		return current().vanillaFogStartDistanceBlocks();
	}

	public static int currentVanillaFogEndDistanceBlocks() {
		return current().vanillaFogEndDistanceBlocks();
	}

	public static int currentShaderStatusCode() {
		return current().shaderStatusCode();
	}

	public static int currentShaderReasonCode() {
		return current().shaderReasonCode();
	}

	public static int currentShaderCapabilityStatusCode() {
		return current().shaderCapabilityStatusCode();
	}

	public static float currentProfileDoFogMix() {
		return current().profileDoFogMix();
	}

	public static float currentProfileRgbFogMix() {
		return current().profileRgbFogMix();
	}

	public static float currentProfileCommonFogMix() {
		return current().profileCommonFogMix();
	}

	public static float currentProfileBorderAlphaFogMix() {
		return current().profileBorderAlphaFogMix();
	}

	public static float currentProfileBlissBorderFogMix() {
		return current().profileBlissBorderFogMix();
	}

	public static float currentProfileNearBlendEndExtra() {
		return current().profileNearBlendEndExtra();
	}

	public static float currentProfileFarFogWidth() {
		return current().profileFarFogWidth();
	}

	public static float currentProfileFarFogStrength() {
		return current().profileFarFogStrength();
	}

	public static float currentProfileWaterGradientStrength() {
		return current().profileWaterGradientStrength();
	}

	public static float currentProfileWaterEndFogStrength() {
		return current().profileWaterEndFogStrength();
	}

	public static Vector3f currentProfileWaterDeepTone() {
		return new Vector3f(current().profileWaterDeepTone());
	}

	public static float currentProfileWaterTransparencyStrength() {
		return current().profileWaterTransparencyStrength();
	}

	public static float currentProfileShadowJoinNear() {
		return current().profileShadowJoinNear();
	}

	public static float currentProfileShadowJoinFar() {
		return current().profileShadowJoinFar();
	}

	public static float currentProfileShadowNearStrength() {
		return current().profileShadowNearStrength();
	}

	public static float currentProfileShadowSideStrength() {
		return current().profileShadowSideStrength();
	}

	public static float currentProfileShadowMax() {
		return current().profileShadowMax();
	}

	private static PauCShaderFrameState capture(
		int frameIndex,
		PauCShaderProfileId currentProfileId,
		boolean shaderPackActive,
		boolean fallbackActive
	) {
		PauCLodRange range = PauCLodHorizonState.currentRange();
		PauCLodShaderProfiles.Profile profile = PauCLodShaderProfiles.current();
		boolean lodsActive = range.enabled();
		int vanillaRenderDistanceBlocks = PauCLodShaderPresentation.vanillaRenderDistanceBlocks();
		int lodStartDistanceBlocks = PauCLodShaderPresentation.lodStartDistanceBlocks();
		int lodEndDistanceBlocks = PauCLodShaderPresentation.lodEndDistanceBlocks();
		int roundHorizonDistanceBlocks = lodsActive ? range.roundHorizonEndChunk() * 16 : 0;
		int transitionWidthBlocks = lodsActive ? Math.max(0, lodEndDistanceBlocks - lodStartDistanceBlocks) : 0;
		int vanillaFogStartBlocks = lodsActive ? Math.round(PauCLodHorizonState.vanillaFogStartBlocks()) : 0;
		int vanillaFogEndBlocks = lodsActive ? Math.round(PauCLodHorizonState.vanillaFogEndBlocks()) : 0;

		return new PauCShaderFrameState(
			frameIndex,
			currentProfileId == null ? PauCShaderProfileId.SHADER_OFF : currentProfileId,
			PauCLodShaderProfiles.currentFamily(),
			PauCLodShaderContext.effectiveDhMode(),
			PauCLodShaderRuntime.pressure(),
			shaderPackActive,
			fallbackActive,
			lodsActive,
			PauCLodShaderContext.currentCapabilities(),
			vanillaRenderDistanceBlocks,
			lodStartDistanceBlocks,
			lodEndDistanceBlocks,
			roundHorizonDistanceBlocks,
			transitionWidthBlocks,
			vanillaFogStartBlocks,
			vanillaFogEndBlocks,
			PauCLodShaderContext.currentStatusCode(),
			PauCLodShaderContext.currentStatusReasonCode(),
			PauCLodShaderContext.currentCapabilities().statusCode(),
			PauCLodShaderContext.currentStatusReason(),
			profile.parsedDoFogMix(),
			profile.parsedRgbFogMix(),
			profile.parsedCommonFogMix(),
			profile.parsedBorderAlphaFogMix(),
			profile.parsedBlissBorderFogMix(),
			profile.parsedNearBlendEndExtra(),
			profile.parsedFarFogWidth(),
			profile.parsedFarFogStrength(),
			profile.parsedWaterGradientStrength(),
			profile.parsedWaterEndFogStrength(),
			profile.parsedWaterDeepTone(),
			profile.parsedWaterTransparencyStrength(),
			profile.parsedLodShadowJoinNear(),
			profile.parsedLodShadowJoinFar(),
			profile.parsedLodShadowNearStrength(),
			profile.parsedLodShadowSideStrength(),
			profile.parsedLodShadowMax()
		);
	}

	private static PauCShaderFrameState empty(int frameIndex) {
		return new PauCShaderFrameState(
			frameIndex,
			PauCShaderProfileId.SHADER_OFF,
			PauCLodShaderProfiles.Family.GENERIC,
			PauCLodShaderContext.DhShaderMode.SHADER_OFF,
			PauCLodShaderRuntime.Pressure.OFF,
			false,
			false,
			false,
			PauCShaderCapabilities.shaderOff(),
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			"",
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
			new Vector3f(0.50F, 0.64F, 0.78F),
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F,
			0.0F
		);
	}
}
