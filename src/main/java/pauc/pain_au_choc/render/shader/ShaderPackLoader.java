package pauc.pain_au_choc.render.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads OptiFine-format shader packs from the shaderpacks directory.
 * Supports both directory-based and ZIP-based shader packs.
 *
 * Shaderpack structure (OptiFine standard):
 *   shaders/
 *     shaders.properties     - Global settings
 *     gbuffers_basic.vsh     - Basic vertex shader
 *     gbuffers_basic.fsh     - Basic fragment shader
 *     gbuffers_terrain.vsh   - Terrain vertex shader
 *     gbuffers_terrain.fsh   - Terrain fragment shader
 *     gbuffers_water.vsh     - Water vertex shader
 *     gbuffers_water.fsh     - Water fragment shader
 *     gbuffers_entities.vsh  - Entity vertex shader
 *     gbuffers_entities.fsh  - Entity fragment shader
 *     gbuffers_hand.vsh      - Hand vertex shader
 *     gbuffers_hand.fsh      - Hand fragment shader
 *     shadow.vsh             - Shadow vertex shader
 *     shadow.fsh             - Shadow fragment shader
 *     composite.vsh          - Composite vertex shader
 *     composite.fsh          - Composite fragment shader
 *     composite1-15.vsh/fsh  - Additional composite passes
 *     deferred.vsh           - Deferred pass vertex shader
 *     deferred.fsh           - Deferred pass fragment shader
 *     deferred1-15.vsh/fsh   - Additional deferred passes
 *     final.vsh              - Final pass vertex shader
 *     final.fsh              - Final pass fragment shader
 *
 * Adapted from Oculus/Iris shaderpack loading.
 */
public class ShaderPackLoader {

    /** Standard program names in load order. */
    public static final String[] GBUFFER_PROGRAMS = {
            "gbuffers_basic", "gbuffers_textured", "gbuffers_textured_lit",
            "gbuffers_skybasic", "gbuffers_skytextured",
            "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout",
            "gbuffers_terrain_cutout_mip",
            "gbuffers_water",
            "gbuffers_entities", "gbuffers_entities_glowing",
            "gbuffers_block",
            "gbuffers_hand", "gbuffers_hand_water",
            "gbuffers_weather", "gbuffers_clouds",
            "gbuffers_armor_glint", "gbuffers_beaconbeam",
            "gbuffers_spidereyes", "gbuffers_damagedblock"
    };

    /** Standard fallback chain for gbuffer programs (OptiFine spec). */
    private static final Map<String, String> FALLBACK_CHAIN = new LinkedHashMap<>();

    static {
        FALLBACK_CHAIN.put("gbuffers_terrain_solid", "gbuffers_terrain");
        FALLBACK_CHAIN.put("gbuffers_terrain_cutout", "gbuffers_terrain");
        FALLBACK_CHAIN.put("gbuffers_terrain_cutout_mip", "gbuffers_terrain");
        FALLBACK_CHAIN.put("gbuffers_water", "gbuffers_terrain");
        FALLBACK_CHAIN.put("gbuffers_skytextured", "gbuffers_textured");
        FALLBACK_CHAIN.put("gbuffers_skybasic", "gbuffers_basic");
        FALLBACK_CHAIN.put("gbuffers_textured_lit", "gbuffers_textured");
        FALLBACK_CHAIN.put("gbuffers_textured", "gbuffers_basic");
        FALLBACK_CHAIN.put("gbuffers_entities", "gbuffers_textured_lit");
        FALLBACK_CHAIN.put("gbuffers_entities_glowing", "gbuffers_entities");
        FALLBACK_CHAIN.put("gbuffers_block", "gbuffers_terrain");
        FALLBACK_CHAIN.put("gbuffers_hand", "gbuffers_textured_lit");
        FALLBACK_CHAIN.put("gbuffers_hand_water", "gbuffers_hand");
        FALLBACK_CHAIN.put("gbuffers_weather", "gbuffers_textured_lit");
        FALLBACK_CHAIN.put("gbuffers_clouds", "gbuffers_textured");
        FALLBACK_CHAIN.put("gbuffers_armor_glint", "gbuffers_textured");
        FALLBACK_CHAIN.put("gbuffers_beaconbeam", "gbuffers_textured");
        FALLBACK_CHAIN.put("gbuffers_spidereyes", "gbuffers_textured");
        FALLBACK_CHAIN.put("gbuffers_damagedblock", "gbuffers_terrain");
    }

