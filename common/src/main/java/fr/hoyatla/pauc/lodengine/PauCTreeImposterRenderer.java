package fr.hoyatla.pauc.lodengine;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * LOD engine 0.6.1 — vegetation IMPOSTERS (billboards) instead of boxy tree geometry.
 *
 * <p>Each distant plant is a CROSS of two textured quads against one baked ATLAS of silhouettes
 * (broadleaf, conifer, jungle, savanna, bamboo, coral, kelp), the silhouette chosen from the column's
 * material tag, tinted per-plant. The terrain mesh draws only the ground for these tiles.</p>
 *
 * <p>PERF: billboards are batched into PER-REGION vertex buffers and FRUSTUM-CULLED each frame. The
 * buffers are anchored to a FIXED origin (re-anchored only on a big move) so moving does not force a
 * rebuild, and a rebuild PASS is DRAINED a few regions per frame — the profiler showed a full-ring
 * rebuild spiking 20-37 ms on the render thread, a per-move hitch; spreading it keeps every frame cheap.</p>
 */
public final class PauCTreeImposterRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lodengine.treeImposters";
	private static final long PASS_INTERVAL_MS = 1_500L;
	private static final double REANCHOR_BLOCKS = 192.0D;
	private static final int REGIONS_PER_FRAME = 6; // drain budget: a full ring finishes over a few frames
	private static final int TREE_CELL = 3;

	private static final int CW = 32;
	private static final int CH = 48;
	private static final int ATLAS_COLS = 4;
	private static final int ATLAS_ROWS = 3;
	private static final int ATLAS_W = CW * ATLAS_COLS;
	private static final int ATLAS_H = CH * ATLAS_ROWS;

	private static final int KIND_BROADLEAF = 0;
	private static final int KIND_CONIFER = 1;
	private static final int KIND_JUNGLE = 2;
	private static final int KIND_SAVANNA = 3;
	private static final int KIND_BAMBOO = 4;
	private static final int KIND_CORAL = 5;
	private static final int KIND_KELP = 6;
	private static final int KIND_MANGROVE = 7;
	private static final int KIND_BIRCH = 8;    // slim, pale, sparse canopy
	private static final int KIND_DARK_OAK = 9; // wide, dense, dark broad canopy
	private static final int KIND_CHERRY = 10;  // broad round canopy (kept PINK, not green-capped)
	private static final int[] CELL_X = { 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2 };
	private static final int[] CELL_Y = { 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2 };

	private static final int KELP_GREEN = (0x3f << 16) | (0x93 << 8) | 0x4c;

	private static final class RegionImposter {
		VertexBuffer buffer;
		int quads;
	}

	private static final Long2ObjectOpenHashMap<RegionImposter> REGIONS = new Long2ObjectOpenHashMap<>();
	private static final LongArrayList pending = new LongArrayList();
	private static final LongOpenHashSet ringKeys = new LongOpenHashSet();
	private static int pendingIndex;
	private static long lastPassMs;

	private static int atlasId = -1;
	private static boolean anchored;
	private static double originX;
	private static double originY;
	private static double originZ;
	// Pass-scoped culling constants (fixed for the duration of one drained rebuild pass).
	private static int passMinChunk;
	private static int passMaxChunk;
	private static int passCamChunkX;
	private static int passCamChunkZ;
	private static float passAmbR = 1.0F;
	private static float passAmbG = 1.0F;
	private static float passAmbB = 1.0F;
	private static boolean failed;

	private PauCTreeImposterRenderer() {
	}

	public static boolean enabled() {
		return PauCTunables.readBoolean(ENABLED_PROPERTY, true);
	}

	public static void reset() {
		for (RegionImposter ri : REGIONS.values()) {
			if (ri.buffer != null) {
				ri.buffer.close();
			}
		}
		REGIONS.clear();
		pending.clear();
		ringKeys.clear();
		pendingIndex = 0;
		anchored = false;
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
			if (atlasId < 0) {
				atlasId = bakeAtlas();
			}
			long now = System.currentTimeMillis();
			double distSq = cameraPos.distanceToSqr(originX, originY, originZ);
			// Re-anchor (fresh origin, drop all buffers) only on a BIG move — float precision stays fine and
			// small movement never forces a rebuild. Otherwise start a new drained pass on the interval.
			if (!anchored || distSq > REANCHOR_BLOCKS * REANCHOR_BLOCKS) {
				for (RegionImposter ri : REGIONS.values()) {
					if (ri.buffer != null) {
						ri.buffer.close();
					}
				}
				REGIONS.clear();
				originX = cameraPos.x;
				originY = cameraPos.y;
				originZ = cameraPos.z;
				anchored = true;
				startPass(minecraft);
			} else if (pending.isEmpty() && now - lastPassMs >= PASS_INTERVAL_MS) {
				startPass(minecraft);
			}
			if (!pending.isEmpty()) {
				drain(minecraft);
				if (pending.isEmpty()) {
					lastPassMs = now;
					removeStale();
				}
			}
			if (REGIONS.isEmpty()) {
				return;
			}
			ShaderInstance shader = GameRenderer.getPositionTexColorShader();
			if (shader == null) {
				return;
			}
			net.minecraft.client.renderer.culling.Frustum frustum = new net.minecraft.client.renderer.culling.Frustum(
				new org.joml.Matrix4f(poseStack.last().pose()), RenderSystem.getProjectionMatrix());
			frustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
			double worldMinY = minecraft.level.getMinBuildHeight();
			double worldMaxY = minecraft.level.getMaxBuildHeight();

			RenderSystem.setShaderTexture(0, atlasId);
			poseStack.pushPose();
			poseStack.translate(originX - cameraPos.x, originY - cameraPos.y, originZ - cameraPos.z);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(false);
			RenderSystem.disableCull();
			org.joml.Matrix4f pose = poseStack.last().pose();
			org.joml.Matrix4f proj = RenderSystem.getProjectionMatrix();
			for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<RegionImposter> e : REGIONS.long2ObjectEntrySet()) {
				RegionImposter ri = e.getValue();
				if (ri.buffer == null || ri.quads == 0) {
					continue;
				}
				long key = e.getLongKey();
				double rx = PauCSurfaceColumnStore.regionXFromKey(key) << PauCSurfaceColumnStore.REGION_SHIFT;
				double rz = PauCSurfaceColumnStore.regionZFromKey(key) << PauCSurfaceColumnStore.REGION_SHIFT;
				if (!frustum.isVisible(new net.minecraft.world.phys.AABB(
						rx, worldMinY, rz, rx + PauCSurfaceColumnStore.REGION_SIZE, worldMaxY, rz + PauCSurfaceColumnStore.REGION_SIZE))) {
					continue;
				}
				ri.buffer.bind();
				ri.buffer.drawWithShader(pose, proj, shader);
			}
			VertexBuffer.unbind();
			RenderSystem.enableCull();
			RenderSystem.depthMask(true);
			RenderSystem.disableBlend();
			poseStack.popPose();
		} catch (Throwable throwable) {
			failed = true;
			LOGGER.warn("PauC vegetation imposters failed; disabled for this session.", throwable);
		}
	}

	/** Begin a rebuild pass: snapshot the in-ring region keys and the cull constants; the drain fills them. */
	private static void startPass(Minecraft minecraft) {
		PauCSurfaceColumnStore store = PauCSurfaceSampler.store();
		int vanillaChunks = minecraft.options.getEffectiveRenderDistance();
		passMinChunk = vanillaChunks + 1;
		int lodMax = PauCSurfaceWitnessRenderer.lodRadiusChunks(vanillaChunks);
		float zw = Math.max(1.0F, (lodMax - passMinChunk) / 4.0F);
		passMaxChunk = (int) (passMinChunk + 3.0F * zw); // zone 4 (last quarter) = no imposters
		passCamChunkX = (int) Math.floor(minecraft.player.getX()) >> 4;
		passCamChunkZ = (int) Math.floor(minecraft.player.getZ()) >> 4;
		float[] ambient = PauCSurfaceWitnessRenderer.ambientLightFactors(minecraft);
		passAmbR = ambient[0];
		passAmbG = ambient[1];
		passAmbB = ambient[2];

		pending.clear();
		ringKeys.clear();
		pendingIndex = 0;
		int sh = PauCSurfaceColumnStore.REGION_SHIFT;
		for (long key : store.regionKeys()) {
			int regionChunkX = (PauCSurfaceColumnStore.regionXFromKey(key) << sh) >> 4;
			int regionChunkZ = (PauCSurfaceColumnStore.regionZFromKey(key) << sh) >> 4;
			int cheb = Math.max(Math.abs(regionChunkX + 2 - passCamChunkX), Math.abs(regionChunkZ + 2 - passCamChunkZ));
			if (cheb > passMaxChunk + 4) {
				continue;
			}
			pending.add(key);
			ringKeys.add(key);
		}
	}

	/** Build up to REGIONS_PER_FRAME regions from the pending queue this frame (fixed origin). */
	private static void drain(Minecraft minecraft) {
		PauCSurfaceColumnStore store = PauCSurfaceSampler.store();
		net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		int end = Math.min(pendingIndex + REGIONS_PER_FRAME, pending.size());
		for (; pendingIndex < end; pendingIndex++) {
			buildRegion(store, level, buffer, pending.getLong(pendingIndex));
		}
		if (pendingIndex >= pending.size()) {
			pending.clear();
			pendingIndex = 0;
		}
	}

	private static void buildRegion(PauCSurfaceColumnStore store, net.minecraft.client.multiplayer.ClientLevel level,
			BufferBuilder buffer, long key) {
		// Never float trees over ground the terrain hasn't rendered yet: only emit imposters for a region
		// whose terrain LOD mesh is actually drawn. As the terrain catches up, the next pass adds them.
		if (!PauCSurfaceWitnessRenderer.hasDrawnRegion(key)) {
			RegionImposter old = REGIONS.get(key);
			if (old != null) {
				if (old.buffer != null) {
					old.buffer.close();
				}
				REGIONS.remove(key);
			}
			return;
		}
		PauCSurfaceColumnStore.Region region = store.region(key);
		int sh = PauCSurfaceColumnStore.REGION_SHIFT;
		int rs = PauCSurfaceColumnStore.REGION_SIZE;
		int ms = PauCSurfaceColumnStore.MAX_SPANS;
		int baseX = PauCSurfaceColumnStore.regionXFromKey(key) << sh;
		int baseZ = PauCSurfaceColumnStore.regionZFromKey(key) << sh;

		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		int quads = 0;
		long cachedChunkKey = Long.MIN_VALUE;
		boolean cachedChunkLoaded = false;
		if (region != null) {
			for (int lz = 0; lz < rs; lz += TREE_CELL) {
				for (int lx = 0; lx < rs; lx += TREE_CELL) {
					int b = ((lz << sh) | lx) * ms;
					short s0 = region.spanY[b];
					if (s0 == Short.MIN_VALUE) {
						continue;
					}
					int c0 = region.spanColor[b];
					int a0 = c0 >>> 24;
					short s1 = region.spanY[b + 1];
					int c1 = region.spanColor[b + 1];
					int a1 = c1 >>> 24;

					int kind;
					float baseY;
					float height;
					int tintRGB;
					boolean warmVary;
					if (PauCSurfaceColumnStore.isTreeAlpha(a0)) {
						float ground = s1 != Short.MIN_VALUE ? (float) s1 : s0 - 6.0F;
						float rawHeight = (s0 + 1) - ground;
						if (rawHeight < 5.0F && a0 != PauCSurfaceColumnStore.BAMBOO_ALPHA) {
							// Ground BUSH (jungle floor shrubs, 1-2 leaf blocks on the ground): a billboard
							// here is a green smear ON the ground — "canopée qui touche le sol". The darkened
							// forest-floor triangle already colours the spot; no imposter.
							continue;
						}
						kind = kindForLandAlpha(a0);
						baseY = ground;
						height = Math.max(4.0F, rawHeight);
						int tr = (c0 >> 16) & 0xff;
						int tg = (c0 >> 8) & 0xff;
						int tb = c0 & 0xff;
						// CHERRY blossom is legitimately PINK (high R, low G) — the green-dominant cap below
						// would turn it green. Species tag exempts it so cherry groves read pink like DH.
						if (a0 != PauCSurfaceColumnStore.CHERRY_ALPHA) {
							// Other foliage must read GREEN-dominant: azalea/odd stored tints rendered as
							// VIOLET billboards ("parties sans texture"). Cap red/blue against green.
							tb = Math.min(tb, Math.max(0, tg - 8));
							tr = Math.min(tr, tg + 24);
						}
						tintRGB = (tr << 16) | (tg << 8) | tb;
						warmVary = a0 != PauCSurfaceColumnStore.CHERRY_ALPHA; // cherry keeps its uniform blossom tone
					} else if (a0 == PauCSurfaceColumnStore.WATER_ALPHA && s1 != Short.MIN_VALUE
							&& (a1 == PauCSurfaceColumnStore.CORAL_ALPHA || a1 == PauCSurfaceColumnStore.KELP_ALPHA)) {
						baseY = s1;
						if (a1 == PauCSurfaceColumnStore.CORAL_ALPHA) {
							kind = KIND_CORAL;
							float top = Math.min(s0, s1 + 5.0F);
							height = Math.max(3.0F, top - s1);
							tintRGB = c1 & 0xffffff;
						} else {
							kind = KIND_KELP;
							height = Math.max(3.0F, Math.min(24.0F, (s0 - 1) - s1));
							tintRGB = KELP_GREEN;
						}
						warmVary = false;
					} else {
						continue;
					}

					int worldX = baseX + lx;
					int worldZ = baseZ + lz;
					int chunkX = worldX >> 4;
					int chunkZ = worldZ >> 4;
					int dcx = chunkX - passCamChunkX;
					int dcz = chunkZ - passCamChunkZ;
					double radial = Math.sqrt((double) dcx * dcx + (double) dcz * dcz);
					if (radial < passMinChunk || radial > passMaxChunk) {
						continue;
					}
					long ckey = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
					boolean vanillaLoaded;
					if (ckey == cachedChunkKey) {
						vanillaLoaded = cachedChunkLoaded;
					} else {
						vanillaLoaded = level != null && level.getChunkSource().getChunk(chunkX, chunkZ, false) != null;
						cachedChunkKey = ckey;
						cachedChunkLoaded = vanillaLoaded;
					}
					if (vanillaLoaded) {
						continue;
					}

					float width = widthForKind(kind, height);
					float cx = (float) (worldX + 0.5 - originX);
					float cz = (float) (worldZ + 0.5 - originZ);
					float yb = (float) (baseY - originY);
					float yt = yb + height;

					int hsh = worldX * 374761393 + worldZ * 668265263;
					hsh = (hsh ^ (hsh >>> 13)) * 1274126177;
					// Scatter each tree within its cell (deterministic) so the forest is not a visible grid.
					cx += (((hsh >>> 3) & 0xff) / 255.0F - 0.5F) * 2.6F;
					cz += (((hsh >>> 11) & 0xff) / 255.0F - 0.5F) * 2.6F;
					float jb = 0.82F + 0.34F * (((hsh >>> 8) & 0xffff) / 65535.0F);
					float warm = warmVary ? ((((hsh >>> 24) & 0xff) / 255.0F) - 0.5F) * 0.20F : 0.0F;
					int r = Math.min(255, (int) (((tintRGB >> 16) & 0xff) * passAmbR * jb * (1.0F + warm)));
					int g = Math.min(255, (int) (((tintRGB >> 8) & 0xff) * passAmbG * jb));
					int bl = Math.min(255, (int) ((tintRGB & 0xff) * passAmbB * jb * (1.0F - warm)));
					// VANILLA CANOPY SHADING: real trees are lit on top and dark underneath (stacked AO +
					// the unlit inner leaves). One flat colour read as a paper cutout; a vertex gradient
					// (top = full, base = 0.62) restores the vanilla depth for free — same vertex count.
					int rB = (int) (r * 0.62F);
					int gB = (int) (g * 0.62F);
					int bB = (int) (bl * 0.62F);

					float u0 = CELL_X[kind] * (1.0F / ATLAS_COLS);
					float u1 = u0 + (1.0F / ATLAS_COLS);
					float v0 = CELL_Y[kind] * (1.0F / ATLAS_ROWS);
					float v1 = v0 + (1.0F / ATLAS_ROWS);
					float hw = width * 0.5F;
					buffer.vertex(cx - hw, yt, cz).uv(u0, v0).color(r, g, bl, 255).endVertex();
					buffer.vertex(cx - hw, yb, cz).uv(u0, v1).color(rB, gB, bB, 255).endVertex();
					buffer.vertex(cx + hw, yb, cz).uv(u1, v1).color(rB, gB, bB, 255).endVertex();
					buffer.vertex(cx + hw, yt, cz).uv(u1, v0).color(r, g, bl, 255).endVertex();
					buffer.vertex(cx, yt, cz - hw).uv(u0, v0).color(r, g, bl, 255).endVertex();
					buffer.vertex(cx, yb, cz - hw).uv(u0, v1).color(rB, gB, bB, 255).endVertex();
					buffer.vertex(cx, yb, cz + hw).uv(u1, v1).color(rB, gB, bB, 255).endVertex();
					buffer.vertex(cx, yt, cz + hw).uv(u1, v0).color(r, g, bl, 255).endVertex();
					quads += 2;
				}
			}
		}

		BufferBuilder.RenderedBuffer rendered = buffer.end();
		RegionImposter ri = REGIONS.get(key);
		if (quads == 0) {
			rendered.release();
			if (ri != null) { // region had billboards before, now none: drop it
				if (ri.buffer != null) {
					ri.buffer.close();
				}
				REGIONS.remove(key);
			}
			return;
		}
		if (ri == null) {
			ri = new RegionImposter();
			ri.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
			REGIONS.put(key, ri);
		}
		ri.buffer.bind();
		ri.buffer.upload(rendered);
		ri.quads = quads;
		VertexBuffer.unbind();
	}

	/** After a pass completes, drop regions that left the ring (free their GPU buffers). */
	private static void removeStale() {
		if (REGIONS.size() <= ringKeys.size()) {
			return;
		}
		var it = REGIONS.long2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			if (!ringKeys.contains(e.getLongKey())) {
				if (e.getValue().buffer != null) {
					e.getValue().buffer.close();
				}
				it.remove();
			}
		}
	}

	private static int kindForLandAlpha(int alpha) {
		if (alpha == PauCSurfaceColumnStore.CONIFER_ALPHA) {
			return KIND_CONIFER;
		}
		if (alpha == PauCSurfaceColumnStore.JUNGLE_ALPHA) {
			return KIND_JUNGLE;
		}
		if (alpha == PauCSurfaceColumnStore.SAVANNA_ALPHA) {
			return KIND_SAVANNA;
		}
		if (alpha == PauCSurfaceColumnStore.MANGROVE_ALPHA) {
			return KIND_MANGROVE;
		}
		if (alpha == PauCSurfaceColumnStore.BAMBOO_ALPHA) {
			return KIND_BAMBOO;
		}
		if (alpha == PauCSurfaceColumnStore.CHERRY_ALPHA) {
			return KIND_CHERRY;
		}
		if (alpha == PauCSurfaceColumnStore.BIRCH_ALPHA) {
			return KIND_BIRCH;
		}
		if (alpha == PauCSurfaceColumnStore.DARK_OAK_ALPHA) {
			return KIND_DARK_OAK;
		}
		return KIND_BROADLEAF;
	}

	private static float widthForKind(int kind, float height) {
		switch (kind) {
			case KIND_CONIFER:
				return clamp(3.0F, 7.0F, height * 0.5F);
			case KIND_JUNGLE:
				return clamp(5.0F, 10.0F, height * 0.78F);
			case KIND_SAVANNA:
				return clamp(5.0F, 11.0F, height * 0.9F);
			case KIND_BAMBOO:
				return clamp(2.0F, 5.0F, height * 0.35F);
			case KIND_CORAL:
				return clamp(3.0F, 7.0F, height * 0.9F);
			case KIND_KELP:
				return clamp(2.0F, 5.0F, 4.0F);
			case KIND_MANGROVE:
				return clamp(5.0F, 10.0F, height * 0.85F);
			case KIND_BIRCH:
				return clamp(3.0F, 7.0F, height * 0.52F);  // slim
			case KIND_DARK_OAK:
				return clamp(6.0F, 13.0F, height * 0.95F); // very wide, dense
			case KIND_CHERRY:
				return clamp(5.0F, 11.0F, height * 0.9F);  // broad round
			default:
				return clamp(4.0F, 9.0F, height * 0.7F);
		}
	}

	private static float clamp(float lo, float hi, float v) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static int bakeAtlas() {
		byte[] px = new byte[ATLAS_W * ATLAS_H * 4];
		for (int kind = 0; kind < CELL_X.length; kind++) {
			int ox = CELL_X[kind] * CW;
			int oy = CELL_Y[kind] * CH;
			for (int y = 0; y < CH; y++) {
				for (int x = 0; x < CW; x++) {
					int rgb = silhouette(kind, x, y);
					if (rgb < 0) {
						continue;
					}
					int i = ((oy + y) * ATLAS_W + (ox + x)) * 4;
					px[i] = (byte) ((rgb >> 16) & 0xff);
					px[i + 1] = (byte) ((rgb >> 8) & 0xff);
					px[i + 2] = (byte) (rgb & 0xff);
					px[i + 3] = (byte) 0xff;
				}
			}
		}
		ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(px.length);
		buf.put(px).flip();
		int id = GlStateManager._genTexture();
		RenderSystem.bindTexture(id);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, ATLAS_W, ATLAS_H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
		org.lwjgl.system.MemoryUtil.memFree(buf);
		LOGGER.info("PauC vegetation imposters: baked {}x{} silhouette atlas (id={}).", ATLAS_W, ATLAS_H, id);
		return id;
	}

	private static final int WHITE = 0xffffff;
	// Trunk texel: the billboard's VERTEX colour is the LEAF tint and MULTIPLIES the texture, so a
	// neutral grey-brown texel rendered GREENISH bark. Pre-compensate red-heavy: texel × typical green
	// foliage tint ≈ vanilla bark brown (oak tint (0.35,0.66,0.23) × this ≈ (66,44,11)).
	private static final int TRUNK = (0xBE << 16) | (0x42 << 8) | 0x30;

	private static int silhouette(int kind, int x, int y) {
		float fx = x + 0.5F;
		float fy = y + 0.5F;
		float midX = CW * 0.5F;
		switch (kind) {
			case KIND_BROADLEAF: {
				// Full round canopy covering the upper ~72% so it DRAWS OVER (hides) the trunk; only a short
				// bark stub shows below. Trunk is emitted FIRST so the canopy pixels win where they overlap.
				// VANILLA FIDELITY: the edge test runs on 3px-QUANTIZED coordinates, so the canopy outline
				// is stair-stepped leaf-block clumps (a vanilla oak is a blocky lump, not a smooth balloon).
				boolean trunk = y >= CH * 0.60F && Math.abs(fx - midX) <= 2.0F;
				float qx = (float) (Math.floor(x / 3.0) * 3.0) + 1.5F;
				float qy = (float) (Math.floor(y / 3.0) * 3.0) + 1.5F;
				float d = dist(qx, qy, midX, CH * 0.34F);
				if (y < CH * 0.72F && d < CW * 0.47F * (0.85F + 0.15F * (float) Math.sin(qx * 1.7F))) {
					return WHITE; // canopy over trunk
				}
				return trunk ? TRUNK : -1;
			}
			case KIND_CONIFER: {
				// Canopy stops at 78% — a bark gap below, so even conifers never drag foliage to the ground.
				float canopyBottom = CH * 0.78F;
				if (y < canopyBottom) {
					float t = y / canopyBottom;
					float hw = 1.5F + t * (CW * 0.46F - 1.5F);
					// VANILLA SPRUCE SKIRTS: each 6px tier FLARES downward then snaps back narrow — the
					// sawtooth silhouette of stacked spruce leaf rings, still a solid cone (no holes; the
					// old sparse sine-spikes read as white sea-urchins in snowy taiga).
					float saw = 0.62F + 0.38F * ((y % 6) / 5.0F);
					return Math.abs(fx - midX) <= hw * saw ? WHITE : -1;
				}
				return Math.abs(fx - midX) <= 2.0F ? TRUNK : -1;
			}
			case KIND_JUNGLE: {
				// Tall tree with a BROAD canopy filling the upper ~62% + a short bark trunk — leafy, not a
				// bare pole (the old small-canopy-on-long-trunk read as a forest of green spikes).
				if (y < CH * 0.62F) {
					float d = dist(fx, fy, midX, CH * 0.26F);
					if (d < CW * 0.48F * (0.82F + 0.18F * (float) Math.sin(x * 1.3F))) {
						return WHITE;
					}
				}
				return y >= CH * 0.62F && Math.abs(fx - midX) <= 2.2F ? TRUNK : -1;
			}
			case KIND_SAVANNA: {
				// Acacia: the crown band was 6%..22% of the cell — at distance it shrank to 1-2 screen
				// pixels while the 78%-tall trunk column dominated, reading as a "forêt de bâtons"
				// (session 07-19). Real acacias are crown-dominant: a broad flat-topped crown over the
				// upper ~third, THEN the bare trunk.
				if (y >= CH * 0.04F && y < CH * 0.34F) {
					float band = (y - CH * 0.04F) / (CH * 0.30F);
					// widens quickly, stays broad, thins slightly at the crown base (umbrella profile)
					float profile = band < 0.35F ? 0.60F + 1.14F * band : 1.0F - 0.25F * (band - 0.35F);
					float hw = CW * 0.48F * profile;
					if (Math.abs(fx - midX) <= hw) {
						return WHITE;
					}
				}
				return y >= CH * 0.34F && Math.abs(fx - midX) <= 1.5F ? TRUNK : -1;
			}
			case KIND_BAMBOO: {
				for (float sx : new float[] { CW * 0.32F, CW * 0.50F, CW * 0.68F }) {
					if (Math.abs(fx - sx) <= 1.3F) {
						return WHITE;
					}
					if (Math.abs(fx - sx) <= 3.0F && ((y % 9) == 3)) {
						return WHITE;
					}
				}
				return -1;
			}
			case KIND_CORAL: {
				if (y >= CH * 0.35F && Math.abs(fx - midX) <= 2.2F) {
					return WHITE;
				}
				float up = (CH - fy);
				float armR = midX + (up - CH * 0.35F) * 0.55F;
				float armL = midX - (up - CH * 0.35F) * 0.55F;
				if (y >= CH * 0.20F && y < CH * 0.66F) {
					if (Math.abs(fx - armR) <= 1.8F || Math.abs(fx - armL) <= 1.8F) {
						return WHITE;
					}
				}
				if (dist(fx, fy, armR, CH * 0.22F) < 2.6F || dist(fx, fy, armL, CH * 0.22F) < 2.6F
					|| dist(fx, fy, midX, CH * 0.34F) < 2.8F) {
					return WHITE;
				}
				return -1;
			}
			case KIND_MANGROVE: {
				// Broad canopy + STILT ROOTS fanning to the ground — the mangrove signature.
				if (y < CH * 0.55F) {
					float d = dist(fx, fy, midX, CH * 0.28F);
					if (d < CW * 0.48F * (0.84F + 0.16F * (float) Math.sin(x * 1.6F))) {
						return WHITE;
					}
					return -1;
				}
				if (y < CH * 0.62F) {
					return Math.abs(fx - midX) <= 2.0F ? TRUNK : -1;
				}
				float t = (fy - CH * 0.62F) / (CH * 0.38F);
				for (float off : new float[] { -7.0F, 0.0F, 7.0F }) {
					if (Math.abs(fx - (midX + off * t)) <= 1.4F) {
						return TRUNK;
					}
				}
				return -1;
			}
			case KIND_KELP: {
				for (float o : new float[] { -5.0F, 0.0F, 5.0F }) {
					float strandX = midX + o + (float) Math.sin(y * 0.35F + o) * 3.0F;
					if (Math.abs(fx - strandX) <= 1.4F) {
						return WHITE;
					}
				}
				return -1;
			}
			case KIND_BIRCH: {
				// Slim, tall, lightly sparse canopy over the upper ~68% + a thin trunk stub.
				boolean trunk = y >= CH * 0.62F && Math.abs(fx - midX) <= 1.3F;
				float d = dist(fx, fy, midX, CH * 0.30F);
				if (y < CH * 0.68F && d < CW * 0.33F * (0.82F + 0.18F * (float) Math.sin(x * 2.1F))) {
					return WHITE;
				}
				return trunk ? TRUNK : -1;
			}
			case KIND_DARK_OAK: {
				// Very WIDE, dense, round canopy filling the upper ~70% over a short thick trunk.
				boolean trunk = y >= CH * 0.66F && Math.abs(fx - midX) <= 2.5F;
				float d = dist(fx, fy, midX, CH * 0.34F);
				if (y < CH * 0.70F && d < CW * 0.50F * (0.90F + 0.10F * (float) Math.sin(x * 1.4F))) {
					return WHITE;
				}
				return trunk ? TRUNK : -1;
			}
			case KIND_CHERRY: {
				// Broad, full, round blossom canopy (upper ~72%) — reads PINK from the vertex tint.
				boolean trunk = y >= CH * 0.64F && Math.abs(fx - midX) <= 2.0F;
				float d = dist(fx, fy, midX, CH * 0.32F);
				if (y < CH * 0.72F && d < CW * 0.49F * (0.88F + 0.12F * (float) Math.sin(x * 1.6F))) {
					return WHITE;
				}
				return trunk ? TRUNK : -1;
			}
			default:
				return -1;
		}
	}

	private static float dist(float ax, float ay, float bx, float by) {
		float dx = ax - bx;
		float dy = ay - by;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}
}
