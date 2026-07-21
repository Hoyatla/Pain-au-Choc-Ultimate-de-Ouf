package fr.hoyatla.pauc.lodengine;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * WORLD-ALIGNED LOD SECTION GRID — the DH-style foundation that ends the "reload the whole map on
 * every move" churn and is the shared basis for future PauC↔DH synchronisation.
 *
 * <p>The legacy renderer bakes LOD bands as rings CENTRED ON THE PLAYER (per-tile distance to the
 * camera), so any camera move shifts every band boundary and forces a full re-mesh. Distant Horizons
 * instead assigns each FIXED WORLD SECTION a quantised {@code detailLevel = f(distance)}: moving the
 * player changes only the LEVEL of fixed sections, and DH re-meshes ONLY the sections whose level
 * actually changed. This class is that brain for PauC.</p>
 *
 * <p>A SECTION is one surface-store region ({@link PauCSurfaceColumnStore#REGION_SIZE} = 64 blocks =
 * 4×4 chunks), already world-aligned. Its {@code detailLevel} is the chebyshev chunk distance from
 * the player to the section, floored into band-width steps: a section keeps its level until the player
 * crosses a whole band width relative to it, so a small move re-levels only the thin ring of sections
 * sitting on a band boundary — never the whole map.</p>
 *
 * <p>Pure logic, no rendering or GL — deliberately unit-testable and reusable. Level 0 = finest (near),
 * increasing outward; a section beyond the LOD radius reports {@link #LEVEL_OUT_OF_RANGE}. The level
 * step aligns with the renderer's band width so the section grid and the existing 1/2/4/8 tile bands
 * stay coherent while the renderer migrates onto the grid.</p>
 */
public final class PauCLodSectionGrid {
	/** A section farther than the LOD radius: not drawn, no mesh. */
	public static final byte LEVEL_OUT_OF_RANGE = (byte) 0xFF;
	/** No mesh built yet for a section (sentinel in the built-level map). */
	private static final byte LEVEL_NONE = (byte) 0xFE;

	private static final int REGION_CHUNK_SPAN = PauCSurfaceColumnStore.REGION_SIZE >> 4; // 4 chunks per region edge

	/** detailLevel the renderer last MESHED each section at (regionKey → level). */
	private final Long2ByteOpenHashMap builtLevel = new Long2ByteOpenHashMap();

	public PauCLodSectionGrid() {
		builtLevel.defaultReturnValue(LEVEL_NONE);
	}

	/** Chunk X of a section's near-centre, from its region key. */
	public static int sectionCentreChunkX(long regionKey) {
		return (PauCSurfaceColumnStore.regionXFromKey(regionKey) << (PauCSurfaceColumnStore.REGION_SHIFT - 4)) + (REGION_CHUNK_SPAN >> 1);
	}

	/** Chunk Z of a section's near-centre, from its region key. */
	public static int sectionCentreChunkZ(long regionKey) {
		return (PauCSurfaceColumnStore.regionZFromKey(regionKey) << (PauCSurfaceColumnStore.REGION_SHIFT - 4)) + (REGION_CHUNK_SPAN >> 1);
	}

	/**
	 * Quantised detail level of a section for the given player chunk and LOD geometry.
	 *
	 * @param minChunkDistance vanilla square edge in chunks (inside = level 0, never coarsened)
	 * @param maxChunkDistance LOD horizon in chunks (beyond = {@link #LEVEL_OUT_OF_RANGE})
	 * @param bandWidthChunks  width of one detail step in chunks (the renderer's band width)
	 */
	public static byte detailLevel(long regionKey, int playerChunkX, int playerChunkZ,
			int minChunkDistance, int maxChunkDistance, int bandWidthChunks) {
		int dcx = sectionCentreChunkX(regionKey) - playerChunkX;
		int dcz = sectionCentreChunkZ(regionKey) - playerChunkZ;
		int cheb = Math.max(Math.abs(dcx), Math.abs(dcz));
		if (cheb > maxChunkDistance + REGION_CHUNK_SPAN) {
			return LEVEL_OUT_OF_RANGE;
		}
		if (cheb <= minChunkDistance) {
			return 0; // inside / adjacent to the vanilla square: finest
		}
		int step = Math.max(1, bandWidthChunks);
		int level = 1 + (cheb - minChunkDistance) / step;
		return (byte) Math.min(120, level); // clamp well under the sentinels
	}

	/**
	 * The sections whose detail level CHANGED since they were last meshed — the incremental re-mesh set
	 * for a move. Sections at an unchanged level are skipped (no churn). Does NOT mark them built; the
	 * caller calls {@link #markBuilt} once a section's mesh at the new level is uploaded.
	 *
	 * @param inRangeRegionKeys every region currently within (or near) the LOD radius
	 */
	public LongArrayList sectionsNeedingRebuild(long[] inRangeRegionKeys, int playerChunkX, int playerChunkZ,
			int minChunkDistance, int maxChunkDistance, int bandWidthChunks) {
		LongArrayList out = new LongArrayList();
		for (long key : inRangeRegionKeys) {
			byte want = detailLevel(key, playerChunkX, playerChunkZ, minChunkDistance, maxChunkDistance, bandWidthChunks);
			if (want == LEVEL_OUT_OF_RANGE) {
				continue; // out of range: the renderer drops its mesh separately
			}
			if (builtLevel.get(key) != want) {
				out.add(key);
			}
		}
		return out;
	}

	/** Records that {@code regionKey} now has a mesh built at {@code level}. */
	public void markBuilt(long regionKey, byte level) {
		builtLevel.put(regionKey, level);
	}

	/** The level a section's current mesh was built at, or a NONE sentinel if never built. */
	public byte builtLevel(long regionKey) {
		return builtLevel.get(regionKey);
	}

	public boolean hasMesh(long regionKey) {
		return builtLevel.get(regionKey) != LEVEL_NONE;
	}

	/** Forgets a section (its mesh was dropped — out of range or world change). */
	public void forget(long regionKey) {
		builtLevel.remove(regionKey);
	}

	/** Wipes all tracked levels (dimension / world change). */
	public void clear() {
		builtLevel.clear();
	}

	public int trackedSections() {
		return builtLevel.size();
	}
}
