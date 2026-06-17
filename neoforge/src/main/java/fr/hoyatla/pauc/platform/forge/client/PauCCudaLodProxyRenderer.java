package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCCudaLodProxyRenderer {
	private static final String ENABLED_PROPERTY = "pauc.lod.cuda.proxyTerrainRenderer";
	private static final String INDIRECT_ENABLED_PROPERTY = "pauc.lod.cuda.proxyIndirectRenderer";
	private static final String FORCE_RENDER_PROPERTY = "pauc.lod.cuda.proxyForceRender";
	private static final String SHADER_ENABLED_PROPERTY = "pauc.lod.cuda.proxyTerrainShaderFallback";
	private static final String SHADERLESS_ENABLED_PROPERTY = "pauc.lod.cuda.proxyTerrainShaderlessCoarseFill";
	private static final String MAX_CELLS_PROPERTY = "pauc.lod.cuda.proxyTerrainMaxCells";
	private static final String CACHE_MS_PROPERTY = "pauc.lod.cuda.proxyTerrainSnapshotMs";
	private static final String RETAIN_MS_PROPERTY = "pauc.lod.cuda.proxyTerrainRetainMs";
	private static final String RENDER_FLUID_CELLS_PROPERTY = "pauc.lod.cuda.proxyTerrainRenderFluidCells";
	private static final String SHADER_RENDER_FLUID_CELLS_PROPERTY = "pauc.lod.cuda.proxyTerrainShaderRenderFluidCells";
	private static final String SHADERLESS_SEA_LEVEL_GUARD_PROPERTY = "pauc.lod.cuda.proxyTerrainShaderlessSeaLevelGuard";
	private static final String Y_BIAS_BLOCKS_PROPERTY = "pauc.lod.cuda.proxyTerrainYBiasBlocks";
	private static final String FLUID_DROP_BLOCKS_PROPERTY = "pauc.lod.cuda.proxyTerrainFluidDropBlocks";
	private static final String LOG_INTERVAL_MS_PROPERTY = "pauc.lod.cuda.proxyTerrainLogIntervalMs";
	private static final Map<Long, Long> SEEN_CELL_MILLIS = new ConcurrentHashMap<>();
	private static volatile List<PauCClientFrontierWarmupManager.ProxyRenderCell> cachedCells = List.of();
	private static volatile long cachedAtMillis;
	private static volatile int lastCandidateCells;
	private static volatile int lastDrawnCells;
	private static volatile int lastSkippedCells;
	private static volatile String lastMode = "not-run";
	private static volatile String lastReason = "-";
	private static volatile long lastLogAtMillis;
	private static List<PauCClientFrontierWarmupManager.ProxyRenderCell> lastUploadedCellsRef;
	private static double indirectAnchorX;
	private static double indirectAnchorZ;
	private static int indirectUploadedCells;

	private PauCCudaLodProxyRenderer() {
	}

	public static void render(ClientLevel level, Camera camera, com.mojang.blaze3d.vertex.PoseStack poseStack, String passName) {
		if (!shouldRender(passName)) {
			lastDrawnCells = 0;
			return;
		}
		if (level == null || camera == null) {
			lastReason = "no-level";
			lastDrawnCells = 0;
			return;
		}

		int maxCells = maxCells();
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> cells = proxyCells(level, maxCells);
		lastCandidateCells = cells.size();
		if (cells.isEmpty()) {
			lastReason = "no-ready-cells";
			lastDrawnCells = 0;
			logIfNeeded();
			return;
		}

		double cameraX = camera.getPosition().x;
		double cameraY = camera.getPosition().y;
		double cameraZ = camera.getPosition().z;
		boolean shaderFallback = PauCLodShaderContext.isShaderPackInUse() && PauCLodShaderContext.isFallbackActive();
		Matrix4f matrix = poseStack.last().pose();

		// GPU-driven MDI path (opt-in). Falls through to the BufferBuilder path on any failure.
		if (readBoolean(INDIRECT_ENABLED_PROPERTY, false)
			&& PauCGpuLodIndirectRenderer.ensureInitialized()
			&& renderIndirect(cells, cameraX, cameraY, cameraZ, shaderFallback, matrix)) {
			return;
		}

		BufferBuilder builder = Tesselator.getInstance().getBuilder();

		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
		RenderSystem.disableCull();

		int drawn = 0;
		int skipped = 0;
		builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		for (PauCClientFrontierWarmupManager.ProxyRenderCell cell : cells) {
			if (cell.hasFluid() && !shouldRenderFluidCells(shaderFallback)) {
				skipped++;
				continue;
			}
			if (!Float.isFinite(cell.terrainSectionY())) {
				skipped++;
				continue;
			}
			if (!shaderFallback && shouldSkipShaderlessSeaLevelPlate(cell)) {
				skipped++;
				continue;
			}
			float y = (cell.terrainSectionY() * 16.0F + 8.0F + yBiasBlocks() - fluidDropBlocks(cell)) - (float) cameraY;
			if (y < -512.0F || y > 512.0F) {
				skipped++;
				continue;
			}
			float x0 = (float) (cell.chunkX() * 16.0D - cameraX);
			float z0 = (float) (cell.chunkZ() * 16.0D - cameraZ);
			float x1 = x0 + 16.0F;
			float z1 = z0 + 16.0F;
			int[] color = colorFor(cell, shaderFallback);
			quad(builder, matrix, x0, y, z0, x1, z1, color[0], color[1], color[2], color[3]);
			drawn++;
		}
		if (drawn > 0) {
			Tesselator.getInstance().end();
		} else {
			builder.end().release();
		}

		RenderSystem.enableCull();
		lastDrawnCells = drawn;
		lastSkippedCells = skipped;
		lastReason = drawn > 0 ? "drawn" : "empty-after-filter";
		logIfNeeded();
	}

	/**
	 * GPU-driven path: pack the cells into the indirect renderer's instance buffer (only when the cell set
	 * changes) and issue a single glMultiDrawElementsIndirect each frame. Positions are stored relative to an
	 * anchor chosen at pack time; the per-frame camera offset turns them camera-relative without re-uploading.
	 * Returns false on any failure so the caller falls back to the BufferBuilder path.
	 */
	private static boolean renderIndirect(
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> cells,
		double cameraX,
		double cameraY,
		double cameraZ,
		boolean shaderFallback,
		Matrix4f poseMatrix
	) {
		if (cells != lastUploadedCellsRef) {
			double anchorX = Math.floor(cameraX);
			double anchorZ = Math.floor(cameraZ);
			ByteBuffer buffer = MemoryUtil.memAlloc(cells.size() * PauCGpuLodIndirectRenderer.cellStrideBytes());
			int packed = 0;
			int skipped = 0;
			try {
				for (PauCClientFrontierWarmupManager.ProxyRenderCell cell : cells) {
					if (cell.hasFluid() && !shouldRenderFluidCells(shaderFallback)) {
						skipped++;
						continue;
					}
					if (!Float.isFinite(cell.terrainSectionY())) {
						skipped++;
						continue;
					}
					if (!shaderFallback && shouldSkipShaderlessSeaLevelPlate(cell)) {
						skipped++;
						continue;
					}
					float worldY = cell.terrainSectionY() * 16.0F + 8.0F + yBiasBlocks() - fluidDropBlocks(cell);
					float relYToCamera = worldY - (float) cameraY;
					if (relYToCamera < -512.0F || relYToCamera > 512.0F) {
						skipped++;
						continue;
					}
					float relX = (float) (cell.chunkX() * 16.0D - anchorX);
					float relZ = (float) (cell.chunkZ() * 16.0D - anchorZ);
					int[] color = colorFor(cell, shaderFallback);
					int rgba = (color[0] << 24) | (color[1] << 16) | (color[2] << 8) | (color[3] & 0xFF);
					buffer.putFloat(relX).putFloat(worldY).putFloat(relZ).putInt(rgba);
					packed++;
				}
				buffer.flip();
				if (!PauCGpuLodIndirectRenderer.uploadCells(buffer, packed)) {
					lastReason = "indirect-upload-failed";
					return false;
				}
			} finally {
				MemoryUtil.memFree(buffer);
			}
			lastUploadedCellsRef = cells;
			indirectAnchorX = anchorX;
			indirectAnchorZ = anchorZ;
			indirectUploadedCells = packed;
			lastSkippedCells = skipped;
		}

		if (indirectUploadedCells <= 0) {
			lastDrawnCells = 0;
			lastReason = "indirect-empty-after-filter";
			logIfNeeded();
			return true;
		}

		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseMatrix);
		Matrix4f projection = RenderSystem.getProjectionMatrix();

		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
		RenderSystem.disableCull();
		boolean drew = PauCGpuLodIndirectRenderer.draw(
			projection,
			modelView,
			(float) (indirectAnchorX - cameraX),
			(float) (0.0D - cameraY),
			(float) (indirectAnchorZ - cameraZ)
		);
		RenderSystem.enableCull();
		if (!drew) {
			lastReason = "indirect-draw-failed";
			return false;
		}
		lastDrawnCells = indirectUploadedCells;
		lastReason = "indirect-mdi";
		logIfNeeded();
		return true;
	}

	public static String describeState() {
		return "cudaProxyRender[mode="
			+ lastMode
			+ ", candidates="
			+ lastCandidateCells
			+ ", drawn="
			+ lastDrawnCells
			+ ", skipped="
			+ lastSkippedCells
			+ ", cached="
			+ cachedCells.size()
			+ ", reason="
			+ lastReason
			+ "]";
	}

	private static boolean shouldRender(String passName) {
		if (!readBoolean(ENABLED_PROPERTY, true)) {
			lastMode = "disabled";
			lastReason = "disabled";
			return false;
		}
		PauCLodRange range = PauCLodHorizonState.currentRange();
		if (range == null || !range.enabled()) {
			lastMode = "range-off";
			lastReason = "range-off";
			return false;
		}
		// Dev/test override: force the proxy to render regardless of coarse-fill/PL-visible state, so the
		// GPU MDI path can be observed and measured even while DH is covering. Default off.
		if (readBoolean(FORCE_RENDER_PROPERTY, false)) {
			lastMode = "forced:" + passName;
			lastReason = "forced-test";
			return true;
		}
		boolean shaderFallback = PauCLodShaderContext.isShaderPackInUse() && PauCLodShaderContext.isFallbackActive();
		if (shaderFallback) {
			lastMode = "shader-fallback:" + passName;
			lastReason = "shader-proxy-off-by-default";
			return readBoolean(SHADER_ENABLED_PROPERTY, false);
		}
		boolean coarseFill = PauCClientFrontierWarmupManager.shouldPreferCoarseFill();
		if (coarseFill) {
			lastMode = "shaderless-coarse:" + passName;
			lastReason = "shaderless-proxy-off-by-default";
			return readBoolean(SHADERLESS_ENABLED_PROPERTY, false);
		}
		lastMode = "standby:" + passName;
		lastReason = "pl-visible";
		return false;
	}

	private static List<PauCClientFrontierWarmupManager.ProxyRenderCell> proxyCells(ClientLevel level, int maxCells) {
		long now = System.currentTimeMillis();
		long cacheMs = readLong(CACHE_MS_PROPERTY, 200L, 0L, 2_000L);
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> cells = cachedCells;
		if (!cells.isEmpty() && now - cachedAtMillis <= cacheMs) {
			return cells;
		}
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> refreshed = PauCClientFrontierWarmupManager.collectProxyRenderCells(level, maxCells);
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> stabilized = stabilizeCells(refreshed, cells, maxCells, now);
		cachedCells = stabilized;
		cachedAtMillis = now;
		return stabilized;
	}

	private static List<PauCClientFrontierWarmupManager.ProxyRenderCell> stabilizeCells(
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> refreshed,
		List<PauCClientFrontierWarmupManager.ProxyRenderCell> previous,
		int maxCells,
		long now
	) {
		long retainMs = readLong(RETAIN_MS_PROPERTY, 6_000L, 0L, 30_000L);
		if (retainMs <= 0L || previous.isEmpty()) {
			for (PauCClientFrontierWarmupManager.ProxyRenderCell cell : refreshed) {
				SEEN_CELL_MILLIS.put(cellKey(cell), now);
			}
			return refreshed;
		}

		SEEN_CELL_MILLIS.entrySet().removeIf(entry -> now - entry.getValue() > retainMs);
		LinkedHashMap<Long, PauCClientFrontierWarmupManager.ProxyRenderCell> merged = new LinkedHashMap<>(Math.min(maxCells, refreshed.size() + previous.size()));
		for (PauCClientFrontierWarmupManager.ProxyRenderCell cell : refreshed) {
			long key = cellKey(cell);
			SEEN_CELL_MILLIS.put(key, now);
			merged.put(key, cell);
			if (merged.size() >= maxCells) {
				return new ArrayList<>(merged.values());
			}
		}
		for (PauCClientFrontierWarmupManager.ProxyRenderCell cell : previous) {
			long key = cellKey(cell);
			Long seenAt = SEEN_CELL_MILLIS.get(key);
			if (seenAt == null || now - seenAt > retainMs) {
				continue;
			}
			merged.putIfAbsent(key, cell);
			if (merged.size() >= maxCells) {
				break;
			}
		}
		return new ArrayList<>(merged.values());
	}

	private static void quad(BufferBuilder builder, Matrix4f matrix, float x0, float y, float z0, float x1, float z1, int r, int g, int b, int a) {
		builder.vertex(matrix, x1, y, z0).color(r, g, b, a).endVertex();
		builder.vertex(matrix, x0, y, z0).color(r, g, b, a).endVertex();
		builder.vertex(matrix, x0, y, z1).color(r, g, b, a).endVertex();
		builder.vertex(matrix, x1, y, z1).color(r, g, b, a).endVertex();
	}

	private static int[] colorFor(PauCClientFrontierWarmupManager.ProxyRenderCell cell, boolean shaderFallback) {
		if (cell.hasFluid()) {
			return shaderFallback
				? new int[] { 58, 68, 62, 255 }
				: new int[] { 48, 58, 54, 255 };
		}
		float blockY = cell.terrainSectionY() * 16.0F + 8.0F;
		if (blockY > 112.0F) {
			return new int[] { 104, 106, 98, 255 };
		}
		if (cell.hasStructure()) {
			return new int[] { 102, 94, 82, 255 };
		}
		if (cell.qualityTier() <= PauCClientFrontierWarmupManager.LOD_QUALITY_COARSE) {
			return new int[] { 76, 88, 70, 255 };
		}
		return new int[] { 84, 98, 76, 255 };
	}

	private static int maxCells() {
		boolean shaderFallback = PauCLodShaderContext.isShaderPackInUse() && PauCLodShaderContext.isFallbackActive();
		int fallback = shaderFallback ? 12288 : 6144;
		return readInt(MAX_CELLS_PROPERTY, fallback, 64, 16384);
	}

	private static boolean shouldRenderFluidCells(boolean shaderFallback) {
		if (shaderFallback) {
			return readBoolean(SHADER_RENDER_FLUID_CELLS_PROPERTY, true);
		}
		return readBoolean(RENDER_FLUID_CELLS_PROPERTY, false);
	}

	private static float yBiasBlocks() {
		return readFloat(Y_BIAS_BLOCKS_PROPERTY, -3.0F, -16.0F, 8.0F);
	}

	private static float fluidDropBlocks(PauCClientFrontierWarmupManager.ProxyRenderCell cell) {
		return cell.hasFluid() ? readFloat(FLUID_DROP_BLOCKS_PROPERTY, 6.0F, 0.0F, 24.0F) : 0.0F;
	}

	private static boolean shouldSkipShaderlessSeaLevelPlate(PauCClientFrontierWarmupManager.ProxyRenderCell cell) {
		if (!readBoolean(SHADERLESS_SEA_LEVEL_GUARD_PROPERTY, true) || cell.hasStructure()) {
			return false;
		}
		float blockY = cell.terrainSectionY() * 16.0F + 8.0F;
		return blockY >= 48.0F && blockY <= 72.0F;
	}

	private static long cellKey(PauCClientFrontierWarmupManager.ProxyRenderCell cell) {
		return (((long) cell.chunkX()) << 32) ^ (cell.chunkZ() & 0xFFFFFFFFL);
	}

	private static void logIfNeeded() {
		long now = System.currentTimeMillis();
		long interval = readLong(LOG_INTERVAL_MS_PROPERTY, 5_000L, 1_000L, 60_000L);
		if (now - lastLogAtMillis < interval) {
			return;
		}
		lastLogAtMillis = now;
		com.mojang.logging.LogUtils.getLogger().info("PauC CUDA proxy terrain renderer: {}.", describeState());
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static long readLong(String key, long fallback, long min, long max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Long.parseLong(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Float.parseFloat(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}
}
