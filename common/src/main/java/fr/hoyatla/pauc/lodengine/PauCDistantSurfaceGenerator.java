package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LOD engine phase 5: singleplayer distant surface generation. Fills {@link PauCSurfaceColumnStore}
 * with surface data for chunks BEYOND the loaded/visited area, so the horizon covers unexplored ground.
 *
 * <p>Only works in singleplayer: the client has the integrated server's {@link ChunkGenerator} + seed.
 * On a multiplayer server the client has neither, so distant generation is impossible there (same
 * fundamental limit as Distant Horizons) — coverage stays "visited + persisted".</p>
 *
 * <p>Threading: {@link ChunkGenerator#getBaseHeight} and the biome source are the same read-only,
 * thread-safe APIs the game's own worldgen workers use, so sampling runs on a daemon thread — never on
 * the render thread or the server tick thread. Generated chunks cross back to the main thread through a
 * lock-free queue and are inserted into the store there. Any failure self-disables generation; the game
 * keeps running.</p>
 */
public final class PauCDistantSurfaceGenerator {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int DEFAULT_WATER_COLOR = 0x3F76E4;
	private static final int PENDING_CAP = 1024;
	// Refinement jobs (step 1/2) are ~16x slower than coverage (full noise column per block): cap their
	// queue small so they can NEVER clog the workers and starve fast step-4 coverage of new frontier —
	// that starvation showed as "generation stopped / holes at the LOD edge" while the queue churned.
	private static final int FINE_PENDING_CAP = 512; // 128 kept the refinement planner permanently starved

	// TWO pools: a single shared FIFO executor was a priority inversion — fine-refinement jobs queued
	// BEHIND hundreds of coverage jobs (the upgrade sweep re-floods on every move), so near-ring detail
	// was accepted but executed "later" that never came. Fine detail now has its own dedicated workers.
	private final ExecutorService worker = Executors.newFixedThreadPool(6, runnable -> {
		Thread thread = new Thread(runnable, "PauC-LodEngine-Gen");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 2);
		return thread;
	});
	private final ExecutorService fineWorker = Executors.newFixedThreadPool(6, runnable -> {
		Thread thread = new Thread(runnable, "PauC-LodEngine-Refine");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final ConcurrentLinkedQueue<GeneratedChunk> done = new ConcurrentLinkedQueue<>();
	private final LongOpenHashSet pending = new LongOpenHashSet();
	// Data resolution per chunk (sample step in blocks: 1/2/4; 1 = full quality from the sampler).
	// PERSISTED alongside the store with a DATA VERSION: chunks absent from the map (legacy sessions or
	// older data formats) are re-generated once by the coverage sweep — that is how format fixes (like
	// the +1 Y offset) heal the whole persisted field — while up-to-date chunks, INCLUDING previously
	// visited ones with real block data, are never overwritten by noise re-generation again.
	// Main-thread access only (submit/drain/mark/load/save).
	private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap chunkStep = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
	// Bump when the generated-data format/semantics change — FORGETTING THIS BUMP freezes stale data
	// forever (the End kept its broken pre-void-fix "green plain" because v2 marked it current).
	// v2 = top-block-Y heights + material tags; v3 = void columns, end-stone surfaces, span-1 undersides;
	// v4 = plain uniform water colour; v5 = water floor (Y + colour) in span 1 for translucent water.
	private static final int STEP_MAP_VERSION = 19; // 19: real surface gen now applies biome tinting (grass/leaves/water match visited tone). SAFE TO BUMP now: seedKnownFromStore() rebuilds the known set from the intact .pauclod store on load. // 17: cherry/birch/dark_oak imposter tags (distant biome-classified) // 16: PURGE — blended vanilla tints + all current formats; legacy pollution (green-in-snow plateaus, magenta, vine columns) regenerates away. Coarse pass is smooth triangles now, so the regen is benign. // 14: imposter sub-kinds (conifer/jungle/savanna/bamboo/coral/kelp tags)
	private java.nio.file.Path stepMapPath;
	private boolean stepMapDirty;
	private long lastStepMapSaveMs;
	private boolean seededFromStore; // once-per-dimension: has the known set been rebuilt from the persisted store?

	private final short[] spanY = new short[PauCSurfaceColumnStore.MAX_SPANS];
	private final int[] spanColor = new int[PauCSurfaceColumnStore.MAX_SPANS];
	private final byte[] spanLight = new byte[PauCSurfaceColumnStore.MAX_SPANS];

	private volatile LevelHeightAccessor level;
	private volatile ChunkGenerator generator;
	private volatile RandomState randomState;
	private volatile BiomeSource biomeSource;
	private volatile int seaLevel = 63;
	// Pre-resolved on the render thread (texture-average base colours) so the daemon thread only does
	// integer base x biome-tint multiplies — the SAME formula the colour cache uses for visited chunks,
	// which is what makes distant terrain match the near LOD instead of the old vivid getGrassColor path.
	private volatile int grassBaseColor = 0xFF7FB238;
	private volatile int waterBaseColor = 0xFFB2B2B2;
	private volatile int snowBaseColor = 0xFFF2F2F2;
	private volatile int leavesBaseColor = 0xFF6A6A6A;
	private volatile int stoneBaseColor = 0xFF7F7F7F;
	private volatile int sandBaseColor = 0xFFDBD3A0;
	private volatile int badlandsBaseColor = 0xFF9C6E4B;
	private volatile int iceBaseColor = 0xFF91B5F9;
	// Enriched surface materials (fidelity level >= 1): texture-average tones, UNtinted (like badlands/
	// mycelium/sand — these are not grass/foliage/water). Podzol = old-growth-taiga floor, mud = mangrove
	// swamp bed, gravel = windswept-gravelly / stony-shore. Hardcoded averages, same precedent as mycelium.
	private static final int PODZOL_BASE_COLOR = 0xFF5B4326;
	private static final int MUD_BASE_COLOR = 0xFF3C3832;
	private static final int GRAVEL_BASE_COLOR = 0xFF837F7F;
	// False for end-like dimensions: surface = untinted base colour (end stone), no biome grass tint,
	// no snow/sand/tree heuristics.
	private volatile boolean tintSurface = true;
	// P2 — REAL SURFACE GENERATION (tunable pauc.lodengine.realSurfaceGen, DEFAULT OFF). Runs vanilla's
	// noise+surface builder for a distant chunk to read the REAL surface block (ends the guessed material).
	// Heavy (DH-level cost) and crash-prone off-thread — fully try/catch'd: the FIRST failure disables it
	// for the session and everything falls back to the heightmap heuristic. Iterated in-game.
	private static final String REAL_SURFACE_PROPERTY = "pauc.lodengine.realSurfaceGen";
	private static final PauCBlockColorCache REAL_SURFACE_COLORS = new PauCBlockColorCache();
	private volatile boolean realSurfaceGenFailed;
	private volatile boolean realSurfaceLogged;
	// TEMPORARY test-default ON (Prism wipes JvmArgs) with a TIGHT session budget: real-gen a bounded
	// sample of chunks to SEE real colours + catch crashes, without flooding heavy off-thread worldgen
	// over thousands of chunks. Revert default to false / raise the budget once validated.
	private static final boolean REAL_SURFACE_DEFAULT = true;
	private final java.util.concurrent.atomic.AtomicInteger realSurfaceBudget = new java.util.concurrent.atomic.AtomicInteger(4096);
	// Per-biome tree density (0..1), scanned on the render thread from biome tags. Immutable after
	// configure() publishes it — the daemon threads only read it, so no synchronization is needed.
	private volatile Map<Holder<Biome>, Float> treeDensityByBiome = Map.of();
	private volatile boolean ready;
	private volatile boolean failed;
	private volatile boolean firstChunkLogged;
	private volatile long generatedTotal;

	private record GeneratedChunk(int chunkX, int chunkZ, short[] ys, int[] colors, short[] bottoms, int[] bottomColors, long key, int step) {
	}

	public void configure(LevelHeightAccessor level, ChunkGenerator generator, RandomState randomState, BiomeSource biomeSource, int seaLevel,
			int grassBaseColor, int waterBaseColor, int snowBaseColor, int leavesBaseColor,
			int stoneBaseColor, int sandBaseColor, int badlandsBaseColor, int iceBaseColor, Map<Holder<Biome>, Float> treeDensityByBiome,
			boolean tintSurface) {
		this.tintSurface = tintSurface;
		this.level = level;
		this.generator = generator;
		this.randomState = randomState;
		this.biomeSource = biomeSource;
		this.seaLevel = seaLevel;
		this.grassBaseColor = grassBaseColor;
		this.waterBaseColor = waterBaseColor;
		this.snowBaseColor = snowBaseColor;
		this.leavesBaseColor = leavesBaseColor;
		this.stoneBaseColor = stoneBaseColor;
		this.sandBaseColor = sandBaseColor;
		this.badlandsBaseColor = badlandsBaseColor;
		this.iceBaseColor = iceBaseColor;
		this.treeDensityByBiome = treeDensityByBiome != null ? treeDensityByBiome : Map.of();
		pending.clear();
		done.clear();
		ready = true;
		LOGGER.info("PauC distant surface generator ready (seaLevel={}, grassBase=#{}, waterBase=#{}, snowBase=#{}, treeBiomes={}).",
			seaLevel, Integer.toHexString(grassBaseColor), Integer.toHexString(waterBaseColor), Integer.toHexString(snowBaseColor), this.treeDensityByBiome.size());
	}

	public synchronized void reset() {
		ready = false;
		pending.clear();
		done.clear();
		chunkStep.clear();
		pendingFineCount = 0;
	}

	/**
	 * Data resolution (sample step, blocks) recorded for a chunk; unknown chunks (e.g. persisted from
	 * an earlier session) count as coarse (4) so they re-refine when the player comes near.
	 */
	public synchronized int recordedStep(int chunkX, int chunkZ) {
		byte step = chunkStep.get(ChunkPos.asLong(chunkX, chunkZ));
		return step == 0 ? 4 : step;
	}

	/** Marks a chunk as FULL quality (per-column sampling from the loaded chunk — the sampler's path). */
	public synchronized void markFullQuality(int chunkX, int chunkZ) {
		long key = ChunkPos.asLong(chunkX, chunkZ);
		if (chunkStep.get(key) != 1) {
			chunkStep.put(key, (byte) 1);
			stepMapDirty = true;
		}
	}

	/** True when the chunk's data was produced by THIS data version (absent = legacy → re-generate). */
	public synchronized boolean isKnown(int chunkX, int chunkZ) {
		return chunkStep.containsKey(ChunkPos.asLong(chunkX, chunkZ));
	}

	/**
	 * Rebuilds the "known" set from the ALREADY-LOADED region store, on the MAIN THREAD, once per step-map
	 * configuration. A chunk whose columns are persisted on disk is marked known at {@code step} so coverage
	 * never storm-regenerates existing terrain after a step-map loss (version bump / corrupt file). Seeding
	 * at step 4 makes coverage phase 0 (isKnown) AND phase 1 (recordedStep &gt; 4) both skip these chunks;
	 * the bounded near-player fine passes still refine as usual. This is what makes STEP_MAP_VERSION bumps
	 * safe: existing terrain stays visible and is NOT overwritten by coarse regen via the session-wins merge.
	 * MUST be main-thread (the store's region map is not thread-safe); caller gates on the disk load settling.
	 */
	public synchronized void seedKnownFromStore(PauCSurfaceColumnStore store, int step) {
		if (seededFromStore || store == null) {
			return;
		}
		seededFromStore = true;
		byte s = (byte) step;
		int seeded = 0;
		for (long regionKey : store.regionKeys()) {
			int baseChunkX = PauCSurfaceColumnStore.regionXFromKey(regionKey) << 2; // 64 columns / 16 = 4 chunks per region side
			int baseChunkZ = PauCSurfaceColumnStore.regionZFromKey(regionKey) << 2;
			for (int cz = 0; cz < 4; cz++) {
				for (int cx = 0; cx < 4; cx++) {
					int chunkX = baseChunkX + cx;
					int chunkZ = baseChunkZ + cz;
					// Origin-column proxy: the coarse pass fills whole chunks, so one sampled column => present.
					if (!store.isSampled(chunkX << 4, chunkZ << 4)) {
						continue;
					}
					long key = ChunkPos.asLong(chunkX, chunkZ);
					if (!chunkStep.containsKey(key)) {
						chunkStep.put(key, s);
						seeded++;
					}
				}
			}
		}
		if (seeded > 0) {
			stepMapDirty = true;
			LOGGER.info("PauC LOD step map: seeded {} known chunk(s) from the persisted store (step {}) — existing terrain will NOT storm-regenerate.", seeded, step);
		}
	}

	/** Loads the persisted step map for the current dimension (call on the main thread at configure). */
	public synchronized void configureStepMap(java.nio.file.Path path) {
		stepMapPath = path;
		chunkStep.clear();
		stepMapDirty = false;
		seededFromStore = false; // re-seed known chunks from the new dimension's store after its disk load settles
		if (path == null || !java.nio.file.Files.isRegularFile(path)) {
			return;
		}
		try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path)))) {
			if (in.readInt() != STEP_MAP_VERSION) {
				LOGGER.info("PauC LOD data version changed: persisted terrain will be re-generated progressively.");
				return; // old version → everything counts as legacy → coverage re-generates it
			}
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				chunkStep.put(in.readLong(), in.readByte());
			}
		} catch (Throwable throwable) {
			LOGGER.warn("PauC LOD step map unreadable; persisted terrain will be re-generated.", throwable);
			chunkStep.clear();
		}
	}

	/** Saves the step map (throttled unless {@code force}); atomic tmp+move like the region store. */
	public synchronized void saveStepMap(boolean force) {
		if (stepMapPath == null || !stepMapDirty) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && now - lastStepMapSaveMs < 60_000L) {
			return;
		}
		lastStepMapSaveMs = now;
		try {
			java.nio.file.Files.createDirectories(stepMapPath.getParent());
			java.nio.file.Path tmp = stepMapPath.resolveSibling(stepMapPath.getFileName() + ".tmp");
			try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp)))) {
				out.writeInt(STEP_MAP_VERSION);
				out.writeInt(chunkStep.size());
				for (var entry : chunkStep.long2ByteEntrySet()) {
					out.writeLong(entry.getLongKey());
					out.writeByte(entry.getByteValue());
				}
			}
			java.nio.file.Files.move(tmp, stepMapPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			stepMapDirty = false;
		} catch (Throwable throwable) {
			LOGGER.warn("PauC LOD step map save failed (will retry).", throwable);
		}
	}

	public boolean isReady() {
		return ready && !failed;
	}

	public synchronized int pendingCount() {
		return pending.size();
	}

	public long generatedTotal() {
		return generatedTotal;
	}

	/**
	 * Main thread: queues a chunk for background surface generation at the given sample step
	 * (4 = fast coverage, 2/1 = near-player refinement). @return {@code true} if newly queued.
	 */
	public synchronized boolean submit(int chunkX, int chunkZ, int sampleStep) {
		if (!ready || failed) {
			return false;
		}
		long key = ChunkPos.asLong(chunkX, chunkZ);
		if (pending.contains(key) || pending.size() >= PENDING_CAP) {
			return false;
		}
		int step = sampleStep == 1 || sampleStep == 2 || sampleStep == 16 ? sampleStep : 4;
		if (step >= 4 && pending.size() >= PENDING_CAP - 64) {
			// RESERVED LANES for fine refinement: during coverage the queue sat pinned at the cap and
			// near-ring detail submits were rejected forever ("detail updates never happen").
			return false;
		}
		if (step < 4 && pendingFineCount >= FINE_PENDING_CAP) {
			return false; // refinement queue full — coverage keeps priority on the workers
		}
		pending.add(key);
		if (step < 4) {
			pendingFineCount++;
			fineWorker.submit(() -> generate(chunkX, chunkZ, key, step)); // dedicated lane, never queues behind coverage
		} else {
			worker.submit(() -> generate(chunkX, chunkZ, key, step));
		}
		return true;
	}

	private int pendingFineCount;

	private void generate(int chunkX, int chunkZ, long key, int step) {
		try {
			ChunkGenerator gen = generator;
			RandomState random = randomState;
			LevelHeightAccessor heightAccessor = level;
			BiomeSource biomes = biomeSource;
			int sea = seaLevel;
			if (gen == null || random == null || heightAccessor == null || biomes == null) {
				return;
			}

			boolean treesOn = PauCTunables.readBoolean("pauc.lodengine.distantTrees", true);
			float treeDensityScale = readFloat("pauc.lodengine.treeDensityScale", 1.0F, 0.0F, 3.0F);
			int treeMinHeight = readInt("pauc.lodengine.treeMinHeight", 4, 0, 24);
			int treeExtraHeight = readInt("pauc.lodengine.treeExtraHeight", 5, 0, 24);

			short[] ys = new short[256];
			int[] colors = new int[256];
			// Floating dimension: approximate island undersides with a varied hashed thickness (the
			// noise API only exposes the heightmap) — visited chunks record the REAL profile instead.
			short[] bottoms = new short[256];
			java.util.Arrays.fill(bottoms, Short.MIN_VALUE);
			int[] bottomColors = new int[256];
			int minX = chunkX << 4;
			int minZ = chunkZ << 4;
			// DH-AS-DATA-SOURCE (approach 1, tunable default OFF): pull already-generated LOD columns
			// from Distant Horizons into the span arrays; getBaseHeight then only fills the columns DH
			// could not supply (left as MIN_VALUE). Gated reflectively — no-op without DH installed, so
			// the eager-classload law holds and PauC still generates everything itself when DH is absent.
			java.util.Arrays.fill(ys, Short.MIN_VALUE);
			boolean dhSource = PauCTunables.readBoolean("pauc.lodengine.dhDataSource", false)
				&& fr.hoyatla.pauc.lod.PauCLodBridgeAccess.isDhDataSourceAvailable();
			if (dhSource) {
				fr.hoyatla.pauc.lod.PauCLodBridgeAccess.fillChunkFromDh(chunkX, chunkZ, step, ys, colors, bottoms, bottomColors);
			}
			// Sample the noise generator at the requested grid step: 4 for fast first coverage (getBaseHeight
			// walks a full noise column, ~1.3ms), 2/1 for near-player refinement passes — the data quality
			// follows the player like the mesh bands do. NOTE: slope-based rock was removed — it painted
			// alien grey "rock columns"; rock strata come from the RENDERER (dirt band → stone on deep
			// walls); distant gen keeps rock only for genuinely high peaks.
			int stoneAltitude = readInt("pauc.lodengine.stoneAltitude", 140, 0, 320);
			// Surface fidelity follows the dynamic-res tier: enriched materials (podzol/mud/gravel) at
			// QUALITY/BALANCED/OFF, cheap 9-class heuristic at PERFORMANCE. Read once per chunk, not per tile.
			boolean enriched = tintSurface && surfaceFidelity() >= 1;
			// P2: real surface block colours for the whole chunk (one heavy gen), or null (heuristic fallback).
			int[] realColors = (tintSurface && PauCTunables.readBoolean(REAL_SURFACE_PROPERTY, REAL_SURFACE_DEFAULT))
				? generateRealSurface(chunkX, chunkZ) : null;
			for (int tz = 0; tz < 16; tz += step) {
				for (int tx = 0; tx < 16; tx += step) {
					int worldX = minX + tx;
					int worldZ = minZ + tz;
					// DH already supplied this column (approach 1): keep its spans, skip regeneration.
					if (dhSource && ys[(tz << 4) | tx] != Short.MIN_VALUE) {
						continue;
					}
					// getBaseHeight returns the FIRST FREE Y (one block ABOVE the top block), while the
					// visited sampler stores the top block Y itself — mixing the two put the whole
					// generated field ONE BLOCK HIGHER than the vanilla terrain (the raised first LOD
					// ring at the seam). Normalise to top-block Y.
					int solidY = gen.getBaseHeight(worldX, worldZ, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, random) - 1;
					if (solidY <= heightAccessor.getMinBuildHeight()) {
						// VOID column (End between islands): mark EMPTY — never a false floor at world bottom.
						for (int dz = 0; dz < step; dz++) {
							for (int dx = 0; dx < step; dx++) {
								int index = ((tz + dz) << 4) | (tx + dx);
								ys[index] = Short.MIN_VALUE;
								colors[index] = 0;
								bottoms[index] = Short.MIN_VALUE;
							}
						}
						continue;
					}
					int waterTop = sea - 1; // top water block of the ocean surface (sea level = first air Y)
					boolean water = sea > heightAccessor.getMinBuildHeight() + 1 && solidY < waterTop;
					boolean rocky = !water && solidY >= stoneAltitude;
					Holder<Biome> biome = biomes.getNoiseBiome(
						QuartPos.fromBlock(worldX), QuartPos.fromBlock(water ? waterTop : solidY), QuartPos.fromBlock(worldZ), random.sampler());
					// SWAMP POOLS: swampy ground sits AT sea level — the base heightmap never dips under
					// it, so tagged/named swamp biomes got zero water and read as a dry grey plain.
					// Hash-speckled shallow pools (translucent, real floor) restore the marsh look.
					if (!water && solidY <= sea && isSwampy(biome) && tileHash(worldX + 419, worldZ + 283) < 0.45F) {
						water = true;
						solidY = Math.min(solidY, waterTop - 1);
					}
					short y = (short) (water ? waterTop : solidY);
					// Frozen biomes: the sea surface is ICE, not open water (frozen ocean/river ice sheets).
					boolean icy = water && biome.value().coldEnoughToSnow(new BlockPos(worldX, waterTop, worldZ));
					int color = surfaceColor(biome, worldX, worldZ, solidY, sea, water, rocky, icy, enriched);
					// P2: replace the GUESSED land colour with the REAL surface block's colour (keeping the
					// heuristic's alpha tag so soil-wall / species semantics survive). Water keeps its path.
					if (realColors != null && !water) {
						color = (realColors[(tz << 4) | tx] & 0x00ffffff) | (color & 0xff000000);
					}
					int floorColor = 0;
					if (water && !icy) {
						// TRANSLUCENT water: span 0 = the water surface (biome tint, WATER tag), span 1 =
						// the FLOOR (real height + colour darkened by depth, same formula as the sampler)
						// rendered opaque underneath — vanilla's look emerges from actual translucency.
						color = (color & 0x00ffffff) | (PauCSurfaceColumnStore.WATER_ALPHA << 24);
						int depth = Math.max(0, waterTop - solidY);
						floorColor = scaleRgb(sandBaseColor, 1.0F - Math.min(depth, 16) / 16.0F * 0.55F) | 0xff000000;
					}

					// Tree overlay (distant only; visited chunks already capture real canopy via WORLD_SURFACE).
					// Deterministic per-tile hash gated by the biome's tag-derived density raises a canopy bump
					// tagged TREE_ALPHA so the renderer draws the two-tone "mushroom" (leaves + trunk wall).
					// No trees on cliffs/rock — trees hugging vertical stone read wrong.
					// No trees on the 16-block blitz pass: one sample fills the whole chunk, a canopy bump
					// there would be a 16-wide green monolith. Trees arrive with the step-4 upgrade.
					boolean treeCell = false;
					int treeGroundColor = 0;
					if (treesOn && step <= 4 && !water) {
						float density = treeDensityByBiome.getOrDefault(biome, 0.0F) * treeDensityScale;
						// Tree placement/height hashes on the 4-ALIGNED cell so refinement passes (step 2/1)
						// keep the SAME trees the coarse pass planted — no forest reshuffle on upgrade.
						int cellX = worldX & ~3;
						int cellZ = worldZ & ~3;
						// Dense-forest biomes keep their trees on rocky/high ground (taiga mountains ARE
						// forested — bare grey LOD chains that turn into spruce woods on approach broke
						// the read); sparse biomes still leave cliffs bare.
						if (density > 0.0F && (!rocky || density >= 0.45F) && tileHash(cellX, cellZ) < density) {
							treeCell = true;
							treeGroundColor = color | 0xff000000; // pre-canopy surface colour = the ground
							int canopy = treeMinHeight + (int) (tileHash(cellX + 811, cellZ + 137) * treeExtraHeight);
							y = (short) (solidY + canopy);
							int leaves = PauCBlockColorCache.multiplyArgbWithRgb(leavesBaseColor, blendedTint(biome, worldX, solidY, worldZ, 2));
							// Per-tree tone variation: uniform canopy colour made forests read as one green carpet.
							leaves = scaleRgb(leaves, 0.90F + 0.20F * tileHash(cellX + 397, cellZ + 733));
							if (biome.value().coldEnoughToSnow(new BlockPos(worldX, solidY + canopy, worldZ))) {
								// Snowy canopy: cold-biome trees carry snow at LOD distance — a pure green
								// canopy jarred against vanilla's white-dusted taigas.
								int sr = (snowBaseColor >> 16) & 0xff;
								int sg = (snowBaseColor >> 8) & 0xff;
								int sb = snowBaseColor & 0xff;
								int lr = (leaves >> 16) & 0xff;
								int lg = (leaves >> 8) & 0xff;
								int lb = leaves & 0xff;
								leaves = ((lr + (sr - lr) * 45 / 100) << 16)
									| ((lg + (sg - lg) * 45 / 100) << 8)
									| (lb + (sb - lb) * 45 / 100);
							}
							color = (leaves & 0x00ffffff) | (imposterTreeAlpha(biome) << 24);
						}
					}

					short bottom = Short.MIN_VALUE;
					int bottomColor = 0;
					if (water && !icy) {
						bottom = (short) solidY; // ocean floor under the translucent surface
						bottomColor = floorColor;
					} else if (treeCell) {
						bottom = (short) solidY; // GROUND under the canopy (renderer floats the leaf slab)
						bottomColor = treeGroundColor;
					} else if (!tintSurface) {
						// End island underside: a SMOOTH, low-frequency thickness (bilinear hash on a
						// 32-block grid), NOT a per-tile random one. The random version made every tile
						// hang to a different depth — a picket-fence of vertical spikes around the island
						// edges. Real island bottoms are gently domed, so interpolate for a clean belly.
						int cell = 32;
						int cx0 = Math.floorDiv(worldX, cell) * cell;
						int cz0 = Math.floorDiv(worldZ, cell) * cell;
						float fx = (worldX - cx0) / (float) cell;
						float fz = (worldZ - cz0) / (float) cell;
						float h00 = tileHash(cx0 + 557, cz0 + 919);
						float h10 = tileHash(cx0 + cell + 557, cz0 + 919);
						float h01 = tileHash(cx0 + 557, cz0 + cell + 919);
						float h11 = tileHash(cx0 + cell + 557, cz0 + cell + 919);
						float smooth = (h00 + (h10 - h00) * fx) + ((h01 + (h11 - h01) * fx) - (h00 + (h10 - h00) * fx)) * fz;
						int thickness = 3 + (int) (smooth * 7.0F); // 3..10 blocks: THIN slabs, no long void columns
						bottom = (short) Math.max(heightAccessor.getMinBuildHeight() + 1, solidY - thickness);
					}
					if (step == 16 && !water) {
						// BLITZ SMOOTHING: one flat sample per chunk rendered as 16-block PLATEAUS ("gros cubes")
						// through the triangle sheet. Sample the four CHUNK CORNERS and fill bilinearly — virgin
						// terrain is born SLOPED from the first pass; refinement sharpens the real relief later.
						int c10 = gen.getBaseHeight(minX + 16, minZ, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, random) - 1;
						int c01 = gen.getBaseHeight(minX, minZ + 16, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, random) - 1;
						int c11 = gen.getBaseHeight(minX + 16, minZ + 16, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, random) - 1;
						int yOff = y - solidY;
						for (int dz = 0; dz < 16; dz++) {
							for (int dx = 0; dx < 16; dx++) {
								float fxs = dx / 16.0F;
								float fzs = dz / 16.0F;
								float hb = (solidY + (c10 - solidY) * fxs)
									+ ((c01 + (c11 - c01) * fxs) - (solidY + (c10 - solidY) * fxs)) * fzs;
								int index = (dz << 4) | dx;
								ys[index] = (short) (Math.round(hb) + yOff);
								colors[index] = color;
								bottoms[index] = bottom;
								bottomColors[index] = bottomColor;
							}
						}
						continue;
					}
					for (int dz = 0; dz < step; dz++) {
						for (int dx = 0; dx < step; dx++) {
							int index = ((tz + dz) << 4) | (tx + dx);
							ys[index] = y;
							colors[index] = color;
							bottoms[index] = bottom;
							bottomColors[index] = bottomColor;
						}
					}
				}
			}
			done.add(new GeneratedChunk(chunkX, chunkZ, ys, colors, bottoms, bottomColors, key, step));
		} catch (Throwable throwable) {
			if (!failed) {
				failed = true;
				LOGGER.warn("PauC distant surface generation failed; disabled for this session.", throwable);
			}
		}
	}

	/** Mushroom/fungal biome: no vanilla biome tag exists, so match by name (mushroom_fields + modded). */
	/** Distant-tree imposter kind by biome (visited trees classify from the real leaf block instead). */
	private static int imposterTreeAlpha(Holder<Biome> biome) {
		if (biome.is(net.minecraft.tags.BiomeTags.IS_TAIGA)) {
			return PauCSurfaceColumnStore.CONIFER_ALPHA;
		}
		if (biome.is(net.minecraft.tags.BiomeTags.IS_JUNGLE)) {
			return PauCSurfaceColumnStore.JUNGLE_ALPHA;
		}
		return biome.unwrapKey().map(key -> {
			String path = key.location().getPath();
			if (path.contains("mangrove")) {
				return PauCSurfaceColumnStore.MANGROVE_ALPHA;
			}
			if (path.contains("cherry")) {
				return PauCSurfaceColumnStore.CHERRY_ALPHA;
			}
			if (path.contains("dark_forest") || path.contains("roofed")) {
				return PauCSurfaceColumnStore.DARK_OAK_ALPHA;
			}
			if (path.contains("birch")) {
				return PauCSurfaceColumnStore.BIRCH_ALPHA;
			}
			if (path.contains("savanna")) {
				return PauCSurfaceColumnStore.SAVANNA_ALPHA;
			}
			if (path.contains("taiga") || path.contains("pine") || path.contains("spruce")
				|| path.contains("grove") || path.contains("snowy")) {
				return PauCSurfaceColumnStore.CONIFER_ALPHA;
			}
			if (path.contains("jungle")) {
				return PauCSurfaceColumnStore.JUNGLE_ALPHA;
			}
			return PauCSurfaceColumnStore.TREE_ALPHA;
		}).orElse(PauCSurfaceColumnStore.TREE_ALPHA);
	}

	/** Savanna-family biome (name heuristic; no vanilla IS_SAVANNA tag). Savannas are GRASS + acacia,
	 *  never sand — even windswept savanna (temp 2.0) must not fall into the hot-dry sand branch. */
	private static boolean isSavanna(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.location().getPath().contains("savanna")).orElse(Boolean.FALSE);
	}

	private static boolean isMushroom(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> {
			String path = key.location().getPath();
			return path.contains("mushroom") || path.contains("fungal") || path.contains("fungi");
		}).orElse(Boolean.FALSE);
	}

	/** Swamp-family biome: vanilla tag (swamp + mangrove carry closer water fog) or name heuristic (modded wetlands). */
	private static boolean isSwampy(Holder<Biome> biome) {
		if (biome.is(net.minecraft.tags.BiomeTags.HAS_CLOSER_WATER_FOG)) {
			return true;
		}
		return biome.unwrapKey().map(key -> {
			String path = key.location().getPath();
			return path.contains("swamp") || path.contains("marsh") || path.contains("bayou")
				|| path.contains("bog") || path.contains("mangrove");
		}).orElse(Boolean.FALSE);
	}

	/** Podzol floor: old-growth (mega) taigas carry podzol, not grass. Name heuristic (no vanilla tag);
	 *  excludes birch old-growth (grass floor). Covers modded mega/old-growth conifer variants. */
	private static boolean isPodzolFloor(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> {
			String path = key.location().getPath();
			return (path.contains("old_growth") || path.contains("mega")) && !path.contains("birch");
		}).orElse(Boolean.FALSE);
	}

	/** Muddy floor: mangrove swamps sit on MUD, not grass (regular swamps stay grass). */
	private static boolean isMuddy(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.location().getPath().contains("mangrove")).orElse(Boolean.FALSE);
	}

	/** Gravel floor: windswept gravelly hills and stony shores expose gravel, not grass/stone. */
	private static boolean isGravelly(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> {
			String path = key.location().getPath();
			return path.contains("gravelly") || path.contains("stony_shore");
		}).orElse(Boolean.FALSE);
	}

	/**
	 * Surface-material fidelity level, derived from the user's dynamic-resolution tier so PauC glides
	 * from "DH quality" at the top to "triangle performance" at the bottom:
	 *   PERFORMANCE -> 0 (cheapest 9-class heuristic, current behaviour)
	 *   BALANCED / QUALITY -> 1 (enriched materials: podzol/mud/gravel)
	 *   OFF (max quality) -> 2 (real distant column — future lot; falls back to 1 until implemented)
	 * Read ONCE per generate() call (not per tile) — a plain System-property lookup underneath.
	 */
	private static int surfaceFidelity() {
		fr.hoyatla.pauc.lod.PauCDynamicResolutionMode mode = fr.hoyatla.pauc.lod.PauCLodClientSettings.dynamicResolutionMode();
		switch (mode) {
			case PERFORMANCE:
				return 0;
			case OFF:
				return 2;
			default: // QUALITY, BALANCED
				return 1;
		}
	}

	/**
	 * P2: generate the REAL surface of one chunk (vanilla SurfaceRules on a throwaway ProtoChunk)
	 * and return per-column top-block colours (ARGB, index {@code (localZ<<4)|localX}), or {@code null} on
	 * any failure OR when disabled. After {@link #MAX_REAL_SURFACE_FAILURES} consecutive failures the
	 * feature disables for the session (transient chunk-status errors no longer kill it permanently).
	 *
	 * <p>Approach: pre-fill the ProtoChunk's heightmaps using {@code getBaseHeight} (proven reliable in the
	 * main gen loop), then call {@code buildSurface} which evaluates the dimension's {@code SurfaceRules}
	 * to place real surface blocks (grass, sand, stone, etc.). This avoids {@code fillFromNoise} and its
	 * {@code Blender}/neighbor-chunk dependency that caused the original "structure_references" failure.</p>
	 */
	// Reflective access to Heightmap.setHeight (private in 1.20.1) — needed to pre-fill heightmaps
	// for the buildSurface-only path that avoids fillFromNoise's Blender/neighbor-chunk dependency.
	private static final java.lang.reflect.Method HEIGHTMAP_SET_HEIGHT;
	static {
		java.lang.reflect.Method m = null;
		try {
			m = net.minecraft.world.level.levelgen.Heightmap.class.getDeclaredMethod("setHeight", int.class, int.class, int.class);
			m.setAccessible(true);
		} catch (Throwable ignored) {
		}
		HEIGHTMAP_SET_HEIGHT = m;
	}

	private static final int MAX_REAL_SURFACE_FAILURES = 16;
	private int realSurfaceFailures;

	private int[] generateRealSurface(int chunkX, int chunkZ) {
		if (realSurfaceGenFailed || realSurfaceBudget.getAndDecrement() <= 0) {
			return null;
		}
		ChunkGenerator gen = generator;
		net.minecraft.world.level.levelgen.RandomState random = randomState;
		if (gen == null || random == null || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
			return null;
		}
		try {
			net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
			net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeReg =
				serverLevel.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
			net.minecraft.world.level.chunk.ProtoChunk proto = new net.minecraft.world.level.chunk.ProtoChunk(
				pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY, serverLevel, biomeReg, null);
			net.minecraft.world.level.levelgen.Heightmap.Types oceanFloor = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG;
			net.minecraft.world.level.levelgen.Heightmap.Types worldSurface = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG;
			net.minecraft.world.level.levelgen.Heightmap hmOcean = proto.getOrCreateHeightmapUnprimed(oceanFloor);
			net.minecraft.world.level.levelgen.Heightmap hmSurface = proto.getOrCreateHeightmapUnprimed(worldSurface);
			// Pre-fill heightmaps using getBaseHeight — avoids fillFromNoise and its Blender/neighbor dependency.
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					int worldX = (chunkX << 4) + lx;
					int worldZ = (chunkZ << 4) + lz;
					int height = gen.getBaseHeight(worldX, worldZ, oceanFloor, level, random);
					if (HEIGHTMAP_SET_HEIGHT != null) {
						try { HEIGHTMAP_SET_HEIGHT.invoke(hmOcean, lx, lz, height); } catch (Throwable ignored) {}
						try { HEIGHTMAP_SET_HEIGHT.invoke(hmSurface, lx, lz, height); } catch (Throwable ignored) {}
					}
				}
			}
			// buildSurface applies vanilla SurfaceRules to place real surface blocks (grass/sand/stone/etc.).
			// Only needs the chunk's heightmaps — no neighboring chunks required.
			java.util.List<net.minecraft.world.level.chunk.ChunkAccess> single = java.util.List.of(proto);
			net.minecraft.server.level.WorldGenRegion region = new net.minecraft.server.level.WorldGenRegion(
				serverLevel, single, net.minecraft.world.level.chunk.ChunkStatus.SURFACE, 0);
			net.minecraft.world.level.StructureManager structManager = serverLevel.structureManager().forWorldGenRegion(region);
			gen.buildSurface(region, structManager, random, proto);
			// Read resulting block states and map to colours.
			int[] colors = new int[256];
			net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
			BiomeSource biomes = biomeSource;
			RandomState rs = randomState;
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					int top = proto.getHeight(oceanFloor, lx, lz) - 1;
					int worldX = (chunkX << 4) + lx;
					int worldZ = (chunkZ << 4) + lz;
					cursor.set(worldX, top, worldZ);
					net.minecraft.world.level.block.state.BlockState state = proto.getBlockState(cursor);
					if (state == null) {
						state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
					}
					int base = REAL_SURFACE_COLORS.baseColor(state);
					if (tintSurface && biomes != null && rs != null && isTintedBlock(state)) {
						try {
							Holder<Biome> biome = biomes.getNoiseBiome(
								QuartPos.fromBlock(worldX), QuartPos.fromBlock(top), QuartPos.fromBlock(worldZ),
								rs.sampler());
							int tintMode = state.getFluidState().isEmpty() ? 2 : 1;
							int tint = blendedTint(biome, worldX, top, worldZ, tintMode);
							base = PauCBlockColorCache.multiplyArgbWithRgb(base, tint);
						} catch (Throwable ignored) {
						}
					}
					colors[(lz << 4) | lx] = base;
				}
			}
			// Success: reset transient failure counter.
			realSurfaceFailures = 0;
			if (!realSurfaceLogged) {
				realSurfaceLogged = true;
				LOGGER.info("PauC REAL SURFACE GEN (P2) active: distant chunk {},{} coloured from its real generated surface blocks.", chunkX, chunkZ);
			}
			return colors;
		} catch (Throwable throwable) {
			realSurfaceFailures++;
			if (realSurfaceFailures >= MAX_REAL_SURFACE_FAILURES) {
				realSurfaceGenFailed = true;
				LOGGER.warn("PauC real surface gen (P2) failed {} times — disabled for this session, falling back to the heightmap heuristic.", realSurfaceFailures, throwable);
			} else if (realSurfaceFailures == 1) {
				LOGGER.debug("PauC real surface gen (P2) transient failure {}/{} for chunk {},{} — will retry.", realSurfaceFailures, MAX_REAL_SURFACE_FAILURES, chunkX, chunkZ);
			}
			return null;
		}
	}

	private int surfaceColor(Holder<Biome> biome, int worldX, int worldZ, int solidY, int sea, boolean water, boolean rocky, boolean icy, boolean enriched) {
		try {
			if (!tintSurface) {
				// End-like dimension: plain untinted surface base (end stone) — no grass tint, no snow.
				return grassBaseColor | 0xff000000;
			}
			if (icy) {
				// Frozen sea surface: ice colour, untinted, plain alpha (ice walls stay ice-coloured).
				return iceBaseColor | 0xff000000;
			}
			if (water) {
				return PauCBlockColorCache.multiplyArgbWithRgb(waterBaseColor, blendedTint(biome, worldX, solidY, worldZ, 1)) | 0xff000000;
			}
			// Snow caps: generic (temperature/elevation via coldEnoughToSnow), no hardcoded biome list, so it
			// works on any modpack. Snow is untinted white — matches the visited colour-cache snow_block tone.
			// Snow wins over rock (snowy peaks) — rock shows on cliffs below the snow line.
			if (biome.value().coldEnoughToSnow(new BlockPos(worldX, solidY, worldZ))) {
				return snowBaseColor | 0xff000000;
			}
			// Badlands: terracotta tones (tagged — modded badlands covered).
			if (biome.is(net.minecraft.tags.BiomeTags.IS_BADLANDS)) {
				return badlandsBaseColor | 0xff000000;
			}
			// Mushroom fields: the ground is MYCELIUM (purple-grey), never green grass. Vanilla ships no
			// IS_MUSHROOM biome tag, so match by name (covers modded fungal/mushroom biomes too).
			if (isMushroom(biome)) {
				return 0xff6E5F6B; // mycelium texture-average tone
			}
			// Enriched surface materials (fidelity >= 1): real ground varies beyond the 9-class heuristic.
			// UNtinted (like badlands/mycelium/sand). Placed before rock so gravelly hills read gravel,
			// not the grey stone the altitude branch would otherwise paint.
			if (enriched) {
				if (isGravelly(biome)) {
					return GRAVEL_BASE_COLOR;
				}
				if (isPodzolFloor(biome)) {
					return PODZOL_BASE_COLOR;
				}
				if (isMuddy(biome)) {
					return MUD_BASE_COLOR;
				}
			}
			// Cliffs and high peaks: exposed rock (slope/altitude computed by the caller).
			if (rocky) {
				return stoneBaseColor | 0xff000000;
			}
			// Beaches (tagged) and hot no-rain biomes (deserts, temp >= 1.9 keeps savannas grassy): sand.
			if (biome.is(net.minecraft.tags.BiomeTags.IS_BEACH)
				|| (!isSavanna(biome) && !biome.value().hasPrecipitation() && biome.value().getBaseTemperature() >= 1.9F)) {
				return sandBaseColor | 0xff000000;
			}
			// base(texture-average) x biome tint — matches the colour cache used for visited chunks, so the
			// distant horizon reads at the same (muted) tone as the near LOD instead of raw vivid biome hues.
			// SOIL tag: grass tops get dirt-coloured walls in the renderer (green top, earthen sides).
			return (PauCBlockColorCache.multiplyArgbWithRgb(grassBaseColor, blendedTint(biome, worldX, solidY, worldZ, 0)) & 0x00ffffff)
				| (PauCSurfaceColumnStore.SOIL_ALPHA << 24);
		} catch (Throwable ignored) {
			return (water ? PauCBlockColorCache.multiplyArgbWithRgb(waterBaseColor, DEFAULT_WATER_COLOR) : heightFallback(solidY, sea)) | 0xff000000;
		}
	}

	/**
	 * VANILLA-MATCHING biome tint: vanilla blends grass/water/foliage colours across neighbouring
	 * biomes (client biome blend); the raw single-biome tint read as HARSH colours and hard borders.
	 * Average the tint over the centre + 4 neighbours at 8 blocks. mode: 0 grass, 1 water, 2 foliage.
	 */
	private int blendedTint(Holder<Biome> center, int worldX, int worldY, int worldZ, int mode) {
		BiomeSource biomes = biomeSource;
		RandomState random = randomState;
		int r = 0;
		int g = 0;
		int b = 0;
		for (int i = 0; i < 5; i++) {
			int ox = i == 1 ? 8 : i == 2 ? -8 : 0;
			int oz = i == 3 ? 8 : i == 4 ? -8 : 0;
			Holder<Biome> bi = i == 0 || biomes == null || random == null ? center
				: biomes.getNoiseBiome(QuartPos.fromBlock(worldX + ox), QuartPos.fromBlock(worldY),
					QuartPos.fromBlock(worldZ + oz), random.sampler());
			int c = mode == 0 ? bi.value().getGrassColor(worldX + ox, worldZ + oz)
				: mode == 1 ? bi.value().getWaterColor() : bi.value().getFoliageColor();
			r += (c >> 16) & 0xff;
			g += (c >> 8) & 0xff;
			b += c & 0xff;
		}
		return ((r / 5) << 16) | ((g / 5) << 8) | (b / 5);
	}

	/** Mixes two ARGB colours' RGB channels ({@code f}=0 → a, 1 → b), keeping b's alpha. */
	private static int mixRgb(int a, int b, float f) {
		int r = (int) (((a >> 16) & 0xff) * (1.0F - f) + ((b >> 16) & 0xff) * f);
		int g = (int) (((a >> 8) & 0xff) * (1.0F - f) + ((b >> 8) & 0xff) * f);
		int bl = (int) ((a & 0xff) * (1.0F - f) + (b & 0xff) * f);
		return (b & 0xff000000) | (r << 16) | (g << 8) | bl;
	}

	/** Scales the RGB of an ARGB colour by a factor, keeping alpha. */
	private static int scaleRgb(int argb, float factor) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xff) * factor));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xff) * factor));
		int b = Math.min(255, Math.round((argb & 0xff) * factor));
		return (argb & 0xff000000) | (r << 16) | (g << 8) | b;
	}

	/** Deterministic per-position hash in [0,1) — used to scatter tree canopy without a stored RNG. */
	private static float tileHash(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0x7fffffff) / (float) 0x7fffffff;
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String raw = PauCTunables.raw(key);
		if (raw == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String raw = PauCTunables.raw(key);
		if (raw == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Float.parseFloat(raw.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	/** Blocks whose colour is biome-tinted in vanilla (grass, leaves, vines, water, etc.). */
	private static boolean isTintedBlock(net.minecraft.world.level.block.state.BlockState state) {
		if (state == null) {
			return false;
		}
		net.minecraft.world.level.block.Block block = state.getBlock();
		return block instanceof net.minecraft.world.level.block.LeavesBlock
			|| block instanceof net.minecraft.world.level.block.BushBlock
			|| !state.getFluidState().isEmpty();
	}

	private static int heightFallback(int solidY, int sea) {
		float t = Math.max(0.0F, Math.min(1.0F, (solidY - sea) / 64.0F));
		int low = 0x6B8E23; // olive lowland
		int high = 0x9E9E8A; // pale highland
		int r = (int) (((low >> 16) & 0xff) + t * (((high >> 16) & 0xff) - ((low >> 16) & 0xff)));
		int g = (int) (((low >> 8) & 0xff) + t * (((high >> 8) & 0xff) - ((low >> 8) & 0xff)));
		int b = (int) ((low & 0xff) + t * ((high & 0xff) - (low & 0xff)));
		return (r << 16) | (g << 8) | b;
	}

	/** Main thread: inserts up to {@code budget} generated chunks into the store. @return count inserted. */
	public synchronized int drain(PauCSurfaceColumnStore store, int budget) {
		int inserted = 0;
		GeneratedChunk chunk;
		while (inserted < budget && (chunk = done.poll()) != null) {
			int minX = chunk.chunkX() << 4;
			int minZ = chunk.chunkZ() << 4;
			for (int dz = 0; dz < 16; dz++) {
				for (int dx = 0; dx < 16; dx++) {
					int index = (dz << 4) | dx;
					spanY[0] = chunk.ys()[index];
					spanColor[0] = chunk.colors()[index];
					spanLight[0] = (byte) 0xF0; // full sky light, no block light
					for (int span = 1; span < PauCSurfaceColumnStore.MAX_SPANS; span++) {
						spanY[span] = Short.MIN_VALUE;
						spanColor[span] = 0;
						spanLight[span] = 0;
					}
					if (chunk.bottoms() != null) {
						spanY[1] = chunk.bottoms()[index]; // water floor / island underside
						spanColor[1] = chunk.bottomColors() != null ? chunk.bottomColors()[index] : 0;
					}
					store.putColumn(minX + dx, minZ + dz, spanY, spanColor, spanLight);
				}
			}
			pending.remove(chunk.key());
			if (chunk.step() < 4) {
				pendingFineCount = Math.max(0, pendingFineCount - 1);
			}
			chunkStep.put(chunk.key(), (byte) chunk.step());
			stepMapDirty = true;
			inserted++;
			generatedTotal++;
			if (!firstChunkLogged) {
				firstChunkLogged = true;
				LOGGER.info("PauC distant surface generator: first generated horizon chunk inserted into the store.");
			}
		}
		return inserted;
	}
}
