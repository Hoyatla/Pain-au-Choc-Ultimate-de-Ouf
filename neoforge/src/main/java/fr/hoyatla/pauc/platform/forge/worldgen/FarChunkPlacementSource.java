package fr.hoyatla.pauc.platform.forge.worldgen;

import javax.annotation.Nullable;
import java.util.List;

public record FarChunkPlacementSource(String className, String packageName, @Nullable String generationHint) {
	private static final String CAPTURE_STACK_PROPERTY = "pauc.farPlacement.captureSourceStack";
	private static final List<String> IGNORED_PREFIXES = List.of(
		"java.",
		"javax.",
		"jdk.",
		"sun.",
		"org.spongepowered.",
		"org.apache.logging.",
		"org.slf4j.",
		"cpw.mods.",
		"net.minecraftforge.",
		"net.minecraft.",
		"com.mojang.",
		"fr.hoyatla.pauc.",
		"net.irisshaders.",
		"net.caffeinemc."
	);

	public static FarChunkPlacementSource capture(@Nullable String generationHint) {
		if (!Boolean.parseBoolean(System.getProperty(CAPTURE_STACK_PROPERTY, "false"))) {
			return new FarChunkPlacementSource("unknown", "", generationHint);
		}

		for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
			String className = element.getClassName();

			if (shouldSkip(className)) {
				continue;
			}

			return new FarChunkPlacementSource(className, extractPackageName(className), generationHint);
		}

		return new FarChunkPlacementSource("unknown", "", generationHint);
	}

	public boolean isUnknown() {
		return "unknown".equals(className);
	}

	public boolean isVanillaOrForge() {
		return className.startsWith("net.minecraft.")
			|| className.startsWith("net.minecraftforge.")
			|| className.startsWith("com.mojang.");
	}

	public boolean isMCreatorGeneratedMod() {
		return className.startsWith("net.mcreator.") || packageName.startsWith("net.mcreator.");
	}

	public boolean matchesPrefix(String prefix) {
		return className.startsWith(prefix) || packageName.startsWith(prefix);
	}

	private static boolean shouldSkip(String className) {
		for (String prefix : IGNORED_PREFIXES) {
			if (className.startsWith(prefix)) {
				return true;
			}
		}

		return false;
	}

	private static String extractPackageName(String className) {
		int separator = className.lastIndexOf('.');
		return separator >= 0 ? className.substring(0, separator) : "";
	}
}
