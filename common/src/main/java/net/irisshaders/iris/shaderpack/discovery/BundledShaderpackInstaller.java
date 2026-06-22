package net.irisshaders.iris.shaderpack.discovery;

import net.irisshaders.iris.Iris;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class BundledShaderpackInstaller {
	public static final String PHOTON_ID = "pauc_builtin_photon";
	public static final String SOLAS_ID = "pauc_builtin_solas";
	private static final String ALLOW_EXTERNAL_PACKS_PROPERTY = "pauc.shaderpacks.allowExternal";

	private static final String CACHE_DIR_NAME = ".pauc_builtin_shaderpacks";
	private static final String CONFIG_DIR_NAME = ".pauc_builtin_shaderpack_configs";
	private static final String MANIFEST_FILE_NAME = ".pauc-bundled-manifest.txt";

	private static final List<BundledShaderpack> BUNDLED_PACKS = List.of(
		new BundledShaderpack(
			PHOTON_ID,
			"Photon",
			"photon_v1.3b.zip",
			"photon_v1.3b",
			"/pauc/shaderpacks/photon_v1.3b",
			"/pauc/shaderpacks/photon_v1.3b.files.txt"
		),
		new BundledShaderpack(
			SOLAS_ID,
			"Solas",
			"Solas Shader V3.6.zip",
			"solas_shader_v3.6",
			"/pauc/shaderpacks/solas_shader_v3.6",
			"/pauc/shaderpacks/solas_shader_v3.6.files.txt"
		)
	);

	private static final Map<String, BundledShaderpack> PACKS_BY_ID = new LinkedHashMap<>();
	private static final Map<String, String> LEGACY_NAME_TO_ID = new LinkedHashMap<>();

	static {
		for (BundledShaderpack pack : BUNDLED_PACKS) {
			PACKS_BY_ID.put(pack.id(), pack);
			LEGACY_NAME_TO_ID.put(pack.legacyFileName(), pack.id());
			LEGACY_NAME_TO_ID.put(pack.displayName(), pack.id());
		}
	}

	private BundledShaderpackInstaller() {
	}

	public static void ensureBundledShaderpacksPresent(Path shaderpacksDirectory) {
		try {
			Files.createDirectories(cacheDirectory(shaderpacksDirectory));
			Files.createDirectories(configDirectory(shaderpacksDirectory));
		} catch (IOException e) {
			Iris.logger.warn("Failed to prepare PauC bundled shaderpack cache directories in {}.", shaderpacksDirectory, e);
		}
	}

	public static List<String> bundledPackIds() {
		return PACKS_BY_ID.keySet().stream().toList();
	}

	public static boolean allowExternalPackSelection() {
		String rawValue = System.getProperty(ALLOW_EXTERNAL_PACKS_PROPERTY);
		return rawValue != null && Boolean.parseBoolean(rawValue);
	}

	public static List<String> mergeBundledWithExternal(List<String> externalPackNames) {
		if (!allowExternalPackSelection()) {
			return bundledPackIds();
		}

		List<String> merged = new ArrayList<>(bundledPackIds());
		for (String externalPackName : externalPackNames) {
			if (shouldHidePackFileName(externalPackName)) {
				continue;
			}
			merged.add(canonicalizePackName(externalPackName));
		}
		return merged;
	}

	public static String canonicalizePackName(@Nullable String packName) {
		if (packName == null || packName.isBlank()) {
			return packName;
		}

		String directMatch = LEGACY_NAME_TO_ID.get(packName);
		if (directMatch != null) {
			return directMatch;
		}

		String normalized = packName.toLowerCase(Locale.ROOT);
		if (normalized.contains("photon")) {
			return PHOTON_ID;
		}
		if (normalized.contains("solas")) {
			return SOLAS_ID;
		}
		return packName;
	}

	public static String displayPackName(@Nullable String packName) {
		if (packName == null || packName.isBlank()) {
			return "";
		}

		BundledShaderpack bundledPack = PACKS_BY_ID.get(canonicalizePackName(packName));
		if (bundledPack != null) {
			return bundledPack.displayName();
		}

		if (packName.endsWith(".zip")) {
			return packName.substring(0, packName.length() - 4);
		}
		return packName;
	}

	public static boolean isBundledPackId(String packName) {
		return PACKS_BY_ID.containsKey(canonicalizePackName(packName));
	}

	public static boolean shouldHidePackFileName(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return true;
		}

		if (CACHE_DIR_NAME.equals(fileName) || CONFIG_DIR_NAME.equals(fileName)) {
			return true;
		}

		return LEGACY_NAME_TO_ID.containsKey(fileName);
	}

	public static @Nullable ResolvedBundledShaderpack resolveBundledPack(String packName, Path shaderpacksDirectory) throws IOException {
		BundledShaderpack bundledPack = PACKS_BY_ID.get(canonicalizePackName(packName));
		if (bundledPack == null) {
			return null;
		}

		Path cacheDirectory = cacheDirectory(shaderpacksDirectory);
		Files.createDirectories(cacheDirectory);
		Path cachedPackRoot = cacheDirectory.resolve(bundledPack.cacheDirectoryName());
		ensureCachedPackDirectory(bundledPack, cachedPackRoot);

		Path configDirectory = configDirectory(shaderpacksDirectory);
		Files.createDirectories(configDirectory);
		Path configFile = configDirectory.resolve(bundledPack.id() + ".txt");

		return new ResolvedBundledShaderpack(bundledPack.id(), bundledPack.displayName(), cachedPackRoot, configFile);
	}

	private static void ensureCachedPackDirectory(BundledShaderpack pack, Path cachedPackRoot) throws IOException {
		String manifest = readRequiredResourceText(pack.manifestResourcePath());
		Path manifestFile = cachedPackRoot.resolve(MANIFEST_FILE_NAME);
		if (Files.isDirectory(cachedPackRoot)
			&& Files.isRegularFile(manifestFile)
			&& manifest.equals(Files.readString(manifestFile, StandardCharsets.UTF_8))) {
			return;
		}

		deleteDirectoryIfExists(cachedPackRoot);
		Files.createDirectories(cachedPackRoot);
		for (String relativePath : parseManifest(manifest)) {
			copyBundledResource(pack, relativePath, cachedPackRoot.resolve(relativePath));
		}
		Files.writeString(manifestFile, manifest, StandardCharsets.UTF_8);
		Iris.logger.info("Updated PauC bundled shaderpack cache for {} at {}.", pack.displayName(), cachedPackRoot);
	}

	private static void copyBundledResource(BundledShaderpack pack, String relativePath, Path destination) throws IOException {
		Path parent = destination.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		String resourcePath = pack.resourceRoot() + "/" + relativePath;
		try (InputStream stream = BundledShaderpackInstaller.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IOException("Bundled shaderpack resource not found: " + resourcePath);
			}

			Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
			Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	private static List<String> parseManifest(String manifest) {
		return manifest.lines()
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();
	}

	private static String readRequiredResourceText(String resourcePath) throws IOException {
		try (InputStream stream = BundledShaderpackInstaller.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IOException("Bundled shaderpack manifest not found: " + resourcePath);
			}

			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void deleteDirectoryIfExists(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}

		try (Stream<Path> stream = Files.walk(directory)) {
			for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static Path cacheDirectory(Path shaderpacksDirectory) {
		return shaderpacksDirectory.resolve(CACHE_DIR_NAME);
	}

	private static Path configDirectory(Path shaderpacksDirectory) {
		return shaderpacksDirectory.resolve(CONFIG_DIR_NAME);
	}

	public record ResolvedBundledShaderpack(String id, String displayName, Path packRoot, Path configFile) {
	}

	private record BundledShaderpack(
		String id,
		String displayName,
		String legacyFileName,
		String cacheDirectoryName,
		String resourceRoot,
		String manifestResourcePath
	) {
	}
}
