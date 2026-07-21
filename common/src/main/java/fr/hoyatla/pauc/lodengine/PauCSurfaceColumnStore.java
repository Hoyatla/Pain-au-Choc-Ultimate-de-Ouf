package fr.hoyatla.pauc.lodengine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;

/**
 * PauC's own LOD surface store (LOD engine phase 1).
 *
 * <p>Data model follows the project rule "charge only the surfaces": per block column we keep up to
 * {@link #MAX_SPANS} top-down surface spans (world Y of the span top, packed ARGB color, sky/block
 * light). Two-three spans cover overhangs and floating islands without a full voxel model — roughly
 * 10x lighter than a DH-style voxel store and a direct fit for the CUDA mesher (phase 3) and the
 * nvidia-mesh renderer path (phase 4).</p>
 *
 * <p>Storage is region-bucketed (64x64 columns) for cheap streaming and later disk persistence
 * (phase 2). All access is main-thread for now; the mesher phase will snapshot regions.</p>
 */
public final class PauCSurfaceColumnStore {
	public static final int MAX_SPANS = 3;
	/**
	 * Material tags carried in the colour ALPHA channel (normal surfaces are 0xFF). Free — no store
	 * format change, persists as-is.
	 * <ul>
	 * <li>{@code 0xFE} = tree canopy column (leaves from the sampler, canopy bumps from the distant
	 * generator) — renderer draws the two-tone "mushroom" tree walls.</li>
	 * <li>{@code 0xFD} = water surface — renderer darkens the walls/skirts toward a depth tone (vanilla
	 * water is translucent, so bright skirts behind it read as a glowing ice shelf).</li>
	 * <li>{@code 0xFC} = grass-topped soil column — renderer draws the walls in DIRT colour (a grass
	 * block is green on top and dirt on the sides; green cliffs read as plastic).</li>
	 * </ul>
	 */
	public static final int TREE_ALPHA = 0xFE;
	public static final int WATER_ALPHA = 0xFD;
	public static final int SOIL_ALPHA = 0xFC;
	/**
	 * Imposter sub-kinds (0.6.1). LAND vegetation kinds live in span-0's alpha (the surface block IS the
	 * plant): {@link #TREE_ALPHA} = broadleaf (oak/birch/dark-oak/mangrove/cherry — the default), plus the
	 * distinct silhouettes below. UNDERWATER kinds live in span-1's alpha (the seabed floor slot, since
	 * span-0 is the water surface): {@link #CORAL_ALPHA}, {@link #KELP_ALPHA}. The witness renderer treats
	 * every LAND kind as a "tree" tile (ground-only under imposters); PauCTreeImposterRenderer picks the
	 * billboard silhouette from the tag.
	 */
	public static final int CONIFER_ALPHA = 0xFA; // spruce / pine — tall narrow cone
	public static final int JUNGLE_ALPHA = 0xF9;  // jungle — tall, canopy high on a long trunk
	public static final int SAVANNA_ALPHA = 0xF8; // acacia — flat umbrella on a bare trunk
	public static final int BAMBOO_ALPHA = 0xF7;  // thin vertical stalks, no canopy
	public static final int CORAL_ALPHA = 0xF6;   // span-1 tag: reef on the seabed (colourful)
	public static final int KELP_ALPHA = 0xF5;
	public static final int MANGROVE_ALPHA = 0xF4; // broad canopy on stilt roots    // span-1 tag: kelp / seagrass strands (green)
	public static final int CHERRY_ALPHA = 0xF3;   // cherry grove — PINK blossom canopy (must escape the green cap)
	public static final int BIRCH_ALPHA = 0xF2;    // birch — slim canopy, pale bark
	public static final int DARK_OAK_ALPHA = 0xF1; // dark oak / roofed forest — wide, dark canopy

	/** True for every LAND vegetation kind carried in a column's span-0 alpha (all get a tree imposter). */
	public static boolean isTreeAlpha(int alpha) {
		return alpha == TREE_ALPHA || alpha == CONIFER_ALPHA || alpha == JUNGLE_ALPHA
			|| alpha == SAVANNA_ALPHA || alpha == BAMBOO_ALPHA || alpha == MANGROVE_ALPHA
			|| alpha == CHERRY_ALPHA || alpha == BIRCH_ALPHA || alpha == DARK_OAK_ALPHA;
	}
	/**
	 * {@code 0xFB} = FLOATING structure column (player build, bridge, platform, or a natural overhang):
	 * the surface mass has open AIR below it. span 0 = structure top, span 1 = structure bottom, span 2 =
	 * the real ground below. The renderer draws a floating slab OVER the ground — never a solid column
	 * down to the ground (the heightfield's default, which walled off the air under every sky build).
	 */
	public static final int FLOATING_ALPHA = 0xFB;
	public static final int REGION_SHIFT = 6; // 64x64 columns per region
	public static final int REGION_SIZE = 1 << REGION_SHIFT;
	private static final int COLUMNS_PER_REGION = REGION_SIZE * REGION_SIZE;

	private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
	private long revision;

