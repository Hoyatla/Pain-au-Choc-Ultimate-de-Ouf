package pauc.pain_au_choc.render.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * PBR texture manager for the deferred pipeline.
 * Detects and manages normal maps (_n) and specular maps (_s) for block/entity textures.
 *
 * OptiFine-compatible shaderpacks expect PBR textures bound alongside albedo:
 * - normals texture unit (typically colortex4 or a dedicated unit)
 * - specular texture unit (typically colortex5 or a dedicated unit)
 *
 * Adapted from Oculus/Iris PBRTextureManager.
 */
public final class PauCPBRTextureManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** GL texture unit for normal maps (matching OptiFine convention). */
    public static final int NORMAL_MAP_TEXTURE_UNIT = GL13.GL_TEXTURE4;

    /** GL texture unit for specular maps (matching OptiFine convention). */
    public static final int SPECULAR_MAP_TEXTURE_UNIT = GL13.GL_TEXTURE5;

    /** Default flat normal (0.5, 0.5, 1.0) packed as a 1x1 texture. */
    private static int defaultNormalTexture = -1;

    /** Default zero-specular packed as a 1x1 texture. */
    private static int defaultSpecularTexture = -1;

    /** Cache: base texture location → PBR info. */
    private static final Map<ResourceLocation, PBRTextureInfo> pbrCache = new HashMap<>();

    /** Whether PBR detection has been enabled for the current shaderpack. */
    private static boolean pbrEnabled = false;

    private PauCPBRTextureManager() {
    }

    /**
     * Enable PBR texture detection. Called when a shaderpack that
     * uses PBR features (normals/specular programs) is loaded.
     */
    public static void enable() {
        pbrEnabled = true;
        ensureDefaultTextures();
        LOGGER.info("PauC PBR texture manager enabled");
    }

    /**
     * Disable PBR and release cached data.
     */
    public static void disable() {
        pbrEnabled = false;
        pbrCache.clear();
        LOGGER.info("PauC PBR texture manager disabled");
    }

    public static boolean isEnabled() {
        return pbrEnabled;
    }

    /**
     * Bind PBR textures for the given base texture to the expected texture units.
     * If no PBR textures exist, binds default flat normal and zero specular.
     *
     * @param baseTexture the albedo texture location
     */
    public static void bindPBRTextures(ResourceLocation baseTexture) {
        if (!pbrEnabled) {
            return;
        }

        PBRTextureInfo info = pbrCache.get(baseTexture);
        if (info == null) {
            info = detectPBRTextures(baseTexture);
            pbrCache.put(baseTexture, info);
        }

        // Bind normal map
        GL13.glActiveTexture(NORMAL_MAP_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, info.normalTextureId);

        // Bind specular map
        GL13.glActiveTexture(SPECULAR_MAP_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, info.specularTextureId);

        // Restore active texture to unit 0
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Unbind PBR textures (reset to defaults).
     */
    public static void unbindPBRTextures() {
        if (!pbrEnabled) {
            return;
        }

        GL13.glActiveTexture(NORMAL_MAP_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultNormalTexture);

        GL13.glActiveTexture(SPECULAR_MAP_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, defaultSpecularTexture);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Detect PBR textures for a given base texture.
     * Looks for _n (normal) and _s (specular) suffixed textures in the texture manager.
     */
    private static PBRTextureInfo detectPBRTextures(ResourceLocation baseTexture) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();

        int normalId = defaultNormalTexture;
        int specularId = defaultSpecularTexture;

        // Try to find normal map: textures/block/stone.png → textures/block/stone_n.png
        ResourceLocation normalLoc = derivePBRLocation(baseTexture, "_n");
        if (normalLoc != null) {
            AbstractTexture normalTex = textureManager.getTexture(normalLoc);
            if (normalTex != null) {
                normalId = normalTex.getId();
            }
        }

        // Try to find specular map
        ResourceLocation specularLoc = derivePBRLocation(baseTexture, "_s");
        if (specularLoc != null) {
            AbstractTexture specularTex = textureManager.getTexture(specularLoc);
            if (specularTex != null) {
                specularId = specularTex.getId();
            }
        }

        return new PBRTextureInfo(normalId, specularId);
    }

    /**
     * Derive PBR texture location by appending suffix before extension.
     * Example: minecraft:textures/block/stone.png → minecraft:textures/block/stone_n.png
     */
    private static ResourceLocation derivePBRLocation(ResourceLocation base, String suffix) {
        String path = base.getPath();
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0) {
            return null;
        }
        String pbrPath = path.substring(0, dotIndex) + suffix + path.substring(dotIndex);
        return ResourceLocation.tryBuild(base.getNamespace(), pbrPath);
    }

    /**
     * Create default 1x1 PBR fallback textures if not yet created.
     */
    private static void ensureDefaultTextures() {
        if (defaultNormalTexture >= 0) {
            return;
        }

        // Default normal: flat surface pointing up (0.5, 0.5, 1.0) → RGB (128, 128, 255)
        defaultNormalTexture = create1x1Texture(128, 128, 255, 255);

        // Default specular: no specular (0, 0, 0, 0)
        defaultSpecularTexture = create1x1Texture(0, 0, 0, 0);
    }

    /**
     * Create a 1x1 GL texture with the given RGBA values.
     */
    private static int create1x1Texture(int r, int g, int b, int a) {
        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        java.nio.ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(4);
        buffer.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        buffer.flip();

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texId;
    }

    /**
     * Release GL resources.
     */
    public static void cleanup() {
        if (defaultNormalTexture >= 0) {
            GL11.glDeleteTextures(defaultNormalTexture);
            defaultNormalTexture = -1;
        }
        if (defaultSpecularTexture >= 0) {
            GL11.glDeleteTextures(defaultSpecularTexture);
            defaultSpecularTexture = -1;
        }
        pbrCache.clear();
        pbrEnabled = false;
    }

    /**
     * Get the number of cached PBR texture entries (for debug).
     */
    public static int getCacheSize() {
        return pbrCache.size();
    }

    private record PBRTextureInfo(int normalTextureId, int specularTextureId) {
    }
}
