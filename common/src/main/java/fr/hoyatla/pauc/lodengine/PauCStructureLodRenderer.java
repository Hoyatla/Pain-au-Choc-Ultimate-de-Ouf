package fr.hoyatla.pauc.lodengine;

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
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;

/**
 * Draws blocky ARCHETYPES for distant structures located by {@link PauCDistantStructureLocator}. Not
 * billboards — a structure keeps right angles, so each is a small set of shaded boxes (a village cluster,
 * a stepped pyramid, a monument slab, a tower...) recognisable by shape from far, placed at the located
 * ground height. Isolated renderer (like the clouds), drawn with the terrain so a build shows at its real
 * spot before it is ever visited. Skips chunks vanilla is actually drawing (it renders the real build).
 */
public final class PauCStructureLodRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lodengine.distantStructures";
	private static final long REBUILD_INTERVAL_MS = 1_000L;
	private static final double REBUILD_MOVE_BLOCKS = 16.0D;

	private static VertexBuffer vertexBuffer;
	private static boolean built;
	private static int builtQuads;
	private static long builtMarkerRevision = -1;
	private static double originX;
	private static double originY;
	private static double originZ;
	private static long lastRebuildMs;
	private static boolean failed;

	private PauCStructureLodRenderer() {
	}

	public static boolean enabled() {
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return false;
		}
		// PauC↔DH COORDINATION (P3, 07-21): defer structures to DH ONLY when DH is actually RENDERING its
		// own LOD. Since paucOwnsLod (default on) silences DH's render (PauCDhRenderControl), PauC then
		// OWNS the LOD and MUST draw its own structure archetypes — else villages/monuments vanish exactly
		// as the user reported. When PauC does NOT own the LOD (paucOwnsLod off, or under a shaderpack that
		// DH integrates with), DH draws structures and PauC steps aside to avoid the z-fight/double-up.
		boolean paucOwnsLod = PauCTunables.readBoolean("pauc.lodengine.paucOwnsLod", true)
				&& !fr.hoyatla.pauc.shadercompat.PauCShaderCompat.isShaderPackInUse();
		if (fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isDistantHorizonsPresent()
				&& !paucOwnsLod
				&& !PauCTunables.readBoolean("pauc.lodengine.structuresWithDh", false)) {
			return false;
		}
		return true;
	}

	public static void render(PoseStack poseStack, Vec3 cameraPos) {
		if (failed || !enabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		if (PauCSurfaceWitnessRenderer.lodRadiusChunks(minecraft.options.getEffectiveRenderDistance()) <= 0) {
			return;
		}
		try {
			long now = System.currentTimeMillis();
			long rev = PauCStructureMarkerStore.revision();
			boolean moved = vertexBuffer != null
				&& cameraPos.distanceToSqr(originX, originY, originZ) > REBUILD_MOVE_BLOCKS * REBUILD_MOVE_BLOCKS;
			// Do NOT rebuild on every marker revision: the locator adds markers continuously while scanning,
			// which churned a full buffer rebuild + GPU upload many times a second. New markers fold in at
			// the next interval tick — a second's latency is invisible for a distant structure.
			if (!built || moved || now - lastRebuildMs >= REBUILD_INTERVAL_MS) {
				rebuild(minecraft, cameraPos, now, rev);
			}
			if (vertexBuffer == null || builtQuads == 0) {
				return;
			}
			ShaderInstance shader = GameRenderer.getPositionColorShader();
			if (shader == null) {
				return;
			}
			poseStack.pushPose();
			poseStack.translate(originX - cameraPos.x, originY - cameraPos.y, originZ - cameraPos.z);
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			vertexBuffer.bind();
			vertexBuffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);
			VertexBuffer.unbind();
			poseStack.popPose();
		} catch (Throwable throwable) {
			failed = true;
			LOGGER.warn("PauC distant structure renderer failed; disabled for this session.", throwable);
		}
	}

	private static void rebuild(Minecraft minecraft, Vec3 cameraPos, long now, long rev) {
		lastRebuildMs = now;
		built = true;
		builtMarkerRevision = rev;
		originX = cameraPos.x;
		originY = cameraPos.y;
		originZ = cameraPos.z;
		String dim = minecraft.level.dimension().location().toString();
		List<PauCStructureMarkerStore.Marker> markers = PauCStructureMarkerStore.markers(dim);

		float[] ambient = PauCSurfaceWitnessRenderer.ambientLightFactors(minecraft);
		int vanillaChunks = minecraft.options.getEffectiveRenderDistance();
		int minChunkDistance = vanillaChunks + 1;
		int maxChunkDistance = PauCSurfaceWitnessRenderer.lodRadiusChunks(vanillaChunks);
		int camChunkX = (int) Math.floor(cameraPos.x) >> 4;
		int camChunkZ = (int) Math.floor(cameraPos.z) >> 4;
		net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;

		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		int quads = 0;
		for (PauCStructureMarkerStore.Marker m : markers) {
			// UNDERWATER structures (shipwreck, ocean ruin, monument): their marker height is the
			// WORLD_SURFACE heightmap = the SEA SURFACE, so the archetype floated ON the ocean like a
			// boat ("débris sur l'océan", session 07-19). In vanilla these are sunken and invisible
			// from LOD distance — draw nothing, exactly like vanilla shows nothing.
			if (m.archetype == PauCStructureMarkerStore.ARCH_SHIPWRECK
					|| m.archetype == PauCStructureMarkerStore.ARCH_OCEAN_RUIN
					|| m.archetype == PauCStructureMarkerStore.ARCH_MONUMENT) {
				continue;
			}
			int chunkX = m.worldX >> 4;
			int chunkZ = m.worldZ >> 4;
			int dcx = chunkX - camChunkX;
			int dcz = chunkZ - camChunkZ;
			double radial = Math.sqrt((double) dcx * dcx + (double) dcz * dcz);
			if (radial < minChunkDistance || radial > maxChunkDistance) {
				continue;
			}
			if (level.getChunkSource().getChunk(chunkX, chunkZ, false) != null) {
				continue; // vanilla is drawing the real structure here
			}
			float cx = (float) (m.worldX + 0.5 - originX);
			float cz = (float) (m.worldZ + 0.5 - originZ);
			// +1 offset: structure base sits above the terrain surface, preventing the side faces from
			// touching the terrain at the seam (which caused the same z-fighting/false-texture merge
			// as the removed bottom face).
			float base = (float) (m.groundY + 1 - originY);
			quads = emitArchetype(buffer, m.archetype, cx, base, cz, ambient, quads);
		}

		BufferBuilder.RenderedBuffer rendered = buffer.end();
		builtQuads = quads;
		if (quads == 0) {
			rendered.release(); // no markers in view: never upload/keep an empty buffer, never spin
			return;
		}
		if (vertexBuffer == null) {
			vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		}
		vertexBuffer.bind();
		vertexBuffer.upload(rendered);
		VertexBuffer.unbind();
	}

	private static int emitArchetype(BufferBuilder buf, int arch, float cx, float base, float cz, float[] amb, int quads) {
		switch (arch) {
			case PauCStructureMarkerStore.ARCH_VILLAGE: {
				// A cluster of small houses: tan walls + a darker roof box, spread over ~±10 blocks.
				int wall = 0xC9B27A;
				int roof = 0x7A4B2B;
				float[][] houses = { {-9, -6, 5, 4}, {2, -8, 6, 5}, {-3, 4, 5, 4}, {7, 6, 5, 4}, {-8, 8, 4, 3} };
				for (float[] h : houses) {
					float hx = cx + h[0];
					float hz = cz + h[1];
					float w = h[2] * 0.5F;
					float ht = h[3];
					quads = box(buf, hx - w, base, hz - w, hx + w, base + ht, hz + w, wall, amb, quads);
					quads = box(buf, hx - w - 0.4F, base + ht, hz - w - 0.4F, hx + w + 0.4F, base + ht + 1.6F, hz + w + 0.4F, roof, amb, quads);
				}
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_DESERT_PYRAMID:
				return steppedPyramid(buf, cx, base, cz, 0xD9C79A, amb, quads);
			case PauCStructureMarkerStore.ARCH_JUNGLE_TEMPLE:
				return steppedPyramid(buf, cx, base, cz, 0x6B7A55, amb, quads);
			case PauCStructureMarkerStore.ARCH_OUTPOST: {
				int wood = 0x4A3722;
				quads = box(buf, cx - 3, base, cz - 3, cx + 3, base + 13, cz + 3, wood, amb, quads);
				quads = box(buf, cx - 4, base + 10, cz - 4, cx + 4, base + 13, cz + 4, wood, amb, quads); // overhang top
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_MONUMENT: {
				int pris = 0x4E9E92;
				quads = box(buf, cx - 11, base, cz - 11, cx + 11, base + 7, cz + 11, pris, amb, quads);
				quads = box(buf, cx - 4, base + 7, cz - 4, cx + 4, base + 13, cz + 4, pris, amb, quads); // central spire
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_MANSION: {
				int wall = 0x8A7A5A;
				int roof = 0x53412C;
				quads = box(buf, cx - 11, base, cz - 9, cx + 11, base + 9, cz + 9, wall, amb, quads);
				quads = box(buf, cx - 12, base + 9, cz - 10, cx + 12, base + 11, cz + 10, roof, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_WITCH_HUT: {
				int wood = 0x3E2F1C;
				// four stilts + a small raised hut
				for (float sx : new float[] { -2.5F, 2.5F }) {
					for (float sz : new float[] { -2.5F, 2.5F }) {
						quads = box(buf, cx + sx - 0.5F, base, cz + sz - 0.5F, cx + sx + 0.5F, base + 3, cz + sz + 0.5F, wood, amb, quads);
					}
				}
				quads = box(buf, cx - 3, base + 3, cz - 3, cx + 3, base + 6, cz + 3, wood, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_IGLOO: {
				int snow = 0xE8EEF2;
				quads = box(buf, cx - 3, base, cz - 3, cx + 3, base + 3, cz + 3, snow, amb, quads);
				quads = box(buf, cx - 2, base + 3, cz - 2, cx + 2, base + 4, cz + 2, snow, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_RUINED_PORTAL: {
				int obs = 0x241634;
				quads = box(buf, cx - 3, base, cz - 1, cx - 2, base + 5, cz + 1, obs, amb, quads);
				quads = box(buf, cx + 2, base, cz - 1, cx + 3, base + 5, cz + 1, obs, amb, quads);
				quads = box(buf, cx - 3, base + 4, cz - 1, cx + 3, base + 5, cz + 1, obs, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_SHIPWRECK: {
				int wood = 0x5A4526;
				quads = box(buf, cx - 8, base, cz - 2, cx + 8, base + 4, cz + 2, wood, amb, quads);
				quads = box(buf, cx - 1, base + 4, cz - 0.6F, cx + 1, base + 10, cz + 0.6F, wood, amb, quads); // mast
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_OCEAN_RUIN: {
				int stone = 0x8A8A82;
				quads = box(buf, cx - 4, base, cz - 4, cx + 2, base + 4, cz + 1, stone, amb, quads);
				quads = box(buf, cx + 1, base, cz + 1, cx + 5, base + 3, cz + 5, stone, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_NETHER_FORTRESS: {
				int netherBrick = 0x2C1218;
				int darkBrick = 0x1A0A10;
				// Long bridge segment
				quads = box(buf, cx - 12, base, cz - 2, cx + 12, base + 4, cz + 2, netherBrick, amb, quads);
				// Bridge railings
				quads = box(buf, cx - 12, base + 4, cz - 2.5F, cx + 12, base + 6, cz - 1.5F, darkBrick, amb, quads);
				quads = box(buf, cx - 12, base + 4, cz + 1.5F, cx + 12, base + 6, cz + 2.5F, darkBrick, amb, quads);
				// Watchtower at one end
				quads = box(buf, cx + 8, base, cz - 4, cx + 14, base + 12, cz + 4, netherBrick, amb, quads);
				// Spire
				quads = box(buf, cx + 10, base + 12, cz - 1, cx + 12, base + 16, cz + 1, darkBrick, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_BASTION: {
				int blackstone = 0x2A2218;
				int goldTrim = 0xB8942A;
				// Main fortress body — large irregular shape
				quads = box(buf, cx - 8, base, cz - 8, cx + 8, base + 10, cz + 8, blackstone, amb, quads);
				// Central keep tower
				quads = box(buf, cx - 3, base + 10, cz - 3, cx + 3, base + 18, cz + 3, blackstone, amb, quads);
				// Ramparts / corner towers
				quads = box(buf, cx - 9, base, cz - 9, cx - 6, base + 7, cz - 6, blackstone, amb, quads);
				quads = box(buf, cx + 6, base, cz - 9, cx + 9, base + 7, cz - 6, blackstone, amb, quads);
				quads = box(buf, cx - 9, base, cz + 6, cx - 6, base + 7, cz + 9, blackstone, amb, quads);
				quads = box(buf, cx + 6, base, cz + 6, cx + 9, base + 7, cz + 9, blackstone, amb, quads);
				// Gold trim band
				quads = box(buf, cx - 8, base + 9, cz - 8, cx + 8, base + 10, cz + 8, goldTrim, amb, quads);
				return quads;
			}
			case PauCStructureMarkerStore.ARCH_TRAIL_RUINS: {
				int terracotta = 0x9B6B52;
				int mossyStone = 0x6B7A5A;
				int packed = 0x8A6A50;
				// Mound-like base — partially buried ruins poking through terrain
				quads = box(buf, cx - 5, base, cz - 4, cx + 5, base + 3, cz + 4, packed, amb, quads);
				// Upper ruins — broken towers and walls
				quads = box(buf, cx - 3, base + 3, cz - 2, cx + 1, base + 7, cz + 2, terracotta, amb, quads);
				quads = box(buf, cx + 2, base + 3, cz - 3, cx + 4, base + 6, cz + 1, mossyStone, amb, quads);
				quads = box(buf, cx - 1, base + 7, cz - 1, cx + 1, base + 9, cz + 1, terracotta, amb, quads);
				return quads;
			}
			default: {
				int stone = 0x8A8A8A;
				quads = box(buf, cx - 4, base, cz - 4, cx + 4, base + 5, cz + 4, stone, amb, quads);
				quads = box(buf, cx + 2, base, cz + 2, cx + 6, base + 3, cz + 6, stone, amb, quads);
				return quads;
			}
		}
	}

	private static int steppedPyramid(BufferBuilder buf, float cx, float base, float cz, int color, float[] amb, int quads) {
		float[] halfW = { 8.0F, 5.5F, 3.0F };
		float y = base;
		for (float hw : halfW) {
			quads = box(buf, cx - hw, y, cz - hw, cx + hw, y + 3.0F, cz + hw, color, amb, quads);
			y += 3.0F;
		}
		return quads;
	}

	/**
	 * One axis-aligned box, 5 faces, per-face shaded (top brightest → bottom darkest) and ambient-tinted.
	 * Bottom face (y0) is skipped: the LOD terrain surface already covers that plane, so drawing it
	 * caused z-fighting flicker between the structure colour and the terrain colour at the seam.
	 */
	private static int box(BufferBuilder buf, float x0, float y0, float z0, float x1, float y1, float z1,
			int color, float[] amb, int quads) {
		int br = (color >> 16) & 0xff;
		int bg = (color >> 8) & 0xff;
		int bb = color & 0xff;
		// top (y1)
		face(buf, amb, br, bg, bb, 1.00F, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		// bottom (y0) — SKIPPED: terrain surface covers this plane
		// north (z0)
		face(buf, amb, br, bg, bb, 0.72F, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0);
		// south (z1)
		face(buf, amb, br, bg, bb, 0.72F, x1, y1, z1, x0, y1, z1, x0, y0, z1, x1, y0, z1);
		// west (x0)
		face(buf, amb, br, bg, bb, 0.60F, x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1);
		// east (x1)
		face(buf, amb, br, bg, bb, 0.60F, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0);
		return quads + 5;
	}

	private static void face(BufferBuilder buf, float[] amb, int br, int bg, int bb, float shade,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		int r = Math.min(255, (int) (br * shade * amb[0]));
		int g = Math.min(255, (int) (bg * shade * amb[1]));
		int b = Math.min(255, (int) (bb * shade * amb[2]));
		buf.vertex(ax, ay, az).color(r, g, b, 255).endVertex();
		buf.vertex(bx, by, bz).color(r, g, b, 255).endVertex();
		buf.vertex(cx, cy, cz).color(r, g, b, 255).endVertex();
		buf.vertex(dx, dy, dz).color(r, g, b, 255).endVertex();
	}
}
