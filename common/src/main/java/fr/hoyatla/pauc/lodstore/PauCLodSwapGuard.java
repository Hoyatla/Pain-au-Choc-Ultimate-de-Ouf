package fr.hoyatla.pauc.lodstore;

public final class PauCLodSwapGuard {
	private static final String ENABLED_PROPERTY = "pauc.lod.swapGuard";
	private static final String ALLOW_UNREADY_CLEAR_PROPERTY = "pauc.lod.swapGuardAllowUnreadyClear";
	private static long allowedClears;
	private static long blockedClears;
	private static String lastDecision = "none";

	private PauCLodSwapGuard() {
	}

	public static Decision evaluateRenderCacheClear(
		String reason,
		boolean meshFormatChanged,
		boolean shaderRuntimeChange,
		boolean replacementReady,
		boolean presentationHoldActive,
		boolean hotRestoreActive
	) {
		if (!readBoolean(ENABLED_PROPERTY, true)) {
			return allow("disabled:" + reason);
		}
		if (replacementReady) {
			return allow("replacement-ready:" + reason);
		}
		if (readBoolean(ALLOW_UNREADY_CLEAR_PROPERTY, false)) {
			return allow("override-unready:" + reason);
		}
		if (shaderRuntimeChange) {
			return block("shader-runtime-without-ready-replacement");
		}
		if (meshFormatChanged || presentationHoldActive || hotRestoreActive) {
			return block("no-ready-replacement:" + reason);
		}
		return allow("safe:" + reason);
	}

	public static String describeState() {
		return "swapGuard[allowed="
			+ allowedClears
			+ ", blocked="
			+ blockedClears
			+ ", last="
			+ lastDecision
			+ "]";
	}

	private static Decision allow(String reason) {
		allowedClears++;
		lastDecision = "allow:" + reason;
		return new Decision(true, reason);
	}

	private static Decision block(String reason) {
		blockedClears++;
		lastDecision = "block:" + reason;
		return new Decision(false, reason);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String value = System.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	public record Decision(boolean allowed, String reason) {
	}
}
