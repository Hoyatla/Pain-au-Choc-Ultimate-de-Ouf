package net.irisshaders.iris.pipeline;

import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCShaderCapabilities;
import fr.hoyatla.pauc.lod.PauCShaderProfileId;
import net.irisshaders.iris.Iris;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PauCShaderPackProgramPatches {
	private static final Pattern PHOTON_CLOUD_DEPTH_DECLARATION = Pattern.compile(
		"float\\s+depth\\s*=\\s*texelFetch\\s*\\(\\s*depthtex1\\s*,\\s*dst_texel\\s*,\\s*0\\s*\\)\\s*\\.\\s*x\\s*;",
		Pattern.MULTILINE
	);
	private static final Pattern PHOTON_CLOUD_TERRAIN_DEPTH_TEST = Pattern.compile(
		"if\\s*\\(\\s*depth\\s*!=\\s*1\\.0\\s*\\)",
		Pattern.MULTILINE
	);
	private static final Pattern PHOTON_CLOUD_TERRAIN_DISTANCE = Pattern.compile(
		"float\\s+view_distance_squared\\s*=\\s*"
			+ "length_squared\\s*\\(\\s*screen_to_view_space\\s*\\(\\s*vec3\\s*\\(\\s*uv\\s*,\\s*depth\\s*\\)\\s*,\\s*true\\s*\\)\\s*\\)\\s*;",
		Pattern.MULTILINE
	);
	private static final Pattern PHOTON_CLOUD_HISTORY_WRITE = Pattern.compile(
		"clouds_history\\s*=\\s*max0\\s*\\(\\s*mix\\s*\\(\\s*current\\s*,\\s*history\\s*,\\s*history_weight\\s*\\)\\s*\\)\\s*;",
		Pattern.MULTILINE
	);
	private static final Pattern PHOTON_BORDER_FOG_UNIFORM_DECLARATION = Pattern.compile(
		"uniform\\s+int\\s+paucVanillaFogEndDistance\\s*;",
		Pattern.MULTILINE
	);
	private static final Pattern PHOTON_BORDER_FOG_SHADOW_COMPENSATION = Pattern.compile(
		"if\\s*\\(\\s*paucVanillaFogEndDistance\\s*>\\s*paucVanillaFogStartDistance\\s*&&\\s*"
			+ "paucVanillaFogEndDistance\\s*>\\s*0\\s*\\)\\s*\\{\\s*"
			+ "float\\s+shadow_fog_start\\s*=\\s*max\\s*\\(\\s*float\\s*\\(\\s*paucVanillaFogStartDistance\\s*\\)\\s*,\\s*32\\.0\\s*\\)\\s*;\\s*"
			+ "float\\s+shadow_fog_end\\s*=\\s*max\\s*\\(\\s*float\\s*\\(\\s*paucVanillaFogEndDistance\\s*\\)\\s*,\\s*shadow_fog_start\\s*\\+\\s*16\\.0\\s*\\)\\s*;\\s*"
			+ "float\\s+shadow_fog_blend\\s*=\\s*smoothstep\\s*\\(\\s*shadow_fog_start\\s*,\\s*shadow_fog_end\\s*,\\s*view_distance\\s*\\)\\s*;\\s*"
			+ "fog\\s*\\*=\\s*1\\.0\\s*-\\s*0\\.82\\s*\\*\\s*shadow_fog_blend\\s*;\\s*\\}",
		Pattern.MULTILINE
	);
	private static final Pattern PHOTON_CLOUD_RESULT_WRITE = Pattern.compile(
		"clouds\\s*\\.\\s*xyz\\s*=\\s*result\\s*\\.\\s*scattering\\s*\\.\\s*xyz\\s*;\\s*"
			+ "clouds\\s*\\.\\s*w\\s*=\\s*result\\s*\\.\\s*transmittance\\s*;",
		Pattern.MULTILINE
	);
	private static boolean photonCloudDepthPatchLogged;
	private static boolean photonCloudHistoryPatchLogged;
	private static boolean photonCloudEdgePatchLogged;
	private static boolean photonShadowFogPatchLogged;
	private static boolean paucNativeHeaderPatchLogged;
	private static volatile int photonCloudDepthPatchCount;
	private static volatile int photonCloudHistoryPatchCount;
	private static volatile int photonCloudEdgePatchCount;
	private static volatile int photonShadowFogPatchCount;
	private static volatile int paucNativeHeaderPatchCount;
	private static volatile PauCLodShaderProfiles.Family lastShaderFamily = PauCLodShaderProfiles.Family.GENERIC;
	private static volatile PauCShaderProfileId lastProfileId = PauCShaderProfileId.SHADER_OFF;

	private PauCShaderPackProgramPatches() {
	}

	public static String patchBeforeTransform(String programName, String source, String stage) {
		if (source == null) {
			return null;
		}

		refreshState();
		if (lastProfileId == PauCShaderProfileId.PAUC_NATIVE) {
			return patchPaucNativeHeader(programName, source, stage);
		}
		return source;
	}

	public static String patchVertex(String programName, String source) {
		if (source == null) {
			return null;
		}

		refreshState();
		return source;
	}

	public static String patchFragment(String programName, String source) {
		if (source == null) {
			return null;
		}

		refreshState();
		if (lastProfileId == PauCShaderProfileId.PAUC_NATIVE) {
			return source;
		}
		return switch (lastShaderFamily) {
			case PHOTON -> patchPhotonPrograms(programName, source);
			default -> source;
		};
	}

	public static String describeState() {
		return "shaderPatches[family="
			+ lastShaderFamily.name().toLowerCase(java.util.Locale.ROOT)
			+ ", profile="
			+ lastProfileId.id()
			+ ", photonDepth="
			+ photonCloudDepthPatchCount
			+ ", photonHistory="
			+ photonCloudHistoryPatchCount
			+ ", photonEdge="
			+ photonCloudEdgePatchCount
			+ ", photonShadowFog="
			+ photonShadowFogPatchCount
			+ ", nativeHeader="
			+ paucNativeHeaderPatchCount
			+ "]";
	}

	private static String patchPaucNativeHeader(String programName, String source, String stage) {
		if (source.contains("PAUC_SHADERPACK_NATIVE")) {
			return source;
		}

		String patched = injectHeaderAfterVersion(source, buildPaucNativeHeader());
		if (!patched.equals(source)) {
			paucNativeHeaderPatchCount++;
			if (!paucNativeHeaderPatchLogged) {
				paucNativeHeaderPatchLogged = true;
				Iris.logger.info("PauC attached native shaderpack header to {} {}.", stage, programName);
			}
		}
		return patched;
	}

	private static void refreshState() {
		lastProfileId = PauCLodShaderProfiles.currentProfileId();
		lastShaderFamily = PauCLodShaderProfiles.currentFamily();
	}

	private static String buildPaucNativeHeader() {
		PauCShaderCapabilities capabilities = PauCLodShaderProfiles.currentProfileId() == PauCShaderProfileId.PAUC_NATIVE
			? fr.hoyatla.pauc.lod.PauCLodShaderContext.currentCapabilities()
			: PauCShaderCapabilities.shaderOff();
		int familyCode = switch (PauCLodShaderProfiles.currentFamily()) {
			case GENERIC -> 0;
			case PHOTON -> 1;
			case SOLAS -> 2;
			case COMPLEMENTARY -> 3;
			case RETHINKING -> 4;
			case BSL -> 5;
			case BLISS -> 6;
			case PAUC -> 7;
		};
		return """
			#define PAUC_SHADER_CONTRACT_VERSION 1
			#define PAUC_SHADERPACK_NATIVE 1
			#define PAUC_SHADER_PROFILE_CODE 4
			#define PAUC_SHADER_FAMILY_CODE %d
			#define PAUC_SHADER_FAMILY_PAUC 1
			#define PAUC_SHADER_CAP_DH_TERRAIN %d
			#define PAUC_SHADER_CAP_DH_SHADOW %d
			#define PAUC_SHADER_CAP_TRANSITION_FOG %d
			#define PAUC_SHADER_CAP_COLORED_LIGHTS %d
			#define PAUC_SHADER_CAP_WEATHER_FOG %d
			
			""".formatted(
			familyCode,
			capabilities.supportsDhTerrain() ? 1 : 0,
			capabilities.supportsDhShadow() ? 1 : 0,
			capabilities.supportsTransitionFog() ? 1 : 0,
			capabilities.supportsColoredLights() ? 1 : 0,
			capabilities.supportsWeatherFog() ? 1 : 0
		);
	}

	private static String injectHeaderAfterVersion(String source, String header) {
		int versionIndex = source.indexOf("#version");
		if (versionIndex < 0) {
			return header + source;
		}

		int insertionIndex = source.indexOf('\n', versionIndex);
		if (insertionIndex < 0) {
			return source + "\n" + header;
		}
		return source.substring(0, insertionIndex + 1) + header + source.substring(insertionIndex + 1);
	}

	private static String patchPhotonPrograms(String programName, String source) {
		if (programName == null) {
			return source;
		}

		String patched = patchPhotonCloudLodDepth(programName, source);
		patched = patchPhotonCloudHistory(programName, patched);
		patched = patchPhotonCloudEdgeFog(programName, patched);
		patched = patchPhotonShadowFog(programName, patched);
		return patched;
	}

	private static String patchPhotonCloudLodDepth(String programName, String source) {
		if (source.contains("paucPhotonCloudTerrainDepth")
			|| !source.contains("combined_projection_matrix_inverse")
			|| !source.contains("combined_depth")
			|| !source.contains("closest_distance")) {
			return source;
		}

		Matcher depthMatcher = PHOTON_CLOUD_DEPTH_DECLARATION.matcher(source);
		if (!depthMatcher.find()) {
			return source;
		}
		String depthDeclaration = depthMatcher.group(0)
			+ """
			
			    float paucPhotonCloudTerrainDepth = depth;
			#ifdef LOD_MOD_ACTIVE
			    paucPhotonCloudTerrainDepth = combined_depth;
			#endif""";
		String withDepth = depthMatcher.replaceFirst(Matcher.quoteReplacement(depthDeclaration));
		String withDepthTest = PHOTON_CLOUD_TERRAIN_DEPTH_TEST.matcher(withDepth)
			.replaceFirst("if (paucPhotonCloudTerrainDepth != 1.0)");
		if (withDepthTest.equals(withDepth)) {
			return source;
		}
		String distanceReplacement = """
			float view_distance_squared =
			#ifdef LOD_MOD_ACTIVE
			    length_squared(screen_to_view_space(combined_projection_matrix_inverse, vec3(uv, paucPhotonCloudTerrainDepth), true));
			#else
			    length_squared(screen_to_view_space(vec3(uv, paucPhotonCloudTerrainDepth), true));
			#endif
			""";
		String patched = PHOTON_CLOUD_TERRAIN_DISTANCE.matcher(withDepthTest)
			.replaceFirst(Matcher.quoteReplacement(distanceReplacement));
		if (patched.equals(withDepthTest)) {
			return source;
		}
		if (!photonCloudDepthPatchLogged) {
			photonCloudDepthPatchLogged = true;
			Iris.logger.info("PauC patched Photon cloud upscaling to occlude against DH LOD depth: {}.", programName);
		}
		photonCloudDepthPatchCount++;
		return patched;
	}

	private static String patchPhotonCloudHistory(String programName, String source) {
		if (source.contains("paucPhotonCloudHistoryClamp")
			|| !source.contains("clouds_history")
			|| !source.contains("history_weight")
			|| !source.contains("is_lod")) {
			return source;
		}

		String replacement = """
			#ifdef LOD_MOD_ACTIVE
			    if (is_lod) {
			        history_weight = min(history_weight * 0.30, 0.74); // paucPhotonCloudHistoryClamp
			    }
			#endif
			    clouds_history = max0(mix(current, history, history_weight));""";
		Matcher matcher = PHOTON_CLOUD_HISTORY_WRITE.matcher(source);
		String patched = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
		if (!patched.equals(source) && !photonCloudHistoryPatchLogged) {
			photonCloudHistoryPatchLogged = true;
			Iris.logger.info("PauC reduced Photon cloud temporal ghosting over DH LOD depth: {}.", programName);
		}
		if (!patched.equals(source)) {
			photonCloudHistoryPatchCount++;
		}
		return patched;
	}

	private static String patchPhotonCloudEdgeFog(String programName, String source) {
		if (source.contains("paucPhotonCloudEdgeFog")
			|| !source.contains("draw_clouds")
			|| !source.contains("clear_sky")
			|| !source.contains("is_lod")
			|| !source.contains("result.apparent_distance")
			|| !source.contains("uniform float far")) {
			return source;
		}

		String replacement = """
			    clouds.xyz = result.scattering.xyz;
			    clouds.w = result.transmittance;
			#ifdef LOD_MOD_ACTIVE
			    {
			        float paucPhotonCloudDistance = result.apparent_distance * rcp(CLOUDS_SCALE);
			        float paucPhotonCloudRangeFog = smoothstep(max(far * 0.95, 224.0), max(far * 1.85, 512.0), paucPhotonCloudDistance);
			        float paucPhotonCloudEdgeFog = 1.0 - smoothstep(0.06, 0.42, 1.0 - clouds.w);
			        float paucPhotonCloudFog = max(0.55 * paucPhotonCloudEdgeFog, 0.16 * paucPhotonCloudRangeFog);
			        float paucPhotonCloudStrength = is_lod ? 0.62 : 0.12 * paucPhotonCloudRangeFog;
			        clouds.xyz = mix(clouds.xyz, clear_sky, 0.16 * paucPhotonCloudStrength * paucPhotonCloudFog);
			        clouds.w = mix(clouds.w, 1.0, 0.08 * paucPhotonCloudStrength * paucPhotonCloudFog);
			    }
			#endif""";
		Matcher matcher = PHOTON_CLOUD_RESULT_WRITE.matcher(source);
		String patched = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
		if (!patched.equals(source) && !photonCloudEdgePatchLogged) {
			photonCloudEdgePatchLogged = true;
			Iris.logger.info("PauC softened Photon cloud edges over DH LOD depth: {}.", programName);
		}
		if (!patched.equals(source)) {
			photonCloudEdgePatchCount++;
		}
		return patched;
	}

	private static String patchPhotonShadowFog(String programName, String source) {
		if (source.contains("paucPhotonDirectShadowFog")
			|| !source.contains("float border_fog(vec3 scene_pos, vec3 world_dir)")
			|| !source.contains("uniform int paucVanillaFogStartDistance;")
			|| !source.contains("uniform int paucVanillaFogEndDistance;")
			|| !source.contains("float view_distance = length(scene_pos.xz);")
			|| !PHOTON_BORDER_FOG_SHADOW_COMPENSATION.matcher(source).find()) {
			return source;
		}

		String patched = source;
		if (!patched.contains("uniform int paucPhotonShadowCoverageDistance;")) {
			patched = PHOTON_BORDER_FOG_UNIFORM_DECLARATION.matcher(patched)
				.replaceFirst("uniform int paucVanillaFogEndDistance;\nuniform int paucPhotonShadowCoverageDistance;");
			if (patched.equals(source)) {
				return source;
			}
		}

		String replacement = """
			if (paucPhotonShadowCoverageDistance > 0) {
			    float shadow_fog_start = max(float(paucPhotonShadowCoverageDistance), 32.0);
			    float shadow_fog_end = max(float(lod_render_distance), shadow_fog_start + 32.0);
			    float shadow_fog = 1.0 - smoothstep(shadow_fog_start, shadow_fog_end, view_distance);
			    fog = min(fog, shadow_fog); // paucPhotonDirectShadowFog
			}""";
		String rewritten = PHOTON_BORDER_FOG_SHADOW_COMPENSATION.matcher(patched)
			.replaceFirst(Matcher.quoteReplacement(replacement));
		if (rewritten.equals(patched)) {
			return source;
		}
		if (!photonShadowFogPatchLogged) {
			photonShadowFogPatchLogged = true;
			Iris.logger.info("PauC rewired Photon border fog to track Photon shadow coverage directly: {}.", programName);
		}
		photonShadowFogPatchCount++;
		return rewritten;
	}

}
