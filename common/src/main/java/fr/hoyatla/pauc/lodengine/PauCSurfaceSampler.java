package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * LOD engine phase 1: budgeted sampler feeding {@link PauCSurfaceColumnStore} from LOADED client
 * chunks around the player (visited-area coverage; distant generation is phase 5). Runs a bounded
 * number of columns per client tick so it is invisible on the frame budget, spiralling outward from
 * the player chunk in FOV-agnostic rings (the store is direction-independent; render-side priority
 * stays FOV-first).
 *
 * <p>Enabled by default (kill-switch {@code pauc.lodengine.enabled=false}). Phase 2 adds disk
 * persistence via {@link PauCSurfaceStoreIO} so coverage accumulates across sessions. No reference to
 * any DH class — this subsystem runs with nothing else installed.</p>
 */
public final class PauCSurfaceSampler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lodengine.enabled";
	private static final String PERSISTENCE_PROPERTY = "pauc.lodengine.persistence";
	private static final String DISTANT_GENERATION_PROPERTY = "pauc.lodengine.distantGeneration";
	private static final String DISTANT_RADIUS_PROPERTY = "pauc.lodengine.distantRadiusChunks";
	private static final String DISTANT_SUBMIT_PROPERTY = "pauc.lodengine.distantSubmitPerTick";
	private static final String DISTANT_DRAIN_PROPERTY = "pauc.lodengine.distantDrainPerTick";
	private static final String COLUMNS_PER_TICK_PROPERTY = "pauc.lodengine.sampleColumnsPerTick";
	private static final long STATS_LOG_INTERVAL_MS = 60_000L;
	private static final long FLUSH_INTERVAL_MS = 3_000L;
	private static final long EVICT_INTERVAL_MS = 6_000L;
	private static long lastEvictMs;
	private static long lastStatsLogMs;
	private static long lastFlushMs;
	private static final PauCSurfaceColumnStore STORE = new PauCSurfaceColumnStore();
	private static final PauCBlockColorCache COLOR_CACHE = new PauCBlockColorCache();
	private static final PauCSurfaceStoreIO IO = new PauCSurfaceStoreIO();
	private static final PauCDistantSurfaceGenerator GEN = new PauCDistantSurfaceGenerator();
	private static final short[] SCRATCH_Y = new short[PauCSurfaceColumnStore.MAX_SPANS];
	private static final int[] SCRATCH_COLOR = new int[PauCSurfaceColumnStore.MAX_SPANS];
	private static final byte[] SCRATCH_LIGHT = new byte[PauCSurfaceColumnStore.MAX_SPANS];
	private static String sampledDimension = "";
	private static int cursorChunkX;
	private static int cursorChunkZ;
	private static int spiralLeg;
	private static int spiralStep;
	// Dedicated distant-generation cursor: a contiguous outward spiral that only advances once a chunk is
	// handled (already covered, or successfully queued). When the generation queue is full it STOPS and
	// keeps the cursor, retrying next tick — so the fill is hole-free instead of the checkerboard the
	// shared spiral produced (it skipped chunks whenever the queue was full).
	private static int genOriginX;
	private static int genOriginZ;
	private static int genCursorX;
	private static int genCursorZ;
	private static int genLeg;
	private static int genStep;
	private static boolean genSpiralValid;
	private static boolean genSpiralDone;
	// Coverage phase: 0 = BLITZ (1 sample/chunk, the whole disc lands in seconds), 1 = background
	// upgrade of blitz chunks to the full 4-block grid. Big-surface-first: the player sees a complete
	// map almost immediately and it sharpens progressively, instead of a slow chunk-by-chunk crawl.
	private static int genPhase;
	// Dedicated near-ring refinement cursor (independent of the coverage sweep).
	private static int refOriginX;
	private static int refOriginZ;
	private static long lastRegionPublishMs;
	private static int refCursorX;
	private static int refCursorZ;
	private static int refLeg;
	private static int refStep;
	private static boolean refValid;
	private static boolean refDone;
	private static int refSubmitted;
	private static boolean statsLogged;

	private PauCSurfaceSampler() {
	}

	public static PauCSurfaceColumnStore store() {
		return STORE;
	}

	/** TRUE once the initial disk load of region files is fully drained (see PauCSurfaceStoreIO). */
	public static boolean diskLoadSettled() {
		return IO.initialLoadSettled();
	}

	public static void onClientTick() {
		// PauC's own LOD engine is the primary track: enabled by default, kill-switch kept.
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}

		boolean persistence = PauCTunables.readBoolean(PERSISTENCE_PROPERTY, true);
		String dimension = level.dimension().location().toString();
		if (!dimension.equals(sampledDimension)) {
			if (persistence && !sampledDimension.isEmpty()) {
				safe(() -> IO.flushAll(STORE));
				safe(() -> GEN.saveStepMap(true)); // before configureStepMap swaps to the new dimension
			}
			sampledDimension = dimension;
			STORE.clear();
			resetSpiral(minecraft);
			genSpiralValid = false;
			PauCLodEnginePlanner.onWorldChange();
			statsLogged = false;
			if (persistence) {
				safe(() -> {
					Path dir = resolveBaseDir(minecraft, dimension);
					if (dir != null) {
						IO.configure(dir);
						LOGGER.info("PauC LOD engine persistence: store directory {} (dim={}).", dir, dimension);
					}
				});
			}
			safe(() -> configureDistantGeneration(minecraft, level));
		}

		// CEILING DIMENSIONS (Nether): the LOD renderer is disabled here (a heightfield can't do the
		// Nether's 3D volume), so don't waste CPU/disk sampling and persisting terrain nothing draws.
		if (level.dimensionType().hasCeiling()) {
			return;
		}

		if (persistence) {
			// Restore budget: drainLoaded only inserts what the background decoder has queued (0 cost once the
			// queue empties), and the queue fills ONCE per session (loadAll at configure). So a HIGH budget
			// speeds up the launch restore — the "chargement difficile au lancement" (thousands of regions at
			// 64/tick took ~10s of incomplete far LOD) — without any steady-state cost.
			safe(() -> IO.drainLoaded(STORE, 384));
			// Once the disk load is fully drained, rebuild the "known" set from the persisted columns so a
			// step-map loss (version bump / corrupt file) never storm-regenerates existing terrain. Main-thread,
			// once per dimension (the generator's own flag makes it idempotent). See seedKnownFromStore.
			if (IO.initialLoadSettled()) {
				safe(() -> GEN.seedKnownFromStore(STORE, 4));
			}
		}

		boolean distantGen = PauCTunables.readBoolean(DISTANT_GENERATION_PROPERTY, true) && GEN.isReady();
		if (distantGen) {
			int drainBudget = readInt(DISTANT_DRAIN_PROPERTY, 448, 1, 1024);
			safe(() -> GEN.drain(STORE, drainBudget));
		}

		int playerChunkX = minecraft.player.chunkPosition().x;
		int playerChunkZ = minecraft.player.chunkPosition().z;
		int columnBudget = readColumnBudget();
		int sampled = 0;
		int attempts = 0;
		int maxAttempts = 64;
		while (sampled < columnBudget && attempts < maxAttempts) {
			attempts++;
			LevelChunk chunk = level.getChunkSource().getChunk(cursorChunkX, cursorChunkZ, false);
			if (chunk != null) {
				sampleChunk(level, chunk);
				sampled += 256;
			}
			advanceSpiral(minecraft);
		}

		if (distantGen) {
			// Generation radius follows the SAME video-settings-coupled radius the witness renderer draws
			// (explicit distantRadiusChunks property still overrides) — coverage always reaches the horizon.
			int vanillaChunks = minecraft.options.getEffectiveRenderDistance();
			int witnessRadius = PauCSurfaceWitnessRenderer.lodRadiusChunks(vanillaChunks);
			int distantRadius = readInt(DISTANT_RADIUS_PROPERTY, witnessRadius, 0, 256);
			int submitBudget = readInt(DISTANT_SUBMIT_PROPERTY, 320, 0, 1024);
			if (distantRadius > 0) {
				// Submission scanning moved OFF the tick: three parallel planner daemons (coverage,
				// refinement, hole repair) fill the queues continuously. The tick only publishes
				// position/state and drains results — no cursor ever stalls a frame again.
				PauCLodEnginePlanner.publish(level, GEN, playerChunkX, playerChunkZ, vanillaChunks, distantRadius);
				long nowRegions = System.currentTimeMillis();
				if (nowRegions - lastRegionPublishMs >= 2_000L) {
					lastRegionPublishMs = nowRegions;
					long[] snapshotKeys = STORE.regionKeys();
					PauCSurfaceColumnStore.Region[] snapshotRegions = new PauCSurfaceColumnStore.Region[snapshotKeys.length];
					for (int i = 0; i < snapshotKeys.length; i++) {
						snapshotRegions[i] = STORE.region(snapshotKeys[i]);
					}
					PauCLodEnginePlanner.publishRegions(snapshotKeys, snapshotRegions);
				}
			}
		}

		if (persistence) {
			long nowFlush = System.currentTimeMillis();
			if (nowFlush - lastFlushMs >= FLUSH_INTERVAL_MS) {
				lastFlushMs = nowFlush;
				safe(() -> IO.flushDirty(STORE, 6));
				safe(() -> GEN.saveStepMap(false));
			}
		}

		// MEMORY BOUND (07-20): evict regions well beyond the LOD horizon so the store stops growing
		// unbounded as the player explores (it hit 100% of an 8 GB heap with DH → GC thrash → mesh
		// wedge). Evicted regions are persisted (flushed just before) and reload from disk on return.
		long nowEvict = System.currentTimeMillis();
		if (nowEvict - lastEvictMs >= EVICT_INTERVAL_MS) {
			lastEvictMs = nowEvict;
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			if (mc.player != null) {
				int lodRadius = PauCSurfaceWitnessRenderer.lodRadiusChunks(mc.options.getEffectiveRenderDistance());
				if (lodRadius > 0) {
					int keepRadius = lodRadius + 48; // generous margin past the render horizon → never evicts a drawn region
					int pcx = mc.player.blockPosition().getX() >> 4;
					int pcz = mc.player.blockPosition().getZ() >> 4;
					if (persistence) {
						safe(() -> IO.flushDirty(STORE, 128)); // persist any recent far changes before dropping
					}
					int evicted = STORE.evictBeyond(pcx, pcz, keepRadius);
					if (evicted > 0) {
						LOGGER.info("PauC LOD store evicted {} far region(s) beyond {} chunks; {} regions retained.",
							evicted, keepRadius, STORE.regionCount());
					}
				}
			}
		}

		if (!statsLogged && STORE.regionCount() > 0) {
			statsLogged = true;
			LOGGER.info("PauC LOD engine sampler active: first surface regions stored (regions={}, dim={}).", STORE.regionCount(), dimension);
		}
		long now = System.currentTimeMillis();
		if (now - lastStatsLogMs >= STATS_LOG_INTERVAL_MS && STORE.regionCount() > 0) {
			lastStatsLogMs = now;
			// Each region holds 64x64 columns x MAX_SPANS slots: short y + int color + byte light = 7 bytes/slot.
			long approxBytes = (long) STORE.regionCount() * 4096L * PauCSurfaceColumnStore.MAX_SPANS * 7L;
			LOGGER.info(
				"PauC LOD engine store: regions={}, ~columns={}, ~memory={} KiB, genChunks={}, genPending={}, genDone={}, dim={}.",
				STORE.regionCount(),
				STORE.regionCount() * 4096L,
				approxBytes / 1024L,
				GEN.generatedTotal(),
				GEN.pendingCount(),
				PauCLodEnginePlanner.coverageDone(),
				dimension
			);
		}
	}

	/** Flushes surfaces to disk and resets state on disconnect so the next world reconfigures cleanly. */
	public static void onSessionEnd() {
		if (PauCTunables.readBoolean(PERSISTENCE_PROPERTY, true)) {
			safe(() -> IO.flushAll(STORE));
			safe(() -> GEN.saveStepMap(true));
		}
		safe(GEN::reset);
		genSpiralValid = false;
		PauCLodEnginePlanner.onWorldChange();
		// Force a full reconfigure on the next join: the next world may reuse the same dimension name.
		sampledDimension = "";
	}

	/** Captures the integrated server's terrain generator for distant generation — singleplayer only. */
	private static void configureDistantGeneration(Minecraft minecraft, ClientLevel level) {
		if (!PauCTunables.readBoolean(DISTANT_GENERATION_PROPERTY, true)) {
			GEN.reset();
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			GEN.reset();
			return;
		}
		ServerLevel serverLevel = server.getLevel(level.dimension());
		if (serverLevel == null) {
			GEN.reset();
			return;
		}
		if (serverLevel.dimensionType().hasCeiling()) {
			// Ceiling dimensions (Nether): getBaseHeight sees the roof, not the walkable surface — noise
			// generation is meaningless there. Coverage = visited chunks only (ceiling-scanned above).
			GEN.reset();
			LOGGER.info("PauC LOD engine: ceiling dimension {} — distant generation off, visited-only coverage.",
				level.dimension().location());
			return;
		}
		ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
		// Resolve the surface base colours HERE (render thread, atlas loaded) and hand the ints to the
		// daemon generator: it must never touch the block models / texture atlas off-thread.
		// The End (and end-like custom dims): the surface is END STONE, untinted — the grass path painted
		// the whole End in biome-tinted green. Detected via the dimension's natural flag + no skylight.
		boolean endLike = !serverLevel.dimensionType().hasSkyLight() && !serverLevel.dimensionType().natural();
		int grassBase = endLike
			? COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.END_STONE.defaultBlockState())
			: COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
		int waterBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
		int snowBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState());
		int leavesBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
		int stoneBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		int sandBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.SAND.defaultBlockState());
		int badlandsBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.TERRACOTTA.defaultBlockState());
		int iceBase = COLOR_CACHE.baseColor(net.minecraft.world.level.block.Blocks.ICE.defaultBlockState());
		java.util.Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, Float> treeDensity =
			buildTreeDensity(generator.getBiomeSource());
		GEN.configure(
			serverLevel,
			generator,
			serverLevel.getChunkSource().randomState(),
			generator.getBiomeSource(),
			generator.getSeaLevel(),
			grassBase,
			waterBase,
			snowBase,
			leavesBase,
			stoneBase,
			sandBase,
			badlandsBase,
			iceBase,
			treeDensity,
			!endLike
		);
		// Persisted data-quality map: lets the sweeps distinguish up-to-date chunks (never re-generate,
		// especially previously VISITED ones with real block data) from legacy/older-version data
		// (re-generated once, which is how format fixes heal the whole stored field).
		if (PauCTunables.readBoolean(PERSISTENCE_PROPERTY, true)) {
			Path stepDir = resolveBaseDir(minecraft, level.dimension().location().toString());
			if (stepDir != null) {
				GEN.configureStepMap(stepDir.resolve("chunksteps.bin"));
			}
		}
	}

	/**
	 * Per-biome tree density from biome TAGS (generic — modded forests that tag themselves as forest/taiga/
	 * jungle are covered, no hardcoded biome ids). Built once per dimension on the render thread; the map is
	 * immutable and read-only from the generator daemon threads.
	 */
	private static java.util.Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, Float> buildTreeDensity(
			net.minecraft.world.level.biome.BiomeSource biomeSource) {
		java.util.Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, Float> density = new java.util.HashMap<>();
		for (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome : biomeSource.possibleBiomes()) {
			float d = 0.0F;
			if (biome.is(net.minecraft.tags.BiomeTags.IS_JUNGLE)) {
				d = 0.72F;
			} else if (biome.is(net.minecraft.tags.BiomeTags.IS_FOREST)) {
				d = 0.60F;
			} else if (biome.is(net.minecraft.tags.BiomeTags.IS_TAIGA)) {
				d = 0.55F;
			} else if (biome.is(net.minecraft.tags.BiomeTags.IS_SAVANNA)) {
				// Scattered acacias: a treeless yellow-grass savanna reads as DESERT at LOD distance.
				d = 0.14F;
			} else if (biome.is(net.minecraft.tags.BiomeTags.HAS_CLOSER_WATER_FOG)
				|| biome.unwrapKey().map(k -> {
					String path = k.location().getPath();
					return path.contains("swamp") || path.contains("marsh") || path.contains("bayou")
						|| path.contains("bog") || path.contains("mangrove");
				}).orElse(Boolean.FALSE)) {
				// Swamps/mangroves are TREED wetlands (mangrove is among the densest vanilla forests) —
				// no forest/taiga/jungle tag, so they generated bald. Vanilla tags swamp+mangrove with
				// closer water fog; the name heuristic covers modded wetlands.
				d = 0.50F;
			}
			if (d > 0.0F) {
				density.put(biome, d);
			}
		}
		return java.util.Map.copyOf(density);
	}

	/** 0..1 deterministic hash of a grid point (matches the distant generator's smooth-underside noise). */
	private static float hash01(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0x7fffffff) / (float) 0x7fffffff;
	}

	/** Smooth (bilinear, 32-block grid) island-underside thickness in blocks (6..20) — no per-tile spikes. */
	private static int smoothIslandThickness(int worldX, int worldZ) {
		int cell = 32;
		int cx0 = Math.floorDiv(worldX, cell) * cell;
		int cz0 = Math.floorDiv(worldZ, cell) * cell;
		float fx = (worldX - cx0) / (float) cell;
		float fz = (worldZ - cz0) / (float) cell;
		float h00 = hash01(cx0, cz0);
		float h10 = hash01(cx0 + cell, cz0);
		float h01 = hash01(cx0, cz0 + cell);
		float h11 = hash01(cx0 + cell, cz0 + cell);
		float hx0 = h00 + (h10 - h00) * fx;
		float hx1 = h01 + (h11 - h01) * fx;
		// 3..10 blocks: End islands are THIN slabs. A thick underside dropped long columns into the
		// void at every island edge ("colonnes qui partent dans le vide").
		return 3 + (int) ((hx0 + (hx1 - hx0) * fz) * 7.0F);
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

	private static Path resolveBaseDir(Minecraft minecraft, String dimension) {
		String dimensionKey = dimension.replaceAll("[^a-zA-Z0-9._-]", "_");
		IntegratedServer singleplayer = minecraft.getSingleplayerServer();
		if (singleplayer != null) {
			// Stored inside the world save: unique per world, travels with it.
			return singleplayer.getWorldPath(LevelResource.ROOT).resolve("pauc-lod-engine").resolve(dimensionKey);
		}
		ServerData server = minecraft.getCurrentServer();
		String worldKey = server != null ? "mp_" + server.ip.replaceAll("[^a-zA-Z0-9._-]", "_") : "local";
		return minecraft.gameDirectory.toPath().resolve("pauc-lod-engine").resolve(worldKey).resolve(dimensionKey);
	}

	private static void safe(Runnable action) {
		try {
			action.run();
		} catch (Throwable throwable) {
			LOGGER.warn("PauC LOD engine persistence step failed (continuing without it).", throwable);
		}
	}

	private static int readColumnBudget() {
		String rawValue = PauCTunables.raw(COLUMNS_PER_TICK_PROPERTY);
		if (rawValue == null) {
			// 4 chunks/tick (~80 chunks/s): 256 made the visited ring crawl into the store — the map
			// looked like it "loaded slowly" because the LOD ingest lagged far behind chunk loading.
			return 1024;
		}
		try {
			return Math.max(16, Math.min(8192, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return 1024;
		}
	}

	/** Land-vegetation imposter kind for a surface block, or 0 if the block is not tree/bamboo. */
	private static int vegetationKind(BlockState state) {
		net.minecraft.world.level.block.Block block = state.getBlock();
		if (block instanceof net.minecraft.world.level.block.LeavesBlock) {
			if (block == net.minecraft.world.level.block.Blocks.MANGROVE_LEAVES) {
				return PauCSurfaceColumnStore.MANGROVE_ALPHA;
			}
			if (block == net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES) {
				return PauCSurfaceColumnStore.CONIFER_ALPHA;
			}
			if (block == net.minecraft.world.level.block.Blocks.JUNGLE_LEAVES) {
				return PauCSurfaceColumnStore.JUNGLE_ALPHA;
			}
			if (block == net.minecraft.world.level.block.Blocks.ACACIA_LEAVES) {
				return PauCSurfaceColumnStore.SAVANNA_ALPHA;
			}
			if (block == net.minecraft.world.level.block.Blocks.CHERRY_LEAVES) {
				return PauCSurfaceColumnStore.CHERRY_ALPHA;
			}
			if (block == net.minecraft.world.level.block.Blocks.BIRCH_LEAVES) {
				return PauCSurfaceColumnStore.BIRCH_ALPHA;
			}
			if (block == net.minecraft.world.level.block.Blocks.DARK_OAK_LEAVES) {
				return PauCSurfaceColumnStore.DARK_OAK_ALPHA;
			}
			return PauCSurfaceColumnStore.TREE_ALPHA; // oak/mangrove/azalea + modded (green-dominant broadleaf)
		}
		if (block == net.minecraft.world.level.block.Blocks.BAMBOO) {
			return PauCSurfaceColumnStore.BAMBOO_ALPHA;
		}
		return 0;
	}

	/** Vines and hanging/climbing plants — excluded from the surface so they don't streak the LOD. */
	private static boolean isClimbingPlant(BlockState state) {
		net.minecraft.world.level.block.Block b = state.getBlock();
		return b == net.minecraft.world.level.block.Blocks.VINE
			|| b == net.minecraft.world.level.block.Blocks.CAVE_VINES
			|| b == net.minecraft.world.level.block.Blocks.CAVE_VINES_PLANT
			|| b == net.minecraft.world.level.block.Blocks.WEEPING_VINES
			|| b == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
			|| b == net.minecraft.world.level.block.Blocks.TWISTING_VINES
			|| b == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT
			|| b == net.minecraft.world.level.block.Blocks.GLOW_LICHEN;
	}

	/**
	 * Any small GROUND plant that is not real terrain and must be descended-past for the LOD surface:
	 * grass, tall grass, ferns, flowers, saplings, dead bush, mushrooms, crops, berry bushes (all
	 * {@link net.minecraft.world.level.block.BushBlock}), plus bamboo and sugar cane, plus climbing
	 * vines. Leaves/logs are NOT here — trees still surface as canopy imposters. User requirement
	 * (07-20): no grass LODs or grass imposters anywhere.
	 */
	private static boolean isSkippableVegetation(BlockState state) {
		net.minecraft.world.level.block.Block b = state.getBlock();
		return b instanceof net.minecraft.world.level.block.BushBlock
			|| b == net.minecraft.world.level.block.Blocks.BAMBOO
			|| b == net.minecraft.world.level.block.Blocks.BAMBOO_SAPLING
			|| b == net.minecraft.world.level.block.Blocks.SUGAR_CANE
			|| isClimbingPlant(state);
	}

	private static void sampleChunk(ClientLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		// Loaded-chunk sampling is per column = FULL data quality; record it so the refinement sweep
		// never wastes a slow noise re-generation on it.
		GEN.markFullQuality(pos.x, pos.z);
		// Ceiling dimensions (Nether and modded caves-with-roof): WORLD_SURFACE is the bedrock roof —
		// scan down under the ceiling for the real walkable surface instead.
		boolean ceiling = level.dimensionType().hasCeiling();
		// Floating dimension (the End): also record the UNDERSIDE of the island (span 1) by scanning
		// down through the solid span — the renderer closes islands at their REAL bottom profile.
		boolean floating = !ceiling && !level.dimensionType().hasSkyLight() && !level.dimensionType().natural();
		int minY = level.getMinBuildHeight();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int worldX = pos.getMinBlockX() + dx;
				int worldZ = pos.getMinBlockZ() + dz;
				int topY = ceiling
					? scanUnderCeiling(level, chunk, dx, dz)
					: chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
				if (!ceiling) {
					// WORLD_SURFACE lands on GROUND VEGETATION (grass, tall grass, ferns, flowers, bamboo,
					// vines) — user does NOT want grass LODs/imposters, and tall plants smear into green
					// vertical streaks. Descend past ALL small ground plants to the real solid surface so
					// the tile becomes the ground below (grass block, dirt, jungle floor), never a floating
					// green plant. Leaves/logs are NOT skipped — trees still become canopy imposters.
					while (topY > minY && isSkippableVegetation(chunk.getBlockState(cursor.set(worldX, topY, worldZ)))) {
						topY--;
					}
				}
				if (topY <= minY) {
					// VOID column (End between islands, incomplete terrain): store EMPTY, never "terrain
					// at the bottom of the world" — that painted a flat false floor under the End.
					for (int span = 0; span < PauCSurfaceColumnStore.MAX_SPANS; span++) {
						SCRATCH_Y[span] = Short.MIN_VALUE;
						SCRATCH_COLOR[span] = 0;
						SCRATCH_LIGHT[span] = 0;
					}
					STORE.putColumn(worldX, worldZ, SCRATCH_Y, SCRATCH_COLOR, SCRATCH_LIGHT);
					continue;
				}
				SCRATCH_Y[0] = (short) topY;
				cursor.set(worldX, topY, worldZ);
				BlockState topState = chunk.getBlockState(cursor);
				// Texture-average colour (DH-style), biome-tinted for grass/leaves/water. Far more faithful
				// than MapColor: real sand/stone/canopy tones, and water is the biome-tinted particle colour
				// (no manual darkening needed — it lands close to the actual rendered water).
				// Material tags in the alpha channel: leaves → mushroom-tree walls, water → dark depth walls,
				// dirt-family tops (grass/podzol/mycelium — the #minecraft:dirt tag, modded soils included)
				// → dirt-coloured walls (grass is green on TOP only; its sides are dirt).
				int alphaTag;
				boolean waterColumn = !topState.getFluidState().isEmpty();
				int vegKind = vegetationKind(topState);
				if (vegKind != 0) {
					alphaTag = vegKind << 24; // broadleaf / conifer / jungle / savanna / bamboo — imposter kinds
				} else if (waterColumn) {
					alphaTag = PauCSurfaceColumnStore.WATER_ALPHA << 24;
				} else if (topState.is(net.minecraft.tags.BlockTags.DIRT)) {
					alphaTag = PauCSurfaceColumnStore.SOIL_ALPHA << 24;
				} else {
					alphaTag = 0xff000000;
				}
				int columnColor = COLOR_CACHE.tintedColor(topState, level, cursor);
				// Water: span 0 = translucent surface (plain biome tint), span 1 = the FLOOR (real
				// height + real block colour, darkened by depth with the generator's exact formula).
				SCRATCH_COLOR[0] = (columnColor & 0x00ffffff) | alphaTag;
				int sky = level.getBrightness(LightLayer.SKY, cursor.move(0, 1, 0));
				int block = level.getBrightness(LightLayer.BLOCK, cursor);
				SCRATCH_LIGHT[0] = (byte) ((sky << 4) | block);
				for (int span = 1; span < PauCSurfaceColumnStore.MAX_SPANS; span++) {
					SCRATCH_Y[span] = Short.MIN_VALUE;
					SCRATCH_COLOR[span] = 0;
					SCRATCH_LIGHT[span] = 0;
				}
				if (floating) {
					// Span 1 = island underside. The REAL block-by-block bottom is irregular by nature, and
					// rendered as LOD walls it became the picket-fence of spikes around End islands. One
					// unified model instead: a SMOOTH domed underside (same bilinear 32-block hash as the
					// distant generator), so visited and generated islands share one clean belly.
					int thickness = smoothIslandThickness(worldX, worldZ);
					SCRATCH_Y[1] = (short) Math.max(minY + 1, topY - thickness);
				} else if (waterColumn) {
					// Span 1 = ocean floor under the translucent surface. SCAN DOWN through water/air to
					// the first solid block — NEVER Heightmap.OCEAN_FLOOR: that heightmap is server-only
					// (Heightmap.Types.OCEAN_FLOOR.sendToClient() == false), so on the client it returns a
					// garbage value that CLOBBERED the generator's good floor the moment the player passed
					// over — the "no more sea floor after passing" bug, for real this time.
					int floorY = topY - 1; // start just below the water surface
					boolean sawAlgae = false;
					while (floorY > minY) {
						BlockState fbs = chunk.getBlockState(cursor.set(worldX, floorY, worldZ));
						if (fbs.isAir() || !fbs.getFluidState().isEmpty()) {
							if (fbs.is(net.minecraft.world.level.block.Blocks.KELP)
								|| fbs.is(net.minecraft.world.level.block.Blocks.KELP_PLANT)
								|| fbs.is(net.minecraft.world.level.block.Blocks.SEAGRASS)
								|| fbs.is(net.minecraft.world.level.block.Blocks.TALL_SEAGRASS)) {
								sawAlgae = true;
							}
							floorY--; // still water or air — keep descending
						} else {
							break; // solid block — floorY is the top block of the ocean floor
						}
					}
					int depth = Math.max(0, topY - floorY);
					cursor.set(worldX, floorY, worldZ);
					BlockState floorState = chunk.getBlockState(cursor);
					int floorColor = COLOR_CACHE.tintedColor(floorState, level, cursor);
					float scale = 1.0F - Math.min(depth, 16) / 16.0F * 0.55F; // 0.7 crushed deep floors to near-black — read as a HOLE, not a seabed
					int r = Math.min(255, (int) (((floorColor >> 16) & 0xff) * scale));
					int g = Math.min(255, (int) (((floorColor >> 8) & 0xff) * scale));
					int b = Math.min(255, (int) ((floorColor & 0xff) * scale));
					SCRATCH_Y[1] = (short) floorY;
					int floorAlpha = 0xff;
					if (floorState.is(net.minecraft.tags.BlockTags.CORAL_BLOCKS)
						|| floorState.is(net.minecraft.tags.BlockTags.CORALS)
						|| floorState.is(net.minecraft.tags.BlockTags.WALL_CORALS)) {
						floorAlpha = PauCSurfaceColumnStore.CORAL_ALPHA;
					} else if (sawAlgae) {
						floorAlpha = PauCSurfaceColumnStore.KELP_ALPHA;
					}
					SCRATCH_COLOR[1] = (floorAlpha << 24) | (r << 16) | (g << 8) | b;
				} else if (PauCSurfaceColumnStore.isTreeAlpha(alphaTag >>> 24)) {
					// Span 1 = GROUND under the canopy (heightmap without leaves; logs count, so the odd
					// trunk-top "ground" bump reads as the trunk itself). The renderer floats the leaf
					// slab above it instead of dropping a full-height green pillar.
					int groundY;
					if ((alphaTag >>> 24) == PauCSurfaceColumnStore.BAMBOO_ALPHA) {
						groundY = topY - 1;
						while (groundY > minY
							&& chunk.getBlockState(cursor.set(worldX, groundY, worldZ)).is(net.minecraft.world.level.block.Blocks.BAMBOO)) {
							groundY--;
						}
					} else {
						groundY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dx, dz);
						// That heightmap counts LOGS, so a TALL tree (jungle / big spruce) lands on the TRUNK TOP,
						// not the ground — the forest floor + imposter base then float at trunk height. Descend past
						// logs (and air/vines) to the REAL ground under the trunk.
						while (groundY > minY) {
							BlockState gs = chunk.getBlockState(cursor.set(worldX, groundY, worldZ));
							if (gs.is(net.minecraft.tags.BlockTags.LOGS) || gs.isAir() || isClimbingPlant(gs)) {
								groundY--;
							} else {
								break;
							}
						}
					}
					if (groundY > minY && groundY < topY) {
						cursor.set(worldX, groundY, worldZ);
						int groundColor = COLOR_CACHE.tintedColor(chunk.getBlockState(cursor), level, cursor);
						SCRATCH_Y[1] = (short) groundY;
						SCRATCH_COLOR[1] = groundColor | 0xff000000;
					}
				} else if (!ceiling) {
					// FLOATING STRUCTURE (player build / bridge / platform / overhang): the surface mass has
					// open AIR below it. Descend through the mass to its bottom, then through the air gap to
					// the real ground. If there's a clear gap over solid ground, tag it FLOATING so the
					// renderer draws a floating SLAB over the ground — not a solid column down to it.
					int structBottom = topY;
					while (structBottom > minY && !chunk.getBlockState(cursor.set(worldX, structBottom - 1, worldZ)).isAir()) {
						structBottom--;
					}
					if (structBottom > minY && topY - structBottom <= 32) {
						int gapY = structBottom - 1;
						int airGap = 0;
						while (gapY > minY && chunk.getBlockState(cursor.set(worldX, gapY, worldZ)).isAir() && airGap < 320) {
							gapY--;
							airGap++;
						}
						if (airGap >= 4 && gapY > minY) {
							SCRATCH_COLOR[0] = (columnColor & 0x00ffffff) | (PauCSurfaceColumnStore.FLOATING_ALPHA << 24);
							SCRATCH_Y[1] = (short) structBottom; // structure bottom (slab underside)
							SCRATCH_COLOR[1] = (columnColor & 0x00ffffff) | 0xff000000;
							cursor.set(worldX, gapY, worldZ);
							int groundColor = COLOR_CACHE.tintedColor(chunk.getBlockState(cursor), level, cursor);
							SCRATCH_Y[2] = (short) gapY; // real ground top under the structure
							SCRATCH_COLOR[2] = groundColor | 0xff000000;
						}
					}
				}
				if (ceiling) {
					// Span 2 = the CEILING underside (lowest block of the netherrack roof). This is the
					// first VOLUMETRIC layer: the renderer draws it as a downward slab so the distant Nether
					// reads as an ENCLOSED cave instead of a floor floating under open sky.
					int ceilY = scanCeilingUnderside(level, chunk, dx, dz);
					if (ceilY > topY && ceilY < level.getMaxBuildHeight()) {
						cursor.set(worldX, ceilY, worldZ);
						int ceilColor = COLOR_CACHE.tintedColor(chunk.getBlockState(cursor), level, cursor);
						SCRATCH_Y[2] = (short) ceilY;
						SCRATCH_COLOR[2] = 0xff000000 // overhead = in shadow, darkened
							| ((((ceilColor >> 16) & 0xff) * 55 / 100) << 16)
							| ((((ceilColor >> 8) & 0xff) * 55 / 100) << 8)
							| (((ceilColor & 0xff) * 55 / 100));
						// Span 1 = a significant FLOATING netherrack blob between floor and ceiling, drawn as
						// a clean floating PLATFORM (volumetric mid-layer). Only THICK masses (>= 3 blocks,
						// air above and below) qualify — thin wisps are skipped so it never becomes clutter.
						int blobTop = findFloatingBlob(chunk, cursor, worldX, worldZ, topY, ceilY, minY);
						if (blobTop > 0) {
							cursor.set(worldX, blobTop, worldZ);
							int blobColor = COLOR_CACHE.tintedColor(chunk.getBlockState(cursor), level, cursor);
							SCRATCH_Y[1] = (short) blobTop;
							SCRATCH_COLOR[1] = blobColor | 0xff000000;
						}
					}
				}
				STORE.putColumn(worldX, worldZ, SCRATCH_Y, SCRATCH_COLOR, SCRATCH_LIGHT);
			}
		}
	}

	/**
	 * Surface under a ceiling (Nether): the MAIN WALKABLE FLOOR, not merely the first solid from the top.
	 * The Nether is full of THIN floating netherrack blobs; catching one as "the surface" rendered it as a
	 * tall vertical column/pole down to the neighbours' real floor ("colonnes sans raison"). So a solid is
	 * only accepted as the floor once it proves at least {@code FLOOR_MIN_THICKNESS} blocks thick; thin
	 * blobs are skipped and the scan keeps descending to the solid mass the player actually stands on.
	 */
	private static final int FLOOR_MIN_THICKNESS = 4;

	/**
	 * Most significant FLOATING solid mass between the floor and the ceiling (Nether netherrack platforms):
	 * returns the top Y of the first thick blob (air above AND below, >= 3 blocks thick), or -1 if none.
	 */
	private static int findFloatingBlob(LevelChunk chunk, BlockPos.MutableBlockPos cursor,
			int worldX, int worldZ, int floorY, int ceilY, int minY) {
		int y = ceilY - 2; // start below the ceiling
		while (y > floorY + 2) {
			boolean solid = !chunk.getBlockState(cursor.set(worldX, y, worldZ)).isAir();
			boolean airAbove = chunk.getBlockState(cursor.set(worldX, y + 1, worldZ)).isAir();
			if (solid && airAbove) {
				int thick = 0;
				int yy = y;
				while (yy > floorY + 1 && !chunk.getBlockState(cursor.set(worldX, yy, worldZ)).isAir() && thick < 8) {
					yy--;
					thick++;
				}
				boolean airBelow = chunk.getBlockState(cursor.set(worldX, yy, worldZ)).isAir();
				if (thick >= 3 && airBelow) {
					return y; // thick floating platform
				}
				y = yy - 1; // skip this thin/grounded mass, keep looking lower
			} else {
				y--;
			}
		}
		return -1;
	}

	/** Ceiling underside Y: the lowest solid block of the roof (where open air begins below it). */
	private static int scanCeilingUnderside(ClientLevel level, LevelChunk chunk, int dx, int dz) {
		BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(
			chunk.getPos().getMinBlockX() + dx, 0, chunk.getPos().getMinBlockZ() + dz);
		int minY = level.getMinBuildHeight();
		int y = Math.min(level.getMaxBuildHeight(), minY + level.dimensionType().logicalHeight()) - 1;
		while (y > minY && chunk.getBlockState(scan.setY(y)).isAir()) {
			y--; // skip any air right under the build ceiling
		}
		while (y > minY && !chunk.getBlockState(scan.setY(y)).isAir()) {
			y--; // descend through the solid roof mass
		}
		return y + 1; // lowest solid block of the roof
	}

	private static int scanUnderCeiling(ClientLevel level, LevelChunk chunk, int dx, int dz) {
		BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(
			chunk.getPos().getMinBlockX() + dx, 0, chunk.getPos().getMinBlockZ() + dz);
		int minY = level.getMinBuildHeight();
		int y = Math.min(level.getMaxBuildHeight(), minY + level.dimensionType().logicalHeight()) - 2;
		while (y > minY && !chunk.getBlockState(scan.setY(y)).isAir()) {
			y--; // skip the solid roof
		}
		while (y > minY) {
			while (y > minY && chunk.getBlockState(scan.setY(y)).isAir()) {
				y--; // descend the air gap
			}
			if (y <= minY) {
				return minY;
			}
			int solidTop = y;
			int thickness = 0;
			while (y > minY && !chunk.getBlockState(scan.setY(y)).isAir()) {
				y--;
				if (++thickness >= FLOOR_MIN_THICKNESS) {
					return solidTop; // a thick mass = the real floor
				}
			}
			// Thin floating blob: ignore it, keep descending toward the main floor below.
		}
		return minY;
	}

	/**
	 * Sweeps a contiguous outward spiral, queuing distant generation for chunks that are missing OR whose
	 * DATA RESOLUTION is coarser than their distance band deserves — the data quality follows the player
	 * like the mesh bands do (near = 1-block sampling, then 2, far = 4). First coverage is always the fast
	 * 4-block pass; refinement happens on the following sweeps, nearest chunks first (spiral order).
	 * Re-arms after a completed sweep whenever it still submitted work or the player moved.
	 */
	private static void driveDistantGeneration(ClientLevel level, int playerChunkX, int playerChunkZ,
			int vanillaChunks, int distantRadius, int submitBudget) {
		// Don't generate over terrain that hasn't loaded + seeded yet: a coarse blitz here would beat the
		// real disk column and the session-wins merge would keep the coarse fill (see seedKnownFromStore).
		if (!diskLoadSettled()) {
			return;
		}
		// Re-center ONLY between sweeps, never mid-sweep: resetting on every player move made the cursor
		// restart at the centre each time and burn its budget re-skipping the already-covered core, never
		// reaching the frontier to fill.
		boolean moved = Math.abs(genOriginX - playerChunkX) > 8 || Math.abs(genOriginZ - playerChunkZ) > 8;
		// Movement preempts even a running upgrade sweep: the new frontier's BLITZ pass always first.
		boolean preempt = moved && genSpiralValid && !genSpiralDone && genPhase == 1;
		if (!genSpiralValid || preempt || (genSpiralDone && moved)) {
			genOriginX = playerChunkX;
			genOriginZ = playerChunkZ;
			genCursorX = playerChunkX;
			genCursorZ = playerChunkZ;
			genLeg = 0;
			genStep = 0;
			genSpiralValid = true;
			genSpiralDone = false;
			genPhase = 0;
		}
		if (genSpiralDone) {
			return;
		}

		// A square spiral of L legs only reaches a HALF-SIDE of ~L/4 (legs of length n span n/2):
		// radius*2 stopped every sweep at HALF the radius — coverage/upgrades silently never finished.
		int maxLeg = distantRadius * 4 + 8;
		// Round horizon: skip the square-spiral corners beyond the Euclidean radius — the witness culls
		// them anyway, and the finished map's data boundary lands exactly on the circle.
		boolean roundHorizon = PauCTunables.readBoolean("pauc.lodengine.roundHorizon", true);
		long radiusSq = (long) (distantRadius + 1) * (distantRadius + 1);
		int minChunkDistance = vanillaChunks + 1;
		int submitted = 0;
		int guard = 0;
		while (submitted < submitBudget && guard < 8192) {
			guard++;
			if (genLeg >= maxLeg) {
				if (genPhase == 0) {
					// Blitz complete (whole disc has terrain) → background-upgrade pass, same origin.
					genPhase = 1;
					genCursorX = genOriginX;
					genCursorZ = genOriginZ;
					genLeg = 0;
					genStep = 0;
					continue;
				}
				genSpiralDone = true;
				return;
			}
			long dx = genCursorX - genOriginX;
			long dz = genCursorZ - genOriginZ;
			boolean insideDisc = !roundHorizon || dx * dx + dz * dz <= radiusSq;
			if (insideDisc && level.getChunkSource().getChunk(genCursorX, genCursorZ, false) == null) {
				if (genPhase == 0) {
					// PHASE 0 — BLITZ: 1 noise sample per chunk (16-block grid) on unknown/legacy chunks:
					// the ENTIRE disc lands in seconds. Keyed on the versioned step map alone (VOID
					// columns are legitimately "sampled empty" and must never resubmit forever).
					if (!GEN.isKnown(genCursorX, genCursorZ)) {
						if (!GEN.submit(genCursorX, genCursorZ, 16)) {
							return; // queue full — keep the cursor here and retry next tick
						}
						submitted++;
					}
				} else if (GEN.recordedStep(genCursorX, genCursorZ) > 4) {
					// PHASE 1 — UPGRADE: blitz chunks re-generate at the full 4-block grid, nearest
					// first, quietly in the background while the player already sees a complete map.
					// Includes blitz-void chunks: a single 16-block sample can miss small islands (End).
					if (!GEN.submit(genCursorX, genCursorZ, 4)) {
						return;
					}
					submitted++;
				}
			}
			advanceGenSpiral();
		}
	}

	/**
	 * Dedicated near-ring refinement cursor: continuously upgrades the chunks of the two fine detail
	 * rings (1-block and 2-block data) around the player, nearest first, re-arming as the player moves.
	 * Independent of the coverage sweep — near LOD detail refreshes even while exploring, which is what
	 * makes the vanilla→LOD transition read as "sharp near, degrading toward the horizon".
	 */
	private static void driveNearRefinement(ClientLevel level, int playerChunkX, int playerChunkZ,
			int vanillaChunks, int distantRadius, int scanBudget) {
		int minChunkDistance = vanillaChunks + 1;
		// Smallest radius whose deserved data step is 4 = the end of the fine rings.
		int refineRadius = minChunkDistance;
		while (refineRadius < distantRadius
			&& PauCSurfaceWitnessRenderer.dataStepForRadial(refineRadius + 0.5F, minChunkDistance, distantRadius) < 4) {
			refineRadius++;
		}
		if (refineRadius <= minChunkDistance) {
			return;
		}
		boolean moved = Math.abs(refOriginX - playerChunkX) > 2 || Math.abs(refOriginZ - playerChunkZ) > 2;
		// Restart the spiral on movement EVEN mid-sweep: finishing the old sweep first left the zone
		// around the new position coarse for minutes while traversing ("turn around and the nearby
		// LODs are raw"). Near-first around the CURRENT position always wins.
		if (!refValid || moved || (refDone && refSubmitted > 0)) {
			refOriginX = playerChunkX;
			refOriginZ = playerChunkZ;
			refCursorX = playerChunkX;
			refCursorZ = playerChunkZ;
			refLeg = 0;
			refStep = 0;
			refValid = true;
			refDone = false;
			refSubmitted = 0;
		}
		if (refDone) {
			return;
		}
		int maxLeg = refineRadius * 4 + 4; // same half-side spiral arithmetic as the coverage sweep
		int guard = 0;
		while (guard < scanBudget) {
			guard++;
			if (refLeg >= maxLeg) {
				refDone = true;
				return;
			}
			long dx = refCursorX - refOriginX;
			long dz = refCursorZ - refOriginZ;
			float radial = (float) Math.sqrt((double) dx * dx + (double) dz * dz);
			if (radial <= refineRadius
				&& level.getChunkSource().getChunk(refCursorX, refCursorZ, false) == null
				&& STORE.isSampled((refCursorX << 4) + 8, (refCursorZ << 4) + 8)) {
				int desired = PauCSurfaceWitnessRenderer.dataStepForRadial(radial, minChunkDistance, distantRadius);
				if (desired < 4 && GEN.recordedStep(refCursorX, refCursorZ) > desired) {
					if (!GEN.submit(refCursorX, refCursorZ, desired)) {
						return; // fine queue full — keep the cursor, retry next tick
					}
					refSubmitted++;
				}
			}
			int leg = refLeg / 2 + 1;
			switch (refLeg & 3) {
				case 0 -> refCursorX++;
				case 1 -> refCursorZ++;
				case 2 -> refCursorX--;
				case 3 -> refCursorZ--;
			}
			refStep++;
			if (refStep >= leg) {
				refStep = 0;
				refLeg++;
			}
		}
	}

	private static void advanceGenSpiral() {
		int leg = genLeg / 2 + 1;
		switch (genLeg & 3) {
			case 0 -> genCursorX++;
			case 1 -> genCursorZ++;
			case 2 -> genCursorX--;
			case 3 -> genCursorZ--;
		}
		genStep++;
		if (genStep >= leg) {
			genStep = 0;
			genLeg++;
		}
	}

	private static void resetSpiral(Minecraft minecraft) {
		ChunkPos playerChunk = minecraft.player.chunkPosition();
		cursorChunkX = playerChunk.x;
		cursorChunkZ = playerChunk.z;
		spiralLeg = 0;
		spiralStep = 0;
	}

	private static void advanceSpiral(Minecraft minecraft) {
		// Square spiral around the player; re-centers when the player moves far from the cursor.
		ChunkPos playerChunk = minecraft.player.chunkPosition();
		if (Math.abs(cursorChunkX - playerChunk.x) > 96 || Math.abs(cursorChunkZ - playerChunk.z) > 96) {
			resetSpiral(minecraft);
			return;
		}
		int leg = spiralLeg / 2 + 1;
		switch (spiralLeg & 3) {
			case 0 -> cursorChunkX++;
			case 1 -> cursorChunkZ++;
			case 2 -> cursorChunkX--;
			case 3 -> cursorChunkZ--;
		}
		spiralStep++;
		if (spiralStep >= leg) {
			spiralStep = 0;
			spiralLeg++;
			if (spiralLeg >= 384) {
				resetSpiral(minecraft);
			}
		}
	}
}
