package net.irisshaders.iris.pipeline;

import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import net.irisshaders.iris.Iris;

import java.util.Locale;
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
	private static final Pattern PHOTON_CLOUD_RESULT_WRITE = Pattern.compile(
		"clouds\\s*\\.\\s*xyz\\s*=\\s*result\\s*\\.\\s*scattering\\s*\\.\\s*xyz\\s*;\\s*"
			+ "clouds\\s*\\.\\s*w\\s*=\\s*result\\s*\\.\\s*transmittance\\s*;",
		Pattern.MULTILINE
	);
	private static boolean photonCloudDepthPatchLogged;
	private static boolean photonCloudHistoryPatchLogged;
	private static boolean photonCloudEdgePatchLogged;
	private static volatile int photonCloudDepthPatchCount;
	private static volatile int photonCloudHistoryPatchCount;
	private static volatile int photonCloudEdgePatchCount;
	private static volatile PauCLodShaderProfiles.Family lastShaderFamily = PauCLodShaderProfiles.Family.GENERIC;

	private PauCShaderPackProgramPatches() {
	}

	public static String patchFragment(String programName, String source) {
		if (source == null) {
			return null;
		}

		lastShaderFamily = PauCLodShaderProfiles.familyForPackName(Iris.getCurrentPackName());
		return switch (lastShaderFamily) {
			case PHOTON -> patchPhotonClouds(programName, source);
			default -> source;
		};
	}

	public static String describeState() {
		return "shaderPatches[family="
			+ lastShaderFamily.name().toLowerCase(java.util.Locale.ROOT)
			+ ", photonDepth="
			+ photonCloudDepthPatchCount
			+ ", photonHistory="
			+ photonCloudHistoryPatchCount
			+ ", photonEdge="
			+ photonCloudEdgePatchCount
			+ "]";
	}

	private static String patchPhotonClouds(String programName, String source) {
		if (programName == null) {
			return source;
		}

		String patched = patchPhotonCloudLodDepth(programName, source);
		patched = patchPhotonCloudHistory(programName, patched);
		patched = patchPhotonCloudEdgeFog(programName, patched);
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
}
