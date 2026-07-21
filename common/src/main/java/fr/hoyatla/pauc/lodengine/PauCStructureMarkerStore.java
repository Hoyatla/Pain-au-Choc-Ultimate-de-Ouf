package fr.hoyatla.pauc.lodengine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared store of DISTANT STRUCTURE markers (LOD engine 0.6.1). In singleplayer the integrated server
 * knows every structure's placement deterministically from the seed — without generating the chunk. The
 * {@code PauCDistantStructureLocator} (server thread) fills this store with an archetype + position for
 * each located structure in the LOD ring; the client {@link PauCStructureLodRenderer} draws a blocky
 * archetype at each. This is how a village/temple/monument shows from far at its real spot BEFORE you
 * ever visit it — impossible from chunk data alone, since the client never loaded that chunk.
 *
 * <p>One writer (server thread) + one reader (render thread), both in the same JVM (singleplayer). The
 * map reference is swapped atomically; readers take a snapshot list.</p>
 */
public final class PauCStructureMarkerStore {
	// Archetype ids — the renderer maps each to a small blocky silhouette. -1 = do not render (hidden /
	// underground / unsupported dimension), used by the locator's classifier.
	public static final int ARCH_GENERIC = 0;
	public static final int ARCH_VILLAGE = 1;
	public static final int ARCH_DESERT_PYRAMID = 2;
	public static final int ARCH_JUNGLE_TEMPLE = 3;
	public static final int ARCH_OUTPOST = 4;
	public static final int ARCH_MONUMENT = 5;
	public static final int ARCH_MANSION = 6;
	public static final int ARCH_WITCH_HUT = 7;
	public static final int ARCH_IGLOO = 8;
	public static final int ARCH_RUINED_PORTAL = 9;
	public static final int ARCH_SHIPWRECK = 10;
	public static final int ARCH_OCEAN_RUIN = 11;
	public static final int ARCH_NETHER_FORTRESS = 12;
	public static final int ARCH_BASTION = 13;
	public static final int ARCH_TRAIL_RUINS = 14;

	public static final class Marker {
		public final int worldX;
		public final int worldZ;
		public final int groundY;
		public final int archetype;

		public Marker(int worldX, int worldZ, int groundY, int archetype) {
			this.worldX = worldX;
			this.worldZ = worldZ;
			this.groundY = groundY;
			this.archetype = archetype;
		}
	}

	private static final Object LOCK = new Object();
	private static final Long2ObjectOpenHashMap<Marker> MARKERS = new Long2ObjectOpenHashMap<>();
	private static volatile String dimension = "";
	private static volatile List<Marker> snapshot = List.of();
	private static volatile long revision;

	private PauCStructureMarkerStore() {
	}

	/** Server thread: records one located structure (idempotent per chunk). Rebuilds the read snapshot. */
	public static void put(String dim, long chunkKey, Marker marker) {
		synchronized (LOCK) {
			if (!dimension.equals(dim)) {
				dimension = dim;
				MARKERS.clear();
			}
			if (MARKERS.putIfAbsent(chunkKey, marker) == null) {
				snapshot = new ArrayList<>(MARKERS.values());
				revision++;
			}
		}
	}

	public static boolean hasChunk(String dim, long chunkKey) {
		synchronized (LOCK) {
			return dimension.equals(dim) && MARKERS.containsKey(chunkKey);
		}
	}

	/** Render thread: immutable snapshot for the current dimension ({@code List.of()} if none). */
	public static List<Marker> markers(String dim) {
		return dimension.equals(dim) ? snapshot : List.of();
	}

	public static long revision() {
		return revision;
	}

	public static void clear() {
		synchronized (LOCK) {
			MARKERS.clear();
			dimension = "";
			snapshot = List.of();
			revision++;
		}
	}
}
