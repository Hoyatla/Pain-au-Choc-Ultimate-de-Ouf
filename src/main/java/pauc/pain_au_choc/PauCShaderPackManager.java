package pauc.pain_au_choc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PauCShaderPackManager {
    private static final String PACK_NAMESPACE = "pauc_shaderpack";
    private static final String MANIFEST_FILE_NAME = "pauc_shaderpack.json";
    private static final Path SHADER_ROOT = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("pauc_ultimate_de_ouf_shaders");
    private static final Path PACKS_FOLDER = SHADER_ROOT.resolve("packs");
    private static final Path PACK_EXAMPLES_FOLDER = PACKS_FOLDER.resolve("examples");
    private static final Path COMPETITIVE_PACK_FOLDER = PACKS_FOLDER.resolve("competitive_fxaa");
    private static final Path CINEMATIC_PACK_FOLDER = PACKS_FOLDER.resolve("cinematic_light");

    private static final String PACKS_README = """
            External PauC shaderpacks live in this folder.

            Format:
            - create a folder directly under packs/
            - add a pauc_shaderpack.json manifest
            - optionally add custom .fsh pass files referenced by the manifest
            - or place a .zip pack directly under packs/

            Loadable examples are generated as:
            - packs/competitive_fxaa/
            - packs/cinematic_light/

            PauC shaderpacks are multi-pass post-process stacks controlled by PauC.
            They are not Oculus/Iris packs and do not take over authority from PauC.
            """;
    private static final String COMPETITIVE_PACK_MANIFEST = """
            {
              "label": "Competitive FXAA Stack",
              "nativePass": true,
              "passes": [
                { "builtin": "fxaa_photon", "label": "FXAA" },
                { "builtin": "shadow_lift", "label": "Shadow Lift" },
                { "builtin": "light_clarity", "label": "Light Clarity" }
              ]
            }
            """;
    private static final String CINEMATIC_PACK_MANIFEST = """
            {
              "label": "Cinematic Light Stack",
              "nativePass": true,
              "passes": [
                { "builtin": "fxaa_elite", "label": "FXAA Elite" },
                { "builtin": "shadow_lift", "label": "Shadow Lift" },
                { "builtin": "warm_tonemap", "label": "Warm Tonemap" },
                { "file": "passes/custom_glow.fsh", "label": "Custom Glow" }
              ]
            }
            """;
    private static final String CINEMATIC_CUSTOM_GLOW = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform vec2 SourceSize;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                vec2 texel = vec2(1.0) / SourceSize;
                vec4 center = texture(DiffuseSampler, texCoord);
                vec3 blur = texture(DiffuseSampler, texCoord + vec2(texel.x, 0.0)).rgb;
                blur += texture(DiffuseSampler, texCoord - vec2(texel.x, 0.0)).rgb;
                blur += texture(DiffuseSampler, texCoord + vec2(0.0, texel.y)).rgb;
                blur += texture(DiffuseSampler, texCoord - vec2(0.0, texel.y)).rgb;
                blur *= 0.25;
                vec3 result = mix(center.rgb, max(center.rgb, blur * 1.08), 0.18);
                fragColor = vec4(clamp(result, 0.0, 1.0), center.a) * vertexColor * ColorModulator;
            }
            """;
    private static final String SHADOW_LIFT_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform float ShadowLift;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            float luma(vec3 color) {
                return dot(color, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                vec4 source = texture(DiffuseSampler, texCoord);
                float shadowMask = 1.0 - smoothstep(0.22, 0.78, luma(source.rgb));
                vec3 lifted = mix(source.rgb, sqrt(max(source.rgb, vec3(0.0))), shadowMask * ShadowLift);
                fragColor = vec4(clamp(lifted, 0.0, 1.0), source.a) * vertexColor * ColorModulator;
            }
            """;
    private static final String LIGHT_CLARITY_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform vec2 SourceSize;
            uniform float LightContrast;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                vec2 texel = vec2(1.0) / SourceSize;
                vec4 center = texture(DiffuseSampler, texCoord);
                vec3 neighbors = texture(DiffuseSampler, texCoord + vec2(texel.x, 0.0)).rgb;
                neighbors += texture(DiffuseSampler, texCoord - vec2(texel.x, 0.0)).rgb;
                neighbors += texture(DiffuseSampler, texCoord + vec2(0.0, texel.y)).rgb;
                neighbors += texture(DiffuseSampler, texCoord - vec2(0.0, texel.y)).rgb;
                neighbors *= 0.25;
                vec3 enhanced = center.rgb + (center.rgb - neighbors) * LightContrast;
                fragColor = vec4(clamp(enhanced, 0.0, 1.0), center.a) * vertexColor * ColorModulator;
            }
            """;
    private static final String WARM_TONEMAP_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D DiffuseSampler;
            uniform vec4 ColorModulator;
            uniform float LightGamma;

            in vec2 texCoord;
            in vec4 vertexColor;

            out vec4 fragColor;

            void main() {
                vec4 source = texture(DiffuseSampler, texCoord);
                vec3 warmed = source.rgb * vec3(1.03, 1.01, 0.96);
                vec3 graded = pow(clamp(warmed, 0.0, 1.0), vec3(max(0.35, LightGamma)));
                fragColor = vec4(clamp(graded, 0.0, 1.0), source.a) * vertexColor * ColorModulator;
            }
            """;

    private static final LinkedHashMap<String, ExternalShaderPackEntry> EXTERNAL_SHADERPACKS = new LinkedHashMap<>();
    private static TextureTarget transientTargetA;
    private static TextureTarget transientTargetB;
    private static int transientWidth = -1;
    private static int transientHeight = -1;

    private PauCShaderPackManager() {
    }

    public static void initializePackFolder() {
        try {
            Files.createDirectories(PACK_EXAMPLES_FOLDER);
            Files.createDirectories(COMPETITIVE_PACK_FOLDER);
            Files.createDirectories(CINEMATIC_PACK_FOLDER.resolve("passes"));
            Files.createDirectories(PACK_EXAMPLES_FOLDER.resolve("competitive_fxaa"));
            Files.createDirectories(PACK_EXAMPLES_FOLDER.resolve("cinematic_light").resolve("passes"));
            writeIfMissing(PACKS_FOLDER.resolve("README.txt"), PACKS_README);
            writeIfMissing(COMPETITIVE_PACK_FOLDER.resolve(MANIFEST_FILE_NAME), COMPETITIVE_PACK_MANIFEST);
            writeIfMissing(CINEMATIC_PACK_FOLDER.resolve(MANIFEST_FILE_NAME), CINEMATIC_PACK_MANIFEST);
            writeIfMissing(CINEMATIC_PACK_FOLDER.resolve("passes").resolve("custom_glow.fsh"), CINEMATIC_CUSTOM_GLOW);
            writeIfMissing(PACK_EXAMPLES_FOLDER.resolve("competitive_fxaa").resolve(MANIFEST_FILE_NAME), COMPETITIVE_PACK_MANIFEST);
            writeIfMissing(PACK_EXAMPLES_FOLDER.resolve("cinematic_light").resolve(MANIFEST_FILE_NAME), CINEMATIC_PACK_MANIFEST);
            writeIfMissing(PACK_EXAMPLES_FOLDER.resolve("cinematic_light").resolve("passes").resolve("custom_glow.fsh"), CINEMATIC_CUSTOM_GLOW);
        } catch (IOException exception) {
            Pain_au_Choc.LOGGER.warn("Failed to prepare PauC shaderpack folder {}", PACKS_FOLDER, exception);
        }
    }

    public static void reloadExternalShaderPacks() {
        closeExternalShaderPacks();
        initializePackFolder();

        if (!Files.isDirectory(PACKS_FOLDER)) {
            return;
        }

        try (Stream<Path> paths = Files.list(PACKS_FOLDER)) {
            paths.sorted().forEach(path -> {
                if (Files.isDirectory(path) && !path.equals(PACK_EXAMPLES_FOLDER)) {
                    loadShaderPackDirectory(path);
                    return;
                }
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(path) && fileName.endsWith(".zip")) {
                    loadShaderPackZip(path);
                }
            });
        } catch (IOException exception) {
            Pain_au_Choc.LOGGER.error("Failed to scan PauC shaderpacks {}", PACKS_FOLDER, exception);
        }

        Pain_au_Choc.LOGGER.info("PauC external shaderpacks loaded={}", EXTERNAL_SHADERPACKS.size());
    }

    public static void releaseTransientTargets() {
        if (transientTargetA != null) {
            transientTargetA.destroyBuffers();
            transientTargetA = null;
        }
        if (transientTargetB != null) {
            transientTargetB.destroyBuffers();
            transientTargetB = null;
        }
        transientWidth = -1;
        transientHeight = -1;
    }

    public static void closeExternalShaderPacks() {
        for (ExternalShaderPackEntry shaderPack : EXTERNAL_SHADERPACKS.values()) {
            for (ShaderPassInstance pass : shaderPack.passes) {
                pass.shader.close();
            }
        }
        EXTERNAL_SHADERPACKS.clear();
        releaseTransientTargets();
    }

    public static int getShaderPackCount() {
        return EXTERNAL_SHADERPACKS.size();
    }

    public static List<String> getShaderPackKeys() {
        return new ArrayList<>(EXTERNAL_SHADERPACKS.keySet());
    }

    public static boolean isShaderPackKey(String shaderKey) {
        return shaderKey != null && shaderKey.startsWith("pack:");
    }

    @Nullable
    public static String getShaderPackLabel(String shaderKey) {
        ExternalShaderPackEntry entry = EXTERNAL_SHADERPACKS.get(shaderKey);
        return entry == null ? null : entry.label;
    }

    public static boolean shouldProcessAtNativeScale(String shaderKey) {
        ExternalShaderPackEntry entry = EXTERNAL_SHADERPACKS.get(shaderKey);
        return entry != null && entry.nativePass;
    }

    public static boolean renderShaderPack(String shaderKey, RenderTarget source, RenderTarget target) {
        ExternalShaderPackEntry entry = EXTERNAL_SHADERPACKS.get(shaderKey);
        if (entry == null || entry.passes.isEmpty()) {
            return false;
        }

        ensureTransientTargets(target.viewWidth, target.viewHeight);
        RenderTarget currentSource = source;
        RenderTarget currentTarget = target;

        for (int index = 0; index < entry.passes.size(); index++) {
            ShaderPassInstance pass = entry.passes.get(index);
            boolean lastPass = index == entry.passes.size() - 1;
            if (!lastPass) {
                currentTarget = currentSource == transientTargetA ? transientTargetB : transientTargetA;
            } else {
                currentTarget = target;
            }

            renderWithShader(currentSource, currentTarget, pass.shader);
            currentSource = currentTarget;
        }

        return true;
    }

    private static void loadShaderPackDirectory(Path packDirectory) {
        Path manifestPath = packDirectory.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            return;
        }

        ArrayList<ShaderPassInstance> passes = new ArrayList<>();
        try {
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
            String packId = sanitizeShaderPath(packDirectory.getFileName().toString());
            String key = "pack:" + packId;
            String label = manifest.has("label") ? manifest.get("label").getAsString() : packId;
            boolean nativePass = !manifest.has("nativePass") || manifest.get("nativePass").getAsBoolean();
            JsonArray passArray = manifest.getAsJsonArray("passes");
            if (passArray == null || passArray.isEmpty()) {
                return;
            }

            int passIndex = 0;
            for (JsonElement passElement : passArray) {
                JsonObject passObject = passElement.getAsJsonObject();
                ShaderPassInstance passInstance = buildPassInstance(
                        packId,
                        passIndex,
                        passObject,
                        relativePath -> readDirectoryPassBytes(packDirectory, relativePath)
                );
                if (passInstance != null) {
                    passes.add(passInstance);
                }
                passIndex++;
            }

            if (passes.isEmpty()) {
                return;
            }

            EXTERNAL_SHADERPACKS.put(key, new ExternalShaderPackEntry(key, label, nativePass, passes));
            Pain_au_Choc.LOGGER.info("PauC shaderpack loaded: {}", label);
        } catch (Exception exception) {
            for (ShaderPassInstance pass : passes) {
                pass.shader.close();
            }
            Pain_au_Choc.LOGGER.error("Failed to load PauC shaderpack {}", packDirectory, exception);
        }
    }

    private static void loadShaderPackZip(Path zipPath) {
        ArrayList<ShaderPassInstance> passes = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ZipEntry manifestEntry = zipFile.getEntry(MANIFEST_FILE_NAME);
            if (manifestEntry == null) {
                return;
            }

            JsonObject manifest = JsonParser.parseString(new String(zipFile.getInputStream(manifestEntry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String packId = sanitizeShaderPath(stripExtension(zipPath.getFileName().toString()));
            String key = "pack:" + packId;
            String label = manifest.has("label") ? manifest.get("label").getAsString() : packId;
            boolean nativePass = !manifest.has("nativePass") || manifest.get("nativePass").getAsBoolean();
            JsonArray passArray = manifest.getAsJsonArray("passes");
            if (passArray == null || passArray.isEmpty()) {
                return;
            }

            int passIndex = 0;
            for (JsonElement passElement : passArray) {
                JsonObject passObject = passElement.getAsJsonObject();
                ShaderPassInstance passInstance = buildPassInstance(
                        packId,
                        passIndex,
                        passObject,
                        relativePath -> readZipPassBytes(zipFile, relativePath)
                );
                if (passInstance != null) {
                    passes.add(passInstance);
                }
                passIndex++;
            }

            if (passes.isEmpty()) {
                return;
            }

            EXTERNAL_SHADERPACKS.put(key, new ExternalShaderPackEntry(key, label, nativePass, passes));
            Pain_au_Choc.LOGGER.info("PauC shaderpack loaded from zip: {}", label);
        } catch (Exception exception) {
            for (ShaderPassInstance pass : passes) {
                pass.shader.close();
            }
            Pain_au_Choc.LOGGER.error("Failed to load PauC shaderpack zip {}", zipPath, exception);
        }
    }

    @Nullable
    private static ShaderPassInstance buildPassInstance(String packId, int passIndex, JsonObject passObject, PackPassResolver passResolver) throws IOException {
        String passLabel = passObject.has("label") ? passObject.get("label").getAsString() : "Pass " + (passIndex + 1);
        String shaderPath = sanitizeShaderPath(packId + "_" + passIndex);

        if (passObject.has("builtin")) {
            String builtinName = passObject.get("builtin").getAsString();
            BuiltinShaderPass builtinPass = BuiltinShaderPass.fromId(builtinName);
            if (builtinPass == null) {
                throw new IOException("Unknown builtin shaderpack pass: " + builtinName);
            }

            ShaderInstance shader = new ShaderInstance(
                    createGeneratedResourceProvider(shaderPath, builtinPass.fragmentSource),
                    ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, shaderPath),
                    DefaultVertexFormat.POSITION_TEX_COLOR
            );
            return new ShaderPassInstance(passLabel, shader);
        }

        if (passObject.has("file")) {
            byte[] fragmentBytes = passResolver.read(passObject.get("file").getAsString());
            if (fragmentBytes == null || fragmentBytes.length == 0) {
                throw new IOException("Invalid shaderpack fragment payload for pass " + passLabel);
            }

            ShaderInstance shader = new ShaderInstance(
                    createByteBackedResourceProvider(shaderPath, fragmentBytes),
                    ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, shaderPath),
                    DefaultVertexFormat.POSITION_TEX_COLOR
            );
            return new ShaderPassInstance(passLabel, shader);
        }

        throw new IOException("Shaderpack pass must define 'builtin' or 'file'");
    }

    private static ResourceProvider createGeneratedResourceProvider(String shaderPath, String fragmentSource) {
        Map<ResourceLocation, Resource> resources = new HashMap<>();
        PackResources packResources = new ShaderPackResources("pauc_shaderpack/" + shaderPath);
        addTextResource(resources, packResources, shaderJsonLocation(shaderPath), buildShaderJson(shaderPath));
        addTextResource(resources, packResources, vertexLocation(), PauCShaderManager.PASSTHROUGH_VERTEX_SOURCE);
        addTextResource(resources, packResources, fragmentLocation(shaderPath), fragmentSource);
        return ResourceProvider.fromMap(resources);
    }

    private static ResourceProvider createByteBackedResourceProvider(String shaderPath, byte[] fragmentBytes) {
        Map<ResourceLocation, Resource> resources = new HashMap<>();
        PackResources packResources = new ShaderPackResources("pauc_shaderpack/" + shaderPath);
        addTextResource(resources, packResources, shaderJsonLocation(shaderPath), buildShaderJson(shaderPath));
        addTextResource(resources, packResources, vertexLocation(), PauCShaderManager.PASSTHROUGH_VERTEX_SOURCE);
        addBinaryResource(resources, packResources, fragmentLocation(shaderPath), fragmentBytes);
        return ResourceProvider.fromMap(resources);
    }

    @Nullable
    private static byte[] readDirectoryPassBytes(Path packDirectory, String relativePath) throws IOException {
        Path fragmentPath = packDirectory.resolve(relativePath).normalize();
        if (!fragmentPath.startsWith(packDirectory) || !Files.isRegularFile(fragmentPath)) {
            return null;
        }
        return Files.readAllBytes(fragmentPath);
    }

    @Nullable
    private static byte[] readZipPassBytes(ZipFile zipFile, String relativePath) throws IOException {
        ZipEntry entry = zipFile.getEntry(relativePath.replace('\\', '/'));
        if (entry == null) {
            return null;
        }
        return zipFile.getInputStream(entry).readAllBytes();
    }

    private static String buildShaderJson(String shaderPath) {
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
                            { "name": "SourceSize", "type": "float", "count": 2, "values": [ 1.0, 1.0 ] },
                            { "name": "RcasStrength", "type": "float", "count": 1, "values": [ 0.0 ] },
                            { "name": "ShadowLift", "type": "float", "count": 1, "values": [ 0.42 ] },
                            { "name": "LightContrast", "type": "float", "count": 1, "values": [ 0.16 ] },
                            { "name": "LightGamma", "type": "float", "count": 1, "values": [ 0.92 ] }
                          ]
                        }
                        """,
                PACK_NAMESPACE,
                PACK_NAMESPACE,
                shaderPath
        );
    }

    private static void ensureTransientTargets(int width, int height) {
        if (transientTargetA == null) {
            transientTargetA = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            transientTargetB = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            transientWidth = width;
            transientHeight = height;
            return;
        }

        if (transientWidth != width || transientHeight != height) {
            transientTargetA.resize(width, height, Minecraft.ON_OSX);
            transientTargetB.resize(width, height, Minecraft.ON_OSX);
            transientWidth = width;
            transientHeight = height;
        }
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
            shader.getUniform("RcasStrength").set((float) PauCClient.getAdvancedSharpeningStrength());
        }
        if (shader.getUniform("ShadowLift") != null) {
            shader.getUniform("ShadowLift").set(0.42F);
        }
        if (shader.getUniform("LightContrast") != null) {
            shader.getUniform("LightContrast").set(0.16F);
        }
        if (shader.getUniform("LightGamma") != null) {
            shader.getUniform("LightGamma").set(0.92F);
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

    private static void addTextResource(Map<ResourceLocation, Resource> resources, PackResources packResources, ResourceLocation location, String contents) {
        addBinaryResource(resources, packResources, location, contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void addBinaryResource(Map<ResourceLocation, Resource> resources, PackResources packResources, ResourceLocation location, byte[] bytes) {
        resources.put(location, new Resource(packResources, () -> new ByteArrayInputStream(bytes)));
    }

    private static ResourceLocation shaderJsonLocation(String shaderPath) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "shaders/core/" + shaderPath + ".json");
    }

    private static ResourceLocation vertexLocation() {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "shaders/core/upscale_passthrough.vsh");
    }

    private static ResourceLocation fragmentLocation(String shaderPath) {
        return ResourceLocation.fromNamespaceAndPath(PACK_NAMESPACE, "shaders/core/" + shaderPath + ".fsh");
    }

    private static void writeIfMissing(Path path, String contents) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        }
    }

    private static String sanitizeShaderPath(String input) {
        String sanitized = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return sanitized.isBlank() ? "shaderpack" : sanitized;
    }

    private static String stripExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot <= 0 ? fileName : fileName.substring(0, lastDot);
    }

    private enum BuiltinShaderPass {
        FXAA_PHOTON("fxaa_photon", PauCShaderManager.PHOTON_FXAA_FRAGMENT_SOURCE),
        FXAA_ELITE("fxaa_elite", PauCShaderManager.ELITE_FXAA_FRAGMENT_SOURCE),
        SHADOW_LIFT("shadow_lift", SHADOW_LIFT_FRAGMENT_SOURCE),
        LIGHT_CLARITY("light_clarity", LIGHT_CLARITY_FRAGMENT_SOURCE),
        WARM_TONEMAP("warm_tonemap", WARM_TONEMAP_FRAGMENT_SOURCE);

        private final String id;
        private final String fragmentSource;

        BuiltinShaderPass(String id, String fragmentSource) {
            this.id = id;
            this.fragmentSource = fragmentSource;
        }

        @Nullable
        private static BuiltinShaderPass fromId(String id) {
            for (BuiltinShaderPass pass : values()) {
                if (pass.id.equals(id)) {
                    return pass;
                }
            }
            return null;
        }
    }

    private record ShaderPassInstance(String label, ShaderInstance shader) {
    }

    private record ExternalShaderPackEntry(String key, String label, boolean nativePass, List<ShaderPassInstance> passes) {
    }

    @FunctionalInterface
    private interface PackPassResolver {
        @Nullable
        byte[] read(String relativePath) throws IOException;
    }

    private static final class ShaderPackResources implements PackResources {
        private final String packId;

        private ShaderPackResources(String packId) {
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
            return Set.of(PACK_NAMESPACE);
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
