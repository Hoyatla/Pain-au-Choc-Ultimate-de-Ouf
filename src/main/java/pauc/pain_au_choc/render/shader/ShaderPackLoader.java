package pauc.pain_au_choc.render.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static volatile DeferredCompatibilityMode compatibilityMode = DeferredCompatibilityMode.BALANCED;
    private static final Pattern VERSION_DIRECTIVE_PATTERN = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)\\b");

    private enum ShaderStage {
        VERTEX,
        FRAGMENT,
        GEOMETRY
    }

    private static final String LEGACY_VERTEX_PRELUDE =
            "\n#define PAUC_LEGACY_GLSL_BRIDGE 1\n"
                    + "in vec3 Position;\n"
                    + "in vec4 Color;\n"
                    + "in vec2 UV0;\n"
                    + "in vec2 UV1;\n"
                    + "in vec2 UV2;\n"
                    + "in vec3 Normal;\n"
                    + "uniform mat4 pauc_ModelViewMatrix;\n"
                    + "uniform mat4 pauc_ModelViewMatrixInverse;\n"
                    + "uniform mat4 pauc_ProjectionMatrix;\n"
                    + "uniform mat4 pauc_ProjectionMatrixInverse;\n"
                    + "const mat4 pauc_TextureIdentity = mat4(1.0);\n"
                    + "const mat4 pauc_TextureMatrix[8] = mat4[8](\n"
                    + "    pauc_TextureIdentity, pauc_TextureIdentity, pauc_TextureIdentity, pauc_TextureIdentity,\n"
                    + "    pauc_TextureIdentity, pauc_TextureIdentity, pauc_TextureIdentity, pauc_TextureIdentity\n"
                    + ");\n"
                    + "#define vaPosition Position\n"
                    + "#define vaNormal Normal\n"
                    + "#ifdef SHADOW\n"
                    + "#define modelViewMatrix shadowModelView\n"
                    + "#define modelViewMatrixInverse shadowModelViewInverse\n"
                    + "#define projectionMatrix shadowProjection\n"
                    + "#define projectionMatrixInverse shadowProjectionInverse\n"
                    + "#define normalMatrix mat3(shadowModelView)\n"
                    + "#else\n"
                    + "#define modelViewMatrix pauc_ModelViewMatrix\n"
                    + "#define modelViewMatrixInverse pauc_ModelViewMatrixInverse\n"
                    + "#define projectionMatrix pauc_ProjectionMatrix\n"
                    + "#define projectionMatrixInverse pauc_ProjectionMatrixInverse\n"
                    + "#define normalMatrix mat3(pauc_ModelViewMatrix)\n"
                    + "#endif\n"
                    + "#define gl_Vertex vec4(Position, 1.0)\n"
                    + "#define gl_Normal Normal\n"
                    + "#define gl_Color Color\n"
                    + "#define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord1 vec4(UV2, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord2 vec4(UV1, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord3 vec4(UV0, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord4 vec4(UV0, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord5 vec4(UV0, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord6 vec4(UV0, 0.0, 1.0)\n"
                    + "#define gl_MultiTexCoord7 vec4(UV0, 0.0, 1.0)\n"
                    + "#define gl_ModelViewMatrix modelViewMatrix\n"
                    + "#define gl_ModelViewMatrixInverse modelViewMatrixInverse\n"
                    + "#define gl_ProjectionMatrix projectionMatrix\n"
                    + "#define gl_ProjectionMatrixInverse projectionMatrixInverse\n"
                    + "#define gl_ModelViewProjectionMatrix (projectionMatrix * modelViewMatrix)\n"
                    + "#define gl_NormalMatrix normalMatrix\n"
                    + "#define gl_TextureMatrix pauc_TextureMatrix\n"
                    + "#define ftransform() (projectionMatrix * modelViewMatrix * vec4(Position, 1.0))\n";

    private static final String LEGACY_FRAGMENT_PRELUDE =
            "\n#define PAUC_LEGACY_GLSL_BRIDGE 1\n"
                    + "out vec4 pauc_FragData0;\n"
                    + "out vec4 pauc_FragData1;\n"
                    + "out vec4 pauc_FragData2;\n"
                    + "out vec4 pauc_FragData3;\n"
                    + "out vec4 pauc_FragData4;\n"
                    + "out vec4 pauc_FragData5;\n"
                    + "out vec4 pauc_FragData6;\n"
                    + "out vec4 pauc_FragData7;\n"
                    + "struct pauc_FogParameters {\n"
                    + "    vec4 color;\n"
                    + "    float density;\n"
                    + "    float start;\n"
                    + "    float end;\n"
                    + "    float scale;\n"
                    + "};\n"
                    + "uniform pauc_FogParameters pauc_Fog;\n"
                    + "#define gl_Fog pauc_Fog\n"
                    + "#ifndef gl_FogFragCoord\n"
                    + "#define gl_FogFragCoord gl_FragCoord.z\n"
                    + "#endif\n"
                    + "#if __VERSION__ >= 130\n"
                    + "#ifndef shadow2D\n"
                    + "#define shadow2D(tex, coord) vec4(texture(tex, coord))\n"
                    + "#endif\n"
                    + "#ifndef shadow2DProj\n"
                    + "#define shadow2DProj(tex, coord) vec4(textureProj(tex, coord))\n"
                    + "#endif\n"
                    + "#endif\n";


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
        public final DeferredCompatibilityMode compatibilityMode;
        public final List<String> warnings;

        public ShaderPack(String name, Path rootPath, Map<String, String> properties,
                          Map<String, ProgramSource> programs, boolean hasShadow,
                          int compositePassCount, int deferredPassCount,
                          DeferredCompatibilityMode compatibilityMode,
                          List<String> warnings) {
            this.name = name;
            this.rootPath = rootPath;
            this.properties = properties;
            this.programs = programs;
            this.hasShadow = hasShadow;
            this.compositePassCount = compositePassCount;
            this.deferredPassCount = deferredPassCount;
            this.compatibilityMode = compatibilityMode;
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** Get a program with fallback resolution. */
        public ProgramSource getProgram(String name) {
            return resolveProgram(this.programs, name, compatibilityMode);
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
        DeferredCompatibilityMode mode = compatibilityMode;
        String packName = packPath.getFileName().toString();

        try {
            if (Files.isDirectory(packPath)) {
                Path shadersDir = findShadersDirectory(packPath);
                if (shadersDir == null) {
                    System.err.println("[PAUC Shader] No shaders/ directory found in: " + packPath);
                    return null;
                }
                return loadFromShadersDirectory(packName, packPath, shadersDir, mode);
            }

            String lowerName = packName.toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(packPath) && lowerName.endsWith(".zip")) {
                try (FileSystem zipFileSystem = FileSystems.newFileSystem(packPath, (ClassLoader) null)) {
                    Path shadersDir = findShadersDirectory(zipFileSystem.getPath("/"));
                    if (shadersDir == null) {
                        System.err.println("[PAUC Shader] No shaders/ directory found in zip: " + packPath);
                        return null;
                    }
                    return loadFromShadersDirectory(packName, packPath, shadersDir, mode);
                }
            }
        } catch (IOException exception) {
            System.err.println("[PAUC Shader] Failed to open shaderpack '" + packPath + "': " + exception.getMessage());
            return null;
        }

        System.err.println("[PAUC Shader] Unsupported shaderpack path: " + packPath);
        return null;
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
            entries.filter(ShaderPackLoader::isShaderPackEntry)
                    .forEach(p -> packs.add(p.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("[PAUC Shader] Failed to list shaderpacks: " + e.getMessage());
        }

        return packs;
    }

    public static void setCompatibilityMode(DeferredCompatibilityMode mode) {
        compatibilityMode = mode == null ? DeferredCompatibilityMode.BALANCED : mode;
    }

    public static DeferredCompatibilityMode getCompatibilityMode() {
        return compatibilityMode;
    }

    // ---- Internal loading helpers ----

    private static ProgramSource resolveProgram(Map<String, ProgramSource> programs, String name, DeferredCompatibilityMode mode) {
        ProgramSource source = programs.get(name);
        if (source != null) {
            return source;
        }

        if (mode == DeferredCompatibilityMode.STRICT) {
            return null;
        }

        source = resolveFallbackProgram(programs, name);
        if (source != null) {
            return source;
        }

        if (mode == DeferredCompatibilityMode.FAST) {
            return resolveFastFallbackProgram(programs, name);
        }

        return null;
    }

    private static ProgramSource resolveFallbackProgram(Map<String, ProgramSource> programs, String name) {
        String fallback = FALLBACK_CHAIN.get(name);
        while (fallback != null) {
            ProgramSource source = programs.get(fallback);
            if (source != null) {
                return source;
            }
            fallback = FALLBACK_CHAIN.get(fallback);
        }
        return null;
    }

    private static ProgramSource resolveFastFallbackProgram(Map<String, ProgramSource> programs, String name) {
        if (!name.startsWith("gbuffers_")) {
            return null;
        }

        ProgramSource terrain = programs.get("gbuffers_terrain");
        if (terrain != null) {
            return terrain;
        }

        ProgramSource textured = programs.get("gbuffers_textured");
        if (textured != null) {
            return textured;
        }

        return programs.get("gbuffers_basic");
    }

    private static ProgramSource loadProgram(Path shadersDir, String programName) {
        Path programDir = resolveProgramDirectory(shadersDir, programName);
        if (programDir == null) {
            return null;
        }

        Path vshPath = programDir.resolve(programName + ".vsh");
        Path fshPath = programDir.resolve(programName + ".fsh");
        Path gshPath = programDir.resolve(programName + ".gsh");

        try {
            String vertexSource = readShaderSource(vshPath, shadersDir, ShaderStage.VERTEX);
            String fragmentSource = readShaderSource(fshPath, shadersDir, ShaderStage.FRAGMENT);
            String geometrySource = Files.exists(gshPath) ? readShaderSource(gshPath, shadersDir, ShaderStage.GEOMETRY) : null;

            return new ProgramSource(programName, vertexSource, fragmentSource, geometrySource);
        } catch (IOException e) {
            System.err.println("[PAUC Shader] Failed to load program '" + programName + "': " + e.getMessage());
            return null;
        }
    }

    private static Path resolveProgramDirectory(Path shadersDir, String programName) {
        if (shadersDir == null || programName == null || programName.isBlank()) {
            return null;
        }

        List<Path> candidates = new ArrayList<>();
        candidates.add(shadersDir);

        try (Stream<Path> entries = Files.list(shadersDir)) {
            entries
                    .filter(Files::isDirectory)
                    .forEach(candidates::add);
        } catch (IOException ignored) {
        }

        Path best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Path dir : candidates) {
            if (!Files.exists(dir.resolve(programName + ".vsh")) || !Files.exists(dir.resolve(programName + ".fsh"))) {
                continue;
            }
            int rank = programDirectoryRank(shadersDir, dir);
            if (rank < bestRank) {
                bestRank = rank;
                best = dir;
            }
        }

        return best;
    }

    private static int programDirectoryRank(Path shadersRoot, Path candidateDir) {
        if (candidateDir == null) {
            return 100;
        }
        if (candidateDir.equals(shadersRoot)) {
            return 0;
        }

        String name = candidateDir.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "world0", "world" -> 1;
            case "dimensions" -> 2;
            case "world-1", "world1" -> 3;
            default -> 5;
        };
    }

    /**
     * Read shader source with #include directive support.
     * Supports the standard /shaders/include/ convention.
     */
    private static String readShaderSource(Path filePath, Path shadersDir, ShaderStage stage) throws IOException {
        String source = Files.readString(filePath, StandardCharsets.UTF_8);
        String withIncludes = processIncludes(source, shadersDir, filePath.getParent(), 0);
        String withModeDefines = injectCompatibilityDefines(withIncludes);
        return adaptLegacyCoreProfileSyntax(withModeDefines, stage);
    }

    private static String processIncludes(String source, Path shadersDir, Path currentDir, int depth) throws IOException {
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
                    Path includeFile = resolveIncludePath(includePath, shadersDir, currentDir);
                    if (Files.exists(includeFile)) {
                        String includeSource = Files.readString(includeFile, StandardCharsets.UTF_8);
                        result.append(processIncludes(includeSource, shadersDir, includeFile.getParent(), depth + 1));
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

    private static Path resolveIncludePath(String includePath, Path shadersDir, Path currentDir) {
        String normalizedInclude = includePath.replace('\\', '/').trim();
        while (normalizedInclude.startsWith("/")) {
            normalizedInclude = normalizedInclude.substring(1);
        }
        if (normalizedInclude.startsWith("shaders/")) {
            normalizedInclude = normalizedInclude.substring("shaders/".length());
        }

        if (currentDir != null) {
            Path relativeCandidate = currentDir.resolve(normalizedInclude).normalize();
            if (Files.exists(relativeCandidate)) {
                return relativeCandidate;
            }
        }

        return shadersDir.resolve(normalizedInclude).normalize();
    }

    private static String injectCompatibilityDefines(String source) {
        DeferredCompatibilityMode mode = compatibilityMode;
        String defines = buildCompatibilityDefines(source, mode);

        int versionIndex = source.indexOf("#version");
        if (versionIndex < 0) {
            return defines + source;
        }

        int lineEnd = source.indexOf('\n', versionIndex);
        if (lineEnd < 0) {
            return source + defines;
        }

        return source.substring(0, lineEnd + 1)
                + defines
                + source.substring(lineEnd + 1);
    }

    private static String buildCompatibilityDefines(String source, DeferredCompatibilityMode mode) {
        StringBuilder defines = new StringBuilder();
        int version = extractShaderVersion(source);
        boolean needsGpuShader4 = version > 0 && version <= 130
                || containsShaderCall(source, "texelFetch2D")
                || containsToken(source, "uvec2")
                || containsToken(source, "uvec3")
                || containsToken(source, "uvec4")
                || containsToken(source, "uint")
                || containsToken(source, "usampler2D")
                || containsToken(source, "usampler2DShadow");
        boolean needsTextureLod = containsShaderCall(source, "texture2DLod")
                || containsShaderCall(source, "texture2DGradARB")
                || containsShaderCall(source, "shadow2DProjLod");

        // Keep extension directives immediately after #version for maximum driver compatibility.
        if (needsGpuShader4 && !containsExtensionDirective(source, "GL_EXT_gpu_shader4")) {
            defines.append("#extension GL_EXT_gpu_shader4 : enable\n");
        }
        if (needsTextureLod && !containsExtensionDirective(source, "GL_ARB_shader_texture_lod")) {
            defines.append("#extension GL_ARB_shader_texture_lod : enable\n");
        }

        defines.append("\n#define PAUC_DEFERRED_MODE ").append(mode.ordinal()).append('\n');
        defines.append("#define PAUC_DEFERRED_MODE_").append(mode.name()).append(" 1\n");
        defines.append("#ifndef MC_VERSION\n#define MC_VERSION 12001\n#endif\n");
        defines.append("#ifndef MC_GL_VERSION\n#define MC_GL_VERSION 330\n#endif\n");
        defines.append("#ifndef MC_GLSL_VERSION\n#define MC_GLSL_VERSION 330\n#endif\n");
        defines.append("#ifndef MC_RENDER_QUALITY\n#define MC_RENDER_QUALITY 1.0\n#endif\n");
        defines.append("#ifndef MC_HAND_DEPTH\n#define MC_HAND_DEPTH 0.125\n#endif\n");
        defines.append("#ifndef MC_NORMAL_MAP\n#define MC_NORMAL_MAP 1\n#endif\n");
        defines.append("#ifndef MC_SPECULAR_MAP\n#define MC_SPECULAR_MAP 1\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_TERRAIN_SOLID\n#define MC_RENDER_STAGE_TERRAIN_SOLID 0\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_TERRAIN_TRANSLUCENT\n#define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 1\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT\n#define MC_RENDER_STAGE_TERRAIN_CUTOUT 2\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED\n#define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 3\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_BLOCK_ENTITIES\n#define MC_RENDER_STAGE_BLOCK_ENTITIES 4\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_ENTITIES\n#define MC_RENDER_STAGE_ENTITIES 5\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_SUN\n#define MC_RENDER_STAGE_SUN 6\n#endif\n");
        defines.append("#ifndef MC_RENDER_STAGE_MOON\n#define MC_RENDER_STAGE_MOON 7\n#endif\n");
        appendPlatformDefines(defines);

        defines.append("#if __VERSION__ >= 130\n");
        defines.append("#ifndef texelFetch2D\n#define texelFetch2D(tex, coord, lod) texelFetch(tex, coord, lod)\n#endif\n");
        defines.append("#ifndef texture2DGradARB\n#define texture2DGradARB(tex, coord, dx, dy) textureGrad(tex, coord, dx, dy)\n#endif\n");
        defines.append("#ifndef texture2DLod\n#define texture2DLod(tex, coord, lod) textureLod(tex, coord, lod)\n#endif\n");
        defines.append("#ifndef texture2DProj\n#define texture2DProj(tex, coord) textureProj(tex, coord)\n#endif\n");
        defines.append("#endif\n");
        return defines.toString();
    }

    private static void appendPlatformDefines(StringBuilder defines) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            defines.append("#define MC_OS_WINDOWS 1\n");
        } else if (osName.contains("mac")) {
            defines.append("#define MC_OS_MAC 1\n");
        } else if (osName.contains("linux")) {
            defines.append("#define MC_OS_LINUX 1\n");
        } else {
            defines.append("#define MC_OS_OTHER 1\n");
        }
    }

    private static boolean containsToken(String source, String token) {
        if (source == null || source.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        return pattern.matcher(source).find();
    }

    private static boolean containsShaderCall(String source, String functionName) {
        if (source == null || source.isEmpty() || functionName == null || functionName.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(functionName) + "\\s*\\(");
        return pattern.matcher(source).find();
    }

    private static boolean containsExtensionDirective(String source, String extensionName) {
        if (source == null || extensionName == null || extensionName.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("(?m)^\\s*#\\s*extension\\s+" + Pattern.quote(extensionName) + "\\b");
        return pattern.matcher(source).find();
    }

    private static int extractShaderVersion(String source) {
        if (source == null) {
            return -1;
        }
        Matcher matcher = VERSION_DIRECTIVE_PATTERN.matcher(source);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String adaptLegacyCoreProfileSyntax(String source, ShaderStage stage) {
        if (source == null || source.isBlank()) {
            return source;
        }

        int version = extractShaderVersion(source);
        if (version > 0 && version < 130) {
            return source;
        }

        boolean hasLegacySyntax = source.contains("gl_MultiTexCoord")
                || source.contains("gl_Vertex")
                || source.contains("gl_FragData")
                || source.contains("gl_FragColor")
                || source.contains("gl_Fog")
                || source.contains("gl_FogFragCoord")
                || source.contains("gl_TextureMatrix")
                || source.contains("ftransform(")
                || source.contains("texture2D(")
                || source.contains("texture2DLod(")
                || source.contains("texture2DGradARB(")
                || source.contains("texture3D(")
                || source.contains("texture3DLod(")
                || source.contains("attribute ")
                || source.contains("varying ")
                || source.contains("vaPosition")
                || source.contains("vaNormal")
                || source.contains("modelViewMatrix")
                || source.contains("projectionMatrix")
                || source.contains("shadow2D(");

        if (!hasLegacySyntax) {
            return source;
        }

        String adapted = source;

        if (stage == ShaderStage.VERTEX) {
            adapted = adapted.replaceAll("\\battribute\\b", "in");
            adapted = adapted.replaceAll("\\bvarying\\b", "out");
        } else if (stage == ShaderStage.FRAGMENT) {
            adapted = adapted.replaceAll("\\bvarying\\b", "in");
        }

        adapted = adapted.replace("texture2DLod(", "textureLod(");
        adapted = adapted.replace("texture2DGradARB(", "textureGrad(");
        adapted = adapted.replace("texture2D(", "texture(");
        adapted = adapted.replace("texture2DProj(", "textureProj(");
        adapted = adapted.replace("texture3DLod(", "textureLod(");
        adapted = adapted.replace("texture3D(", "texture(");
        adapted = adapted.replace("shadow2DProjLod(", "textureProjLod(");
        adapted = adapted.replace("shadow2DLod(", "textureLod(");

        if (stage == ShaderStage.FRAGMENT) {
            for (int i = 0; i < 8; i++) {
                adapted = adapted.replaceAll("\\bgl_FragData\\s*\\[\\s*" + i + "\\s*\\]", "pauc_FragData" + i);
            }
            adapted = adapted.replaceAll("\\bgl_FragColor\\b", "pauc_FragData0");
        }

        return injectLegacyPrelude(adapted, stage, version);
    }

    private static String injectLegacyPrelude(String source, ShaderStage stage, int version) {
        if (version > 0 && version < 130) {
            return source;
        }

        String prelude = switch (stage) {
            case VERTEX -> LEGACY_VERTEX_PRELUDE;
            case FRAGMENT -> LEGACY_FRAGMENT_PRELUDE;
            default -> "";
        };

        if (prelude.isEmpty()) {
            return source;
        }

        int insertAt = findLegacyPreludeInsertionIndex(source);
        return source.substring(0, insertAt)
                + prelude
                + source.substring(insertAt);
    }

    private static int findLegacyPreludeInsertionIndex(String source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }

        int position = 0;
        int versionIndex = source.indexOf("#version");
        if (versionIndex >= 0) {
            int versionLineEnd = source.indexOf('\n', versionIndex);
            if (versionLineEnd >= 0) {
                position = versionLineEnd + 1;
            } else {
                return source.length();
            }
        }

        while (position < source.length()) {
            int lineEnd = source.indexOf('\n', position);
            if (lineEnd < 0) {
                break;
            }
            String line = source.substring(position, lineEnd).trim();
            if (line.isEmpty()
                    || line.startsWith("//")
                    || line.startsWith("#define")
                    || line.startsWith("#extension")
                    || line.startsWith("#pragma")
                    || line.startsWith("#line")) {
                position = lineEnd + 1;
                continue;
            }
            break;
        }
        return position;
    }

    private static boolean isShaderPackEntry(Path entryPath) {
        if (entryPath == null) {
            return false;
        }

        if (Files.isDirectory(entryPath)) {
            return findShadersDirectory(entryPath) != null;
        }

        String fileName = entryPath.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(entryPath) && fileName.endsWith(".zip");
    }

    private static Path findShadersDirectory(Path rootPath) {
        if (rootPath == null) {
            return null;
        }

        Path directShadersDir = rootPath.resolve("shaders");
        if (Files.isDirectory(directShadersDir)) {
            return directShadersDir;
        }

        try (Stream<Path> walk = Files.walk(rootPath, 3)) {
            Optional<Path> nested = walk
                    .filter(Files::isDirectory)
                    .filter(path -> "shaders".equalsIgnoreCase(path.getFileName().toString()))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .findFirst();
            return nested.orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private static ShaderPack loadFromShadersDirectory(
            String packName,
            Path packPath,
            Path shadersDir,
            DeferredCompatibilityMode mode
    ) {
        List<String> warnings = new ArrayList<>();

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
        int deferredLimit = mode == DeferredCompatibilityMode.FAST ? 4 : 16;
        for (int i = 0; i < deferredLimit; i++) {
            String name = i == 0 ? "deferred" : "deferred" + i;
            ProgramSource deferred = loadProgram(shadersDir, name);
            if (deferred != null) {
                programs.put(name, deferred);
                deferredCount = i + 1;
            }
        }

        // Load composite passes (composite, composite1-15)
        int compositeCount = 0;
        int compositeLimit = mode == DeferredCompatibilityMode.FAST ? 4 : 16;
        for (int i = 0; i < compositeLimit; i++) {
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

        boolean hasCoreGbuffer = programs.containsKey("gbuffers_terrain")
                || programs.containsKey("gbuffers_basic")
                || programs.containsKey("gbuffers_textured");
        if (!hasCoreGbuffer) {
            warnings.add("missing core gbuffer program");
        }
        if (finalPass == null) {
            warnings.add("missing final pass");
        }
        if (!hasShadow) {
            warnings.add("shadow program not present");
        }
        if (mode == DeferredCompatibilityMode.FAST) {
            if (Files.exists(shadersDir.resolve("deferred4.vsh")) || Files.exists(shadersDir.resolve("deferred4.fsh"))) {
                warnings.add("deferred passes truncated to fast limit");
            }
            if (Files.exists(shadersDir.resolve("composite4.vsh")) || Files.exists(shadersDir.resolve("composite4.fsh"))) {
                warnings.add("composite passes truncated to fast limit");
            }
        }

        if (mode == DeferredCompatibilityMode.STRICT && !hasCoreGbuffer) {
            System.err.println("[PAUC Shader] Strict mode rejected pack '" + packName
                    + "': missing core gbuffer programs");
            return null;
        }

        System.out.println("[PAUC Shader] Loaded shaderpack '" + packName + "' (mode=" + mode.name().toLowerCase(Locale.ROOT) + "): "
                + programs.size() + " programs, shadow=" + hasShadow
                + ", deferred=" + deferredCount + ", composite=" + compositeCount);

        return new ShaderPack(packName, packPath, properties, programs,
                hasShadow, compositeCount, deferredCount, mode, warnings);
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
