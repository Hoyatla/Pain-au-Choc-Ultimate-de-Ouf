// This file is based on code from Sodium by JellySquid, licensed under the LGPLv3 license.

package net.irisshaders.iris.gl.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.KHRDebug;

public class ProgramCreator {
	private static final Logger LOGGER = LogManager.getLogger(ProgramCreator.class);

	public static int create(String name, GlShader... shaders) {
		boolean bindExtendedAttributes = !TransformPatcher.shouldUseConservativeAttributeBindings();
		try {
			return createProgram(name, shaders, bindExtendedAttributes);
		} catch (ShaderCompileException primaryFailure) {
			if (!bindExtendedAttributes || !isBuiltinAttributeCollision(primaryFailure.getError())) {
				throw primaryFailure;
			}

			LOGGER.warn("Program '{}' failed link because a builtin vertex attribute collided with an extended binding. " +
				"Retrying with conservative attribute bindings.", name);
			return createProgram(name, shaders, false);
		}
	}

	private static int createProgram(String name, GlShader[] shaders, boolean bindExtendedAttributes) {
		int program = GlStateManager.glCreateProgram();

		if (bindExtendedAttributes) {
			GlStateManager._glBindAttribLocation(program, 11, "iris_Entity");
			GlStateManager._glBindAttribLocation(program, 11, "mc_Entity");
			GlStateManager._glBindAttribLocation(program, 12, "mc_midTexCoord");
			GlStateManager._glBindAttribLocation(program, 13, "at_tangent");
			GlStateManager._glBindAttribLocation(program, 14, "at_midBlock");
		}

		GlStateManager._glBindAttribLocation(program, 0, "Position");
		GlStateManager._glBindAttribLocation(program, 1, "UV0");

		for (GlShader shader : shaders) {
			GLDebug.nameObject(KHRDebug.GL_SHADER, shader.getHandle(), shader.getName());

			GlStateManager.glAttachShader(program, shader.getHandle());
		}

		GlStateManager.glLinkProgram(program);

		GLDebug.nameObject(KHRDebug.GL_PROGRAM, program, name);

		//Always detach shaders according to https://www.khronos.org/opengl/wiki/Shader_Compilation#Cleanup
		for (GlShader shader : shaders) {
			IrisRenderSystem.detachShader(program, shader.getHandle());
		}

		String log = IrisRenderSystem.getProgramInfoLog(program);

		if (!log.isEmpty()) {
			LOGGER.warn("Program link log for " + name + ": " + log);
		}

		int result = GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS);

		if (result != GL20C.GL_TRUE) {
			GlStateManager.glDeleteProgram(program);
			throw new ShaderCompileException(name, log);
		}

		return program;
	}

	private static boolean isBuiltinAttributeCollision(String log) {
		if (log == null) {
			return false;
		}

		String lowered = log.toLowerCase(java.util.Locale.ROOT);
		return lowered.contains("builtin vertex attribute")
			&& lowered.contains("collid")
			&& (lowered.contains("at_tangent") || lowered.contains("mc_midtexcoord") || lowered.contains("at_midblock"));
	}
}