    /** Result of loading a shaderpack. */
    public static class ShaderPack {
        public final String name;
        public final Path rootPath;
        public final Map<String, String> properties;
        public final Map<String, ProgramSource> programs;
        public final boolean hasShadow;
        public final int compositePassCount;
        public final int deferredPassCount;

        public ShaderPack(String name, Path rootPath, Map<String, String> properties,
                          Map<String, ProgramSource> programs, boolean hasShadow,
                          int compositePassCount, int deferredPassCount) {
            this.name = name;
            this.rootPath = rootPath;
            this.properties = properties;
            this.programs = programs;
            this.hasShadow = hasShadow;
            this.compositePassCount = compositePassCount;
            this.deferredPassCount = deferredPassCount;
        }

        /** Get a program with fallback resolution. */
        public ProgramSource getProgram(String name) {
            ProgramSource source = this.programs.get(name);
            if (source != null) return source;

            // Follow fallback chain
            String fallback = FALLBACK_CHAIN.get(name);
            while (fallback != null) {
                source = this.programs.get(fallback);
                if (source != null) return source;
                fallback = FALLBACK_CHAIN.get(fallback);
            }

            return null;
        }
    }

    /** Source code pair for a shader program. */
    public static class ProgramSource {
        public final String name;
        public final String vertexSource;
        public final String fragmentSource;
        public final String geometrySource; // nullable

        public ProgramSource(String name, String vertexSource, String fragmentSource, String geometrySource) {
            this.name = name;
            this.vertexSource = vertexSource;
            this.fragmentSource = fragmentSource;
            this.geometrySource = geometrySource;
        }
    }

    /**
     * Load a shaderpack from a directory path.
     *
     * @param packPath Path to the shaderpack root (contains shaders/ directory)
     * @return Loaded ShaderPack, or null on failure
     */
    public static ShaderPack load(Path packPath) {
        String packName = packPath.getFileName().toString();
        Path shadersDir = packPath.resolve("shaders");

        if (!Files.isDirectory(shadersDir)) {
            System.err.println("[PAUC Shader] No shaders/ directory found in: " + packPath);
            return null;
        }

        // Load shaders.properties
        Map<String, String> properties = loadProperties(shadersDir.resolve("shaders.properties"));

        // Load all shader programs
        Map<String, ProgramSource> programs = new LinkedHashMap<>();

        // Load gbuffer programs
        for (String programName : GBUFFER_PROGRAMS) {
            ProgramSource source = loadProgram(shadersDir, programName);
            if (source != null) {
                programs.put(programName, source);
            }
        }

        // Load shadow program
        ProgramSource shadow = loadProgram(shadersDir, "shadow");
        boolean hasShadow = false;
        if (shadow != null) {
            programs.put("shadow", shadow);
            hasShadow = true;
        }

        // Load deferred passes (deferred, deferred1-15)
        int deferredCount = 0;
        for (int i = 0; i <= 15; i++) {
            String name = i == 0 ? "deferred" : "deferred" + i;
            ProgramSource deferred = loadProgram(shadersDir, name);
            if (deferred != null) {
                programs.put(name, deferred);
                deferredCount = i + 1;
            }
        }

        // Load composite passes (composite, composite1-15)
        int compositeCount = 0;
        for (int i = 0; i <= 15; i++) {
            String name = i == 0 ? "composite" : "composite" + i;
            ProgramSource composite = loadProgram(shadersDir, name);
            if (composite != null) {
                programs.put(name, composite);
                compositeCount = i + 1;
            }
        }

        // Load final pass
        ProgramSource finalPass = loadProgram(shadersDir, "final");
        if (finalPass != null) {
            programs.put("final", finalPass);
        }

        System.out.println("[PAUC Shader] Loaded shaderpack '" + packName + "': "
                + programs.size() + " programs, shadow=" + hasShadow
                + ", deferred=" + deferredCount + ", composite=" + compositeCount);

        return new ShaderPack(packName, packPath, properties, programs,
                hasShadow, compositeCount, deferredCount);
    }

