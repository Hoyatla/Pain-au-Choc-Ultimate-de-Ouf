package fr.hoyatla.pauc.platform.forge.worldgen;

import javax.annotation.Nullable;
import java.util.Collection;

public interface AsyncFarChunkPreparationPlanner<S> extends FarChunkPreparationPlanner {
	@Nullable
	S capturePreparationSnapshot(FarChunkPreparationContext context);

	Collection<PendingChunkPlacement> prepareAsync(S snapshot);

	@Override
	default Collection<PendingChunkPlacement> prepare(FarChunkPreparationContext context) {
		S snapshot = capturePreparationSnapshot(context);
		return snapshot != null ? prepareAsync(snapshot) : java.util.List.of();
	}
}
