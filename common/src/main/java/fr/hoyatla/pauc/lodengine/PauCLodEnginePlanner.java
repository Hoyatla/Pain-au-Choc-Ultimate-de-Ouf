package fr.hoyatla.pauc.lodengine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PauC LOD engine PLANNERS — the "who works on what" layer, OFF the main thread.
 *
 * <p>The old design ran every submission cursor inside the client tick with per-tick budgets:
 * coverage, refinement and repairs advanced in lockstep, stalled whole ticks whenever a queue was
 * full, and each sweep had to finish before serving a newer need. These are three independent
 * DAEMON threads that fill the generation queues CONTINUOUSLY and in parallel:</p>
 * <ul>
 * <li><b>COVERAGE</b> — blitz (step 16) then upgrade (step 4) spiral around the player, re-armed on
 * movement. A full queue costs a few milliseconds of sleep, never a lost tick.</li>
 * <li><b>REFINEMENT</b> — near-ring fine spiral (steps 1/2) around the player, restarted the moment
 * the player moves; nearest cells always submit first.</li>
 * <li><b>HOLES</b> — near-first spiral over the WHOLE covered disc hunting empty columns AND absent
 * regions/never-submitted chunks (the fast-flight voids); each faulty chunk gets ONE regeneration
 * attempt per session (End void columns are legitimately empty — one attempt keeps them from
 * looping forever).</li>
 * </ul>
 * <p>The main tick only PUBLISHES state (player position, level, radii, a region snapshot) and
 * drains generation results into the store — it never scans anything again.</p>
 */
public final class PauCLodEnginePlanner {
	private static final Logger LOGGER = LoggerFactory.getLogger(PauCLodEnginePlanner.class);

	private static volatile ClientLevel level;
	private static volatile PauCDistantSurfaceGenerator gen;
	private static volatile int playerChunkX;
	private static volatile int playerChunkZ;
	private static volatile int vanillaChunks = 12;
	private static volatile int distantRadius;
	private static volatile long[] regionKeys = new long[0];
	private static volatile PauCSurfaceColumnStore.Region[] regionSnapshot = new PauCSurfaceColumnStore.Region[0];
	/** Bumped on world/dimension change: every planner aborts its sweep and starts fresh. */
	private static volatile int epoch;
	private static volatile boolean coverageDone;
	private static volatile boolean started;

	private PauCLodEnginePlanner() {
	}

	/** Main tick: publish the current state; planners pick it up on their own schedule. */
	public static void publish(ClientLevel clientLevel, PauCDistantSurfaceGenerator generator,
			int chunkX, int chunkZ, int vanilla, int radius) {
		level = clientLevel;
		gen = generator;
		playerChunkX = chunkX;
		playerChunkZ = chunkZ;
		vanillaChunks = vanilla;
		distantRadius = radius;
		ensureStarted();
	}

	/** Main tick (~2s cadence): safe snapshot of the store's regions for the hole scanner. */
	public static void publishRegions(long[] keys, PauCSurfaceColumnStore.Region[] regions) {
		regionKeys = keys;
		regionSnapshot = regions;
	}

	public static void onWorldChange() {
		level = null;
		coverageDone = false;
		epoch++;
	}

	public static boolean coverageDone() {
		return coverageDone;
	}

	private static synchronized void ensureStarted() {
		if (started) {
			return;
		}
		started = true;
		daemon("PauC-LodPlan-Coverage", PauCLodEnginePlanner::coverageLoop);
		daemon("PauC-LodPlan-Refine", PauCLodEnginePlanner::refineLoop);
		daemon("PauC-LodPlan-Holes", PauCLodEnginePlanner::holeLoop);
		LOGGER.info("PauC LOD engine planners started: coverage + refinement + hole-repair run in parallel off-tick.");
	}

