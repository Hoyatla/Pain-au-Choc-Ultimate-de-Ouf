package fr.hoyatla.pauc.lodstore;

import java.util.Collection;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class PauCLodHotRestore {
	private PauCLodHotRestore() {
	}

	public static Result restoreBatch(
		PauCLodMeshStore store,
		Collection<PauCLodMeshKey> keys,
		int maxCells,
		long deadlineNanos,
		BiConsumer<PauCLodMeshKey, byte[]> restoredMeshConsumer
	) {
		if (store == null || keys == null || keys.isEmpty() || maxCells <= 0 || restoredMeshConsumer == null) {
			return Result.empty();
		}
		int scanned = 0;
		int restored = 0;
		for (PauCLodMeshKey key : keys) {
			if (scanned >= maxCells || System.nanoTime() >= deadlineNanos) {
				break;
			}
			scanned++;
			Optional<byte[]> mesh = store.read(key);
			if (mesh.isEmpty()) {
				continue;
			}
			restoredMeshConsumer.accept(key, mesh.get());
			restored++;
		}
		return new Result(scanned, restored);
	}

	public record Result(int scanned, int restored) {
		private static Result empty() {
			return new Result(0, 0);
		}
	}
}
