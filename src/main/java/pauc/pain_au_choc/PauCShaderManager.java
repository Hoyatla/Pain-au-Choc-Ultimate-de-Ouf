package pauc.pain_au_choc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class PauCShaderManager {
    private static final int GL_READ_FRAMEBUFFER = 36008;
    private static final int GL_DRAW_FRAMEBUFFER = 36009;
    private static final int GL_COLOR_BUFFER_BIT = 16384;
    private static final int GL_LINEAR = 9729;

    private static final String EXTERNAL_NAMESPACE = "pauc_external";
    private static final String DEFAULT_SHADER_KEY = UpscaleShaderMode.LINEAR.key;
    private static final Path SHADER_FOLDER = FMLPaths.GAMEDIR.get().resolve("pauc_ultimate_de_ouf_shaders");
    private static final Path EXAMPLES_FOLDER = SHADER_FOLDER.resolve("examples");

    private static final String README_CONTENT = """
            Internal Pain au Choc ultimate de Ouf render resources.
            Generated files in this folder are managed automatically by the mod.
            """;
    static final String PASSTHROUGH_VERTEX_SOURCE = """
            #version 150

            in vec3 Position;
            in vec2 UV;
            in vec4 Color;

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;

            out vec2 texCoord;
            out vec4 vertexColor;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                texCoord = UV;
                vertexColor = Color;
            }
            """;
    static final String PHOTON_FXAA_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform vec2 SourceSize;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            const int max_iterations = 12;
            const float[12] quality = float[12](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

            const float edge_threshold_min = 0.0312;
            const float edge_threshold_max = 0.125;
            const float subpixel_quality = 0.75;

            float clamp01(float value) {
                return clamp(value, 0.0, 1.0);
            }

            float rcp(float value) {
                return 1.0 / value;
            }

            float get_luma(vec3 rgb) {
                const vec3 luminance_weights = vec3(0.2126, 0.7152, 0.0722);
                return sqrt(dot(rgb, luminance_weights));
            }

            float min_of(float a, float b, float c, float d, float e) {
                return min(a, min(b, min(c, min(d, e))));
            }

            float max_of(float a, float b, float c, float d, float e) {
                return max(a, max(b, max(c, max(d, e))));
            }

            ivec2 clamp_texel(ivec2 texel) {
                return clamp(texel, ivec2(0), max(ivec2(0), ivec2(SourceSize) - ivec2(1)));
            }

            vec4 fetch_color(ivec2 texel) {
                return texelFetch(DiffuseSampler, clamp_texel(texel), 0);
            }

            void main() {
                vec2 view_pixel_size = vec2(1.0) / SourceSize;
                ivec2 texel = ivec2(texCoord * SourceSize);

                vec3 a = fetch_color(texel + ivec2(-1,  1)).rgb;
                vec3 b = fetch_color(texel + ivec2( 0,  1)).rgb;
                vec3 c = fetch_color(texel + ivec2( 1,  1)).rgb;
                vec4 d_sample = fetch_color(texel + ivec2(-1,  0));
                vec4 e_sample = fetch_color(texel);
                vec4 f_sample = fetch_color(texel + ivec2( 1,  0));
                vec3 g = fetch_color(texel + ivec2(-1, -1)).rgb;
                vec3 h = fetch_color(texel + ivec2( 0, -1)).rgb;
                vec3 i = fetch_color(texel + ivec2( 1, -1)).rgb;

                vec3 d = d_sample.rgb;
                vec3 e = e_sample.rgb;
                vec3 f = f_sample.rgb;

                float luma = get_luma(e);
                float luma_u = get_luma(b);
                float luma_l = get_luma(d);
                float luma_r = get_luma(f);
                float luma_d = get_luma(h);

                float luma_min = min_of(luma, luma_d, luma_u, luma_l, luma_r);
                float luma_max = max_of(luma, luma_d, luma_u, luma_l, luma_r);
                float luma_range = luma_max - luma_min;

                if (luma_range < max(edge_threshold_min, luma_max * edge_threshold_max)) {
                    fragColor = e_sample * vertexColor * ColorModulator;
                    return;
                }

                float luma_ul = get_luma(a);
                float luma_ur = get_luma(c);
                float luma_dl = get_luma(g);
                float luma_dr = get_luma(i);

                float luma_horizontal = luma_d + luma_u;
                float luma_vertical = luma_l + luma_r;

                float luma_left_corners = luma_dl + luma_ul;
                float luma_down_corners = luma_dl + luma_dr;
                float luma_right_corners = luma_dr + luma_ur;
                float luma_up_corners = luma_ul + luma_ur;

                float edge_horizontal = abs(-2.0 * luma_l + luma_left_corners) + abs(-2.0 * luma + luma_vertical) * 2.0 + abs(-2.0 * luma_r + luma_right_corners);
                float edge_vertical = abs(-2.0 * luma_u + luma_up_corners) + abs(-2.0 * luma + luma_horizontal) * 2.0 + abs(-2.0 * luma_d + luma_down_corners);

                bool is_horizontal = edge_horizontal >= edge_vertical;

                float luma1 = is_horizontal ? luma_d : luma_l;
                float luma2 = is_horizontal ? luma_u : luma_r;

                float gradient1 = luma1 - luma;
                float gradient2 = luma2 - luma;
                bool is_1_steepest = abs(gradient1) >= abs(gradient2);
                float gradient_scaled = 0.25 * max(abs(gradient1), abs(gradient2));

                float step_length = is_horizontal ? view_pixel_size.y : view_pixel_size.x;
                float luma_local_average;
                if (is_1_steepest) {
                    step_length = -step_length;
                    luma_local_average = 0.5 * (luma1 + luma);
                } else {
                    luma_local_average = 0.5 * (luma2 + luma);
                }

                vec2 current_uv = texCoord;
                if (is_horizontal) {
                    current_uv.y += step_length * 0.5;
                } else {
                    current_uv.x += step_length * 0.5;
                }

                vec2 offset = is_horizontal ? vec2(view_pixel_size.x, 0.0) : vec2(0.0, view_pixel_size.y);
                vec2 uv1 = current_uv - offset;
                vec2 uv2 = current_uv + offset;

                float luma_end_1 = get_luma(textureLod(DiffuseSampler, uv1, 0).rgb);
                float luma_end_2 = get_luma(textureLod(DiffuseSampler, uv2, 0).rgb);
                luma_end_1 -= luma_local_average;
                luma_end_2 -= luma_local_average;

                bool reached1 = abs(luma_end_1) >= gradient_scaled;
                bool reached2 = abs(luma_end_2) >= gradient_scaled;
                bool reached_both = reached1 && reached2;

                if (!reached1) {
                    uv1 -= offset;
                }
                if (!reached2) {
                    uv2 += offset;
                }

                if (!reached_both) {
                    for (int index = 2; index < max_iterations; ++index) {
                        if (!reached1) {
                            luma_end_1 = get_luma(textureLod(DiffuseSampler, uv1, 0).rgb) - luma_local_average;
                        }
                        if (!reached2) {
                            luma_end_2 = get_luma(textureLod(DiffuseSampler, uv2, 0).rgb) - luma_local_average;
                        }

                        reached1 = abs(luma_end_1) >= gradient_scaled;
                        reached2 = abs(luma_end_2) >= gradient_scaled;
                        reached_both = reached1 && reached2;

                        if (!reached1) {
                            uv1 -= offset * quality[index];
                        }
                        if (!reached2) {
                            uv2 += offset * quality[index];
                        }

                        if (reached_both) {
                            break;
                        }
                    }
                }

                float distance1 = is_horizontal ? (texCoord.x - uv1.x) : (texCoord.y - uv1.y);
                float distance2 = is_horizontal ? (uv2.x - texCoord.x) : (uv2.y - texCoord.y);
                bool is_direction_1 = distance1 < distance2;
                float distance_final = min(distance1, distance2);
                float edge_thickness = max(distance1 + distance2, 1.0e-5);
                float pixel_offset = -distance_final / edge_thickness + 0.5;

                bool is_luma_center_smaller = luma < luma_local_average;
                bool correct_variation = ((is_direction_1 ? luma_end_1 : luma_end_2) < 0.0) != is_luma_center_smaller;
                float final_offset = correct_variation ? pixel_offset : 0.0;

                float luma_average = rcp(12.0) * (2.0 * (luma_horizontal + luma_vertical) + luma_left_corners + luma_right_corners);
                float sub_pixel_offset_1 = clamp01(abs(luma_average - luma) / max(luma_range, 1.0e-5));
                float sub_pixel_offset_2 = (-2.0 * sub_pixel_offset_1 + 3.0) * sub_pixel_offset_1 * sub_pixel_offset_1;
                float sub_pixel_offset_final = sub_pixel_offset_2 * sub_pixel_offset_2 * subpixel_quality;

                final_offset = max(final_offset, sub_pixel_offset_final);

                vec2 final_uv = texCoord;
                if (is_horizontal) {
                    final_uv.y += final_offset * step_length;
                } else {
                    final_uv.x += final_offset * step_length;
                }

                vec4 final_color = textureLod(DiffuseSampler, final_uv, 0);
                fragColor = final_color * vertexColor * ColorModulator;
            }
            """;
    private static final String PHOTON_CAS_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform vec2 SourceSize;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            vec3 min_of(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
                return min(a, min(b, min(c, min(d, e))));
            }

            vec3 max_of(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
                return max(a, max(b, max(c, max(d, e))));
            }

            ivec2 clamp_texel(ivec2 texel) {
                return clamp(texel, ivec2(0), max(ivec2(0), ivec2(SourceSize) - ivec2(1)));
            }

            vec3 fetch_rgb(ivec2 texel) {
                return texelFetch(DiffuseSampler, clamp_texel(texel), 0).rgb;
            }

            void main() {
                ivec2 texel = ivec2(texCoord * SourceSize);

                vec3 a = fetch_rgb(texel + ivec2(-1, -1));
                vec3 b = fetch_rgb(texel + ivec2( 0, -1));
                vec3 c = fetch_rgb(texel + ivec2( 1, -1));
                vec3 d = fetch_rgb(texel + ivec2(-1,  0));
                vec4 e_sample = texelFetch(DiffuseSampler, clamp_texel(texel), 0);
                vec3 e = e_sample.rgb;
                vec3 f = fetch_rgb(texel + ivec2( 1,  0));
                vec3 g = fetch_rgb(texel + ivec2(-1,  1));
                vec3 h = fetch_rgb(texel + ivec2( 0,  1));
                vec3 i = fetch_rgb(texel + ivec2( 1,  1));

                vec3 min_color = min_of(d, e, f, b, h);
                min_color += min_of(min_color, a, c, g, i);

                vec3 max_color = max_of(d, e, f, b, h);
                max_color += max_of(max_color, a, c, g, i);

                vec3 peak = max(max_color, vec3(1.0e-5));
                vec3 weight = clamp(min(min_color, 2.0 - max_color) / peak, 0.0, 1.0);
                weight = 1.0 - (1.0 - weight) * (1.0 - weight);
                weight *= -1.0 / 6.5;

                vec3 filtered = ((b + d + f + h) * weight + e) / (1.0 + 4.0 * weight);
                filtered = clamp(filtered, 0.0, 1.0);

                fragColor = vec4(filtered, e_sample.a) * vertexColor * ColorModulator;
            }
            """;
    static final String ELITE_FXAA_FRAGMENT_SOURCE = PHOTON_FXAA_FRAGMENT_SOURCE;
    private static final String ELITE_SMART_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform vec2 SourceSize;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            float luminance(vec3 color) {
                return dot(color, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec2 texel = vec2(1.0) / SourceSize;
                vec4 center = texture(DiffuseSampler, texCoord);
                vec4 north = texture(DiffuseSampler, texCoord + vec2(0.0, -texel.y));
                vec4 south = texture(DiffuseSampler, texCoord + vec2(0.0, texel.y));
                vec4 east = texture(DiffuseSampler, texCoord + vec2(texel.x, 0.0));
                vec4 west = texture(DiffuseSampler, texCoord + vec2(-texel.x, 0.0));

                vec3 neighborhood = 0.25 * (north.rgb + south.rgb + east.rgb + west.rgb);
                float edge = luminance(abs(center.rgb - neighborhood));
                float sharpenAmount = clamp(edge * 3.5, 0.08, 0.65);
                vec3 sharpened = center.rgb * (1.0 + sharpenAmount) - neighborhood * sharpenAmount;

                fragColor = vec4(clamp(sharpened, 0.0, 1.0), center.a) * vertexColor * ColorModulator;
            }
            """;
    private static final String DERCODE_BICUBIC_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform vec2 SourceSize;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            float catmullRom(float x) {
                float ax = abs(x);
                float ax2 = ax * ax;
                float ax3 = ax2 * ax;
                if (ax <= 1.0) {
                    return 1.5 * ax3 - 2.5 * ax2 + 1.0;
                }
                if (ax < 2.0) {
                    return -0.5 * ax3 + 2.5 * ax2 - 4.0 * ax + 2.0;
                }
                return 0.0;
            }

            void main() {
                vec2 samplePos = texCoord * SourceSize - vec2(0.5);
                vec2 base = floor(samplePos);
                vec2 fraction = samplePos - base;
                vec2 texel = vec2(1.0) / SourceSize;

                vec4 color = vec4(0.0);
                float totalWeight = 0.0;

                for (int y = -1; y <= 2; ++y) {
                    float weightY = catmullRom(float(y) - fraction.y);
                    for (int x = -1; x <= 2; ++x) {
                        float weightX = catmullRom(fraction.x - float(x));
                        float weight = weightX * weightY;
                        vec2 uv = (base + vec2(float(x), float(y)) + vec2(0.5)) * texel;
                        color += texture(DiffuseSampler, uv) * weight;
                        totalWeight += weight;
                    }
                }

                vec4 filtered = totalWeight != 0.0 ? color / totalWeight : texture(DiffuseSampler, texCoord);
                fragColor = filtered * vertexColor * ColorModulator;
            }
            """;
    private static final String DERCODE_CAS_FRAGMENT_SOURCE = PHOTON_CAS_FRAGMENT_SOURCE;

    private static final EnumMap<UpscaleShaderMode, ShaderInstance> INTERNAL_SHADERS = new EnumMap<>(UpscaleShaderMode.class);
    private static final LinkedHashMap<String, ExternalShaderEntry> EXTERNAL_SHADERS = new LinkedHashMap<>();

    private static String activeShaderKey = DEFAULT_SHADER_KEY;
    private static boolean loggedFallbackWarning;

    private PauCShaderManager() {
    }

    public static void initializeShaderFolder() {
        try {
            Files.createDirectories(EXAMPLES_FOLDER);
            writeIfMissing(SHADER_FOLDER.resolve("README.txt"), README_CONTENT);
            for (UpscaleShaderMode mode : UpscaleShaderMode.values()) {
                writeIfMissing(EXAMPLES_FOLDER.resolve(mode.exampleFileName), mode.exampleSource);
            }
            PauCShaderPackManager.initializePackFolder();
        } catch (IOException exception) {
            Pain_au_Choc.LOGGER.warn("Failed to prepare shader folder {}", SHADER_FOLDER, exception);
        }
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        INTERNAL_SHADERS.clear();
        loggedFallbackWarning = false;

        for (UpscaleShaderMode mode : UpscaleShaderMode.values()) {
            try {
                event.registerShader(
                        new ShaderInstance(event.getResourceProvider(), mode.shaderId, DefaultVertexFormat.POSITION_TEX_COLOR),
                        shader -> {
                            INTERNAL_SHADERS.put(mode, shader);
                            Pain_au_Choc.LOGGER.info("PauC internal shader loaded: {}", shader.getName());
                        }
                );
            } catch (IOException exception) {
                Pain_au_Choc.LOGGER.error("Failed to register PauC internal shader {}", mode.shaderId, exception);
            }
        }

        reloadExternalShaders();
    }

    public static void copyColor(RenderTarget source, RenderTarget target) {
        if (source == null || target == null) {
            return;
        }

        RenderSystem.assertOnRenderThreadOrInit();
        ensureValidActiveShader();
        if (PauCShaderPackManager.isShaderPackKey(activeShaderKey) && PauCShaderPackManager.renderShaderPack(activeShaderKey, source, target)) {
            return;
        }

        ShaderInstance shader = resolveActiveShader();
        if (shader == null) {
            blitFallback(source, target);
            return;
        }

        renderWithShader(source, target, shader);
    }

    public static void cycleShaderMode() {
        List<String> shaderKeys = getAvailableShaderKeys();
        if (shaderKeys.isEmpty()) {
            activeShaderKey = DEFAULT_SHADER_KEY;
            showShaderModeToast();
            return;
        }

        int index = shaderKeys.indexOf(activeShaderKey);
        if (index < 0) {
            index = 0;
        }

        activeShaderKey = shaderKeys.get((index + 1) % shaderKeys.size());
        Pain_au_Choc.LOGGER.info("PauC shader={}", getActiveShaderLabel());
        showShaderModeToast();
    }

    public static void setActiveShaderKey(String shaderKey) {
        if (shaderKey == null || shaderKey.isBlank()) {
            activeShaderKey = DEFAULT_SHADER_KEY;
            return;
        }

        activeShaderKey = shaderKey;
        ensureValidActiveShader();
    }

    public static String getActiveShaderKey() {
        ensureValidActiveShader();
        return activeShaderKey;
    }

    public static String getActiveShaderLabel() {
        ensureValidActiveShader();

        if (PauCShaderPackManager.isShaderPackKey(activeShaderKey)) {
            String label = PauCShaderPackManager.getShaderPackLabel(activeShaderKey);
            if (label != null) {
                return label;
            }
        }

        if (activeShaderKey.startsWith("external:")) {
            ExternalShaderEntry entry = EXTERNAL_SHADERS.get(activeShaderKey);
            if (entry != null) {
                return entry.label;
            }
        }

        UpscaleShaderMode mode = UpscaleShaderMode.fromKey(activeShaderKey);
        return mode == null ? UpscaleShaderMode.LINEAR.label : mode.label;
    }

    public static int getAvailableShaderCount() {
        return getAvailableShaderKeys().size();
    }

    public static int getExternalShaderCount() {
        return EXTERNAL_SHADERS.size() + PauCShaderPackManager.getShaderPackCount();
    }

    public static int getExternalShaderPackCount() {
        return PauCShaderPackManager.getShaderPackCount();
    }

    public static String getDefaultShaderKey() {
        return DEFAULT_SHADER_KEY;
    }

    public static boolean shouldProcessAtNativeScale() {
        ensureValidActiveShader();
        if (PauCShaderPackManager.isShaderPackKey(activeShaderKey)) {
            return PauCShaderPackManager.shouldProcessAtNativeScale(activeShaderKey);
        }
        if (activeShaderKey.startsWith("external:")) {
            ExternalShaderEntry entry = EXTERNAL_SHADERS.get(activeShaderKey);
            return entry != null && entry.nativePass;
        }

        UpscaleShaderMode mode = UpscaleShaderMode.fromKey(activeShaderKey);
        if (mode == null) {
            mode = UpscaleShaderMode.LINEAR;
        }
        return mode.nativePass;
    }

    public static void reloadExternalShaders() {
        initializeShaderFolder();
        if (!RenderSystem.isOnRenderThread() && !RenderSystem.isInInitPhase()) {
            RenderSystem.recordRenderCall(PauCShaderManager::reloadExternalShadersInternal);
            return;
        }

        reloadExternalShadersInternal();
    }

    public static void openShaderFolder() {
        initializeShaderFolder();
        Util.getPlatform().openFile(SHADER_FOLDER.toFile());
    }

    public static void releaseTransientTargets() {
        PauCShaderPackManager.releaseTransientTargets();
    }

    private static void reloadExternalShadersInternal() {
        closeExternalShaders();
        Set<String> reservedNames = new HashSet<>();

        if (!Files.isDirectory(SHADER_FOLDER)) {
            ensureValidActiveShader();
            return;
        }

        try (Stream<Path> files = Files.list(SHADER_FOLDER)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".fsh"))
                    .sorted()
                    .forEach(path -> loadExternalShader(path, reservedNames));
        } catch (IOException exception) {
            Pain_au_Choc.LOGGER.error("Failed to scan PauC shader folder {}", SHADER_FOLDER, exception);
        }

        PauCShaderPackManager.reloadExternalShaderPacks();

        ensureValidActiveShader();
        Pain_au_Choc.LOGGER.info(
                "PauC external shaders loaded={}, shaderpacks={}",
                EXTERNAL_SHADERS.size(),
                PauCShaderPackManager.getShaderPackCount()
        );
    }

    private static void loadExternalShader(Path fragmentPath, Set<String> reservedNames) {
        String fileName = fragmentPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - 4);
        String shaderPath = makeUniqueShaderPath(sanitizeShaderPath(baseName), reservedNames);
        String shaderKey = "external:" + shaderPath;

        try {
            ShaderInstance shader = new ShaderInstance(
                    createExternalResourceProvider(shaderPath, fragmentPath),
                    ResourceLocation.fromNamespaceAndPath(EXTERNAL_NAMESPACE, shaderPath),
                    DefaultVertexFormat.POSITION_TEX_COLOR
            );
            EXTERNAL_SHADERS.put(shaderKey, new ExternalShaderEntry(shaderKey, baseName, shader, true));
            Pain_au_Choc.LOGGER.info("PauC external shader loaded: {}", fragmentPath.getFileName());
        } catch (Exception exception) {
            Pain_au_Choc.LOGGER.error("Failed to load external PauC shader {}", fragmentPath, exception);
        }
    }

    private static ResourceProvider createExternalResourceProvider(String shaderPath, Path fragmentPath) {
        Map<ResourceLocation, Resource> resources = new HashMap<>();
        PackResources packResources = new ExternalPackResources("pauc_external/" + shaderPath);

        addGeneratedResources(resources, packResources, shaderPath, null);
        addFileResource(
                resources,
                packResources,
                ResourceLocation.fromNamespaceAndPath(EXTERNAL_NAMESPACE, "shaders/core/" + shaderPath + ".fsh"),
                fragmentPath
        );

        return ResourceProvider.fromMap(resources);
    }

    private static ResourceProvider createGeneratedExternalResourceProvider(String shaderPath, String fragmentSource) {
        Map<ResourceLocation, Resource> resources = new HashMap<>();
        PackResources packResources = new ExternalPackResources("pauc_external/" + shaderPath);
        addGeneratedResources(resources, packResources, shaderPath, fragmentSource);
        return ResourceProvider.fromMap(resources);
    }

    private static void addGeneratedResources(Map<ResourceLocation, Resource> resources, PackResources packResources, String shaderPath, @Nullable String fragmentSource) {
        addTextResource(
                resources,
                packResources,
                ResourceLocation.fromNamespaceAndPath(EXTERNAL_NAMESPACE, "shaders/core/" + shaderPath + ".json"),
                buildExternalShaderJson(shaderPath)
        );
        addTextResource(
                resources,
                packResources,
                ResourceLocation.fromNamespaceAndPath(EXTERNAL_NAMESPACE, "shaders/core/upscale_passthrough.vsh"),
                PASSTHROUGH_VERTEX_SOURCE
        );
        if (fragmentSource != null) {
            addTextResource(
                    resources,
                    packResources,
                    ResourceLocation.fromNamespaceAndPath(EXTERNAL_NAMESPACE, "shaders/core/" + shaderPath + ".fsh"),
                    fragmentSource
            );
        }
    }

    private static String buildExternalShaderJson(String shaderPath) {
        return String.format(
                Locale.ROOT,
                """
                        {
                          "vertex": "%s:upscale_passthrough",
                          "fragment": "%s:%s",
                          "attributes": [
                            "Position",
                            "UV",
                            "Color"
                          ],
                          "samplers": [
                            { "name": "DiffuseSampler" }
                          ],
                          "uniforms": [
                            { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                            { "name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] },
                            { "name": "ColorModulator", "type": "float", "count": 4, "values": [ 1.0, 1.0, 1.0, 1.0 ] },
                            { "name": "SourceSize", "type": "float", "count": 2, "values": [ 1.0, 1.0 ] }
                          ]
                        }
                        """,
                EXTERNAL_NAMESPACE,
                EXTERNAL_NAMESPACE,
                shaderPath
        );
    }

    private static void addTextResource(Map<ResourceLocation, Resource> resources, PackResources packResources, ResourceLocation location, String contents) {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        resources.put(location, new Resource(packResources, () -> new ByteArrayInputStream(bytes)));
    }

    private static void addFileResource(Map<ResourceLocation, Resource> resources, PackResources packResources, ResourceLocation location, Path file) {
        resources.put(location, new Resource(packResources, () -> Files.newInputStream(file)));
    }

    private static void closeExternalShaders() {
        for (ExternalShaderEntry entry : EXTERNAL_SHADERS.values()) {
            entry.shader.close();
        }
        EXTERNAL_SHADERS.clear();
        PauCShaderPackManager.closeExternalShaderPacks();
    }

    private static void ensureValidActiveShader() {
        List<String> shaderKeys = getAvailableShaderKeys();
        if (shaderKeys.isEmpty()) {
            activeShaderKey = DEFAULT_SHADER_KEY;
            return;
        }

        if (!shaderKeys.contains(activeShaderKey)) {
            activeShaderKey = shaderKeys.get(0);
        }
    }

    private static ShaderInstance resolveActiveShader() {
        ensureValidActiveShader();
        ExternalShaderEntry activeExternal = getActiveExternalShader();
        if (activeExternal != null) {
            return activeExternal.shader;
        }

        UpscaleShaderMode mode = UpscaleShaderMode.fromKey(activeShaderKey);
        if (mode == null) {
            mode = UpscaleShaderMode.LINEAR;
        }

        return INTERNAL_SHADERS.get(mode);
    }

    private static List<String> getAvailableShaderKeys() {
        List<String> shaderKeys = new ArrayList<>();
        for (UpscaleShaderMode mode : UpscaleShaderMode.values()) {
            shaderKeys.add(mode.key);
        }
        shaderKeys.addAll(EXTERNAL_SHADERS.keySet());
        shaderKeys.addAll(PauCShaderPackManager.getShaderPackKeys());
        return shaderKeys;
    }

    @Nullable
    private static ExternalShaderEntry getActiveExternalShader() {
        if (!activeShaderKey.startsWith("external:")) {
            return null;
        }

        return EXTERNAL_SHADERS.get(activeShaderKey);
    }

    private static void renderWithShader(RenderTarget source, RenderTarget target, ShaderInstance shader) {
        target.bindWrite(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, target.viewWidth, target.viewHeight);
        RenderSystem.disableBlend();

        shader.setSampler("DiffuseSampler", source);
        Matrix4f projectionMatrix = new Matrix4f().setOrtho(0.0F, (float) target.viewWidth, (float) target.viewHeight, 0.0F, 1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(new Matrix4f().translation(0.0F, 0.0F, -2000.0F));
        }

        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projectionMatrix);
        }

        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, 1.0F);
        }

        if (shader.getUniform("SourceSize") != null) {
            shader.getUniform("SourceSize").set((float) source.viewWidth, (float) source.viewHeight);
        }

        if (shader.getUniform("RcasStrength") != null) {
            float strength = PauCClient.isAdvancedSharpeningActive()
                    ? (float) PauCClient.getAdvancedSharpeningStrength()
                    : 0.0F;
            shader.getUniform("RcasStrength").set(strength);
        }

        shader.apply();

        float targetWidth = target.viewWidth;
        float targetHeight = target.viewHeight;
        float uMax = (float) source.viewWidth / (float) source.width;
        float vMax = (float) source.viewHeight / (float) source.height;
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(0.0D, targetHeight, 0.0D).uv(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(targetWidth, targetHeight, 0.0D).uv(uMax, 0.0F).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(targetWidth, 0.0D, 0.0D).uv(uMax, vMax).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(0.0D, 0.0D, 0.0D).uv(0.0F, vMax).color(255, 255, 255, 255).endVertex();
        BufferUploader.draw(bufferBuilder.end());

        shader.clear();
        GlStateManager._depthMask(true);
        GlStateManager._enableDepthTest();
    }

    private static void blitFallback(RenderTarget source, RenderTarget target) {
        if (!loggedFallbackWarning) {
            loggedFallbackWarning = true;
            Pain_au_Choc.LOGGER.warn("PauC shader unavailable, falling back to framebuffer blit.");
        }

        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(
                0,
                0,
                source.viewWidth,
                source.viewHeight,
                0,
                0,
                target.viewWidth,
                target.viewHeight,
                GL_COLOR_BUFFER_BIT,
                GL_LINEAR
        );
    }

    private static void showShaderModeToast() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Pain au Choc ultimate de Ouf mode: " + getActiveShaderLabel()), true);
        }
    }

    private static void writeIfMissing(Path path, String contents) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        }
    }

    private static String sanitizeShaderPath(String input) {
        String sanitized = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (sanitized.isBlank()) {
            return "custom_shader";
        }
        return sanitized;
    }

    private static String makeUniqueShaderPath(String baseName, Set<String> reservedNames) {
        String uniqueName = baseName;
        int suffix = 2;
        while (!reservedNames.add(uniqueName)) {
            uniqueName = baseName + "_" + suffix;
            suffix++;
        }
        return uniqueName;
    }

    private static String stripExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return fileName;
        }

        return fileName.substring(0, lastDot);
    }

    private record ExternalShaderEntry(
            String key,
            String label,
            ShaderInstance shader,
            boolean nativePass
    ) {
    }

    private enum UpscaleShaderMode {
        LINEAR("builtin:linear", "Linear", "upscale_blit", "linear_template.fsh", false, """
                #version 150

                uniform sampler2D DiffuseSampler;
                uniform vec4 ColorModulator;

                in vec2 texCoord;
                in vec4 vertexColor;

                out vec4 fragColor;

                void main() {
                    vec4 color = texture(DiffuseSampler, texCoord) * vertexColor;
                    fragColor = color * ColorModulator;
                }
                """),
        NEAREST("builtin:nearest", "Nearest", "upscale_nearest", "nearest_template.fsh", false, """
                #version 150

                uniform sampler2D DiffuseSampler;
                uniform vec4 ColorModulator;
                uniform vec2 SourceSize;

                in vec2 texCoord;
                in vec4 vertexColor;

                out vec4 fragColor;

                void main() {
                    vec2 texel = vec2(1.0) / SourceSize;
                    vec2 snappedUv = (floor(texCoord * SourceSize) + vec2(0.5)) * texel;
                    vec4 color = texture(DiffuseSampler, snappedUv) * vertexColor;
                    fragColor = color * ColorModulator;
                }
                """),
        SHARP("builtin:sharp", "Sharp", "upscale_sharp", "sharp_template.fsh", true, """
                #version 150

                uniform sampler2D DiffuseSampler;
                uniform vec4 ColorModulator;
                uniform vec2 SourceSize;
                uniform float RcasStrength;

                in vec2 texCoord;
                in vec4 vertexColor;

                out vec4 fragColor;

                vec3 safe_div(vec3 numerator, vec3 denominator) {
                    return numerator / max(denominator, vec3(1.0e-5));
                }

                void main() {
                    vec2 texel = vec2(1.0) / SourceSize;
                    vec3 b = texture(DiffuseSampler, texCoord + vec2(0.0, -texel.y)).rgb;
                    vec3 d = texture(DiffuseSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
                    vec4 e_sample = texture(DiffuseSampler, texCoord);
                    vec3 e = e_sample.rgb;
                    vec3 f = texture(DiffuseSampler, texCoord + vec2(texel.x, 0.0)).rgb;
                    vec3 h = texture(DiffuseSampler, texCoord + vec2(0.0, texel.y)).rgb;

                    vec3 min_ring = min(min(b, d), min(f, h));
                    vec3 max_ring = max(max(b, d), max(f, h));

                    vec3 min_luma = min(min_ring, e);
                    vec3 max_luma = max(max_ring, e);
                    vec3 amplitude = clamp(min(min_luma, 1.0 - max_luma) * safe_div(vec3(1.0), max_luma), 0.0, 1.0);

                    float sharpness = clamp(RcasStrength, 0.0, 1.0);
                    vec3 weight = -amplitude * (0.20 + sharpness * 0.60);
                    vec3 sharpened = ((b + d + f + h) * weight + e) / (1.0 + 4.0 * weight);
                    sharpened = clamp(sharpened, 0.0, 1.0);

                    fragColor = vec4(sharpened, e_sample.a) * vertexColor * ColorModulator;
                }
                """);

        private final String key;
        private final String label;
        private final ResourceLocation shaderId;
        private final String exampleFileName;
        private final boolean nativePass;
        private final String exampleSource;

        UpscaleShaderMode(String key, String label, String shaderPath, String exampleFileName, boolean nativePass, String exampleSource) {
            this.key = key;
            this.label = label;
            this.shaderId = ResourceLocation.fromNamespaceAndPath(Pain_au_Choc.MOD_ID, shaderPath);
            this.exampleFileName = exampleFileName;
            this.nativePass = nativePass;
            this.exampleSource = exampleSource;
        }

        @Nullable
        private static UpscaleShaderMode fromKey(String key) {
            for (UpscaleShaderMode mode : values()) {
                if (mode.key.equals(key)) {
                    return mode;
                }
            }

            return null;
        }
    }

    private static final class ExternalPackResources implements PackResources {
        private final String packId;

        private ExternalPackResources(String packId) {
            this.packId = packId;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
            return null;
        }

        @Override
        public void listResources(PackType packType, String namespace, String path, ResourceOutput output) {
        }

        @Override
        public Set<String> getNamespaces(PackType packType) {
            return Set.of(EXTERNAL_NAMESPACE);
        }

        @Override
        public @Nullable <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
            return null;
        }

        @Override
        public String packId() {
            return this.packId;
        }

        @Override
        public void close() {
        }
    }
}


