package net.irisshaders.batchedentityrendering.impl;

import fr.hoyatla.pauc.shader.PauCShaders;

public final class BatchedEntityRenderingPolicy {
	private static final String ENABLE_WITH_SHADERS_PROPERTY = "pauc.shader.entityBatchingWithShaders";

	private BatchedEntityRenderingPolicy() {
	}

	public static boolean isEnabled() {
		if (!PauCShaders.isShaderPackInUse()) {
			return true;
		}

		return readBoolean(ENABLE_WITH_SHADERS_PROPERTY, false);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return fallback;
		}

		String normalized = rawValue.trim().toLowerCase();
		if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
			return true;
		}
		if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
			return false;
		}

		return fallback;
	}
}
