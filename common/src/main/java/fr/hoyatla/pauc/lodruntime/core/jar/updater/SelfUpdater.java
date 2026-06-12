package fr.hoyatla.pauc.lodruntime.core.jar.updater;

import java.io.File;
import java.io.InputStream;

public final class SelfUpdater {
	public SelfUpdater() {
	}

	// Release builds ship without embedded download or self-update behavior.
	public static boolean onStart() {
		return false;
	}

	private static boolean onStableStart() {
		return false;
	}

	private static boolean onNightlyStart() {
		return false;
	}

	public static boolean updateMod() {
		return false;
	}

	public static boolean updateMod(String ignoredVersion, File ignoredTargetFile) {
		return false;
	}

	public static boolean updateStableMod(String ignoredVersion, File ignoredTargetFile) {
		return false;
	}

	public static boolean updateNightlyMod(String ignoredVersion, File ignoredTargetFile) {
		return false;
	}

	public static void onClose() {
	}

	private static String convertInputStreamToString(InputStream ignoredStream) {
		return "";
	}
}
