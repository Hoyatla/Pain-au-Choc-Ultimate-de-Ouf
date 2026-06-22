package fr.hoyatla.pauc.lod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.irisshaders.iris.shaderpack.discovery.BundledShaderpackInstaller;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class PauCShaderCapabilitiesLoader {
	private static final String MANIFEST_DIRECTORY = "pauc";
	private static final String MANIFEST_FILE = "capabilities.json";

	private PauCShaderCapabilitiesLoader() {
	}

	public static PauCShaderCapabilities load(Path shaderDirectory, String packName) {
		PauCLodShaderProfiles.Family family = PauCLodShaderProfiles.familyForPackName(packName);
		if (shaderDirectory == null || !Files.isDirectory(shaderDirectory)) {
			return PauCShaderCapabilities.externalPack(packName, family, "missing-shaders-dir");
		}

		Path manifestPath = shaderDirectory.resolve(MANIFEST_DIRECTORY).resolve(MANIFEST_FILE);
		if (!Files.isRegularFile(manifestPath)) {
			if (BundledShaderpackInstaller.isBundledPackId(packName)) {
				return inferBundledCapabilities(shaderDirectory, packName, family);
			}
			return PauCShaderCapabilities.externalPack(packName, family, "manifest-missing");
		}

		try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			int schemaVersion = readInt(root, "schemaVersion", 1);
			String packId = readString(root, "packId", packName);
			PauCShaderProfileId profileId = PauCShaderProfileId.fromManifestValue(readString(root, "profileId", "generic-compat"));
			return PauCShaderCapabilities.manifest(
				schemaVersion,
				packId,
				profileId,
				readBoolean(root, "supportsDhTerrain", false),
				readBoolean(root, "supportsDhShadow", false),
				readBoolean(root, "supportsTransitionFog", false),
				readBoolean(root, "supportsColoredLights", false),
				readBoolean(root, "supportsWeatherFog", false),
				"manifest-ok"
			);
		} catch (IOException exception) {
			return PauCShaderCapabilities.externalPack(packName, family, "manifest-io-error");
		} catch (IllegalStateException | JsonParseException exception) {
			return PauCShaderCapabilities.externalPack(packName, family, "manifest-invalid");
		}
	}

	private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
		return root.has(key) ? root.get(key).getAsBoolean() : fallback;
	}

	private static int readInt(JsonObject root, String key, int fallback) {
		return root.has(key) ? root.get(key).getAsInt() : fallback;
	}

	private static String readString(JsonObject root, String key, String fallback) {
		return root.has(key) ? root.get(key).getAsString() : fallback;
	}

	private static PauCShaderCapabilities inferBundledCapabilities(
		Path shaderDirectory,
		String packName,
		PauCLodShaderProfiles.Family family
	) {
		String normalizedPackId = BundledShaderpackInstaller.canonicalizePackName(packName);
		boolean dhTerrain = containsProgram(shaderDirectory, "dh_terrain", "pl_terrain");
		boolean dhShadow = containsProgram(shaderDirectory, "dh_shadow", "pl_shadow");
		boolean transitionFog = family == PauCLodShaderProfiles.Family.PAUC;
		boolean coloredLights = family == PauCLodShaderProfiles.Family.PHOTON
			|| family == PauCLodShaderProfiles.Family.SOLAS
			|| family == PauCLodShaderProfiles.Family.PAUC;
		boolean weatherFog = family == PauCLodShaderProfiles.Family.PHOTON
			|| family == PauCLodShaderProfiles.Family.SOLAS
			|| family == PauCLodShaderProfiles.Family.PAUC;

		if (BundledShaderpackInstaller.PHOTON_ID.equals(normalizedPackId)) {
			dhTerrain = true;
			transitionFog = false;
			coloredLights = true;
			weatherFog = true;
		} else if (BundledShaderpackInstaller.SOLAS_ID.equals(normalizedPackId)) {
			dhTerrain = true;
			dhShadow = true;
			transitionFog = false;
			coloredLights = true;
			weatherFog = true;
		}

		return PauCShaderCapabilities.bundledPack(
			normalizedPackId,
			family,
			dhTerrain,
			dhShadow,
			transitionFog,
			coloredLights,
			weatherFog
		);
	}

	private static boolean containsProgram(Path shaderDirectory, String... programNames) {
		try (var stream = Files.walk(shaderDirectory, 6)) {
			return stream
				.filter(Files::isRegularFile)
				.anyMatch(path -> isProgramFile(path, programNames));
		} catch (IOException | SecurityException exception) {
			return false;
		}
	}

	private static boolean isProgramFile(Path path, String... programNames) {
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!(fileName.endsWith(".vsh")
			|| fileName.endsWith(".fsh")
			|| fileName.endsWith(".gsh")
			|| fileName.endsWith(".csh")
			|| fileName.endsWith(".glsl"))) {
			return false;
		}

		for (String programName : programNames) {
			if (fileName.equals(programName + ".vsh")
				|| fileName.equals(programName + ".fsh")
				|| fileName.equals(programName + ".gsh")
				|| fileName.equals(programName + ".csh")
				|| fileName.equals(programName + ".glsl")) {
				return true;
			}
		}
		return false;
	}
}
