package fr.hoyatla.pauc.platform.forge.worldgen;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FarChunkPreparationRegistry {
	private static final CopyOnWriteArrayList<FarChunkPreparationPlanner> PLANNERS = new CopyOnWriteArrayList<>();

	private FarChunkPreparationRegistry() {
	}

	public static void register(FarChunkPreparationPlanner planner) {
		PLANNERS.addIfAbsent(planner);
	}

	public static void unregister(FarChunkPreparationPlanner planner) {
		PLANNERS.remove(planner);
	}

	public static List<FarChunkPreparationPlanner> planners() {
		return List.copyOf(PLANNERS);
	}
}