	private static void daemon(String name, Runnable body) {
		Thread thread = new Thread(() -> {
			while (true) {
				try {
					body.run();
				} catch (Throwable throwable) {
					LOGGER.warn("PauC LOD planner {} error (continuing).", name, throwable);
					sleep(1000);
				}
			}
		}, name);
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		thread.start();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean unready() {
		return level == null || gen == null || !gen.isReady() || distantRadius <= 0;
	}

	// ---------------------------------------------------------------- coverage (blitz + upgrade)
	private static void coverageLoop() {
		int myEpoch = epoch;
		int originX = playerChunkX;
		int originZ = playerChunkZ;
		int cursorX = originX;
		int cursorZ = originZ;
		int leg = 0;
		int stepInLeg = 0;
		int phase = 0; // 0 = blitz(16), 1 = upgrade(4)
		int scannedSinceBreath = 0;
		while (true) {
			if (unready() || myEpoch != epoch) {
				coverageDone = false;
				sleep(150);
				myEpoch = epoch;
				originX = playerChunkX;
				originZ = playerChunkZ;
				cursorX = originX;
				cursorZ = originZ;
				leg = 0;
				stepInLeg = 0;
				phase = 0;
				continue;
			}
			// Wait for the persisted terrain to finish loading AND seed the known-set before generating
			// anything: otherwise a coarse blitz fills a chunk before its real disk column drains, and the
			// session-wins merge then keeps the coarse fill = existing refined terrain destroyed on load.
			if (!PauCSurfaceSampler.diskLoadSettled()) {
				sleep(100);
				continue;
			}
			ClientLevel lvl = level;
			PauCDistantSurfaceGenerator generator = gen;
			int radius = distantRadius;
			boolean moved = Math.abs(originX - playerChunkX) > 2 || Math.abs(originZ - playerChunkZ) > 2;
			// Preempt the slow upgrade phase on movement (blitz keeps finishing: whole-map terrain first).
			if (moved && phase == 1) {
				originX = playerChunkX;
				originZ = playerChunkZ;
				cursorX = originX;
				cursorZ = originZ;
				leg = 0;
				stepInLeg = 0;
				phase = 0;
				coverageDone = false;
			}
			int maxLeg = radius * 4 + 8;
			if (leg >= maxLeg) {
				if (phase == 0) {
					phase = 1;
					cursorX = originX;
					cursorZ = originZ;
					leg = 0;
					stepInLeg = 0;
					continue;
				}
				coverageDone = true;
				sleep(200); // both phases complete: idle until the player moves
				continue;
			}
			long dx = cursorX - originX;
			long dz = cursorZ - originZ;
			long radiusSq = (long) (radius + 1) * (radius + 1);
			if (dx * dx + dz * dz <= radiusSq && lvl.getChunkSource().getChunk(cursorX, cursorZ, false) == null) {
				if (phase == 0) {
					if (!generator.isKnown(cursorX, cursorZ)) {
						while (!generator.submit(cursorX, cursorZ, 16)) {
							// Queue full: a few ms of MY time, not a lost client tick.
							sleep(6);
							if (myEpoch != epoch || !generator.isReady()) {
								break;
							}
						}
					}
				} else if (generator.recordedStep(cursorX, cursorZ) > 4) {
					while (!generator.submit(cursorX, cursorZ, 4)) {
						sleep(6);
						if (myEpoch != epoch || !generator.isReady()) {
							break;
						}
					}
				}
			}
			int legLen = leg / 2 + 1;
			switch (leg & 3) {
				case 0 -> cursorX++;
				case 1 -> cursorZ++;
				case 2 -> cursorX--;
				case 3 -> cursorZ--;
			}
			stepInLeg++;
			if (stepInLeg >= legLen) {
				stepInLeg = 0;
				leg++;
			}
			if (++scannedSinceBreath >= 4096) {
				scannedSinceBreath = 0;
				sleep(1); // stay polite with the chunk-cache reads
			}
		}
	}

	// ---------------------------------------------------------------- near-ring refinement (1/2)
	private static void refineLoop() {
		int myEpoch = epoch;
		int originX = playerChunkX;
		int originZ = playerChunkZ;
		int cursorX = originX;
		int cursorZ = originZ;
		int leg = 0;
		int stepInLeg = 0;
		int submittedThisSweep = 0;
		while (true) {
			if (unready() || myEpoch != epoch) {
				sleep(150);
				myEpoch = epoch;
				originX = playerChunkX;
				originZ = playerChunkZ;
				cursorX = originX;
				cursorZ = originZ;
				leg = 0;
				stepInLeg = 0;
				submittedThisSweep = 0;
				continue;
			}
			// Same guard as coverage: don't refine over terrain that hasn't loaded/seeded yet (see coverageLoop).
			if (!PauCSurfaceSampler.diskLoadSettled()) {
				sleep(100);
				continue;
			}
			ClientLevel lvl = level;
			PauCDistantSurfaceGenerator generator = gen;
			int minChunkDistance = vanillaChunks + 1;
			int radius = distantRadius;
			int refineRadius = minChunkDistance;
			while (refineRadius < radius
				&& PauCSurfaceWitnessRenderer.dataStepForRadial(refineRadius + 0.5F, minChunkDistance, radius) < 4) {
				refineRadius++;
			}
			boolean moved = Math.abs(originX - playerChunkX) > 2 || Math.abs(originZ - playerChunkZ) > 2;
			if (moved) {
				// Nearest-to-the-CURRENT-position always wins: restart mid-sweep, never finish a stale one.
				originX = playerChunkX;
				originZ = playerChunkZ;
				cursorX = originX;
				cursorZ = originZ;
				leg = 0;
				stepInLeg = 0;
				submittedThisSweep = 0;
			}
			int maxLeg = refineRadius * 4 + 4;
			if (leg >= maxLeg) {
				// Sweep complete: idle briefly; re-arm on movement or when new coarse data landed.
				sleep(submittedThisSweep > 0 ? 50 : 200);
				cursorX = originX;
				cursorZ = originZ;
				leg = 0;
				stepInLeg = 0;
				submittedThisSweep = 0;
				continue;
			}
			long dx = cursorX - originX;
			long dz = cursorZ - originZ;
			float radial = (float) Math.sqrt((double) dx * dx + (double) dz * dz);
			if (radial <= refineRadius
				&& lvl.getChunkSource().getChunk(cursorX, cursorZ, false) == null
				&& generator.isKnown(cursorX, cursorZ)) {
				int desired = PauCSurfaceWitnessRenderer.dataStepForRadial(radial, minChunkDistance, radius);
				if (desired < 4 && generator.recordedStep(cursorX, cursorZ) > desired) {
					while (!generator.submit(cursorX, cursorZ, desired)) {
						sleep(4); // fine queue full: wait right here — this cell is the nearest unserved one
						if (myEpoch != epoch || !generator.isReady()
							|| Math.abs(originX - playerChunkX) > 2 || Math.abs(originZ - playerChunkZ) > 2) {
							break;
						}
					}
					submittedThisSweep++;
				}
			}
			int legLen = leg / 2 + 1;
			switch (leg & 3) {
				case 0 -> cursorX++;
				case 1 -> cursorZ++;
				case 2 -> cursorX--;
				case 3 -> cursorZ--;
			}
			stepInLeg++;
			if (stepInLeg >= legLen) {
				stepInLeg = 0;
				leg++;
			}
		}
	}

	// ---------------------------------------------------------------- background hole repair
	/**
	 * Repairs BOTH kinds of holes, spiralling NEAR-FIRST over the whole covered disc:
	 * <ul>
	 * <li><b>Empty columns</b> inside a stored region (old persisted data, partial generation).</li>
	 * <li><b>Absent regions / never-submitted chunks</b> — proven 07-19 by parsing the persisted store
	 * against session screenshots: fast flight leaves REGION-SIZED voids behind (chunks were briefly
	 * LOADED when the coverage blitz passed — skipped as "the sampler's job" — then unloaded before the
	 * sampler's budget reached them). The old sweep iterated only STORED regions and skipped
	 * {@code !isKnown} chunks, so those voids were invisible to it and stayed as sky-coloured holes
	 * (the "white lakes" and crest slits). Now the sweep walks the expected disc: any unloaded chunk
	 * whose region is missing, or whose columns are empty, gets ONE submission per session.</li>
	 * </ul>
	 */
	private static void holeLoop() {
		LongOpenHashSet attempted = new LongOpenHashSet();
		int myEpoch = epoch;
		while (true) {
			if (unready()) {
				sleep(500);
				continue;
			}
			if (myEpoch != epoch) {
				myEpoch = epoch;
				attempted.clear();
			}
			PauCDistantSurfaceGenerator generator = gen;
			long[] keys = regionKeys;
			PauCSurfaceColumnStore.Region[] regions = regionSnapshot;
			Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> present = new Long2ObjectOpenHashMap<>();
			for (int i = 0; i < keys.length && i < regions.length; i++) {
				if (regions[i] != null) {
					present.put(keys[i], regions[i]);
				}
			}
			int radius = distantRadius;
			int pcx = playerChunkX;
			int pcz = playerChunkZ;
			int repaired = 0;
			int cursorX = pcx;
			int cursorZ = pcz;
			int leg = 0;
			int stepInLeg = 0;
			int maxLeg = radius * 4 + 8;
			int scannedSinceBreath = 0;
			boolean playerMoved = false;
			while (leg < maxLeg && myEpoch == epoch) {
				// Flight tracking: a stale near-first sweep repairs LAST what is now nearest — restart
				// from the current position instead (the attempted-set keeps restarts cheap).
				if (Math.abs(pcx - playerChunkX) > 8 || Math.abs(pcz - playerChunkZ) > 8) {
					playerMoved = true;
					break;
				}
				long dx = cursorX - pcx;
				long dz = cursorZ - pcz;
				if (dx * dx + dz * dz <= (long) radius * radius
						&& repairChunk(generator, present, attempted, cursorX, cursorZ)) {
					repaired++;
				}
				int legLen = leg / 2 + 1;
				switch (leg & 3) {
					case 0 -> cursorX++;
					case 1 -> cursorZ++;
					case 2 -> cursorX--;
					case 3 -> cursorZ--;
				}
				stepInLeg++;
				if (stepInLeg >= legLen) {
					stepInLeg = 0;
					leg++;
				}
				if (++scannedSinceBreath >= 512) {
					scannedSinceBreath = 0;
					sleep(1); // polite pace: full 106-chunk disc in well under a minute, near-zero CPU
				}
			}
			if (repaired > 0) {
				LOGGER.info("PauC LOD hole repair: {} chunk(s) (empty columns or absent regions) resubmitted.", repaired);
			}
			sleep(playerMoved ? 250 : 5000);
		}
	}

	/** One chunk of the repair sweep. TRUE when a generation job was actually submitted. */
	private static boolean repairChunk(PauCDistantSurfaceGenerator generator,
			Long2ObjectOpenHashMap<PauCSurfaceColumnStore.Region> present, LongOpenHashSet attempted,
			int chunkX, int chunkZ) {
		long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
		if (attempted.contains(chunkKey)) {
			return false;
		}
		ClientLevel lvl = level;
		if (lvl == null || lvl.getChunkSource().getChunk(chunkX, chunkZ, false) != null) {
			return false; // loaded: the client sampler owns it; retried here once it unloads
		}
		PauCSurfaceColumnStore.Region region = present.get(PauCSurfaceColumnStore.regionKey(chunkX << 4, chunkZ << 4));
		boolean hole;
		if (region == null) {
			// ABSENT region — but ONLY a hole once the initial disk load has fully streamed in. During
			// the load window, absence means "file not drained yet": treating it as a hole submitted
			// 5525 ghost regenerations (07-19) whose coarse fills then WON the session-wins merge over
			// the real disk data — the exact opposite of a repair. Not added to `attempted`: retried
			// naturally on a later sweep once the load settles.
			if (!PauCSurfaceSampler.diskLoadSettled()) {
				return false;
			}
			hole = true; // whole region absent from the store
		} else {
			hole = false;
			int lcx = chunkX & 3;
			int lcz = chunkZ & 3;
			for (int bz = 0; bz < 16 && !hole; bz++) {
				int base = ((((lcz << 4) + bz) << PauCSurfaceColumnStore.REGION_SHIFT) | (lcx << 4))
					* PauCSurfaceColumnStore.MAX_SPANS;
				for (int bx = 0; bx < 16; bx++) {
					if (region.spanY[base + bx * PauCSurfaceColumnStore.MAX_SPANS] == Short.MIN_VALUE) {
						hole = true;
						break;
					}
				}
			}
		}
		if (!hole) {
			return false;
		}
		attempted.add(chunkKey);
		// ABSENT region: submit at BLITZ grain (16 → one noise column, ~1.3ms) so the sky closes within
		// seconds — a smooth coarse sheet beats a hole (triangle-base law), and the normal 4→2→1 upgrade
		// ladder sharpens it afterwards. A PARTIALLY-holed chunk keeps its recorded grain: a 16-fill
		// would REPLACE its existing fine columns with bilinear coarse (visible detail regression).
		int step = region == null ? 16
			: generator.isKnown(chunkX, chunkZ)
				? Math.max(2, Math.min(4, generator.recordedStep(chunkX, chunkZ)))
				: 4;
		// BLITZ-STYLE TIGHT WAIT (session 07-19 log: ~400 repairs/sweep re-rejected for ~50s): the
		// coverage loop keeps the queue pinned at its cap and WAITS in a 6ms loop for a slot, so a
		// repair that just "retries next sweep" (5s) starves behind virgin-frontier coverage — visible
		// sky holes outlive the whole backlog. Wait for a slot on equal terms; bail (and retry next
		// sweep) on epoch change, generator loss, or real player movement.
		int myEpoch = epoch;
		int originX = playerChunkX;
		int originZ = playerChunkZ;
		while (!generator.submit(chunkX, chunkZ, step)) {
			sleep(6);
			if (myEpoch != epoch || !generator.isReady()
					|| Math.abs(originX - playerChunkX) > 2 || Math.abs(originZ - playerChunkZ) > 2) {
				attempted.remove(chunkKey);
				return false;
			}
		}
		return true;
	}
}
