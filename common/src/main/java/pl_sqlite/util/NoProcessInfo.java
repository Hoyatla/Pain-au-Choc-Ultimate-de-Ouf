package pl_sqlite.util;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class NoProcessInfo {
	public NoProcessInfo() {
	}

	public String runAndWaitFor(String command) {
		return resolveKnownCommand(command);
	}

	public String runAndWaitFor(String command, long timeout, TimeUnit unit) {
		return resolveKnownCommand(command);
	}

	private static String resolveKnownCommand(String command) {
		String normalizedCommand = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
		if ("uname -m".equals(normalizedCommand)) {
			return normalizeArchitecture(System.getProperty("os.arch", ""));
		}
		if ("uname -o".equals(normalizedCommand)) {
			String runtimeName = System.getProperty("java.runtime.name", "");
			if (runtimeName.toLowerCase(Locale.ROOT).contains("android")) {
				return "Android";
			}
			return System.getProperty("os.name", "");
		}
		return "";
	}

	private static String normalizeArchitecture(String architecture) {
		String normalized = architecture.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return "";
		}
		if ("amd64".equals(normalized) || "x64".equals(normalized)) {
			return "x86_64";
		}
		if ("x86".equals(normalized) || "i386".equals(normalized) || "i486".equals(normalized) || "i586".equals(normalized) || "i686".equals(normalized)) {
			return "x86";
		}
		if ("arm64".equals(normalized)) {
			return "aarch64";
		}
		return architecture;
	}
}