    /**
     * List all available shaderpacks in the shaderpacks directory.
     *
     * @param shaderpacksDir The shaderpacks directory
     * @return List of shaderpack directory names
     */
    public static List<String> listAvailable(Path shaderpacksDir) {
        List<String> packs = new ArrayList<>();
        if (!Files.isDirectory(shaderpacksDir)) return packs;

        try (Stream<Path> entries = Files.list(shaderpacksDir)) {
            entries.filter(p -> Files.isDirectory(p.resolve("shaders"))
                            || p.toString().endsWith(".zip"))
                    .forEach(p -> packs.add(p.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("[PAUC Shader] Failed to list shaderpacks: " + e.getMessage());
        }

        return packs;
    }

    // ---- Internal loading helpers ----

    private static ProgramSource loadProgram(Path shadersDir, String programName) {
        Path vshPath = shadersDir.resolve(programName + ".vsh");
        Path fshPath = shadersDir.resolve(programName + ".fsh");
        Path gshPath = shadersDir.resolve(programName + ".gsh");

        // At minimum need vertex + fragment
        if (!Files.exists(vshPath) || !Files.exists(fshPath)) {
            return null;
        }

        try {
            String vertexSource = readShaderSource(vshPath, shadersDir);
            String fragmentSource = readShaderSource(fshPath, shadersDir);
            String geometrySource = Files.exists(gshPath) ? readShaderSource(gshPath, shadersDir) : null;

            return new ProgramSource(programName, vertexSource, fragmentSource, geometrySource);
        } catch (IOException e) {
            System.err.println("[PAUC Shader] Failed to load program '" + programName + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Read shader source with #include directive support.
     * Supports the standard /shaders/include/ convention.
     */
    private static String readShaderSource(Path filePath, Path shadersDir) throws IOException {
        String source = Files.readString(filePath, StandardCharsets.UTF_8);
        return processIncludes(source, shadersDir, 0);
    }

    private static String processIncludes(String source, Path shadersDir, int depth) throws IOException {
        if (depth > 16) {
            throw new IOException("Include depth exceeded (possible circular include)");
        }

        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                // Parse #include "path" or #include <path>
                String includePath = extractIncludePath(trimmed);
                if (includePath != null) {
                    Path includeFile = shadersDir.resolve(includePath);
                    if (Files.exists(includeFile)) {
                        String includeSource = Files.readString(includeFile, StandardCharsets.UTF_8);
                        result.append(processIncludes(includeSource, shadersDir, depth + 1));
                    } else {
                        result.append("// [PAUC] Include not found: ").append(includePath).append("\n");
                    }
                } else {
                    result.append(line).append("\n");
                }
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }

    private static String extractIncludePath(String line) {
        int startQuote = line.indexOf('"');
        int endQuote = line.lastIndexOf('"');
        if (startQuote >= 0 && endQuote > startQuote) {
            return line.substring(startQuote + 1, endQuote);
        }

        int startAngle = line.indexOf('<');
        int endAngle = line.indexOf('>');
        if (startAngle >= 0 && endAngle > startAngle) {
            return line.substring(startAngle + 1, endAngle);
        }

        return null;
    }

    private static Map<String, String> loadProperties(Path propertiesFile) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (!Files.exists(propertiesFile)) return properties;

        try {
            Properties props = new Properties();
            props.load(Files.newInputStream(propertiesFile));
            for (String key : props.stringPropertyNames()) {
                properties.put(key, props.getProperty(key));
            }
        } catch (IOException e) {
            System.err.println("[PAUC Shader] Failed to load shaders.properties: " + e.getMessage());
        }

        return properties;
    }
}