	/** One 64x64-column bucket. Span slot layout per column: [y|light] short-packed + ARGB color. */
	public static final class Region {
		/**
		 * Top Y of each span, {@link Short#MIN_VALUE} = empty slot. Index = (columnIndex * MAX_SPANS) + span.
		 * Public read access: the shadow renderer streams the heightfield window from these arrays.
		 */
		public final short[] spanY = new short[COLUMNS_PER_REGION * MAX_SPANS];
		/** Packed ARGB color of the span surface. */
		final int[] spanColor = new int[COLUMNS_PER_REGION * MAX_SPANS];
		/** Packed light: (skyLight << 4) | blockLight. */
		final byte[] spanLight = new byte[COLUMNS_PER_REGION * MAX_SPANS];
		long revision;

		Region() {
			java.util.Arrays.fill(spanY, Short.MIN_VALUE);
		}
	}

	public static long regionKey(int columnX, int columnZ) {
		return ((long) (columnX >> REGION_SHIFT) << 32) | ((columnZ >> REGION_SHIFT) & 0xffffffffL);
	}

	private static int columnIndex(int columnX, int columnZ) {
		return ((columnZ & (REGION_SIZE - 1)) << REGION_SHIFT) | (columnX & (REGION_SIZE - 1));
	}

	/** Writes the spans of one column (top-down order); unused slots must be passed as {@link Short#MIN_VALUE}. */
	public void putColumn(int columnX, int columnZ, short[] ys, int[] colors, byte[] lights) {
		Region region = regions.computeIfAbsent(regionKey(columnX, columnZ), key -> new Region());
		int base = columnIndex(columnX, columnZ) * MAX_SPANS;
		for (int span = 0; span < MAX_SPANS; span++) {
			region.spanY[base + span] = ys[span];
			region.spanColor[base + span] = colors[span];
			region.spanLight[base + span] = lights[span];
		}
		revision++;
		region.revision = revision;
	}

	/** @return the top surface Y of the column, or {@link Short#MIN_VALUE} when unsampled. */
	public short topY(int columnX, int columnZ) {
		Region region = regions.get(regionKey(columnX, columnZ));
		if (region == null) {
			return Short.MIN_VALUE;
		}
		return region.spanY[columnIndex(columnX, columnZ) * MAX_SPANS];
	}

	public boolean isSampled(int columnX, int columnZ) {
		return topY(columnX, columnZ) != Short.MIN_VALUE;
	}

	public Region region(long regionKey) {
		return regions.get(regionKey);
	}

	/** Snapshot of the current region keys (main-thread callers: sampler and witness renderer). */
	public long[] regionKeys() {
		return regions.keySet().toLongArray();
	}

	public static int regionXFromKey(long regionKey) {
		return (int) (regionKey >> 32);
	}

	public static int regionZFromKey(long regionKey) {
		return (int) regionKey;
	}

	public int regionCount() {
		return regions.size();
	}

	public long revision() {
		return revision;
	}

	public void clear() {
		regions.clear();
		revision++;
	}

	/**
	 * Evicts every region whose centre is farther than {@code keepRadiusChunks} from the player — they
	 * are beyond the LOD horizon and already persisted by the periodic flush, so they reload from disk
	 * on return. The store otherwise grows UNBOUNDED as you explore (no eviction existed), exhausting
	 * the heap (100% at 8 GB with DH, proven 07-20) → GC thrash → the mesh-pipeline wedge. Main-thread
	 * only (like every other store mutation); snapshot readers (planner/witness) are unaffected. Returns
	 * the number of regions evicted.
	 */
	public int evictBeyond(int playerChunkX, int playerChunkZ, int keepRadiusChunks) {
		int half = (REGION_SIZE >> 4) >> 1; // half a region edge, in chunks
		long keepSq = (long) keepRadiusChunks * keepRadiusChunks;
		it.unimi.dsi.fastutil.longs.LongArrayList toRemove = new it.unimi.dsi.fastutil.longs.LongArrayList();
		for (long key : regions.keySet()) {
			int centreChunkX = (regionXFromKey(key) << (REGION_SHIFT - 4)) + half;
			int centreChunkZ = (regionZFromKey(key) << (REGION_SHIFT - 4)) + half;
			long dx = centreChunkX - playerChunkX;
			long dz = centreChunkZ - playerChunkZ;
			if (dx * dx + dz * dz > keepSq) {
				toRemove.add(key);
			}
		}
		for (int i = 0; i < toRemove.size(); i++) {
			regions.remove(toRemove.getLong(i));
		}
		if (!toRemove.isEmpty()) {
			revision++;
		}
		return toRemove.size();
	}

	public long regionRevision(long regionKey) {
		Region region = regions.get(regionKey);
		return region == null ? -1L : region.revision;
	}

	// ---- Persistence (phase 2). Raw byte layout: magic + spanY[] + spanColor[] + spanLight[]. ----
	private static final int PERSIST_MAGIC = 0x504C_5231; // 'PLR1'
	private static final int SLOTS = COLUMNS_PER_REGION * MAX_SPANS;
	public static final int PERSIST_BYTES = 4 + SLOTS * 7;

