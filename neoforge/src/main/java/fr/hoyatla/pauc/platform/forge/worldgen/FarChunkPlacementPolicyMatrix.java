package fr.hoyatla.pauc.platform.forge.worldgen;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FarChunkPlacementPolicyMatrix {
	private static final Map<String, FarChunkPlacementMode> PACKAGE_OVERRIDES = new ConcurrentHashMap<>();

	private FarChunkPlacementPolicyMatrix() {
	}

	public static void registerPackageOverride(String packagePrefix, FarChunkPlacementMode mode) {
		PACKAGE_OVERRIDES.put(packagePrefix, mode);
	}

	public static FarChunkPlacementMode resolve(FarChunkPlacementSource source, BlockState state, int flags) {
		FarChunkPlacementMode override = findOverride(source);
		if (override != null) {
			return override;
		}

		if (source.isMCreatorGeneratedMod()) {
			return FarChunkPlacementMode.DEFER_AND_SUCCEED;
		}

		if (source.isVanillaOrForge()) {
			return FarChunkPlacementMode.DEFER;
		}

		if (state.hasBlockEntity() || flags != 3) {
			return FarChunkPlacementMode.DEFER;
		}

		if (source.isUnknown()) {
			return FarChunkPlacementMode.DEFER_AND_SUCCEED;
		}

		return FarChunkPlacementMode.DEFER_AND_SUCCEED;
	}

	private static FarChunkPlacementMode findOverride(FarChunkPlacementSource source) {
		FarChunkPlacementMode bestMatch = null;
		int longestPrefix = -1;

		for (Map.Entry<String, FarChunkPlacementMode> entry : PACKAGE_OVERRIDES.entrySet()) {
			String prefix = entry.getKey();
			if (!source.matchesPrefix(prefix) || prefix.length() <= longestPrefix) {
				continue;
			}

			bestMatch = entry.getValue();
			longestPrefix = prefix.length();
		}

		return bestMatch;
	}
}
