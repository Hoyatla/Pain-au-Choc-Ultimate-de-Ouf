package fr.hoyatla.pauc.lodengine;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * LOD clouds: extends the cloud cover past vanilla's fixed ±384-block cloud mesh out to the PauC LOD
 * horizon, so the sky doesn't go cloud-empty beyond the vanilla ring. Same visual language as the
 * terrain LOD: flat cells sampled from the REAL {@code clouds.png} pattern (12 blocks per cell, same
 * drift direction/speed as vanilla), tinted with the level's live cloud colour (day/night/weather) and
 * alpha-faded at the horizon edge.
 *
 * <p>Small mesh (a few thousand quads), rebuilt on the render thread at most every few seconds —
 * no async machinery needed. Follows the LODs gauge: OFF when the LOD engine is off.</p>
 */
public final class PauCCloudLodRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lodengine.lodClouds";
	private static final String START_BLOCKS_PROPERTY = "pauc.lodengine.lodCloudStartBlocks";
	private static final int CELL_BLOCKS = 12; // vanilla cloud cell size
	private static final int CLOUD_THICKNESS_BLOCKS = 4; // vanilla fancy cloud box height
	private static final int VANILLA_CLOUD_RADIUS_BLOCKS = 384; // vanilla's cloud mesh half-extent
	private static final long REBUILD_INTERVAL_MS = 3_000L;
	private static final double REBUILD_MOVE_BLOCKS = 64.0D;

	private static VertexBuffer vertexBuffer;
	private static int builtQuads;
	private static long lastRebuildMs;
	private static double originX;
	private static double originY;
	private static double originZ;
	// Drift baked into the current mesh: the per-frame draw translation adds (driftNow - builtDrift) so
	// the LOD clouds GLIDE continuously exactly like vanilla's (baking drift per rebuild made the
	// pattern jump a whole cell every ~20s and never line up with the vanilla layer).
	private static double builtDrift;
	// Camera side of the cloud layer at build time (-1 below / 0 inside / +1 above): only the faces
	// visible from that side are emitted — drawing tops AND bottoms made them blend over each other
	// (triangulated seams on the underside of big slabs, the "cloud culling" artifact).
	private static int builtCamSide = Integer.MIN_VALUE;
	private static boolean[] cloudCells; // 256x256 bitmap from clouds.png
	private static boolean bitmapFailed;
	private static boolean renderFailureLogged;

	private PauCCloudLodRenderer() {
	}

	/**
	 * True when PauC OWNS the cloud layer (vanilla clouds are cancelled and PauC draws ONE coherent
	 * layer from the player out to the LOD horizon). A patch-ring approach (vanilla inside, PauC ring
	 * outside) never lines up — two separately-built layers always betray a seam.
	 */
	public static boolean ownsClouds() {
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)
			|| !fr.hoyatla.pauc.lod.PauCLodClientSettings.isLodCloudsEnabled()
			|| fr.hoyatla.pauc.lod.PauCLodShaderContext.isShaderPackInUse()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.options == null) {
			return false;
		}
		return PauCSurfaceWitnessRenderer.lodRadiusChunks(minecraft.options.getEffectiveRenderDistance()) > 0;
	}

	public static void render(PoseStack poseStack, Vec3 cameraPos, float partialTick) {
		if (!ownsClouds()) {
			builtQuads = 0;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		try {
			int lodRadius = PauCSurfaceWitnessRenderer.lodRadiusChunks(minecraft.options.getEffectiveRenderDistance());
			if (lodRadius <= 0) {
				builtQuads = 0;
				return; // LODs gauge OFF → LOD clouds off too
			}
			float cloudHeight = minecraft.level.effects().getCloudHeight();
			if (Float.isNaN(cloudHeight)) {
				return; // dimension without clouds
			}
			if (!loadBitmap(minecraft)) {
				return;
			}

			long now = System.currentTimeMillis();
			boolean moved = cameraPos.distanceToSqr(originX, originY, originZ) > REBUILD_MOVE_BLOCKS * REBUILD_MOVE_BLOCKS;
			int camSide = cameraPos.y < cloudHeight - 0.5D ? -1 : cameraPos.y > cloudHeight + CLOUD_THICKNESS_BLOCKS ? 1 : 0;
			boolean sideChanged = camSide != builtCamSide;
			if (vertexBuffer == null || sideChanged
				|| ((moved || now - lastRebuildMs >= REBUILD_INTERVAL_MS) && now - lastRebuildMs >= 1_000L)) {
				rebuild(minecraft, cameraPos, partialTick, cloudHeight, lodRadius, now, camSide);
			}
			if (vertexBuffer == null || builtQuads == 0) {
				return;
			}
			ShaderInstance shader = GameRenderer.getPositionColorShader();
			if (shader == null) {
				return;
			}
			// Continuous vanilla drift: clouds slide toward -X at 0.03 blocks/tick, every frame.
			double driftNow = (minecraft.level.getGameTime() + partialTick) * 0.03D;
			poseStack.pushPose();
			poseStack.translate(originX - cameraPos.x - (driftNow - builtDrift), originY - cameraPos.y, originZ - cameraPos.z);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(true);
			RenderSystem.disableCull();
			vertexBuffer.bind();
			// VANILLA'S OWN CLOUD TRICK — draw twice: a DEPTH-ONLY prepass (no colour) fixes the nearest
			// face per pixel, then the colour pass blends EXACTLY ONE face per pixel. Kills every
			// internal-face artifact (own back side through the front, cell seams, angle-dependent
			// double-blends) at any camera position, with no winding/culling fragility.
			RenderSystem.colorMask(false, false, false, false);
			vertexBuffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);
			RenderSystem.colorMask(true, true, true, true);
			vertexBuffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);
			VertexBuffer.unbind();
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
			poseStack.popPose();
		} catch (Throwable throwable) {
			if (!renderFailureLogged) {
				renderFailureLogged = true;
				LOGGER.warn("PauC LOD cloud renderer failed; disabled for this session.", throwable);
			}
			System.setProperty(ENABLED_PROPERTY, "false");
		}
	}

	private static void rebuild(Minecraft minecraft, Vec3 cameraPos, float partialTick, float cloudHeight, int lodRadius, long now, int camSide) {
		lastRebuildMs = now;
		builtCamSide = camSide;
		boolean emitTops = camSide >= 0;
		boolean emitBottoms = camSide <= 0;
		// PauC owns the WHOLE cloud layer (vanilla clouds are cancelled): full disc from the player to
		// the LOD horizon — one coherent layer, no ring seam.
		int startBlocks = readInt(START_BLOCKS_PROPERTY, 0, 0, 4096);
		int endBlocks = lodRadius * 16;
		if (endBlocks <= startBlocks) {
			builtQuads = 0;
			return;
		}
		// Vanilla cloud drift: +X at 0.03 blocks/tick; the texture scrolls, the mesh stays camera-local.
		double drift = (minecraft.level.getGameTime() + partialTick) * 0.03D;
		Vec3 cloudColor = minecraft.level.getCloudColor(partialTick);
		float y = cloudHeight - 0.33F; // just under the vanilla cloud slab: no z-fight in the overlap ring

		long startSq = (long) startBlocks * startBlocks;
		long endSq = (long) endBlocks * endBlocks;

		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		int quads = 0;
		int r = (int) (cloudColor.x * 255.0D);
		int g = (int) (cloudColor.y * 255.0D);
		int b = (int) (cloudColor.z * 255.0D);

		// Cells are anchored to the DRIFTING vanilla cloud grid (cell k occupies worldX = k*12 - drift,
		// z offset -3.96 like vanilla's +0.33-cell v offset), so the LOD pattern lines up with the
		// vanilla cloud layer at the seam and glides with it instead of sitting frozen in world space.
		int range = endBlocks / CELL_BLOCKS + 2;
		int camCellX = (int) Math.floor((cameraPos.x + drift) / CELL_BLOCKS);
		int camCellZ = (int) Math.floor((cameraPos.z + 3.96D) / CELL_BLOCKS);
		float yBottom = (float) (y - cameraPos.y);
		float yTop = yBottom + CLOUD_THICKNESS_BLOCKS;
		for (int cz = -range; cz <= range; cz++) {
			for (int cx = -range; cx <= range; cx++) {
				int kx = camCellX + cx;
				int kz = camCellZ + cz;
				double bx = kx * (double) CELL_BLOCKS - drift - cameraPos.x;
				double bz = kz * (double) CELL_BLOCKS - 3.96D - cameraPos.z;
				double cxm = bx + CELL_BLOCKS * 0.5D;
				double czm = bz + CELL_BLOCKS * 0.5D;
				long distSq = (long) (cxm * cxm + czm * czm);
				if (distSq < startSq || distSq > endSq) {
					continue; // vanilla covers the inner disc; the player's chosen view ends the outer
				}
				int texX = kx & 255;
				int texZ = kz & 255;
				if (!cloudCells[(texZ << 8) | texX]) {
					continue;
				}
				// Minecraft 26.x cloud model: base OPAQUE (a cloud seen from below is solid, not a veil),
				// alpha fading LINEARLY from the player (distance 0) out to the cloud fog end (verified
				// in the 26.1.2 core shader: `color.a *= 1 - linear_fog_value(vertexDistance, 0, FogCloudsEnd)`).
				float dist = (float) Math.sqrt((double) distSq);
				float alpha = 1.0F - dist / endBlocks;
				if (alpha <= 0.02F) {
					continue;
				}
				int a = (int) (alpha * 255.0F);
				float x0 = (float) bx;
				float z0 = (float) bz;
				float x1 = x0 + CELL_BLOCKS;
				float z1 = z0 + CELL_BLOCKS;

				// 3D cloud box (12x4x12), 26.x face colours: top 1.0, bottom 0.7, X sides 0.9, Z sides
				// 0.8 — side faces only where the neighbouring cell is empty, and top/bottom only on the
				// camera's side of the layer (both at once double-blend into visible seams).
				if (emitTops) {
					buffer.vertex(x0, yTop, z0).color(r, g, b, a).endVertex();
					buffer.vertex(x0, yTop, z1).color(r, g, b, a).endVertex();
					buffer.vertex(x1, yTop, z1).color(r, g, b, a).endVertex();
					buffer.vertex(x1, yTop, z0).color(r, g, b, a).endVertex();
					quads++;
				}
				if (emitBottoms) {
					int rb = (int) (r * 0.7F);
					int gb = (int) (g * 0.7F);
					int bb = (int) (b * 0.7F);
					buffer.vertex(x0, yBottom, z0).color(rb, gb, bb, a).endVertex();
					buffer.vertex(x0, yBottom, z1).color(rb, gb, bb, a).endVertex();
					buffer.vertex(x1, yBottom, z1).color(rb, gb, bb, a).endVertex();
					buffer.vertex(x1, yBottom, z0).color(rb, gb, bb, a).endVertex();
					quads++;
				}
				int rx = (int) (r * 0.9F);
				int gx = (int) (g * 0.9F);
				int bx2 = (int) (b * 0.9F);
				if (!cloudCells[(texZ << 8) | ((texX + 1) & 255)]) {
					buffer.vertex(x1, yTop, z0).color(rx, gx, bx2, a).endVertex();
					buffer.vertex(x1, yTop, z1).color(rx, gx, bx2, a).endVertex();
					buffer.vertex(x1, yBottom, z1).color(rx, gx, bx2, a).endVertex();
					buffer.vertex(x1, yBottom, z0).color(rx, gx, bx2, a).endVertex();
					quads++;
				}
				if (!cloudCells[(texZ << 8) | ((texX - 1) & 255)]) {
					buffer.vertex(x0, yTop, z0).color(rx, gx, bx2, a).endVertex();
					buffer.vertex(x0, yTop, z1).color(rx, gx, bx2, a).endVertex();
					buffer.vertex(x0, yBottom, z1).color(rx, gx, bx2, a).endVertex();
					buffer.vertex(x0, yBottom, z0).color(rx, gx, bx2, a).endVertex();
					quads++;
				}
				int rz = (int) (r * 0.8F);
				int gz = (int) (g * 0.8F);
				int bz2 = (int) (b * 0.8F);
				if (!cloudCells[(((texZ + 1) & 255) << 8) | texX]) {
					buffer.vertex(x0, yTop, z1).color(rz, gz, bz2, a).endVertex();
					buffer.vertex(x1, yTop, z1).color(rz, gz, bz2, a).endVertex();
					buffer.vertex(x1, yBottom, z1).color(rz, gz, bz2, a).endVertex();
					buffer.vertex(x0, yBottom, z1).color(rz, gz, bz2, a).endVertex();
					quads++;
				}
				if (!cloudCells[(((texZ - 1) & 255) << 8) | texX]) {
					buffer.vertex(x0, yTop, z0).color(rz, gz, bz2, a).endVertex();
					buffer.vertex(x1, yTop, z0).color(rz, gz, bz2, a).endVertex();
					buffer.vertex(x1, yBottom, z0).color(rz, gz, bz2, a).endVertex();
					buffer.vertex(x0, yBottom, z0).color(rz, gz, bz2, a).endVertex();
					quads++;
				}
			}
		}

		BufferBuilder.RenderedBuffer rendered = buffer.end();
		if (vertexBuffer == null) {
			vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		}
		vertexBuffer.bind();
		vertexBuffer.upload(rendered);
		VertexBuffer.unbind();
		originX = cameraPos.x;
		originY = cameraPos.y;
		originZ = cameraPos.z;
		builtDrift = drift;
		builtQuads = quads;
	}

	/**
	 * Loads the cloud pattern once (256x256, alpha>128 = cloud cell). Prefers the MODERN Minecraft
	 * 26.x pattern bundled with PauC (the cloud shapes changed vs 1.20.1 — "the clouds don't look like
	 * new Minecraft" was literally a different clouds.png); falls back to the game's own texture (which
	 * also lets resource packs override clouds by removing the bundled file).
	 */
	private static boolean loadBitmap(Minecraft minecraft) {
		if (cloudCells != null) {
			return true;
		}
		if (bitmapFailed) {
			return false;
		}
		try (var resource = minecraft.getResourceManager().open(new ResourceLocation("iris", "textures/pauc/clouds_26.png"))) {
			return readBitmap(resource);
		} catch (Throwable ignored) {
			// bundled modern pattern missing → fall back to the game's clouds.png below
		}
		try (var resource = minecraft.getResourceManager().open(new ResourceLocation("textures/environment/clouds.png"))) {
			return readBitmap(resource);
		} catch (Throwable throwable) {
			bitmapFailed = true;
			LOGGER.warn("PauC LOD clouds: could not read clouds.png; LOD clouds disabled.", throwable);
			return false;
		}
	}

	private static boolean readBitmap(java.io.InputStream resource) throws java.io.IOException {
		try (NativeImage image = NativeImage.read(resource)) {
			boolean[] cells = new boolean[256 * 256];
			int w = Math.min(256, image.getWidth());
			int h = Math.min(256, image.getHeight());
			for (int z = 0; z < h; z++) {
				for (int x = 0; x < w; x++) {
					cells[(z << 8) | x] = ((image.getPixelRGBA(x, z) >>> 24) & 0xff) > 128;
				}
			}
			cloudCells = cells;
			LOGGER.info("PauC LOD clouds: cloud pattern loaded ({}x{}).", w, h);
			return true;
		}
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		if (edge1 <= edge0) {
			return x >= edge1 ? 1.0F : 0.0F;
		}
		float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0F - 2.0F * t);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = PauCTunables.raw(key);
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}
}
