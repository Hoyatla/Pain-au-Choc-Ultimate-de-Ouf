package fr.hoyatla.pauc.lod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.texture.TextureInfoCache;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

public final class PauCLodLateDepthBuffer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static DepthTexture savedDepth;
	private static DepthBufferFormat savedFormat;
	private static int savedWidth;
	private static int savedHeight;
	private static boolean captured;
	private static boolean unsupportedLogged;
	private static boolean captureLogged;
	private static boolean restoreLogged;
	private static boolean restoreMissLogged;

	private PauCLodLateDepthBuffer() {
	}

	public static void captureBeforeShaderFinalPass() {
		if (!PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
			captured = false;
			return;
		}

		if (!canCopyDepthTexture()) {
			logUnsupportedOnce();
			captured = false;
			return;
		}

		RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
		int sourceDepthTexture = mainTarget.getDepthTextureId();
		if (sourceDepthTexture <= 0 || mainTarget.width <= 0 || mainTarget.height <= 0) {
			captured = false;
			return;
		}

		DepthBufferFormat format = depthFormat(sourceDepthTexture);
		ensureSavedDepth(mainTarget.width, mainTarget.height, format);
		GL43C.glCopyImageSubData(
			sourceDepthTexture,
			GL43C.GL_TEXTURE_2D,
			0,
			0,
			0,
			0,
			savedDepth.getTextureId(),
			GL43C.GL_TEXTURE_2D,
			0,
			0,
			0,
			0,
			mainTarget.width,
			mainTarget.height,
			1
		);
		captured = true;
		if (!captureLogged) {
			captureLogged = true;
			LOGGER.info(
				"PauC captured vanilla depth before shader final pass for late LOD restore: {}x{}, format={}.",
				mainTarget.width,
				mainTarget.height,
				format
			);
		}
	}

	public static boolean restoreForLateFallbackRender() {
		if (!captured || savedDepth == null) {
			if (!restoreMissLogged) {
				restoreMissLogged = true;
				LOGGER.info("PauC has no saved vanilla depth for late LOD restore this frame.");
			}
			return false;
		}

		if (!canCopyDepthTexture()) {
			logUnsupportedOnce();
			return false;
		}

		RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
		int destinationDepthTexture = mainTarget.getDepthTextureId();
		if (destinationDepthTexture <= 0
			|| mainTarget.width != savedWidth
			|| mainTarget.height != savedHeight) {
			return false;
		}

		GL43C.glCopyImageSubData(
			savedDepth.getTextureId(),
			GL43C.GL_TEXTURE_2D,
			0,
			0,
			0,
			0,
			destinationDepthTexture,
			GL43C.GL_TEXTURE_2D,
			0,
			0,
			0,
			0,
			mainTarget.width,
			mainTarget.height,
			1
		);
		if (!restoreLogged) {
			restoreLogged = true;
			LOGGER.info("PauC restored vanilla depth before late fallback LOD render.");
		}
		return true;
	}

	private static void ensureSavedDepth(int width, int height, DepthBufferFormat format) {
		if (savedDepth != null && savedWidth == width && savedHeight == height && savedFormat == format) {
			return;
		}

		if (savedDepth != null) {
			savedDepth.destroy();
		}
		savedDepth = new DepthTexture("PauC late LOD saved vanilla depth", width, height, format);
		savedWidth = width;
		savedHeight = height;
		savedFormat = format;
		captured = false;
	}

	private static DepthBufferFormat depthFormat(int textureId) {
		int internalFormat = TextureInfoCache.INSTANCE.getInfo(textureId).getInternalFormat();
		return DepthBufferFormat.fromGlEnumOrDefault(internalFormat);
	}

	private static boolean canCopyDepthTexture() {
		return GL.getCapabilities().glCopyImageSubData != MemoryUtil.NULL;
	}

	private static void logUnsupportedOnce() {
		if (!unsupportedLogged) {
			unsupportedLogged = true;
			LOGGER.warn("PauC cannot restore late LOD depth because glCopyImageSubData is unavailable.");
		}
	}
}
