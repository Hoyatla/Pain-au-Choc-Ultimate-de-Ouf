package fr.hoyatla.pauc.platform.forge.runtime;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import org.slf4j.Logger;

/**
 * Spreads and stretches dropped-item merge scans under server tick debt.
 *
 * <p>Vanilla calls {@code ItemEntity#mergeWithNeighbours} every 40 ticks per item; a mob-death burst
 * spawns its whole pile on the same tick, so every item in the pile re-scans its neighbourhood on the
 * same tick forever (O(pile-size squared) AABB scans, all in one server tick). Loot-heavy packs
 * (zombie apocalypse hordes) multiply this. This throttler (a) phase-offsets scans by entity id so a
 * pile spreads its scans across the 40-tick window instead of stacking them, and (b) under measured
 * tick debt skips a growing fraction of scans (up to 3 of 4 at max pressure). Items still merge —
 * scans just happen a little later; gameplay and visuals are untouched.</p>
 */
public final class PauCItemEntityThrottler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_KEY = "itemMergeThrottle.enabled";
	private static final String MAX_STRETCH_KEY = "itemMergeThrottle.maxStretch";
	private static volatile boolean engagedLogged;

	private PauCItemEntityThrottler() {
	}

	/** @return {@code true} if this scheduled merge scan should be skipped this round. */
	public static boolean shouldDeferMergeScan(ItemEntity item) {
		if (item == null || item.level() == null || item.level().isClientSide()) {
			return false;
		}
		if (!PauCServerOptimizationProfile.enabled(ENABLED_KEY, PauCServerOptimizationProfile.Feature.ITEM_MERGE_THROTTLE)) {
			return false;
		}

		MinecraftServer server = item.level().getServer();
		if (server == null) {
			return false;
		}

		double pressure = PauCTickDebtController.pressure(server);
		int maxStretch = PauCRuntimeSwitches.readInt(MAX_STRETCH_KEY, 4, 1, 16);
		// stretch=1 when the tick is healthy (every scheduled scan runs), up to maxStretch under full debt.
		int stretch = 1 + (int) Math.round(pressure * (maxStretch - 1));
		if (stretch <= 1) {
			return false;
		}
		if (!engagedLogged) {
			engagedLogged = true;
			LOGGER.info("PauC item merge throttle engaged: stretching merge scans up to x{} under tick debt.", maxStretch);
		}
		// Phase-offset by entity id: a same-tick loot burst no longer scans its whole pile on one tick.
		int phase = (item.getId() & 0x7fffffff) % stretch;
		long scheduledRound = item.getAge() / 40L;
		return (scheduledRound % stretch) != phase;
	}
}
