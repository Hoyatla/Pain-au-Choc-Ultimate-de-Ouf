package net.irisshaders.iris.compat.dh;

import com.google.common.primitives.Ints;
import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import fr.hoyatla.pauc.lod.PauCLodNearClipOverride;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderProfiles;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IrisLodRenderProgram {
	private static final String COMPLEMENTARY_DH_FOG_MIX = "0.16";
	private static final String GENERIC_DH_FOG_MIX = "0.10";
	private static final String BLISS_BORDER_FOG_MIX = "0.12";
	private static final String ROUND_HORIZON_SHADER_FOG_STRENGTH_PROPERTY = "pauc.lod.roundHorizonShaderFogStrength";
	private static final String ROUND_HORIZON_WATER_FOG_STRENGTH_PROPERTY = "pauc.lod.roundHorizonWaterFogStrength";
	private static final String NATIVE_RUNTIME_UNDERWATER_FOG_PROPERTY = "pauc.lod.nativeShaderRuntimeUnderwaterFog";
	private static final String SYNTHETIC_LOD_SHADOW_PROPERTY = "pauc.lod.shaderSyntheticLodShadow";
	private static final String BOUNDARY_LOD_SHADOW_PROPERTY = "pauc.lod.shaderBoundaryShadowBridge";
	private static final Pattern DH_FOG_CALL = Pattern.compile(
		"DoFog\\s*\\(\\s*(color\\s*,\\s*sky\\s*,\\s*lViewPos\\s*,\\s*playerPos\\s*,\\s*VdotU\\s*,\\s*VdotS\\s*,\\s*dither\\s*,\\s*false\\s*,\\s*0\\.0)\\s*\\)\\s*;"
	);
	private static final Pattern DH_RGB_FOG_CALL = Pattern.compile(
		"\\bFog\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.rgb)?)\\s*,\\s*(viewPos(?:\\.xyz)?)(\\s*,[^;\\r\\n]*)?\\)\\s*;"
	);
	private static final Pattern DH_MIN_DIST_FAR_JOIN = Pattern.compile(
		"(float\\s+minDist\\s*=\\s*\\([^;\\r\\n]+?\\)\\s*\\*\\s*16\\.0\\s*\\+\\s*)far(\\s*;)"
	);
	private static final Pattern PHOTON_DH_FADE_BLOCK = Pattern.compile(
		"float\\s+dh_fade_start_distance\\s*=\\s*max0\\s*\\(\\s*far\\s*-\\s*DH_OVERDRAW_DISTANCE\\s*-\\s*DH_OVERDRAW_FADE_LENGTH\\s*\\)\\s*;\\s*float\\s+dh_fade_end_distance\\s*=\\s*max0\\s*\\(\\s*far\\s*-\\s*DH_OVERDRAW_DISTANCE\\s*\\)\\s*;",
		Pattern.MULTILINE
	);
	private static final Pattern SIMPLE_DH_FADE_BLOCK = Pattern.compile(
		"float\\s+dh_fade_start_distance\\s*=\\s*max\\s*\\(\\s*far\\s*-\\s*16(?:\\.0)?f?\\s*-\\s*16(?:\\.0)?f?\\s*,\\s*0(?:\\.0)?f?\\s*\\)\\s*;\\s*float\\s+dh_fade_end_distance\\s*=\\s*max\\s*\\(\\s*far\\s*-\\s*16(?:\\.0)?f?\\s*,\\s*0(?:\\.0)?f?\\s*\\)\\s*;",
		Pattern.MULTILINE
	);
	private static final Pattern DH_FADE_START_ASSIGNMENT = Pattern.compile(
		"float\\s+dh_fade_start_distance\\s*=\\s*[^;]+;",
		Pattern.MULTILINE
	);
	private static final Pattern DH_FADE_END_ASSIGNMENT = Pattern.compile(
		"float\\s+dh_fade_end_distance\\s*=\\s*[^;]+;",
		Pattern.MULTILINE
	);
	private static final Pattern DH_VIEW_DISTANCE_DECLARATION = Pattern.compile(
		"float\\s+view_distance\\s*=\\s*length\\s*\\(\\s*scene_pos\\s*\\)\\s*;"
	);
	private static final Pattern DH_FADE_VIEW_DISTANCE = Pattern.compile(
		"smoothstep\\s*\\(\\s*dh_fade_start_distance\\s*,\\s*dh_fade_end_distance\\s*,\\s*view_distance\\s*\\)"
	);
	private static final Pattern DH_FADE_DISTANCE_FAR_JOIN = Pattern.compile(
		"(dh_fade_(?:start|end)_distance\\s*=\\s*max0\\s*\\(\\s*)far(\\s*-)"
	);
	private static final Pattern DH_COMMON_FOG_ASSIGNMENT = Pattern.compile(
		"(vec4\\s+fog\\s*=\\s*common_fog\\s*\\([^;]+?\\)\\s*;)"
	);
	private static final Pattern DH_BORDER_FOG_ALPHA = Pattern.compile(
		"fragment_color\\s*\\.\\s*a\\s*\\*=\\s*border_fog\\s*\\(\\s*scene_pos\\s*,\\s*world_dir\\s*\\)\\s*;"
	);
	private static final Pattern BSL_CLOUD_DH_FAR_PLANE = Pattern.compile(
		"cloudMaxDistance\\s*=\\s*max\\s*\\(\\s*cloudMaxDistance\\s*,\\s*dhFarPlane\\s*\\)\\s*;"
	);
	private static final Pattern BLISS_SOLID_JOIN_DISTANCE = Pattern.compile(
		"clamp\\s*\\(\\s*far\\s*-\\s*32\\.0\\s*,\\s*32\\.0\\s*,\\s*maxOverdrawDistance\\s*\\)"
	);
	private static final Pattern BLISS_TRANSITION_DISTANCE = Pattern.compile(
		"length\\s*\\(\\s*playerPos\\s*\\)\\s*/\\s*\\(\\s*far\\s*-\\s*8(?:\\.0)?\\s*\\)"
	);
	private static final Pattern BLISS_TRANSPARENT_DISCARD_DISTANCE = Pattern.compile(
		"clamp\\s*\\(\\s*far\\s*-\\s*16\\s*\\*\\s*4\\s*,\\s*16\\s*,\\s*maxOverdrawDistance\\s*\\)"
	);
	private static final Pattern BLISS_BORDER_FOG_ATTENUATION_POINT = Pattern.compile(
		"(fog\\s*\\*=\\s*exp\\s*\\(\\s*-10\\.0\\s*\\*\\s*pow\\s*\\(\\s*clamp\\s*\\(\\s*normalize\\s*\\(\\s*playerPos\\s*\\)\\.y\\s*,\\s*0\\.0\\s*,\\s*1\\.0\\s*\\)\\s*\\*\\s*4\\.0\\s*,\\s*2\\.0\\s*\\)\\s*\\)\\s*;)"
	);
	private static final Pattern FAR_BASED_ALPHA_FADE = Pattern.compile(
		"color\\s*\\.\\s*a\\s*\\*=\\s*smoothstep\\s*\\(\\s*far\\s*\\*\\s*(?:0\\.5|\\.5|0\\.50)\\s*,\\s*far\\s*\\*\\s*(?:0\\.7|\\.7|0\\.70)\\s*,\\s*([^;\\r\\n]+?)\\s*\\)\\s*;"
	);
	private static final Pattern PRIMARY_COLOR_WRITE = Pattern.compile(
		"gl_FragData\\s*\\[\\s*0\\s*\\]\\s*=\\s*color\\s*;"
	);
	private static final Pattern PRIMARY_ALBEDO_WRITE = Pattern.compile(
		"gl_FragData\\s*\\[\\s*0\\s*\\]\\s*=\\s*albedo\\s*;"
	);
	private static final Pattern PHOTON_WATER_FOG_APPLY = Pattern.compile(
		"fragment_color\\s*\\.\\s*rgb\\s*=\\s*fragment_color\\s*\\.\\s*rgb\\s*\\*\\s*fog\\s*\\.\\s*a\\s*\\+\\s*fog\\s*\\.\\s*rgb\\s*;"
	);
	private static final Pattern BLISS_TRANSLUCENT_BORDER_FOG_WRITE = Pattern.compile(
		"gl_FragData\\s*\\[\\s*0\\s*\\]\\s*\\.\\s*rgb\\s*=\\s*mix\\s*\\(\\s*gl_FragData\\s*\\[\\s*0\\s*\\]\\s*\\.\\s*rgb\\s*,\\s*borderFogColor\\s*\\*\\s*0\\.1\\s*,\\s*fog\\s*\\)\\s*;"
	);
	private static final Pattern BLISS_WATER_DISTANCE_FADE_ALPHA_KILL = Pattern.compile(
		"if\\s*\\(\\s*((?:texture2D|texture)\\s*\\(\\s*depthtex0\\s*,\\s*gl_FragCoord\\s*\\.\\s*xy\\s*\\*\\s*texelSize\\s*\\)\\s*\\.\\s*x\\s*<\\s*1\\.0)\\s*\\|\\|\\s*distancefade\\s*>\\s*0\\.0\\s*\\)\\s*\\{\\s*"
			+ "gl_FragData\\s*\\[\\s*0\\s*\\]\\s*\\.\\s*a\\s*=\\s*0\\.0\\s*;\\s*"
			+ "material\\s*=\\s*0\\.0\\s*;\\s*"
			+ "\\}",
		Pattern.MULTILINE
	);
	private static final Pattern BLISS_WATER_DISTANCE_FADE_ALPHA_KILL_FALLBACK = Pattern.compile(
		"if\\s*\\(\\s*([^\\r\\n{};]*depthtex0[^\\r\\n{};]*<\\s*1\\.0[^\\r\\n{};]*)\\|\\|\\s*distancefade\\s*>\\s*0\\.0\\s*\\)\\s*\\{\\s*"
			+ "gl_FragData\\s*\\[\\s*0\\s*\\]\\s*\\.\\s*a\\s*=\\s*0\\.0\\s*;\\s*"
			+ "material\\s*=\\s*0\\.0\\s*;\\s*"
			+ "\\}",
		Pattern.MULTILINE
	);
	private static boolean paucNativeNearFadePatchLogged;
	private static boolean paucNativeLodShadowPatchLogged;
	private static boolean paucNativeJoinDistancePatchLogged;
	private static boolean paucNativeChunkBoundaryPatchLogged;
	private static boolean paucNativeGenericFogPatchLogged;
	private static boolean paucNativeBorderFogPatchLogged;
	private static boolean paucNativeEdgeFogPatchLogged;
	private static boolean paucNativeWaterGradientPatchLogged;
	private static boolean paucNativeBlissHorizonPatchLogged;
	private static boolean paucNativeBlissWaterSeamPatchLogged;
	private static boolean paucNativeLodMaterialConstantsLogged;
	private static boolean paucNativeUnderwaterFogPatchLogged;
	private static boolean paucNativeUnderwaterFogBypassLogged;
	private static final char[] LOD_BLOCK_PREFIX = new char[] {'D', 'H', '_'};
	private static final String[][] LOD_BLOCK_MATERIAL_CONSTANTS = {
		{"UNKNOWN", "0"},
		{"LEAVES", "1"},
		{"STONE", "2"},
		{"WOOD", "3"},
		{"METAL", "4"},
		{"DIRT", "5"},
		{"LAVA", "6"},
		{"DEEPSLATE", "7"},
		{"SNOW", "8"},
		{"SAND", "9"},
		{"TERRACOTTA", "10"},
		{"NETHER_STONE", "11"},
		{"WATER", "12"},
		{"GRASS", "13"},
		{"AIR", "14"},
		{"ILLUMINATED", "15"}
	};

	// Uniforms
	public final int modelOffsetUniform;
	public final int worldYOffsetUniform;
	public final int mircoOffsetUniform;
	public final int modelViewUniform;
	public final int modelViewInverseUniform;
	public final int projectionUniform;
	public final int projectionInverseUniform;
	public final int normalMatrix3fUniform;
	// Fog/Clip Uniforms
	public final int clipDistanceUniform;
	private final int id;
	private final ProgramUniforms uniforms;
	private final CustomUniforms customUniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final BlendModeOverride blend;
	private final BufferBlendOverride[] bufferBlendOverrides;

	// This will bind  AbstractVertexAttribute
	private IrisLodRenderProgram(String name, boolean isShadowPass, boolean translucent, BlendModeOverride override, BufferBlendOverride[] bufferBlendOverrides, String vertex, String tessControl, String tessEval, String geometry, String fragment, CustomUniforms customUniforms, IrisRenderingPipeline pipeline) {
		id = GL43C.glCreateProgram();

		GL32.glBindAttribLocation(this.id, 0, "vPosition");
		GL32.glBindAttribLocation(this.id, 1, "iris_color");
		GL32.glBindAttribLocation(this.id, 2, "irisExtra");

		this.bufferBlendOverrides = bufferBlendOverrides;

		GlShader vert = new GlShader(ShaderType.VERTEX, name + ".vsh", vertex);
		GL43C.glAttachShader(id, vert.getHandle());

		GlShader tessCont = null;
		if (tessControl != null) {
			tessCont = new GlShader(ShaderType.TESSELATION_CONTROL, name + ".tcs", tessControl);
			GL43C.glAttachShader(id, tessCont.getHandle());
		}

		GlShader tessE = null;
		if (tessEval != null) {
			tessE = new GlShader(ShaderType.TESSELATION_EVAL, name + ".tes", tessEval);
			GL43C.glAttachShader(id, tessE.getHandle());
		}

		GlShader geom = null;
		if (geometry != null) {
			geom = new GlShader(ShaderType.GEOMETRY, name + ".gsh", geometry);
			GL43C.glAttachShader(id, geom.getHandle());
		}

		GlShader frag = new GlShader(ShaderType.FRAGMENT, name + ".fsh", fragment);
		GL43C.glAttachShader(id, frag.getHandle());

		GL32.glLinkProgram(this.id);
		int status = GL32.glGetProgrami(this.id, 35714);
		if (status != 1) {
			String message = "Shader link error in Iris DH program! Details: " + GL32.glGetProgramInfoLog(this.id);
			this.free();
			throw new RuntimeException(message);
		} else {
			GL32.glUseProgram(this.id);
		}

		vert.destroy();
		frag.destroy();

		if (tessCont != null) tessCont.destroy();
		if (tessE != null) tessE.destroy();
		if (geom != null) geom.destroy();

		blend = override;
		ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(name, id);
		ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(id, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
		customUniforms.assignTo(uniformBuilder);
		BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
		ProgramImages.Builder builder = ProgramImages.builder(id);
		pipeline.addGbufferOrShadowSamplers(samplerBuilder, builder, isShadowPass ? pipeline::getFlippedBeforeShadow : () -> translucent ? pipeline.getFlippedAfterTranslucent() : pipeline.getFlippedAfterPrepare(), isShadowPass, false, true, false);
		customUniforms.mapholderToPass(uniformBuilder, this);
		this.uniforms = uniformBuilder.buildUniforms();
		this.customUniforms = customUniforms;
		samplers = samplerBuilder.build();
		images = builder.build();

		modelOffsetUniform = tryGetUniformLocation2("modelOffset");
		worldYOffsetUniform = tryGetUniformLocation2("worldYOffset");
		mircoOffsetUniform = tryGetUniformLocation2("mircoOffset");
		projectionUniform = tryGetUniformLocation2("iris_ProjectionMatrix");
		projectionInverseUniform = tryGetUniformLocation2("iris_ProjectionMatrixInverse");
		modelViewUniform = tryGetUniformLocation2("iris_ModelViewMatrix");
		modelViewInverseUniform = tryGetUniformLocation2("iris_ModelViewMatrixInverse");
		normalMatrix3fUniform = tryGetUniformLocation2("iris_NormalMatrix");

		// Fog/Clip Uniforms
		clipDistanceUniform = tryGetUniformLocation2("clipDistance");
	}

	public static IrisLodRenderProgram createProgram(String name, boolean isShadowPass, boolean translucent, ProgramSource source, CustomUniforms uniforms, IrisRenderingPipeline pipeline) {
		String fragmentSource = source.getFragmentSource().orElseThrow(RuntimeException::new);
		fragmentSource = alignNativeDhJoinDistance(fragmentSource, isShadowPass, name);
		fragmentSource = alignPaucChunkBoundaryFade(fragmentSource, isShadowPass, name);
		fragmentSource = relaxNativeDhNearFade(fragmentSource, isShadowPass, name);
		fragmentSource = applyPaucLodShadowGradient(fragmentSource, isShadowPass, translucent, name);
		Map<PatchShaderType, String> transformed = TransformPatcher.patchDHTerrain(
			name,
			source.getVertexSource().orElseThrow(RuntimeException::new),
			source.getTessControlSource().orElse(null),
			source.getTessEvalSource().orElse(null),
			source.getGeometrySource().orElse(null),
			fragmentSource,
			pipeline.getTextureMap());
		String vertex = transformed.get(PatchShaderType.VERTEX);
		String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
		String tessEval = transformed.get(PatchShaderType.TESS_EVAL);
		String geometry = transformed.get(PatchShaderType.GEOMETRY);
		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		vertex = ensureLodBlockMaterialConstants(vertex, name);
		tessControl = ensureLodBlockMaterialConstants(tessControl, name);
		tessEval = ensureLodBlockMaterialConstants(tessEval, name);
		geometry = ensureLodBlockMaterialConstants(geometry, name);
		fragment = ensureLodBlockMaterialConstants(fragment, name);
		fragment = alignNativeDhJoinDistance(fragment, isShadowPass, name);
		fragment = alignPaucChunkBoundaryFade(fragment, isShadowPass, name);
		fragment = attenuateDhFog(fragment, name);
		fragment = relaxNativeDhNearFade(fragment, isShadowPass, name);
		fragment = applyPaucLodPresentationGradient(fragment, isShadowPass, name);
		fragment = applyPaucPhotonWaterGradient(fragment, isShadowPass, name);
		fragment = applyPaucBlissHorizonFog(fragment, isShadowPass, name);
		fragment = applyPaucBlissWaterSeamPatch(fragment, isShadowPass, name);
		fragment = applyPaucLodShadowGradient(fragment, isShadowPass, translucent, name);
		fragment = applyPaucUnderwaterRuntimeFog(fragment, isShadowPass, name);
		fragment = ensurePaucFinalUniforms(fragment);
		Map<PatchShaderType, String> printedSources = new EnumMap<>(PatchShaderType.class);
		printedSources.put(PatchShaderType.VERTEX, vertex);
		printedSources.put(PatchShaderType.TESS_CONTROL, tessControl);
		printedSources.put(PatchShaderType.TESS_EVAL, tessEval);
		printedSources.put(PatchShaderType.GEOMETRY, geometry);
		printedSources.put(PatchShaderType.FRAGMENT, fragment);
		ShaderPrinter.printProgram(name)
			.addSources(printedSources)
			.setName("dh_" + name)
			.print();

		List<BufferBlendOverride> bufferOverrides = new ArrayList<>();

		source.getDirectives().getBufferBlendOverrides().forEach(information -> {
			int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), information.index());
			if (index > -1) {
				bufferOverrides.add(new BufferBlendOverride(index, information.blendMode()));
			}
		});

		return new IrisLodRenderProgram(name, isShadowPass, translucent, source.getDirectives().getBlendModeOverride().orElse(null), bufferOverrides.toArray(BufferBlendOverride[]::new), vertex, tessControl, tessEval, geometry, fragment, uniforms, pipeline);
	}

	private static PauCLodShaderProfiles.Profile currentShaderLodProfile() {
		return PauCLodShaderProfiles.current();
	}

	private static PauCLodShaderProfiles.Profile shaderLodProfile(PauCLodShaderProfiles.Family family) {
		return PauCLodShaderProfiles.profile(family);
	}

	private static String doFogMix(PauCLodShaderProfiles.Profile profile) {
		return profile.doFogMix();
	}

	private static String rgbFogMix(PauCLodShaderProfiles.Profile profile) {
		return profile.rgbFogMix();
	}

	private static String commonFogMix(PauCLodShaderProfiles.Profile profile) {
		return profile.commonFogMix();
	}

	private static String borderAlphaFogMix(PauCLodShaderProfiles.Profile profile) {
		return profile.borderAlphaFogMix();
	}

	private static String blissBorderFogMix(PauCLodShaderProfiles.Profile profile) {
		return profile.blissBorderFogMix();
	}

	private static boolean shouldApplyDirectColorPresentation(PauCLodShaderProfiles.Profile profile, boolean waterProgram) {
		return profile.shouldApplyDirectColorPresentation(waterProgram);
	}

	private static boolean shouldApplyAlbedoPresentation(PauCLodShaderProfiles.Profile profile, boolean waterProgram) {
		return profile.shouldApplyAlbedoPresentation(waterProgram);
	}

	private static boolean isWaterProgram(String programName) {
		if (programName == null) {
			return false;
		}

		String key = programName.toLowerCase(Locale.ROOT);
		return key.contains("water") || key.contains("translucent");
	}

	private static String nearBlendEndExtra(PauCLodShaderProfiles.Profile profile) {
		return profile.nearBlendEndExtra();
	}

	private static String farFogWidth(PauCLodShaderProfiles.Profile profile) {
		return profile.farFogWidth();
	}

	private static String farFogStrength(PauCLodShaderProfiles.Profile profile) {
		return readClampedFloatString(
			ROUND_HORIZON_SHADER_FOG_STRENGTH_PROPERTY,
			profileFloat(profile.farFogStrength(), 1.0F),
			0.0F,
			1.0F
		);
	}

	private static String waterGradientStrength(PauCLodShaderProfiles.Profile profile) {
		return profile.waterGradientStrength();
	}

	private static String waterEndFogStrength(PauCLodShaderProfiles.Profile profile) {
		return readClampedFloatString(
			ROUND_HORIZON_WATER_FOG_STRENGTH_PROPERTY,
			profileFloat(profile.waterEndFogStrength(), 1.0F),
			0.0F,
			1.0F
		);
	}

	private static String waterDeepTone(PauCLodShaderProfiles.Profile profile) {
		return profile.waterDeepTone();
	}

	private static String waterTransparencyStrength(PauCLodShaderProfiles.Profile profile) {
		return profile.waterTransparencyStrength();
	}

	private static String waterNearFogStrength(PauCLodShaderProfiles.Profile profile) {
		return switch (profile.family()) {
			case BLISS -> "0.08";
			case BSL -> "0.10";
			case RETHINKING -> "0.16";
			default -> "1.00";
		};
	}

	private static String readClampedFloatString(String key, float fallback, float min, float max) {
		String rawValue = System.getProperty(key);
		float value = fallback;
		if (rawValue != null) {
			try {
				value = Float.parseFloat(rawValue.trim());
			} catch (NumberFormatException ignored) {
				value = fallback;
			}
		}
		return Float.toString(Math.max(min, Math.min(max, value)));
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	private static float profileFloat(String rawValue, float fallback) {
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Float.parseFloat(rawValue.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static String waterOpacityBoost(PauCLodShaderProfiles.Profile profile) {
		return switch (profile.family()) {
			case BLISS -> "0.04";
			case BSL -> "0.12";
			case RETHINKING -> "0.18";
			default -> "0.00";
		};
	}

	private static boolean shouldUseSoftWaterNearBlend(PauCLodShaderProfiles.Profile profile, boolean waterProgram) {
		return waterProgram && (profile.family() == PauCLodShaderProfiles.Family.BLISS
			|| profile.family() == PauCLodShaderProfiles.Family.BSL
			|| profile.family() == PauCLodShaderProfiles.Family.RETHINKING);
	}

	private static boolean shouldApplyNativeRuntimeUnderwaterFog(PauCLodShaderProfiles.Profile profile, String programName) {
		boolean defaultEnabled = !profile.preservesNativeDhPresentation();
		boolean enabled = Boolean.parseBoolean(System.getProperty(NATIVE_RUNTIME_UNDERWATER_FOG_PROPERTY, Boolean.toString(defaultEnabled)));
		if (!enabled && profile.preservesNativeDhPresentation() && !paucNativeUnderwaterFogBypassLogged) {
			paucNativeUnderwaterFogBypassLogged = true;
			Iris.logger.info("PauC keeps native shader underwater fog for DH LOD presentation: {} (profile={}).", programName, profile.id());
		}
		return enabled;
	}

	private static String waterRingGradient(PauCLodShaderProfiles.Profile profile, String target, String distanceExpression, String marker) {
		String rgb = target + ".rgb";
		String alpha = target + ".a";
		return ""
			+ "    float " + marker + "NearRing = smoothstep(max(float(paucLodStartDistance) - 16.0, 0.0), float(paucLodStartDistance) + " + nearBlendEndExtra(profile) + ", " + distanceExpression + ");\n"
			+ "    float " + marker + "MidRingA = smoothstep(float(paucLodStartDistance) + 48.0, float(paucLodStartDistance) + 176.0, " + distanceExpression + ");\n"
			+ "    float " + marker + "MidRingB = smoothstep(float(paucLodStartDistance) + 144.0, max(float(paucLodEndDistance) - 144.0, float(paucLodStartDistance) + 224.0), " + distanceExpression + ");\n"
			+ "    float " + marker + "FarRing = smoothstep(max(float(paucLodEndDistance) - 192.0, float(paucLodStartDistance) + 240.0), float(paucLodEndDistance), " + distanceExpression + ");\n"
			+ "    float " + marker + "Tone = clamp(0.18 * " + marker + "NearRing + 0.28 * " + marker + "MidRingA + 0.34 * " + marker + "MidRingB + 0.20 * " + marker + "FarRing, 0.0, 1.0);\n"
			+ "    float " + marker + "Shade = mix(0.955, 1.0, 0.5 + 0.5 * sin(" + distanceExpression + " * 0.017));\n"
			+ "    vec3 " + marker + "NearWater = mix(" + rgb + ", " + waterDeepTone(profile) + ", 0.16) * " + marker + "Shade;\n"
			+ "    vec3 " + marker + "MidWater = mix(" + rgb + ", " + waterDeepTone(profile) + ", 0.36) * " + marker + "Shade;\n"
			+ "    vec3 " + marker + "DeepWater = mix(" + rgb + ", " + waterDeepTone(profile) + ", 0.58) * " + marker + "Shade;\n"
			+ "    vec3 " + marker + "GradedWater = mix(" + rgb + ", " + marker + "NearWater, 0.35 * " + marker + "NearRing);\n"
			+ "    " + marker + "GradedWater = mix(" + marker + "GradedWater, " + marker + "MidWater, 0.48 * " + marker + "MidRingA);\n"
			+ "    " + marker + "GradedWater = mix(" + marker + "GradedWater, " + marker + "DeepWater, 0.54 * " + marker + "MidRingB);\n"
			+ "    " + marker + "GradedWater = mix(" + marker + "GradedWater, " + marker + "DeepWater, 0.72 * " + marker + "FarRing);\n"
			+ "    " + rgb + " = mix(" + rgb + ", " + marker + "GradedWater, " + waterGradientStrength(profile) + " * " + marker + "Tone);\n"
			+ "    " + alpha + " = mix(" + alpha + ", min(1.0, " + alpha + " + (1.0 - " + alpha + ") * " + waterOpacityBoost(profile) + "), " + marker + "Tone);\n"
			+ "    " + alpha + " *= 1.0 - " + waterTransparencyStrength(profile) + " * " + marker + "Tone;\n";
	}

	private static String photonWaterHorizonGradient(PauCLodShaderProfiles.Profile profile, String target, String distanceExpression, String marker) {
		String rgb = target + ".rgb";
		String alpha = target + ".a";
		return ""
			+ "    float " + marker + "NearBlend = smoothstep(max(float(paucLodStartDistance) - 32.0, 0.0), float(paucLodStartDistance) + 112.0, " + distanceExpression + ");\n"
			+ "    float " + marker + "FarBlend = smoothstep(max(float(paucLodEndDistance) - " + farFogWidth(profile) + " * 1.35, float(paucLodStartDistance) + 160.0), float(paucLodEndDistance), " + distanceExpression + ");\n"
			+ "    float " + marker + "Tone = clamp(0.18 * " + marker + "NearBlend + 0.42 * " + marker + "FarBlend, 0.0, 1.0);\n"
			+ "    vec3 " + marker + "FoggedWater = mix(" + rgb + ", iris_FogColor.rgb, 0.24 + 0.34 * " + marker + "FarBlend);\n"
			+ "    " + rgb + " = mix(" + rgb + ", " + marker + "FoggedWater, " + waterGradientStrength(profile) + " * " + marker + "Tone);\n"
			+ "    " + alpha + " = mix(" + alpha + ", min(1.0, " + alpha + " + (1.0 - " + alpha + ") * 0.04), 0.35 * " + marker + "Tone);\n"
			+ "    " + alpha + " *= 1.0 - (" + waterTransparencyStrength(profile) + " * 0.45) * " + marker + "Tone;\n";
	}

	private static String farAlphaFade(PauCLodShaderProfiles.Profile profile, boolean albedoTarget) {
		return switch (profile.family()) {
			case BLISS -> albedoTarget ? "0.42" : "0.55";
			case BSL -> albedoTarget ? "0.06" : "0.10";
			case RETHINKING -> albedoTarget ? "0.10" : "0.14";
			case COMPLEMENTARY -> albedoTarget ? "0.12" : "0.16";
			case PHOTON -> albedoTarget ? "0.08" : "0.10";
			case SOLAS -> albedoTarget ? "0.20" : "0.28";
			default -> albedoTarget ? "0.24" : "0.32";
		};
	}

	private static String attenuateDhFog(String source, String programName) {
		if (source == null || (!source.contains("DoFog")
			&& !source.contains("Fog(")
			&& !source.contains("BorderFog")
			&& !source.contains("common_fog")
			&& !source.contains("border_fog"))) {
			return source;
		}

		String rewrittenSource = source;
		boolean patched = false;
		PauCLodShaderProfiles.Profile profile = currentShaderLodProfile();
		if (!profile.shouldAttenuateNativeFog()) {
			return source;
		}

		Matcher matcher = DH_FOG_CALL.matcher(rewrittenSource);
		StringBuffer rewritten = new StringBuffer(rewrittenSource.length());
		while (matcher.find()) {
			String replacement = "{ vec4 paucDhFogBefore = color; DoFog(" + matcher.group(1)
				+ "); color = mix(paucDhFogBefore, color, " + doFogMix(profile) + "); }";
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
			patched = true;
		}
		matcher.appendTail(rewritten);
		rewrittenSource = rewritten.toString();

		matcher = DH_RGB_FOG_CALL.matcher(rewrittenSource);
		rewritten = new StringBuffer(rewrittenSource.length());
		while (matcher.find()) {
			String target = matcher.group(1);
			String viewPos = matcher.group(2);
			String extraArgs = matcher.group(3) == null ? "" : matcher.group(3);
			String replacement = "{ vec3 paucDhRgbFogBefore = " + target + "; Fog(" + target + ", " + viewPos + extraArgs
				+ "); " + target + " = mix(paucDhRgbFogBefore, " + target + ", " + rgbFogMix(profile) + "); }";
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
			patched = true;
		}
		matcher.appendTail(rewritten);
		rewrittenSource = rewritten.toString();

		if (rewrittenSource.contains("common_fog") && !rewrittenSource.contains("paucDhCommonFog")) {
			matcher = DH_COMMON_FOG_ASSIGNMENT.matcher(rewrittenSource);
			rewritten = new StringBuffer(rewrittenSource.length());
			while (matcher.find()) {
				String replacement = matcher.group(1)
					+ "\n    fog = vec4(fog.rgb * " + commonFogMix(profile)
					+ ", mix(1.0, fog.a, " + commonFogMix(profile) + ")); // paucDhCommonFog";
				matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
				patched = true;
			}
			matcher.appendTail(rewritten);
			rewrittenSource = rewritten.toString();
		}

		if (rewrittenSource.contains("border_fog") && !rewrittenSource.contains("paucDhBorderAlphaFog")) {
			matcher = DH_BORDER_FOG_ALPHA.matcher(rewrittenSource);
			rewritten = new StringBuffer(rewrittenSource.length());
			while (matcher.find()) {
				String replacement = "fragment_color.a *= mix(1.0, border_fog(scene_pos, world_dir), "
					+ borderAlphaFogMix(profile) + "); // paucDhBorderAlphaFog";
				matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
				patched = true;
			}
			matcher.appendTail(rewritten);
			rewrittenSource = rewritten.toString();
		}

		if (profile.family() != PauCLodShaderProfiles.Family.BLISS && rewrittenSource.contains("borderFogColor") && !rewrittenSource.contains("paucDhBorderFog")) {
			matcher = BLISS_BORDER_FOG_ATTENUATION_POINT.matcher(rewrittenSource);
			rewritten = new StringBuffer(rewrittenSource.length());
			while (matcher.find()) {
				String replacement = matcher.group(1)
					+ "\n      fog *= " + blissBorderFogMix(profile) + "; // paucDhBorderFog";
				matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
				patched = true;
			}
			matcher.appendTail(rewritten);
			rewrittenSource = rewritten.toString();
			if (patched && !paucNativeBorderFogPatchLogged) {
				paucNativeBorderFogPatchLogged = true;
				Iris.logger.info("PauC attenuated native DH border fog for shader LOD visibility: {}.", programName);
			}
		}

		if (profile.family() == PauCLodShaderProfiles.Family.BSL && rewrittenSource.contains("cloudMaxDistance") && rewrittenSource.contains("dhFarPlane") && !rewrittenSource.contains("paucDhBslCloudDistance")) {
			matcher = BSL_CLOUD_DH_FAR_PLANE.matcher(rewrittenSource);
			rewritten = new StringBuffer(rewrittenSource.length());
			while (matcher.find()) {
				String replacement = "cloudMaxDistance = max(cloudMaxDistance, float(paucLodEndDistance)); // paucDhBslCloudDistance";
				matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
				patched = true;
			}
			matcher.appendTail(rewritten);
			rewrittenSource = ensurePaucDistanceUniforms(rewritten.toString());
		}

		if (patched && !paucNativeGenericFogPatchLogged) {
			paucNativeGenericFogPatchLogged = true;
			Iris.logger.info("PauC attenuated native DH shader fog for LOD visibility: {}.", programName);
		}
		return patched ? rewrittenSource : source;
	}

	private static String alignNativeDhJoinDistance(String source, boolean isShadowPass, String programName) {
		if (isShadowPass || source == null || !source.contains("far")) {
			return source;
		}

		boolean[] patched = {false};
		String rewritten = replacePhotonFadeBlock(source, patched);
		rewritten = replaceFarJoin(DH_MIN_DIST_FAR_JOIN, rewritten, "float(paucVanillaRenderDistance)", patched);
		rewritten = replaceFarJoin(DH_FADE_DISTANCE_FAR_JOIN, rewritten, "float(paucVanillaRenderDistance)", patched);
		rewritten = replaceLiteral(
			BLISS_SOLID_JOIN_DISTANCE,
			rewritten,
			"clamp(float(paucVanillaRenderDistance), 16.0, maxOverdrawDistance)",
			patched
		);
		rewritten = replaceLiteral(
			BLISS_TRANSITION_DISTANCE,
			rewritten,
			"length(playerPos)/(float(paucLodStartDistance) + 48.0)",
			patched
		);
		rewritten = replaceLiteral(
			BLISS_TRANSPARENT_DISCARD_DISTANCE,
			rewritten,
			"clamp(float(paucVanillaRenderDistance), 16.0, maxOverdrawDistance)",
			patched
		);

		if (!patched[0]) {
			return source;
		}

		rewritten = ensurePaucDistanceUniforms(rewritten);
		if (!paucNativeJoinDistancePatchLogged) {
			paucNativeJoinDistancePatchLogged = true;
			Iris.logger.info("PauC aligned native DH join distance with vanilla render distance for shader LOD continuity: {}.", programName);
		}
		return rewritten;
	}

	private static String replacePhotonFadeBlock(String source, boolean[] patched) {
		Matcher matcher = PHOTON_DH_FADE_BLOCK.matcher(source);
		String replacement = """
			float dh_fade_start_distance = max(float(paucLodStartDistance) - 16.0, 0.0);
			    float dh_fade_end_distance = max(float(paucLodStartDistance) + 48.0, 0.0);""";
		String rewritten = matcher.replaceAll(Matcher.quoteReplacement(replacement));
		if (!rewritten.equals(source)) {
			patched[0] = true;
		}
		matcher = SIMPLE_DH_FADE_BLOCK.matcher(rewritten);
		String simpleRewritten = matcher.replaceAll(Matcher.quoteReplacement(replacement));
		if (!simpleRewritten.equals(rewritten)) {
			patched[0] = true;
		}
		return simpleRewritten;
	}

	private static String alignPaucChunkBoundaryFade(String source, boolean isShadowPass, String programName) {
		if (isShadowPass
			|| source == null
			|| !source.contains("dh_fade_start_distance")
			|| !source.contains("dh_fade_end_distance")) {
			return source;
		}

		boolean[] patched = {false};
		String rewritten = rewriteDhFadeWindow(source, patched);
		rewritten = applyPaucChunkBoundaryFade(rewritten, patched);
		if (!patched[0]) {
			return source;
		}

		rewritten = ensurePaucDistanceUniforms(rewritten);
		if (!paucNativeChunkBoundaryPatchLogged) {
			paucNativeChunkBoundaryPatchLogged = true;
			Iris.logger.info("PauC aligned native DH fade to chunk boundary distance for shader LOD continuity: {}.", programName);
		}
		return rewritten;
	}

	private static String rewriteDhFadeWindow(String source, boolean[] patched) {
		String rewritten = replaceLiteral(
			DH_FADE_START_ASSIGNMENT,
			source,
			"float dh_fade_start_distance = max(float(paucLodStartDistance) - 16.0, 0.0);",
			patched
		);
		rewritten = replaceLiteral(
			DH_FADE_END_ASSIGNMENT,
			rewritten,
			"float dh_fade_end_distance = max(float(paucLodStartDistance) + 48.0, 0.0);",
			patched
		);
		return rewritten;
	}

	private static String applyPaucChunkBoundaryFade(String source, boolean[] patched) {
		if (source == null
			|| !source.contains("dh_fade_start_distance")
			|| !source.contains("dh_fade_end_distance")
			|| !source.contains("view_distance")
			|| !source.contains("scene_pos")) {
			return source;
		}

		Matcher fadeMatcher = DH_FADE_VIEW_DISTANCE.matcher(source);
		if (!fadeMatcher.find()) {
			return source;
		}

		String rewritten = source;
		if (!rewritten.contains("paucChunkBoundaryDistance")) {
			Matcher distanceMatcher = DH_VIEW_DISTANCE_DECLARATION.matcher(rewritten);
			if (!distanceMatcher.find()) {
				return source;
			}
			rewritten = distanceMatcher.replaceFirst(
				Matcher.quoteReplacement("float view_distance = length(scene_pos);\n\tfloat paucChunkBoundaryDistance = length(scene_pos.xz);")
			);
		}
		String chunkFade = DH_FADE_VIEW_DISTANCE.matcher(rewritten).replaceAll(
			Matcher.quoteReplacement("smoothstep(dh_fade_start_distance, dh_fade_end_distance, paucChunkBoundaryDistance)")
		);
		if (!chunkFade.equals(source)) {
			patched[0] = true;
		}
		return chunkFade;
	}

	private static String replaceFarJoin(Pattern pattern, String source, String replacementDistance, boolean[] patched) {
		Matcher matcher = pattern.matcher(source);
		StringBuffer rewritten = new StringBuffer(source.length());
		while (matcher.find()) {
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + replacementDistance + matcher.group(2)));
			patched[0] = true;
		}
		matcher.appendTail(rewritten);
		return patched[0] ? rewritten.toString() : source;
	}

	private static String replaceLiteral(Pattern pattern, String source, String replacement, boolean[] patched) {
		Matcher matcher = pattern.matcher(source);
		if (!matcher.find()) {
			return source;
		}

		patched[0] = true;
		return matcher.replaceAll(Matcher.quoteReplacement(replacement));
	}

	private static String ensurePaucDistanceUniforms(String source) {
		StringBuilder uniforms = new StringBuilder();
		if (!source.contains("uniform int paucVanillaRenderDistance")) {
			uniforms.append("uniform int paucVanillaRenderDistance;\n");
		}
		if (!source.contains("uniform int paucLodStartDistance")) {
			uniforms.append("uniform int paucLodStartDistance;\n");
		}
		if (!source.contains("uniform int paucLodEndDistance")) {
			uniforms.append("uniform int paucLodEndDistance;\n");
		}
		if (uniforms.length() == 0) {
			return source;
		}

		int insertionIndex = shaderGlobalDeclarationInsertionIndex(source);
		return source.substring(0, insertionIndex) + uniforms + source.substring(insertionIndex);
	}

	private static String ensurePaucPresentationUniforms(String source) {
		String withDistanceUniforms = ensurePaucDistanceUniforms(source);
		boolean hasFogUniform = withDistanceUniforms.contains("uniform vec4 iris_FogColor");
		boolean hasWaterUniform = withDistanceUniforms.contains("uniform int isEyeInWater");
		if (hasFogUniform && hasWaterUniform) {
			return withDistanceUniforms;
		}

		int insertionIndex = shaderGlobalDeclarationInsertionIndex(withDistanceUniforms);
		StringBuilder uniforms = new StringBuilder();
		if (!hasFogUniform) {
			uniforms.append("uniform vec4 iris_FogColor;\n");
		}
		if (!hasWaterUniform) {
			uniforms.append("uniform int isEyeInWater;\n");
		}
		return withDistanceUniforms.substring(0, insertionIndex)
			+ uniforms
			+ withDistanceUniforms.substring(insertionIndex);
	}

	private static String ensurePaucFinalUniforms(String source) {
		if (source == null) {
			return null;
		}

		String rewritten = source;
		if (rewritten.contains("paucVanillaRenderDistance")
			|| rewritten.contains("paucLodStartDistance")
			|| rewritten.contains("paucLodEndDistance")) {
			rewritten = ensurePaucDistanceUniforms(rewritten);
		}
		if (rewritten.contains("iris_FogColor")) {
			rewritten = ensurePaucPresentationUniforms(rewritten);
		}
		return rewritten;
	}

	private static String ensureLodBlockMaterialConstants(String source, String programName) {
		if (source == null) {
			return null;
		}

		String blockPrefix = lodBlockPrefix();
		if (!source.contains(blockPrefix)) {
			return source;
		}

		StringBuilder missingDefinitions = new StringBuilder();
		for (String[] constant : LOD_BLOCK_MATERIAL_CONSTANTS) {
			String name = lodBlockMacro(constant[0]);
			if (source.contains(name)
				&& !source.contains("#define " + name)
				&& !source.contains("const int " + name)) {
				missingDefinitions
					.append("#define ")
					.append(name)
					.append(' ')
					.append(constant[1])
					.append('\n');
			}
		}
		if (missingDefinitions.length() == 0) {
			return source;
		}

		if (!paucNativeLodMaterialConstantsLogged) {
			paucNativeLodMaterialConstantsLogged = true;
			Iris.logger.info("PauC injected native LOD material constants for shader LOD compatibility: {}.", programName);
		}
		int insertionIndex = shaderGlobalDeclarationInsertionIndex(source);
		return source.substring(0, insertionIndex) + missingDefinitions + source.substring(insertionIndex);
	}

	private static String lodBlockPrefix() {
		return new String(LOD_BLOCK_PREFIX) + "BLOCK_";
	}

	private static String lodBlockMacro(String suffix) {
		return lodBlockPrefix() + suffix;
	}

	private static int shaderGlobalDeclarationInsertionIndex(String source) {
		int index = 0;
		while (index < source.length()) {
			int lineEnd = source.indexOf('\n', index);
			int nextLine = lineEnd < 0 ? source.length() : lineEnd + 1;
			String line = source.substring(index, lineEnd < 0 ? source.length() : lineEnd).trim();
			if (line.isEmpty()
				|| line.startsWith("//")
				|| line.startsWith("/*")
				|| line.startsWith("*")
				|| line.startsWith("#version")
				|| line.startsWith("#extension")
				|| line.startsWith("#line")) {
				index = nextLine;
				continue;
			}

			return index;
		}

		return source.length();
	}

	private static String relaxNativeDhNearFade(String source, boolean isShadowPass, String programName) {
		if (isShadowPass || source == null || !source.contains("smoothstep") || !source.contains("far")) {
			return source;
		}

		Matcher matcher = FAR_BASED_ALPHA_FADE.matcher(source);
		StringBuffer rewritten = new StringBuffer(source.length());
		boolean patched = false;
		while (matcher.find()) {
			patched = true;
			String distanceExpression = matcher.group(1);
			String replacement = "color.a *= smoothstep(max(float(paucVanillaRenderDistance) - 16.0, 0.0), float(paucLodStartDistance) + 16.0, " + distanceExpression + ");";
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(rewritten);
		if (patched && !paucNativeNearFadePatchLogged) {
			paucNativeNearFadePatchLogged = true;
			Iris.logger.info("PauC relaxed native DH near alpha fade for shader LOD continuity: {}.", programName);
		}
		return patched ? ensurePaucDistanceUniforms(rewritten.toString()) : source;
	}

	private static String applyPaucLodPresentationGradient(String source, boolean isShadowPass, String programName) {
		if (isShadowPass || source == null || source.contains("paucDhFarEdgeFog")) {
			return source;
		}

		PauCLodShaderProfiles.Profile profile = currentShaderLodProfile();
		boolean waterProgram = isWaterProgram(programName);
		boolean directColorPresentation = shouldApplyDirectColorPresentation(profile, waterProgram);
		boolean albedoPresentation = shouldApplyAlbedoPresentation(profile, waterProgram);
		if (!directColorPresentation && !albedoPresentation) {
			return source;
		}

		String nearExtra = nearBlendEndExtra(profile);
		String farWidth = farFogWidth(profile);
		String farStrength = waterProgram ? waterEndFogStrength(profile) : farFogStrength(profile);
		String waterGradient = waterProgram ? waterRingGradient(profile, "color", "lengthCylinder", "paucDhWater") : "";
		String albedoWaterGradient = waterProgram ? waterRingGradient(profile, "albedo", "paucDhAlbedoDistance", "paucDhWater") : "";
		boolean runtimeUnderwaterFog = shouldApplyNativeRuntimeUnderwaterFog(profile, programName);
		String directNearBlend = shouldUseSoftWaterNearBlend(profile, waterProgram)
			? "    color.rgb = mix(color.rgb, iris_FogColor.rgb, " + waterNearFogStrength(profile) + " * (1.0 - paucDhNearBlend));\n"
			: "    color.rgb = mix(iris_FogColor.rgb, color.rgb, paucDhNearBlend);\n";
		String albedoNearBlend = shouldUseSoftWaterNearBlend(profile, waterProgram)
			? "    albedo.rgb = mix(albedo.rgb, iris_FogColor.rgb, " + waterNearFogStrength(profile) + " * (1.0 - paucDhNearBlend));\n"
			: "";
		String directUnderwaterFog = runtimeUnderwaterFog ? "    if (isEyeInWater == 1) { // paucDhUnderwaterRuntimeFog\n"
			+ "        float paucDhUnderwaterFog = smoothstep(float(paucLodStartDistance), max(float(paucLodStartDistance) + 64.0, float(paucLodEndDistance) * 0.62), lengthCylinder);\n"
			+ "        vec3 paucDhUnderwaterColor = mix(iris_FogColor.rgb, vec3(0.06, 0.18, 0.24), 0.26);\n"
			+ "        color.rgb = mix(color.rgb, paucDhUnderwaterColor, 0.76 * paucDhUnderwaterFog);\n"
			+ "        color.a = mix(color.a, 1.0, 0.30 * paucDhUnderwaterFog);\n"
			+ "    }\n"
			: "";
		String albedoUnderwaterFog = runtimeUnderwaterFog ? "    if (isEyeInWater == 1) { // paucDhUnderwaterRuntimeFog\n"
			+ "        float paucDhUnderwaterFog = smoothstep(float(paucLodStartDistance), max(float(paucLodStartDistance) + 64.0, float(paucLodEndDistance) * 0.62), paucDhAlbedoDistance);\n"
			+ "        vec3 paucDhUnderwaterColor = mix(iris_FogColor.rgb, vec3(0.06, 0.18, 0.24), 0.26);\n"
			+ "        albedo.rgb = mix(albedo.rgb, paucDhUnderwaterColor, 0.76 * paucDhUnderwaterFog);\n"
			+ "        albedo.a = mix(albedo.a, 1.0, 0.30 * paucDhUnderwaterFog);\n"
			+ "    }\n"
			: "";
		String rewritten = source;
		boolean patched = false;
		if (directColorPresentation && rewritten.contains("lengthCylinder") && rewritten.contains("color")) {
			Matcher matcher = PRIMARY_COLOR_WRITE.matcher(rewritten);
			StringBuffer buffer = new StringBuffer(rewritten.length());
			while (matcher.find()) {
				patched = true;
				String replacement = "{\n"
					+ "    float paucDhNearBlend = smoothstep(max(float(paucVanillaRenderDistance) - 16.0, 0.0), float(paucLodStartDistance) + " + nearExtra + ", lengthCylinder);\n"
					+ "    float paucDhFarEdgeFog = smoothstep(max(float(paucLodEndDistance) - " + farWidth + ", 0.0), float(paucLodEndDistance), lengthCylinder);\n"
					+ directNearBlend
					+ waterGradient
					+ "    color.rgb = mix(color.rgb, iris_FogColor.rgb, " + farStrength + " * paucDhFarEdgeFog);\n"
					+ "    color.a *= 1.0 - " + farAlphaFade(profile, false) + " * paucDhFarEdgeFog;\n"
					+ directUnderwaterFog
					+ "}\n"
					+ "gl_FragData[0] = color;";
				matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
			}
			matcher.appendTail(buffer);
			rewritten = buffer.toString();
		}

		if (albedoPresentation && rewritten.contains("viewPos") && rewritten.contains("albedo")) {
			Matcher matcher = PRIMARY_ALBEDO_WRITE.matcher(rewritten);
			StringBuffer buffer = new StringBuffer(rewritten.length());
			while (matcher.find()) {
				patched = true;
				String replacement = "{\n"
					+ "    float paucDhAlbedoDistance = length(viewPos);\n"
					+ "    float paucDhNearBlend = smoothstep(max(float(paucVanillaRenderDistance) - 16.0, 0.0), float(paucLodStartDistance) + " + nearExtra + ", paucDhAlbedoDistance);\n"
					+ "    float paucDhFarEdgeFog = smoothstep(max(float(paucLodEndDistance) - " + farWidth + ", 0.0), float(paucLodEndDistance), paucDhAlbedoDistance);\n"
					+ albedoNearBlend
					+ albedoWaterGradient
					+ "    albedo.rgb = mix(albedo.rgb, iris_FogColor.rgb, " + farStrength + " * paucDhFarEdgeFog);\n"
					+ "    albedo.a *= 1.0 - " + farAlphaFade(profile, true) + " * paucDhFarEdgeFog;\n"
					+ albedoUnderwaterFog
					+ "}\n"
					+ "gl_FragData[0] = albedo;";
				matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
			}
			matcher.appendTail(buffer);
			rewritten = buffer.toString();
		}

		if (patched && !paucNativeEdgeFogPatchLogged) {
			paucNativeEdgeFogPatchLogged = true;
			Iris.logger.info("PauC applied native DH near water blend and far-edge fog for LOD presentation: {}.", programName);
		}
		return patched ? ensurePaucPresentationUniforms(rewritten) : source;
	}

	private static String applyPaucPhotonWaterGradient(String source, boolean isShadowPass, String programName) {
		if (isShadowPass
			|| source == null
			|| source.contains("paucDhPhotonWaterTone")
			|| currentShaderLodProfile().family() != PauCLodShaderProfiles.Family.PHOTON
			|| !currentShaderLodProfile().shouldApplyNativeWaterTonePatch()
			|| !isWaterProgram(programName)
			|| !source.contains("fragment_color")
			|| !source.contains("scene_pos")
			|| !source.contains("common_fog")) {
			return source;
		}

		Matcher matcher = PHOTON_WATER_FOG_APPLY.matcher(source);
		StringBuffer rewritten = new StringBuffer(source.length());
		boolean patched = false;
		while (matcher.find()) {
			patched = true;
			String replacement = "{\n"
				+ "    float paucDhPhotonWaterDistance = length(scene_pos.xz);\n"
				+ "    float paucDhPhotonWaterEndFog = smoothstep(max(float(paucLodEndDistance) - " + farFogWidth(shaderLodProfile(PauCLodShaderProfiles.Family.PHOTON)) + ", 0.0), float(paucLodEndDistance), paucDhPhotonWaterDistance);\n"
				+ photonWaterHorizonGradient(shaderLodProfile(PauCLodShaderProfiles.Family.PHOTON), "fragment_color", "paucDhPhotonWaterDistance", "paucDhPhotonWater")
				+ "    fragment_color.rgb = mix(fragment_color.rgb, iris_FogColor.rgb, " + waterEndFogStrength(shaderLodProfile(PauCLodShaderProfiles.Family.PHOTON)) + " * paucDhPhotonWaterEndFog);\n"
				+ "}\n"
				+ "fragment_color.rgb = fragment_color.rgb * fog.a + fog.rgb;";
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(rewritten);
		if (patched && !paucNativeWaterGradientPatchLogged) {
			paucNativeWaterGradientPatchLogged = true;
			Iris.logger.info("PauC applied native DH water tone gradient for shader LOD presentation: {}.", programName);
		}
		return patched ? ensurePaucPresentationUniforms(rewritten.toString()) : source;
	}

	private static String applyPaucBlissHorizonFog(String source, boolean isShadowPass, String programName) {
		if (isShadowPass
			|| source == null
			|| source.contains("paucDhBlissEndFog")
			|| currentShaderLodProfile().family() != PauCLodShaderProfiles.Family.BLISS
			|| isWaterProgram(programName)
			|| !source.contains("borderFogColor")
			|| !source.contains("playerPos")) {
			return source;
		}

		if (!Boolean.parseBoolean(System.getProperty("pauc.lod.blissHorizonPatch", "true"))) {
			return source;
		}

		Matcher matcher = BLISS_TRANSLUCENT_BORDER_FOG_WRITE.matcher(source);
		StringBuffer rewritten = new StringBuffer(source.length());
		boolean patched = false;
		while (matcher.find()) {
			patched = true;
			String replacement = "{\n"
				+ "        float paucDhBlissEndFog = smoothstep(max(float(paucLodEndDistance) - " + farFogWidth(shaderLodProfile(PauCLodShaderProfiles.Family.BLISS)) + ", 0.0), float(paucLodEndDistance), length(playerPos));\n"
				+ "        vec3 paucDhBlissNativeFog = borderFogColor * 0.1;\n"
				+ "        vec3 paucDhBlissStableFog = iris_FogColor.rgb * 0.1;\n"
				+ "        vec3 paucDhBlissGradientFog = mix(paucDhBlissNativeFog, paucDhBlissStableFog, 0.55 + 0.25 * paucDhBlissEndFog);\n"
				+ "        vec3 paucDhBlissBaseFog = mix(gl_FragData[0].rgb, paucDhBlissGradientFog, clamp(fog * 0.62, 0.0, 1.0));\n"
				+ "        vec3 paucDhBlissFogColor = mix(paucDhBlissBaseFog, paucDhBlissStableFog, 0.30 + 0.16 * paucDhBlissEndFog);\n"
				+ "        gl_FragData[0].rgb = mix(paucDhBlissBaseFog, paucDhBlissFogColor, " + waterEndFogStrength(shaderLodProfile(PauCLodShaderProfiles.Family.BLISS)) + " * paucDhBlissEndFog);\n"
				+ "    }";
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(rewritten);
		if (patched && !paucNativeBlissHorizonPatchLogged) {
			paucNativeBlissHorizonPatchLogged = true;
			Iris.logger.info("PauC applied Bliss DH end fog for LOD horizon presentation: {}.", programName);
		}
		return patched ? ensurePaucPresentationUniforms(rewritten.toString()) : source;
	}

	private static String applyPaucBlissWaterSeamPatch(String source, boolean isShadowPass, String programName) {
		if (isShadowPass
			|| source == null
			|| source.contains("paucDhBlissWaterSeam")
			|| currentShaderLodProfile().family() != PauCLodShaderProfiles.Family.BLISS
			|| !isWaterProgram(programName)
			|| !source.contains("distancefade")
			|| !source.contains("averageSkyCol")) {
			return source;
		}

		Matcher matcher = BLISS_WATER_DISTANCE_FADE_ALPHA_KILL.matcher(source);
		String rewritten = replaceBlissWaterDistanceFadeKill(matcher, source);
		if (rewritten.equals(source)) {
			rewritten = replaceBlissWaterDistanceFadeKill(BLISS_WATER_DISTANCE_FADE_ALPHA_KILL_FALLBACK.matcher(source), source);
		}
		if (!rewritten.equals(source) && !paucNativeBlissWaterSeamPatchLogged) {
			paucNativeBlissWaterSeamPatchLogged = true;
			Iris.logger.info("PauC softened Bliss DH water seam to remove circular alpha edge: {}.", programName);
		}
		return rewritten.equals(source) ? source : rewritten;
	}

	private static String replaceBlissWaterDistanceFadeKill(Matcher matcher, String source) {
		if (!matcher.find()) {
			return source;
		}

		String depthHitExpression = matcher.group(1).trim();
		String replacement = """
			{
			    float paucDhBlissWaterSeam = smoothstep(0.10, 1.0, distancefade);
			    gl_FragData[0].rgb = mix(gl_FragData[0].rgb, averageSkyCol * 0.1, 0.24 * paucDhBlissWaterSeam);
			    gl_FragData[0].a *= max(0.0, 1.0 - 0.58 * paucDhBlissWaterSeam);
			    if (%s) {
			        gl_FragData[0].a = 0.0;
			        material = 0.0;
			    }
			}""".formatted(depthHitExpression);
		return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
	}

	private static String applyPaucLodShadowGradient(String source, boolean isShadowPass, boolean translucent, String programName) {
		if (isShadowPass || translucent || source == null || source.contains("paucDhLodShadow")) {
			return source;
		}

		if (!source.contains("lengthCylinder") || !source.contains("sunVec") || !source.contains("normal")) {
			return source;
		}

		Matcher matcher = PRIMARY_COLOR_WRITE.matcher(source);
		StringBuffer rewritten = new StringBuffer(source.length());
		boolean patched = false;
		PauCLodShaderProfiles.Profile profile = currentShaderLodProfile();
		if (!shouldApplyAnyPaucLodShadow(profile)) {
			return source;
		}
		boolean syntheticShadow = shouldApplyPaucSyntheticLodShadow(profile);
		String joinNear = syntheticShadow
			? profile.lodShadowJoinNear()
			: "max(float(paucLodStartDistance) - 24.0, 0.0)";
		String joinFar = syntheticShadow
			? profile.lodShadowJoinFar()
			: "float(paucLodStartDistance) + 112.0";
		String nearStrength = syntheticShadow
			? profile.lodShadowNearStrength()
			: formatFloat(boundaryShadowNearStrength(profile));
		String sideStrength = syntheticShadow
			? profile.lodShadowSideStrength()
			: formatFloat(boundaryShadowSideStrength(profile));
		String shadowMax = syntheticShadow
			? profile.lodShadowMax()
			: formatFloat(boundaryShadowMax(profile));
		while (matcher.find()) {
			patched = true;
			String replacement = "{\n"
				+ "    float paucDhLodJoin = 1.0 - smoothstep(" + joinNear + ", " + joinFar + ", lengthCylinder);\n"
				+ "    float paucDhLodFacing = clamp(dot(normalize(normal), normalize(sunVec)), 0.0, 1.0);\n"
				+ "    float paucDhLodSideShadow = pow(1.0 - paucDhLodFacing, " + (syntheticShadow ? "1.35" : "1.20") + ");\n"
				+ "    float paucDhLodShadow = clamp(" + nearStrength + " * paucDhLodJoin + " + sideStrength + " * paucDhLodSideShadow * paucDhLodJoin, 0.0, " + shadowMax + ");\n"
				+ "    color.rgb *= 1.0 - paucDhLodShadow;\n"
				+ "}\n"
				+ "gl_FragData[0] = color;";
			matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(rewritten);
		if (patched && !paucNativeLodShadowPatchLogged) {
			paucNativeLodShadowPatchLogged = true;
			Iris.logger.info("PauC applied native DH LOD shadow bridge for shader terrain continuity: {}.", programName);
		}
		return patched ? ensurePaucDistanceUniforms(rewritten.toString()) : source;
	}

	private static boolean shouldApplyAnyPaucLodShadow(PauCLodShaderProfiles.Profile profile) {
		return shouldApplyPaucSyntheticLodShadow(profile) || shouldApplyPaucBoundaryLodShadow(profile);
	}

	private static boolean shouldApplyPaucSyntheticLodShadow(PauCLodShaderProfiles.Profile profile) {
		return readBoolean(SYNTHETIC_LOD_SHADOW_PROPERTY, false) && profile.shouldApplySyntheticLodShadow();
	}

	private static boolean shouldApplyPaucBoundaryLodShadow(PauCLodShaderProfiles.Profile profile) {
		return readBoolean(BOUNDARY_LOD_SHADOW_PROPERTY, true)
			&& profile != null
			&& profile.family() != PauCLodShaderProfiles.Family.PHOTON
			&& profile.family() != PauCLodShaderProfiles.Family.SOLAS
			&& profile.family() != PauCLodShaderProfiles.Family.GENERIC
			&& profile.preservesNativeDhPresentation();
	}

	private static float boundaryShadowNearStrength(PauCLodShaderProfiles.Profile profile) {
		float raw = profileFloat(profile.lodShadowNearStrength(), 0.18F) * 0.45F;
		return Math.max(0.08F, Math.min(0.20F, raw));
	}

	private static float boundaryShadowSideStrength(PauCLodShaderProfiles.Profile profile) {
		float raw = profileFloat(profile.lodShadowSideStrength(), 0.30F) * 0.35F;
		return Math.max(0.06F, Math.min(0.18F, raw));
	}

	private static float boundaryShadowMax(PauCLodShaderProfiles.Profile profile) {
		float raw = profileFloat(profile.lodShadowMax(), 0.40F) * 0.40F;
		return Math.max(0.10F, Math.min(0.22F, raw));
	}

	private static String formatFloat(float value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String applyPaucUnderwaterRuntimeFog(String source, boolean isShadowPass, String programName) {
		if (isShadowPass || source == null || source.contains("paucDhUnderwaterRuntimeFog")) {
			return source;
		}

		PauCLodShaderProfiles.Profile profile = currentShaderLodProfile();
		if (!shouldApplyNativeRuntimeUnderwaterFog(profile, programName)) {
			return source;
		}

		String rewritten = source;
		boolean patched = false;
		if (rewritten.contains("lengthCylinder") && rewritten.contains("color")) {
			Matcher matcher = PRIMARY_COLOR_WRITE.matcher(rewritten);
			StringBuffer buffer = new StringBuffer(rewritten.length());
			while (matcher.find()) {
				patched = true;
				String replacement = "{\n"
					+ "    if (isEyeInWater == 1) { // paucDhUnderwaterRuntimeFog\n"
					+ "        float paucDhUnderwaterFog = smoothstep(float(paucLodStartDistance), max(float(paucLodStartDistance) + 64.0, float(paucLodEndDistance) * 0.62), lengthCylinder);\n"
					+ "        vec3 paucDhUnderwaterColor = mix(iris_FogColor.rgb, vec3(0.06, 0.18, 0.24), 0.26);\n"
					+ "        color.rgb = mix(color.rgb, paucDhUnderwaterColor, 0.76 * paucDhUnderwaterFog);\n"
					+ "        color.a = mix(color.a, 1.0, 0.30 * paucDhUnderwaterFog);\n"
					+ "    }\n"
					+ "}\n"
					+ "gl_FragData[0] = color;";
				matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
			}
			matcher.appendTail(buffer);
			rewritten = buffer.toString();
		}

		if (!patched && rewritten.contains("viewPos") && rewritten.contains("albedo")) {
			Matcher matcher = PRIMARY_ALBEDO_WRITE.matcher(rewritten);
			StringBuffer buffer = new StringBuffer(rewritten.length());
			while (matcher.find()) {
				patched = true;
				String replacement = "{\n"
					+ "    float paucDhAlbedoDistance = length(viewPos);\n"
					+ "    if (isEyeInWater == 1) { // paucDhUnderwaterRuntimeFog\n"
					+ "        float paucDhUnderwaterFog = smoothstep(float(paucLodStartDistance), max(float(paucLodStartDistance) + 64.0, float(paucLodEndDistance) * 0.62), paucDhAlbedoDistance);\n"
					+ "        vec3 paucDhUnderwaterColor = mix(iris_FogColor.rgb, vec3(0.06, 0.18, 0.24), 0.26);\n"
					+ "        albedo.rgb = mix(albedo.rgb, paucDhUnderwaterColor, 0.76 * paucDhUnderwaterFog);\n"
					+ "        albedo.a = mix(albedo.a, 1.0, 0.30 * paucDhUnderwaterFog);\n"
					+ "    }\n"
					+ "}\n"
					+ "gl_FragData[0] = albedo;";
				matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
			}
			matcher.appendTail(buffer);
			rewritten = buffer.toString();
		}

		if (patched && !paucNativeUnderwaterFogPatchLogged) {
			paucNativeUnderwaterFogPatchLogged = true;
			Iris.logger.info("PauC applied underwater fog to native DH LOD terrain presentation: {}.", programName);
		}
		return patched ? ensurePaucPresentationUniforms(rewritten) : source;
	}

	// Noise Uniforms

	public int tryGetUniformLocation2(CharSequence name) {
		return GL32.glGetUniformLocation(this.id, name);
	}

	public void setUniform(int index, Matrix4fc matrix) {
		if (index == -1 || matrix == null) return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.callocFloat(16);
			matrix.get(buffer);
			buffer.rewind();

			RenderSystem.glUniformMatrix4(index, false, buffer);
		}
	}

	public void setUniform(int index, Matrix3f matrix) {
		if (index == -1) return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.callocFloat(9);
			matrix.get(buffer);
			buffer.rewind();

			RenderSystem.glUniformMatrix3(index, false, buffer);
		}
	}

	// Override ShaderProgram.bind()
	public void bind() {
		GL43C.glUseProgram(id);
		if (blend != null) blend.apply();

		for (BufferBlendOverride override : bufferBlendOverrides) {
			override.apply();
		}
	}

	public void unbind() {
		GL43C.glUseProgram(0);
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		BlendModeOverride.restore();
	}

	public void free() {
		GL43C.glDeleteProgram(id);
	}

	public void fillUniformData(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks) {
		GL43C.glUseProgram(id);

		Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.LIGHTMAP_TEXTURE_UNIT, RenderSystem.getShaderTexture(2));
		setUniform(modelViewUniform, modelView);
		setUniform(modelViewInverseUniform, modelView.invert(new Matrix4f()));
		setUniform(projectionUniform, projection);
		setUniform(projectionInverseUniform, projection.invert(new Matrix4f()));
		setUniform(normalMatrix3fUniform, new Matrix4f(modelView).invert().transpose3x3(new Matrix3f()));

		setUniform(mircoOffsetUniform, 0.01f); // 0.01 block offset

		// setUniform(skyLightUniform, skyLight);

		if (worldYOffsetUniform != -1) setUniform(worldYOffsetUniform, (float) worldYOffset);

		// Fog/Clip Uniforms
		float dhNearClipDistance = DhApi.Delayed.renderProxy.getNearClipPlaneDistanceInBlocks(partialTicks);
		float paucBoundaryClipDistance = PauCLodNearClipOverride.overrideNearClipBlocks(dhNearClipDistance);
		setUniform(clipDistanceUniform, paucBoundaryClipDistance);

		samplers.update();
		uniforms.update();

		customUniforms.push(this);

		images.update();
	}

	private void setUniform(int index, float value) {
		GL43C.glUniform1f(index, value);
	}

	public void setModelPos(DhApiVec3f modelPos) {
		setUniform(modelOffsetUniform, modelPos);
	}

	private void setUniform(int index, DhApiVec3f pos) {
		GL43C.glUniform3f(index, pos.x, pos.y, pos.z);
	}

}
