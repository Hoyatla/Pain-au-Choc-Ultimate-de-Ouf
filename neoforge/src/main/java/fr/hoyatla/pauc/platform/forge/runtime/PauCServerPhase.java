package fr.hoyatla.pauc.platform.forge.runtime;

public enum PauCServerPhase {
	FAR_QUERY("far-query"),
	PATHFINDING("pathfinding"),
	STRUCTURE_CHECK("structure-check"),
	FLUID("fluid"),
	CHUNK_POST_LOAD("chunk-post-load"),
	NEIGHBOR_CASCADE("neighbor-cascade"),
	WORLDGEN_APPLY("worldgen-apply"),
	WORLDGEN_FORCE_LOAD("worldgen-force-load"),
	SAVE_FLUSH("save-flush");

	private final String id;

	PauCServerPhase(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}
}
