package fr.hoyatla.pauc.lod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

public final class PauCLodScreenFogColor {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final ByteBuffer PIXEL = BufferUtils.createByteBuffer(4);
	private static final float[] COLOR = new float[] {1.0F, 1.0F, 1.0F};
	private static final float[] FALLBACK = new float[] {1.0F, 1.0F, 1.0F};
	private static final float[] SAMPLE_POINT = new float[] {0.50F, 0.90F};
	private static final int CAPTURE_INTERVAL_FRAMES = 2;
	private static final float CAPTURE_SMOOTHING = 0.35F;
	private static boolean captured;
	private static boolean captureLogged;
	private static boolean fallbackLogged;
	private static boolean noSampleLogged;
	private static boolean failureLogged;
	private static float capturedLuminance = 1.0F;
	private static int captureFrameIndex;

	private PauCLodScreenFogColor() {
	}

	public static void captureFromMainTarget() {
		if (!PauCLodShaderPresentation.shouldLateRenderFallbackLods()) {
			captured = false;
			return;
		}

		RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
		if (mainTarget.frameBufferId <= 0 || mainTarget.width <= 0 || mainTarget.height <= 0) {
			captured = false;
			return;
		}
		captureFrameIndex++;
		if (captured && captureFrameIndex % CAPTURE_INTERVAL_FRAMES != 0) {
			return;
		}

		int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
		int previousReadBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
		try {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
			GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
			int x = clamp(Math.round((mainTarget.width - 1) * SAMPLE_POINT[0]), 0, mainTarget.width - 1);
			int y = clamp(Math.round((mainTarget.height - 1) * SAMPLE_POINT[1]), 0, mainTarget.height - 1);
			PIXEL.clear();
			GL11C.glReadPixels(x, y, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, PIXEL);
			float sampleRed = byteToFloat(PIXEL.get(0));
			float sampleGreen = byteToFloat(PIXEL.get(1));
			float sampleBlue = byteToFloat(PIXEL.get(2));
			float sampleLuminance = luminance(sampleRed, sampleGreen, sampleBlue);
			if (sampleLuminance < 0.08F && captured) {
				return;
			}
			if (sampleLuminance < 0.08F) {
				captured = false;
				if (!noSampleLogged) {
					noSampleLogged = true;
					LOGGER.info(
						"PauC found no usable shader screen fog color for late LOD blend; single-sample luminance={}.",
						round(sampleLuminance)
					);
				}
				return;
			}

			applyCapturedColor(sampleRed, sampleGreen, sampleBlue);
			logCapture(1, captured ? "single-smoothed" : "single");
		} catch (Exception | Error error) {
			captured = false;
			if (!failureLogged) {
				failureLogged = true;
				LOGGER.warn("PauC could not sample shader screen fog color for late LOD blend.", error);
			}
		} finally {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
			GL11C.glReadBuffer(previousReadBuffer);
		}
	}

	private static void applyCapturedColor(float sampleRed, float sampleGreen, float sampleBlue) {
		if (captured) {
			COLOR[0] = lerp(COLOR[0], sampleRed, CAPTURE_SMOOTHING);
			COLOR[1] = lerp(COLOR[1], sampleGreen, CAPTURE_SMOOTHING);
			COLOR[2] = lerp(COLOR[2], sampleBlue, CAPTURE_SMOOTHING);
		} else {
			COLOR[0] = sampleRed;
			COLOR[1] = sampleGreen;
			COLOR[2] = sampleBlue;
		}
		capturedLuminance = luminance(COLOR[0], COLOR[1], COLOR[2]);
		captured = true;
	}

	public static float[] currentOrFallback(float[] fallback) {
		if (captured) {
			return COLOR;
		}
		if (fallback == null || fallback.length < 3) {
			return FALLBACK;
		}
		float red = clamp(fallback[0], 0.0F, 1.0F);
		float green = clamp(fallback[1], 0.0F, 1.0F);
		float blue = clamp(fallback[2], 0.0F, 1.0F);
		float lift = Math.max(0.0F, 0.78F - luminance(red, green, blue));
		FALLBACK[0] = clamp(red + lift, 0.0F, 1.0F);
		FALLBACK[1] = clamp(green + lift, 0.0F, 1.0F);
		FALLBACK[2] = clamp(blue + lift, 0.0F, 1.0F);
		if (!fallbackLogged) {
			fallbackLogged = true;
			LOGGER.info(
				"PauC is using lifted vanilla fog color for late LOD blend: rgb={}/{}/{}.",
				round(FALLBACK[0]),
				round(FALLBACK[1]),
				round(FALLBACK[2])
			);
		}
		return FALLBACK;
	}

	public static boolean hasCapturedColor() {
		return captured;
	}

	public static float rescueStrength(float[] fallbackColor) {
		float luminance = captured ? capturedLuminance : fallbackLuminance(fallbackColor);
		if (!captured) {
			return 1.0F;
		}
		return 1.0F - smoothstep(0.42F, 0.82F, luminance);
	}

	private static void logCapture(int samples, String mode) {
		if (!captureLogged) {
			captureLogged = true;
			LOGGER.info(
				"PauC sampled shader screen fog color for late LOD blend: rgb={}/{}/{}, samples={}, mode={}.",
				round(COLOR[0]),
				round(COLOR[1]),
				round(COLOR[2]),
				samples,
				mode
			);
		}
	}

	private static float byteToFloat(byte value) {
		return (value & 0xFF) / 255.0F;
	}

	private static float luminance(float red, float green, float blue) {
		return red * 0.2126F + green * 0.7152F + blue * 0.0722F;
	}

	private static float saturation(float red, float green, float blue) {
		float min = Math.min(red, Math.min(green, blue));
		float max = Math.max(red, Math.max(green, blue));
		return max <= 0.001F ? 0.0F : (max - min) / max;
	}

	private static float fallbackLuminance(float[] fallbackColor) {
		if (fallbackColor == null || fallbackColor.length < 3) {
			return 0.0F;
		}
		return luminance(
			clamp(fallbackColor[0], 0.0F, 1.0F),
			clamp(fallbackColor[1], 0.0F, 1.0F),
			clamp(fallbackColor[2], 0.0F, 1.0F)
		);
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float factor = clamp((value - edge0) / Math.max(edge1 - edge0, 0.001F), 0.0F, 1.0F);
		return factor * factor * (3.0F - 2.0F * factor);
	}

	private static float lerp(float start, float end, float factor) {
		return start + (end - start) * clamp(factor, 0.0F, 1.0F);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float round(float value) {
		return Math.round(value * 1000.0F) / 1000.0F;
	}
}
