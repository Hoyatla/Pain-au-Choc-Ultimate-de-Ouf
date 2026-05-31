package fr.hoyatla.pauc.platform.forge.worldgen;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;

public interface FarChunkPreparationPlanner {
	ResourceLocation id();

	default int preparationRadiusChunks() {
		return 0;
	}

	default long preparationKey(FarChunkPreparationContext context) {
		return context.windowAnchorKey();
	}

	Collection<PendingChunkPlacement> prepare(FarChunkPreparationContext context);
}
