package fr.hoyatla.pauc.platform.forge.client;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class PauCClientFrameMetrics {
	private static final String[] FPS_METHOD_NAMES = { "getFps", "m_260875_", "m" };
	private static final String[] FPS_STRING_FIELD_NAMES = { "fpsString", "f_90977_", "A" };

	private PauCClientFrameMetrics() {
	}

	public static int queryFps(Minecraft minecraft) {
		if (minecraft == null) {
			return -1;
		}

		try {
			int fps = minecraft.getFps();
			if (fps > 0) {
				return fps;
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Reflection fallbacks below cover remapped production runtimes.
		}

		int reflectedFps = queryReflectedFps();
		if (reflectedFps > 0) {
			return reflectedFps;
		}

		return parseFpsString(queryFpsString());
	}

	private static int queryReflectedFps() {
		for (String methodName : FPS_METHOD_NAMES) {
			try {
				Method method = Minecraft.class.getDeclaredMethod(methodName);
				if (method.getReturnType() != Integer.TYPE || method.getParameterCount() != 0) {
					continue;
				}
				method.setAccessible(true);
				Object receiver = Modifier.isStatic(method.getModifiers()) ? null : Minecraft.getInstance();
				Object result = method.invoke(receiver);
				if (result instanceof Integer fps && fps > 0) {
					return fps;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Try the next mapping name.
			}
		}
		return -1;
	}

	private static String queryFpsString() {
		for (String fieldName : FPS_STRING_FIELD_NAMES) {
			try {
				Field field = Minecraft.class.getDeclaredField(fieldName);
				if (field.getType() != String.class) {
					continue;
				}
				field.setAccessible(true);
				Object receiver = Modifier.isStatic(field.getModifiers()) ? null : Minecraft.getInstance();
				Object result = field.get(receiver);
				if (result instanceof String fpsString && !fpsString.isBlank()) {
					return fpsString;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Try the next mapping name.
			}
		}
		return "";
	}

	private static int parseFpsString(String value) {
		if (value == null || value.isBlank()) {
			return -1;
		}

		int start = -1;
		for (int index = 0; index < value.length(); index++) {
			if (Character.isDigit(value.charAt(index))) {
				start = index;
				break;
			}
		}
		if (start < 0) {
			return -1;
		}

		int end = start;
		while (end < value.length() && Character.isDigit(value.charAt(end))) {
			end++;
		}

		try {
			return Integer.parseInt(value.substring(start, end));
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}
}