	/** Snapshots one region into raw bytes (main thread); {@code null} when the region is absent. */
	public byte[] encodeRegion(long regionKey) {
		Region region = regions.get(regionKey);
		if (region == null) {
			return null;
		}
		ByteBuffer buffer = ByteBuffer.allocate(PERSIST_BYTES);
		buffer.putInt(PERSIST_MAGIC);
		for (int i = 0; i < SLOTS; i++) {
			buffer.putShort(region.spanY[i]);
		}
		for (int i = 0; i < SLOTS; i++) {
			buffer.putInt(region.spanColor[i]);
		}
		for (int i = 0; i < SLOTS; i++) {
			buffer.put(region.spanLight[i]);
		}
		return buffer.array();
	}

	/** Decodes raw bytes into a region and inserts it (main thread). @return new region revision, or -1 on bad data. */
	/**
	 * Disk restore that never clobbers session data: if the region already exists in memory, only
	 * columns still EMPTY are filled from the disk snapshot (fresh sampler/generator writes win,
	 * untouched columns get restored). A whole-region skip lost every unvisited column of partially
	 * written regions — the "empty map at launch" breakage.
	 */
	public long loadRegionRawMerge(long regionKey, byte[] raw) {
		Region existing = regions.get(regionKey);
		if (existing == null) {
			return loadRegionRaw(regionKey, raw);
		}
		if (raw == null || raw.length < PERSIST_BYTES) {
			return -1L;
		}
		ByteBuffer buffer = ByteBuffer.wrap(raw);
		if (buffer.getInt() != PERSIST_MAGIC) {
			return -1L;
		}
		short[] ys = new short[SLOTS];
		int[] colors = new int[SLOTS];
		byte[] lights = new byte[SLOTS];
		for (int i = 0; i < SLOTS; i++) {
			ys[i] = buffer.getShort();
		}
		for (int i = 0; i < SLOTS; i++) {
			colors[i] = buffer.getInt();
		}
		for (int i = 0; i < SLOTS; i++) {
			lights[i] = buffer.get();
		}
		despikeLegacyColumns(ys, colors);
		for (int column = 0; column < SLOTS / MAX_SPANS; column++) {
			int base = column * MAX_SPANS;
			if (existing.spanY[base] != Short.MIN_VALUE) {
				continue; // session already wrote this column — it wins
			}
			for (int span = 0; span < MAX_SPANS; span++) {
				existing.spanY[base + span] = ys[base + span];
				existing.spanColor[base + span] = colors[base + span];
				existing.spanLight[base + span] = lights[base + span];
			}
		}
		revision++;
		existing.revision = revision;
		return existing.revision;
	}

	/**
	 * Load-time sanitizer for OLD persisted data: a NORMAL (untagged) column whose top towers over the
	 * median of its 8 neighbours is a legacy VINE column (pre-fix sampling landed on hanging vines) —
	 * the triangle mesh renders each as a tall green blade. Clamp it to the neighbourhood median.
	 * Tagged columns (trees/water/floating) are untouched; region border columns are skipped.
	 */
	private static void despikeLegacyColumns(short[] ys, int[] colors) {
		short[] n = new short[8];
		for (int z = 0; z < REGION_SIZE; z++) {
			for (int x = 0; x < REGION_SIZE; x++) {
				int base = ((z << REGION_SHIFT) | x) * MAX_SPANS;
				short y = ys[base];
				if (y == Short.MIN_VALUE || (colors[base] >>> 24) != 0xff) {
					continue;
				}
				int count = 0;
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dz == 0) {
							continue;
						}
						int nx = x + dx;
						int nz = z + dz;
						if (nx < 0 || nz < 0 || nx >= REGION_SIZE || nz >= REGION_SIZE) {
							continue;
						}
						short ny = ys[((nz << REGION_SHIFT) | nx) * MAX_SPANS];
						if (ny != Short.MIN_VALUE) {
							n[count++] = ny;
						}
					}
				}
				if (count < 4) {
					continue;
				}
				java.util.Arrays.sort(n, 0, count);
				short median = n[count / 2];
				if (y > median + 8) {
					ys[base] = median;
				}
			}
		}
	}

	public long loadRegionRaw(long regionKey, byte[] raw) {
		if (raw == null || raw.length < PERSIST_BYTES) {
			return -1L;
		}
		ByteBuffer buffer = ByteBuffer.wrap(raw);
		if (buffer.getInt() != PERSIST_MAGIC) {
			return -1L;
		}
		Region region = new Region();
		for (int i = 0; i < SLOTS; i++) {
			region.spanY[i] = buffer.getShort();
		}
		for (int i = 0; i < SLOTS; i++) {
			region.spanColor[i] = buffer.getInt();
		}
		for (int i = 0; i < SLOTS; i++) {
			region.spanLight[i] = buffer.get();
		}
		despikeLegacyColumns(region.spanY, region.spanColor);
		revision++;
		region.revision = revision;
		regions.put(regionKey, region);
		return region.revision;
	}
}
