package fr.hoyatla.pauc.lodengine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LOD engine renderer: draws the PauC surface store BEYOND the vanilla render distance — terrain drawn
 * entirely by PauC's own engine, no Distant Horizons involved.
 *
 * <p>Meshing is BLOCKY (flat tile tops + vertical walls, Minecraft's cubic look) across 4 detail
 * bands (1/2/4/8-block tiles) whose edges are percentages of the LOD span, so they rescale with the
 * video-settings render distance. Detail pass: dirt-sided soil tiles, dirt→stone strata on deep
 * walls, depth-tinted water with dark walls, per-tile tone jitter, vertical AO gradient, standalone
 * tree models (1x1 trunk, 3x3 leaf slab + 1 cap — the classic silhouette), and block-light emissive
 * (torches/lava glow at night). Unloaded vanilla chunks are FILLED with LOD tiles so the map never
 * shows holes while chunks stream in.</p>
 *
 * <p>The mesh is built ASYNCHRONOUSLY on a dedicated daemon thread from an immutable region-map
 * snapshot (the render thread only snapshots, uploads and draws) — rebuilding ~300k quads on the
 * render thread froze the game on every chunk crossing. The persistent {@link VertexBuffer} pattern
 * itself is unchanged (the per-frame immediate-mode approach crashed with the vendored vertex-format
 * mixins — see history).</p>
 */
public final class PauCSurfaceWitnessRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lodengine.witnessRenderer";
	private static final String RADIUS_CHUNKS_PROPERTY = "pauc.lodengine.witnessRadiusChunks";
	private static final String MAX_QUADS_PROPERTY = "pauc.lodengine.witnessMaxQuads";
	private static final String WORLD_ALIGNED_GRID_PROPERTY = "pauc.lodengine.worldAlignedGrid"; // P1
	private static final String GRID_BAND_WIDTH_PROPERTY = "pauc.lodengine.gridBandWidth"; // P1: chunks per detail step
	private static final PauCLodSectionGrid SECTION_GRID = new PauCLodSectionGrid(); // P1: world-aligned section levels
	private static boolean gridEngagedLogged; // P1: one-shot confirmation that the world-aligned path ran
	// VALIDATED IN-GAME 2026-07-21: world-aligned grid kills the whole-map reload on movement. Default ON.
	// Set -Dpauc.lodengine.worldAlignedGrid=false to fall back to the legacy player-relative ring rebuild.
	private static final boolean WORLD_ALIGNED_GRID_DEFAULT = true;
	private static final String SKIRT_DEPTH_PROPERTY = "pauc.lodengine.witnessSkirtDepth";
	private static final String FOG_WIDTH_PROPERTY = "pauc.lodengine.fogWidthChunks";
	private static final String INNER_FOG_PROPERTY = "pauc.lodengine.innerFogChunks";
	private static final String DYNAMIC_LIGHT_PROPERTY = "pauc.lodengine.dynamicLight";
	private static final String ROUND_HORIZON_PROPERTY = "pauc.lodengine.roundHorizon";
	private static final String RADIUS_MULTIPLIER_PROPERTY = "pauc.lodengine.lodRadiusMultiplier";
	// Quality rings have EQUAL, FIXED widths (default 8 chunks each), INDEPENDENT of the chosen LOD
	// distance: rings 1/2/4 blocks are always the same size, only the cheap 8-block far band grows with
	// the gauge — so raising the LOD distance never inflates the fine-ring cost. When the gauge is too
	// small for three full rings they compress but stay equal to each other.
	private static final String BAND_WIDTH_PROPERTY = "pauc.lodengine.lodBandWidthChunks";
	private static final String FILL_VANILLA_HOLES_PROPERTY = "pauc.lodengine.fillUnloadedVanillaChunks";

	private static final String TRIANGLE_FAR_PROPERTY = "pauc.lodengine.triangleFar";
	private static final String REFINE_PIXELS_PROPERTY = "pauc.lodengine.refinePixels";
	// NEAR-VANILLA pass (Z1 blocky): vertex AO (vanilla smooth-lighting look), raw sampled colours
	// (no per-block jitter next to the real chunks), greedy flat-run merging (pure efficiency: the
	// merged quads are pixel-identical to the per-block ones, only fewer).
	private static final String NEAR_AO_PROPERTY = "pauc.lodengine.nearVertexAo";
	private static final String NEAR_PURE_COLORS_PROPERTY = "pauc.lodengine.nearPureColors";
	private static final String NEAR_GREEDY_PROPERTY = "pauc.lodengine.nearGreedyMerge";
	// Unified with the video-settings toggle (07-20 audit): the option wrote pauc.client.biomeBlend
	// while the mesher read pauc.lodengine.biomeBlend → the toggle was DEAD. Same key now = live.
	private static final String BIOME_BLEND_PROPERTY = "pauc.client.biomeBlend";
	private static final String WATER_SEAM_SHADE_PROPERTY = "pauc.lodengine.waterSeamShade";
	private static final String NEAR_VANILLA_PASS_PROPERTY = "pauc.lodengine.nearVanillaPass";
	private static final int CELL = 8; // coarsest tile edge; band-selection granularity (always within one chunk)
	private static final float SEAM_FADE_CHUNKS = 3.0F; // width of the vanilla->LOD opacity fade ring
	private static final long REBUILD_INTERVAL_MS = 600L;
	// Data-only rebuilds (store revision bumps) are throttled much harder: during generation the
	// revision changes CONTINUOUSLY, and rebuilding+uploading ~20MB every 600ms was the loading-time
	// stutter. Camera-driven rebuilds (movement/light/gauge) stay at the fast interval.
	private static final long DATA_REBUILD_INTERVAL_MS = 2_500L;
	private static final double REBUILD_MOVE_BLOCKS = 8.0D;
	// Mushroom-tree walls (dense forest): leaf band on top, bark only on DEEP walls.
	private static final int TREE_CANOPY_WALL_BLOCKS = 4;
	private static final int TREE_TRUNK_MIN_WALL_BLOCKS = 8;
	// Standalone tree model (isolated tree tiles, bands >= 4 blocks): 1x1 trunk, 3x3 leaf slab, 1x1 cap.
	private static final int TREE_MODEL_MIN_DROP = 4;
	// Soil strata: dirt band under the grass top, stone below that (real Minecraft stratigraphy).
	private static final int SOIL_DIRT_BAND_BLOCKS = 4;
	// Neighbour sentinel: the neighbour column is VOID (no terrain at all — End void, floating islands,
	// structures in the air). Distinct from "not drawn" (MIN_VALUE): a void edge gets a SHORT skirt and
	// a bottom cap so floaters read as slabs, not 160-block columns hanging into the void.
	private static final short VOID_NEIGHBOUR = Short.MIN_VALUE + 1;
	private static final int FLOAT_SKIRT_BLOCKS = 24;
	// Grace period after a vanilla chunk loads before its LOD fill retires (Sodium mesh build time).
	private static final long LOADED_GRACE_MS = 4_000L; // 1.2s undershot real meshing latency under load — the fill vanished before vanilla drew
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap loadedSince = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	// ---- render-thread state ----
	// Shared origin for the whole drawn region set (positions are relative to it; the poseStack
	// translate maps origin->camera each frame). All region meshes in drawnMeshes share this origin.
	private static double originX;
	private static double originY;
	private static double originZ;
	private static long builtRevision = -1L;
	private static long lastSubmitMs;
	private static int builtCameraChunkX = Integer.MIN_VALUE;
	private static int builtCameraChunkZ = Integer.MIN_VALUE;
	private static int builtMinChunkDistance;
	private static int builtMaxChunkDistance;
	private static int builtQuads;
	private static float builtNight = -1.0F;
	private static long lastQuadLogMs;
	private static boolean firstDrawLogged;
	private static boolean renderFailureLogged;
	// Fog band of the LAST SUBMITTED job (blocks) — read by the vanilla-fog extension.
	private static volatile float fogStartChunksShared;
	private static volatile float fogEndChunksShared;
	// Trunk/dirt/stone colours resolved once from the block textures (render thread, atlas loaded).
	private static final PauCBlockColorCache MATERIAL_CACHE = new PauCBlockColorCache();
	private static float trunkR = 109.0F;
	private static float trunkG = 87.0F;
	private static float trunkB = 53.0F;
	private static float dirtR = 134.0F;
	private static float dirtG = 96.0F;
	private static float dirtB = 67.0F;
	private static float stoneR = 126.0F;
	private static float stoneG = 126.0F;
	private static float stoneB = 126.0F;
	private static boolean materialsResolved;

	// ---- parallel per-region meshing ----
	// The mesh is partitioned by STORE REGION (64x64 columns). Each region meshes INDEPENDENTLY into
	// its own small pair of VBOs (opaque + translucent water) on a POOL of worker threads. Two paths:
	//   * FULL rebuild (camera crossed a chunk / light / radius change): every in-radius region is
	//     re-meshed against a fresh camera snapshot into a STAGING map, swapped in wholesale when it
	//     completes (one shared origin -> no position mismatch). Old meshes stay drawn until then.
	//   * INCREMENTAL (generation produced new/changed data, camera still): ONLY the changed regions
	//     re-mesh, in place, against the current snapshot -> new terrain appears within one pool cycle
	//     instead of waiting for a whole-world rebuild. This is what makes near detail "update live".
	private static final int MESH_POOL_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
	private static final ExecutorService MESH_POOL = Executors.newFixedThreadPool(MESH_POOL_THREADS, runnable -> {
		Thread thread = new Thread(runnable, "PauC-LodEngine-Mesh");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	/** Vertex writers + emission state — POOLED, never reused until the render thread has uploaded. */
	private static final class BuildCtx {
		final BufferBuilder main = new BufferBuilder(2 * 1024 * 1024);
		final BufferBuilder water = new BufferBuilder(512 * 1024);
		BufferBuilder emitTarget;
		int emitAlpha = 255;
		int waterQuads;
	}
	// A BufferBuilder's RenderedBuffer points INTO the builder's native ByteBuffer. If a worker reused
	// its builder for the next region (begin()) before the render thread uploaded the previous
	// RenderedBuffer, that pending buffer's memory was overwritten -> the GL upload read freed native
	// memory -> EXCEPTION_ACCESS_VIOLATION in the driver. So contexts are POOLED with a hard handoff:
	// a worker BORROWS a ctx (blocks if none free = natural backpressure), builds, hands it to the
	// render thread; the render thread uploads, then RETURNS the ctx. A ctx is never re-begun while its
	// RenderedBuffer is still in flight. Pool size = pool threads + slack for a few pending uploads.
	private static final int CTX_POOL_SIZE = MESH_POOL_THREADS + 4;
	private static final java.util.concurrent.ArrayBlockingQueue<BuildCtx> CTX_POOL =
		new java.util.concurrent.ArrayBlockingQueue<>(CTX_POOL_SIZE);
	static {
		for (int i = 0; i < CTX_POOL_SIZE; i++) {
			CTX_POOL.add(new BuildCtx());
		}
	}
	/** One region's uploaded GPU mesh (render thread only). */
	private static final class RegionMesh {
		VertexBuffer opaque;
		VertexBuffer water;
		int quads;
		int waterQuads;
		long dataRevision;
		net.minecraft.world.phys.AABB bounds; // frustum bounds cached once, reused every frame (no per-frame alloc)
		void close() {
			if (opaque != null) { opaque.close(); opaque = null; }
			if (water != null) { water.close(); water = null; }
		}
	}
	/** A finished region build handed back to the render thread for GPU upload. */
	private static final class RegionResult {
		final long regionKey;
		final int stamp;
		final boolean incremental;
		final BufferBuilder.RenderedBuffer opaque;
		final BufferBuilder.RenderedBuffer water;
		final int quads;
		final int waterQuads;
		final long dataRevision;
		final BuildCtx ctx; // the borrowed context to return to the pool after upload/dispose
		RegionResult(long regionKey, int stamp, boolean incremental, BufferBuilder.RenderedBuffer opaque,
				BufferBuilder.RenderedBuffer water, int quads, int waterQuads, long dataRevision, BuildCtx ctx) {
			this.regionKey = regionKey;
			this.stamp = stamp;
			this.incremental = incremental;
			this.opaque = opaque;
			this.water = water;
			this.quads = quads;
			this.waterQuads = waterQuads;
			this.dataRevision = dataRevision;
			this.ctx = ctx;
		}
	}
	private static final ConcurrentLinkedQueue<RegionResult> RESULTS = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger inFlight = new AtomicInteger();
	// Currently displayed region meshes (render thread only) — all built against originX/Y/Z.
	private static Long2ObjectOpenHashMap<RegionMesh> drawnMeshes = new Long2ObjectOpenHashMap<>();
	// Reused per-frame list of frustum-visible meshes (avoids per-frame allocation).
	private static final java.util.ArrayList<RegionMesh> visibleMeshes = new java.util.ArrayList<>();
	// Staging set for a full rebuild in progress (null when none).
	private static Long2ObjectOpenHashMap<RegionMesh> stagingMeshes;
	private static it.unimi.dsi.fastutil.longs.LongOpenHashSet stagingExpected;
	private static MeshJob currentJob;      // snapshot the workers build against (origin, fog, fill)
	private static volatile int buildStamp; // bumped per full rebuild; results with an old stamp are dropped
	private static int drawnStamp = -1;
	private static long lastDataSubmitMs;

	/** Everything a mesh build needs, captured immutably on the render thread. */
	private static final class MeshJob {
		final Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> regions;
		final LongOpenHashSet loadedVanillaChunks;
		final boolean fillVanillaHoles;
		final double camX;
		final double camY;
		final double camZ;
		final int cameraChunkX;
		final int cameraChunkZ;
		final int minChunkDistance;
		final int maxChunkDistance;
		final int maxQuads;
		final int skirtDepth;
		final boolean roundHorizon;
		// Vanilla's VERTICAL cull distance (blocks): sections farther than this from the camera are not
		// rendered by vanilla even when loaded (tall peaks near the player, the ground under a high
		// flight). LOD fills those too.
		final int vanillaCullBlocks;
		// End-like dimension: every tile gets a bottom cap (islands float over void and are seen from
		// below — an open underside reads as a hole in the island).
		final boolean floatingWorld;
		// Ceiling dimension (Nether): render the stored span-2 ceiling as a downward slab (enclosed cave).
		// Non-final: set right after construction in buildJob, before the job is handed to the pool.
		boolean ceilingWorld;
		// 0.6.1 tree-imposter experiment: when on, tree tiles emit GROUND ONLY — the isolated
		// PauCTreeImposterRenderer draws the canopies as billboards. Off = the classic boxy tree mesh.
		// Non-final: set in buildJob (a tunable read on the render thread), like ceilingWorld.
		boolean treeImposters;
		// Experiment: distant bands (step >= 4) as a smooth watertight TRIANGLE mesh (no walls) instead
		// of blocky flat-top+wall tiles. Near bands stay blocky. Tunable, default off.
		boolean triangleFar;
		// P1 — WORLD-ALIGNED SECTION GRID (tunable pauc.lodengine.worldAlignedGrid, DEFAULT OFF). When on,
		// a region meshes at ONE uniform step from its section's LIVE detail level (PauCLodSectionGrid), so
		// moving re-meshes only sections that crossed a band boundary — not the whole map. Set in buildJob;
		// copied in the incremental copy constructor. livePlayerChunk is the CURRENT player (drives the
		// section level); the mesh COORDS still use camX/cameraChunk (the fixed origin) so meshes stay
		// aligned across incremental rebuilds. Off = classic per-tile radial bandStep, unchanged.
		boolean worldAligned;
		int livePlayerChunkX;
		int livePlayerChunkZ;
		int gridBandWidthChunks;
		// Screen-space refinement thresholds (lot 3): squared 3D distances (blocks) under which a cell
		// refines to blocky 1x1 / 2x2 — from window height, FOV and the refinePixels preset, so the
		// blocky radius self-calibrates with resolution/FOV/altitude instead of fixed sector widths.
		float zone1End;
		float zone2End;
		boolean terrainShading;
		boolean biomeBlendGradient;
		// Near-vanilla Z1 pass (all set in buildJob like the other tunables).
		boolean nearVertexAo;
		boolean nearPureColors;
		boolean nearGreedyMerge;
		float waterSeamShade;
		float refine1BlocksSq;
		float refine2BlocksSq;
		final float fogR;
		final float fogG;
		final float fogB;
		final float fogStartChunks;
		final float fogEndChunks;
		final float innerFogEndChunks;
		final float band0End;
		final float band1End;
		final float band2End;
		final float ambientR;
		final float ambientG;
		final float ambientB;
		final long revision;
		final float night;

		MeshJob(Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> regions, LongOpenHashSet loadedVanillaChunks,
				boolean fillVanillaHoles, double camX, double camY, double camZ, int cameraChunkX, int cameraChunkZ,
				int minChunkDistance, int maxChunkDistance, int maxQuads, int skirtDepth, boolean roundHorizon,
				int vanillaCullBlocks, boolean floatingWorld,
				float fogR, float fogG, float fogB, float fogStartChunks, float fogEndChunks, float innerFogEndChunks,
				float band0End, float band1End, float band2End, float ambientR, float ambientG, float ambientB,
				long revision, float night) {
			this.regions = regions;
			this.loadedVanillaChunks = loadedVanillaChunks;
			this.fillVanillaHoles = fillVanillaHoles;
			this.camX = camX;
			this.camY = camY;
			this.camZ = camZ;
			this.cameraChunkX = cameraChunkX;
			this.cameraChunkZ = cameraChunkZ;
			this.minChunkDistance = minChunkDistance;
			this.maxChunkDistance = maxChunkDistance;
			this.maxQuads = maxQuads;
			this.skirtDepth = skirtDepth;
			this.roundHorizon = roundHorizon;
			this.vanillaCullBlocks = vanillaCullBlocks;
			this.floatingWorld = floatingWorld;
			this.fogR = fogR;
			this.fogG = fogG;
			this.fogB = fogB;
			this.fogStartChunks = fogStartChunks;
			this.fogEndChunks = fogEndChunks;
			this.innerFogEndChunks = innerFogEndChunks;
			this.band0End = band0End;
			this.band1End = band1End;
			this.band2End = band2End;
			this.ambientR = ambientR;
			this.ambientG = ambientG;
			this.ambientB = ambientB;
			this.revision = revision;
			this.night = night;
		}

		/** Copy for an INCREMENTAL rebuild: same camera/fog/fill origin, fresh region snapshot. */
		MeshJob(MeshJob src, Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> newRegions) {
			this.regions = newRegions;
			this.loadedVanillaChunks = src.loadedVanillaChunks;
			this.fillVanillaHoles = src.fillVanillaHoles;
			this.camX = src.camX;
			this.camY = src.camY;
			this.camZ = src.camZ;
			this.cameraChunkX = src.cameraChunkX;
			this.cameraChunkZ = src.cameraChunkZ;
			this.minChunkDistance = src.minChunkDistance;
			this.maxChunkDistance = src.maxChunkDistance;
			this.maxQuads = src.maxQuads;
			this.skirtDepth = src.skirtDepth;
			this.roundHorizon = src.roundHorizon;
			this.vanillaCullBlocks = src.vanillaCullBlocks;
			this.floatingWorld = src.floatingWorld;
			this.ceilingWorld = src.ceilingWorld;
			this.treeImposters = src.treeImposters;
			this.triangleFar = src.triangleFar;
			this.zone1End = src.zone1End;
			this.zone2End = src.zone2End;
			this.terrainShading = src.terrainShading;
			this.biomeBlendGradient = src.biomeBlendGradient;
			this.nearVertexAo = src.nearVertexAo;
			this.nearPureColors = src.nearPureColors;
			this.nearGreedyMerge = src.nearGreedyMerge;
			this.waterSeamShade = src.waterSeamShade;
			this.refine1BlocksSq = src.refine1BlocksSq;
			this.refine2BlocksSq = src.refine2BlocksSq;
			this.fogR = src.fogR;
			this.fogG = src.fogG;
			this.fogB = src.fogB;
			this.fogStartChunks = src.fogStartChunks;
			this.fogEndChunks = src.fogEndChunks;
			this.innerFogEndChunks = src.innerFogEndChunks;
			this.band0End = src.band0End;
			this.band1End = src.band1End;
			this.band2End = src.band2End;
			this.ambientR = src.ambientR;
			this.ambientG = src.ambientG;
			this.ambientB = src.ambientB;
			this.revision = src.revision;
			this.night = src.night;
			this.worldAligned = src.worldAligned;
			this.livePlayerChunkX = src.livePlayerChunkX;
			this.livePlayerChunkZ = src.livePlayerChunkZ;
			this.gridBandWidthChunks = src.gridBandWidthChunks;
		}

		/**
		 * P1: uniform meshing step for a whole region from its LIVE section detail level; returns -1 when
		 * not world-aligned (caller uses per-tile bandStep) or the section is out of LOD range.
		 */
		int worldAlignedStep(long regionKey) {
			if (!worldAligned) {
				return -1;
			}
			byte level = PauCLodSectionGrid.detailLevel(regionKey, livePlayerChunkX, livePlayerChunkZ,
				minChunkDistance, maxChunkDistance, Math.max(1, gridBandWidthChunks));
			if (level == PauCLodSectionGrid.LEVEL_OUT_OF_RANGE) {
				return -1;
			}
			return Math.min(1 << Math.min(level, 3), CELL); // level 0..3+ -> step 1/2/4/8, capped at CELL
		}

		int bandStep(float radialChunks) {
			if (radialChunks < band0End) {
				return 1;
			}
			if (radialChunks < band1End) {
				return 2;
			}
			if (radialChunks < band2End) {
				return 4;
			}
			return CELL;
		}
	}

	/**
	 * P5 SMOOTH CROSS-FADE: near each band boundary (1→2, 2→4, 4→8), a 1-chunk dither zone
	 * probabilistically picks the finer or coarser step instead of hard-cutting. The dither
	 * uses a positional hash so the choice is stable per-column and doesn't shimmer when the
	 * camera moves. Only the step is changed (finer→coarser); the tile's actual colour and
	 * height are computed from the SAME column data, so there is no colour discontinuity —
	 * just a smooth density/geometry transition.
	 */
	private static int ditheredBandStep(MeshJob job, float radialChunks, int currentStep, int worldX, int worldZ) {
		// Transition zone width: 1 chunk on each side of the boundary = 2 chunks total.
		final float FADE = 1.0F;
		// Band boundaries and the steps they transition between: fine→coarse.
		float[] boundaries = { job.band0End, job.band1End, job.band2End };
		int[]   fineSteps  = {           1,           2,           4 };
		int[]   coarseSteps = {          2,           4,          CELL };
		for (int b = 0; b < boundaries.length; b++) {
			float dist = radialChunks - boundaries[b];
			if (dist >= -FADE && dist <= FADE) {
				// t = 0 at the fine side, 1 at the coarse side.
				float t = (dist + FADE) / (2.0F * FADE);
				int h = worldX * 374761393 ^ worldZ * 668265263;
				h = (h ^ (h >>> 13)) * 1274126177;
				if (((h >>> 8) & 0xff) / 255.0F < t) {
					return coarseSteps[b];
				}
				return fineSteps[b];
			}
		}
		return currentStep;
	}


	private PauCSurfaceWitnessRenderer() {
	}

	public static void render(PoseStack poseStack, Vec3 cameraPos) {
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}

		try {
			// CEILING DIMENSIONS (Nether): MULTI-DIM (07-20, user). A heightfield can't capture the
			// Nether's full 3D volume, but the store already samples the WALKABLE surface under the roof
			// (scanUnderCeiling) and the mesh has a ceilingWorld path — floor + downward ceiling slab so
			// the distant Nether reads as an ENCLOSED cave, not a floor under open sky. Enabled via
			// `pauc.lodengine.netherLod` (default OFF: unchanged behaviour). Data is visited-only (distant
			// noise-gen is meaningless under a roof), so coverage follows where the player has been.
			if (minecraft.level.dimensionType().hasCeiling()
					&& !PauCTunables.readBoolean("pauc.lodengine.netherLod", false)) {
				builtQuads = 0;
				return;
			}
			// LODs OFF via the video-settings gauge: draw nothing (fog reverts stock since builtQuads=0).
			if (lodRadiusChunks(minecraft.options.getEffectiveRenderDistance()) <= 0) {
				builtQuads = 0;
				return;
			}
			// PauC↔DH COORDINATION — "PauC enriches DH" mode (tunable, DEFAULT OFF): when Distant Horizons
			// is installed AND rendering, DH owns the distant TERRAIN; PauC stops drawing its own witness
			// terrain so the two don't double-render / z-fight, and keeps only its enrichments (imposters,
			// clouds). The store + sampler + generator keep running (imposters read the store), so when DH
			// is ABSENT — including under shaderpacks where DH does not integrate — PauC's full witness
			// renderer takes the relay again with zero change. Default OFF keeps PauC standalone sacred.
			if (PauCTunables.readBoolean("pauc.lodengine.deferTerrainToDh", false)
					&& fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
				builtQuads = 0;
				return;
			}

			// 1. Upload any finished region builds (small VBO uploads only — a few ms).
			drainResults();

			// 2. Schedule rebuilds: FULL on camera/light/radius change, INCREMENTAL on data change.
			PauCSurfaceColumnStore store = PauCSurfaceSampler.store();
			long now = System.currentTimeMillis();
			scheduleBuilds(minecraft, store, cameraPos, now);

			// 3. Draw: ALL opaque region meshes first, THEN all translucent water (water pass last).
			if (drawnMeshes.isEmpty() || builtQuads == 0) {
				return;
			}
			ShaderInstance shader = GameRenderer.getPositionColorShader();
			if (shader == null) {
				return;
			}
			// FRUSTUM CULL to the PLAYER FOV: drawing the whole 360-degree ring was ~2400 draw calls per
			// frame, most of them BEHIND the player and never seen. Meshing still covers 360 in the
			// background (instant turn, no pop-in) — only the DRAW is limited to what the camera sees.
			// This is THE lever for high FPS in first-person modpacks.
			net.minecraft.client.renderer.culling.Frustum frustum =
				new net.minecraft.client.renderer.culling.Frustum(new org.joml.Matrix4f(poseStack.last().pose()), RenderSystem.getProjectionMatrix());
			frustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
			double worldMinY = minecraft.level.getMinBuildHeight();
			double worldMaxY = minecraft.level.getMaxBuildHeight();
			visibleMeshes.clear();
			for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<RegionMesh> entry : drawnMeshes.long2ObjectEntrySet()) {
				RegionMesh mesh = entry.getValue();
				if (mesh == null || (mesh.quads == 0 && mesh.waterQuads == 0)) {
					continue;
				}
				if (mesh.bounds == null) {
					long key = entry.getLongKey();
					double rx = PauCSurfaceColumnStore.regionXFromKey(key) << PauCSurfaceColumnStore.REGION_SHIFT;
					double rz = PauCSurfaceColumnStore.regionZFromKey(key) << PauCSurfaceColumnStore.REGION_SHIFT;
					mesh.bounds = new net.minecraft.world.phys.AABB(
						rx, worldMinY, rz, rx + PauCSurfaceColumnStore.REGION_SIZE, worldMaxY, rz + PauCSurfaceColumnStore.REGION_SIZE);
				}
				if (frustum.isVisible(mesh.bounds)) {
					visibleMeshes.add(mesh);
				}
			}
			int drawnVisible = visibleMeshes.size();

			poseStack.pushPose();
			poseStack.translate(originX - cameraPos.x, originY - cameraPos.y, originZ - cameraPos.z);
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(true);
			RenderSystem.disableCull();
			// Blend ON for the whole LOD draw: opaque tiles carry alpha 255 (blend is a no-op → identical
			// to opaque), only the seam-fade ring carries alpha < 255 and dissolves into vanilla. Water
			// (alpha 185) also uses this same blend func; depthMask stays ON so water reads as solid.
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			org.joml.Matrix4f pose = poseStack.last().pose();
			org.joml.Matrix4f proj = RenderSystem.getProjectionMatrix();
			for (int i = 0; i < drawnVisible; i++) {
				RegionMesh mesh = visibleMeshes.get(i);
				if (mesh.opaque != null && mesh.quads > 0) {
					mesh.opaque.bind();
					mesh.opaque.drawWithShader(pose, proj, shader);
				}
			}
			for (int i = 0; i < drawnVisible; i++) {
				RegionMesh mesh = visibleMeshes.get(i);
				if (mesh.water != null && mesh.waterQuads > 0) {
					mesh.water.bind();
					mesh.water.drawWithShader(pose, proj, shader);
				}
			}
			RenderSystem.disableBlend();
			VertexBuffer.unbind();
			RenderSystem.enableCull();
			poseStack.popPose();

			if (!firstDrawLogged) {
				firstDrawLogged = true;
				LOGGER.info("PauC LOD engine witness renderer: first PauC-drawn horizon tiles on screen ({} quads).", builtQuads);
			}
		} catch (Throwable throwable) {
			if (!renderFailureLogged) {
				renderFailureLogged = true;
				LOGGER.warn("PauC LOD engine witness renderer failed; disabled for this session.", throwable);
			}
			System.setProperty(ENABLED_PROPERTY, "false");
		}
	}

	// ---- render-thread: upload finished builds, swap staging, schedule work ----

	private static void drainResults() {
		RegionResult result;
		while ((result = RESULTS.poll()) != null) {
			inFlight.decrementAndGet();
			try {
				if (result.incremental) {
					// Only valid if no full rebuild superseded the drawn set while this was building.
					if (result.stamp != drawnStamp || stagingMeshes != null) {
						disposeRendered(result);
						continue;
					}
					RegionMesh mesh = uploadRegion(result, drawnMeshes.get(result.regionKey));
					drawnMeshes.put(result.regionKey, mesh);
					builtQuads += mesh.quads; // approximate running total for the fog gate
				} else {
					if (stagingMeshes == null || result.stamp != buildStamp) {
						disposeRendered(result);
						continue;
					}
					RegionMesh mesh = uploadRegion(result, null);
					stagingMeshes.put(result.regionKey, mesh);
					stagingExpected.remove(result.regionKey);
					if (stagingExpected.isEmpty()) {
						swapStaging();
					}
				}
			} finally {
				// Return the borrowed context to the pool ONLY after its RenderedBuffers were consumed
				// (uploaded or released) — never before, or the worker would overwrite live data.
				CTX_POOL.offer(result.ctx);
			}
		}
	}

	private static RegionMesh uploadRegion(RegionResult result, RegionMesh reuse) {
		RegionMesh mesh = reuse != null ? reuse : new RegionMesh();
		if (mesh.opaque == null) {
			mesh.opaque = new VertexBuffer(VertexBuffer.Usage.STATIC);
		}
		if (mesh.water == null) {
			mesh.water = new VertexBuffer(VertexBuffer.Usage.STATIC);
		}
		mesh.opaque.bind();
		mesh.opaque.upload(result.opaque);
		mesh.water.bind();
		mesh.water.upload(result.water);
		VertexBuffer.unbind();
		mesh.quads = result.quads;
		mesh.waterQuads = result.waterQuads;
		mesh.dataRevision = result.dataRevision;
		return mesh;
	}

	private static void disposeRendered(RegionResult result) {
		if (result.opaque != null) {
			result.opaque.release();
		}
		if (result.water != null) {
			result.water.release();
		}
	}

	private static void swapStaging() {
		for (RegionMesh old : drawnMeshes.values()) {
			if (old != null) {
				old.close();
			}
		}
		drawnMeshes = stagingMeshes;
		drawnStamp = buildStamp;
		originX = currentJob.camX;
		originY = currentJob.camY;
		originZ = currentJob.camZ;
		builtCameraChunkX = currentJob.cameraChunkX;
		builtCameraChunkZ = currentJob.cameraChunkZ;
		builtMinChunkDistance = currentJob.minChunkDistance;
		builtMaxChunkDistance = currentJob.maxChunkDistance;
		builtNight = currentJob.night;
		builtRevision = currentJob.revision;
		stagingMeshes = null;
		stagingExpected = null;
		int q = 0;
		for (RegionMesh m : drawnMeshes.values()) {
			if (m != null) {
				q += m.quads;
			}
		}
		builtQuads = q;
		long now = System.currentTimeMillis();
		if (now - lastQuadLogMs >= 30_000L) {
			lastQuadLogMs = now;
			LOGGER.info("PauC LOD engine witness render: {} quads across {} region meshes (band {}..{} chunks, parallel per-region).",
				q, drawnMeshes.size(), builtMinChunkDistance, builtMaxChunkDistance);
		}
	}

	private static void scheduleBuilds(Minecraft minecraft, PauCSurfaceColumnStore store, Vec3 cameraPos, long now) {
		// A full rebuild re-meshes and re-uploads EVERY region — the whole map churns at once (the
		// "massive reload" during travel). It is UNAVOIDABLE on move with the current architecture: the
		// LOD bands/zones/horizon are rings CENTRED ON THE PLAYER (radialDistance from cameraChunk), and
		// every region shares ONE draw origin, so moving shifts every band boundary and a subset must
		// re-mesh — but they can't re-mesh against a different camera without misaligning, forcing a full
		// pass. The DH-style fix is WORLD-ALIGNED band boundaries (a fixed grid, not player rings) so a
		// move only changes the detail LEVEL of fixed sections — tracked as a follow-up. Until then, the
		// only lever is FREQUENCY: geometry is world-anchored (correct between rebuilds via the draw
		// translate), only band-detail/fog/fill lag, and the fog gradient (~18 chunks) hides a lag of a
		// few chunks — so full rebuilds fire only after REAL travel (96 blocks / 6 chunks), not 24.
		double dxo = cameraPos.x - originX;
		double dzo = cameraPos.z - originZ;
		boolean movedFar = drawnStamp >= 0 && (dxo * dxo + dzo * dzo) > 96.0D * 96.0D;
		boolean movedY = drawnStamp >= 0 && Math.abs(cameraPos.y - originY) > 96.0D;
		float nightNow = nightFactor(minecraft);
		boolean lightChanged = builtNight >= 0.0F && Math.abs(nightNow - builtNight) > 0.04F;
		int wantedRadius = Math.max(builtMinChunkDistance + 1, lodRadiusChunks(minecraft.options.getEffectiveRenderDistance()));
		boolean radiusChanged = builtMaxChunkDistance > 0 && wantedRadius != builtMaxChunkDistance;
		boolean needFull = drawnStamp < 0 || movedFar || movedY || lightChanged || radiusChanged;
		boolean dataChanged = store.revision() != builtRevision;

		// P1 — WORLD-ALIGNED GRID: route pure MOVEMENT to an incremental re-mesh of only the sections whose
		// detail level changed, instead of the whole-map full rebuild. First build, light, radius and a
		// FAR re-center (float precision) still take the full path. Default off: `worldAligned` is false.
		boolean worldAligned = PauCTunables.readBoolean(WORLD_ALIGNED_GRID_PROPERTY, WORLD_ALIGNED_GRID_DEFAULT);
		boolean movementOnly = drawnStamp >= 0 && !lightChanged && !radiusChanged && (movedFar || movedY);
		boolean needRecenter = (dxo * dxo + dzo * dzo) > 512.0D * 512.0D || Math.abs(cameraPos.y - originY) > 512.0D;
		if (worldAligned && movementOnly && !needRecenter && stagingMeshes == null
				&& now - lastDataSubmitMs >= REBUILD_INTERVAL_MS) {
			gridIncrementalRebuild(minecraft, store, cameraPos, now);
			return;
		}

		// One full rebuild in flight at a time, throttled. Old meshes keep drawing until it swaps in.
		if (stagingMeshes != null && now - lastSubmitMs > 10_000L) {
			// FAILSAFE: a wedged staging generation (a worker died without delivering its region) froze the
			// WHOLE pipeline — no full rebuilds, no incremental updates: holes everywhere, stale boundaries,
			// LOD over vanilla (log evidence: swaps counter frozen). Force-swap what arrived and log the rest.
			com.mojang.logging.LogUtils.getLogger().warn("PauC LOD staging wedged {}s, {} regions missing — force swap.",
				(now - lastSubmitMs) / 1000L, stagingExpected == null ? 0 : stagingExpected.size());
			swapStaging();
		}
		if (needFull && stagingMeshes == null && now - lastSubmitMs >= REBUILD_INTERVAL_MS) {
			startFullRebuild(minecraft, store, cameraPos, now);
			return;
		}
		// Incremental data path: only when idle (no staging), throttled — the generation stream.
		if (dataChanged && stagingMeshes == null && drawnStamp >= 0 && now - lastDataSubmitMs >= DATA_REBUILD_INTERVAL_MS) {
			startIncremental(store, now);
		}
	}

	private static void startFullRebuild(Minecraft minecraft, PauCSurfaceColumnStore store, Vec3 cameraPos, long now) {
		lastSubmitMs = now;
		resolveMaterialColors();
		MeshJob job = buildJob(minecraft, store, cameraPos, now);
		currentJob = job;
		int stamp = ++buildStamp;
		stagingMeshes = new Long2ObjectOpenHashMap<>();
		stagingExpected = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		long[] keys = inRadiusRegions(job, true); // near-first: the near field swaps in first
		for (long key : keys) {
			stagingExpected.add(key);
		}
		if (keys.length == 0) {
			swapStaging(); // empty store: adopt an empty set so we don't spin on needFull
			return;
		}
		for (long key : keys) {
			enqueueRegion(key, job, stamp, false);
		}
		// P1: a full rebuild re-meshes EVERY section — seed the grid so later grid-incrementals know the
		// baseline level each section was built at (only crossings re-mesh afterwards). Default off.
		if (job.worldAligned) {
			SECTION_GRID.clear();
			int band = Math.max(1, job.gridBandWidthChunks);
			for (long key : keys) {
				SECTION_GRID.markBuilt(key, PauCLodSectionGrid.detailLevel(key, job.livePlayerChunkX, job.livePlayerChunkZ,
					job.minChunkDistance, job.maxChunkDistance, band));
			}
		}
	}

	private static void startIncremental(PauCSurfaceColumnStore store, long now) {
		if (currentJob == null) {
			return;
		}
		lastDataSubmitMs = now;
		builtRevision = store.revision();
		resolveMaterialColors();
		Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> fresh = snapshotRegions(store);
		MeshJob job = new MeshJob(currentJob, fresh);
		currentJob = job;
		long[] keys = inRadiusRegions(job, false); // incremental: few regions, order irrelevant — skip the sort
		for (long key : keys) {
			if (inFlight.get() > MESH_POOL_THREADS * 6) {
				break; // don't flood the pool; the rest is picked up on the next data tick
			}
			RegionMesh existing = drawnMeshes.get(key);
			PauCSurfaceColumnStore.Region region = fresh.get(key);
			long rev = region == null ? 0L : region.revision;
			if (existing == null || existing.dataRevision != rev) {
				enqueueRegion(key, job, drawnStamp, true);
			}
		}
	}

	/**
	 * P1 — world-aligned incremental rebuild for MOVEMENT. Re-meshes ONLY the sections whose live detail
	 * level changed and drops meshes now out of LOD range. Keeps the SAME fixed origin (currentJob.camX)
	 * so re-meshed and untouched sections stay geometrically aligned. Runs on the render thread.
	 */
	private static void gridIncrementalRebuild(Minecraft minecraft, PauCSurfaceColumnStore store, Vec3 cameraPos, long now) {
		if (currentJob == null) {
			return;
		}
		lastDataSubmitMs = now;
		resolveMaterialColors();
		Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> fresh = snapshotRegions(store);
		MeshJob job = new MeshJob(currentJob, fresh);
		int pcx = ((int) Math.floor(cameraPos.x)) >> 4;
		int pcz = ((int) Math.floor(cameraPos.z)) >> 4;
		job.livePlayerChunkX = pcx;
		job.livePlayerChunkZ = pcz;
		currentJob = job;
		builtRevision = store.revision();
		int band = Math.max(1, job.gridBandWidthChunks);
		long[] keys = inRadiusRegions(job, false);
		if (!gridEngagedLogged) {
			gridEngagedLogged = true;
			com.mojang.logging.LogUtils.getLogger().info(
				"PauC LOD WORLD-ALIGNED GRID engaged (bandWidth={} chunks): movement now re-meshes only changed sections, not the whole map.", band);
		}
		// Drop meshes for sections that just left the LOD range (else they linger past the horizon).
		it.unimi.dsi.fastutil.longs.LongOpenHashSet inRange = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(keys.length);
		for (long k : keys) {
			inRange.add(k);
		}
		it.unimi.dsi.fastutil.longs.LongIterator drawnIt = drawnMeshes.keySet().iterator();
		while (drawnIt.hasNext()) {
			long k = drawnIt.nextLong();
			if (!inRange.contains(k)) {
				RegionMesh gone = drawnMeshes.get(k);
				if (gone != null) {
					gone.close();
				}
				drawnIt.remove();
				SECTION_GRID.forget(k);
			}
		}
		// Re-mesh only the sections whose detail level changed with the move.
		it.unimi.dsi.fastutil.longs.LongArrayList changed = SECTION_GRID.sectionsNeedingRebuild(keys, pcx, pcz, job.minChunkDistance, job.maxChunkDistance, band);
		for (long key : changed) {
			if (inFlight.get() > MESH_POOL_THREADS * 6) {
				break; // don't flood the pool; the next movement tick picks up the rest
			}
			byte level = PauCLodSectionGrid.detailLevel(key, pcx, pcz, job.minChunkDistance, job.maxChunkDistance, band);
			enqueueRegion(key, job, drawnStamp, true);
			SECTION_GRID.markBuilt(key, level);
		}
	}

	private static void enqueueRegion(long regionKey, MeshJob job, int stamp, boolean incremental) {
		inFlight.incrementAndGet();
		MESH_POOL.submit(() -> {
			try {
				buildOneRegion(job, regionKey, stamp, incremental);
			} catch (Throwable throwable) {
				inFlight.decrementAndGet();
				if (!renderFailureLogged) {
					renderFailureLogged = true;
					LOGGER.warn("PauC LOD engine region mesh build failed.", throwable);
				}
			}
		});
	}

	/** Worker thread: BORROWS a pooled context, builds ONE region, hands it off (no GL calls). */
	private static void buildOneRegion(MeshJob job, long regionKey, int stamp, boolean incremental)
			throws InterruptedException {
		BuildCtx ctx = CTX_POOL.take(); // blocks if all contexts are in flight — natural backpressure
		boolean handedOff = false;
		try {
			ctx.main.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			ctx.water.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			ctx.emitTarget = ctx.main;
			ctx.emitAlpha = 255;
			ctx.waterQuads = 0;
			int quads = 0;
			long dataRevision = 0L;
			PauCSurfaceColumnStore.Region region = job.regions.get(regionKey);
			if (region != null) {
				dataRevision = region.revision;
				int baseColumnX = PauCSurfaceColumnStore.regionXFromKey(regionKey) << PauCSurfaceColumnStore.REGION_SHIFT;
				int baseColumnZ = PauCSurfaceColumnStore.regionZFromKey(regionKey) << PauCSurfaceColumnStore.REGION_SHIFT;
				quads = buildRegionTiles(ctx, job, region, baseColumnX, baseColumnZ, 0);
			}
			BufferBuilder.RenderedBuffer opaque = ctx.main.end();
			BufferBuilder.RenderedBuffer water = ctx.water.end();
			RESULTS.add(new RegionResult(regionKey, stamp, incremental, opaque, water, quads, ctx.waterQuads, dataRevision, ctx));
			handedOff = true; // the render thread now owns ctx and will return it to the pool
		} finally {
			if (!handedOff) {
				// Build threw mid-way: the builder may be left in the building state. Don't return a
				// dirty context to the pool — replace it with a fresh one so the pool size holds.
				CTX_POOL.offer(new BuildCtx());
			}
		}
	}

	/**
	 * Region keys within the draw radius. {@code sortNear} = NEAREST FIRST (full rebuild, so the near
	 * field appears first); the incremental path passes false (a handful of changed regions, order is
	 * irrelevant) and skips the sort entirely. Primitive long[] throughout — the old boxed Long[] +
	 * comparator allocated thousands of objects per rebuild, a GC-churn source behind the frame spikes.
	 */
	private static long[] inRadiusRegions(MeshJob job, boolean sortNear) {
		int regionChunkSpan = PauCSurfaceColumnStore.REGION_SIZE >> 4;
		long[] all = job.regions.keySet().toLongArray();
		long[] kept = new long[all.length];
		int n = 0;
		for (long key : all) {
			int baseChunkX = (PauCSurfaceColumnStore.regionXFromKey(key) << PauCSurfaceColumnStore.REGION_SHIFT) >> 4;
			int baseChunkZ = (PauCSurfaceColumnStore.regionZFromKey(key) << PauCSurfaceColumnStore.REGION_SHIFT) >> 4;
			int cheb = Math.max(
				Math.abs(baseChunkX + regionChunkSpan / 2 - job.cameraChunkX),
				Math.abs(baseChunkZ + regionChunkSpan / 2 - job.cameraChunkZ));
			if (cheb <= job.maxChunkDistance + regionChunkSpan) {
				kept[n++] = key;
			}
		}
		long[] out = java.util.Arrays.copyOf(kept, n);
		if (sortNear) {
			long[] dist = new long[n];
			for (int i = 0; i < n; i++) {
				long dx = ((long) PauCSurfaceColumnStore.regionXFromKey(out[i]) << PauCSurfaceColumnStore.REGION_SHIFT) + 32 - (long) job.camX;
				long dz = ((long) PauCSurfaceColumnStore.regionZFromKey(out[i]) << PauCSurfaceColumnStore.REGION_SHIFT) + 32 - (long) job.camZ;
				dist[i] = dx * dx + dz * dz;
			}
			sortByKey(out, dist, 0, n - 1);
		}
		return out;
	}

	/** In-place quicksort of {@code keys} ordered by the parallel {@code dist} array (no autoboxing). */
	private static void sortByKey(long[] keys, long[] dist, int lo, int hi) {
		while (lo < hi) {
			long pivot = dist[lo + ((hi - lo) >> 1)];
			int i = lo;
			int j = hi;
			while (i <= j) {
				while (dist[i] < pivot) {
					i++;
				}
				while (dist[j] > pivot) {
					j--;
				}
				if (i <= j) {
					long tk = keys[i]; keys[i] = keys[j]; keys[j] = tk;
					long td = dist[i]; dist[i] = dist[j]; dist[j] = td;
					i++;
					j--;
				}
			}
			// Recurse into the smaller partition, loop on the larger — bounded stack depth.
			if (j - lo < hi - i) {
				sortByKey(keys, dist, lo, j);
				lo = i;
			} else {
				sortByKey(keys, dist, i, hi);
				hi = j;
			}
		}
	}

	private static Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> snapshotRegions(PauCSurfaceColumnStore store) {
		Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> regions = new Long2ObjectOpenHashMap<>();
		for (long key : store.regionKeys()) {
			PauCSurfaceColumnStore.Region region = store.region(key);
			if (region != null) {
				regions.put(key, region);
			}
		}
		return regions;
	}

	/** Render thread: captures an immutable camera/fog/fill snapshot for a full rebuild. */
	private static MeshJob buildJob(Minecraft minecraft, PauCSurfaceColumnStore store, Vec3 cameraPos, long now) {
		int vanillaChunks = minecraft.options.getEffectiveRenderDistance();
		int minChunkDistance = vanillaChunks + 1;
		int maxChunkDistance = Math.max(minChunkDistance + 1, lodRadiusChunks(vanillaChunks));
		int maxQuads = readInt(MAX_QUADS_PROPERTY, 2_600_000, 1_000, 6_000_000);
		int cameraChunkX = (int) Math.floor(cameraPos.x) >> 4;
		int cameraChunkZ = (int) Math.floor(cameraPos.z) >> 4;

		float fogRv = 0.65F;
		float fogGv = 0.75F;
		float fogBv = 0.92F;
		float[] fog = RenderSystem.getShaderFogColor();
		if (fog != null && fog.length >= 3) {
			fogRv = fog[0];
			fogGv = fog[1];
			fogBv = fog[2];
		}
		int fogWidth = readInt(FOG_WIDTH_PROPERTY, 18, 0, 128);
		float fogEnd = maxChunkDistance - 1.5F; // full sky BEFORE the clip edge: the horizon dissolves, no hard rim
		float fogStart = Math.max(minChunkDistance, maxChunkDistance - fogWidth);
		if (!PauCTunables.readBoolean("pauc.client.fog", true)) {
			// Video Settings Fog OFF (modern-MC style toggle): clear horizon, no distance fade.
			fogStart = 1.0e6F;
			fogEnd = 1.0e6F + 1.0F;
		}
		fogStartChunksShared = fogStart;
		fogEndChunksShared = fogEnd;
		int innerFogChunks = readInt(INNER_FOG_PROPERTY, 0, 0, 16); // 0: the sky-blue inner veil painted a PALE STRIPE on clear days (proven by pixel forensics 07-18) — hard seam beats a wrong fade

		boolean roundHorizon = PauCTunables.readBoolean(ROUND_HORIZON_PROPERTY, true);
		int skirtDepth = readInt(SKIRT_DEPTH_PROPERTY, 160, 0, 512);
		float ringWidth = bandRingWidth(minChunkDistance, maxChunkDistance);
		float band0End = minChunkDistance + ringWidth;
		float band1End = minChunkDistance + 2.0F * ringWidth;
		float band2End = minChunkDistance + 3.0F * ringWidth;

		float night = nightFactor(minecraft);
		float ambientR = 1.0F - 0.78F * night;
		float ambientG = 1.0F - 0.76F * night;
		float ambientB = 1.0F - 0.62F * night;

		Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> regions = snapshotRegions(store);

		boolean fillHoles = PauCTunables.readBoolean(FILL_VANILLA_HOLES_PROPERTY, true);
		LongOpenHashSet loaded = new LongOpenHashSet();
		if (fillHoles && minecraft.level != null) {
			for (int dz = -minChunkDistance; dz <= minChunkDistance; dz++) {
				for (int dx = -minChunkDistance; dx <= minChunkDistance; dx++) {
					int cx = cameraChunkX + dx;
					int cz = cameraChunkZ + dz;
					long key = ChunkPos.asLong(cx, cz);
					if (minecraft.level.getChunkSource().getChunk(cx, cz, false) != null) {
						long since = loadedSince.getOrDefault(key, 0L);
						if (since == 0L) {
							loadedSince.put(key, now);
						} else if (now - since > LOADED_GRACE_MS) {
							loaded.add(key);
						}
					} else {
						loadedSince.remove(key);
					}
				}
			}
		}

		boolean floatingWorld = minecraft.level != null
			&& !minecraft.level.dimensionType().hasSkyLight()
			&& !minecraft.level.dimensionType().natural()
			&& !minecraft.level.dimensionType().hasCeiling();
		MeshJob job = new MeshJob(regions, loaded, fillHoles, cameraPos.x, cameraPos.y, cameraPos.z,
			cameraChunkX, cameraChunkZ, minChunkDistance, maxChunkDistance, maxQuads, skirtDepth, roundHorizon,
			vanillaChunks * 16, floatingWorld,
			fogRv, fogGv, fogBv, fogStart, fogEnd, minChunkDistance + innerFogChunks,
			band0End, band1End, band2End, ambientR, ambientG, ambientB, store.revision(), night);
		job.ceilingWorld = minecraft.level != null && minecraft.level.dimensionType().hasCeiling();
		job.treeImposters = PauCTreeImposterRenderer.enabled();
		job.triangleFar = PauCTunables.readBoolean(TRIANGLE_FAR_PROPERTY, true); // default ON; set =false to compare blocky
		// MASTER A/B SWITCH for the whole 07-19 near-vanilla pass: -Dpauc.lodengine.nearVanillaPass=false
		// restores the exact pre-07-19 rendering (no vertex AO, per-block jitter back, no greedy merge,
		// terrainShading dead again, water shade 1.0) in ONE flag, so a regression suspicion is a
		// 2-minute test instead of an argument.
		boolean nearVanillaPass = PauCTunables.readBoolean(NEAR_VANILLA_PASS_PROPERTY, true);
		// "Relief ombré" video toggle: was exposed in the options screen but never wired to the job —
		// the slope shading code was dead. Reads the same system property the settings screen writes.
		job.terrainShading = nearVanillaPass && PauCTunables.readBoolean("pauc.client.terrainShading", true);
		job.biomeBlendGradient = nearVanillaPass && PauCTunables.readBoolean(BIOME_BLEND_PROPERTY, false); // opt-in: Gouraud biome blending, untested at scale
		job.nearVertexAo = nearVanillaPass && PauCTunables.readBoolean(NEAR_AO_PROPERTY, true);
		job.nearPureColors = nearVanillaPass && PauCTunables.readBoolean(NEAR_PURE_COLORS_PROPERTY, true);
		job.nearGreedyMerge = nearVanillaPass && PauCTunables.readBoolean(NEAR_GREEDY_PROPERTY, true);
		job.waterSeamShade = nearVanillaPass ? readFloat(WATER_SEAM_SHADE_PROPERTY, 0.80F, 0.3F, 1.0F) : 1.0F;
		// 4 EQUAL ZONES from the vanilla edge to the LOD border (user model): Z1 blocky 1x1, Z2 fine
		// triangles (step 2), Z3 coarse triangles + imposters, Z4 coarse triangles, no imposters.
		float zoneW = Math.max(1.0F, (maxChunkDistance - minChunkDistance) / 4.0F);
		job.zone1End = minChunkDistance + zoneW;
		job.zone2End = minChunkDistance + 2.0F * zoneW;
		job.refine1BlocksSq = job.zone1End * 16.0F * job.zone1End * 256.0F;
		job.refine2BlocksSq = job.refine1BlocksSq;
		// P1 — world-aligned section grid (default off). livePlayer = camera at build time; overridden on
		// the copy job for grid-incremental rebuilds so the section level follows the live player.
		job.worldAligned = PauCTunables.readBoolean(WORLD_ALIGNED_GRID_PROPERTY, WORLD_ALIGNED_GRID_DEFAULT);
		job.livePlayerChunkX = job.cameraChunkX;
		job.livePlayerChunkZ = job.cameraChunkZ;
		job.gridBandWidthChunks = readInt(GRID_BAND_WIDTH_PROPERTY, 8, 1, 64);
		return job;
	}

	private static int buildRegionTiles(BuildCtx ctx, MeshJob job, PauCSurfaceColumnStore.Region region, int baseColumnX, int baseColumnZ, int quads) {
		// P1: when world-aligned, the WHOLE region meshes at one uniform step from its live section level
		// (>0), instead of the per-tile radial bandStep. -1 = classic behaviour / out of range.
		int uniformStep = job.worldAlignedStep(PauCSurfaceColumnStore.regionKey(baseColumnX, baseColumnZ));
		for (int cellZ = 0; cellZ < PauCSurfaceColumnStore.REGION_SIZE && quads < job.maxQuads; cellZ += CELL) {
			for (int cellX = 0; cellX < PauCSurfaceColumnStore.REGION_SIZE && quads < job.maxQuads; cellX += CELL) {
				int worldX = baseColumnX + cellX;
				int worldZ = baseColumnZ + cellZ;
				int chunkX = worldX >> 4;
				int chunkZ = worldZ >> 4;
				int dcx = chunkX - job.cameraChunkX;
				int dcz = chunkZ - job.cameraChunkZ;
				int chebDistance = Math.max(Math.abs(dcx), Math.abs(dcz));
				float radialDistance = job.roundHorizon
					? (float) Math.sqrt((double) dcx * dcx + (double) dcz * dcz)
					: chebDistance;
				// The vanilla SQUARE is chebyshev <= render distance. LOD/refinement begins AFTER the vanilla
				// chunks: NOTHING is drawn inside the square on the ground (no LOD, no fill, no big cubes).
				// The ONLY exception is ALTITUDE: when vanilla vertically-culls the ground, LOD fills the
				// square to hide the big hole in the landscape.
				boolean insideVanilla = chebDistance <= job.minChunkDistance - 1;
				if (radialDistance > job.maxChunkDistance) {
					continue; // beyond the LOD horizon
				}
				if (radialDistance > job.maxChunkDistance - 1.5F) {
					// FAR-FADE (DH-style, geometric): the outermost ring DISSOLVES in a positional dither
					// instead of ending on a hard rim — density falls with distance, fog covers the gaps.
					int dh = cellX * 668265263 ^ cellZ * 374761393 ^ (baseColumnX + baseColumnZ) * 1274126177;
					float t = (job.maxChunkDistance - radialDistance) / 1.5F;
					if (((dh >>> 8) & 0xff) / 255.0F > t) {
						continue;
					}
				}
				boolean vanillaOwns = false;
				if (insideVanilla) {
					if (!job.fillVanillaHoles) {
						continue;
					}
					// Mature chunks vanilla is actually rendering get SUPPRESSED by the fog-sphere check below;
					// loading holes and the vertically-culled corners/altitude are FILLED ground-only — no LOD over
					// rendered vanilla, but movement/load never punches a hole.
					vanillaOwns = job.loadedVanillaChunks.contains(ChunkPos.asLong(chunkX, chunkZ));
				}
				// Fill tiles use the 4-block step: that is the distant-generation data grid, so finer
				// steps only added stair-noise ("dégueux") without adding real information.
				int step = insideVanilla ? 4 : (uniformStep > 0 ? uniformStep : job.bandStep(radialDistance));
				if (job.triangleFar && insideVanilla && radialDistance < job.zone1End) {
					step = 1; // vanilla-square fill matches zone 1's grain — coarse fill triangles read as zone mixing
				}
				if (job.triangleFar && !insideVanilla) {
					if (radialDistance < job.zone1End) {
						step = 1;
					} else if (radialDistance < job.zone2End - 1.0F) {
						step = 2; // Z2: fine triangles; last chunk ring snaps to step 4 (seam guard)
					} else {
						step = 4;
					}
				}
				// ADAPTIVE ROUGHNESS SPLIT: in the coarse 8-block band, mountainous cells (large height
				// range) subdivide to 4-block tiles — the data grid's real resolution — so distant
				// ranges stop reading as huge monolithic columns. Flat cells keep the cheap 8-block
				// tiles: the extra quads are spent ONLY where the relief needs them.
				if (step == CELL) {
					short cMin = Short.MAX_VALUE;
					short cMax = Short.MIN_VALUE;
					for (int sz = 0; sz < CELL; sz += 4) {
						for (int sx = 0; sx < CELL; sx += 4) {
							short top = region.spanY[(((cellZ + sz) << PauCSurfaceColumnStore.REGION_SHIFT) | (cellX + sx)) * PauCSurfaceColumnStore.MAX_SPANS];
							if (top == Short.MIN_VALUE) {
								continue;
							}
							cMin = top < cMin ? top : cMin;
							cMax = top > cMax ? top : cMax;
						}
					}
					if (cMax - cMin > 10) {
						step = 4;
					}
				}
				// SMOOTH CROSS-FADE (P5): near each step-transition boundary, a 1-chunk dither
				// zone probabilistically picks the finer or coarser step instead of hard-cutting.
				// This creates a stippled density gradient — the same technique as the far-fade
				// but applied to tile size instead of tile presence.
				if (!insideVanilla && !job.worldAligned && uniformStep <= 0) {
					step = ditheredBandStep(job, radialDistance, step, worldX, worldZ);
				}

				float fogT;
				if (insideVanilla) {
					fogT = 0.0F; // nearest tiles: never fogged
				} else {
					float outerFogT = smoothstep(job.fogStartChunks, job.fogEndChunks, radialDistance);
					// Inner fog MUST use the same metric as the draw test (radial): computing it on the
					// chebyshev distance made the corner tiles of the vanilla square (cheb < min but
					// radial >= min → drawn) read as "inside" → fogT 1 → sky-coloured WHITE CORNERS.
					float innerFogT = 1.0F - smoothstep((float) job.minChunkDistance, job.innerFogEndChunks, radialDistance);
					fogT = Math.max(innerFogT, outerFogT);
				}

				// WATER SEAM SHADE: the rendered vanilla ocean is darker than the raw stored water colour.
				// Pixel forensics 07-19 (transect across the vanilla->LOD boundary, shot 03.27.44): vanilla
				// surface ~(38,88,112) vs LOD ~(48,105,142) — a uniform ~0.80 on all three channels. One
				// flat factor, no distance ramp (fog owns the horizon); tunable for recalibration.
				float waterShade = job.waterSeamShade;
				// Seam fade REMOVED: ramping the first LOD ring's alpha made the LOD terrain SILHOUETTE
				// read as semi-transparent against the sky ("les lods mais en transparent"). LOD tiles
				// are fully opaque now; the vanilla->LOD transition is a plain hard edge again.
				int seamAlpha = 255;
				// Z1 GREEDY ROWS (pure efficiency, zero visual change): locally-flat land columns with
				// identical colour/light merge into ONE row-long top quad — on plains most of the near
				// zone collapses from 8 quads per row to 1. Only cells whose whole 3x3 chunk
				// neighbourhood is inside the blocky band qualify, so every neighbour comparison is an
				// exact step-1 column read (same semantics as the per-tile path — no boundary drift).
				if (job.nearGreedyMerge && job.nearPureColors && job.triangleFar && !insideVanilla && step == 1
						&& (float) (dcx * dcx + dcz * dcz) < job.zone1End * job.zone1End
						&& blockyInterior(job, chunkX, chunkZ)) {
					for (int subZ = 0; subZ < CELL && quads < job.maxQuads; subZ++) {
						quads = emitBlockyRowRuns(ctx, job, region, baseColumnX, baseColumnZ, cellX, cellZ + subZ,
							fogT, waterShade, quads);
					}
					continue;
				}
				for (int subZ = 0; subZ < CELL && quads < job.maxQuads; subZ += step) {
					for (int subX = 0; subX < CELL && quads < job.maxQuads; subX += step) {
						quads = emitTile(ctx, job, region, baseColumnX, baseColumnZ, cellX + subX, cellZ + subZ, step,
							fogT, waterShade, insideVanilla, vanillaOwns, seamAlpha, quads);
					}
				}
			}
		}
		return quads;
	}

	/**
	 * Emits one BLOCKY voxel tile: flat top + vertical walls to lower drawn neighbours, with material
	 * detail (soil strata, mushroom/standalone trees, dark water walls, jitter, AO, emissive).
	 */
	private static int emitTile(BuildCtx ctx, MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int localX, int localZ, int step,
			float fogT, float waterShade, boolean sunkenFill, boolean vanillaOwns, int seamAlpha, int quads) {
		int worldX = baseColumnX + localX;
		int worldZ = baseColumnZ + localZ;
		// Own column is always in the current region: direct array access, no hashmap.
		short h = region.spanY[((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS];
		if (h == Short.MIN_VALUE) {
			// Hole repair: old persisted data can miss single columns; fall back to the 4-block
			// generation grid corner so the map shows terrain instead of a hole.
			h = region.spanY[(((localZ & ~3) << PauCSurfaceColumnStore.REGION_SHIFT) | (localX & ~3)) * PauCSurfaceColumnStore.MAX_SPANS];
			if (h == Short.MIN_VALUE) {
				// Last resort: the 8-block CELL anchor — a fully missing 4-grid cell otherwise leaves a hole.
				h = region.spanY[(((localZ & ~7) << PauCSurfaceColumnStore.REGION_SHIFT) | (localX & ~7)) * PauCSurfaceColumnStore.MAX_SPANS];
				if (h == Short.MIN_VALUE) {
					return quads;
				}
			}
		}
		ctx.emitAlpha = seamAlpha;
		// NETHER (ceiling dim): draw NO LOD inside the vanilla zone. The Nether is dense 3D terrain and
		// the LOD floor/ceiling/blob heights don't line up with the real blocks vanilla renders, so a
		// near fill tile floats as a pale slab mixing INTO the vanilla netherrack. The LOD is only for the
		// enclosed-cave HORIZON here, so skip everything within the vanilla radius.
		if (job.ceilingWorld && sunkenFill) {
			return quads;
		}
		if (vanillaOwns) {
			// Loaded chunk actually rendering: a heightfield plate CANNOT follow ravines/caves — it slices
			// them mid-air and z-fights the walls (F3-proven at -2233/-1826). Suppress inside vanilla's
			// render sphere, EXCEPT the overlap-dither tiles (DH cross-fade, user order — they must show).
			int odx0 = (worldX >> 4) - job.cameraChunkX;
			int odz0 = (worldZ >> 4) - job.cameraChunkZ;
			float orad0 = (float) Math.sqrt((double) (odx0 * odx0 + odz0 * odz0));
			float ot0 = (orad0 - (job.minChunkDistance - 3.0F)) / 3.0F;
			int oh0 = worldX * 374761393 ^ worldZ * 668265263;
			oh0 = (oh0 ^ (oh0 >>> 13)) * 1274126177;
			boolean ditherTile = ot0 > 0.0F && ((oh0 >>> 9) & 0xff) / 255.0F < ot0;
			if (!ditherTile) {
				float cxr = (float) (worldX + step * 0.5 - job.camX);
				float czr = (float) (worldZ + step * 0.5 - job.camZ);
				float dyr = (float) (job.camY - (h + 1));
				float rr = job.vanillaCullBlocks + 20.0F;
				if (cxr * cxr + czr * czr + dyr * dyr < rr * rr) {
					return quads;
				}
			}
		}
		// Vanilla owns this chunk (mature-loaded): suppress the fill ONLY where vanilla can actually
		// DRAW it — inside the 3D fog SPHERE. Sections are culled spherically: at altitude, and in the
		// loaded-square corners, a chunk is loaded and "mature" yet never rendered — suppressing there
		// punched the white holes along the vanilla/LOD boundary (static AND moving). The sphere is
		// slightly LARGER than vanilla's (an undersized one left a band where LOD canopies mixed into
		// real jungle trees); fill tiles are sunken/ground-only so the small overlap stays invisible.

		long rSum = 0;
		long gSum = 0;
		long bSum = 0;
		int samples = 0;
		int treeSamples = 0;
		int waterSamples = 0;
		int soilSamples = 0;
		int floatingSamples = 0;
		int maxBlockLight = 0;
		// WATER-ONLY accumulators: a coarse tile straddling a shore/river mixes water + land columns. Using
		// the whole-tile colour average paints water PALE (blue+sand), and taking the surface Y from the
		// origin column terraces the water onto land height. Track the water columns alone so a water tile
		// renders as a FLAT sea-level plane in pure water blue (fixes the "pale stepped water" at banks).
		long waterR = 0;
		long waterG = 0;
		long waterB = 0;
		int waterColCount = 0;
		short waterSurfaceY = Short.MIN_VALUE;
		for (int dz = 0; dz < step; dz++) {
			for (int dx = 0; dx < step; dx++) {
				int base = (((localZ + dz) << PauCSurfaceColumnStore.REGION_SHIFT) | (localX + dx)) * PauCSurfaceColumnStore.MAX_SPANS;
				if (region.spanY[base] == Short.MIN_VALUE) {
					continue;
				}
				int color = region.spanColor[base];
				rSum += (color >> 16) & 0xff;
				gSum += (color >> 8) & 0xff;
				bSum += color & 0xff;
				samples++;
				int alpha = (color >>> 24) & 0xff;
				if (PauCSurfaceColumnStore.isTreeAlpha(alpha)) {
					treeSamples++;
				} else if (alpha == PauCSurfaceColumnStore.WATER_ALPHA) {
					waterSamples++;
					waterR += (color >> 16) & 0xff;
					waterG += (color >> 8) & 0xff;
					waterB += color & 0xff;
					waterColCount++;
					// Water surface Y = the highest water span (they all sit at sea level; a max guards mixed data).
					if (waterSurfaceY == Short.MIN_VALUE || region.spanY[base] > waterSurfaceY) {
						waterSurfaceY = region.spanY[base];
					}
				} else if (alpha == PauCSurfaceColumnStore.SOIL_ALPHA) {
					soilSamples++;
				} else if (alpha == PauCSurfaceColumnStore.FLOATING_ALPHA) {
					floatingSamples++;
				}
				maxBlockLight = Math.max(maxBlockLight, region.spanLight[base] & 0xF);
			}
		}
		if (samples == 0) {
			// Same hole-repair path for the colour: read the 4-grid corner column.
			int base = ((((localZ & ~3)) << PauCSurfaceColumnStore.REGION_SHIFT) | (localX & ~3)) * PauCSurfaceColumnStore.MAX_SPANS;
			if (region.spanY[base] == Short.MIN_VALUE) {
				return quads;
			}
			int color = region.spanColor[base];
			rSum = (color >> 16) & 0xff;
			gSum = (color >> 8) & 0xff;
			bSum = color & 0xff;
			samples = 1;
		}
		float rBase = rSum / (float) samples;
		float gBase = gSum / (float) samples;
		float bBase = bSum / (float) samples;
		boolean tree = treeSamples * 2 >= samples;
		boolean waterTile = waterSamples * 2 >= samples;
		boolean soil = soilSamples * 2 >= samples;
		if (waterTile && waterColCount > 0) {
			// Pure water blue from the water columns alone (not the pale whole-tile average), and a FLAT
			// surface at the real water level (not the origin column's land height) — kills the pale
			// terraced water at shores/rivers. Open ocean (all-water tiles) is unchanged.
			rBase = waterR / (float) waterColCount;
			gBase = waterG / (float) waterColCount;
			bBase = waterB / (float) waterColCount;
			if (waterSurfaceY != Short.MIN_VALUE) {
				h = waterSurfaceY;
			}
		}
		// Block-light emissive (torches, lava, glowstone): lifts the night ambient so lit areas glow
		// warm in the distance, exactly like the near world.
		float emissive = maxBlockLight / 15.0F;

		// LOT 2 — NEAR BLOCKY REFINEMENT (computed here so the colour path below can see it): inside
		// the fine band (step 1) the tile is emitted BLOCKY (flat top + walls, the Minecraft look)
		// instead of the smooth triangle base. step >= 2 stays triangle.
		boolean blockyNear = false;
		if (job.triangleFar && !waterTile && !sunkenFill && step == 1) {
			int rdx = (worldX >> 4) - job.cameraChunkX;
			int rdz = (worldZ >> 4) - job.cameraChunkZ;
			blockyNear = (rdx * rdx + rdz * rdz) < job.zone1End * job.zone1End;
		}

		// Per-tile tone jitter: kills the flat plastic look. Water stays a smooth sheet and takes the
		// DISTANCE gradient instead (vanilla tone at the seam → darker at the horizon). The NEAR blocky
		// zone renders the RAW sampled colours instead: right next to the real chunks the noise read as
		// speckle against vanilla's uniform biome tint (nearPureColors, default on).
		if (blockyNear && job.nearPureColors) {
			// raw colours
		} else if (!waterTile) {
			float jitter = 0.955F + 0.09F * hash01(worldX, worldZ);
			rBase *= jitter;
			gBase *= jitter;
			bBase *= jitter;
		} else {
			rBase *= waterShade;
			gBase *= waterShade;
			bBase *= waterShade;
		}

		float x0 = (float) (worldX - job.camX);
		float z0 = (float) (worldZ - job.camZ);
		float x1 = x0 + step;
		float z1 = z0 + step;
		float yTop = (float) (h + 1 - job.camY);
		// FLOATING STRUCTURE (player build / bridge / platform / overhang): draw a floating SLAB over the
		// real ground below it — never a solid column to the ground. Same primitive as End islands + a
		// ground layer. This is what stops sky-builds from walling off the air beneath them.
		if (floatingSamples * 2 >= samples) {
			return emitFloatingTile(ctx, job, region, localX, localZ, x0, z0, step,
				rBase, gBase, bBase, fogT, emissive, quads);
		}
		if (waterTile) {
			// ONE uniform water plane EVERYWHERE (-0.15: under vanilla's ~0.889 surface, no z-fight,
			// and identical in fill and ring zones — every per-zone offset difference showed as a
			// zigzag slit seam across open water).
			yTop -= 0.15F;
		}
		if (sunkenFill) {
			// Vanilla-zone under-fill sits below the real surface: covered by vanilla wherever it
			// renders, visible wherever it is not. WATER fill uses a tiny extra offset only (-0.04,
			// total -0.15, still under vanilla water at ~-0.11): the old -0.25 left an OPEN SLIT along
			// the fill/ring boundary between water planes — no wall is emitted there (stored heights
			// are equal) and the sky showed through the slit as whitish bands at grazing angles.
			// DH-STYLE OVERLAP DITHER (user order): over the outer ~3 chunks of the vanilla square, a
			// hash-selected, densifying subset of tiles draws just ABOVE the vanilla surface — the LOD
			// speckles in over the real terrain and takes over seamlessly at the boundary.
			int odx = (worldX >> 4) - job.cameraChunkX;
			int odz = (worldZ >> 4) - job.cameraChunkZ;
			float orad = (float) Math.sqrt((double) (odx * odx + odz * odz));
			float ot = (orad - (job.minChunkDistance - 3.0F)) / 3.0F;
			int oh = worldX * 374761393 ^ worldZ * 668265263;
			oh = (oh ^ (oh >>> 13)) * 1274126177;
			if (!waterTile && ot > 0.0F && ((oh >>> 9) & 0xff) / 255.0F < ot) {
				yTop += 0.06F; // dithered overlay tile of the cross-fade
			} else {
				yTop -= waterTile ? 0.0F : 0.25F; // hidden continuity plate under vanilla
			}
		}

		short hE = neighbourEdgeMinTop(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ, step, true);
		short hW = neighbourEdgeMinTop(job, region, baseColumnX, baseColumnZ, worldX - 1, worldZ, step, true);
		short hS = neighbourEdgeMinTop(job, region, baseColumnX, baseColumnZ, worldX, worldZ + step, step, false);
		short hN = neighbourEdgeMinTop(job, region, baseColumnX, baseColumnZ, worldX, worldZ - 1, step, false);

		float xShade = waterTile ? 0.35F : 0.60F;
		float zShade = waterTile ? 0.40F : 0.80F;

		if (tree) {
			int tBase = ((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS;
			short g1 = region.spanY[tBase + 1];
			int g1Color = scrubGroundColor(region.spanColor[tBase + 1]);
			if (sunkenFill) {
				// FILL-ZONE tree tile: GROUND ONLY. Vanilla draws (or is about to draw) its own trees
				// here — a full LOD canopy/trunk mixed into the real jungle. The fill's only job is to
				// close sky holes until the vanilla mesh lands.
				if (g1 != Short.MIN_VALUE && g1Color != 0 && quads < job.maxQuads) {
					float fgy = (float) (g1 + 1 - job.camY) - 0.25F;
					emitQuad(ctx, job, x0, fgy, z0, x0, fgy, z0 + step, x0 + step, fgy, z0 + step, x0 + step, fgy, z0,
						((g1Color >> 16) & 0xff) * 0.62F, ((g1Color >> 8) & 0xff) * 0.62F, (g1Color & 0xff) * 0.62F,
						1.0F, fogT, emissive);
					quads++;
					return quads;
				}
			} else { // trees are IMPOSTERS ONLY — the boxy LOD-tree path is DELETED (user demand)
				// 0.6.1 IMPOSTER MODE: the terrain draws only the forest FLOOR, CLOSED with walls; the
				// isolated PauCTreeImposterRenderer paints the canopy as a billboard. No canopy/trunk here
				// — that is the speed point. A bare ground quad (no walls) left the floor see-through under
				// the imposters on any slope and at region seams; emitForestFloorTile walls it shut.
				if (g1 == Short.MIN_VALUE || g1Color == 0) {
					// No stored ground under this tree column: derive it (canopy minus a typical trunk) so the
					// tile NEVER leaks into the generic canopy-coloured triangle (the "LOD tree" cones).
					g1 = (short) (h - 6);
					g1Color = 0xff5E4A33;
				}
				if (g1 != Short.MIN_VALUE && g1Color != 0) {
					if (job.triangleFar && !blockyNear && quads < job.maxQuads) {
						// TRIANGLE BASE under the imposters: sloped ground quad through the shared corner
						// grounds (cornerHeightAt maps tree columns to span-1) — watertight with the
						// surrounding triangle base, no walls, no seams. Canopy = billboard.
						float gtr = ((g1Color >> 16) & 0xff) * 0.62F;
						float gtg = ((g1Color >> 8) & 0xff) * 0.62F;
						float gtb = (g1Color & 0xff) * 0.62F;
						short t00 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, worldX, worldZ, g1);
						short t10 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ, g1);
						short t01 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, worldX, worldZ + step, g1);
						short t11 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ + step, g1);
						emitQuad(ctx, job, x0, (float) (t00 + 1 - job.camY), z0, x0, (float) (t01 + 1 - job.camY), z0 + step,
							x0 + step, (float) (t11 + 1 - job.camY), z0 + step, x0 + step, (float) (t10 + 1 - job.camY), z0,
							gtr, gtg, gtb, 1.0F, fogT, emissive);
						return quads + 1;
					}
					return emitForestFloorTile(ctx, job, region, baseColumnX, baseColumnZ, localX, localZ,
						x0, z0, step, g1, g1Color, worldX, worldZ, fogT, emissive, quads);
				}
			}
		}

		if (waterTile) {
			// TRANSLUCENT WATER (must be routed BEFORE the surface quad is emitted): first the opaque
			// FLOOR into the main mesh, then every water surface/wall goes to the translucent builder.
			int wBase = ((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS;
			short floorY = region.spanY[wBase + 1];
			int floorColor = scrubGroundColor(region.spanColor[wBase + 1]);
			if (floorY == Short.MIN_VALUE || floorColor == 0) {
				// NO stored floor: a translucent surface with nothing behind reads as a HOLE in the
				// sea. Synthesise a dark floor 3 blocks under the surface.
				floorY = (short) (h - 3);
				floorColor = 0xff000000
					| ((((int) (rBase * 0.45F)) & 0xff) << 16)
					| ((((int) (gBase * 0.45F)) & 0xff) << 8)
					| (((int) (bBase * 0.45F)) & 0xff);
			}
			if (job.triangleFar && quads < job.maxQuads) {
				// TRIANGLE SEA FLOOR: the same watertight sheet as land — corners on neighbouring floor
				// heights, shores included (the floor RISES to the bank), no floor walls, no plate seams:
				// seeing under the map through water becomes impossible by construction.
				float wfr = (floorColor >> 16) & 0xff;
				float wfg = (floorColor >> 8) & 0xff;
				float wfb = floorColor & 0xff;
				short f00 = floorCornerAt(job, region, baseColumnX, baseColumnZ, worldX, worldZ, floorY);
				short f10 = floorCornerAt(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ, floorY);
				short f01 = floorCornerAt(job, region, baseColumnX, baseColumnZ, worldX, worldZ + step, floorY);
				short f11 = floorCornerAt(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ + step, floorY);
				emitQuad(ctx, job, x0, (float) (f00 + 1 - job.camY), z0, x0, (float) (f01 + 1 - job.camY), z1,
					x1, (float) (f11 + 1 - job.camY), z1, x1, (float) (f10 + 1 - job.camY), z0,
					wfr, wfg, wfb, 1.0F, fogT, 0.0F);
				quads++;
			} else if (quads < job.maxQuads) {
				float fy = (float) (floorY + 1 - job.camY) - (sunkenFill ? 0.25F : 0.0F);
				float fr = (floorColor >> 16) & 0xff;
				float fg = (floorColor >> 8) & 0xff;
				float fb = floorColor & 0xff;
				emitQuad(ctx, job, x0, fy, z0, x0, fy, z1, x1, fy, z1, x1, fy, z0, fr, fg, fb, 1.0F, fogT, 0.0F);
				quads++;
				// FLOOR WALLS: without verticals between floor levels the seabed relief reads as
				// DISCONNECTED PLATES — gaps at every region seam (64 blocks) let you see through the
				// floor, which after the solid vanilla floor moves away reads as "no more sea floor".
				// Now snapshot-aware: neighbour floors are looked up ACROSS region boundaries too, so the
				// seabed is one continuous surface, seams closed.
				for (int dir = 0; dir < 4 && quads < job.maxQuads; dir++) {
					int nwx = worldX + (dir == 0 ? step : dir == 1 ? -step : 0);
					int nwz = worldZ + (dir == 2 ? step : dir == 3 ? -step : 0);
					short nFloor = floorYAt(job, region, baseColumnX, baseColumnZ, nwx, nwz);
					if (nFloor != Short.MIN_VALUE && nFloor >= floorY) {
						continue; // neighbour seabed at or above ours — no open edge to close
					}
					float nfy;
					if (nFloor == Short.MIN_VALUE) {
						// No neighbour floor. Two very different cases:
						//  - the neighbour COLUMN exists (land / shallows: span-0 set, span-1 empty) — its own
						//    surface + walls close this edge; a skirt here hangs a dark curtain under the
						//    shoreline (the old "hanging dark curtains" regression). Skip.
						//  - the neighbour column is genuinely VOID (re-generation frontier, seabed not sampled
						//    yet): without a wall the plate's open underside is seen through the water ("on voit
						//    sous la carte"). Drop a skirt deep enough to hide the underside.
						if (topYAt(job, region, baseColumnX, baseColumnZ, nwx, nwz) != Short.MIN_VALUE) {
							continue;
						}
						nfy = fy - Math.min((float) job.skirtDepth, 64.0F);
					} else {
						nfy = (float) (nFloor + 1 - job.camY) - (sunkenFill ? 0.25F : 0.0F);
					}
					if (dir == 0) {
						emitWallQuad(ctx, job, x1, z0, x1, z1, fy, nfy, fr, fg, fb, 0.6F, fogT, 0.0F);
					} else if (dir == 1) {
						emitWallQuad(ctx, job, x0, z0, x0, z1, fy, nfy, fr, fg, fb, 0.6F, fogT, 0.0F);
					} else if (dir == 2) {
						emitWallQuad(ctx, job, x0, z1, x1, z1, fy, nfy, fr, fg, fb, 0.8F, fogT, 0.0F);
					} else {
						emitWallQuad(ctx, job, x0, z0, x1, z0, fy, nfy, fr, fg, fb, 0.8F, fogT, 0.0F);
					}
					quads++;
				}
			}
			ctx.emitTarget = ctx.water;
			ctx.emitAlpha = 185; // denser surface: rain/night scenes no longer glow floor-bright cyan
		}
		if (job.triangleFar && !waterTile && !blockyNear && !sunkenFill) {
			// TRIANGLE BASE (all bands): one SLOPED top quad (2 GPU triangles) through the 4 shared corner
			// heights — a smooth watertight surface, NO vertical walls, no skirts, no fill special cases.
			// The complete base is ALWAYS drawable (worst case = smooth, never a hole); the blocky look
			// returns as a budgeted near REFINEMENT pass (lot 2). Water keeps its flat surface path.
			// Z2->coarse STITCH: fine tiles near the zone-2 edge sample their corners ON the 4-grid, so
			// their surface coincides exactly with the coarse sheet - the mixed-grid cracks (permanent
			// slope holes) become impossible by construction.
			int sxa = worldX, sza = worldZ, sxb = worldX + step, szb = worldZ + step;
			if (step == 2) {
				int zdx = (worldX >> 4) - job.cameraChunkX;
				int zdz = (worldZ >> 4) - job.cameraChunkZ;
				float edge = job.zone2End - 3.0F;
				if (zdx * zdx + zdz * zdz >= edge * edge) {
					sxa = worldX & ~3; sza = worldZ & ~3; sxb = sxa + 4; szb = sza + 4;
				}
			}
			short c00 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, sxa, sza, h);
			short c10 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, sxb, sza, h);
			short c01 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, sxa, szb, h);
			short c11 = cornerHeightAt(job, region, baseColumnX, baseColumnZ, sxb, szb, h);
			float y00 = (float) (c00 + 1 - job.camY);
			float y10 = (float) (c10 + 1 - job.camY);
			float y01 = (float) (c01 + 1 - job.camY);
			float y11 = (float) (c11 + 1 - job.camY);
			float triShade = 1.0F;
			if (job.terrainShading) {
				// Slope + NW-light shading baked per tile: creases and hillsides read as relief (the
				// zero-cost stand-in for a SSAO pass: no post-processing, pure vertex bake).
				float gx = (y10 + y11 - y00 - y01) / (2.0F * step);
				float gz = (y01 + y11 - y00 - y10) / (2.0F * step);
				float slope = Math.min(1.6F, (float) Math.sqrt(gx * gx + gz * gz));
				triShade = Math.max(0.74F, Math.min(1.05F, 1.02F - slope * 0.16F + (gx - gz) * 0.05F));
			}
			if (fogT < 0.6F) {
				// Deterministic per-tile brightness noise, distance-faded: breaks the flat colour
				// sheets (a noise texture, baked at mesh time instead of shaded per pixel).
				int nh = worldX * 668265263 ^ worldZ * 374761393;
				nh = (nh ^ (nh >>> 13)) * 1274126177;
				triShade *= 1.0F + (((nh >>> 9) & 0xff) / 255.0F - 0.5F) * 0.07F * (1.0F - fogT);
			}
			int spreadT = Math.max(Math.max(c00, c10), Math.max(c01, c11)) - Math.min(Math.min(c00, c10), Math.min(c01, c11));
			if (job.biomeBlendGradient && spreadT <= 6) {
				// Per-corner colours interpolated across the quad (Gouraud): biome edges melt into
				// each other instead of hard cell borders.
				emitQuadGradient(ctx, job,
					x0, y00, z0, x0, y01, z1, x1, y11, z1, x1, y10, z0,
					cornerColorAt(job, region, baseColumnX, baseColumnZ, worldX, worldZ, rBase, gBase, bBase),
					cornerColorAt(job, region, baseColumnX, baseColumnZ, worldX, worldZ + step, rBase, gBase, bBase),
					cornerColorAt(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ + step, rBase, gBase, bBase),
					cornerColorAt(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ, rBase, gBase, bBase),
					triShade, fogT, emissive);
			} else {
				emitQuad(ctx, job, x0, y00, z0, x0, y01, z1, x1, y11, z1, x1, y10, z0, rBase, gBase, bBase, triShade, fogT, emissive);
			}
			return quads + 1;
		}
		// Flat top quad. In the near blocky zone the four corners take a vanilla-style smooth-lighting
		// term (higher neighbours darken the corner they touch) so the LOD relief reads exactly like
		// vanilla's ambient occlusion instead of uniformly-lit plates.
		if (blockyNear && job.nearVertexAo) {
			short dNW = topYFast(job, region, baseColumnX, baseColumnZ, worldX - 1, worldZ - 1);
			short dNE = topYFast(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ - 1);
			short dSW = topYFast(job, region, baseColumnX, baseColumnZ, worldX - 1, worldZ + step);
			short dSE = topYFast(job, region, baseColumnX, baseColumnZ, worldX + step, worldZ + step);
			float aoNW = cornerAo(h, hW, hN, dNW);
			float aoSW = cornerAo(h, hW, hS, dSW);
			float aoSE = cornerAo(h, hE, hS, dSE);
			float aoNE = cornerAo(h, hE, hN, dNE);
			if (aoNW < 1.0F || aoSW < 1.0F || aoSE < 1.0F || aoNE < 1.0F) {
				emitQuadGradient(ctx, job, x0, yTop, z0, x0, yTop, z1, x1, yTop, z1, x1, yTop, z0,
					packF(rBase * aoNW, gBase * aoNW, bBase * aoNW),
					packF(rBase * aoSW, gBase * aoSW, bBase * aoSW),
					packF(rBase * aoSE, gBase * aoSE, bBase * aoSE),
					packF(rBase * aoNE, gBase * aoNE, bBase * aoNE),
					1.0F, fogT, emissive);
			} else {
				emitQuad(ctx, job, x0, yTop, z0, x0, yTop, z1, x1, yTop, z1, x1, yTop, z0, rBase, gBase, bBase, 1.0F, fogT, emissive);
			}
		} else {
			emitQuad(ctx, job, x0, yTop, z0, x0, yTop, z1, x1, yTop, z1, x1, yTop, z0, rBase, gBase, bBase, 1.0F, fogT, emissive);
		}
		quads++;
		if (sunkenFill) {
			return quads; // vanilla-square fill: TOP ONLY — walls made big cubes, triangles made curtains
		}

		// Walls: soil gets dirt (grass is green on top only) with stone below the dirt band; trees get
		// the mushroom two-tone; water gets its own colour at depth-dark shading; stone/sand as-is.
		float wallR = soil ? dirtR : rBase;
		float wallG = soil ? dirtG : gBase;
		float wallB = soil ? dirtB : bBase;
		// Floating underside / water floor: span 1 of the origin column.
		short spanBottom = region.spanY[((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS + 1];
		float floatBottom = spanBottom != Short.MIN_VALUE
			? Math.min(yTop - 1.0F, (float) (spanBottom - job.camY))
			: yTop - FLOAT_SKIRT_BLOCKS;
		// WATER against a VOID neighbour (the re-generation frontier crossing open ocean): a wall IS
		// needed (no wall = sky holes at the water's edge), but at SURFACE COLOUR full shade — from
		// above it reads as more water (invisible), unlike the dark side-shaded walls (bright/dark
		// line crawling across the sea) or a missing wall (holes). No cap either way.
		float ybE = hE == VOID_NEIGHBOUR ? floatBottom : wallBottom(hE, yTop, job.skirtDepth, job.camY);
		float ybW = hW == VOID_NEIGHBOUR ? floatBottom : wallBottom(hW, yTop, job.skirtDepth, job.camY);
		float ybS = hS == VOID_NEIGHBOUR ? floatBottom : wallBottom(hS, yTop, job.skirtDepth, job.camY);
		float ybN = hN == VOID_NEIGHBOUR ? floatBottom : wallBottom(hN, yTop, job.skirtDepth, job.camY);
		if (blockyNear) {
			// BOUNDARY UP-WALLS: inside the blocky zone a HIGHER neighbour closes the step with its own
			// down-wall, but a TRIANGLE neighbour emits no walls at all — where the triangle surface
			// rises above a boundary blocky tile the step stayed OPEN (sky wedges along the refinement
			// ring; pink speckles at dusk). Close upward when the higher neighbour is beyond the zone.
			quads = emitBoundaryUpWall(ctx, job, hE, h, x1, z0, x1, z1, worldX + step + 1, worldZ + (step >> 1), wallR, wallG, wallB, 0.60F, fogT, emissive, quads);
			quads = emitBoundaryUpWall(ctx, job, hW, h, x0, z0, x0, z1, worldX - 2, worldZ + (step >> 1), wallR, wallG, wallB, 0.60F, fogT, emissive, quads);
			quads = emitBoundaryUpWall(ctx, job, hS, h, x0, z1, x1, z1, worldX + (step >> 1), worldZ + step + 1, wallR, wallG, wallB, 0.80F, fogT, emissive, quads);
			quads = emitBoundaryUpWall(ctx, job, hN, h, x0, z0, x1, z0, worldX + (step >> 1), worldZ - 2, wallR, wallG, wallB, 0.80F, fogT, emissive, quads);
		}
		if (waterTile) {
			if (hE == VOID_NEIGHBOUR) {
				emitWallQuad(ctx, job, x1, z0, x1, z1, yTop, ybE, rBase, gBase, bBase, 1.0F, fogT, emissive);
				quads++;
				ybE = yTop;
			}
			if (hW == VOID_NEIGHBOUR) {
				emitWallQuad(ctx, job, x0, z0, x0, z1, yTop, ybW, rBase, gBase, bBase, 1.0F, fogT, emissive);
				quads++;
				ybW = yTop;
			}
			if (hS == VOID_NEIGHBOUR) {
				emitWallQuad(ctx, job, x0, z1, x1, z1, yTop, ybS, rBase, gBase, bBase, 1.0F, fogT, emissive);
				quads++;
				ybS = yTop;
			}
			if (hN == VOID_NEIGHBOUR) {
				emitWallQuad(ctx, job, x0, z0, x1, z0, yTop, ybN, rBase, gBase, bBase, 1.0F, fogT, emissive);
				quads++;
				ybN = yTop;
			}
		}
		// PHASE 3 — NO TALL COLUMNS in volumetric dims: cap how far any wall may drop. In the End/Nether
		// an isolated high tile beside a much lower neighbour otherwise dropped a long vertical column
		// into the void; capped, it becomes a short skirt (the tile's own thin body closes underneath).
		if (job.floatingWorld || job.ceilingWorld) {
			float wallCap = yTop - 16.0F;
			if (ybE < wallCap) { ybE = wallCap; }
			if (ybW < wallCap) { ybW = wallCap; }
			if (ybS < wallCap) { ybS = wallCap; }
			if (ybN < wallCap) { ybN = wallCap; }
		}
		quads = emitWall(ctx, job, x1, z0, x1, z1, yTop, ybE, rBase, gBase, bBase, wallR, wallG, wallB, xShade, fogT, emissive, tree, soil, quads);
		quads = emitWall(ctx, job, x0, z0, x0, z1, yTop, ybW, rBase, gBase, bBase, wallR, wallG, wallB, xShade, fogT, emissive, tree, soil, quads);
		quads = emitWall(ctx, job, x0, z1, x1, z1, yTop, ybS, rBase, gBase, bBase, wallR, wallG, wallB, zShade, fogT, emissive, tree, soil, quads);
		quads = emitWall(ctx, job, x0, z0, x1, z0, yTop, ybN, rBase, gBase, bBase, wallR, wallG, wallB, zShade, fogT, emissive, tree, soil, quads);

		// BOTTOM CAP at the island's real underside: any VOID edge (rims, floating structures) closes;
		// in end-like dimensions EVERY tile closes (islands are seen from below).
		boolean voidEdge = !waterTile
			&& (hE == VOID_NEIGHBOUR || hW == VOID_NEIGHBOUR || hS == VOID_NEIGHBOUR || hN == VOID_NEIGHBOUR);
		if ((voidEdge || job.floatingWorld) && quads < job.maxQuads) {
			emitQuad(ctx, job, x0, floatBottom, z0, x0, floatBottom, z1, x1, floatBottom, z1, x1, floatBottom, z0, wallR, wallG, wallB, 0.45F, fogT, emissive);
			quads++;
		}
		if (waterTile) {
			ctx.waterQuads++;
			ctx.emitTarget = ctx.main;
			ctx.emitAlpha = 255;
		}
		// CEILING (Nether volumetric layer, span 2): a downward slab + walls to higher neighbour ceilings,
		// so the distant Nether reads as an ENCLOSED cave instead of a floor under open sky. Only reached
		// for drawn tiles (vanilla-suppressed tiles already returned), so it never doubles vanilla's roof.
		if (job.ceilingWorld && quads < job.maxQuads) {
			// Phase-2 floating PLATFORMS (span 1) REMOVED: at distance they projected into the open cave
			// air and were the main source of the jumbled floor/ceiling MIXING. A heightfield can't place
			// solids in mid-air cleanly — that needs a real voxel model. Bare floor + ceiling shell only.
			int cBase = ((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS + 2;
			short ceilY = region.spanY[cBase];
			int ceilColor = region.spanColor[cBase];
			if (ceilY != Short.MIN_VALUE && ceilColor != 0) {
				float cy = (float) (ceilY - job.camY);
				float cr = (ceilColor >> 16) & 0xff;
				float cg = (ceilColor >> 8) & 0xff;
				float cb = ceilColor & 0xff;
				emitQuad(ctx, job, x0, cy, z0, x0, cy, z1, x1, cy, z1, x1, cy, z0, cr, cg, cb, 1.0F, fogT, 0.0F);
				quads++;
				for (int dir = 0; dir < 4 && quads < job.maxQuads; dir++) {
					int nwx = worldX + (dir == 0 ? step : dir == 1 ? -step : 0);
					int nwz = worldZ + (dir == 2 ? step : dir == 3 ? -step : 0);
					short nCeil = ceilingYAt(job, region, baseColumnX, baseColumnZ, nwx, nwz);
					if (nCeil == Short.MIN_VALUE || nCeil <= ceilY) {
						continue;
					}
					float ncy = (float) (nCeil - job.camY);
					if (dir == 0) {
						emitWallQuad(ctx, job, x1, z0, x1, z1, ncy, cy, cr, cg, cb, 0.6F, fogT, 0.0F);
					} else if (dir == 1) {
						emitWallQuad(ctx, job, x0, z0, x0, z1, ncy, cy, cr, cg, cb, 0.6F, fogT, 0.0F);
					} else if (dir == 2) {
						emitWallQuad(ctx, job, x0, z1, x1, z1, ncy, cy, cr, cg, cb, 0.8F, fogT, 0.0F);
					} else {
						emitWallQuad(ctx, job, x0, z0, x1, z0, ncy, cy, cr, cg, cb, 0.8F, fogT, 0.0F);
					}
					quads++;
				}
			}
		}
		return quads;
	}

	/**
	 * Dense-forest tree tile with stored ground: the real ground surface (span 1, canopy-shaded) with
	 * dirt walls down to the neighbours' ground (tree-aware, region-local like the sea-floor walls),
	 * a floating 3-block leaf slab closed underneath at the canopy top, and a 1x1 bark trunk on the
	 * 4-aligned tree-cell anchor (one per cell — the same cells the generator plants).
	 */
	/**
	 * Floating structure tile: the real GROUND (span 2) drawn flat below, and the structure itself
	 * (span 0 top .. span 1 bottom) as a closed floating BOX above it — top, short side walls, bottom
	 * cap. No wall runs down to the ground, so the air beneath a sky-build stays open (the whole point).
	 */
	private static int emitFloatingTile(BuildCtx ctx, MeshJob job, PauCSurfaceColumnStore.Region region,
			int localX, int localZ, float x0, float z0, int step,
			float rBase, float gBase, float bBase, float fogT, float emissive, int quads) {
		float x1 = x0 + step;
		float z1 = z0 + step;
		int base = ((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS;
		short topY = region.spanY[base];
		short botY = region.spanY[base + 1];
		short groundY = region.spanY[base + 2];
		int groundColor = region.spanColor[base + 2];
		// Ground below (so the terrain under the structure is not void).
		if (groundY != Short.MIN_VALUE && groundColor != 0 && quads < job.maxQuads) {
			float gyGround = (float) (groundY + 1 - job.camY);
			emitQuad(ctx, job, x0, gyGround, z0, x0, gyGround, z1, x1, gyGround, z1, x1, gyGround, z0,
				(groundColor >> 16) & 0xff, (groundColor >> 8) & 0xff, groundColor & 0xff, 1.0F, fogT, 0.0F);
			quads++;
		}
		// The structure as a closed floating box.
		float sTop = (float) (topY + 1 - job.camY);
		float sBot = botY != Short.MIN_VALUE ? (float) (botY - job.camY) : sTop - 2.0F;
		if (sBot >= sTop) {
			sBot = sTop - 1.0F;
		}
		return emitBox(ctx, job, x0, z0, x1, z1, sBot, sTop, rBase, gBase, bBase, fogT, emissive, true, quads);
	}

	private static int emitCanopyTile(BuildCtx ctx, MeshJob job, PauCSurfaceColumnStore.Region region, int localX, int localZ,
			float x0, float z0, int step, float yTop, short groundY, int groundColor,
			float rLeaf, float gLeaf, float bLeaf, int worldX, int worldZ, float fogT, float emissive, int quads) {
		float x1 = x0 + step;
		float z1 = z0 + step;
		float gy = (float) (groundY + 1 - job.camY);
		// Canopy shadow: ground under a closed canopy is dark in vanilla too.
		float gr = ((groundColor >> 16) & 0xff) * 0.62F;
		float gg = ((groundColor >> 8) & 0xff) * 0.62F;
		float gb = (groundColor & 0xff) * 0.62F;
		if (quads < job.maxQuads) {
			emitQuad(ctx, job, x0, gy, z0, x0, gy, z1, x1, gy, z1, x1, gy, z0, gr, gg, gb, 1.0F, fogT, emissive);
			quads++;
		}
		int rs = PauCSurfaceColumnStore.REGION_SIZE;
		int ms = PauCSurfaceColumnStore.MAX_SPANS;
		int sh = PauCSurfaceColumnStore.REGION_SHIFT;
		for (int dir = 0; dir < 4 && quads < job.maxQuads; dir++) {
			int nx = localX + (dir == 0 ? step : dir == 1 ? -step : 0);
			int nz = localZ + (dir == 2 ? step : dir == 3 ? -step : 0);
			if (nx < 0 || nz < 0 || nx >= rs || nz >= rs) {
				continue; // cross-region edge: hidden under the canopy — skip, like the sea-floor walls
			}
			int nBase = ((nz << sh) | nx) * ms;
			short nGround = region.spanY[nBase];
			if (PauCSurfaceColumnStore.isTreeAlpha(region.spanColor[nBase] >>> 24)
				&& region.spanY[nBase + 1] != Short.MIN_VALUE) {
				nGround = region.spanY[nBase + 1]; // tree neighbour: compare grounds, not canopy tops
			}
			if (nGround == Short.MIN_VALUE) {
				continue;
			}
			float yb = wallBottom(nGround, gy, job.skirtDepth, job.camY);
			float shade = dir < 2 ? 0.60F : 0.80F;
			if (dir == 0) {
				quads = emitWall(ctx, job, x1, z0, x1, z1, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			} else if (dir == 1) {
				quads = emitWall(ctx, job, x0, z0, x0, z1, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			} else if (dir == 2) {
				quads = emitWall(ctx, job, x0, z1, x1, z1, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			} else {
				quads = emitWall(ctx, job, x0, z0, x1, z0, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			}
		}
		// Floating canopy slab, closed underneath — depth scales with tree height (short oaks keep a
		// 3-block pancake; tall spruces/jungle giants carry foliage over ~half their height, far closer
		// to their real silhouette than a lollipop). Internal faces between canopies stay hidden.
		float slabBottom = Math.max(gy + 1.0F, yTop - Math.min(10.0F, Math.max(3.0F, (yTop - gy) * 0.45F)));
		quads = emitBox(ctx, job, x0, z0, x1, z1, slabBottom, yTop, rLeaf, gLeaf, bLeaf, fogT, emissive, true, quads);
		// 1x1 trunk on the 4-aligned cell anchor (~1 per 4x4 blocks in sampled forests too).
		boolean trunkHere = step >= 4 || ((worldX & 3) == 2 && (worldZ & 3) == 2);
		if (trunkHere && slabBottom - gy > 0.75F) {
			float cx = x0 + step * 0.5F;
			float cz = z0 + step * 0.5F;
			quads = emitBox(ctx, job, cx - 0.5F, cz - 0.5F, cx + 0.5F, cz + 0.5F, gy, slabBottom, trunkR, trunkG, trunkB, fogT, emissive, false, quads);
		}
		return quads;
	}

	/**
	 * IMPOSTER-mode forest tile: the ground under the (billboard-drawn) canopy — a flat top at the stored
	 * ground span, WALLED down to each neighbour's GROUND. Unlike {@link #emitCanopyTile} (whose canopy
	 * hid the seams, so it skipped cross-region edges) this looks up neighbour grounds ACROSS regions and
	 * skirts genuine void, because in imposter mode no terrain canopy covers the floor — a bare ground
	 * quad showed through on any slope and at every 64-block region seam. Tree neighbours are compared by
	 * their GROUND (span 1), not their canopy top, so a forest on a hillside steps cleanly with no gaps.
	 */
	private static int emitForestFloorTile(BuildCtx ctx, MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int localX, int localZ, float x0, float z0, int step,
			short groundY, int groundColor, int worldX, int worldZ, float fogT, float emissive, int quads) {
		float x1 = x0 + step;
		float z1 = z0 + step;
		float gy = (float) (groundY + 1 - job.camY);
		// Canopy shadow: ground under a closed canopy is dark in vanilla too (same 0.62 as emitCanopyTile).
		float gr = ((groundColor >> 16) & 0xff) * 0.62F;
		float gg = ((groundColor >> 8) & 0xff) * 0.62F;
		float gb = (groundColor & 0xff) * 0.62F;
		if (quads < job.maxQuads) {
			emitQuad(ctx, job, x0, gy, z0, x0, gy, z1, x1, gy, z1, x1, gy, z0, gr, gg, gb, 1.0F, fogT, emissive);
			quads++;
		}
		for (int dir = 0; dir < 4 && quads < job.maxQuads; dir++) {
			int nwx = worldX + (dir == 0 ? step : dir == 1 ? -step : 0);
			int nwz = worldZ + (dir == 2 ? step : dir == 3 ? -step : 0);
			short nGround = groundYAt(job, region, baseColumnX, baseColumnZ, nwx, nwz);
			if (nGround != Short.MIN_VALUE && nGround >= groundY) {
				continue; // neighbour ground at or above ours — no open edge
			}
			float yb = wallBottom(nGround, gy, job.skirtDepth, job.camY); // MIN_VALUE → skirt closes the void frontier
			float shade = dir < 2 ? 0.60F : 0.80F;
			if (dir == 0) {
				quads = emitWall(ctx, job, x1, z0, x1, z1, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			} else if (dir == 1) {
				quads = emitWall(ctx, job, x0, z0, x0, z1, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			} else if (dir == 2) {
				quads = emitWall(ctx, job, x0, z1, x1, z1, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			} else {
				quads = emitWall(ctx, job, x0, z0, x1, z0, gy, yb, gr, gg, gb, dirtR, dirtG, dirtB, shade, fogT, emissive, false, true, quads);
			}
		}
		return quads;
	}

	/**
	 * GROUND Y of a neighbour column for imposter forest-floor walls: a TREE column's ground is span 1
	 * (its span 0 is the canopy top); every other column uses span 0. {@link Short#MIN_VALUE} = void.
	 * Current-region fast path, snapshot lookup across region boundaries.
	 */
	private static short groundYAt(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int worldX, int worldZ) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		PauCSurfaceColumnStore.Region r;
		int idx;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			r = region;
			idx = ((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS;
		} else {
			r = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
			if (r == null) {
				return Short.MIN_VALUE;
			}
			idx = (((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
				| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1))) * PauCSurfaceColumnStore.MAX_SPANS;
		}
		short top = r.spanY[idx];
		if (top == Short.MIN_VALUE) {
			return Short.MIN_VALUE;
		}
		if (PauCSurfaceColumnStore.isTreeAlpha(r.spanColor[idx] >>> 24) && r.spanY[idx + 1] != Short.MIN_VALUE) {
			return r.spanY[idx + 1]; // tree neighbour: compare grounds, not canopy tops
		}
		return top;
	}

	/**
	 * The classic Minecraft tree: 1x1 trunk from the ground to the canopy, 3x3x1 leaf slab, 1x1 leaf
	 * cap on top ({@code yTop} = cap top). A darkened ground patch (canopy shadow) sits under the tree
	 * at the highest-neighbour level, and the tile closes its own sides down to each lower neighbour.
	 */
	private static int emitTreeModel(BuildCtx ctx, MeshJob job, float x0, float z0, int step, float yTop, float yGround,
			short hE, short hW, short hS, short hN,
			float rLeaf, float gLeaf, float bLeaf, float fogT, float emissive, int quads) {
		float x1 = x0 + step;
		float z1 = z0 + step;
		float gR = rLeaf * 0.55F;
		float gG = gLeaf * 0.55F;
		float gB = bLeaf * 0.55F;
		emitQuad(ctx, job, x0, yGround, z0, x0, yGround, z1, x1, yGround, z1, x1, yGround, z0, gR, gG, gB, 1.0F, fogT, emissive);
		quads++;
		// Close the tile sides from the ground patch down to each lower neighbour (dirt, like soil walls).
		quads = emitWall(ctx, job, x1, z0, x1, z1, yGround, wallBottom(hE, yGround, job.skirtDepth, job.camY), gR, gG, gB, dirtR, dirtG, dirtB, 0.60F, fogT, emissive, false, true, quads);
		quads = emitWall(ctx, job, x0, z0, x0, z1, yGround, wallBottom(hW, yGround, job.skirtDepth, job.camY), gR, gG, gB, dirtR, dirtG, dirtB, 0.60F, fogT, emissive, false, true, quads);
		quads = emitWall(ctx, job, x0, z1, x1, z1, yGround, wallBottom(hS, yGround, job.skirtDepth, job.camY), gR, gG, gB, dirtR, dirtG, dirtB, 0.80F, fogT, emissive, false, true, quads);
		quads = emitWall(ctx, job, x0, z0, x1, z0, yGround, wallBottom(hN, yGround, job.skirtDepth, job.camY), gR, gG, gB, dirtR, dirtG, dirtB, 0.80F, fogT, emissive, false, true, quads);

		float cx = x0 + step * 0.5F;
		float cz = z0 + step * 0.5F;
		float treeH = yTop - yGround;
		// CONIFER / JUNGLE silhouette for TALL trees (>= 9 blocks): a visible 2x2 bark trunk over the
		// bottom third, then a NARROWING 3-tier cone (3x3 -> 2x2 -> 1x1 cap). Short trees keep the round
		// oak shape (1x1 trunk + 3x3 slab + cap). A spruce drawn as an oak lollipop was the "incohérent".
		if (treeH >= 9.0F) {
			float canopyBase = yGround + treeH * 0.34F; // bare trunk over the bottom third
			// 2x2 trunk (thicker = reads as a real conifer stem at LOD distance).
			quads = emitBox(ctx, job, cx - 1.0F, cz - 1.0F, cx + 1.0F, cz + 1.0F, yGround, canopyBase, trunkR, trunkG, trunkB, fogT, emissive, false, quads);
			float coneH = yTop - canopyBase;
			float t1 = canopyBase + coneH * 0.45F; // bottom tier top
			float t2 = canopyBase + coneH * 0.78F; // mid tier top
			// Tier 1: widest (3x3), tier 2: 2x2, tier 3: 1x1 cap -> Christmas-tree cone.
			quads = emitBox(ctx, job, cx - 1.5F, cz - 1.5F, cx + 1.5F, cz + 1.5F, canopyBase, t1, rLeaf, gLeaf, bLeaf, fogT, emissive, true, quads);
			quads = emitBox(ctx, job, cx - 1.0F, cz - 1.0F, cx + 1.0F, cz + 1.0F, t1, t2, rLeaf, gLeaf, bLeaf, fogT, emissive, false, quads);
			quads = emitBox(ctx, job, cx - 0.5F, cz - 0.5F, cx + 0.5F, cz + 0.5F, t2, yTop, rLeaf, gLeaf, bLeaf, fogT, emissive, false, quads);
			return quads;
		}
		float capBottom = yTop - 1.0F;   // 1x1 leaf cap occupies [capBottom, yTop]
		// 3x3 foliage body: depth scales with tree height.
		float slabBottom = capBottom - Math.min(8.0F, Math.max(1.0F, treeH * 0.45F));
		float trunkTop = slabBottom;
		// Trunk: 1x1, variable height (ground to slab).
		if (trunkTop > yGround) {
			quads = emitBox(ctx, job, cx - 0.5F, cz - 0.5F, cx + 0.5F, cz + 0.5F, yGround, trunkTop, trunkR, trunkG, trunkB, fogT, emissive, false, quads);
		}
		// Leaf slab: 3x3 x 1.
		quads = emitBox(ctx, job, cx - 1.5F, cz - 1.5F, cx + 1.5F, cz + 1.5F, slabBottom, capBottom, rLeaf, gLeaf, bLeaf, fogT, emissive, true, quads);
		// Leaf cap: 1x1 x 1 on top.
		quads = emitBox(ctx, job, cx - 0.5F, cz - 0.5F, cx + 0.5F, cz + 0.5F, capBottom, yTop, rLeaf, gLeaf, bLeaf, fogT, emissive, true, quads);
		return quads;
	}

	/** Emits an axis-aligned box: 4 sides + top (+ bottom when {@code withBottom}). */
	private static int emitBox(BuildCtx ctx, MeshJob job, float bx0, float bz0, float bx1, float bz1, float yBottom, float yTop,
			float r, float g, float b, float fogT, float emissive, boolean withBottom, int quads) {
		if (quads + 6 > job.maxQuads) {
			return quads;
		}
		emitQuad(ctx, job, bx0, yTop, bz0, bx0, yTop, bz1, bx1, yTop, bz1, bx1, yTop, bz0, r, g, b, 1.0F, fogT, emissive);
		quads++;
		emitWallQuad(ctx, job, bx1, bz0, bx1, bz1, yTop, yBottom, r, g, b, 0.60F, fogT, emissive);
		quads++;
		emitWallQuad(ctx, job, bx0, bz0, bx0, bz1, yTop, yBottom, r, g, b, 0.60F, fogT, emissive);
		quads++;
		emitWallQuad(ctx, job, bx0, bz1, bx1, bz1, yTop, yBottom, r, g, b, 0.80F, fogT, emissive);
		quads++;
		emitWallQuad(ctx, job, bx0, bz0, bx1, bz0, yTop, yBottom, r, g, b, 0.80F, fogT, emissive);
		quads++;
		if (withBottom) {
			emitQuad(ctx, job, bx0, yBottom, bz0, bx0, yBottom, bz1, bx1, yBottom, bz1, bx1, yBottom, bz0, r, g, b, 0.50F, fogT, emissive);
			quads++;
		}
		return quads;
	}

	/**
	 * One vertical wall from {@code yTop} down to {@code yb} (no-op when the neighbour is equal or
	 * higher). Trees: leaf band then bark on deep walls. Soil: dirt band then STONE below (real
	 * stratigraphy — grass top, dirt, then rock; this also replaced the old slope-painted "rock
	 * columns" that looked alien). Everything else: single colour.
	 */
	private static int emitWall(BuildCtx ctx, MeshJob job, float ax, float az, float bx, float bz,
			float yTop, float yb, float rTop, float gTop, float bTop,
			float wallR, float wallG, float wallB, float shade, float fogT, float emissive,
			boolean tree, boolean soil, int quads) {
		if (yb >= yTop || quads >= job.maxQuads) {
			return quads;
		}
		if (tree) {
			// Tree walls are LEAVES all the way: the old two-tone painted a "trunk" as wide as the
			// whole canopy — nothing in Minecraft looks like that. Real 1x1 trunks come exclusively
			// from the standalone tree model.
			emitWallQuad(ctx, job, ax, az, bx, bz, yTop, yb, rTop, gTop, bTop, shade, fogT, emissive);
			return quads + 1;
		}
		if (soil && yTop - yb > SOIL_DIRT_BAND_BLOCKS) {
			float ySplit = yTop - SOIL_DIRT_BAND_BLOCKS;
			emitWallQuad(ctx, job, ax, az, bx, bz, yTop, ySplit, wallR, wallG, wallB, shade, fogT, emissive);
			quads++;
			if (quads < job.maxQuads) {
				emitWallQuad(ctx, job, ax, az, bx, bz, ySplit, yb, stoneR, stoneG, stoneB, shade, fogT, emissive);
				quads++;
			}
			return quads;
		}
		emitWallQuad(ctx, job, ax, az, bx, bz, yTop, yb, wallR, wallG, wallB, shade, fogT, emissive);
		return quads + 1;
	}

	/**
	 * TRUE when the chunk and its 8 neighbours all sit strictly inside the Z1 blocky band (outside
	 * the vanilla square, inside zone1End): there every neighbour tile is a direct step-1 column, so
	 * the greedy-merge flatness test reads exactly what the per-tile wall pass would read. Boundary
	 * chunks (vanilla square / Z2 stitch) keep the per-tile path with its band-aware neighbour grid.
	 */
	private static boolean blockyInterior(MeshJob job, int chunkX, int chunkZ) {
		float z1Sq = job.zone1End * job.zone1End;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int cx = chunkX + dx - job.cameraChunkX;
				int cz = chunkZ + dz - job.cameraChunkZ;
				if (Math.max(Math.abs(cx), Math.abs(cz)) <= job.minChunkDistance - 1) {
					return false; // touches the vanilla square
				}
				if ((float) (cx * cx + cz * cz) >= z1Sq) {
					return false; // touches the Z2 triangle band
				}
			}
		}
		return true;
	}

	/**
	 * One 8-block row of a Z1 cell with GREEDY RUN MERGING: consecutive plain-land columns that are
	 * locally flat (all 8 neighbours at the same top — no walls, no boundary up-walls, uniform AO)
	 * with identical colour and block light collapse into a single top quad. Every other column
	 * falls back to the full per-tile path, so the output is pixel-identical to the unmerged mesh.
	 */
	private static int emitBlockyRowRuns(BuildCtx ctx, MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int cellX, int localZ, float fogT, float waterShade, int quads) {
		int runStart = -1;
		short runH = 0;
		int runColor = 0;
		int runLight = 0;
		for (int i = 0; i < CELL && quads < job.maxQuads; i++) {
			int localX = cellX + i;
			int base = ((localZ << PauCSurfaceColumnStore.REGION_SHIFT) | localX) * PauCSurfaceColumnStore.MAX_SPANS;
			short h = region.spanY[base];
			int color = 0;
			int light = 0;
			boolean mergeable = false;
			if (h != Short.MIN_VALUE) {
				color = region.spanColor[base];
				int alpha = color >>> 24;
				// Plain LAND columns only: trees, water and floating builds keep the full tile path.
				if (!PauCSurfaceColumnStore.isTreeAlpha(alpha) && alpha != PauCSurfaceColumnStore.WATER_ALPHA
						&& alpha != PauCSurfaceColumnStore.FLOATING_ALPHA) {
					light = region.spanLight[base] & 0xF;
					mergeable = flatLocal(job, region, baseColumnX, baseColumnZ, baseColumnX + localX, baseColumnZ + localZ, h);
				}
			}
			if (mergeable && runStart >= 0 && h == runH && color == runColor && light == runLight) {
				continue; // extend the current run
			}
			if (runStart >= 0) {
				quads = flushBlockyRun(ctx, job, baseColumnX + cellX + runStart, baseColumnZ + localZ,
					i - runStart, runH, runColor, runLight, fogT, quads);
				runStart = -1;
			}
			if (mergeable) {
				runStart = i;
				runH = h;
				runColor = color;
				runLight = light;
			} else {
				quads = emitTile(ctx, job, region, baseColumnX, baseColumnZ, localX, localZ, 1,
					fogT, waterShade, false, false, 255, quads);
			}
		}
		if (runStart >= 0 && quads < job.maxQuads) {
			quads = flushBlockyRun(ctx, job, baseColumnX + cellX + runStart, baseColumnZ + localZ,
				CELL - runStart, runH, runColor, runLight, fogT, quads);
		}
		return quads;
	}

	/** Emits one merged flat run as a single top quad (raw sampled colour, same as nearPureColors tiles). */
	private static int flushBlockyRun(BuildCtx ctx, MeshJob job, int worldX, int worldZ,
			int len, short h, int color, int light, float fogT, int quads) {
		if (quads >= job.maxQuads) {
			return quads;
		}
		float x0 = (float) (worldX - job.camX);
		float z0 = (float) (worldZ - job.camZ);
		float x1 = x0 + len;
		float z1 = z0 + 1.0F;
		float yTop = (float) (h + 1 - job.camY);
		ctx.emitAlpha = 255;
		emitQuad(ctx, job, x0, yTop, z0, x0, yTop, z1, x1, yTop, z1, x1, yTop, z0,
			(color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, 1.0F, fogT, light / 15.0F);
		return quads + 1;
	}

	/**
	 * TRUE when the column's 8 neighbours all draw at exactly the same top height: locally flat
	 * terrain — no walls in any direction, no higher neighbour (uniform AO), so consecutive tiles
	 * are visually identical and can merge.
	 */
	private static boolean flatLocal(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int worldX, int worldZ, short h) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if ((dx | dz) == 0) {
					continue;
				}
				if (topYFast(job, region, baseColumnX, baseColumnZ, worldX + dx, worldZ + dz) != h) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Vanilla-style smooth-lighting corner term for a blocky top: each of the two edge neighbours
	 * and the diagonal touching this corner darkens it when HIGHER than the tile top (heightfield
	 * occlusion). Unknown/void neighbours never darken.
	 */
	private static float cornerAo(short h, short side1, short side2, short diag) {
		int occluders = 0;
		if (side1 != Short.MIN_VALUE && side1 != VOID_NEIGHBOUR && side1 > h) {
			occluders++;
		}
		if (side2 != Short.MIN_VALUE && side2 != VOID_NEIGHBOUR && side2 > h) {
			occluders++;
		}
		if (diag != Short.MIN_VALUE && diag > h) {
			occluders++;
		}
		return occluders == 0 ? 1.0F : occluders == 1 ? 0.84F : occluders == 2 ? 0.72F : 0.62F;
	}

	/** One wall quad with a vertical AO gradient (bottom darkens up to -25% over 12 blocks). */
	private static void emitWallQuad(BuildCtx ctx, MeshJob job, float ax, float az, float bx, float bz,
			float yTop, float yBottom, float rBase, float gBase, float bBase, float shade, float fogT, float emissive) {
		float depth = yTop - yBottom;
		float bottomShade = shade * (1.0F - Math.min(depth, 12.0F) / 12.0F * 0.25F);
		int rT = channel(job, rBase, shade, job.fogR, fogT, emissive, 0);
		int gT = channel(job, gBase, shade, job.fogG, fogT, emissive, 1);
		int bT = channel(job, bBase, shade, job.fogB, fogT, emissive, 2);
		int rB = channel(job, rBase, bottomShade, job.fogR, fogT, emissive, 0);
		int gB = channel(job, gBase, bottomShade, job.fogG, fogT, emissive, 1);
		int bB = channel(job, bBase, bottomShade, job.fogB, fogT, emissive, 2);
		ctx.emitTarget.vertex(ax, yTop, az).color(rT, gT, bT, ctx.emitAlpha).endVertex();
		ctx.emitTarget.vertex(bx, yTop, bz).color(rT, gT, bT, ctx.emitAlpha).endVertex();
		ctx.emitTarget.vertex(bx, yBottom, bz).color(rB, gB, bB, ctx.emitAlpha).endVertex();
		ctx.emitTarget.vertex(ax, yBottom, az).color(rB, gB, bB, ctx.emitAlpha).endVertex();
	}

	/** One quad, single colour on all four vertices. */
	/** Corner-column colour for the gradient (tree->span1, floating->span2, else span0; scrubbed). */
	private static int cornerColorAt(MeshJob job, PauCSurfaceColumnStore.Region region,
		int baseColumnX, int baseColumnZ, int worldX, int worldZ, float fr, float fg, float fb) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		PauCSurfaceColumnStore.Region r;
		int idx;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			r = region;
			idx = ((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS;
		} else {
			r = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
			if (r == null) {
				return packF(fr, fg, fb);
			}
			idx = (((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
				| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1))) * PauCSurfaceColumnStore.MAX_SPANS;
		}
		int alpha = r.spanColor[idx] >>> 24;
		int c;
		if (PauCSurfaceColumnStore.isTreeAlpha(alpha)) {
			c = scrubGroundColor(r.spanColor[idx + 1]);
		} else if (alpha == PauCSurfaceColumnStore.FLOATING_ALPHA) {
			c = scrubGroundColor(r.spanColor[idx + 2]);
		} else {
			c = scrubGroundColor(r.spanColor[idx]);
		}
		return c == 0 ? packF(fr, fg, fb) : c;
	}

	private static int packF(float r, float g, float b) {
		return (((int) r & 0xff) << 16) | (((int) g & 0xff) << 8) | ((int) b & 0xff);
	}

	/** emitQuad with a DIFFERENT colour per vertex (Gouraud biome blending across the triangle base). */
	private static void emitQuadGradient(BuildCtx ctx, MeshJob job,
		float ax, float ay, float az, float bx, float by, float bz,
		float cx, float cy, float cz, float dx, float dy, float dz,
		int colA, int colB, int colC, int colD, float shade, float fogT, float emissive) {
		gradVertex(ctx, job, ax, ay, az, colA, shade, fogT, emissive);
		gradVertex(ctx, job, bx, by, bz, colB, shade, fogT, emissive);
		gradVertex(ctx, job, cx, cy, cz, colC, shade, fogT, emissive);
		gradVertex(ctx, job, dx, dy, dz, colD, shade, fogT, emissive);
	}

	private static void gradVertex(BuildCtx ctx, MeshJob job, float x, float y, float z,
		int col, float shade, float fogT, float emissive) {
		int r = channel(job, (col >> 16) & 0xff, shade, job.fogR, fogT, emissive, 0);
		int g = channel(job, (col >> 8) & 0xff, shade, job.fogG, fogT, emissive, 1);
		int b = channel(job, col & 0xff, shade, job.fogB, fogT, emissive, 2);
		ctx.emitTarget.vertex(x, y, z).color(r, g, b, ctx.emitAlpha).endVertex();
	}

	private static void emitQuad(BuildCtx ctx, MeshJob job,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz,
			float rBase, float gBase, float bBase, float shade, float fogT, float emissive) {
		int r = channel(job, rBase, shade, job.fogR, fogT, emissive, 0);
		int g = channel(job, gBase, shade, job.fogG, fogT, emissive, 1);
		int b = channel(job, bBase, shade, job.fogB, fogT, emissive, 2);
		ctx.emitTarget.vertex(ax, ay, az).color(r, g, b, ctx.emitAlpha).endVertex();
		ctx.emitTarget.vertex(bx, by, bz).color(r, g, b, ctx.emitAlpha).endVertex();
		ctx.emitTarget.vertex(cx, cy, cz).color(r, g, b, ctx.emitAlpha).endVertex();
		ctx.emitTarget.vertex(dx, dy, dz).color(r, g, b, ctx.emitAlpha).endVertex();
	}

	/**
	 * Final colour channel: day/night ambient (lifted by block-light emissive so torches/lava glow at
	 * night), face shade, then fog fade toward the sky colour.
	 */
	private static int channel(MeshJob job, float base, float shade, float fogChannel, float fogT, float emissive, int idx) {
		float ambient = idx == 0 ? job.ambientR : idx == 1 ? job.ambientG : job.ambientB;
		if (emissive > 0.0F) {
			// Warm emissive floor: R full, G slightly lower, B lowest (torch-orange cast).
			float warm = idx == 0 ? emissive : idx == 1 ? emissive * 0.85F : emissive * 0.60F;
			ambient = Math.max(ambient, warm);
		}
		float v = base * ambient * shade * 0.92F; // vanilla renders darker (AO/lightmap): match its tone
		v += (fogChannel * 255.0F - v) * fogT;
		return (int) Math.max(0.0F, Math.min(255.0F, v));
	}

	/**
	 * MINIMUM drawn height of the neighbour tiles along one edge, or {@link Short#MIN_VALUE} when the
	 * neighbour is not drawn (deep skirt). Band-aware (snaps to the neighbour band's grid) and
	 * hole-fill-aware (a LOADED vanilla chunk is "not drawn"; an unloaded one IS drawn).
	 */
	private static short neighbourEdgeMinTop(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int nx, int nz, int edgeLen, boolean alongZ) {
		int chunkX = nx >> 4;
		int chunkZ = nz >> 4;
		int dcx = chunkX - job.cameraChunkX;
		int dcz = chunkZ - job.cameraChunkZ;
		int cheb = Math.max(Math.abs(dcx), Math.abs(dcz));
		float radial = job.roundHorizon ? (float) Math.sqrt((double) dcx * dcx + (double) dcz * dcz) : cheb;
		int nStep;
		if (radial < job.minChunkDistance) {
			if (!job.fillVanillaHoles) {
				return Short.MIN_VALUE;
			}
			nStep = 4; // sunken under-fill tiles: always drawn, 4-block grid
		} else if (radial > job.maxChunkDistance) {
			return Short.MIN_VALUE; // beyond the round horizon → skirt
		} else {
			nStep = job.bandStep(radial);
		}
		short minTop = Short.MAX_VALUE;
		if (alongZ) {
			int sx = Math.floorDiv(nx, nStep) * nStep;
			for (int zz = Math.floorDiv(nz, nStep) * nStep; zz < nz + edgeLen; zz += nStep) {
				short top = topYFast(job, region, baseColumnX, baseColumnZ, sx, zz);
				if (top == Short.MIN_VALUE) {
					return VOID_NEIGHBOUR; // no terrain there at all → short skirt + cap, not a 160-block column
				}
				minTop = top < minTop ? top : minTop;
			}
		} else {
			int sz = Math.floorDiv(nz, nStep) * nStep;
			for (int xx = Math.floorDiv(nx, nStep) * nStep; xx < nx + edgeLen; xx += nStep) {
				short top = topYFast(job, region, baseColumnX, baseColumnZ, xx, sz);
				if (top == Short.MIN_VALUE) {
					return VOID_NEIGHBOUR;
				}
				minTop = top < minTop ? top : minTop;
			}
		}
		return minTop;
	}

	/**
	 * Top Y with a fast path: columns inside the CURRENT region read the array directly — the vast
	 * majority of neighbour samples, sparing millions of hashmap lookups per mesh build.
	 */
	/**
	 * GROUND height at a grid CORNER for the triangle surface, shared with neighbours (watertight). A
	 * tree column returns its GROUND (span 1), NEVER the canopy (span 0) — a canopy-height corner pulls
	 * the surface into a tall spike (the "forest of spikes"). Floating builds use their real ground
	 * (span 2). Void → the tile fallback, so a missing sample never spikes either.
	 */
	private static short cornerHeightAt(MeshJob job, PauCSurfaceColumnStore.Region region,
		int baseColumnX, int baseColumnZ, int worldX, int worldZ, short fallback) {
		short g = groundAtColumn(job, region, baseColumnX, baseColumnZ, worldX, worldZ);
		if (g != Short.MIN_VALUE) {
			return g;
		}
		// Corner unsampled: fall back to the 4-block generation-grid anchor of THIS corner — a value
		// SHARED by every tile touching the corner (pure function of worldX/worldZ) so the mesh stays
		// watertight. A per-tile fallback (the caller's h) gave neighbours DIFFERENT heights at the same
		// corner = cracks/holes on sparse-data slopes (mesa "triangles manquants").
		g = groundAtColumn(job, region, baseColumnX, baseColumnZ, worldX & ~3, worldZ & ~3);
		if (g != Short.MIN_VALUE) {
			return g;
		}
		// BILINEAR over the surrounding 8-grid anchors: a missing fine sample continues the SLOPE.
		// The old flat 8-anchor stamped 8x8 PLATEAUS into the triangle sheet ("blocs 8x8").
		int ax0 = worldX & ~7;
		int az0 = worldZ & ~7;
		int sum = 0;
		int wsum = 0;
		for (int dz8 = 0; dz8 <= 8; dz8 += 8) {
			for (int dx8 = 0; dx8 <= 8; dx8 += 8) {
				short a = groundAtColumn(job, region, baseColumnX, baseColumnZ, ax0 + dx8, az0 + dz8);
				if (a != Short.MIN_VALUE) {
					int wx = dx8 == 0 ? 8 - (worldX - ax0) : worldX - ax0;
					int wz = dz8 == 0 ? 8 - (worldZ - az0) : worldZ - az0;
					int w = Math.max(1, wx * wz);
					sum += a * w;
					wsum += w;
				}
			}
		}
		return wsum > 0 ? (short) (sum / wsum) : fallback;
	}

	/** GROUND height of a column (tree->span1, floating->span2, water->top-1, else top), or MIN_VALUE. */
	private static short groundAtColumn(MeshJob job, PauCSurfaceColumnStore.Region region,
		int baseColumnX, int baseColumnZ, int worldX, int worldZ) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		PauCSurfaceColumnStore.Region r;
		int idx;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			r = region;
			idx = ((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS;
		} else {
			r = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
			if (r == null) {
				return Short.MIN_VALUE;
			}
			idx = (((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
				| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1))) * PauCSurfaceColumnStore.MAX_SPANS;
		}
		short top = r.spanY[idx];
		if (top == Short.MIN_VALUE) {
			return Short.MIN_VALUE;
		}
		int alpha = r.spanColor[idx] >>> 24;
		if (alpha == PauCSurfaceColumnStore.WATER_ALPHA) {
			return (short) (top - 1);
		}
		if (PauCSurfaceColumnStore.isTreeAlpha(alpha)) {
			return r.spanY[idx + 1]; // tree GROUND (MIN if absent -> caller uses the shared 4-grid anchor, no canopy spike)
		}
		if (alpha == PauCSurfaceColumnStore.FLOATING_ALPHA) {
			return r.spanY[idx + 2];
		}
		return top;
	}

	/** Sea-FLOOR height at a corner: water->span1 floor, tree->ground, floating->ground, land->bank top. */
	private static short floorCornerAt(MeshJob job, PauCSurfaceColumnStore.Region region,
		int baseColumnX, int baseColumnZ, int worldX, int worldZ, short fallback) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		PauCSurfaceColumnStore.Region r;
		int idx;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			r = region;
			idx = ((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS;
		} else {
			r = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
			if (r == null) {
				return fallback;
			}
			idx = (((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
				| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1))) * PauCSurfaceColumnStore.MAX_SPANS;
		}
		short top = r.spanY[idx];
		if (top == Short.MIN_VALUE) {
			return fallback;
		}
		int alpha = r.spanColor[idx] >>> 24;
		if (alpha == PauCSurfaceColumnStore.WATER_ALPHA) {
			short f = r.spanY[idx + 1];
			return f != Short.MIN_VALUE ? f : (short) (top - 3);
		}
		if (PauCSurfaceColumnStore.isTreeAlpha(alpha)) {
			short g = r.spanY[idx + 1];
			return g != Short.MIN_VALUE ? g : top;
		}
		if (alpha == PauCSurfaceColumnStore.FLOATING_ALPHA) {
			short g = r.spanY[idx + 2];
			return g != Short.MIN_VALUE ? g : top;
		}
		return top; // land bank: the floor rises to the shore, sealing the water edge
	}

	private static short topYFast(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int columnX, int columnZ) {
		int lx = columnX - baseColumnX;
		int lz = columnZ - baseColumnZ;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			return effectiveTop(region, ((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS,
				job.treeImposters);
		}
		return topY(job.regions, columnX, columnZ, job.treeImposters);
	}

	/**
	 * Span-0 top, EXCEPT a TREE column in imposter mode returns its GROUND (span 1): the canopy is a
	 * billboard, not geometry, so for wall/skirt purposes a forest column is a ground-height surface.
	 * Without this, normal tiles saw a tree neighbour at canopy height and never walled down to its lower
	 * ground — a thin open slit at every forest edge on a slope (the leftover "petits trous").
	 */
	private static short effectiveTop(PauCSurfaceColumnStore.Region region, int base, boolean treeImposters) {
		short top = region.spanY[base];
		if (treeImposters && top != Short.MIN_VALUE
				&& PauCSurfaceColumnStore.isTreeAlpha(region.spanColor[base] >>> 24)
				&& region.spanY[base + 1] != Short.MIN_VALUE) {
			return region.spanY[base + 1];
		}
		return top;
	}

	/** Top Y of a column from the SNAPSHOT region map (worker-safe). */
	private static short topY(Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> regions, int columnX, int columnZ,
			boolean treeImposters) {
		PauCSurfaceColumnStore.Region region = regions.get(PauCSurfaceColumnStore.regionKey(columnX, columnZ));
		if (region == null) {
			return Short.MIN_VALUE;
		}
		int index = ((columnZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
			| (columnX & (PauCSurfaceColumnStore.REGION_SIZE - 1));
		return effectiveTop(region, index * PauCSurfaceColumnStore.MAX_SPANS, treeImposters);
	}

	/** Span-1 (water floor / underside) Y of a column: current region fast path, snapshot for cross-region. */
	private static short floorYAt(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int worldX, int worldZ) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			return region.spanY[((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS + 1];
		}
		PauCSurfaceColumnStore.Region nr = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
		if (nr == null) {
			return Short.MIN_VALUE;
		}
		int index = ((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
			| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1));
		return nr.spanY[index * PauCSurfaceColumnStore.MAX_SPANS + 1];
	}

	/** Span-0 (top) Y of a column: current-region fast path, snapshot for cross-region. MIN_VALUE = void. */
	private static short topYAt(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int worldX, int worldZ) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			return region.spanY[((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS];
		}
		PauCSurfaceColumnStore.Region nr = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
		if (nr == null) {
			return Short.MIN_VALUE;
		}
		int index = ((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
			| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1));
		return nr.spanY[index * PauCSurfaceColumnStore.MAX_SPANS];
	}

	/** Span-2 (ceiling) Y of a column: current region fast path, snapshot for cross-region. */
	private static short ceilingYAt(MeshJob job, PauCSurfaceColumnStore.Region region,
			int baseColumnX, int baseColumnZ, int worldX, int worldZ) {
		int lx = worldX - baseColumnX;
		int lz = worldZ - baseColumnZ;
		if ((lx | lz) >= 0 && lx < PauCSurfaceColumnStore.REGION_SIZE && lz < PauCSurfaceColumnStore.REGION_SIZE) {
			return region.spanY[((lz << PauCSurfaceColumnStore.REGION_SHIFT) | lx) * PauCSurfaceColumnStore.MAX_SPANS + 2];
		}
		PauCSurfaceColumnStore.Region nr = job.regions.get(PauCSurfaceColumnStore.regionKey(worldX, worldZ));
		if (nr == null) {
			return Short.MIN_VALUE;
		}
		int index = ((worldZ & (PauCSurfaceColumnStore.REGION_SIZE - 1)) << PauCSurfaceColumnStore.REGION_SHIFT)
			| (worldX & (PauCSurfaceColumnStore.REGION_SIZE - 1));
		return nr.spanY[index * PauCSurfaceColumnStore.MAX_SPANS + 2];
	}

	/**
	 * Upward seam wall for a BOUNDARY blocky tile: emitted only when the higher neighbour lies BEYOND
	 * the blocky zone (a triangle tile — it emits no walls of its own). Inside the zone the higher
	 * blocky neighbour still closes the step itself (no duplicate/z-fight).
	 */
	private static int emitBoundaryUpWall(BuildCtx ctx, MeshJob job, short nTop, short ownTop,
			float ax, float az, float bx, float bz, int nWorldX, int nWorldZ,
			float r, float g, float b, float shade, float fogT, float emissive, int quads) {
		if (nTop == Short.MIN_VALUE || nTop == VOID_NEIGHBOUR || nTop <= ownTop || quads >= job.maxQuads) {
			return quads;
		}
		float ndx = (float) (nWorldX - job.camX);
		float ndz = (float) (nWorldZ - job.camZ);
		float ndy = (float) (nTop - job.camY);
		if (ndx * ndx + ndy * ndy + ndz * ndz < job.refine2BlocksSq) {
			return quads; // neighbour is blocky too — its own down-wall closes this step
		}
		emitWallQuad(ctx, job, ax, az, bx, bz, (float) (nTop + 1 - job.camY), (float) (ownTop + 1 - job.camY), r, g, b, shade, fogT, emissive);
		return quads + 1;
	}

	private static float wallBottom(short neighbourH, float yTop, int skirtDepth, double cameraY) {
		if (neighbourH == Short.MIN_VALUE) {
			return yTop - skirtDepth;
		}
		if (neighbourH == VOID_NEIGHBOUR) {
			return yTop - FLOAT_SKIRT_BLOCKS; // floating edge: short skirt, closed by a bottom cap
		}
		return Math.min(yTop, (float) (neighbourH + 1 - cameraY));
	}

	/**
	 * Old sampler versions coloured the tree ground ONE ABOVE the surface (often AIR -> the MISSING
	 * sprite): months of persisted span-1 colours are saturated MAGENTA (r==b, g~0). Scrub them to an
	 * earthy forest-floor tone at read time — fixes ALL old data instantly, no regeneration.
	 */
	private static int scrubGroundColor(int color) {
		int r = (color >> 16) & 0xff;
		int g = (color >> 8) & 0xff;
		int b = color & 0xff;
		if (color != 0 && r > g + 32 && b > g + 32) {
			return (color & 0xff000000) | 0x5E4A33;
		}
		return color;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		if (edge1 <= edge0) {
			return x >= edge1 ? 1.0F : 0.0F;
		}
		float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0F - 2.0F * t);
	}

	/** Deterministic per-tile hash in [0,1) for the colour jitter (stable across rebuilds). */
	private static float hash01(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0x7fffffff) / (float) 0x7fffffff;
	}

	/** Resolves bark/dirt/stone base colours from the block textures (render thread, atlas loaded). */
	private static void resolveMaterialColors() {
		if (materialsResolved) {
			return;
		}
		try {
			int bark = MATERIAL_CACHE.baseColor(net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
			trunkR = (bark >> 16) & 0xff;
			trunkG = (bark >> 8) & 0xff;
			trunkB = bark & 0xff;
			int dirt = MATERIAL_CACHE.baseColor(net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
			dirtR = (dirt >> 16) & 0xff;
			dirtG = (dirt >> 8) & 0xff;
			dirtB = dirt & 0xff;
			int stone = MATERIAL_CACHE.baseColor(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
			stoneR = (stone >> 16) & 0xff;
			stoneG = (stone >> 8) & 0xff;
			stoneB = stone & 0xff;
			materialsResolved = true;
		} catch (Throwable ignored) {
			// keep fallback constants; retry next submit
		}
	}

	/** Night factor 0 (noon) .. 1 (midnight) from the float getSkyDarken overload (client-reliable). */
	private static float nightFactor(Minecraft minecraft) {
		if (!PauCTunables.readBoolean(DYNAMIC_LIGHT_PROPERTY, true) || minecraft.level == null) {
			return 0.0F;
		}
		if (!minecraft.level.dimensionType().hasSkyLight()) {
			// End/Nether: uniform ambient, no day-night cycle — a fake "night" dim washed the
			// end-stone LODs into a murky wrong tone.
			return 0.0F;
		}
		float brightness = minecraft.level.getSkyDarken(1.0F);
		return Math.max(0.0F, Math.min(1.0F, 1.0F - (brightness - 0.2F) / 0.8F));
	}

	/**
	 * Per-channel ambient multiplier the terrain mesh bakes into every quad (day = ~1, dimming toward a
	 * cool night tone). The isolated tree-imposter renderer reuses this so its billboards darken with the
	 * day-night cycle exactly like the ground under them — otherwise they glow at full daytime brightness
	 * at night. Returns {r, g, b}.
	 */
	/**
	 * True when the terrain LOD has a DRAWN (non-empty) mesh for this region. The imposter renderer gates
	 * on this: a tree canopy must never float over ground the terrain hasn't rendered yet (regeneration /
	 * staging lag) — the "floating trees over void" artifact.
	 */
	public static boolean hasDrawnRegion(long regionKey) {
		RegionMesh m = drawnMeshes.get(regionKey);
		return m != null && (m.quads > 0 || m.waterQuads > 0);
	}

	public static float[] ambientLightFactors(Minecraft minecraft) {
		float night = nightFactor(minecraft);
		return new float[] { 1.0F - 0.78F * night, 1.0F - 0.76F * night, 1.0F - 0.62F * night };
	}

	/**
	 * Returns the far-plane distance (in blocks) the LOD engine needs, or 0 if inactive — used by
	 * {@code MixinPauCLodFarPlane} to extend {@code GameRenderer.getDepthFar()}.
	 */
	public static float requiredFarPlaneBlocks() {
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return 0.0F;
		}
		if (PauCSurfaceSampler.store().regionCount() == 0) {
			return 0.0F;
		}
		int vanillaChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
		int radius = lodRadiusChunks(vanillaChunks);
		return radius <= 0 ? 0.0F : radius * 16 + 512.0F;
	}

	/**
	 * Vanilla-fog extension while the LOD engine is drawing: fog relocates to the LOD field's own fade
	 * band (see {@code MixinFogRenderer}). Returns 0 when the engine has nothing on screen.
	 */
	public static float vanillaFogStartBlocksForLodEngine() {
		if (drawnMeshes.isEmpty() || builtQuads == 0 || !PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return 0.0F;
		}
		return fogStartChunksShared * 16.0F;
	}

	/** Fog end matching {@link #vanillaFogStartBlocksForLodEngine()}: the LOD field's outer edge. */
	public static float vanillaFogEndBlocksForLodEngine() {
		return fogEndChunksShared * 16.0F;
	}

	/**
	 * The LOD outer radius in chunks, DRIVEN BY THE PAUC LODS GAUGE IN VIDEO SETTINGS
	 * ({@code PauCLodClientSettings}): radius = vanilla render distance + the gauge's extra-distance
	 * value. Gauge at its MINIMUM (or the LODs toggle off) returns 0 = LODs OFF — the engine draws
	 * nothing and vanilla fog reverts to stock. An explicit {@code pauc.lodengine.witnessRadiusChunks}
	 * overrides the coupling. The 4 detail bands are percentage-based, so they rescale with this.
	 */
	public static int lodRadiusChunks(int vanillaChunks) {
		String rawValue = PauCTunables.raw(RADIUS_CHUNKS_PROPERTY);
		if (rawValue != null) {
			try {
				return Math.max(0, Math.min(256, Integer.parseInt(rawValue.trim())));
			} catch (NumberFormatException ignored) {
				// fall through to the gauge coupling
			}
		}
		if (!fr.hoyatla.pauc.lod.PauCLodClientSettings.isLodsEnabled()) {
			return 0;
		}
		int extra = fr.hoyatla.pauc.lod.PauCLodClientSettings.targetDistanceChunks();
		if (extra <= fr.hoyatla.pauc.lod.PauCLodRange.MIN_RENDER_DISTANCE_CHUNKS) {
			return 0; // gauge at minimum = OFF
		}
		return Math.min(256, vanillaChunks + extra);
	}

	/**
	 * Width (chunks) of ONE quality ring: fixed and equal for the three fine rings regardless of the
	 * chosen LOD distance (default 8, tunable), compressed equally when the total span is too small.
	 */
	private static float bandRingWidth(int minChunkDistance, int maxChunkDistance) {
		int configured = readInt(BAND_WIDTH_PROPERTY, 8, 1, 64);
		float lodSpan = Math.max(1, maxChunkDistance - minChunkDistance);
		return Math.min(configured, lodSpan / 3.0F);
	}

	/**
	 * DATA sample step (blocks) a chunk deserves at a radial distance — the data-quality counterpart of
	 * the render bands: 1-block sampling in the near ring, 2 in the next, 4 beyond (the 4/8-block render
	 * bands read a 4-block grid without loss). Same equal-width rings as the mesh.
	 */
	public static int dataStepForRadial(float radialChunks, int minChunkDistance, int maxChunkDistance) {
		float ringWidth = bandRingWidth(minChunkDistance, maxChunkDistance);
		// PRECISION scales with the dynamic-resolution state (user 07-20 — PauC must reach near-DH
		// fidelity at max quality): at OFF the per-block (step 1) shell extends over the WHOLE fine band
		// and 2-block data reaches far out, matching DH; PERFORMANCE keeps the cheap 4-chunk step-1 shell
		// and coarsens fast. q = 1 (OFF) … 0.3 (PERFORMANCE).
		float q = dataQualityFactor();
		// BOUNDED precision (07-20 fix): per-block (step 1) is 16x the gen cost of step 4 AND makes the
		// mesh heavier — extending it far saturated the CPU and WEDGED the mesh pipeline (2900 regions
		// missing → force-swap, proven in the WITH-DH log). Keep step-1 to a small near shell regardless
		// of quality; only step-2 (a cheap 4x) extends with quality — a real precision bump that the
		// pipeline can absorb. Big precision needs the world-aligned grid (re-mesh only changed sections).
		float fineReach = minChunkDistance + Math.max(4.0F, 4.0F + 2.0F * q); // step-1: 4..6 chunks
		float midReach = minChunkDistance + ringWidth * (2.0F + 0.6F * q);    // step-2: 2.0..2.6 rings
		if (radialChunks < fineReach) {
			return 1;
		}
		if (radialChunks < midReach) {
			return 2;
		}
		return 4;
	}

	/** Data-precision factor from the dynamic-resolution state: OFF = max (near-DH), PERFORMANCE = min. */
	private static float dataQualityFactor() {
		try {
			switch (fr.hoyatla.pauc.lod.PauCLodClientSettings.dynamicResolutionMode()) {
				case OFF:
					return 1.0F;
				case QUALITY:
					return 0.75F;
				case BALANCED:
					return 0.5F;
				default:
					return 0.3F; // PERFORMANCE
			}
		} catch (Throwable ignored) {
			return 1.0F; // settings unavailable off-thread early: default to full precision
		}
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

	private static float readFloat(String key, float fallback, float min, float max) {
		String rawValue = PauCTunables.raw(key);
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Float.parseFloat(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}
}
